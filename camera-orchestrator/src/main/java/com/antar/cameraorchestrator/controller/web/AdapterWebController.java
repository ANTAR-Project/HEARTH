package com.antar.cameraorchestrator.controller.web;

import com.antar.cameraorchestrator.service.adapter.ble.BleAdapterService;
import com.antar.cameraorchestrator.service.adapter.zigbee.ZigbeeAdapterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.Map;

@Controller
public class AdapterWebController {

    private final BleAdapterService bleAdapterService;
    private final ZigbeeAdapterService zigbeeAdapterService;
    private final com.antar.cameraorchestrator.service.adapter.camera.CameraAdapterService cameraAdapterService;

    @Autowired
    public AdapterWebController(@Autowired(required = false) BleAdapterService bleAdapterService,
                                @Autowired(required = false) ZigbeeAdapterService zigbeeAdapterService,
                                @Autowired(required = false) com.antar.cameraorchestrator.service.adapter.camera.CameraAdapterService cameraAdapterService) {
        this.bleAdapterService = bleAdapterService;
        this.zigbeeAdapterService = zigbeeAdapterService;
        this.cameraAdapterService = cameraAdapterService;
    }

    @GetMapping("/adapters")
    public String adapters(Model model) {
        Map<String, Object> mqttStatus = Map.of(
                "protocol", "MQTT",
                "broker", "tcp://localhost:1883",
                "status", "CONNECTED",
                "client_id", "home-assistant-backend"
        );

        Map<String, Object> bleStatus = bleAdapterService != null
                ? bleAdapterService.getAdapterStatus()
                : Map.of("protocol", "BLE", "enabled", false);

        Map<String, Object> zigbeeStatus = zigbeeAdapterService != null
                ? zigbeeAdapterService.getAdapterStatus()
                : Map.of("protocol", "ZIGBEE", "enabled", false);

        Map<String, Object> cameraStatus = cameraAdapterService != null
                ? cameraAdapterService.getAdapterStatus()
                : Map.of("protocol", "CAMERA", "enabled", false);

        model.addAttribute("mqtt", mqttStatus);
        model.addAttribute("ble", bleStatus);
        model.addAttribute("zigbee", zigbeeStatus);
        model.addAttribute("camera", cameraStatus);
        model.addAttribute("currentPage", "adapters");

        return "adapters";
    }
}
