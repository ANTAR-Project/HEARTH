package com.antar.cameraorchestrator.service.adapter.zigbee;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.antar.cameraorchestrator.model.dto.DeviceCommand;
import com.antar.cameraorchestrator.service.adapter.mqtt.MqttOutboundService;
import com.antar.cameraorchestrator.service.dispatcher.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class ZigbeeOutboundService implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(ZigbeeOutboundService.class);

    private final MqttOutboundService mqttOutboundService;
    private final ObjectMapper objectMapper;

    @Value("${zigbee.topics.outbound-set-pattern:zigbee2mqtt/%s/set}")
    private String outboundSetPattern;

    @Value("${zigbee.topics.permit-join:zigbee2mqtt/bridge/request/permit_join}")
    private String permitJoinTopic;

    private final List<Map<String, Object>> dispatchedCommands = new CopyOnWriteArrayList<>();

    public ZigbeeOutboundService(MqttOutboundService mqttOutboundService,
                                ObjectMapper objectMapper) {
        this.mqttOutboundService = mqttOutboundService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProtocol() {
        return "ZIGBEE";
    }

    @Override
    public boolean sendCommand(String deviceId, DeviceCommand command) {
        if (deviceId == null || command == null) {
            log.warn("Invalid Zigbee command: deviceId or command is null");
            return false;
        }

        String topic = String.format(outboundSetPattern, deviceId);
        String state = command.getState();
        Map<String, Object> attributes = command.getAttributes() != null ? command.getAttributes() : new HashMap<>();

        Map<String, Object> z2mPayload = new LinkedHashMap<>();

        if ("TOGGLE".equalsIgnoreCase(state)) {
            z2mPayload.put("state", "TOGGLE");
        } else if ("ON".equalsIgnoreCase(state) || "OFF".equalsIgnoreCase(state)) {
            z2mPayload.put("state", state.toUpperCase());
        } else if (state != null && !state.isBlank()) {
            z2mPayload.put("state", state);
        }

        if (attributes.containsKey("brightness")) {
            z2mPayload.put("brightness", attributes.get("brightness"));
        } else if (attributes.containsKey("level")) {
            z2mPayload.put("brightness", attributes.get("level"));
        }

        if (attributes.containsKey("color_temp")) {
            z2mPayload.put("color_temp", attributes.get("color_temp"));
        }

        try {
            String payloadJson = objectMapper.writeValueAsString(z2mPayload);
            log.info("Publishing Zigbee2MQTT command to topic [{}]: {}", topic, payloadJson);

            Map<String, Object> record = new ConcurrentHashMap<>();
            record.put("deviceId", deviceId);
            record.put("topic", topic);
            record.put("payload", z2mPayload);
            record.put("payloadJson", payloadJson);
            record.put("timestamp", System.currentTimeMillis());
            dispatchedCommands.add(record);

            return mqttOutboundService.sendRawPayload(topic, payloadJson);

        } catch (Exception e) {
            log.error("Failed to construct Zigbee2MQTT command payload for [{}]: {}", deviceId, e.getMessage(), e);
            return false;
        }
    }

    public boolean sendPermitJoin(boolean enable, int durationSeconds) {
        try {
            Map<String, Object> payload = Map.of(
                    "value", enable,
                    "time", enable ? durationSeconds : 0
            );
            String payloadJson = objectMapper.writeValueAsString(payload);
            log.info("Publishing Zigbee2MQTT permit-join to [{}]: {}", permitJoinTopic, payloadJson);
            return mqttOutboundService.sendRawPayload(permitJoinTopic, payloadJson);
        } catch (Exception e) {
            log.error("Failed to send Zigbee2MQTT permit-join: {}", e.getMessage(), e);
            return false;
        }
    }

    public List<Map<String, Object>> getDispatchedCommands() {
        return Collections.unmodifiableList(dispatchedCommands);
    }

    public void clearDispatchedCommands() {
        dispatchedCommands.clear();
    }
}
