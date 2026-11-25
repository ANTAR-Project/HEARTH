package com.antar.cameraorchestrator.service.adapter.mqtt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.antar.cameraorchestrator.model.dto.TelemetryMessage;
import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.model.entity.EntityStateEntity;
import com.antar.cameraorchestrator.model.event.DeviceStatusChangedEvent;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import com.antar.cameraorchestrator.repository.EntityStateRepository;
import com.antar.cameraorchestrator.service.StateEngineService;
import com.antar.cameraorchestrator.service.adapter.ble.BleInboundHandler;
import com.antar.cameraorchestrator.service.adapter.zigbee.ZigbeeInboundHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.integration.annotation.ServiceActivator;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
@ConditionalOnProperty(name = "mqtt.enabled", havingValue = "true", matchIfMissing = true)
public class MqttInboundHandler {

    private static final Logger log = LoggerFactory.getLogger(MqttInboundHandler.class);

    private final StateEngineService stateEngineService;
    private final DeviceRepository deviceRepository;
    private final EntityStateRepository entityRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;
    private final ZigbeeInboundHandler zigbeeInboundHandler;
    private final BleInboundHandler bleInboundHandler;

    @Autowired
    public MqttInboundHandler(StateEngineService stateEngineService,
                              DeviceRepository deviceRepository,
                              EntityStateRepository entityRepository,
                              ApplicationEventPublisher eventPublisher,
                              ObjectMapper objectMapper,
                              @Autowired(required = false) ZigbeeInboundHandler zigbeeInboundHandler,
                              @Autowired(required = false) BleInboundHandler bleInboundHandler) {
        this.stateEngineService = stateEngineService;
        this.deviceRepository = deviceRepository;
        this.entityRepository = entityRepository;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
        this.zigbeeInboundHandler = zigbeeInboundHandler;
        this.bleInboundHandler = bleInboundHandler;
    }

    @ServiceActivator(inputChannel = "mqttInputChannel")
    public void handleInboundMessage(Message<?> message) {
        String topic = (String) message.getHeaders().get(MqttHeaders.RECEIVED_TOPIC);
        Object payloadObj = message.getPayload();
        String payloadStr = payloadObj instanceof String ? (String) payloadObj : new String((byte[]) payloadObj);

        log.debug("Received MQTT message on [{}]: {}", topic, payloadStr);

        try {
            // Check for Zigbee2MQTT messages
            if (topic != null && topic.startsWith("zigbee2mqtt/")) {
                if (zigbeeInboundHandler != null) {
                    zigbeeInboundHandler.handleMqttMessage(topic, payloadStr);
                } else {
                    log.warn("Received Zigbee2MQTT message but handler is not available: {}", topic);
                }
                return;
            }

            // Check for BLE Proxy messages
            if (topic != null && (topic.startsWith("ble/") || topic.startsWith("esphome/ble_proxy/"))) {
                if (bleInboundHandler != null) {
                    bleInboundHandler.handleMqttMessage(topic, payloadStr);
                } else {
                    log.warn("Received BLE proxy message but handler is not available: {}", topic);
                }
                return;
            }

            String deviceId = extractDeviceIdFromTopic(topic);
            if (deviceId == null || deviceId.isBlank()) {
                log.warn("Could not extract deviceId from topic: {}", topic);
                return;
            }

            if (topic != null && (topic.endsWith("/status") || topic.endsWith("/availability"))) {
                handleStatusMessage(deviceId, payloadStr.trim());
            } else {
                handleTelemetryMessage(deviceId, payloadStr, topic);
            }
        } catch (Exception e) {
            log.error("Failed to process MQTT message on topic [{}]: {}", topic, e.getMessage(), e);
        }
    }

    private void handleStatusMessage(String deviceId, String payload) {
        boolean isOnline = parseOnlineStatus(payload);
        log.info("Received LWT/Status update for device [{}]: isOnline={}", deviceId, isOnline);

        updateDeviceStatus(deviceId, isOnline);
        eventPublisher.publishEvent(new DeviceStatusChangedEvent(this, deviceId, isOnline));

        List<EntityStateEntity> entities = entityRepository.findByDeviceId(deviceId);
        for (EntityStateEntity entity : entities) {
            if (!isOnline) {
                if (!"unavailable".equalsIgnoreCase(entity.getCurrentState())) {
                    Map<String, Object> attributes = new HashMap<>();
                    attributes.put("restored_state", entity.getCurrentState());
                    attributes.put("available", false);
                    stateEngineService.updateState(entity.getId(), "unavailable", attributes);
                }
            } else {
                if ("unavailable".equalsIgnoreCase(entity.getCurrentState())) {
                    Map<String, Object> attributes = new HashMap<>();
                    attributes.put("available", true);
                    stateEngineService.updateState(entity.getId(), "OFF", attributes);
                }
            }
        }
    }

    private void handleTelemetryMessage(String deviceId, String payloadStr, String topic) {
        try {
            TelemetryMessage telemetry = objectMapper.readValue(payloadStr, TelemetryMessage.class);

            updateDeviceStatus(deviceId, true);

            String entityId = telemetry.getEntityId();
            if (entityId == null || entityId.isBlank()) {
                String domain = telemetry.getDomain() != null ? telemetry.getDomain() : "sensor";
                entityId = domain + "." + deviceId;
            }

            String state = telemetry.getState();
            if (state == null || state.isBlank()) {
                state = "ON";
            }

            Map<String, Object> attributes = telemetry.getAttributes() != null
                    ? telemetry.getAttributes()
                    : new HashMap<>();

            stateEngineService.updateState(entityId, state, attributes);

        } catch (Exception e) {
            log.debug("Telemetry is not in standard JSON format, treating as raw value for device: {}", deviceId);
            String entityId = "sensor." + deviceId;
            updateDeviceStatus(deviceId, true);
            stateEngineService.updateState(entityId, payloadStr.trim(), new HashMap<>());
        }
    }

    private String extractDeviceIdFromTopic(String topic) {
        if (topic == null || topic.isBlank()) return null;
        String[] parts = topic.split("/");
        if (parts.length >= 2) {
            return parts[1];
        }
        return null;
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

    private void updateDeviceStatus(String deviceId, boolean isOnline) {
        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);
        if (deviceOpt.isPresent()) {
            DeviceEntity device = deviceOpt.get();
            device.setOnline(isOnline);
            if (isOnline) {
                device.setLastSeen(LocalDateTime.now());
            }
            deviceRepository.save(device);
        } else {
            DeviceEntity newDevice = new DeviceEntity();
            newDevice.setId(deviceId);
            newDevice.setName("Discovered Device " + deviceId);
            newDevice.setProtocol("MQTT");
            newDevice.setMqttTopicPrefix("devices/" + deviceId);
            newDevice.setOnline(isOnline);
            if (isOnline) {
                newDevice.setLastSeen(LocalDateTime.now());
            }
            deviceRepository.save(newDevice);
            log.info("Auto-registered new device from MQTT: {} (online={})", deviceId, isOnline);
        }
    }
}
