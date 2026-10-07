package com.ayrotek.agent.manager.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Kurum İçi Entegrasyon Ajanı Yönetici (Sidecar Manager) Servisi.
 *
 * Temel Sorumluluklar:
 * 1. REGISTRATION (Kayıt): Açılışta Coordinator'a düğüm ID, kurum adı ve Ajan URL'ini bildirir.
 * 2. HEARTBEAT (Canlılık): Periyodik olarak Coordinator'a canlılık sinyali gönderir.
 * 3. HEALTH MONITORING (İzleme): Kurumun yerel Agent ve AnonCreds servislerinin ayakta olduğunu denetler.
 * 4. GOVERNANCE (Onay Takibi): Düğümün konsorsiyum onay durumunu takip eder (PENDING_APPROVAL / APPROVED).
 */
@Service
public class NodeManagerService {

    private static final Logger log = LoggerFactory.getLogger(NodeManagerService.class);

    @Value("${node.id:Banka_A}")
    private String nodeId;

    @Value("${node.name:Banka A Kurum İçi Yönetici}")
    private String nodeName;

    @Value("${node.agent-url:http://localhost:8083}")
    private String agentUrl;

    @Value("${node.manager-url:http://localhost:8093}")
    private String managerUrl;

    @Value("${node.supported-datasets:bankalar,finans,kredi,consortium}")
    private String supportedDatasets;

    @Value("${node.anoncreds-url:http://localhost:3000}")
    private String anoncredsUrl;

    @Value("${node.coordinator-url:http://localhost:8081}")
    private String coordinatorUrl;

    @Value("${node.heartbeat.enabled:true}")
    private boolean heartbeatEnabled;

    private boolean isRegistered = false;
    private boolean isApproved = false;
    private String lastStatusMessage = "INITIALIZING";
    private Instant lastSuccessfulHeartbeat;
    private boolean lastAgentHealth = false;
    private boolean lastAnoncredsHealth = false;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(3))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        log.info("[NodeManager] Kurum Yöneticisi ayağa kalktı. Düğüm: {} ({})", nodeId, nodeName);
        log.info("[NodeManager] Hedef Coordinator: {}", coordinatorUrl);
        log.info("[NodeManager] Yönetilen Ajan URL: {}", agentUrl);
        log.info("[NodeManager] Yönetici Kendi URL: {}", managerUrl);
        log.info("[NodeManager] Desteklenen Veri Setleri: {}", supportedDatasets);
        log.info("[NodeManager] Yönetilen AnonCreds URL: {}", anoncredsUrl);
        registerWithCoordinator();
    }

    public synchronized boolean registerWithCoordinator() {
        try {
            Map<String, String> regBody = new LinkedHashMap<>();
            regBody.put("nodeId", nodeId);
            regBody.put("institutionName", nodeName);
            regBody.put("agentUrl", agentUrl);
            regBody.put("managerUrl", managerUrl);
            regBody.put("supportedDatasets", supportedDatasets);
            String jsonPayload = objectMapper.writeValueAsString(regBody);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl(coordinatorUrl) + "/api/v1/nodes/register"))
                    .timeout(Duration.ofSeconds(5))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                isRegistered = true;
                JsonNode root = objectMapper.readTree(resp.body());
                String status = root.path("approvalStatus").asText("UNKNOWN");
                this.isApproved = "APPROVED".equalsIgnoreCase(status);
                this.lastStatusMessage = "Kayıt Başarılı. Onay: " + status;
                log.info("[NodeManager] Coordinator kaydı tamamlandı: {} -> Onay: {}", nodeId, status);
                return true;
            } else {
                this.lastStatusMessage = "Kayıt Başarısız: HTTP " + resp.statusCode();
                log.warn("[NodeManager] Coordinator kaydı reddetti: HTTP {} -> {}", resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            this.lastStatusMessage = "Coordinator'a ulaşılamadı: " + e.getMessage();
            log.warn("[NodeManager] Coordinator bağlantı hatası: {}", e.getMessage());
        }
        return false;
    }

    @Scheduled(fixedRateString = "${node.heartbeat.interval-ms:10000}")
    public void sendHeartbeat() {
        if (!heartbeatEnabled) {
            return;
        }

        // Yerel servislerin durumunu kontrol et
        checkLocalServicesHealth();

        if (!isRegistered) {
            boolean success = registerWithCoordinator();
            if (!success) {
                return;
            }
        }

        try {
            Map<String, String> hbBody = Map.of("nodeId", nodeId);
            String jsonPayload = objectMapper.writeValueAsString(hbBody);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(cleanUrl(coordinatorUrl) + "/api/v1/nodes/heartbeat"))
                    .timeout(Duration.ofSeconds(3))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());

            if (resp.statusCode() == 200) {
                this.isApproved = true;
                this.lastSuccessfulHeartbeat = Instant.now();
                this.lastStatusMessage = "ONLINE_APPROVED";
                log.debug("[NodeManager] Heartbeat başarılı -> {}", nodeId);
            } else if (resp.statusCode() == 403) {
                this.isApproved = false;
                this.lastStatusMessage = "PENDING_APPROVAL";
                log.info("[NodeManager] Düğüm henüz onaylanmamış (PENDING_APPROVAL)");
            } else if (resp.statusCode() == 404 || resp.statusCode() == 400) {
                log.warn("[NodeManager] Düğüm Coordinator'da bulunamadı, yeniden kayıt deneniyor...");
                registerWithCoordinator();
            }
        } catch (Exception e) {
            this.lastStatusMessage = "Coordinator bağlantı hatası: " + e.getMessage();
            log.debug("[NodeManager] Heartbeat iletilemedi: {}", e.getMessage());
        }
    }

    private void checkLocalServicesHealth() {
        this.lastAgentHealth = pingUrl(cleanUrl(agentUrl) + "/api/v1/agent/status");
        this.lastAnoncredsHealth = pingUrl(cleanUrl(anoncredsUrl) + "/api/schema") || pingUrl(cleanUrl(anoncredsUrl) + "/api/v1/schema");
    }

    private boolean pingUrl(String url) {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            return resp.statusCode() >= 200 && resp.statusCode() < 500;
        } catch (Exception e) {
            return false;
        }
    }

    public Map<String, Object> getNodeStatus() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("nodeId", nodeId);
        map.put("nodeName", nodeName);
        map.put("agentUrl", agentUrl);
        map.put("managerUrl", managerUrl);
        map.put("supportedDatasets", supportedDatasets);
        map.put("anoncredsUrl", anoncredsUrl);
        map.put("coordinatorUrl", coordinatorUrl);
        map.put("isRegistered", isRegistered);
        map.put("isApproved", isApproved);
        map.put("status", lastStatusMessage);
        map.put("agentHealthy", lastAgentHealth);
        map.put("anoncredsHealthy", lastAnoncredsHealth);
        map.put("lastSuccessfulHeartbeat", lastSuccessfulHeartbeat != null ? lastSuccessfulHeartbeat.toString() : "YOK");
        return map;
    }

    private String cleanUrl(String url) {
        return url != null ? url.replaceAll("/+$", "") : "http://localhost:8081";
    }
}
