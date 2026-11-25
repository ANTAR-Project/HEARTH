package com.antar.cameraorchestrator.controller.api;

import com.antar.cameraorchestrator.model.entity.AreaEntity;
import com.antar.cameraorchestrator.repository.AreaRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/areas")
public class AreaApiController {

    private final AreaRepository areaRepository;

    public AreaApiController(AreaRepository areaRepository) {
        this.areaRepository = areaRepository;
    }

    @GetMapping
    public ResponseEntity<List<AreaEntity>> getAllAreas() {
        return ResponseEntity.ok(areaRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AreaEntity> getAreaById(@PathVariable String id) {
        return areaRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AreaEntity> createArea(@RequestBody AreaEntity area) {
        if (area.getId() == null || area.getId().isBlank()) {
            area.setId(area.getName().toLowerCase().replace(" ", "_"));
        }
        AreaEntity saved = areaRepository.save(area);
        return ResponseEntity.ok(saved);
    }
}
