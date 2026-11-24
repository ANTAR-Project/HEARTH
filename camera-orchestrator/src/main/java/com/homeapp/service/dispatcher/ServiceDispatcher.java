package com.homeapp.service.dispatcher;

import com.homeapp.model.dto.DeviceCommand;
import com.homeapp.model.dto.EntityStateDto;
import com.homeapp.model.dto.ServiceCallRequest;
import com.homeapp.model.entity.DeviceEntity;
import com.homeapp.model.entity.EntityStateEntity;
import com.homeapp.repository.DeviceRepository;
import com.homeapp.repository.EntityStateRepository;
import com.homeapp.service.StateEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ServiceDispatcher {

    private static final Logger log = LoggerFactory.getLogger(ServiceDispatcher.class);

    private final StateEngineService stateEngineService;
    private final EntityStateRepository entityRepository;
    private final DeviceRepository deviceRepository;

    // Registry of protocol adapters keyed by protocol name (MQTT, BLE, ZIGBEE, etc.)
    private final Map<String, ProtocolAdapter> adapterRegistry = new ConcurrentHashMap<>();

    @Autowired
    public ServiceDispatcher(StateEngineService stateEngineService,
                             EntityStateRepository entityRepository,
                             DeviceRepository deviceRepository,
                             List<ProtocolAdapter> adapters) {
        this.stateEngineService = stateEngineService;
        this.entityRepository = entityRepository;
        this.deviceRepository = deviceRepository;

        if (adapters != null) {
            for (ProtocolAdapter adapter : adapters) {
                adapterRegistry.put(adapter.getProtocol().toUpperCase(), adapter);
                log.info("Registered Protocol Adapter: [{}] -> {}", adapter.getProtocol(), adapter.getClass().getSimpleName());
            }
        }
    }

    public Map<String, Object> dispatch(ServiceCallRequest request) {
        log.info("Dispatching service call: domain={}, service={}, entityId={}, data={}",
                request.getDomain(), request.getService(), request.getEntityId(), request.getData());

        Map<String, Object> result = new HashMap<>();
        result.put("domain", request.getDomain());
        result.put("service", request.getService());
        result.put("entity_id", request.getEntityId());

        String entityId = request.getEntityId();
        String service = request.getService();
        Map<String, Object> data = request.getData() != null ? request.getData() : Collections.emptyMap();

        // 1. Resolve target entity and its device
        Optional<EntityStateEntity> entityOpt = entityRepository.findById(entityId);
        String deviceId = entityOpt.map(EntityStateEntity::getDeviceId).orElse(null);

        // Fallback: derive deviceId from entityId naming convention if not set (e.g. 'light.living_room' -> 'esp32_relay1')
        if (deviceId == null) {
            deviceId = deriveDeviceId(entityId);
        }

        // 2. Resolve protocol from device
        String protocol = "MQTT";
        if (deviceId != null) {
            Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);
            if (deviceOpt.isPresent() && deviceOpt.get().getProtocol() != null) {
                protocol = deviceOpt.get().getProtocol().toUpperCase();
            }
        }

        // 3. Translate domain service into DeviceCommand
        DeviceCommand command = translateToCommand(service, data, entityId);

        // 4. Route command to appropriate ProtocolAdapter
        ProtocolAdapter adapter = adapterRegistry.get(protocol);
        boolean success = false;

        if (adapter != null) {
            log.info("Routing command for device [{}] to adapter [{}]", deviceId, protocol);
            success = adapter.sendCommand(deviceId, command);
        } else {
            // Revert risky optimistic update: never spoof state when adapter is missing or misconfigured!
            log.warn("No protocol adapter registered for protocol [{}] on device [{}]. Command not dispatched to hardware.",
                    protocol, deviceId);
            success = false;
        }

        result.put("success", success);
        result.put("protocol", protocol);
        result.put("command", command);
        return result;
    }

    DeviceCommand translateToCommand(String service, Map<String, Object> data, String entityId) {
        String targetState;
        Map<String, Object> attributes = new HashMap<>(data);

        switch (service.toLowerCase()) {
            case "turn_on":
                targetState = "ON";
                break;
            case "turn_off":
                targetState = "OFF";
                break;
            case "toggle":
                Optional<EntityStateDto> current = stateEngineService.getState(entityId);
                String currentState = current.map(EntityStateDto::getState).orElse("OFF");
                targetState = "ON".equalsIgnoreCase(currentState) ? "OFF" : "ON";
                break;
            case "set_state":
                targetState = data.containsKey("state") ? String.valueOf(data.get("state")) : "ON";
                break;
            case "lock":
                targetState = "LOCKED";
                break;
            case "unlock":
                targetState = "UNLOCKED";
                break;
            default:
                targetState = service.toUpperCase();
                break;
        }

        return new DeviceCommand(targetState, attributes);
    }

    private String deriveDeviceId(String entityId) {
        if (entityId.contains(".")) {
            return entityId.split("\\.")[1];
        }
        return entityId;
    }

    public Map<String, ProtocolAdapter> getAdapterRegistry() {
        return Collections.unmodifiableMap(adapterRegistry);
    }
}
