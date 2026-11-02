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

    public record PolicyRequest(Map<String, List<String>> tables) {}

    /** Seçilen her sütun veritabanında gerçekten var olan sayısal bir sütun olmalıdır. Kayıttan sonra koordinatördeki liste yenilenir. */
    @PutMapping("/policy")
    public Map<String, Object> savePolicy(@RequestBody PolicyRequest request) throws SQLException {
        Map<String, List<String>> requested = request == null || request.tables() == null ? Map.of() : request.tables();
        for (var entry : requested.entrySet()) {
            List<String> columns = entry.getValue() == null ? List.of() : entry.getValue();
            if (columns.isEmpty()) {
                continue;
            }
            List<String> numeric = databaseService.numericColumns(entry.getKey().trim().toLowerCase());
            for (String column : columns) {
                if (!numeric.contains(column.trim().toLowerCase())) {
                    throw new IllegalArgumentException("'" + entry.getKey() + "." + column + "' bu veritabanında sayısal bir sütun değil.");
                }
            }
        }
        var saved = policy.replace(requested);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("policy", saved);
        out.put("coordinatorUpdated", reregister());
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
