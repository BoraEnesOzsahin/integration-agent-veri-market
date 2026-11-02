package com.ayrotek.agent.controller;

import com.ayrotek.agent.service.IntegrationAgentService;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Kurum İçi Entegrasyon Ajanı REST Uç Noktaları. İstek ve yanıt modelleri bu dosyadadır.
 */
@RestController
@RequestMapping("/api/v1/agent")
public class IntegrationAgentController {

    private final IntegrationAgentService agentService;

    public IntegrationAgentController(IntegrationAgentService agentService) {
        this.agentService = agentService;
    }

    @GetMapping("/status")
    public ResponseEntity<AgentStatusResponse> getAgentStatus() {
        return ResponseEntity.ok(new AgentStatusResponse(
                agentService.getInstitutionId(),
                agentService.getInstitutionName(),
                "ACTIVE_ONLINE",
                "MP-SPDZ",
                agentService.isAnonCredsEngineAvailable()
        ));
    }

    /** Round 1: yerel değeri okur, ZKP üretir, maskeyi seçer ve iş sertifikasını döner. Ham değer ve maske ajandan çıkmaz. */
    @PostMapping("/mpc/engine/prepare")
    public ResponseEntity<AgentEnginePrepareResponse> enginePrepare(@RequestBody AgentEnginePrepareRequest request) {
        return ResponseEntity.ok(agentService.enginePrepare(request));
    }

    /** Round 2: imzalı bildirimi doğrular, MP-SPDZ tarafını çalıştırır ve maskeyi tüketici anahtarıyla şifreler. */
    @PostMapping("/mpc/engine/run")
    public ResponseEntity<AgentEngineRunResponse> engineRun(@RequestBody AgentEngineRunRequest request) {
        return ResponseEntity.ok(agentService.engineRun(request));
    }

    /** Sonuç imzalarını doğrulayan kurum anahtarı; yönetici onayında karşılaştırılabilir. */
    @GetMapping("/mpc/attestation-key")
    public ResponseEntity<Map<String, String>> attestationKey() {
        return ResponseEntity.ok(agentService.getAttestationKey());
    }

    /** Tek kurumlu sorgu: koordinatör imzası zorunludur. */
    @PostMapping("/pipeline/process-request")
    public ResponseEntity<AgentPipelineResponse> processRequest(@RequestBody AgentPipelineRequest request) {
        return ResponseEntity.ok(agentService.executePipeline(request));
    }

    /** Veri seti belgesi: değerler yalnızca Pedersen taahhüdü olarak çıkar. */
    @PostMapping("/chamber/mint-vc")
    public ResponseEntity<Map<String, Object>> mintChamberVc(@RequestBody MintVcRequest request) {
        return ResponseEntity.ok(agentService.mintChamberVc(request.chamberId(), request.category(), request.tableName()));
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

    public record MintVcRequest(String chamberId, String category, String tableName) {}

    public record PredicateRequirement(
            @JsonProperty("name") String name,
            @JsonProperty("pType") String pType,
            @JsonProperty("pValue") long pValue
    ) {}

    public record AgentStatusResponse(
            String institutionId,
            String institutionName,
            String status,
            String mpcEngine,
            boolean anonCredsConnected
    ) {}

    public record AgentZkpProofRequest(
            String presentationRequestId,
            String nonce,
            List<PredicateRequirement> requestedPredicates,
            String schemaName,
            String datasetName
    ) {}

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
            String securityGuarantee,
            /** Koordinatörün bağımsız doğrulaması için KAMUSAL kanıt malzemesi (presentation, presentationRequest, schema, credentialDefinition). */
            Map<String, Object> artifacts
    ) {}

    /** Tüketici anahtarıyla şifreli değer: tek kurumda sonuç, MP-SPDZ'de kurumun maskesi. */
    public record EncryptedConsumerValue(
            int partyId,
            String encryptedData,
            String algorithm
    ) {}

    public record AgentPipelineRequest(
            String jobId,
            String consumerPublicKey,
            String nonce,
            List<PredicateRequirement> requestedPredicates,
            String targetMetric,
            String schemaName,
            String datasetName,
            String operation,
            String denominatorMetric,
            String outputMode,
            String comparator,
            String thresholdValue,
            /** Koordinatörün imzaladığı tek kurum sorgu bildirimi. */
            String requestManifest,
            String requestManifestSignature,
            /** Tüketicinin tarayıcısında üretilen nonce: koşul kanıtı buna bağlanır, koordinatör değiştirirse tüketici fark eder. */
            String proofNonce
    ) {}

    /** Koşul kanıtı tüketici anahtarına şifrelidir: kanıtlanan koşul cevabı ele verdiği için koordinatör okuyamaz. */
    public record AgentPredicateProof(String wrappedKey, String iv, String ciphertext, String signature) {}

    /** Kurumun imzaladığı sonuç bildirimi: koordinatör içeriği değiştirirse tüketici fark eder. */
    public record AgentAttestation(String payload, String signature) {}

    public record AgentPipelineResponse(
            String institutionId,
            String jobId,
            AgentZkpProofResponse zkpProof,
            EncryptedConsumerValue consumerEncryptedShare,
            String status,
            String timestamp,
            AgentAttestation attestation,
            AgentPredicateProof predicateProof
    ) {}

    public record AgentEngineInit(
            @JsonProperty("jobId") String jobId,
            @JsonProperty("partyId") int partyId,
            @JsonProperty("threshold") int threshold,
            @JsonProperty("totalParties") int totalParties,
            @JsonProperty("targetMetric") String targetMetric,
            @JsonProperty("datasetName") String datasetName,
            @JsonProperty("requestedPredicates") List<PredicateRequirement> requestedPredicates,
            @JsonProperty("nonce") String nonce,
            @JsonProperty("schemaName") String schemaName,
            /** partyId -> kurum ajanının taban URL'i. */
            @JsonProperty("peerEndpoints") Map<Integer, String> peerEndpoints,
            /** Koordinatörün imzaladığı kanonik iş bildirimi ve Ed25519 imzası (hex). */
            @JsonProperty("jobManifest") String jobManifest,
            @JsonProperty("jobManifestSignature") String jobManifestSignature,
            /** SUM, PRODUCT veya RATIO. */
            @JsonProperty("operation") String operation,
            /** RATIO için payda sütunu. */
            @JsonProperty("denominatorMetric") String denominatorMetric,
            /** VALUE: sonuç açılır. THRESHOLD: yalnızca evet/hayır açılır. */
            @JsonProperty("outputMode") String outputMode,
            @JsonProperty("comparator") String comparator,
            @JsonProperty("thresholdValue") String thresholdValue
    ) {}

    public record AgentEnginePrepareRequest(
            @JsonProperty("init") AgentEngineInit init,
            /** malicious-shamir (n >= 3) veya semi2k (n = 2). */
            @JsonProperty("protocol") String protocol
    ) {}

    public record AgentEnginePrepareResponse(
            String institutionId,
            int partyId,
            AgentZkpProofResponse zkpProof,
            String certPem,
            String status
    ) {}

    public record AgentEngineRunRequest(
            @JsonProperty("jobId") String jobId,
            @JsonProperty("basePort") int basePort,
            /** partyId -> o işe özel MP-SPDZ sertifikası (PEM). */
            @JsonProperty("certs") Map<Integer, String> certs,
            @JsonProperty("consumerPublicKey") String consumerPublicKey,
            /** Kanonik: jobId|protokol|eşik|basePort|tüketiciAnahtarSHA256|1=sertSHA256,2=sertSHA256 */
            @JsonProperty("runManifest") String runManifest,
            @JsonProperty("runManifestSignature") String runManifestSignature
    ) {}

    public record AgentEngineRunResponse(
            String institutionId,
            int partyId,
            /** sonuç + tüm maskeler. Tek başına sonuç hakkında bilgi vermez; her taraf aynı değeri döner ve imzalar. */
            String maskedResult,
            EncryptedConsumerValue consumerEncryptedMask,
            String status,
            AgentAttestation attestation
    ) {}
}
