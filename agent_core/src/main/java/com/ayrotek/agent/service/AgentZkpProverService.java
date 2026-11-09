package com.ayrotek.agent.service;

import com.ayrotek.agent.controller.IntegrationAgentController.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Kurumun Hyperledger AnonCreds motoruyla konuşur: yayında kalıcı belgeyi düzenler, sorguda kanıt üretir.
 * Belgeyi kurum kendisi imzalar; kanıt "bu kurumun imzaladığı değer koşulu sağlıyor" der, değerin gerçekliğini garanti etmez.
 */
@Service
public class AgentZkpProverService {

    private static final Logger log = LoggerFactory.getLogger(AgentZkpProverService.class);

    /** Devre 64 bit işaretli değer kapsar. */
    private static final java.math.BigInteger PROVABLE_LIMIT = java.math.BigInteger.ONE.shiftLeft(63);

    @Value("${anoncreds.service.url:http://localhost:3000}")
    private String anonCredsServiceUrl = "http://localhost:3000";

    private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public boolean isAnonCredsEngineAvailable() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(anonCredsServiceUrl + "/api/health"))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            return httpClient.send(req, HttpResponse.BodyHandlers.ofString()).statusCode() == 200;
        } catch (IOException e) {
            log.debug("[AgentZkpProver] AnonCreds motoruna ulaşılamadı: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public static boolean isProvable(java.math.BigInteger value) {
        return value.compareTo(PROVABLE_LIMIT.negate()) >= 0 && value.compareTo(PROVABLE_LIMIT) < 0;
    }

    private record ZkpResponse(int status, JsonNode body) {}

    private ZkpResponse post(String path, Map<String, Object> body, int timeoutSeconds) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(anonCredsServiceUrl + path))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return new ZkpResponse(response.statusCode(), objectMapper.readTree(response.body()));
        } catch (IOException e) {
            log.warn("[AgentZkpProver] ZKP motoru hatası: {}", e.getMessage());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kurumun ZKP motoruna ulaşılamadı.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ZKP işlemi kesildi.");
        }
    }

    public record PublishedCredential(String credDefId, Map<String, String> commitments) {}

    /** Yayında çağrılır: kurum kimliği belgesini düzenler ve her kanıtlanabilir sütun için taahhüt üretir. */
    public PublishedCredential issueInstitutionCredential(String institutionId, String tableName, Map<String, java.math.BigInteger> metrics) {
        Map<String, Object> provable = new LinkedHashMap<>();
        metrics.forEach((column, value) -> {
            if (isProvable(value)) {
                provable.put(column, value.toString());
            }
        });
        ZkpResponse response = post("/api/institution/credential", Map.of(
                "institutionId", institutionId,
                "tableName", tableName,
                "attributeValues", Map.of("dataset_id", "DS-" + institutionId, "institution_id", institutionId),
                "metrics", provable), 120);
        if (response.status() != 200) {
            log.error("[AgentZkpProver] Belge düzenlenemedi: {}", response.body().path("error").asText());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Kurum belgesi düzenlenemedi.");
        }
        Map<String, String> commitments = objectMapper.convertValue(response.body().path("commitments"), new TypeReference<>() {});
        return new PublishedCredential(response.body().path("credDefId").asText(), commitments);
    }

    private Map<String, Object> createProof(String institutionId, String tableName, String nonce, List<Map<String, Object>> predicates) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("institutionId", institutionId);
        body.put("tableName", tableName);
        body.put("nonce", nonce);
        body.put("predicates", predicates);
        ZkpResponse response = post("/api/institution/proof", body, 30);
        if (response.status() == 404) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, institutionId + " bu veri setini henüz yayınlamadı.");
        }
        if (response.status() != 200) {
            // Kimlik kanıtı değere bağlı değildir: hata veri değişikliği değil, belge veya motor hatasıdır
            log.warn("[AgentZkpProver] Kimlik kanıtı üretilemedi ({}): {}", response.status(), response.body().path("error").asText());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    institutionId + " kurumunun kimlik kanıtı üretilemedi. Ayrıntı kurumun ajan kaydında; veri setini yeniden yayınlamak belgeyi yeniler.");
        }
        return objectMapper.convertValue(response.body().path("artifacts"), new TypeReference<>() {});
    }

    /** Kurum kimliği kanıtı: belgenin bu kuruma ait olduğunu gösterir, hesaplanan değer hakkında bir şey söylemez. */
    public AgentZkpProofResponse generateZkpProof(AgentZkpProofRequest request, String institutionId) {
        long startTime = System.currentTimeMillis();
        List<Map<String, Object>> predicates = new ArrayList<>();
        if (request.requestedPredicates() != null) {
            for (PredicateRequirement p : request.requestedPredicates()) {
                predicates.add(Map.of("name", p.name(), "predicateType", p.pType() != null ? p.pType() : ">=", "predicateValue", p.pValue()));
            }
        }
        Map<String, Object> artifacts = createProof(institutionId, request.datasetName(), request.nonce(), predicates);
        return new AgentZkpProofResponse(
                "pres-" + UUID.randomUUID().toString().substring(0, 8),
                institutionId,
                request.presentationRequestId(),
                request.nonce(),
                "ANONCREDS",
                Map.of("dataset_id", "DS-" + institutionId, "institution_id", institutionId),
                Map.of(),
                null,
                Math.max(1, System.currentTimeMillis() - startTime),
                Instant.now().toString(),
                "PROOF_GENERATED_ON_PREMISE",
                false,
                "Kurumun yayında düzenlediği kalıcı belgeden üretildi.",
                artifacts
        );
    }

    /** Koşul kanıtı (PLONK): değeri açmadan taahhüt edilen değerin koşulu sağladığını gösterir. */
    public Map<String, Object> provePredicate(String institutionId, String tableName, String metric, java.math.BigInteger currentValue,
                                              String comparator, java.math.BigInteger limit, String nonce, String publishedCommitment) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("institutionId", institutionId);
        body.put("tableName", tableName);
        body.put("metric", metric);
        body.put("currentValue", currentValue.toString());
        body.put("comparator", comparator);
        body.put("limit", limit.toString());
        body.put("nonce", nonce);
        if (publishedCommitment != null && !publishedCommitment.isBlank()) {
            body.put("publishedCommitment", publishedCommitment);
        }
        ZkpResponse response = post("/api/institution/predicate-proof", body, 60);
        if (response.status() == 404) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, institutionId + " bu sütunu henüz yayınlamadı. Veri setinin yeniden yayınlanması gerekir.");
        }
        if (response.status() == 409 && "STALE_PUBLICATION".equals(response.body().path("code").asText())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    institutionId + " bu veri setindeki yayınını yenilemeli: yayından sonra aynı sütunu başka bir yayınla yeniden taahhüt etti.");
        }
        if (response.status() == 422 && "VALUE_CHANGED".equals(response.body().path("code").asText())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    institutionId + " verisi yayından sonra değişmiş. Kurumun veri setini yeniden yayınlaması gerekir.");
        }
        if (response.status() != 200) {
            log.warn("[AgentZkpProver] Koşul kanıtı üretilemedi ({} {}): {}", response.status(),
                    response.body().path("code").asText(), response.body().path("error").asText());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    institutionId + " kurumunun koşul kanıtı üretilemedi. Ayrıntı kurumun ajan kaydında.");
        }
        Map<String, Object> proof = new LinkedHashMap<>();
        proof.put("proof", response.body().path("proof"));
        proof.put("publicSignals", response.body().path("publicSignals"));
        return proof;
    }
}
