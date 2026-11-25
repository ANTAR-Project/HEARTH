package com.antar.cameraorchestrator.controller.api;

import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/devices")
public class DeviceApiController {

    private final DeviceRepository deviceRepository;

    public DeviceApiController(DeviceRepository deviceRepository) {
        this.deviceRepository = deviceRepository;
    }

    @GetMapping
    public ResponseEntity<List<DeviceEntity>> getAllDevices(
            @RequestParam(required = false) String area,
            @RequestParam(required = false) Boolean online,
            @RequestParam(required = false) String protocol) {

        if (area != null) {
            return ResponseEntity.ok(deviceRepository.findByAreaId(area));
        }
        if (online != null) {
            return ResponseEntity.ok(deviceRepository.findByOnline(online));
        }
        if (protocol != null) {
            return ResponseEntity.ok(deviceRepository.findByProtocol(protocol.toUpperCase()));
        }
        return ResponseEntity.ok(deviceRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<DeviceEntity> getDeviceById(@PathVariable String id) {
        return deviceRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<DeviceEntity> registerDevice(@RequestBody DeviceEntity device) {
        DeviceEntity saved = deviceRepository.save(device);
        return ResponseEntity.ok(saved);
    }
}
