package com.homeapp.service.adapter.mqtt;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeapp.model.dto.DeviceCommand;
import com.homeapp.service.dispatcher.ProtocolAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.messaging.MessageChannel;
import org.springframework.stereotype.Service;

@Service
public class MqttOutboundService implements ProtocolAdapter {

    private static final Logger log = LoggerFactory.getLogger(MqttOutboundService.class);

    private final MessageChannel mqttOutboundChannel;
    private final ObjectMapper objectMapper;

    @Override
    public String getProtocol() {
        return "MQTT";
    }

    @Value("${mqtt.topics.outbound-set-pattern:devices/%s/set}")
    private String outboundSetPattern;

    @Value("${mqtt.simulate-on-disconnect:false}")
    private boolean simulateOnDisconnect = false;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public MqttOutboundService(@Qualifier("mqttOutboundChannel") MessageChannel mqttOutboundChannel,
                               ObjectMapper objectMapper) {
        this.mqttOutboundChannel = mqttOutboundChannel;
        this.objectMapper = objectMapper;
    }

    public boolean sendCommand(String deviceId, DeviceCommand command) {
        String topic = String.format(outboundSetPattern, deviceId);
        try {
            String payloadJson = objectMapper.writeValueAsString(command);
            log.info("Publishing MQTT command to topic [{}]: {}", topic, payloadJson);

            if (mqttOutboundChannel != null) {
                mqttOutboundChannel.send(
                        MessageBuilder.withPayload(payloadJson)
                                .setHeader(MqttHeaders.TOPIC, topic)
                                .setHeader(MqttHeaders.QOS, 1)
                                .build()
                );
                return true;
            } else {
                log.warn("MQTT outbound channel not configured/available for device [{}]", deviceId);
                return simulateOnDisconnect;
            }
        } catch (Exception e) {
            log.error("Failed to publish MQTT command to topic [{}]: {}", topic, e.getMessage());
            if (simulateOnDisconnect && isConnectionFailure(e)) {
                log.warn("Dev simulation active (mqtt.simulate-on-disconnect=true). Simulating success for device [{}]", deviceId);
                return true;
            }
            return false;
        }
    }

    public boolean sendRawPayload(String topic, String payload) {
        try {
            log.info("Publishing raw MQTT message to topic [{}]: {}", topic, payload);
            if (mqttOutboundChannel != null) {
                mqttOutboundChannel.send(
                        MessageBuilder.withPayload(payload)
                                .setHeader(MqttHeaders.TOPIC, topic)
                                .setHeader(MqttHeaders.QOS, 1)
                                .build()
                );
                return true;
            } else {
                log.warn("MQTT outbound channel not configured/available for topic [{}]", topic);
                return simulateOnDisconnect;
            }
        } catch (Exception e) {
            log.error("Failed to send raw payload to topic [{}]: {}", topic, e.getMessage());
            if (simulateOnDisconnect && isConnectionFailure(e)) {
                log.warn("Dev simulation active (mqtt.simulate-on-disconnect=true). Simulating success for raw payload on [{}]", topic);
                return true;
            }
            return false;
        }
    }

    private boolean isConnectionFailure(Throwable t) {
        if (t == null) return false;
        String msg = t.getMessage() != null ? t.getMessage().toLowerCase() : "";
        if (msg.contains("connection refused") || msg.contains("connect timed out") ||
                msg.contains("client is not connected") || msg.contains("broker") ||
                msg.contains("connection lost")) {
            return true;
        }
        return isConnectionFailure(t.getCause());
    }
}
