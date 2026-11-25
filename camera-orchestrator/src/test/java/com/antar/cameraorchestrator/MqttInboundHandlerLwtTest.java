package com.antar.cameraorchestrator;

import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.model.entity.EntityStateEntity;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import com.antar.cameraorchestrator.repository.EntityStateRepository;
import com.antar.cameraorchestrator.service.StateEngineService;
import com.antar.cameraorchestrator.service.adapter.mqtt.MqttInboundHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.integration.mqtt.support.MqttHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class MqttInboundHandlerLwtTest {

    @Autowired
    private MqttInboundHandler mqttInboundHandler;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private EntityStateRepository entityStateRepository;

    @Autowired
    private StateEngineService stateEngineService;

    @Test
    void testDeviceLwtOnlineAndOfflineTransitions() {
        String deviceId = "esp32_relay1";

        // 1. Send LWT online status message
        var onlineMessage = MessageBuilder.withPayload("online")
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "devices/" + deviceId + "/status")
                .build();
        mqttInboundHandler.handleInboundMessage(onlineMessage);

        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);
        assertTrue(deviceOpt.isPresent());
        assertTrue(deviceOpt.get().isOnline(), "Device should be online after 'online' status payload");

        // 2. Send LWT offline status message (simulating power loss / ungraceful disconnect)
        var offlineMessage = MessageBuilder.withPayload("offline")
                .setHeader(MqttHeaders.RECEIVED_TOPIC, "devices/" + deviceId + "/status")
                .build();
        mqttInboundHandler.handleInboundMessage(offlineMessage);

        deviceOpt = deviceRepository.findById(deviceId);
        assertTrue(deviceOpt.isPresent());
        assertFalse(deviceOpt.get().isOnline(), "Device should be marked offline after LWT 'offline' payload");

        // 3. Verify associated entities are marked unavailable
        List<EntityStateEntity> entities = entityStateRepository.findByDeviceId(deviceId);
        assertFalse(entities.isEmpty(), "Entities for device should exist");
        for (EntityStateEntity entity : entities) {
            var stateDto = stateEngineService.getState(entity.getId());
            assertTrue(stateDto.isPresent());
            assertEquals("unavailable", stateDto.get().getState(), "Entity state should be marked 'unavailable' when device goes offline");
            assertNotNull(stateDto.get().getAttributes().get("restored_state"), "Previous state should be preserved in attributes");
            assertEquals(false, stateDto.get().getAttributes().get("available"));
        }

        // 4. Send device birth / online message again
        mqttInboundHandler.handleInboundMessage(onlineMessage);
        deviceOpt = deviceRepository.findById(deviceId);
        assertTrue(deviceOpt.isPresent());
        assertTrue(deviceOpt.get().isOnline(), "Device should be back online");
    }
}
