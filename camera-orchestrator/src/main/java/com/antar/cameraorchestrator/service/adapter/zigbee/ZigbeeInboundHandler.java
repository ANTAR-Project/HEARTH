package com.antar.cameraorchestrator.service.adapter.zigbee;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.antar.cameraorchestrator.config.ZigbeeConfig;
import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.model.entity.EntityStateEntity;
import com.antar.cameraorchestrator.model.event.DeviceStatusChangedEvent;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import com.antar.cameraorchestrator.repository.EntityStateRepository;
import com.antar.cameraorchestrator.service.StateEngineService;
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
@ConditionalOnProperty(name = "zigbee.enabled", havingValue = "true", matchIfMissing = true)
public class ZigbeeInboundHandler {

    private static final Logger log = LoggerFactory.getLogger(ZigbeeInboundHandler.class);

    private final StateEngineService stateEngineService;
    private final DeviceRepository deviceRepository;
    private final EntityStateRepository entityRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ZigbeeConfig zigbeeConfig;
    private final ObjectMapper objectMapper;

    public ZigbeeInboundHandler(StateEngineService stateEngineService,
                                DeviceRepository deviceRepository,
                                EntityStateRepository entityRepository,
                                ApplicationEventPublisher eventPublisher,
                                ZigbeeConfig zigbeeConfig,
                                ObjectMapper objectMapper) {
        this.stateEngineService = stateEngineService;
        this.deviceRepository = deviceRepository;
        this.entityRepository = entityRepository;
        this.eventPublisher = eventPublisher;
        this.zigbeeConfig = zigbeeConfig;
        this.objectMapper = objectMapper;
    }

    public void handleMqttMessage(String topic, String payloadStr) {
        log.debug("Received Zigbee2MQTT message on [{}]: {}", topic, payloadStr);
        try {
            if (topic.endsWith("/availability")) {
                String deviceId = topic.substring("zigbee2mqtt/".length(), topic.length() - "/availability".length());
                boolean online = parseOnlineStatus(payloadStr);
                if (online) {
                    updateDeviceStatus(deviceId, null, null, true);
                    eventPublisher.publishEvent(new DeviceStatusChangedEvent(this, deviceId, true));
                } else {
                    handleDeviceOffline(deviceId);
                }
                return;
            }

            if (topic.startsWith("zigbee2mqtt/bridge/")) {
                log.info("Received Zigbee2MQTT bridge message on [{}]: {}", topic, payloadStr);
                return;
            }

            String deviceId = topic.substring("zigbee2mqtt/".length());
            if (deviceId.contains("/")) {
                deviceId = deviceId.substring(0, deviceId.indexOf('/'));
            }

            if (deviceId.isBlank()) {
                log.warn("Could not extract deviceId from Zigbee2MQTT topic: {}", topic);
                return;
            }

            Map<String, Object> attrs = objectMapper.readValue(payloadStr, new TypeReference<Map<String, Object>>() {});
            handleDeviceTelemetry(deviceId, attrs);

        } catch (Exception e) {
            log.error("Failed to process Zigbee2MQTT message on topic [{}]: {}", topic, e.getMessage(), e);
        }
    }

    public void handleDeviceTelemetry(String deviceId, Map<String, Object> attrs) {
        if (attrs == null) attrs = new HashMap<>();

        // 1. Update or Auto-discover Device
        String ieee = attrs.get("ieee_address") != null ? attrs.get("ieee_address").toString() : null;
        String name = attrs.get("device_name") != null ? attrs.get("device_name").toString() : "Zigbee " + deviceId;
        updateDeviceStatus(deviceId, ieee, name, true);

        // 2. Publish DeviceStatusChangedEvent
        eventPublisher.publishEvent(new DeviceStatusChangedEvent(this, deviceId, true));

        // 3. Prepare base attributes
        Map<String, Object> baseAttrs = new HashMap<>(attrs);
        baseAttrs.put("protocol", "ZIGBEE");
        baseAttrs.put("available", true);
        if (attrs.containsKey("linkquality")) {
            baseAttrs.put("lqi", attrs.get("linkquality"));
        }

        // 4. Update states
        if (attrs.containsKey("temperature")) {
            double temp = parseMeasurementValue(attrs.get("temperature"), 1.0);
            String entityId = "sensor." + deviceId + "_temperature";
            Map<String, Object> tempAttrs = new HashMap<>(baseAttrs);
            tempAttrs.put("unit_of_measurement", "°C");
            tempAttrs.put("device_class", "temperature");
            stateEngineService.updateState(entityId, String.format(java.util.Locale.ROOT, "%.1f", temp), tempAttrs);
        }

        if (attrs.containsKey("humidity")) {
            double hum = parseMeasurementValue(attrs.get("humidity"), 1.0);
            String entityId = "sensor." + deviceId + "_humidity";
            Map<String, Object> humAttrs = new HashMap<>(baseAttrs);
            humAttrs.put("unit_of_measurement", "%");
            humAttrs.put("device_class", "humidity");
            stateEngineService.updateState(entityId, String.format(java.util.Locale.ROOT, "%.1f", hum), humAttrs);
        }

        if (attrs.containsKey("occupancy") || attrs.containsKey("motion")) {
            boolean motion = parseZoneAlarm(attrs);
            String entityId = "binary_sensor." + deviceId + "_motion";
            Map<String, Object> motionAttrs = new HashMap<>(baseAttrs);
            motionAttrs.put("device_class", "motion");
            stateEngineService.updateState(entityId, motion ? "ON" : "OFF", motionAttrs);
        }

        if (attrs.containsKey("power")) {
            double power = parseMeasurementValue(attrs.get("power"), 1.0);
            String entityId = "sensor." + deviceId + "_power";
            Map<String, Object> powerAttrs = new HashMap<>(baseAttrs);
            powerAttrs.put("unit_of_measurement", "W");
            powerAttrs.put("device_class", "power");
            stateEngineService.updateState(entityId, String.format(java.util.Locale.ROOT, "%.1f", power), powerAttrs);
        }

        if (attrs.containsKey("state")) {
            String state = parseOnOffState(attrs.get("state"));
            String entityId = resolveEntityId("switch", deviceId);
            stateEngineService.updateState(entityId, state, baseAttrs);
        }
    }

    public void handleZclMessage(ZigbeeZclMessage message) {
        if (message == null) return;

        String ieee = message.getIeeeAddress();
        String deviceId = message.getDeviceId();
        if ((deviceId == null || deviceId.isBlank()) && ieee != null) {
            deviceId = "zigbee_" + ieee.replace(":", "").toLowerCase();
        }
        if (deviceId == null || deviceId.isBlank()) return;

        Map<String, Object> attrs = new HashMap<>(message.getAttributes() != null ? message.getAttributes() : Map.of());
        if (message.getLinkQuality() != null) {
            attrs.put("linkquality", message.getLinkQuality());
        }
        if (ieee != null) {
            attrs.put("ieee_address", ieee);
        }

        Integer clusterId = message.getClusterId();
        if (clusterId != null) {
            if (clusterId == ZigbeeZclMessage.CLUSTER_TEMPERATURE) {
                Object val = attrs.getOrDefault("measuredValue", attrs.get("temperature"));
                double tempC = parseMeasurementValue(val, 100.0);
                attrs.put("temperature", tempC);
            } else if (clusterId == ZigbeeZclMessage.CLUSTER_HUMIDITY) {
                Object val = attrs.getOrDefault("measuredValue", attrs.get("humidity"));
                double hum = parseMeasurementValue(val, 100.0);
                attrs.put("humidity", hum);
            } else if (clusterId == ZigbeeZclMessage.CLUSTER_IAS_ZONE || clusterId == ZigbeeZclMessage.CLUSTER_OCCUPANCY) {
                attrs.put("occupancy", parseZoneAlarm(attrs));
            } else if (clusterId == ZigbeeZclMessage.CLUSTER_ON_OFF) {
                attrs.put("state", parseOnOffState(attrs.getOrDefault("onOff", attrs.get("state"))));
            }
        }

        handleDeviceTelemetry(deviceId, attrs);
    }

    public void handleDeviceOffline(String deviceId) {
        log.info("Zigbee device [{}] marked offline", deviceId);
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

    private String resolveEntityId(String defaultDomain, String deviceId) {
        List<EntityStateEntity> entities = entityRepository.findByDeviceId(deviceId);
        if (!entities.isEmpty()) {
            return entities.get(0).getId();
        }
        return defaultDomain + "." + deviceId;
    }

    private String parseOnOffState(Object value) {
        if (value == null) return "OFF";
        if (value instanceof Boolean) {
            return (Boolean) value ? "ON" : "OFF";
        }
        if (value instanceof Number) {
            return ((Number) value).intValue() > 0 ? "ON" : "OFF";
        }
        String s = value.toString().trim().toUpperCase();
        return ("ON".equals(s) || "TRUE".equals(s) || "1".equals(s)) ? "ON" : "OFF";
    }

    private double parseMeasurementValue(Object value, double divisor) {
        if (value instanceof Number) {
            double d = ((Number) value).doubleValue();
            return d > 500 ? d / divisor : d;
        }
        try {
            double d = Double.parseDouble(String.valueOf(value));
            return d > 500 ? d / divisor : d;
        } catch (Exception e) {
            return 0.0;
        }
    }

    private boolean parseZoneAlarm(Map<String, Object> attrs) {
        Object zoneStatus = attrs.getOrDefault("zoneStatus", attrs.get("occupancy"));
        if (zoneStatus instanceof Boolean) return (Boolean) zoneStatus;
        if (zoneStatus instanceof Number) {
            int val = ((Number) zoneStatus).intValue();
            return (val & 0x01) != 0 || val > 0;
        }
        if (zoneStatus != null) {
            String s = zoneStatus.toString().trim().toUpperCase();
            return "ON".equals(s) || "TRUE".equals(s) || "1".equals(s);
        }
        return false;
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

    private void updateDeviceStatus(String deviceId, String ieeeAddress, String deviceName, boolean online) {
        LocalDateTime now = LocalDateTime.now();
        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);

        if (deviceOpt.isPresent()) {
            DeviceEntity device = deviceOpt.get();
            device.setOnline(online);
            if (online) {
                device.setLastSeen(now);
            }
            if (ieeeAddress != null && (device.getMacAddress() == null || device.getMacAddress().isBlank())) {
                device.setMacAddress(ieeeAddress);
            }
            deviceRepository.save(device);
        } else {
            DeviceEntity newDevice = new DeviceEntity();
            newDevice.setId(deviceId);
            newDevice.setName(deviceName != null && !deviceName.isBlank() ? deviceName : "Zigbee Device " + deviceId);
            newDevice.setProtocol("ZIGBEE");
            newDevice.setMacAddress(ieeeAddress);
            newDevice.setMqttTopicPrefix("zigbee2mqtt/" + deviceId);
            newDevice.setOnline(online);
            newDevice.setLastSeen(now);
            deviceRepository.save(newDevice);
            log.info("Auto-registered new Zigbee2MQTT device: {} (online={})", deviceId, online);
        }
    }
}
