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

/**
 * Kurum İçi Entegrasyon Ajanı Ana Servisi.
 *
 * Birden çok kurumlu sorguda kurumun kendi MP-SPDZ tarafını yönetir, tek kurumlu sorguda sonucu burada hesaplar.
 * Girdi yalnızca kurumun kendi veritabanından gelir ve her istek koordinatör imzasıyla doğrulanır.
 */
@Service
public class IntegrationAgentService {

    private static final Logger log = LoggerFactory.getLogger(IntegrationAgentService.class);

    @Value("${agent.institution-id}")
    private String institutionId;

    @Value("${agent.institution-name}")
    private String institutionName;

    @Value("${server.port:8083}")
    private int serverPort = 8083;

    private final AgentCryptoService cryptoService;
    private final AgentDatabaseService databaseService;
    private final AgentZkpProverService zkpProverService;
    private final AgentChamberVcService chamberVcService;

    private final java.net.http.HttpClient engineHttp = java.net.http.HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();
    private final com.fasterxml.jackson.databind.ObjectMapper engineMapper = new com.fasterxml.jackson.databind.ObjectMapper();

    /** Sabitlenmiş koordinatör Ed25519 açık anahtarı (X.509, hex). Anahtar: GET <koordinatör>/api/v1/vc/platform-public-key */
    @Value("${agent.coordinator.public-key}")
    private String coordinatorPublicKeyHex;

    private final Map<String, Long> usedPipelineJobs = new ConcurrentHashMap<>();

    @jakarta.annotation.PostConstruct
    void requireCoordinatorKey() {
        if (coordinatorPublicKeyHex == null || coordinatorPublicKeyHex.isBlank()) {
            throw new IllegalStateException("AGENT_COORDINATOR_PUBLIC_KEY gerekli.");
        }
    }

    private static org.springframework.web.server.ResponseStatusException forbidden(String message) {
        return new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, message);
    }

    /** pipe1|iş|bitiş|anahtarÖzeti|tablo|sütun|payda|işlem|çıktı|karşılaştırma|eşik: yalnızca koordinatör tek kurum sorgusu başlatabilir. */
    private void verifyPipelineManifest(AgentPipelineRequest request, String jobId, String consumerKey, String datasetName, String metric, QuerySpec spec) {
        String manifest = request.requestManifest();
        String sigHex = request.requestManifestSignature();
        if (manifest == null || sigHex == null || manifest.isBlank() || sigHex.isBlank()) {
            throw forbidden("İmzalı sorgu bildirimi zorunlu.");
        }
        verifyCoordinatorSignature(manifest, sigHex);
        String[] f = manifest.split("\\|", -1);
        long now = Instant.now().getEpochSecond();
        long expires;
        try {
            expires = f.length == 11 ? Long.parseLong(f[2]) : -1;
        } catch (NumberFormatException e) {
            expires = -1;
        }
        String expected = String.join("|", "pipe1", jobId, f.length == 11 ? f[2] : "", cryptoService.sha256Hex(consumerKey),
                datasetName, metric, spec.denominator(), spec.operation(), spec.output(), spec.comparator(), spec.threshold());
        if (expires < now || !expected.equals(manifest)) {
            throw forbidden("Sorgu bildirimi istekle uyuşmuyor veya süresi dolmuş.");
        }
        usedPipelineJobs.values().removeIf(exp -> exp < now);
        if (usedPipelineJobs.putIfAbsent(jobId, expires) != null) {
            throw forbidden("Bu sorgu bildirimi daha önce kullanılmış.");
        }
    }

    /** Kanonik iş bildirimi: iş|eşik|n|bitiş|1=url,2=url. Taraf adresleri yalnızca imzalı bildirimden kabul edilir. */
    private void verifyJobManifest(AgentEngineInit init, String jobId) {
        String manifest = init.jobManifest();
        String sigHex = init.jobManifestSignature();
        if (manifest == null || sigHex == null || manifest.isBlank() || sigHex.isBlank()) {
            throw forbidden("İmzalı iş bildirimi (jobManifest) zorunlu.");
        }
        verifyCoordinatorSignature(manifest, sigHex);
        String[] f = manifest.split("\\|", -1);
        if (f.length != 5 || !f[0].equals(jobId)
                || Integer.parseInt(f[1]) != init.threshold() || Integer.parseInt(f[2]) != init.totalParties()) {
            throw forbidden("İş bildirimi istekle uyuşmuyor (jobId/eşik/n).");
        }
        if (Long.parseLong(f[3]) < Instant.now().getEpochSecond()) {
            throw forbidden("İş bildirimi süresi dolmuş.");
        }
        Map<Integer, String> inManifest = new HashMap<>();
        for (String kv : f[4].split(",")) {
            if (kv.isBlank()) continue;
            String[] part = kv.split("=", 2);
            inManifest.put(Integer.parseInt(part[0]), part[1]);
        }
        Map<Integer, String> asked = new HashMap<>();
        if (init.peerEndpoints() != null) {
            init.peerEndpoints().forEach((k, v) -> asked.put(k, v.replaceAll("/+$", "")));
        }
        if (!asked.equals(inManifest)) {
            throw forbidden("peerEndpoints imzalı bildirimle uyuşmuyor.");
        }
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

    public String getInstitutionId() {
        return institutionId;
    }

    public String getInstitutionName() {
        return institutionName;
    }

    public int getServerPort() {
        return serverPort;
    }

    public boolean isAnonCredsEngineAvailable() {
        return zkpProverService.isAnonCredsEngineAvailable();
    }

    private AgentZkpProofResponse prove(String jobId, String nonce, List<PredicateRequirement> predicates, String schemaName, String datasetName) {
        return zkpProverService.generateZkpProof(new AgentZkpProofRequest(jobId, nonce, predicates, schemaName, datasetName), institutionId);
    }

    /**
     * Tek kurumlu eşik sorgusu: cevap ne ise o koşul kanıtlanır (hayır ise koşulun tersi), değer açılmaz.
     * Kanıtlanan koşul cevabı gösterdiği için paket tüketici anahtarına şifrelenir; doğrulama tarayıcıda yapılır, sunucuya gitmez.
     * Kurum imzası paketi kuruma ve işe bağlar.
     */
    private AgentPredicateProof buildPredicateProof(String jobId, String consumerKey, String datasetName, String metric, QuerySpec spec, BigInteger value, String nonce) {
        Map<String, Object> pack = new LinkedHashMap<>();
        BigInteger limit = new BigInteger(spec.threshold());
        if (!AgentZkpProverService.isProvable(value) || !AgentZkpProverService.isProvable(limit)) {
            pack.put("status", "NOT_AVAILABLE");
            pack.put("reason", "Değer kanıtlanabilir aralığın dışında.");
        } else {
            boolean satisfied = spec.localResult(value, BigInteger.ONE).equals(BigInteger.ONE);
            String proved = satisfied ? spec.comparator() : switch (spec.comparator()) {
                case ">" -> "<=";
                case ">=" -> "<";
                case "<" -> ">=";
                default -> ">";
            };
            pack.put("status", "PROVEN");
            pack.put("comparator", proved);
            pack.putAll(zkpProverService.provePredicate(institutionId, datasetName, metric, value, proved, limit, nonce));
        }
        String plain;
        try {
            plain = engineMapper.writeValueAsString(pack);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("Kanıt paketi hazırlanamadı.", e);
        }
        var sealed = cryptoService.sealForConsumer(consumerKey, plain);
        String signature = chamberVcService.signAttestation(String.join("|", "zkpproof", "v1", institutionId, jobId, cryptoService.sha256Hex(plain)));
        return new AgentPredicateProof(sealed.wrappedKey(), sealed.iv(), sealed.ciphertext(), signature);
    }

    private static String requireName(String value, String label) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(label + " zorunludur.");
        }
        return value.trim();
    }

    /** Tek kurumda MPC yoktur: sonuç kurumda hesaplanır, doğrudan tüketici anahtarına şifrelenir. */
    public AgentPipelineResponse executePipeline(AgentPipelineRequest request) {
        String jobId = requireName(request.jobId(), "JobId");
        String consumerKey = requireName(request.consumerPublicKey(), "Tüketici açık anahtarı");
        String datasetName = requireName(request.datasetName(), "Tablo adı");
        String metric = requireName(request.targetMetric(), "Sütun adı");
        QuerySpec spec = QuerySpec.of(request.operation(), request.denominatorMetric(), request.outputMode(), request.comparator(), request.thresholdValue());
        // İmza, ZKP ve kurum verisinden önce denetlenir
        verifyPipelineManifest(request, jobId, consumerKey, datasetName, metric, spec);
        requireDatabase();

        BigInteger value = readMetric(datasetName, metric);
        var zkpProof = prove(jobId, request.nonce(), request.requestedPredicates(), request.schemaName(), datasetName);

        int partyId = 1;
        String encrypted = cryptoService.encryptWithRsaOaep(consumerKey, partyId + ":" + spec.localResult(
                value, spec.isRatio() ? readMetric(datasetName, spec.denominator()) : BigInteger.ONE));
        // Oranda sonuç taahhüt edilen değer değildir: bu kanıt yalnızca sütun değeri için çalışır
        AgentPredicateProof predicateProof = spec.isThreshold() && !spec.isRatio() && request.proofNonce() != null && request.proofNonce().matches("\\d{1,24}")
                ? buildPredicateProof(jobId, consumerKey, datasetName, metric, spec, value, request.proofNonce())
                : null;
        return new AgentPipelineResponse(
                institutionId,
                jobId,
                zkpProof,
                new EncryptedConsumerValue(partyId, encrypted, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"),
                "ON_PREMISE_PIPELINE_EXECUTED_SUCCESSFULLY",
                Instant.now().toString(),
                attest(jobId, partyId, 1, 1, "LOCAL_SINGLE_NODE", datasetName, metric, spec, consumerKey, encrypted, null),
                predicateProof
        );
    }

    // -------------------------------------------------------------------------
    // MP-SPDZ MPC: her kurum yalnızca KENDİ tarafını kendi makinesinde çalıştırır
    // -------------------------------------------------------------------------
    /** Kurumun yanında çalışan MP-SPDZ köprüsü. Dışarıya açılmaz, yalnızca bu ajan çağırır. */
    @Value("${agent.mpc.engine-url:http://localhost:50051}")
    private String mpcEngineUrl = "http://localhost:50051";

    private record EngineSession(BigInteger secret, BigInteger mask, String protocol, int partyId, int totalParties,
                                 int threshold, List<String> hosts, String certPem, long expiresAtEpochSec,
                                 String datasetName, String targetMetric, BigInteger denominator, QuerySpec spec) {}

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
            BigInteger value = databaseService.queryLocalDatabaseMetric(datasetName, column, institutionId);
            if (value == null) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Kurum veritabanına bağlanılamadı.");
            }
            return value;
        } catch (SQLException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.BAD_REQUEST,
                    "Veritabanı Şema Hatası: '" + column + "' sütunu veya '" + datasetName + "' tablosu " + institutionId + " kurumunda yok. [" + e.getMessage() + "]");
        }
    }

    private void requireDatabase() {
        if (!databaseService.isDbConnected()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE, "Kurum veritabanına bağlanılamadı.");
        }
    }

    /** Sonuç bildirimi: v2|kurum|iş|taraf|n|eşik|protokol|tablo|sütun|payda|işlem|çıktı|karşılaştırma|eşik değeri|anahtar özeti|maske özeti|maskeli sonuç */
    private AgentAttestation attest(String jobId, int partyId, int totalParties, int threshold, String protocol,
                                    String datasetName, String targetMetric, QuerySpec spec, String consumerKey, String encrypted, String masked) {
        String payload = String.join("|", "v2", institutionId, jobId, String.valueOf(partyId), String.valueOf(totalParties),
                String.valueOf(threshold), protocol, datasetName, targetMetric, spec.denominator(), spec.operation(), spec.output(),
                spec.comparator(), spec.threshold(), cryptoService.sha256Hex(consumerKey),
                cryptoService.sha256Hex(encrypted), masked != null ? masked : "");
        return new AgentAttestation(payload, chamberVcService.signAttestation(payload));
    }

    public Map<String, String> getAttestationKey() {
        return Map.of(
                "institutionId", institutionId,
                "algorithm", "Ed25519",
                "publicKeyHex", chamberVcService.attestationPublicKeyHex());
    }

    private final Map<String, EngineSession> engineSessions = new ConcurrentHashMap<>();

    public AgentEnginePrepareResponse enginePrepare(AgentEnginePrepareRequest request) {
        if (request == null || request.init() == null) {
            throw new IllegalArgumentException("JobId boş olamaz!");
        }
        AgentEngineInit init = request.init();
        String jobId = requireName(init.jobId(), "JobId");
        verifyJobManifest(init, jobId);
        requireDatabase();
        String protocol = request.protocol();
        if (!"malicious-shamir".equals(protocol) && !"semi2k".equals(protocol)) {
            throw new IllegalArgumentException("Desteklenmeyen MPC protokolü: " + protocol);
        }
        int n = init.totalParties();
        int partyId = init.partyId();
        List<String> hosts = new ArrayList<>();
        for (int x = 1; x <= n; x++) {
            String url = init.peerEndpoints().get(x);
            if (url == null || url.isBlank()) {
                throw new IllegalArgumentException("Party " + x + " için adres (peerEndpoints) verilmedi.");
            }
            String host = java.net.URI.create(url.replaceAll("/+$", "")).getHost();
            if (host == null) {
                throw new IllegalArgumentException("Party " + x + " adresi çözümlenemedi: " + url);
            }
            hosts.add(host);
        }

        QuerySpec spec = QuerySpec.of(init.operation(), init.denominatorMetric(), init.outputMode(), init.comparator(), init.thresholdValue());
        String datasetName = requireName(init.datasetName(), "Tablo adı");
        String targetMetric = requireName(init.targetMetric(), "Sütun adı");
        BigInteger value = readMetric(datasetName, targetMetric);
        BigInteger denominator = spec.isRatio() ? readMetric(datasetName, spec.denominator()) : BigInteger.ZERO;
        var proof = prove(jobId, init.nonce(), init.requestedPredicates(), init.schemaName(), datasetName);

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("jobId", jobId);
        body.put("partyId", partyId - 1); // MP-SPDZ tarafları 0'dan başlar
        body.put("numParties", n);
        body.put("protocol", protocol);
        putQuery(body, spec);
        String certPem = callEngine("/api/v1/mpc/party-prepare", body, 240).path("certPem").asText();

        long now = Instant.now().getEpochSecond();
        engineSessions.values().removeIf(s -> s.expiresAtEpochSec() < now);
        engineSessions.put(jobId, new EngineSession(value, newMask(protocol), protocol, partyId, n,
                init.threshold(), hosts, certPem, now + 600, datasetName, targetMetric, denominator, spec));
        return new AgentEnginePrepareResponse(institutionId, partyId, proof, certPem, "PREPARED_FOR_MP_SPDZ");
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
                institutionId, jobId, session.protocol(), session.partyId(), session.totalParties());
        return new AgentEngineRunResponse(institutionId, session.partyId(), masked,
                new EncryptedConsumerValue(session.partyId(), encrypted, "RSA/ECB/OAEPWithSHA-256AndMGF1Padding"),
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
                    .POST(java.net.http.HttpRequest.BodyPublishers.ofString(engineMapper.writeValueAsString(body)))
                    .build();
            var resp = engineHttp.send(req, java.net.http.HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "MP-SPDZ motoru hata döndü (HTTP " + resp.statusCode() + "): " + resp.body());
            }
            return engineMapper.readTree(resp.body());
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

    public Map<String, Object> mintChamberVc(String chamberId, String category, String tableName) {
        return chamberVcService.mintChamberVc(chamberId, category, tableName, institutionId, institutionName, serverPort);
    }
}
