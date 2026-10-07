package com.ayrotek.agent.manager.service;

import com.ayrotek.agent.manager.model.*;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Dinamik ZKP ve W3C VC Şema Belirleme Servisi.
 *
 * Koordinatörden veya tüketiciden gelen her farklı analitik predicate talebine göre;
 * 1. Hyperledger AnonCreds ZKP şeması ve kanıt talebi şablonu (selective disclosure & predicate)
 * 2. W3C Verifiable Credential (VC) JSON şeması
 * dinamik olarak oluşturulur ve yerel AnonCreds motoruyla senkronize edilir.
 */
@Service
public class DynamicSchemaService {

    private static final Logger log = LoggerFactory.getLogger(DynamicSchemaService.class);

    @Value("${node.id:Banka_A}")
    private String nodeId;

    @Value("${node.name:Banka A Kurum İçi Yönetici}")
    private String nodeName;

    @Value("${node.anoncreds-url:http://localhost:3000}")
    private String anoncredsUrl;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public PredicateSchemaResponse resolveDynamicSchema(PredicateRequest request) {
        String jobId = (request != null && request.jobId() != null && !request.jobId().isBlank())
                ? request.jobId()
                : "job-" + UUID.randomUUID().toString().substring(0, 8);

        String rawDataset = (request != null && request.datasetName() != null && !request.datasetName().isBlank())
                ? request.datasetName().trim()
                : "consortium";
        String datasetName = sanitizeIdentifier(rawDataset);

        List<DynamicPredicateDto> predicates = (request != null && request.predicates() != null)
                ? request.predicates()
                : Collections.emptyList();

        String rawMetric = (request != null && request.targetMetric() != null && !request.targetMetric().isBlank())
                ? request.targetMetric().trim()
                : (!predicates.isEmpty() ? predicates.get(0).name() : "balance");
        String targetMetric = sanitizeIdentifier(rawMetric);

        String version = "1.0.0";
        String dynamicSchemaName = datasetName + "_" + targetMetric + "_dynamic_zkp_schema";
        String issuerDid = "did:ayrotek:" + sanitizeIdentifier(nodeId);

        log.info("[DynamicSchemaService] Dinamik şema çözümleniyor -> Düğüm: {}, Şema: {}, Hedef: {}, Predicate Sayısı: {}",
                nodeId, dynamicSchemaName, targetMetric, predicates.size());

        // 1. Dinamik Öznitelikler Havuzunu Derle
        LinkedHashSet<String> attrSet = new LinkedHashSet<>();
        attrSet.add("dataset_id");
        attrSet.add("institution_id");
        attrSet.add(targetMetric);

        for (DynamicPredicateDto pred : predicates) {
            if (pred != null && pred.name() != null && !pred.name().isBlank()) {
                attrSet.add(sanitizeIdentifier(pred.name()));
            }
        }

        if (request != null && request.customAttributes() != null) {
            for (String key : request.customAttributes().keySet()) {
                if (key != null && !key.isBlank()) {
                    attrSet.add(sanitizeIdentifier(key));
                }
            }
        }

        if (request != null && request.revealedAttributes() != null) {
            for (String ra : request.revealedAttributes()) {
                if (ra != null && !ra.isBlank()) {
                    attrSet.add(sanitizeIdentifier(ra));
                }
            }
        }

        List<String> attributeNames = new ArrayList<>(attrSet);

        // 2. AnonCreds Predicate ve Kural Listesini Formatla
        List<Map<String, Object>> anoncredsPredicates = new ArrayList<>();
        List<String> predicateRuleSummaries = new ArrayList<>();

        for (DynamicPredicateDto pred : predicates) {
            if (pred != null && pred.name() != null) {
                String pName = sanitizeIdentifier(pred.name());
                String pType = (pred.pType() != null && !pred.pType().isBlank()) ? pred.pType().trim() : ">=";
                anoncredsPredicates.add(Map.of(
                        "name", pName,
                        "predicateType", pType,
                        "predicateValue", pred.pValue()
                ));
                predicateRuleSummaries.add(pName + " " + pType + " " + pred.pValue());
            }
        }

        List<String> revealedAttrs = (request != null && request.revealedAttributes() != null && !request.revealedAttributes().isEmpty())
                ? request.revealedAttributes()
                : List.of("dataset_id", "institution_id");

        // 3. AnonCreds Şema Yükü
        Map<String, Object> anoncredsPayload = new LinkedHashMap<>();
        anoncredsPayload.put("issuerId", issuerDid);
        anoncredsPayload.put("name", dynamicSchemaName);
        anoncredsPayload.put("version", version);
        anoncredsPayload.put("attributeNames", attributeNames);

        // 4. AnonCreds Presentation Request Şablonu
        Map<String, Object> presReqTemplate = buildPresentationRequestTemplate(dynamicSchemaName, version, revealedAttrs, anoncredsPredicates);

        // 5. Yerel AnonCreds Motoruna Şemayı Kaydetmeyi Dene (Eğer Ayaktaysa)
        AnoncredsRegistrationResult regResult = registerWithLocalAnoncreds(anoncredsPayload, dynamicSchemaName);

        DynamicZkpSchema zkpSchema = new DynamicZkpSchema(
                dynamicSchemaName,
                version,
                issuerDid,
                attributeNames,
                anoncredsPredicates,
                revealedAttrs,
                regResult.registered(),
                regResult.schemaId(),
                anoncredsPayload,
                presReqTemplate
        );

        // 6. Dinamik W3C Verifiable Credential (VC) JSON Şemasını İnşa Et
        DynamicVcSchema vcSchema = buildDynamicVcSchema(
                dynamicSchemaName,
                datasetName,
                targetMetric,
                attributeNames,
                predicateRuleSummaries,
                predicates
        );

        return new PredicateSchemaResponse(
                "SUCCESS",
                nodeId,
                nodeName,
                jobId,
                datasetName,
                targetMetric,
                dynamicSchemaName,
                zkpSchema,
                vcSchema,
                Instant.now().toString(),
                "Dinamik predicate talebine uygun Hyperledger AnonCreds ZKP ve W3C VC şemaları başarıyla oluşturuldu."
        );
    }

    private Map<String, Object> buildPresentationRequestTemplate(
            String schemaName,
            String version,
            List<String> revealedAttrs,
            List<Map<String, Object>> predicates) {

        Map<String, Object> reqAttrs = new LinkedHashMap<>();
        int attrIdx = 1;
        for (String attr : revealedAttrs) {
            reqAttrs.put("attr_" + attrIdx + "_referent", Map.of(
                    "name", attr,
                    "restrictions", List.of(Map.of("schema_name", schemaName))
            ));
            attrIdx++;
        }

        Map<String, Object> reqPreds = new LinkedHashMap<>();
        int predIdx = 1;
        for (Map<String, Object> p : predicates) {
            reqPreds.put("predicate_" + predIdx + "_referent", Map.of(
                    "name", p.get("name"),
                    "p_type", p.get("predicateType"),
                    "p_value", p.get("predicateValue"),
                    "restrictions", List.of(Map.of("schema_name", schemaName))
            ));
            predIdx++;
        }

        Map<String, Object> template = new LinkedHashMap<>();
        template.put("name", "Dynamic Predicate Proof Request (" + schemaName + ")");
        template.put("version", version);
        template.put("nonce", UUID.randomUUID().toString());
        template.put("requested_attributes", reqAttrs);
        template.put("requested_predicates", reqPreds);
        return template;
    }

    private DynamicVcSchema buildDynamicVcSchema(
            String schemaName,
            String datasetName,
            String targetMetric,
            List<String> attributeNames,
            List<String> predicateRuleSummaries,
            List<DynamicPredicateDto> predicates) {

        String schemaId = "https://schema.ayrotek.com/vc/" + schemaName + ".json";
        String schemaType = "JsonSchemaValidator2018";
        List<String> context = List.of(
                "https://www.w3.org/2018/credentials/v1",
                "https://schema.ayrotek.com/v1",
                "https://schema.ayrotek.com/vc/" + datasetName
        );
        List<String> credentialTypes = List.of(
                "VerifiableCredential",
                "DynamicMpcAggregatedProofCredential",
                capitalize(datasetName) + "Credential"
        );
        String issuerDid = "did:ayrotek:platform:mainnet";

        List<String> requiredClaims = List.of(
                "jobId",
                "datasetName",
                "targetMetric",
                "threshold",
                "totalParties",
                "encryptedAggregatedShares",
                "predicatesVerified",
                "zkpProofFingerprint"
        );

        // JSON Schema (draft 2020-12) nesnesi
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("id", Map.of("type", "string", "format", "uri", "description", "Credential Subject tekil URI"));
        props.put("jobId", Map.of("type", "string", "description", "MPC / ZKP analitik sorgu iş kimliği"));
        props.put("datasetName", Map.of("type", "string", "const", datasetName, "description", "Hedef veri seti veya sektör"));
        props.put("targetMetric", Map.of("type", "string", "const", targetMetric, "description", "Hesaplanan analitik metrik"));
        props.put("threshold", Map.of("type", "integer", "minimum", 1, "description", "Shamir MPC gizli paylaşım eşik değeri (t)"));
        props.put("totalParties", Map.of("type", "integer", "minimum", 1, "description", "Hesaplamaya katılan toplam kurum sayısı (n)"));
        props.put("participatingInstitutions", Map.of("type", "array", "items", Map.of("type", "string"), "description", "Katılımcı kurum düğüm adları"));
        props.put("encryptedAggregatedShares", Map.of("type", "array", "description", "Tüketici açık anahtarıyla mühürlenmiş homomorfik paylar"));

        // Dinamik predicate doğrulama özellikleri
        Map<String, Object> predicateProps = new LinkedHashMap<>();
        for (DynamicPredicateDto dp : predicates) {
            if (dp != null && dp.name() != null) {
                String pName = sanitizeIdentifier(dp.name());
                predicateProps.put(pName, Map.of(
                        "type", "boolean",
                        "description", pName + " " + dp.pType() + " " + dp.pValue() + " ZKP koşulu"
                ));
            }
        }
        props.put("predicatesVerified", Map.of(
                "type", "object",
                "properties", predicateProps,
                "description", "Sıfır bilgi ispatı (ZKP) ile doğrulanan dinamik koşul sonuçları"
        ));
        props.put("zkpProofFingerprint", Map.of("type", "string", "description", "Hyperledger AnonCreds kanıtının SHA-256 özeti"));

        Map<String, Object> jsonSchema = new LinkedHashMap<>();
        jsonSchema.put("$schema", "https://json-schema.org/draft/2020-12/schema");
        jsonSchema.put("id", schemaId);
        jsonSchema.put("title", "Dynamic MPC & ZKP Verifiable Credential Schema (" + schemaName + ")");
        jsonSchema.put("type", "object");
        jsonSchema.put("properties", props);
        jsonSchema.put("required", List.of("jobId", "datasetName", "targetMetric", "threshold", "totalParties", "predicatesVerified"));

        return new DynamicVcSchema(
                schemaId,
                schemaType,
                context,
                credentialTypes,
                issuerDid,
                requiredClaims,
                predicateRuleSummaries,
                jsonSchema
        );
    }

    private record AnoncredsRegistrationResult(boolean registered, String schemaId) {}

    private AnoncredsRegistrationResult registerWithLocalAnoncreds(Map<String, Object> payload, String fallbackSchemaName) {
        String defaultSchemaId = "schema:ayrotek:" + fallbackSchemaName + ":1.0.0";
        if (anoncredsUrl == null || anoncredsUrl.isBlank()) {
            return new AnoncredsRegistrationResult(false, defaultSchemaId);
        }

        try {
            String jsonBody = objectMapper.writeValueAsString(payload);
            String targetEndpoint = cleanUrl(anoncredsUrl) + "/api/schema";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(targetEndpoint))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() == 200 || resp.statusCode() == 201) {
                JsonNode root = objectMapper.readTree(resp.body());
                String registeredId = root.path("data").path("id").asText();
                if (registeredId.isBlank()) {
                    registeredId = root.path("id").asText(defaultSchemaId);
                }
                log.info("[DynamicSchemaService] AnonCreds motoruna şema kaydedildi -> ID: {}", registeredId);
                return new AnoncredsRegistrationResult(true, registeredId);
            } else {
                log.debug("[DynamicSchemaService] AnonCreds motoru HTTP {} döndü, yerel fallback şema ID kullanılıyor.", resp.statusCode());
            }
        } catch (Exception e) {
            log.debug("[DynamicSchemaService] AnonCreds motoruna ulaşılamadı (offline), fallback şema ID kullanılıyor: {}", e.getMessage());
        }

        return new AnoncredsRegistrationResult(false, defaultSchemaId);
    }

    private String sanitizeIdentifier(String input) {
        if (input == null) return "unknown";
        return input.trim().toLowerCase().replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
    }

    private String capitalize(String str) {
        if (str == null || str.isEmpty()) return "Consortium";
        return str.substring(0, 1).toUpperCase() + str.substring(1).toLowerCase();
    }

    private String cleanUrl(String url) {
        return url != null ? url.replaceAll("/+$", "") : "http://localhost:3000";
    }
}
