package com.homeapp.service.adapter.ble;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeapp.config.BleConfig;
import com.homeapp.model.entity.DeviceEntity;
import com.homeapp.model.entity.EntityStateEntity;
import com.homeapp.model.event.DeviceStatusChangedEvent;
import com.homeapp.repository.DeviceRepository;
import com.homeapp.repository.EntityStateRepository;
import com.homeapp.service.StateEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "ble.enabled", havingValue = "true", matchIfMissing = true)
public class BleInboundHandler {

    private static final Logger log = LoggerFactory.getLogger(BleInboundHandler.class);

    private final StateEngineService stateEngineService;
    private final DeviceRepository deviceRepository;
    private final EntityStateRepository entityRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final BleConfig bleConfig;
    private final ObjectMapper objectMapper;

    public BleInboundHandler(StateEngineService stateEngineService,
                             DeviceRepository deviceRepository,
                             EntityStateRepository entityRepository,
                             ApplicationEventPublisher eventPublisher,
                             BleConfig bleConfig,
                             ObjectMapper objectMapper) {
        this.stateEngineService = stateEngineService;
        this.deviceRepository = deviceRepository;
        this.entityRepository = entityRepository;
        this.eventPublisher = eventPublisher;
        this.bleConfig = bleConfig;
        this.objectMapper = objectMapper;
    }

    public void handleMqttMessage(String topic, String payloadStr) {
        log.debug("Received BLE Proxy message on [{}]: {}", topic, payloadStr);
        try {
            if (topic.endsWith("/status")) {
                String[] parts = topic.split("/");
                String deviceId = parts.length >= 2 ? parts[1] : null;
                if (deviceId != null) {
                    boolean online = parseOnlineStatus(payloadStr);
                    if (online) {
                        updateDeviceStatus(deviceId, null, null, true);
                        eventPublisher.publishEvent(new DeviceStatusChangedEvent(this, deviceId, true));
                    } else {
                        handleDisconnect(deviceId);
                    }
                }
                return;
            }

            BleAdvertisementPayload payload = objectMapper.readValue(payloadStr, BleAdvertisementPayload.class);
            if ((payload.getDeviceId() == null || payload.getDeviceId().isBlank()) && topic.contains("/")) {
                String[] parts = topic.split("/");
                if (parts.length >= 2 && !"advertisement".equals(parts[1])) {
                    payload.setDeviceId(parts[1]);
                }
            }
            handleAdvertisement(payload);

        } catch (Exception e) {
            log.error("Failed to process BLE Proxy message on topic [{}]: {}", topic, e.getMessage(), e);
        }
    }

    public void handleAdvertisement(BleAdvertisementPayload payload) {
        if (payload == null) return;

        String macAddress = payload.getMacAddress();
        String deviceId = payload.getDeviceId();
        if ((deviceId == null || deviceId.isBlank()) && macAddress != null) {
            deviceId = "ble_" + macAddress.replace(":", "").toLowerCase();
        }

        if (deviceId == null || deviceId.isBlank()) {
            log.warn("BLE advertisement rejected: missing deviceId and macAddress");
            return;
        }

        if (payload.getRssi() != null && payload.getRssi() < bleConfig.getRssiThreshold()) {
            log.debug("BLE advertisement ignored due to weak RSSI: {} < {}", payload.getRssi(), bleConfig.getRssiThreshold());
            return;
        }

        log.debug("Processing BLE advertisement from device [{}], MAC [{}]", deviceId, macAddress);

        // 1. Update or Auto-discover Device
        updateDeviceStatus(deviceId, macAddress, payload.getDeviceName(), true);

        // 2. Publish DeviceStatusChangedEvent
        eventPublisher.publishEvent(new DeviceStatusChangedEvent(this, deviceId, true));

        // 3. Process Telemetry and States
        Map<String, Object> baseAttributes = new HashMap<>();
        if (payload.getRssi() != null) {
            baseAttributes.put("rssi", payload.getRssi());
        }
        if (payload.getBattery() != null) {
            baseAttributes.put("battery", payload.getBattery());
        }
        if (macAddress != null) {
            baseAttributes.put("mac_address", macAddress);
        }
        baseAttributes.put("protocol", "BLE");
        baseAttributes.put("available", true);

        Map<String, Object> data = payload.getData() != null ? payload.getData() : new HashMap<>();

        if (data.containsKey("temperature")) {
            String tempEntityId = "sensor." + deviceId + "_temp";
            Map<String, Object> tempAttrs = new HashMap<>(baseAttributes);
            tempAttrs.put("unit_of_measurement", "°C");
            tempAttrs.put("device_class", "temperature");
            stateEngineService.updateState(tempEntityId, String.valueOf(data.get("temperature")), tempAttrs);
        }

        if (data.containsKey("humidity")) {
            String humEntityId = "sensor." + deviceId + "_humidity";
            Map<String, Object> humAttrs = new HashMap<>(baseAttributes);
            humAttrs.put("unit_of_measurement", "%");
            humAttrs.put("device_class", "humidity");
            stateEngineService.updateState(humEntityId, String.valueOf(data.get("humidity")), humAttrs);
        }

        if (payload.getState() != null) {
            String entityId = payload.getEntityId();
            if (entityId == null || entityId.isBlank()) {
                List<EntityStateEntity> entities = entityRepository.findByDeviceId(deviceId);
                if (!entities.isEmpty()) {
                    entityId = entities.get(0).getId();
                } else {
                    String domain = payload.getDomain() != null ? payload.getDomain() : "sensor";
                    entityId = domain + "." + deviceId;
                }
            }
            Map<String, Object> attrs = new HashMap<>(baseAttributes);
            attrs.putAll(data);
            stateEngineService.updateState(entityId, payload.getState(), attrs);
        }
    }

    public void handleDisconnect(String deviceId) {
        log.info("BLE device [{}] disconnected or timed out", deviceId);
        updateDeviceStatus(deviceId, null, null, false);
        eventPublisher.publishEvent(new DeviceStatusChangedEvent(this, deviceId, false));

        List<EntityStateEntity> entities = entityRepository.findByDeviceId(deviceId);
        for (EntityStateEntity entity : entities) {
            if (!"unavailable".equalsIgnoreCase(entity.getCurrentState())) {
                Map<String, Object> attributes = new HashMap<>();
                attributes.put("restored_state", entity.getCurrentState());
                attributes.put("available", false);
                stateEngineService.updateState(entity.getId(), "unavailable", attributes);
            }
        }
    }

    private boolean parseOnlineStatus(String payload) {
        if (payload == null) return false;
        String lower = payload.toLowerCase().trim();
        if ("online".equals(lower) || "true".equals(lower) || "1".equals(lower)) return true;
        if ("offline".equals(lower) || "false".equals(lower) || "0".equals(lower)) return false;
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (node.has("state")) return parseOnlineStatus(node.get("state").asText());
            if (node.has("status")) return parseOnlineStatus(node.get("status").asText());
        } catch (Exception ignored) {}
        return false;
    }

    private void updateDeviceStatus(String deviceId, String macAddress, String deviceName, boolean online) {
        LocalDateTime now = LocalDateTime.now();
        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);

        if (deviceOpt.isPresent()) {
            DeviceEntity device = deviceOpt.get();
            device.setOnline(online);
            if (online) {
                device.setLastSeen(now);
            }
            if (macAddress != null && (device.getMacAddress() == null || device.getMacAddress().isBlank())) {
                device.setMacAddress(macAddress);
            }
            deviceRepository.save(device);
        } else {
            DeviceEntity newDevice = new DeviceEntity();
            newDevice.setId(deviceId);
            newDevice.setName(deviceName != null && !deviceName.isBlank() ? deviceName : "BLE Device " + deviceId);
            newDevice.setProtocol("BLE");
            newDevice.setMacAddress(macAddress);
            newDevice.setMqttTopicPrefix("ble/" + deviceId);
            newDevice.setOnline(online);
            newDevice.setLastSeen(now);
            deviceRepository.save(newDevice);
            log.info("Auto-registered new BLE device: {} (MAC: {}, online={})", deviceId, macAddress, online);
        }
    }
}
