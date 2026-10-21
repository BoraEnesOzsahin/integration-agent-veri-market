package com.ayrotek.agent.controller;

import com.ayrotek.agent.service.IntegrationAgentService;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Kurum İçi Entegrasyon Ajanı REST Uç Noktaları.
 * Tüm DTO modelleri Java 17 record olarak bu dosya içinde kompakt şekilde yer alır.
 */
@RestController
@RequestMapping("/api/v1/agent")
@Tag(name = "Kurum İçi Entegrasyon Ajanı (Integration Agent Node API)",
     description = "Kurumun ham verisini dışarı sızdırmadan ZKP kanıtı üreten, Shamir payını şifreleyen ve gelen payları kabul eden uç noktalar")
public class IntegrationAgentController {

    private final IntegrationAgentService agentService;

    public IntegrationAgentController(IntegrationAgentService agentService) {
        this.agentService = agentService;
    }

    // -------------------------------------------------------------------------
    // REST Uç Noktaları
    // -------------------------------------------------------------------------

    @GetMapping("/status")
    @Operation(summary = "Entegrasyon Ajanı Durum ve Kimlik Bilgisi",
               description = "Kurum içi ajanın çalışma durumunu, kurum kimliğini ve kriptografik motor bağlantılarını döner.")
    public ResponseEntity<AgentStatusResponse> getAgentStatus() {
        boolean anonCredsActive = agentService.isAnonCredsEngineAvailable();
        return ResponseEntity.ok(new AgentStatusResponse(
                agentService.getInstitutionId(),
                agentService.getInstitutionName(),
                "1.0.0",
                "ACTIVE_ONLINE",
                anonCredsActive ? "Hyperledger AnonCreds Engine (Port 3000 / Rust Native CL-Signatures)" : "AnonCreds On-Premise Prover (Standalone Stub)",
                "Shamir Secret Sharing + RSA OAEP-256 Consumer Encryption",
                "Kurum İçi Gizli Müşteri Verisi Güvenli Sınırda Tutuluyor (Ham Veri Sızma: 0)",
                agentService.getActiveSessionsCount(),
                Map.of("serverPort", agentService.getServerPort(), "networkZone", "ON_PREMISE_INSTITUTION_INTERNAL", "zkpPredicateSupported", true, "anonCredsConnected", anonCredsActive)
        ));
    }


    @PostMapping("/zkp/generate-proof")
    @Operation(summary = "Kurum İçi ZKP Kanıtı Üret (AnonCreds Prover)",
               description = "Kurumun içerideki ham verisi üzerinde AnonCreds ZKP kanıtı üretir. Gelir, kredi skoru gibi ham değerler ASLA dışarı sızmaz, sadece matematiksel koşul ispatı döner.")
    public ResponseEntity<AgentZkpProofResponse> generateZkpProof(@RequestBody(required = false) AgentZkpProofRequest request) {
        return ResponseEntity.ok(agentService.generateZkpProof(request));
    }

    @PostMapping("/mpc/compute-and-encrypt-share")
    @Operation(summary = "Shamir Payı Hesapla ve Tüketici Açık Anahtarıyla Şifrele",
               description = "Kurum içi ham veriyi Shamir paylarına böler ve elde edilen payı doğrudan TÜKETİCİ AÇIK ANAHTARI (RSA-OAEP) ile şifreler. Platform koordinatörü dahil kimse payı göremez.")
    public ResponseEntity<AgentComputeShareResponse> computeAndEncryptShare(@RequestBody(required = false) AgentComputeShareRequest request) {
        return ResponseEntity.ok(agentService.computeAndEncryptShare(request));
    }

    @PostMapping("/mpc/init-and-split")
    @Operation(summary = "MPC Oturumu Başlat, ZKP İspatı Üret ve Payları Böl (Round 1)",
               description = "Kurum içi ham veri üzerinde ZKP ispatı üretir, sırrı Shamir paylarına böler. Kendi payını yerel oturum belleğinde tutar, diğer kurumlara gönderilecek payları döner.")
    public ResponseEntity<AgentInitAndSplitResponse> initAndSplit(@RequestBody AgentInitAndSplitRequest request) {
        return ResponseEntity.ok(agentService.initAndSplit(request));
    }

    @PostMapping("/mpc/receive-share")
    @Operation(summary = "Shamir Payını Kabul Et (Receive Shamir Share)",
               description = "Diğer kurumlardan veya koordinatörden gelen Shamir payını teslim alır, doğrular ve ajanın MPC oturum belleğine güvenle kaydeder.")
    public ResponseEntity<ReceiveShareResponse> receiveShamirShare(@RequestBody ReceiveShareRequest request) {
        return ResponseEntity.ok(agentService.receiveShare(request));
    }

    @PostMapping("/mpc/aggregate-and-encrypt")
    @Operation(summary = "Toplanan Payları Homomorfik Olarak Birleştir ve Tüketici Anahtarıyla Şifrele (Round 2)",
               description = "Kuruma ulaşmış olan tüm Shamir paylarını homomorfik olarak toplar ve elde edilen toplam payı Tüketici Açık Anahtarı ile şifreleyerek teslim eder.")
    public ResponseEntity<AgentAggregateResponse> aggregateAndEncrypt(@RequestBody AgentAggregateRequest request) {
        return ResponseEntity.ok(agentService.aggregateAndEncrypt(request));
    }

    @GetMapping("/mpc/received-shares/{jobId}")
    @Operation(summary = "İş Bazında Alınan Shamir Paylarını Listele",
               description = "Belirli bir hesaplama işi (jobId) için kurum içi ajana ulaşmış olan tüm payları listeler.")
    public ResponseEntity<List<ReceiveShareRequest>> getReceivedShares(@PathVariable String jobId) {
        return ResponseEntity.ok(agentService.getReceivedShares(jobId));
    }

    @PostMapping("/pipeline/process-request")
    @Operation(summary = "Bütünleşik Ajan İş Akışı (ZKP İspatı + Şifreli Shamir Payı)",
               description = "Koordinatörün talebiyle tek seferde: Kurum içi ZKP kanıtı üretilir, Shamir payı hesaplanıp tüketici açık anahtarıyla kilitlenir ve güvenli zarf teslim edilir.")
    public ResponseEntity<AgentPipelineResponse> processRequest(@RequestBody(required = false) AgentPipelineRequest request) {
        return ResponseEntity.ok(agentService.executePipeline(request));
    }

    @PostMapping("/chamber/mint-vc")
    @Operation(summary = "Data Chamber Odası İçin Kurum İçi Canlı VC Türet",
               description = "Kurumun DMZ sınırları içindeki yerel PostgreSQL veritabanından ham veriyi çeker. Ham veri ASLA dışarı çıkmaz; yerel Pedersen taahhütleri ve ZKP ile mühürlenmiş W3C VC üretir.")
    public ResponseEntity<Map<String, Object>> mintChamberVc(@RequestBody(required = false) Map<String, String> request) {
        String chamberId = request != null ? request.getOrDefault("chamberId", "chamber-alpha") : "chamber-alpha";
        String category = request != null ? request.getOrDefault("category", "gelir_gider") : "gelir_gider";
        String tableName = request != null ? request.getOrDefault("tableName", "gelir_gider") : "gelir_gider";
        return ResponseEntity.ok(agentService.mintChamberVc(chamberId, category, tableName));
    }

    @GetMapping("/db/overview")
    @Operation(summary = "Kurumun Yerel PostgreSQL Tablo ve Şema Bilgilerini Getir",
               description = "Kurumun DMZ içindeki veritabanı tablolarını, kolonlarını ve satır sayılarını listeler. Ham veri dışarı çıkmaz, yalnızca şema yapısı arayüze sunulur.")
    public ResponseEntity<Map<String, Object>> getDatabaseOverview() {
        return ResponseEntity.ok(agentService.getLocalDatabaseOverview());
    }

    @PostMapping("/chamber/mint-custom")
    @Operation(summary = "Kullanıcı Tanımlı Şema ile Kurum İçi Canlı VC Türet",
               description = "Kullanıcının seçtiği tablodan ve kolonlardan Pedersen Taahhüdü ve AnonCreds ZKP ile mühürlü W3C VC üretir.")
    public ResponseEntity<Map<String, Object>> mintCustomVc(@RequestBody Map<String, Object> request) {
        return ResponseEntity.ok(agentService.mintCustomChamberVc(request));
    }

    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleResponseStatus(org.springframework.web.server.ResponseStatusException ex) {
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of(
                "status", "ERROR",
                "errorType", "SCHEMA_ERROR",
                "message", ex.getReason() != null ? ex.getReason() : ex.getMessage(),
                "institutionId", agentService.getInstitutionId()
        ));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleIllegalArgument(IllegalArgumentException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "status", "ERROR",
                "errorType", "VALIDATION_ERROR",
                "message", ex.getMessage(),
                "institutionId", agentService.getInstitutionId()
        ));
    }

    // -------------------------------------------------------------------------
    // Modeller (Kompakt Java 17 Records - Ayrı dosya gerektirmez)
    // -------------------------------------------------------------------------

    public record PredicateRequirement(
            @JsonProperty("name") String name,
            @JsonProperty("pType") String pType,
            @JsonProperty("pValue") long pValue
    ) {}

    public record AgentStatusResponse(
            String institutionId,
            String institutionName,
            String agentVersion,
            String status,
            String zkpProverEngine,
            String mpcEngine,
            String localDataSummary,
            int activeMpcSessionsCount,
            Map<String, Object> environmentInfo
    ) {}

    public record AgentZkpProofRequest(
            String presentationRequestId,
            String nonce,
            List<PredicateRequirement> requestedPredicates,
            List<String> discloseAttributes,
            Map<String, Object> customAttributes,
            String schemaName,
            String datasetName
    ) {
        public AgentZkpProofRequest(String presentationRequestId, String nonce, List<PredicateRequirement> requestedPredicates, List<String> discloseAttributes) {
            this(presentationRequestId, nonce, requestedPredicates, discloseAttributes, null, null, null);
        }
        public AgentZkpProofRequest(String presentationRequestId, String nonce, List<PredicateRequirement> requestedPredicates, List<String> discloseAttributes, Map<String, Object> customAttributes) {
            this(presentationRequestId, nonce, requestedPredicates, discloseAttributes, customAttributes, null, null);
        }
        public AgentZkpProofRequest(String presentationRequestId, String nonce, List<PredicateRequirement> requestedPredicates, List<String> discloseAttributes, Map<String, Object> customAttributes, String schemaName) {
            this(presentationRequestId, nonce, requestedPredicates, discloseAttributes, customAttributes, schemaName, null);
        }
    }

    public record AgentZkpProofResponse(
            String presentationId,
            String institutionId,
            String presentationRequestId,
            String nonce,
            String proofSystem,
            Map<String, Object> disclosedAttributes,
            Map<String, Boolean> predicatesProved,
            String proofJson,
            long generationTimeMs,
            String createdAt,
            String status,
            boolean rawAttributesExposed,
            String securityGuarantee
    ) {}

    public record EncryptedShamirShare(
            int partyId,
            String encryptedData,
            String algorithm
    ) {}

    public record AgentComputeShareRequest(
            String jobId,
            String consumerPublicKey,
            int partyId,
            int threshold,
            int totalParties,
            BigInteger overrideSecret,
            String targetMetric,
            Map<String, Object> customAttributes
    ) {
        public AgentComputeShareRequest(String jobId, String consumerPublicKey, int partyId, int threshold, int totalParties, BigInteger overrideSecret) {
            this(jobId, consumerPublicKey, partyId, threshold, totalParties, overrideSecret, "income", null);
        }
    }

    public record AgentComputeShareResponse(
            String institutionId,
            String jobId,
            int partyId,
            int threshold,
            int totalParties,
            EncryptedShamirShare consumerEncryptedShare,
            String consumerPublicKeyFingerprint,
            String status,
            String securityNote
    ) {}

    public record ReceiveShareRequest(
            String jobId,
            String sourceInstitutionId,
            int senderPartyId,
            int targetPartyId,
            BigInteger shareValue,
            boolean encrypted,
            String encryptedData,
            String protocol,
            Map<String, String> metadata
    ) {}

    public record ReceiveShareResponse(
            String jobId,
            String targetInstitutionId,
            String sourceInstitutionId,
            int registeredSenderPartyId,
            int totalSharesCollectedForJob,
            String status,
            String message,
            String timestamp
    ) {}

    public record AgentPipelineRequest(
            String jobId,
            String consumerPublicKey,
            int partyId,
            int threshold,
            int totalParties,
            String nonce,
            List<PredicateRequirement> requestedPredicates,
            BigInteger overrideSecret,
            String targetMetric,
            Map<String, Object> customAttributes,
            String schemaName
    ) {
        public AgentPipelineRequest(String jobId, String consumerPublicKey, int partyId, int threshold, int totalParties, String nonce, List<PredicateRequirement> requestedPredicates, BigInteger overrideSecret) {
            this(jobId, consumerPublicKey, partyId, threshold, totalParties, nonce, requestedPredicates, overrideSecret, "income", null, null);
        }
        public AgentPipelineRequest(String jobId, String consumerPublicKey, int partyId, int threshold, int totalParties, String nonce, List<PredicateRequirement> requestedPredicates, BigInteger overrideSecret, String targetMetric, Map<String, Object> customAttributes) {
            this(jobId, consumerPublicKey, partyId, threshold, totalParties, nonce, requestedPredicates, overrideSecret, targetMetric, customAttributes, null);
        }
    }

    public record AgentPipelineResponse(
            String institutionId,
            String jobId,
            AgentZkpProofResponse zkpProof,
            EncryptedShamirShare consumerEncryptedShare,
            String status,
            String securityGuarantee,
            String timestamp
    ) {}

    public record SharePacket(
            @JsonProperty("senderPartyId") int senderPartyId,
            @JsonProperty("targetPartyId") int targetPartyId,
            @JsonProperty("shareValue") BigInteger shareValue
    ) {}

    public record AgentInitAndSplitRequest(
            @JsonProperty("jobId") String jobId,
            @JsonProperty("partyId") int partyId,
            @JsonProperty("threshold") int threshold,
            @JsonProperty("totalParties") int totalParties,
            @JsonProperty("targetMetric") String targetMetric,
            @JsonProperty("datasetName") String datasetName,
            @JsonProperty("rawQuery") String rawQuery,
            @JsonProperty("requestedPredicates") List<PredicateRequirement> requestedPredicates,
            @JsonProperty("nonce") String nonce,
            @JsonProperty("overrideSecret") BigInteger overrideSecret,
            @JsonProperty("customAttributes") Map<String, Object> customAttributes,
            @JsonProperty("schemaName") String schemaName
    ) {
        public AgentInitAndSplitRequest(String jobId, int partyId, int threshold, int totalParties, String targetMetric, List<PredicateRequirement> requestedPredicates, String nonce, BigInteger overrideSecret, Map<String, Object> customAttributes, String schemaName) {
            this(jobId, partyId, threshold, totalParties, targetMetric, "gelir_gider", null, requestedPredicates, nonce, overrideSecret, customAttributes, schemaName);
        }
    }

    public record AgentInitAndSplitResponse(
            String institutionId,
            int partyId,
            AgentZkpProofResponse zkpProof,
            List<SharePacket> outgoingShares,
            String status
    ) {}

    public record AgentAggregateRequest(
            @JsonProperty("jobId") String jobId,
            @JsonProperty("consumerPublicKey") String consumerPublicKey,
            @JsonProperty("partyId") int partyId
    ) {}

    public record AgentAggregateResponse(
            String institutionId,
            int partyId,
            EncryptedShamirShare consumerEncryptedShare,
            int sharesAggregatedCount,
            String status
    ) {}
}
