package com.ayrotek.agent.service;

import com.ayrotek.agent.controller.IntegrationAgentController.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigInteger;
import java.sql.SQLException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kurum İçi Entegrasyon Ajanı Ana Orkestrasyon Servisi (On-Premise Facade Service).
 *
 * Sorumluluklar:
 * 1. Ajan kimlik ve durum yönetimi.
 * 2. Kripto, Veritabanı, ZKP ve Verifiable Credential servislerinin orkestrasyonu.
 * 3. MPC oturum belleği ve pay takas yönetimi (Round 1 & Round 2).
 */
@Service
public class IntegrationAgentService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationAgentService.class);

    public static final String INSTITUTION_ID = "Banka_A";
    public static final BigInteger PRIME = AgentCryptoService.PRIME;

    @Value("${agent.institution-id:${AGENT_ID:Banka_A}}")
    private String institutionId = "Banka_A";

    @Value("${agent.institution-name:${AGENT_NAME:Kurum İçi Entegrasyon Ajanı (On-Premise DMZ Node)}}")
    private String institutionName = "Kurum İçi Entegrasyon Ajanı (On-Premise DMZ Node)";

    @Value("${agent.default-secret:50000}")
    private String defaultSecret = "50000";

    @Value("${server.port:8083}")
    private int serverPort = 8083;

    @Value("${agent.attributes.income:50000}")
    private String defaultIncome;

    @Value("${agent.attributes.credit_score:1350}")
    private String defaultCreditScore;

    @Value("${agent.attributes.risk_level:LOW}")
    private String defaultRiskLevel;

    @Value("${agent.attributes.sample_size:150000}")
    private String defaultSampleSize;

    // Uzmanlaşmış Alt Servisler
    private final AgentCryptoService cryptoService;
    private final AgentDatabaseService databaseService;
    private final AgentZkpProverService zkpProverService;
    private final AgentChamberVcService chamberVcService;

    // Kurum İçi Yapılandırılabilir Nitelik Deposu
    private final Map<String, Object> localDatasetAttributes = new ConcurrentHashMap<>();

    // MPC Oturumu Bazında Alınan / Toplanan Paylar (JobId -> Alınan Paylar)
    private final Map<String, List<ReceiveShareRequest>> sessionReceivedShares = new ConcurrentHashMap<>();

    public IntegrationAgentService(AgentCryptoService cryptoService,
                                   AgentDatabaseService databaseService,
                                   AgentZkpProverService zkpProverService,
                                   AgentChamberVcService chamberVcService) {
        this.cryptoService = cryptoService;
        this.databaseService = databaseService;
        this.zkpProverService = zkpProverService;
        this.chamberVcService = chamberVcService;
    }

    @jakarta.annotation.PostConstruct
    public void init() {
        if (defaultSecret == null || defaultSecret.isBlank()) {
            defaultSecret = "50000";
        }
        localDatasetAttributes.put("dataset_id", "DS-" + getInstitutionId());
        localDatasetAttributes.put("institution_id", getInstitutionId());
        localDatasetAttributes.put("sample_size", defaultSampleSize != null ? defaultSampleSize : "150000");
        localDatasetAttributes.put("income", defaultIncome != null ? defaultIncome : defaultSecret);
        localDatasetAttributes.put("salary", defaultSecret);
        localDatasetAttributes.put("bakiye", defaultSecret);
        localDatasetAttributes.put("credit_score", defaultCreditScore != null ? defaultCreditScore : "1350");
        localDatasetAttributes.put("risk_level", defaultRiskLevel != null ? defaultRiskLevel : "LOW");
    }

    public String getInstitutionId() {
        return institutionId != null ? institutionId : INSTITUTION_ID;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public int getServerPort() {
        return serverPort;
    }

    public String getAnonCredsServiceUrl() {
        return zkpProverService.getAnonCredsServiceUrl();
    }

    public boolean isAnonCredsEngineAvailable() {
        return zkpProverService.isAnonCredsEngineAvailable();
    }

    public BigInteger queryLocalDatabaseMetric(String datasetName, String targetMetric) throws SQLException {
        return databaseService.queryLocalDatabaseMetric(datasetName, targetMetric, getInstitutionId());
    }

    public Long queryPredicateAttribute(String datasetName, String attrName) throws SQLException {
        return databaseService.queryPredicateAttribute(datasetName, attrName);
    }

    // -------------------------------------------------------------------------
    // AnonCreds ZKP İspat Üretimi
    // -------------------------------------------------------------------------
    public AgentZkpProofResponse generateZkpProof(AgentZkpProofRequest request) {
        return zkpProverService.generateZkpProof(request, getInstitutionId(), localDatasetAttributes);
    }

    // -------------------------------------------------------------------------
    // Shamir Payı Hesaplama ve Tüketici Açık Anahtarıyla Şifreleme
    // -------------------------------------------------------------------------
    public AgentComputeShareResponse computeAndEncryptShare(AgentComputeShareRequest request) {
        String targetMetric = (request != null && request.targetMetric() != null && !request.targetMetric().isBlank())
                ? request.targetMetric()
                : "income";

        BigInteger secret;
        if (request != null && request.overrideSecret() != null && request.overrideSecret().compareTo(BigInteger.ZERO) > 0) {
            secret = request.overrideSecret();
        } else {
            try {
                BigInteger dbVal = queryLocalDatabaseMetric("gelir_gider", targetMetric);
                if (dbVal != null) {
                    secret = dbVal;
                } else {
                    Map<String, Object> effectiveAttrs = new LinkedHashMap<>(localDatasetAttributes);
                    if (request != null && request.customAttributes() != null && !request.customAttributes().isEmpty()) {
                        effectiveAttrs.putAll(request.customAttributes());
                    }
                    Object val = effectiveAttrs.get(targetMetric);
                    secret = (val != null) ? new BigInteger(val.toString()) : new BigInteger(defaultSecret);
                }
            } catch (SQLException e) {
                log.error("[IntegrationAgent - {}] PostgreSQL Şema Hatası: {}", getInstitutionId(), e.getMessage());
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Veritabanı Şema Hatası: '" + targetMetric + "' kolonu " + getInstitutionId() + " kurumunda mevcut değildir! [" + e.getMessage() + "]"
                );
            }
        }

        int totalParties = (request != null && request.totalParties() > 0) ? request.totalParties() : 3;
        int threshold = (request != null && request.threshold() > 0) ? request.threshold() : (totalParties / 2 + 1);
        int partyId = (request != null && request.partyId() > 0) ? request.partyId() : 1;

        // Shamir polinom katsayıları
        BigInteger[] coeffs = cryptoService.generatePolynomialCoefficients(secret, threshold);

        // f(partyId) hesabı
        BigInteger shareVal = cryptoService.evaluatePolynomial(coeffs, partyId, threshold);

        // RSA-OAEP ile Tüketici Açık Anahtarına şifreleme
        String pubKey = (request != null && request.consumerPublicKey() != null && !request.consumerPublicKey().isBlank())
                ? request.consumerPublicKey()
                : cryptoService.getEffectivePublicKeyBase64();

        String encryptedData = cryptoService.encryptWithRsaOaep(pubKey, partyId + ":" + shareVal);
        String fingerprint = cryptoService.calculateFingerprint(pubKey);
        String jId = (request != null && request.jobId() != null) ? request.jobId() : "job-" + UUID.randomUUID().toString().substring(0, 8);

        return new AgentComputeShareResponse(
                getInstitutionId(),
                jId,
                partyId,
                threshold,
                totalParties,
                new EncryptedShamirShare(partyId, encryptedData, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"),
                fingerprint,
                "SHARE_COMPUTED_AND_ENCRYPTED_ON_PREMISE",
                "Ham veri (" + targetMetric + ") kurum sınırlarını terk etmedi. Shamir payı doğrudan tüketici açık anahtarıyla mühürlendi."
        );
    }

    // -------------------------------------------------------------------------
    // Shamir Payını Kabul Etme (Receive Share)
    // -------------------------------------------------------------------------
    public ReceiveShareResponse receiveShare(ReceiveShareRequest request) {
        if (request == null || request.jobId() == null || request.jobId().isBlank()) {
            throw new IllegalArgumentException("JobId boş olamaz!");
        }

        String jobId = request.jobId().trim();
        sessionReceivedShares.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>()).add(request);
        int total = sessionReceivedShares.get(jobId).size();

        return new ReceiveShareResponse(
                jobId,
                getInstitutionId(),
                request.sourceInstitutionId() != null ? request.sourceInstitutionId() : "EXTERNAL_PEER",
                request.senderPartyId(),
                total,
                "SHARE_RECEIVED_AND_REGISTERED",
                "Shamir payı kurum entegrasyon ajanı tarafından doğrulandı ve güvenli MPC oturum belleğine eklendi.",
                Instant.now().toString()
        );
    }

    public List<ReceiveShareRequest> getReceivedShares(String jobId) {
        return sessionReceivedShares.getOrDefault(jobId, Collections.emptyList());
    }

    public int getActiveSessionsCount() {
        return sessionReceivedShares.size();
    }

    // -------------------------------------------------------------------------
    // Bütünleşik Ajan İş Akışı (ZKP + Şifreli Pay)
    // -------------------------------------------------------------------------
    public AgentPipelineResponse executePipeline(AgentPipelineRequest request) {
        String jobId = (request != null && request.jobId() != null) ? request.jobId() : "job-" + UUID.randomUUID().toString().substring(0, 8);

        var zkpReq = new AgentZkpProofRequest(
                jobId,
                request != null ? request.nonce() : null,
                request != null ? request.requestedPredicates() : null,
                null,
                request != null ? request.customAttributes() : null,
                request != null ? request.schemaName() : null
        );
        var zkpProof = generateZkpProof(zkpReq);

        int totalParties = (request != null && request.totalParties() > 0) ? request.totalParties() : 3;
        int threshold = (request != null && request.threshold() > 0) ? request.threshold() : (totalParties / 2 + 1);
        int partyId = (request != null && request.partyId() > 0) ? request.partyId() : 1;

        var shareReq = new AgentComputeShareRequest(
                jobId,
                request != null ? request.consumerPublicKey() : null,
                partyId,
                threshold,
                totalParties,
                request != null ? request.overrideSecret() : null,
                request != null ? request.targetMetric() : "income",
                request != null ? request.customAttributes() : null
        );
        var shareResp = computeAndEncryptShare(shareReq);

        return new AgentPipelineResponse(
                getInstitutionId(),
                jobId,
                zkpProof,
                shareResp.consumerEncryptedShare(),
                "ON_PREMISE_PIPELINE_EXECUTED_SUCCESSFULLY",
                "Ham veri (" + (request != null && request.targetMetric() != null ? request.targetMetric() : "income") + ") asla kurum dışına çıkmadı. Tüketici için şifrelenmiş pay ve dinamik sıfır bilgi ispatı güvenle hazırlandı.",
                Instant.now().toString()
        );
    }

    // -------------------------------------------------------------------------
    // Dağıtık Shamir MPC: Round 1 - Başlatma, ZKP İspatı ve Paylara Bölme
    // -------------------------------------------------------------------------
    public AgentInitAndSplitResponse initAndSplit(AgentInitAndSplitRequest request) {
        if (request == null || request.jobId() == null || request.jobId().isBlank()) {
            throw new IllegalArgumentException("JobId boş olamaz!");
        }

        String jobId = request.jobId().trim();
        int partyId = request.partyId() > 0 ? request.partyId() : 1;
        int totalParties = request.totalParties() > 0 ? request.totalParties() : 3;
        int threshold = request.threshold() > 0 ? request.threshold() : (totalParties / 2 + 1);
        String targetMetric = (request.targetMetric() != null && !request.targetMetric().isBlank())
                ? request.targetMetric().trim()
                : "kar";
        String datasetName = (request.datasetName() != null && !request.datasetName().isBlank())
                ? request.datasetName().trim()
                : "gelir_gider";

        // 1. Kurumun Yerel Gizli Değerini Belirle (PostgreSQL veya Yapılandırma)
        BigInteger secret;
        Map<String, Object> queryAttrs = new LinkedHashMap<>(localDatasetAttributes);
        if (request.customAttributes() != null && !request.customAttributes().isEmpty()) {
            queryAttrs.putAll(request.customAttributes());
        }

        if (request.overrideSecret() != null && request.overrideSecret().compareTo(BigInteger.ZERO) > 0) {
            secret = request.overrideSecret();
            queryAttrs.put(targetMetric, secret.toString());
        } else {
            try {
                BigInteger dbVal = queryLocalDatabaseMetric(datasetName, targetMetric);
                if (dbVal != null) {
                    secret = dbVal;
                    queryAttrs.put(targetMetric, dbVal.toString());
                    log.info("[IntegrationAgent - {}] PostgreSQL'den gerçek yerel gizli değer çekildi: {} TL (Tablo: {}, Kolon: {})",
                            getInstitutionId(), secret, datasetName, targetMetric);
                } else {
                    Object val = queryAttrs.get(targetMetric);
                    secret = (val != null) ? new BigInteger(val.toString()) : new BigInteger(defaultSecret);
                }
            } catch (SQLException e) {
                log.error("[IntegrationAgent - {}] PostgreSQL Şema Hatası (Kolon veya Tablo Yok): {}", getInstitutionId(), e.getMessage());
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_REQUEST,
                        "Veritabanı Şema Hatası: '" + targetMetric + "' kolonu veya '" + datasetName + "' tablosu " + getInstitutionId() + " kurumunda mevcut değildir! [" + e.getMessage() + "]"
                );
            }
        }

        // 2. Kurum İçi AnonCreds ZKP İspatı Üret
        var zkpReq = new AgentZkpProofRequest(
                jobId,
                request.nonce(),
                request.requestedPredicates(),
                null,
                queryAttrs,
                request.schemaName(),
                datasetName
        );
        var zkpProof = generateZkpProof(zkpReq);

        log.info("[IntegrationAgent - {}] Oturum {} başlatıldı. Tablo: {}, Metrik: {}, Yerel Gizli Değer: {} (gizli tutuluyor), Eşik: {}, Toplam Paydaş: {}",
                getInstitutionId(), jobId, datasetName, targetMetric, secret, threshold, totalParties);

        // 3. Shamir Polinom Katsayılarını Üret
        BigInteger[] coeffs = cryptoService.generatePolynomialCoefficients(secret, threshold);

        // 4. Tüm Katılımcılar İçin Payları Hesapla
        List<SharePacket> outgoingShares = new ArrayList<>();
        for (int x = 1; x <= totalParties; x++) {
            BigInteger shareVal = cryptoService.evaluatePolynomial(coeffs, x, threshold);

            if (x == partyId) {
                // Kendi payını doğrudan kendi bellek alanına kaydet
                receiveShare(new ReceiveShareRequest(
                        jobId,
                        getInstitutionId(),
                        partyId,
                        partyId,
                        shareVal,
                        false,
                        null,
                        "SHAMIR_SECRET_SHARING",
                        Map.of("selfShare", "true")
                ));
            } else {
                outgoingShares.add(new SharePacket(partyId, x, shareVal));
            }
        }

        return new AgentInitAndSplitResponse(
                getInstitutionId(),
                partyId,
                zkpProof,
                outgoingShares,
                "INITIALIZED_AND_SPLIT_SUCCESSFULLY"
        );
    }

    // -------------------------------------------------------------------------
    // Dağıtık Shamir MPC: Round 2 - Toplanan Payları Birleştirme ve Tüketici Şifreleme
    // -------------------------------------------------------------------------
    public AgentAggregateResponse aggregateAndEncrypt(AgentAggregateRequest request) {
        if (request == null || request.jobId() == null || request.jobId().isBlank()) {
            throw new IllegalArgumentException("JobId boş olamaz!");
        }

        String jobId = request.jobId().trim();
        int partyId = request.partyId() > 0 ? request.partyId() : 1;
        String pubKey = (request.consumerPublicKey() != null && !request.consumerPublicKey().isBlank())
                ? request.consumerPublicKey()
                : cryptoService.getEffectivePublicKeyBase64();

        List<ReceiveShareRequest> shares = sessionReceivedShares.getOrDefault(jobId, Collections.emptyList());
        if (shares.isEmpty()) {
            throw new IllegalStateException("Oturum için (" + jobId + ") henüz hiç pay toplanmadı!");
        }

        // Homomorfik toplama: S_i = sum(shareValue) mod p
        BigInteger localSum = BigInteger.ZERO;
        for (ReceiveShareRequest s : shares) {
            if (s.shareValue() != null) {
                localSum = localSum.add(s.shareValue()).mod(PRIME);
            }
        }

        log.info("[IntegrationAgent - {}] Oturum {} için {} adet pay toplandı. Toplam pay (S_{}): {} (mod p)",
                getInstitutionId(), jobId, shares.size(), partyId, localSum);

        // Tüketici Açık Anahtarı ile RSA-OAEP şifreleme
        String payload = partyId + ":" + localSum;
        String encryptedData = cryptoService.encryptWithRsaOaep(pubKey, payload);

        var encShare = new EncryptedShamirShare(
                partyId,
                encryptedData,
                "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"
        );

        return new AgentAggregateResponse(
                getInstitutionId(),
                partyId,
                encShare,
                shares.size(),
                "AGGREGATED_AND_ENCRYPTED_SUCCESSFULLY"
        );
    }

    // -------------------------------------------------------------------------
    // Güvenli Veri Odası (Clean Room) ve Şema Delegasyonu
    // -------------------------------------------------------------------------
    public Map<String, Object> mintChamberVc(String chamberId, String category, String tableName) {
        return chamberVcService.mintChamberVc(chamberId, category, tableName, getInstitutionId(), getInstitutionName(), getServerPort());
    }

    public Map<String, Object> getLocalDatabaseOverview() {
        return databaseService.getLocalDatabaseOverview(getInstitutionId(), getInstitutionName(), getServerPort());
    }

    public Map<String, Object> mintCustomChamberVc(Map<String, Object> req) {
        return chamberVcService.mintCustomChamberVc(req, getInstitutionId(), getInstitutionName(), getServerPort());
    }
}
