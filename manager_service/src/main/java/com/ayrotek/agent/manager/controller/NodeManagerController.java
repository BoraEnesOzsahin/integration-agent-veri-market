package com.ayrotek.agent.manager.controller;

import com.ayrotek.agent.manager.service.NodeManagerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/manager")
@Tag(name = "Kurum İçi Yönetici API (Node Manager)",
     description = "Düğüm kaydı, canlılık yönetimi ve sağlık izleme. Yalnızca 127.0.0.1'de açılır.")
public class NodeManagerController {

    private final NodeManagerService managerService;

    public NodeManagerController(NodeManagerService managerService) {
        this.managerService = managerService;
    }

    @GetMapping("/status")
    @Operation(summary = "Yönetici ve Düğüm Durumu",
               description = "Düğümün kayıt, canlılık, konsorsiyum onay ve yerel servis sağlık durumlarını döner.")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(managerService.getNodeStatus());
    }

    @PostMapping("/reregister")
    @Operation(summary = "Coordinator'a Yeniden Kaydol",
               description = "Düğümün merkezi Coordinator servisine kaydını zorunlu olarak yeniler.")
    public ResponseEntity<Map<String, Object>> reregister() {
        boolean success = managerService.registerWithCoordinator();
        return ResponseEntity.ok(Map.of(
                "success", success,
                "nodeStatus", managerService.getNodeStatus()
        ));
    }

    @GetMapping("/health")
    @Operation(summary = "Yönetici Sağlık Kontrolü",
               description = "Yönetici servisinin ayakta olup olmadığını kontrol eder.")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "manager", "OK"
        ));
    }
}
