package com.antar.cameraorchestrator;

import com.antar.cameraorchestrator.model.dto.EntityStateDto;
import com.antar.cameraorchestrator.model.dto.ServiceCallRequest;
import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import com.antar.cameraorchestrator.service.StateEngineService;
import com.antar.cameraorchestrator.service.adapter.zigbee.ZigbeeAdapterService;
import com.antar.cameraorchestrator.service.adapter.zigbee.ZigbeeInboundHandler;
import com.antar.cameraorchestrator.service.adapter.zigbee.ZigbeeOutboundService;
import com.antar.cameraorchestrator.service.dispatcher.ServiceDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class ZigbeeAdapterTest {

    @Autowired
    private ZigbeeInboundHandler zigbeeInboundHandler;

    @Autowired
    private ZigbeeOutboundService zigbeeOutboundService;

    @Autowired
    private ZigbeeAdapterService zigbeeAdapterService;

    @Autowired
    private ServiceDispatcher serviceDispatcher;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private StateEngineService stateEngineService;

    @BeforeEach
    void setup() {
        zigbeeOutboundService.clearDispatchedCommands();
    }

    @Test
    void testZigbee2MqttInboundTelemetry() {
        String deviceId = "zigbee_test_climate";
        String topic = "zigbee2mqtt/" + deviceId;
        String payload = "{\"temperature\":23.5,\"humidity\":52.0,\"occupancy\":true,\"linkquality\":145,\"battery\":92}";

        zigbeeInboundHandler.handleMqttMessage(topic, payload);

        // 1. Check device auto-discovery with ZIGBEE protocol
        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);
        assertTrue(deviceOpt.isPresent());
        assertEquals("ZIGBEE", deviceOpt.get().getProtocol());
        assertEquals("zigbee2mqtt/" + deviceId, deviceOpt.get().getMqttTopicPrefix());
        assertTrue(deviceOpt.get().isOnline());

        // 2. Check temperature entity state
        Optional<EntityStateDto> tempState = stateEngineService.getState("sensor." + deviceId + "_temperature");
        assertTrue(tempState.isPresent());
        assertEquals("23.5", tempState.get().getState());
        assertEquals(145, tempState.get().getAttributes().get("lqi"));
        assertEquals("ZIGBEE", tempState.get().getAttributes().get("protocol"));

        // 3. Check humidity entity state
        Optional<EntityStateDto> humState = stateEngineService.getState("sensor." + deviceId + "_humidity");
        assertTrue(humState.isPresent());
        assertEquals("52.0", humState.get().getState());

        // 4. Check motion entity state
        Optional<EntityStateDto> motionState = stateEngineService.getState("binary_sensor." + deviceId + "_motion");
        assertTrue(motionState.isPresent());
        assertEquals("ON", motionState.get().getState());
    }

    @Test
    void testZigbee2MqttAvailabilityTransitions() {
        String deviceId = "zigbee_living_room_plug";
        String entityId = "switch.living_room_zigbee_plug";

        // 1. Device reports offline via zigbee2mqtt/{deviceId}/availability
        zigbeeInboundHandler.handleMqttMessage("zigbee2mqtt/" + deviceId + "/availability", "offline");

        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);
        assertTrue(deviceOpt.isPresent());
        assertFalse(deviceOpt.get().isOnline(), "Device should be marked offline");

        Optional<EntityStateDto> entityState = stateEngineService.getState(entityId);
        assertTrue(entityState.isPresent());
        assertEquals("unavailable", entityState.get().getState());
        assertEquals(false, entityState.get().getAttributes().get("available"));

        // 2. Device comes back online
        zigbeeInboundHandler.handleMqttMessage("zigbee2mqtt/" + deviceId + "/availability", "online");
        deviceOpt = deviceRepository.findById(deviceId);
        assertTrue(deviceOpt.isPresent());
        assertTrue(deviceOpt.get().isOnline(), "Device should be marked online");
    }

    @Test
    void testZigbeeOutboundPublishesToZigbee2MqttWithoutDirectStateSpoofing() {
        String entityId = "switch.living_room_zigbee_plug";
        String deviceId = "zigbee_living_room_plug";

        stateEngineService.updateState(entityId, "OFF", Map.of("protocol", "ZIGBEE"));

        ServiceCallRequest onReq = new ServiceCallRequest("switch", "turn_on", entityId, Map.of());
        serviceDispatcher.dispatch(onReq);

        // 1. Verify outbound service published command to zigbee2mqtt/{deviceId}/set
        assertEquals(1, zigbeeOutboundService.getDispatchedCommands().size());
        Map<String, Object> record = zigbeeOutboundService.getDispatchedCommands().get(0);
        assertEquals("zigbee2mqtt/" + deviceId + "/set", record.get("topic"));
        assertEquals("ON", ((Map<?, ?>) record.get("payload")).get("state"));

        // 2. Verify state engine was NOT optimistically spoofed
        Optional<EntityStateDto> stateBeforeAck = stateEngineService.getState(entityId);
        assertTrue(stateBeforeAck.isPresent());
        assertEquals("OFF", stateBeforeAck.get().getState(),
                "State engine must NOT be updated directly by outbound command; waiting for device ack");

        // 3. Now simulate Zigbee2MQTT device acknowledgment payload arriving on MQTT
        zigbeeInboundHandler.handleMqttMessage("zigbee2mqtt/" + deviceId, "{\"state\":\"ON\",\"linkquality\":150}");

        // 4. Verify state is now updated upon reception
        Optional<EntityStateDto> stateAfterAck = stateEngineService.getState(entityId);
        assertTrue(stateAfterAck.isPresent());
        assertEquals("ON", stateAfterAck.get().getState(),
                "State engine should update only after verified message reception from Zigbee2MQTT");
    }

    @Test
    void testZigbeeLevelControlBrightnessPublish() {
        String entityId = "light.living_room_accent";
        String deviceId = "zigbee_accent_light";

        ServiceCallRequest req = new ServiceCallRequest("light", "turn_on", entityId, Map.of("brightness", 180));
        serviceDispatcher.dispatch(req);

        assertEquals(1, zigbeeOutboundService.getDispatchedCommands().size());
        Map<String, Object> record = zigbeeOutboundService.getDispatchedCommands().get(0);
        assertEquals("zigbee2mqtt/" + deviceId + "/set", record.get("topic"));
        assertEquals(180, ((Map<?, ?>) record.get("payload")).get("brightness"));
    }

    @Test
    void testZigbeePermitJoinToggle() {
        assertFalse((Boolean) zigbeeAdapterService.getAdapterStatus().get("permit_join"));

        zigbeeAdapterService.setPermitJoin(true, 60);
        assertTrue((Boolean) zigbeeAdapterService.getAdapterStatus().get("permit_join"));

        zigbeeAdapterService.setPermitJoin(false, 0);
        assertFalse((Boolean) zigbeeAdapterService.getAdapterStatus().get("permit_join"));
    }
}
