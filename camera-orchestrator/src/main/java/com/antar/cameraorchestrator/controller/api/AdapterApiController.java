package com.antar.cameraorchestrator.controller.api;

import com.antar.cameraorchestrator.service.adapter.ble.BleAdapterService;
import com.antar.cameraorchestrator.service.adapter.ble.BleAdvertisementPayload;
import com.antar.cameraorchestrator.service.adapter.zigbee.ZigbeeAdapterService;
import com.antar.cameraorchestrator.service.adapter.zigbee.ZigbeeZclMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/adapters")
public class AdapterApiController {

    private final BleAdapterService bleAdapterService;
    private final ZigbeeAdapterService zigbeeAdapterService;

    @Autowired
    public AdapterApiController(@Autowired(required = false) BleAdapterService bleAdapterService,
                                @Autowired(required = false) ZigbeeAdapterService zigbeeAdapterService) {
        this.bleAdapterService = bleAdapterService;
        this.zigbeeAdapterService = zigbeeAdapterService;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAdaptersStatus() {
        Map<String, Object> response = new HashMap<>();

        response.put("mqtt", Map.of(
                "protocol", "MQTT",
                "enabled", true,
                "type", "MessageDrivenChannelAdapter"
        ));

        if (bleAdapterService != null) {
            response.put("ble", bleAdapterService.getAdapterStatus());
        } else {
            response.put("ble", Map.of("enabled", false));
        }

        if (zigbeeAdapterService != null) {
            response.put("zigbee", zigbeeAdapterService.getAdapterStatus());
        } else {
            response.put("zigbee", Map.of("enabled", false));
        }

        return ResponseEntity.ok(response);
    }

    @GetMapping("/ble")
    public ResponseEntity<?> getBleStatus() {
        if (bleAdapterService == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(bleAdapterService.getAdapterStatus());
    }

    @PostMapping("/ble/advertisement")
    public ResponseEntity<?> ingestBleAdvertisement(@RequestBody BleAdvertisementPayload payload) {
        if (bleAdapterService == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "BLE adapter is not enabled"));
        }
        bleAdapterService.processAdvertisement(payload);
        return ResponseEntity.ok(Map.of("status", "accepted", "protocol", "BLE"));
    }

    @GetMapping("/zigbee")
    public ResponseEntity<?> getZigbeeStatus() {
        if (zigbeeAdapterService == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(zigbeeAdapterService.getAdapterStatus());
    }

    @PostMapping("/zigbee/report")
    public ResponseEntity<?> ingestZigbeeReport(@RequestBody ZigbeeZclMessage message) {
        if (zigbeeAdapterService == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Zigbee adapter is not enabled"));
        }
        zigbeeAdapterService.processZclReport(message);
        return ResponseEntity.ok(Map.of("status", "accepted", "protocol", "ZIGBEE"));
    }

    @PostMapping("/zigbee/permit-join")
    public ResponseEntity<?> setPermitJoin(@RequestBody Map<String, Object> body) {
        if (zigbeeAdapterService == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Zigbee adapter is not enabled"));
        }
        boolean enable = Boolean.TRUE.equals(body.get("enable"));
        Integer duration = body.containsKey("duration_seconds")
                ? ((Number) body.get("duration_seconds")).intValue()
                : null;
        boolean active = zigbeeAdapterService.setPermitJoin(enable, duration);
        return ResponseEntity.ok(Map.of(
                "permit_join", active,
                "duration_seconds", duration != null ? duration : 180
        ));
    }
}
