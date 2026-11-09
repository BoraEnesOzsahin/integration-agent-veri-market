package com.ayrotek.agent.manager.controller;

import com.ayrotek.agent.manager.service.NodeManagerService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/manager")
public class NodeManagerController {

    private final NodeManagerService managerService;

    public NodeManagerController(NodeManagerService managerService) {
        this.managerService = managerService;
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(managerService.getNodeStatus());
    }

    @PostMapping("/reregister")
    public ResponseEntity<Map<String, Object>> reregister() {
        boolean success = managerService.registerWithCoordinator();
        return ResponseEntity.ok(Map.of(
                "success", success,
                "nodeStatus", managerService.getNodeStatus()
        ));
    }

    @GetMapping("/health")
    public ResponseEntity<Map<String, Object>> health() {
        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "manager", "OK"
        ));
    }
}
