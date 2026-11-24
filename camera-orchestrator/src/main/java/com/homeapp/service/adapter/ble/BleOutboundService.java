package com.homeapp.service.adapter.ble;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeapp.model.dto.DeviceCommand;
import com.homeapp.service.adapter.mqtt.MqttOutboundService;
import com.homeapp.service.dispatcher.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class BleOutboundService implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(BleOutboundService.class);

    public static final String UUID_GENERIC_ACCESS = "00001800-0000-1000-8000-00805f9b34fb";
    public static final String UUID_LIGHT_SWITCH = "00001815-0000-1000-8000-00805f9b34fb";
    public static final String UUID_LOCK_STATE = "00001828-0000-1000-8000-00805f9b34fb";

    private final MqttOutboundService mqttOutboundService;
    private final ObjectMapper objectMapper;

    @Value("${ble.topics.outbound-write-pattern:ble/%s/write}")
    private String outboundWritePattern;

    private final List<Map<String, Object>> dispatchedOperations = new CopyOnWriteArrayList<>();

    public BleOutboundService(MqttOutboundService mqttOutboundService,
                              ObjectMapper objectMapper) {
        this.mqttOutboundService = mqttOutboundService;
        this.objectMapper = objectMapper;
    }

    @Override
    public String getProtocol() {
        return "BLE";
    }

    @Override
    public boolean sendCommand(String deviceId, DeviceCommand command) {
        if (deviceId == null || command == null) {
            log.warn("Invalid BLE command: deviceId or command is null");
            return false;
        }

        String state = command.getState();
        Map<String, Object> attributes = command.getAttributes() != null ? command.getAttributes() : new HashMap<>();

        log.info("Dispatching BLE GATT command to proxy for device [{}]: state={}, attributes={}",
                deviceId, state, attributes);

        String characteristicUuid;
        String rawValue;

        if ("ON".equalsIgnoreCase(state) || "UNLOCK".equalsIgnoreCase(state)) {
            rawValue = "01";
            characteristicUuid = UUID_LIGHT_SWITCH;
        } else if ("OFF".equalsIgnoreCase(state) || "LOCK".equalsIgnoreCase(state)) {
            rawValue = "00";
            characteristicUuid = UUID_LIGHT_SWITCH;
        } else if ("TOGGLE".equalsIgnoreCase(state)) {
            rawValue = "toggle";
            characteristicUuid = UUID_LIGHT_SWITCH;
        } else {
            rawValue = state;
            characteristicUuid = UUID_GENERIC_ACCESS;
        }

        Map<String, Object> proxyPayload = new LinkedHashMap<>();
        proxyPayload.put("device_id", deviceId);
        proxyPayload.put("state", state);
        proxyPayload.put("characteristic_uuid", characteristicUuid);
        proxyPayload.put("raw_value", rawValue);
        if (!attributes.isEmpty()) {
            proxyPayload.put("attributes", attributes);
        }

        String topic = String.format(outboundWritePattern, deviceId);

        try {
            String payloadJson = objectMapper.writeValueAsString(proxyPayload);
            log.info("Publishing BLE proxy write command to topic [{}]: {}", topic, payloadJson);

            Map<String, Object> operation = new ConcurrentHashMap<>();
            operation.put("deviceId", deviceId);
            operation.put("topic", topic);
            operation.put("state", state);
            operation.put("characteristicUuid", characteristicUuid);
            operation.put("rawValue", rawValue);
            operation.put("timestamp", System.currentTimeMillis());
            dispatchedOperations.add(operation);

            return mqttOutboundService.sendRawPayload(topic, payloadJson);

        } catch (Exception e) {
            log.error("Failed to construct BLE proxy command payload for [{}]: {}", deviceId, e.getMessage(), e);
            return false;
        }
    }

    public List<Map<String, Object>> getDispatchedOperations() {
        return Collections.unmodifiableList(dispatchedOperations);
    }

    public void clearDispatchedOperations() {
        dispatchedOperations.clear();
    }
}
