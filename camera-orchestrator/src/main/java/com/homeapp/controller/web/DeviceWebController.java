package com.homeapp.controller.web;

import com.homeapp.model.dto.EntityStateDto;
import com.homeapp.model.entity.AreaEntity;
import com.homeapp.model.entity.DeviceEntity;
import com.homeapp.repository.AreaRepository;
import com.homeapp.repository.DeviceRepository;
import com.homeapp.service.StateEngineService;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Controller
public class DeviceWebController {

    private final DeviceRepository deviceRepository;
    private final AreaRepository areaRepository;
    private final StateEngineService stateEngineService;

    public DeviceWebController(DeviceRepository deviceRepository,
                               AreaRepository areaRepository,
                               StateEngineService stateEngineService) {
        this.deviceRepository = deviceRepository;
        this.areaRepository = areaRepository;
        this.stateEngineService = stateEngineService;
    }

    @GetMapping("/devices")
    public String devices(Model model) {
        List<DeviceEntity> devices = deviceRepository.findAll();
        Map<String, String> areaNames = areaRepository.findAll().stream()
                .collect(Collectors.toMap(AreaEntity::getId, AreaEntity::getName, (a, b) -> a));

        Map<String, List<EntityStateDto>> entitiesByDevice = new HashMap<>();
        for (DeviceEntity d : devices) {
            List<EntityStateDto> states = stateEngineService.getAllStates().stream()
                    .filter(s -> d.getId().equals(s.getDeviceId()) || s.getEntityId().contains(d.getId()))
                    .collect(Collectors.toList());
            entitiesByDevice.put(d.getId(), states);
        }

        long mqttCount = devices.stream().filter(d -> "MQTT".equalsIgnoreCase(d.getProtocol())).count();
        long bleCount = devices.stream().filter(d -> "BLE".equalsIgnoreCase(d.getProtocol())).count();
        long zigbeeCount = devices.stream().filter(d -> "ZIGBEE".equalsIgnoreCase(d.getProtocol())).count();
        long cameraCount = devices.stream().filter(d -> "CAMERA".equalsIgnoreCase(d.getProtocol())).count();

        model.addAttribute("devices", devices);
        model.addAttribute("areaNames", areaNames);
        model.addAttribute("entitiesByDevice", entitiesByDevice);
        model.addAttribute("mqttCount", mqttCount);
        model.addAttribute("bleCount", bleCount);
        model.addAttribute("zigbeeCount", zigbeeCount);
        model.addAttribute("cameraCount", cameraCount);
        model.addAttribute("currentPage", "devices");

        return "devices";
    }
}
