package com.homeapp.controller.web;

import com.homeapp.model.dto.EntityStateDto;
import com.homeapp.model.entity.AreaEntity;
import com.homeapp.model.entity.DeviceEntity;
import com.homeapp.repository.AreaRepository;
import com.homeapp.repository.AutomationRuleRepository;
import com.homeapp.repository.DeviceRepository;
import com.homeapp.service.StateEngineService;
import com.homeapp.service.adapter.ble.BleAdapterService;
import com.homeapp.service.adapter.zigbee.ZigbeeAdapterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.*;
import java.util.stream.Collectors;

@Controller
public class DashboardWebController {

    private final StateEngineService stateEngineService;
    private final DeviceRepository deviceRepository;
    private final AreaRepository areaRepository;
    private final AutomationRuleRepository automationRuleRepository;
    private final BleAdapterService bleAdapterService;
    private final ZigbeeAdapterService zigbeeAdapterService;

    @Autowired
    public DashboardWebController(StateEngineService stateEngineService,
                                  DeviceRepository deviceRepository,
                                  AreaRepository areaRepository,
                                  AutomationRuleRepository automationRuleRepository,
                                  @Autowired(required = false) BleAdapterService bleAdapterService,
                                  @Autowired(required = false) ZigbeeAdapterService zigbeeAdapterService) {
        this.stateEngineService = stateEngineService;
        this.deviceRepository = deviceRepository;
        this.areaRepository = areaRepository;
        this.automationRuleRepository = automationRuleRepository;
        this.bleAdapterService = bleAdapterService;
        this.zigbeeAdapterService = zigbeeAdapterService;
    }

    @GetMapping({"/", "/iot", "/dashboard"})
    public String iot(Model model) {
        List<AreaEntity> areas = areaRepository.findAll();
        List<DeviceEntity> devices = deviceRepository.findAll();
        List<EntityStateDto> allStates = stateEngineService.getAllStates();

        long onlineDevices = devices.stream().filter(DeviceEntity::isOnline).count();
        long activeAutomations = automationRuleRepository.findByEnabledTrue().size();

        // Group entities by area
        Map<String, List<EntityStateDto>> entitiesByArea = new LinkedHashMap<>();
        for (AreaEntity area : areas) {
            List<EntityStateDto> areaStates = stateEngineService.getStatesByArea(area.getId());
            if (!areaStates.isEmpty()) {
                entitiesByArea.put(area.getId(), areaStates);
            }
        }

        // Summary metrics
        Map<String, Object> stats = new HashMap<>();
        stats.put("totalDevices", devices.size());
        stats.put("onlineDevices", onlineDevices);
        stats.put("totalEntities", allStates.size());
        stats.put("activeAutomations", activeAutomations);

        model.addAttribute("areas", areas);
        model.addAttribute("entitiesByArea", entitiesByArea);
        model.addAttribute("devices", devices);
        model.addAttribute("allStates", allStates);
        model.addAttribute("stats", stats);
        model.addAttribute("currentPage", "iot");

        return "iot";
    }

    @GetMapping("/nas")
    public String nas(Model model) {
        model.addAttribute("currentPage", "nas");
        return "nas";
    }
}
