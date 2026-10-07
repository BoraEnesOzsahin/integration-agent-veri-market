package com.ayrotek.agent.manager.controller;

import com.ayrotek.agent.manager.model.PredicateRequest;
import com.ayrotek.agent.manager.model.PredicateSchemaResponse;
import com.ayrotek.agent.manager.service.DynamicSchemaService;
import com.ayrotek.agent.manager.service.NodeManagerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/manager")
@Tag(name = "Kurum İçi Yönetici API (Node Manager)",
     description = "Düğüm kaydı, canlılık yönetimi, sağlık izleme ve dinamik ZKP / VC şema türetme uç noktaları")
public class NodeManagerController {

    private final NodeManagerService managerService;
    private final DynamicSchemaService dynamicSchemaService;

    public NodeManagerController(NodeManagerService managerService, DynamicSchemaService dynamicSchemaService) {
        this.managerService = managerService;
        this.dynamicSchemaService = dynamicSchemaService;
    }

    @GetMapping("/status")
    @Operation(summary = "Yönetici ve Düğüm Durumu",
               description = "Düğümün kayıt, canlılık, konsorsiyum onay ve yerel servis sağlık durumlarını döner.")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(managerService.getNodeStatus());
    }

    @PostMapping({"/predicate-schema", "/schema/resolve", "/schema/generate"})
    @Operation(summary = "Dinamik Predicate Talebine Göre ZKP ve VC Şeması Türet",
               description = "Koordinatörden gelen analitik predicate talebini alır. Hyperledger AnonCreds ZKP şemasını ve W3C Verifiable Credential (VC) JSON şemasını dinamik olarak türetir.")
    public ResponseEntity<PredicateSchemaResponse> resolvePredicateSchema(@RequestBody(required = false) PredicateRequest request) {
        return ResponseEntity.ok(dynamicSchemaService.resolveDynamicSchema(request));
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
