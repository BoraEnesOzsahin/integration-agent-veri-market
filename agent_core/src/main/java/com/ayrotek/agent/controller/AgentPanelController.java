package com.ayrotek.agent.controller;

import com.ayrotek.agent.service.AgentActivityLog;
import com.ayrotek.agent.service.AgentDatabaseService;
import com.ayrotek.agent.service.ExposurePolicyService;
import com.ayrotek.agent.service.IntegrationAgentService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

/**
 * Kurumun yerel paneli. Yalnızca ajanın 127.0.0.1'deki panel portundan erişilir (PanelAccessConfig);
 * koordinatörün ve diğer kurumların ulaştığı ajan portunda bu uçlar yoktur.
 */
@RestController
@RequestMapping("/api/v1/panel")
public class AgentPanelController {

    private final IntegrationAgentService agentService;
    private final AgentDatabaseService databaseService;
    private final ExposurePolicyService policy;
    private final AgentActivityLog activity;
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    @Value("${agent.manager.local-url:http://127.0.0.1:8093}")
    private String managerUrl;

    /** Veri setlerinin mantıksal tanımı platformun açık kataloğundan okunur. */
    @Value("${agent.platform.url:}")
    private String platformUrl;

    public AgentPanelController(IntegrationAgentService agentService, AgentDatabaseService databaseService,
                                ExposurePolicyService policy, AgentActivityLog activity) {
        this.agentService = agentService;
        this.databaseService = databaseService;
        this.policy = policy;
        this.activity = activity;
    }

    @GetMapping("/overview")
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("institutionId", agentService.getInstitutionId());
        out.put("institutionName", agentService.getInstitutionName());
        out.put("publicKeyHex", agentService.getAttestationKey().get("publicKeyHex"));
        out.put("databaseConfigured", databaseService.isDbConnected());
        out.put("zkpEngine", agentService.isAnonCredsEngineAvailable());
        out.put("policyFile", policy.file().toString());
        out.put("platformUrl", platformUrl);
        out.put("manager", managerStatus());
        return out;
    }

    @GetMapping("/schema")
    public Map<String, Object> schema() throws SQLException {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("tables", databaseService.describeSchema());
        out.put("policy", policy.current());
        return out;
    }

    public record PolicyRequest(Map<String, ExposurePolicyService.DatasetMapping> datasets) {}

    /** Her eşlenen sütun, seçilen tabloda gerçekten var olan sayısal bir sütun olmalıdır. Kayıttan sonra koordinatördeki liste yenilenir. */
    @PutMapping("/policy")
    public Map<String, Object> savePolicy(@RequestBody PolicyRequest request) throws SQLException {
        Map<String, ExposurePolicyService.DatasetMapping> requested = request == null || request.datasets() == null ? Map.of() : request.datasets();
        for (var entry : requested.entrySet()) {
            var mapping = entry.getValue();
            if (mapping == null || mapping.columns() == null || mapping.columns().values().stream().allMatch(v -> v == null || v.isBlank())) {
                continue;
            }
            if (mapping.table() == null || mapping.table().isBlank()) {
                throw new IllegalArgumentException("'" + entry.getKey() + "' için tablo seçilmedi.");
            }
            List<String> numeric = databaseService.numericColumns(mapping.table().trim().toLowerCase());
            for (var column : mapping.columns().entrySet()) {
                String local = column.getValue();
                if (local != null && !local.isBlank() && !numeric.contains(local.trim().toLowerCase())) {
                    throw new IllegalArgumentException("'" + mapping.table() + "." + local + "' bu veritabanında sayısal bir sütun değil.");
                }
            }
        }
        var saved = policy.replace(requested);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", saved);
        out.put("coordinatorUpdated", reregister());
        return out;
    }

    /**
     * Platformun açık kataloğundaki veri setleri ve mantıksal sütunları. Aynı anahtarı kullanan veri setleri tek satırda toplanır.
     * Eski biçimdeki veri setlerinde sütun tanımı yoktur; yayınlanmış sütunlar gösterilir.
     */
    @GetMapping("/catalog")
    public Map<String, Object> catalog() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (platformUrl == null || platformUrl.isBlank()) {
            out.put("available", false);
            out.put("error", "PLATFORM_URL ayarlı değil.");
            return out;
        }
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(platformUrl.replaceAll("/+$", "") + "/api/v1/chambers"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() != 200) {
                throw new IllegalStateException("HTTP " + resp.statusCode());
            }
            Map<String, Map<String, Object>> byKey = new TreeMap<>();
            for (JsonNode chamber : mapper.readTree(resp.body())) {
                String key = chamber.path("category").asText();
                Map<String, Object> entry = byKey.computeIfAbsent(key, k -> {
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("key", k);
                    e.put("names", new ArrayList<String>());
                    e.put("columns", new LinkedHashMap<String, String>());
                    e.put("member", false);
                    return e;
                });
                @SuppressWarnings("unchecked") List<String> names = (List<String>) entry.get("names");
                names.add(chamber.path("name").asText());
                @SuppressWarnings("unchecked") Map<String, String> columns = (Map<String, String>) entry.get("columns");
                chamber.path("columns").forEach(c -> columns.putIfAbsent(c.path("name").asText(), c.path("label").asText("")));
                if (chamber.path("columns").isEmpty()) {
                    chamber.path("participants").forEach(p -> p.path("detectedSchema").forEach(f -> {
                        if (f.path("encrypted").asBoolean()) columns.putIfAbsent(f.path("fieldName").asText(), "");
                    }));
                }
                chamber.path("participants").forEach(p -> {
                    if (p.path("nodeId").asText().equalsIgnoreCase(agentService.getInstitutionId())) entry.put("member", true);
                });
            }
            out.put("available", true);
            out.put("datasets", byKey.values());
        } catch (Exception e) {
            out.put("available", false);
            out.put("error", "Platform kataloğu okunamadı (" + platformUrl + "): " + e.getMessage());
        }
        return out;
    }

    @GetMapping("/activity")
    public List<AgentActivityLog.Entry> activity() {
        return activity.recent();
    }

    private Map<String, Object> managerStatus() {
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(managerUrl + "/api/v1/manager/status"))
                    .timeout(Duration.ofSeconds(3)).GET().build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = mapper.readTree(resp.body());
            Map<String, Object> out = new LinkedHashMap<>();
            for (String key : List.of("coordinatorUrl", "isRegistered", "isApproved", "status", "lastSuccessfulHeartbeat", "supportedDatasets")) {
                out.put(key, mapper.convertValue(node.path(key), Object.class));
            }
            out.put("reachable", true);
            return out;
        } catch (Exception e) {
            return Map.of("reachable", false, "error", String.valueOf(e.getMessage()));
        }
    }

    /** Yönetici servisi kaydı yenileyince koordinatör yeni tablo listesini görür. */
    private boolean reregister() {
        try {
            HttpResponse<String> resp = http.send(HttpRequest.newBuilder(URI.create(managerUrl + "/api/v1/manager/reregister"))
                    .timeout(Duration.ofSeconds(10)).POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            return resp.statusCode() == 200 && mapper.readTree(resp.body()).path("success").asBoolean(false);
        } catch (Exception e) {
            return false;
        }
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler({SQLException.class, IllegalStateException.class})
    public ResponseEntity<Map<String, String>> failure(Exception e) {
        return ResponseEntity.status(500).body(Map.of("message", e.getMessage()));
    }
}
