package com.antar.cameraorchestrator.controller.api;

import com.antar.cameraorchestrator.model.dto.EntityStateDto;
import com.antar.cameraorchestrator.service.StateEngineService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/states")
public class StateApiController {

    private final StateEngineService stateEngineService;

    public StateApiController(StateEngineService stateEngineService) {
        this.stateEngineService = stateEngineService;
    }

    @GetMapping
    public ResponseEntity<List<EntityStateDto>> getAllStates(
            @RequestParam(required = false) String domain,
            @RequestParam(required = false) String area) {

        if (domain != null && !domain.isBlank()) {
            return ResponseEntity.ok(stateEngineService.getStatesByDomain(domain));
        }
        if (area != null && !area.isBlank()) {
            return ResponseEntity.ok(stateEngineService.getStatesByArea(area));
        }
        return ResponseEntity.ok(stateEngineService.getAllStates());
    }

    @GetMapping("/{entityId}")
    public ResponseEntity<EntityStateDto> getState(@PathVariable String entityId) {
        return stateEngineService.getState(entityId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{entityId}")
    public ResponseEntity<EntityStateDto> updateState(
            @PathVariable String entityId,
            @RequestBody Map<String, Object> body) {

        String state = (String) body.get("state");
        if (state == null || state.isBlank()) {
            return ResponseEntity.badRequest().build();
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> attributes = (Map<String, Object>) body.get("attributes");

        EntityStateDto updated = stateEngineService.updateState(entityId, state, attributes);
        return ResponseEntity.ok(updated);
    }
}
