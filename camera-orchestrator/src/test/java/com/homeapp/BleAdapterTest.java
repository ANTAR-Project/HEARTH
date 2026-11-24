package com.homeapp;

import com.homeapp.model.dto.EntityStateDto;
import com.homeapp.model.dto.ServiceCallRequest;
import com.homeapp.model.entity.DeviceEntity;
import com.homeapp.repository.DeviceRepository;
import com.homeapp.service.StateEngineService;
import com.homeapp.service.adapter.ble.BleAdvertisementPayload;
import com.homeapp.service.adapter.ble.BleInboundHandler;
import com.homeapp.service.adapter.ble.BleOutboundService;
import com.homeapp.service.dispatcher.ServiceDispatcher;
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
class BleAdapterTest {

    @Autowired
    private BleInboundHandler bleInboundHandler;

    @Autowired
    private BleOutboundService bleOutboundService;

    @Autowired
    private ServiceDispatcher serviceDispatcher;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private StateEngineService stateEngineService;

    @BeforeEach
    void setup() {
        bleOutboundService.clearDispatchedOperations();
    }

    @Test
    void testBleAdvertisementIngestionAndAutoDiscovery() {
        String mac = "A4:C1:38:AA:11:22";
        String deviceId = "ble_test_sensor";

        BleAdvertisementPayload payload = new BleAdvertisementPayload();
        payload.setDeviceId(deviceId);
        payload.setMacAddress(mac);
        payload.setDeviceName("BLE Climate Test");
        payload.setRssi(-68);
        payload.setBattery(85);
        payload.setData(Map.of("temperature", 24.3, "humidity", 51.2));

        bleInboundHandler.handleAdvertisement(payload);

        // 1. Device registered in repository
        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);
        assertTrue(deviceOpt.isPresent(), "BLE Device should be auto-discovered");
        assertEquals("BLE", deviceOpt.get().getProtocol());
        assertEquals(mac, deviceOpt.get().getMacAddress());
        assertTrue(deviceOpt.get().isOnline());

        // 2. States updated in State Engine
        Optional<EntityStateDto> tempState = stateEngineService.getState("sensor." + deviceId + "_temp");
        assertTrue(tempState.isPresent(), "Temperature entity should be created");
        assertEquals("24.3", tempState.get().getState());
        assertEquals(-68, tempState.get().getAttributes().get("rssi"));
        assertEquals(85, tempState.get().getAttributes().get("battery"));
        assertEquals("BLE", tempState.get().getAttributes().get("protocol"));

        Optional<EntityStateDto> humState = stateEngineService.getState("sensor." + deviceId + "_humidity");
        assertTrue(humState.isPresent(), "Humidity entity should be created");
        assertEquals("51.2", humState.get().getState());
    }

    @Test
    void testBleProxyMqttIngestion() {
        String deviceId = "ble_proxy_node1";
        String topic = "ble/" + deviceId + "/telemetry";
        String jsonPayload = "{\"device_id\":\"" + deviceId + "\",\"mac_address\":\"A4:C1:38:00:11:22\"," +
                "\"device_name\":\"BLE Living Room Bulb\",\"state\":\"ON\",\"domain\":\"light\",\"rssi\":-60}";

        bleInboundHandler.handleMqttMessage(topic, jsonPayload);

        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);
        assertTrue(deviceOpt.isPresent());
        assertTrue(deviceOpt.get().isOnline());

        Optional<EntityStateDto> stateOpt = stateEngineService.getState("light." + deviceId);
        assertTrue(stateOpt.isPresent());
        assertEquals("ON", stateOpt.get().getState());
        assertEquals(-60, stateOpt.get().getAttributes().get("rssi"));
    }

    @Test
    void testBleOutboundCommandDispatchesToProxyWithoutDirectStateSpoofing() {
        String entityId = "light.porch_ble_bulb";
        String deviceId = "ble_porch_bulb";

        stateEngineService.updateState(entityId, "OFF", Map.of("protocol", "BLE"));

        ServiceCallRequest turnOn = new ServiceCallRequest("light", "turn_on", entityId, Map.of());
        serviceDispatcher.dispatch(turnOn);

        // 1. Verify outbound service dispatched GATT write command to proxy topic
        assertEquals(1, bleOutboundService.getDispatchedOperations().size());
        Map<String, Object> op = bleOutboundService.getDispatchedOperations().get(0);
        assertEquals("ble/" + deviceId + "/write", op.get("topic"));
        assertEquals("ON", op.get("state"));
        assertEquals(BleOutboundService.UUID_LIGHT_SWITCH, op.get("characteristicUuid"));

        // 2. Verify state engine was NOT optimistically spoofed
        Optional<EntityStateDto> stateBeforeAck = stateEngineService.getState(entityId);
        assertTrue(stateBeforeAck.isPresent());
        assertEquals("OFF", stateBeforeAck.get().getState(),
                "State engine must NOT be updated directly by outbound command; waiting for proxy ack");

        // 3. Simulate BLE proxy confirming GATT write by publishing state back on MQTT
        String proxyTelemetry = "{\"device_id\":\"" + deviceId + "\",\"state\":\"ON\",\"domain\":\"light\",\"rssi\":-64}";
        bleInboundHandler.handleMqttMessage("ble/" + deviceId + "/telemetry", proxyTelemetry);

        // 4. Verify state is now updated upon verified proxy telemetry
        Optional<EntityStateDto> stateAfterAck = stateEngineService.getState(entityId);
        assertTrue(stateAfterAck.isPresent());
        assertEquals("ON", stateAfterAck.get().getState(),
                "State engine should update only after verified telemetry arrives from BLE proxy");
    }

    @Test
    void testBleDisconnectMarksEntitiesUnavailable() {
        String deviceId = "ble_porch_bulb";
        String entityId = "light.porch_ble_bulb";

        bleInboundHandler.handleDisconnect(deviceId);

        Optional<DeviceEntity> deviceOpt = deviceRepository.findById(deviceId);
        assertTrue(deviceOpt.isPresent());
        assertFalse(deviceOpt.get().isOnline(), "Device should be marked offline");

        Optional<EntityStateDto> entityState = stateEngineService.getState(entityId);
        assertTrue(entityState.isPresent());
        assertEquals("unavailable", entityState.get().getState(), "Entity state should be marked unavailable");
        assertEquals(false, entityState.get().getAttributes().get("available"));
    }
}
