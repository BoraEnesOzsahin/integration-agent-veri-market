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

    // Oturum başına beklenen toplam katılımcı sayısı (eksik pay ile toplama yapılmasını engeller)
    private final Map<String, Integer> expectedPartiesByJob = new ConcurrentHashMap<>();

    // Eş-ler arası (agent -> agent) şifreli pay taşıma
    private final java.net.http.HttpClient peerHttp = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();
    private final com.fasterxml.jackson.databind.ObjectMapper peerMapper = new com.fasterxml.jackson.databind.ObjectMapper();
    private final Map<String, String> peerKeyCache = new ConcurrentHashMap<>();

    /** Virgülle ayrılmış izinli eş taban URL öneki listesi. Boşsa kısıtlama yok (YALNIZCA demo). Üretimde mutlaka doldurulmalı. */
    @Value("${agent.mpc.peer-allowlist:}")
    private String peerAllowlist = "";

    /**
     * Sabitlenmiş (pinned) platform/koordinatör Ed25519 açık anahtarı (X.509, hex). Doluysa her init-and-split
     * isteği, koordinatörce imzalanmış geçerli bir iş bildirimi taşımak ZORUNDADIR ve paylar yalnızca bildirimde
     * adı geçen eşlere gider (allowlist gerekmez). Anahtar: GET <koordinatör>/api/v1/vc/platform-public-key
     */
    @Value("${agent.coordinator.public-key:}")
    private String coordinatorPublicKeyHex = "";

    /** Açıksa kurum yalnızca kayıtlı bir tüketicinin imzaladığı sorguyu çalıştırır; eski imzasız uçlar kapanır. */
    @Value("${AGENT_REQUIRE_CONSUMER_AUTH:false}")
    private boolean requireConsumerAuth = false;

    /** Kurumun onayladığı tüketiciler: kimlik:anahtarHex,kimlik:anahtarHex */
    @Value("${AGENT_TRUSTED_CONSUMERS:}")
    private String trustedConsumers = "";

    private static final long MAX_AUTHORIZATION_SECONDS = 900;

    private final Map<String, Long> usedQueryIds = new ConcurrentHashMap<>();

    /** Doğrulanmış sorgu yetkisi: tüketicinin şifreleme anahtarı özeti ve katılan kurumların anahtarları. */
    private record AuthorizedQuery(String consumerId, String consumerKeyHash, Map<String, String> institutions) {}

    public void requireLegacyEndpointsAllowed() {
        if (requireConsumerAuth) {
            throw forbidden("Bu uç, tüketici yetkisi zorunluyken kapalıdır.");
        }
    }

    private static org.springframework.web.server.ResponseStatusException forbidden(String message) {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, message);
    }

    static boolean ed25519Valid(String publicKeyHex, String payload, String signatureHex) {
        try {
            java.security.PublicKey pk = java.security.KeyFactory.getInstance("Ed25519").generatePublic(
                    new java.security.spec.X509EncodedKeySpec(hexToBytes(publicKeyHex.trim())));
            java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
            sig.initVerify(pk);
            sig.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return sig.verify(hexToBytes(signatureHex.trim()));
        } catch (Exception e) {
            return false;
        }
    }

    /** cq1|tüketici|sorgu|bitiş|anahtarÖzeti|tablo|sütun|payda|işlem|çıktı|karşılaştırma|eşik|kurum=anahtar,kurum=anahtar */
    private AuthorizedQuery verifyConsumerAuthorization(ConsumerAuthorization auth, String datasetName, String targetMetric,
                                                        QuerySpec spec, int totalParties) {
        if (!requireConsumerAuth) {
            return null;
        }
        if (auth == null || auth.payload() == null || auth.signature() == null) {
            throw forbidden("Sorgu, kayıtlı bir tüketicinin imzasını taşımıyor.");
        }
        String[] f = auth.payload().split("\\|", -1);
        if (f.length != 13 || !"cq1".equals(f[0])) {
            throw forbidden("Tüketici yetkisinin biçimi tanınmadı.");
        }
        String signer = null;
        for (String entry : (trustedConsumers == null ? "" : trustedConsumers).split(",")) {
            String[] kv = entry.trim().split(":", 2);
            if (kv.length == 2 && kv[0].equals(f[1])) {
                signer = kv[1];
            }
        }
        if (signer == null) {
            throw forbidden("Tüketici bu kurumda kayıtlı değil: " + f[1]);
        }
        if (!ed25519Valid(signer, auth.payload(), auth.signature())) {
            throw forbidden("Tüketici imzası geçersiz.");
        }
        long now = Instant.now().getEpochSecond();
        long expires;
        try {
            expires = Long.parseLong(f[3]);
        } catch (NumberFormatException e) {
            throw forbidden("Tüketici yetkisinin süresi okunamadı.");
        }
        if (expires < now || expires > now + MAX_AUTHORIZATION_SECONDS) {
            throw forbidden("Tüketici yetkisinin süresi geçersiz.");
        }
        if (!f[5].equals(datasetName) || !f[6].equals(targetMetric) || !f[7].equals(spec.denominator()) || !f[8].equals(spec.operation())
                || !f[9].equals(spec.output()) || !f[10].equals(spec.comparator()) || !f[11].equals(spec.threshold())) {
            throw forbidden("Sorgu, tüketicinin imzaladığı sorguyla uyuşmuyor.");
        }
        Map<String, String> institutions = new LinkedHashMap<>();
        for (String entry : f[12].split(",")) {
            String[] kv = entry.split("=", 2);
            if (kv.length == 2) {
                institutions.put(kv[0], kv[1]);
            }
        }
        if (institutions.size() != totalParties) {
            throw forbidden("Kurum sayısı, tüketicinin imzaladığı listeyle uyuşmuyor.");
        }
        if (!chamberVcService.attestationPublicKeyHex().equals(institutions.get(getInstitutionId()))) {
            throw forbidden("Bu kurum, tüketicinin imzaladığı listede bu anahtarla yok.");
        }
        usedQueryIds.values().removeIf(exp -> exp < now);
        if (usedQueryIds.putIfAbsent(f[2], expires) != null) {
            throw forbidden("Bu tüketici yetkisi daha önce kullanılmış.");
        }
        return new AuthorizedQuery(f[1], f[4], institutions);
    }

    /** Sahte taraf engeli: her taraf sertifikası, tüketicinin imzaladığı listedeki farklı bir kurumca imzalanmış olmalı. */
    static void verifyPeerCertificates(String jobId, int totalParties, Map<Integer, String> certs, Map<Integer, String> signatures,
                                       Map<Integer, String> certInstitutions, Map<String, String> institutionKeys,
                                       java.util.function.UnaryOperator<String> sha256Hex) {
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (int partyId = 1; partyId <= totalParties; partyId++) {
            String cert = certs != null ? certs.get(partyId) : null;
            String signature = signatures != null ? signatures.get(partyId) : null;
            String institution = certInstitutions != null ? certInstitutions.get(partyId) : null;
            String key = institution != null ? institutionKeys.get(institution) : null;
            if (cert == null || signature == null || key == null || !seen.add(institution)
                    || !ed25519Valid(key, certPayload(jobId, partyId, sha256Hex.apply(cert)), signature)) {
                throw forbidden("Party " + partyId + " için kurum imzalı sertifika doğrulanamadı.");
            }
        }
    }

    static String certPayload(String jobId, int partyId, String certHash) {
        return "cert1|" + jobId + "|" + partyId + "|" + certHash;
    }

    /** jobId -> o iş için koordinatörün onayladığı eş taban URL'leri. */
    private final Map<String, java.util.Set<String>> approvedPeersByJob = new ConcurrentHashMap<>();

    private void verifyJobManifest(AgentInitAndSplitRequest request, String jobId) {
        if (coordinatorPublicKeyHex == null || coordinatorPublicKeyHex.isBlank()) {
            return; // eski mod: allowlist (veya kısıtsız demo)
        }
        String manifest = request.jobManifest();
        String sigHex = request.jobManifestSignature();
        if (manifest == null || sigHex == null || manifest.isBlank() || sigHex.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "İmzalı iş bildirimi (jobManifest) zorunlu.");
        }
        verifyCoordinatorSignature(manifest, sigHex);
        String[] f = manifest.split("\\|", -1);
        if (f.length != 5 || !f[0].equals(jobId)
                || Integer.parseInt(f[1]) != request.threshold() || Integer.parseInt(f[2]) != request.totalParties()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "İş bildirimi istekle uyuşmuyor (jobId/eşik/n).");
        }
        if (Long.parseLong(f[3]) < java.time.Instant.now().getEpochSecond()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "İş bildirimi süresi dolmuş.");
        }
        java.util.Set<String> approved = new java.util.HashSet<>();
        Map<Integer, String> inManifest = new java.util.HashMap<>();
        for (String kv : f[4].split(",")) {
            if (kv.isBlank()) continue;
            String[] p = kv.split("=", 2);
            inManifest.put(Integer.parseInt(p[0]), p[1]);
            approved.add(p[1]);
        }
        Map<Integer, String> asked = new java.util.HashMap<>();
        if (request.peerEndpoints() != null) {
            request.peerEndpoints().forEach((k, v) -> asked.put(k, v.replaceAll("/+$", "")));
        }
        if (!asked.equals(inManifest)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "peerEndpoints imzalı bildirimle uyuşmuyor.");
        }
        approvedPeersByJob.put(jobId, approved);
    }

    private void verifyCoordinatorSignature(String payload, String sigHex) {
        try {
            java.security.PublicKey pk = java.security.KeyFactory.getInstance("Ed25519").generatePublic(
                    new java.security.spec.X509EncodedKeySpec(hexToBytes(coordinatorPublicKeyHex.trim())));
            java.security.Signature sig = java.security.Signature.getInstance("Ed25519");
            sig.initVerify(pk);
            sig.update(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            if (!sig.verify(hexToBytes(sigHex.trim()))) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "İş bildirimi imzası geçersiz.");
            }
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "İş bildirimi doğrulanamadı: " + e.getMessage());
        }
    }

    private static byte[] hexToBytes(String h) {
        byte[] out = new byte[h.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

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
        return computeAndEncryptShare(request, "gelir_gider");
    }

    private AgentComputeShareResponse computeAndEncryptShare(AgentComputeShareRequest request, String datasetName) {
        String targetMetric = (request != null && request.targetMetric() != null && !request.targetMetric().isBlank())
                ? request.targetMetric()
                : "income";

        BigInteger secret;
        if (request != null && request.overrideSecret() != null && request.overrideSecret().compareTo(BigInteger.ZERO) > 0) {
            secret = request.overrideSecret();
        } else {
            try {
                BigInteger dbVal = queryLocalDatabaseMetric(datasetName, targetMetric);
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
        List<ReceiveShareRequest> sessionList = sessionReceivedShares.computeIfAbsent(jobId, k -> new CopyOnWriteArrayList<>());
        int total;
        synchronized (sessionList) {
            boolean duplicate = sessionList.stream().anyMatch(r -> r.senderPartyId() == request.senderPartyId());
            if (duplicate) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.CONFLICT,
                        "Bu oturum için Party " + request.senderPartyId() + " payı zaten alındı (çift sayım engellendi).");
            }
            sessionList.add(request);
            total = sessionList.size();
        }

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
        if (request != null) {
            requireLocalInput(request.overrideSecret());
        }
        String datasetName = (request != null && request.datasetName() != null && !request.datasetName().isBlank())
                ? request.datasetName().trim()
                : "gelir_gider";
        // Yetki, ZKP ve kurum verisinden önce denetlenir
        String metric = request != null && request.targetMetric() != null ? request.targetMetric().trim() : "income";
        String consumerKey = request != null ? request.consumerPublicKey() : null;
        QuerySpec spec = QuerySpec.of(request != null ? request.operation() : null, request != null ? request.denominatorMetric() : null,
                request != null ? request.outputMode() : null, request != null ? request.comparator() : null,
                request != null ? request.thresholdValue() : null);
        AuthorizedQuery authorized = request != null
                ? verifyConsumerAuthorization(request.consumerAuthorization(), datasetName, metric, spec, 1)
                : null;
        if (authorized != null && (consumerKey == null || !authorized.consumerKeyHash().equals(cryptoService.sha256Hex(consumerKey)))) {
            throw forbidden("Tüketici anahtarı, tüketicinin imzaladığı anahtar değil.");
        }
        if (requireConsumerAuth && authorized == null) {
            throw forbidden("Sorgu, kayıtlı bir tüketicinin imzasını taşımıyor.");
        }

        var zkpReq = new AgentZkpProofRequest(
                jobId,
                request != null ? request.nonce() : null,
                request != null ? request.requestedPredicates() : null,
                null,
                request != null ? request.customAttributes() : null,
                request != null ? request.schemaName() : null,
                datasetName
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
        EncryptedShamirShare consumerShare;
        if (authorized != null || (consumerKey != null && !consumerKey.isBlank() && request.operation() != null)) {
            // Tek kurumda MPC yoktur: sonuç kurumda hesaplanır, doğrudan tüketici anahtarına şifrelenir
            String encrypted = cryptoService.encryptWithRsaOaep(consumerKey, partyId + ":" + spec.localResult(
                    readMetric(datasetName, metric), spec.isRatio() ? readMetric(datasetName, spec.denominator()) : BigInteger.ONE));
            consumerShare = new EncryptedShamirShare(partyId, encrypted, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
        } else {
            consumerShare = computeAndEncryptShare(shareReq, datasetName).consumerEncryptedShare();
        }
        AgentAttestation attestation = (consumerKey == null || consumerKey.isBlank()) ? null
                : attest(jobId, partyId, totalParties, threshold, "LOCAL_SINGLE_NODE", datasetName, metric, spec, consumerKey,
                        consumerShare.encryptedData(), null);

        return new AgentPipelineResponse(
                getInstitutionId(),
                jobId,
                zkpProof,
                consumerShare,
                "ON_PREMISE_PIPELINE_EXECUTED_SUCCESSFULLY",
                "Ham veri (" + (request != null && request.targetMetric() != null ? request.targetMetric() : "income") + ") asla kurum dışına çıkmadı. Tüketici için şifrelenmiş pay ve dinamik sıfır bilgi ispatı güvenle hazırlandı.",
                Instant.now().toString(),
                attestation
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
        verifyJobManifest(request, jobId);
        int partyId = request.partyId() > 0 ? request.partyId() : 1;
        int totalParties = request.totalParties() > 0 ? request.totalParties() : 3;
        int threshold = request.threshold() > 0 ? request.threshold() : (totalParties / 2 + 1);

        LocalSecret local = resolveSecretAndProve(request, jobId);
        BigInteger secret = local.secret();
        var zkpProof = local.proof();

        log.info("[IntegrationAgent - {}] Oturum {} başlatıldı. Eşik: {}, Toplam Paydaş: {}",
                getInstitutionId(), jobId, threshold, totalParties);

        // 3. Shamir Polinom Katsayılarını Üret
        BigInteger[] coeffs = cryptoService.generatePolynomialCoefficients(secret, threshold);

        // 4. Tüm Katılımcılar İçin Payları Hesapla
        expectedPartiesByJob.put(jobId, totalParties);
        Map<Integer, String> peers = request.peerEndpoints() != null ? request.peerEndpoints() : Collections.emptyMap();
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
                String peerUrl = peers.get(x);
                if (peerUrl == null || peerUrl.isBlank()) {
                    throw new org.springframework.web.server.ResponseStatusException(
                            org.springframework.http.HttpStatus.BAD_REQUEST,
                            "Party " + x + " için eş ajan adresi (peerEndpoints) verilmedi; pay koordinatör üzerinden düz metin taşınmaz.");
                }
                sendShareToPeer(jobId, partyId, x, peerUrl, shareVal);
            }
        }
        // Koordinatöre HİÇBİR pay dönmez: paylar eşlere doğrudan ve şifreli gönderildi.
        return new AgentInitAndSplitResponse(
                getInstitutionId(),
                partyId,
                zkpProof,
                new ArrayList<SharePacket>(),
                "INITIALIZED_AND_SPLIT_SUCCESSFULLY_P2P"
        );
    }

    private record LocalSecret(BigInteger secret, AgentZkpProofResponse proof) {}

    /** Kurumun yerel gizli değerini belirler (PostgreSQL veya yapılandırma) ve o değer üzerinde ZKP ispatı üretir. */
    private LocalSecret resolveSecretAndProve(AgentInitAndSplitRequest request, String jobId) {
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
                    // ZKP yalnızca koşulu olan sütunu taşır: AnonCreds negatif değer kabul etmez, zararlı toplam kanıtı düşürmemeli
                    if (request.requestedPredicates() != null
                            && request.requestedPredicates().stream().anyMatch(p -> p != null && targetMetric.equals(p.name()))) {
                        queryAttrs.put(targetMetric, dbVal.toString());
                    }
                    log.info("[IntegrationAgent - {}] PostgreSQL'den yerel gizli değer çekildi (Tablo: {}, Kolon: {})",
                            getInstitutionId(), datasetName, targetMetric);
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
        return new LocalSecret(secret, generateZkpProof(zkpReq));
    }

    // -------------------------------------------------------------------------
    // Gerçek MP-SPDZ MPC: her kurum yalnızca KENDİ tarafını kendi makinesinde çalıştırır
    // -------------------------------------------------------------------------
    private static final BigInteger RING_MODULUS = BigInteger.ONE.shiftLeft(64);

    /** Kurumun yanında çalışan MP-SPDZ köprüsü. Dışarıya açılmaz, yalnızca bu ajan çağırır. */
    @Value("${agent.mpc.engine-url:http://localhost:50051}")
    private String mpcEngineUrl = "http://localhost:50051";

    private record EngineSession(BigInteger secret, BigInteger mask, String protocol, int partyId, int totalParties,
                                 int threshold, List<String> hosts, String certPem, long expiresAtEpochSec,
                                 String datasetName, String targetMetric, BigInteger denominator, QuerySpec spec,
                                 AuthorizedQuery authorized) {}

    /** Devrede kullanılan sabit noktalı ölçek ve "payda sıfır" işareti; mp_spdz_service.py ile aynı olmalı. */
    private static final BigInteger FIXED_SCALE = BigInteger.ONE.shiftLeft(16);
    private static final BigInteger RATIO_UNDEFINED = BigInteger.ONE.shiftLeft(52);

    /** İşlem ve çıktı biçimi. Kurum bunu imzalar: koordinatör sorguyu değiştirirse tüketici fark eder. */
    private record QuerySpec(String operation, String denominator, String output, String comparator, String threshold) {

        static QuerySpec of(String operation, String denominator, String output, String comparator, String threshold) {
            String op = operation == null || operation.isBlank() ? "SUM" : operation.trim().toUpperCase();
            String out = output == null || output.isBlank() ? "VALUE" : output.trim().toUpperCase();
            if (!List.of("SUM", "PRODUCT", "RATIO").contains(op) || !List.of("VALUE", "THRESHOLD").contains(out)) {
                throw badQuery("Desteklenmeyen işlem veya çıktı: " + op + "/" + out);
            }
            String den = "";
            if ("RATIO".equals(op)) {
                den = denominator == null ? "" : denominator.trim();
                if (!den.matches("^[a-zA-Z0-9_]+$")) {
                    throw badQuery("Oran için payda sütunu gerekli.");
                }
            }
            String cmp = "";
            String limit = "";
            if ("THRESHOLD".equals(out)) {
                cmp = comparator == null ? "" : comparator.trim();
                limit = threshold == null ? "" : threshold.trim();
                if (!List.of(">", ">=", "<", "<=").contains(cmp)) {
                    throw badQuery("Eşik karşılaştırması geçersiz: " + cmp);
                }
                if (!limit.matches("RATIO".equals(op) ? "^-?\\d{1,12}(\\.\\d{1,4})?$" : "^-?\\d{1,18}$")) {
                    throw badQuery("Eşik değeri geçersiz: " + limit);
                }
            }
            return new QuerySpec(op, den, out, cmp, limit);
        }

        private static org.springframework.web.server.ResponseStatusException badQuery(String message) {
            return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, message);
        }

        boolean isRatio() {
            return "RATIO".equals(operation);
        }

        boolean isThreshold() {
            return "THRESHOLD".equals(output);
        }

        /** Devreye giden eşik: orandaki ondalık eşik sabit noktalı ölçeğe çevrilir. */
        String engineThreshold() {
            java.math.BigDecimal value = new java.math.BigDecimal(threshold);
            return isRatio()
                    ? value.multiply(new java.math.BigDecimal(FIXED_SCALE)).setScale(0, java.math.RoundingMode.HALF_EVEN).toPlainString()
                    : value.toPlainString();
        }

        /** Tek kurum: devrenin vereceği sonucun aynısı. */
        BigInteger localResult(BigInteger value, BigInteger denominatorValue) {
            boolean undefined = isRatio() && denominatorValue.signum() == 0;
            BigInteger result = isRatio() && !undefined
                    ? value.multiply(FIXED_SCALE).divide(denominatorValue)
                    : (undefined ? RATIO_UNDEFINED : value);
            if (!isThreshold()) {
                return result;
            }
            if (undefined) {
                return BigInteger.TWO;
            }
            int c = result.compareTo(new BigInteger(engineThreshold()));
            boolean ok = switch (comparator) {
                case ">" -> c > 0;
                case ">=" -> c >= 0;
                case "<" -> c < 0;
                default -> c <= 0;
            };
            return ok ? BigInteger.ONE : BigInteger.ZERO;
        }
    }

    private BigInteger readMetric(String datasetName, String column) {
        try {
            BigInteger value = queryLocalDatabaseMetric(datasetName, column);
            if (value == null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Kurum veritabanına bağlanılamadı.");
            }
            return value;
        } catch (SQLException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Veritabanı Şema Hatası: '" + column + "' sütunu veya '" + datasetName + "' tablosu " + getInstitutionId() + " kurumunda yok. [" + e.getMessage() + "]");
        }
    }

    /** Koordinatör bir kurumun girdisini belirleyemez: girdi yalnızca kurumun kendi veritabanından gelir. */
    private void requireLocalInput(BigInteger overrideSecret) {
        if (coordinatorPublicKeyHex == null || coordinatorPublicKeyHex.isBlank()) {
            return; // eski mod: sabitlenmiş koordinatör anahtarı yok
        }
        if (overrideSecret != null) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Kurum girdisi dışarıdan verilemez.");
        }
        if (!databaseService.isDbConnected()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Kurum veritabanına bağlanılamadı; varsayılan değerle hesaplama yapılmaz.");
        }
    }

    /** Sonuç bildirimi: v2|kurum|iş|taraf|n|eşik|protokol|tablo|sütun|payda|işlem|çıktı|karşılaştırma|eşik değeri|anahtar özeti|maske özeti|maskeli sonuç */
    private AgentAttestation attest(String jobId, int partyId, int totalParties, int threshold, String protocol,
                                    String datasetName, String targetMetric, QuerySpec spec, String consumerKey, String encrypted, String masked) {
        String payload = String.join("|", "v2", getInstitutionId(), jobId, String.valueOf(partyId), String.valueOf(totalParties),
                String.valueOf(threshold), protocol, datasetName, targetMetric, spec.denominator(), spec.operation(), spec.output(),
                spec.comparator(), spec.threshold(), cryptoService.sha256Hex(consumerKey),
                cryptoService.sha256Hex(encrypted), masked != null ? masked : "");
        return new AgentAttestation(payload, chamberVcService.signAttestation(payload));
    }

    public Map<String, String> getAttestationKey() {
        return Map.of(
                "institutionId", getInstitutionId(),
                "algorithm", "Ed25519",
                "publicKeyHex", chamberVcService.attestationPublicKeyHex());
    }

    private final Map<String, EngineSession> engineSessions = new ConcurrentHashMap<>();

    public AgentEnginePrepareResponse enginePrepare(AgentEnginePrepareRequest request) {
        if (request == null || request.init() == null || request.init().jobId() == null || request.init().jobId().isBlank()) {
            throw new IllegalArgumentException("JobId boş olamaz!");
        }
        AgentInitAndSplitRequest init = request.init();
        String jobId = init.jobId().trim();
        verifyJobManifest(init, jobId);
        requireLocalInput(init.overrideSecret());
        String protocol = request.protocol();
        if (!"malicious-shamir".equals(protocol) && !"semi2k".equals(protocol)) {
            throw new IllegalArgumentException("Desteklenmeyen MPC protokolü: " + protocol);
        }
        int n = init.totalParties();
        int partyId = init.partyId();
        Map<Integer, String> peers = init.peerEndpoints() != null ? init.peerEndpoints() : Collections.emptyMap();
        List<String> hosts = new ArrayList<>();
        for (int x = 1; x <= n; x++) {
            String url = peers.get(x);
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("Party " + x + " için adres (peerEndpoints) verilmedi.");
            }
            String base = url.replaceAll("/+$", "");
            assertPeerAllowed(jobId, base);
            String host = java.net.URI.create(base).getHost();
            if (host == null) {
                throw new IllegalArgumentException("Party " + x + " adresi çözümlenemedi: " + url);
            }
            hosts.add(host);
        }

        QuerySpec spec = QuerySpec.of(init.operation(), init.denominatorMetric(), init.outputMode(), init.comparator(), init.thresholdValue());
        String datasetName = init.datasetName() != null && !init.datasetName().isBlank() ? init.datasetName().trim() : "gelir_gider";
        String metricName = init.targetMetric() != null && !init.targetMetric().isBlank() ? init.targetMetric().trim() : "kar";
        // Yetki, kurum verisine dokunmadan önce denetlenir
        AuthorizedQuery authorized = verifyConsumerAuthorization(init.consumerAuthorization(), datasetName, metricName, spec, n);
        LocalSecret local = resolveSecretAndProve(init, jobId);
        BigInteger denominator = spec.isRatio() ? readMetric(datasetName, spec.denominator()) : BigInteger.ZERO;

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("partyId", partyId - 1); // MP-SPDZ tarafları 0'dan başlar
        body.put("numParties", n);
        body.put("protocol", protocol);
        putQuery(body, spec);
        String certPem = callEngine("/api/v1/mpc/party-prepare", body, 240).path("certPem").asText();

        long now = Instant.now().getEpochSecond();
        engineSessions.values().removeIf(s -> s.expiresAtEpochSec() < now);
        engineSessions.put(jobId, new EngineSession(local.secret(), newMask(protocol), protocol, partyId, n,
                init.threshold(), hosts, certPem, now + 600, datasetName, metricName, denominator, spec, authorized));
        String certSignature = chamberVcService.signAttestation(certPayload(jobId, partyId, cryptoService.sha256Hex(certPem)));
        return new AgentEnginePrepareResponse(getInstitutionId(), partyId, local.proof(), certPem, "PREPARED_FOR_MP_SPDZ", certSignature);
    }

    public AgentEngineRunResponse engineRun(AgentEngineRunRequest request) {
        if (request == null || request.jobId() == null || request.jobId().isBlank()) {
            throw new IllegalArgumentException("JobId boş olamaz!");
        }
        String jobId = request.jobId().trim();
        String consumerKey = request.consumerPublicKey();
        if (consumerKey == null || consumerKey.isBlank()) {
            throw new IllegalArgumentException("Tüketici açık anahtarı zorunludur.");
        }
        EngineSession session = engineSessions.remove(jobId);
        if (session == null) {
            throw new IllegalStateException("Bu iş için hazırlık yok veya iş zaten çalıştırıldı: " + jobId);
        }
        Map<Integer, String> certs = request.certs() != null ? request.certs() : Collections.emptyMap();
        if (!session.certPem().equals(certs.get(session.partyId()))) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Bu kurumun iş sertifikası değiştirilmiş.");
        }
        verifyRunManifest(request, session, jobId);
        if (session.authorized() != null) {
            if (!session.authorized().consumerKeyHash().equals(cryptoService.sha256Hex(consumerKey))) {
                throw forbidden("Tüketici anahtarı, tüketicinin imzaladığı anahtar değil.");
            }
            verifyPeerCertificates(jobId, session.totalParties(), certs, request.certSignatures(), request.certInstitutions(),
                    session.authorized().institutions(), cryptoService::sha256Hex);
        }

        Map<String, String> engineCerts = new LinkedHashMap<>();
        certs.forEach((k, v) -> engineCerts.put(String.valueOf(k - 1), v));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("partyId", session.partyId() - 1);
        body.put("numParties", session.totalParties());
        body.put("threshold", session.threshold());
        body.put("protocol", session.protocol());
        putQuery(body, session.spec());
        body.put("input", session.secret().toString());
        body.put("denominator", session.denominator().toString());
        body.put("mask", session.mask().toString());
        body.put("hosts", session.hosts());
        body.put("certs", engineCerts);
        body.put("basePort", request.basePort());
        var result = callEngine("/api/v1/mpc/party-run", body, 200);

        String masked = result.path("maskedResult").isNull() ? null : result.path("maskedResult").asText(null);
        String encrypted = cryptoService.encryptWithRsaOaep(consumerKey, session.partyId() + ":" + session.mask());
        log.info("[IntegrationAgent - {}] MP-SPDZ işi {} tamamlandı (Protokol: {}, Taraf: {}/{}).",
                getInstitutionId(), jobId, session.protocol(), session.partyId(), session.totalParties());
        return new AgentEngineRunResponse(getInstitutionId(), session.partyId(), masked,
                new EncryptedShamirShare(session.partyId(), encrypted, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"),
                "MP_SPDZ_PARTY_COMPLETED",
                attest(jobId, session.partyId(), session.totalParties(), session.threshold(), session.protocol(),
                        session.datasetName(), session.targetMetric(), session.spec(), consumerKey, encrypted, masked));
    }

    private static void putQuery(Map<String, Object> body, QuerySpec spec) {
        body.put("operation", spec.operation());
        body.put("outputMode", spec.output());
        if (spec.isThreshold()) {
            body.put("comparator", spec.comparator());
            body.put("compareValue", spec.engineThreshold());
        }
    }

    private void verifyRunManifest(AgentEngineRunRequest request, EngineSession session, String jobId) {
        if (coordinatorPublicKeyHex == null || coordinatorPublicKeyHex.isBlank()) {
            return; // eski mod: sabitlenmiş koordinatör anahtarı yok
        }
        String manifest = request.runManifest();
        String sigHex = request.runManifestSignature();
        if (manifest == null || sigHex == null || manifest.isBlank() || sigHex.isBlank()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "İmzalı çalışma bildirimi (runManifest) zorunlu.");
        }
        verifyCoordinatorSignature(manifest, sigHex);
        StringBuilder certHashes = new StringBuilder();
        new java.util.TreeMap<>(request.certs()).forEach((k, v) -> {
            if (certHashes.length() > 0) certHashes.append(',');
            certHashes.append(k).append('=').append(cryptoService.sha256Hex(v));
        });
        String expected = jobId + "|" + session.protocol() + "|" + session.threshold() + "|" + request.basePort() + "|"
                + cryptoService.sha256Hex(request.consumerPublicKey()) + "|" + certHashes;
        if (!expected.equals(manifest)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Çalışma bildirimi istekle uyuşmuyor.");
        }
    }

    /** Sonucun alanından (halka 128, alan 255 bit) belirgin biçimde uzun maske: sonuç + maske, sonuç hakkında bilgi vermez. */
    private static BigInteger newMask(String protocol) {
        return new BigInteger("semi2k".equals(protocol) ? 186 : 310, new java.security.SecureRandom());
    }

    private com.fasterxml.jackson.databind.JsonNode callEngine(String path, Map<String, Object> body, int timeoutSeconds) {
        try {
            var req = java.net.http.HttpRequest.newBuilder()
                    .uri(java.net.URI.create(mpcEngineUrl.replaceAll("/+$", "") + path))
                    .timeout(java.time.Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(peerMapper.writeValueAsString(body)))
                    .build();
            var resp = peerHttp.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "MP-SPDZ motoru hata döndü (HTTP " + resp.statusCode() + "): " + resp.body());
            }
            return peerMapper.readTree(resp.body());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("MP-SPDZ çağrısı kesildi.", e);
        } catch (java.io.IOException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_GATEWAY,
                    "MP-SPDZ motoruna ulaşılamadı (" + mpcEngineUrl + "): " + e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Ajanlar arası doğrudan şifreli pay taşıma
    // -------------------------------------------------------------------------
    public Map<String, String> getPeerTransportPublicKey() {
        return Map.of(
                "institutionId", getInstitutionId(),
                "algorithm", "RSA/ECB/OAEPWithSHA-256AndMGF1Padding",
                "publicKey", cryptoService.getNodeTransportPublicKeyBase64());
    }

    private void assertPeerAllowed(String jobId, String baseUrl) {
        if (coordinatorPublicKeyHex != null && !coordinatorPublicKeyHex.isBlank()) {
            java.util.Set<String> ok = approvedPeersByJob.get(jobId);
            if (ok == null || !ok.contains(baseUrl)) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.FORBIDDEN, "Eş bu iş için koordinatör bildiriminde yok: " + baseUrl);
            }
            return;
        }
        if (peerAllowlist == null || peerAllowlist.isBlank()) {
            return;
        }
        for (String allowed : peerAllowlist.split(",")) {
            String a = allowed.trim();
            if (!a.isEmpty() && baseUrl.startsWith(a)) {
                return;
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.FORBIDDEN,
                "Eş adresi izinli listede (agent.mpc.peer-allowlist) değil: " + baseUrl);
    }

    private void sendShareToPeer(String jobId, int senderPartyId, int targetPartyId, String endpoint, BigInteger share) {
        String base = endpoint.replaceAll("/+$", "");
        assertPeerAllowed(jobId, base);
        Exception last = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                String peerKey = peerKeyCache.get(base);
                if (peerKey == null) {
                    var keyReq = java.net.http.HttpRequest.newBuilder()
                            .uri(java.net.URI.create(base + "/api/v1/agent/mpc/peer-public-key"))
                            .timeout(java.time.Duration.ofSeconds(5)).GET().build();
                    var keyResp = peerHttp.send(keyReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                    if (keyResp.statusCode() != 200) {
                        throw new IllegalStateException("peer-public-key HTTP " + keyResp.statusCode());
                    }
                    peerKey = peerMapper.readTree(keyResp.body()).path("publicKey").asText();
                    if (peerKey.isBlank()) {
                        throw new IllegalStateException("Eş açık anahtar döndürmedi");
                    }
                    peerKeyCache.put(base, peerKey);
                }
                String plaintext = jobId + "|" + senderPartyId + "|" + targetPartyId + "|" + share;
                String enc = cryptoService.encryptWithRsaOaep(peerKey, plaintext);
                var body = new EncryptedShareRequest(jobId, getInstitutionId(), senderPartyId, targetPartyId, enc);
                var postReq = java.net.http.HttpRequest.newBuilder()
                        .uri(java.net.URI.create(base + "/api/v1/agent/mpc/receive-encrypted-share"))
                        .timeout(java.time.Duration.ofSeconds(10))
                        .header("Content-Type", "application/json")
                        .POST(java.net.http.HttpRequest.BodyPublishers.ofString(peerMapper.writeValueAsString(body)))
                        .build();
                var postResp = peerHttp.send(postReq, java.net.http.HttpResponse.BodyHandlers.ofString());
                if (postResp.statusCode() == 200) {
                    log.info("[IntegrationAgent - {}] Pay şifreli olarak doğrudan eşe iletildi (Party {} -> Party {}).",
                            getInstitutionId(), senderPartyId, targetPartyId);
                    return;
                }
                // Anahtar değişmiş olabilir (eş yeniden başladı): önbelleği temizle, bir kez daha dene
                peerKeyCache.remove(base);
                throw new IllegalStateException("receive-encrypted-share HTTP " + postResp.statusCode() + ": " + postResp.body());
            } catch (org.springframework.web.server.ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                last = e;
                peerKeyCache.remove(base);
            }
        }
        throw new org.springframework.web.server.ResponseStatusException(
                org.springframework.http.HttpStatus.BAD_GATEWAY,
                "Pay eşe iletilemedi (Party " + targetPartyId + ", " + base + "): " + (last != null ? last.getMessage() : "bilinmeyen hata"));
    }

    public ReceiveShareResponse receiveEncryptedShare(EncryptedShareRequest request) {
        if (request == null || request.jobId() == null || request.jobId().isBlank()
                || request.encryptedData() == null || request.encryptedData().isBlank()) {
            throw new IllegalArgumentException("jobId ve encryptedData zorunludur!");
        }
        String plain = cryptoService.decryptWithNodeTransportKey(request.encryptedData());
        String[] p = plain.split("\\|");
        if (p.length != 4) {
            throw new IllegalArgumentException("Şifreli pay biçimi geçersiz.");
        }
        // Şifreli içerik ile zarf (envelope) üst verisi uyuşmalı: başka işe/başka taraftan yeniden oynatmayı engeller
        if (!p[0].equals(request.jobId())
                || Integer.parseInt(p[1]) != request.senderPartyId()
                || Integer.parseInt(p[2]) != request.targetPartyId()) {
            throw new IllegalArgumentException("Şifreli pay ile zarf bilgileri uyuşmuyor (olası yeniden oynatma).");
        }
        BigInteger val = new BigInteger(p[3]);
        if (val.signum() < 0 || val.compareTo(PRIME) >= 0) {
            throw new IllegalArgumentException("Pay değeri alan dışında.");
        }
        return receiveShare(new ReceiveShareRequest(
                request.jobId(),
                request.sourceInstitutionId(),
                request.senderPartyId(),
                request.targetPartyId(),
                val,
                true,
                null,
                "SHAMIR_SECRET_SHARING",
                Map.of("transport", "RSA-OAEP-P2P")));
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

        Integer expected = expectedPartiesByJob.get(jobId);
        if (expected != null && shares.size() != expected) {
            throw new IllegalStateException("Oturum (" + jobId + ") için " + shares.size() + "/" + expected
                    + " pay toplandı; eksik payla toplama yapılmaz.");
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
