package com.antar.cameraorchestrator.controller.api;

import com.antar.cameraorchestrator.model.dto.ServiceCallRequest;
import com.antar.cameraorchestrator.service.dispatcher.ServiceDispatcher;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Collections;
import java.util.Map;

@RestController
@RequestMapping("/api/services")
public class ServiceApiController {

    private final ServiceDispatcher serviceDispatcher;

    public ServiceApiController(ServiceDispatcher serviceDispatcher) {
        this.serviceDispatcher = serviceDispatcher;
    }

    @PostMapping("/{domain}/{service}")
    public ResponseEntity<Map<String, Object>> callService(
            @PathVariable String domain,
            @PathVariable String service,
            @RequestBody(required = false) Map<String, Object> body) {

        String entityId = null;
        Map<String, Object> data = Collections.emptyMap();

        if (body != null) {
            entityId = (String) body.get("entity_id");
            if (body.containsKey("data") && body.get("data") instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> innerData = (Map<String, Object>) body.get("data");
                data = innerData;
            } else {
                data = body;
            }
        }

        if (entityId == null || entityId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "entity_id is required"));
        }

        ServiceCallRequest request = new ServiceCallRequest(domain, service, entityId, data);
        Map<String, Object> result = serviceDispatcher.dispatch(request);
        return ResponseEntity.ok(result);
    }
}
