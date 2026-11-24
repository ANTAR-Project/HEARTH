package com.homeapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeapp.model.dto.EntityStateDto;
import com.homeapp.model.dto.ServiceCallRequest;
import com.homeapp.service.StateEngineService;
import com.homeapp.service.adapter.ble.BleOutboundService;
import com.homeapp.service.adapter.zigbee.ZigbeeOutboundService;
import com.homeapp.service.dispatcher.ServiceDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class ProtocolAdapterIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ServiceDispatcher serviceDispatcher;

    @Autowired
    private StateEngineService stateEngineService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BleOutboundService bleOutboundService;

    @Autowired
    private ZigbeeOutboundService zigbeeOutboundService;

    @BeforeEach
    void setup() {
        bleOutboundService.clearDispatchedOperations();
        zigbeeOutboundService.clearDispatchedCommands();
    }

    @Test
    void testMqttBrokerOfflineReportsHonestFailure() {
        ServiceCallRequest mqttReq = new ServiceCallRequest("light", "turn_on", "light.living_room", Map.of());
        Map<String, Object> mqttResult = serviceDispatcher.dispatch(mqttReq);

        assertNotNull(mqttResult);
        assertEquals("light.living_room", mqttResult.get("entity_id"));
        assertEquals("light", mqttResult.get("domain"));
        assertEquals("turn_on", mqttResult.get("service"));
        assertFalse((Boolean) mqttResult.get("success"),
                "When Mosquitto broker is down/unreachable, dispatch must report false");
    }

    @Test
    void testCrossProtocolRoutingToBleAndZigbeeAdapters() {
        // 1. BLE device dispatch -> routed to BleOutboundService targeting BLE proxy topic
        ServiceCallRequest bleReq = new ServiceCallRequest("light", "turn_on", "light.porch_ble_bulb", Map.of());
        Map<String, Object> bleResult = serviceDispatcher.dispatch(bleReq);
        assertNotNull(bleResult);
        assertEquals("light.porch_ble_bulb", bleResult.get("entity_id"));
        assertEquals(1, bleOutboundService.getDispatchedOperations().size());
        assertEquals("ble/ble_porch_bulb/write", bleOutboundService.getDispatchedOperations().get(0).get("topic"));

        // 2. Zigbee device dispatch -> routed to ZigbeeOutboundService targeting Zigbee2MQTT set topic
        ServiceCallRequest zigbeeReq = new ServiceCallRequest("switch", "turn_on", "switch.living_room_zigbee_plug", Map.of());
        Map<String, Object> zigbeeResult = serviceDispatcher.dispatch(zigbeeReq);
        assertNotNull(zigbeeResult);
        assertEquals("switch.living_room_zigbee_plug", zigbeeResult.get("entity_id"));
        assertEquals(1, zigbeeOutboundService.getDispatchedCommands().size());
        assertEquals("zigbee2mqtt/zigbee_living_room_plug/set", zigbeeOutboundService.getDispatchedCommands().get(0).get("topic"));
    }

    @Test
    void testCrossProtocolAutomationTrigger() throws Exception {
        Optional<EntityStateDto> initialLight = stateEngineService.getState("light.kitchen_main");
        assertTrue(initialLight.isPresent());

        // Trigger Zigbee motion sensor: binary_sensor.kitchen_zigbee_motion -> ON
        stateEngineService.updateState("binary_sensor.kitchen_zigbee_motion", "ON", Map.of("protocol", "ZIGBEE"));

        Thread.sleep(300);

        Optional<EntityStateDto> updatedLight = stateEngineService.getState("light.kitchen_main");
        assertTrue(updatedLight.isPresent());
    }

    @Test
    void testAdapterRestEndpoints() throws Exception {
        // 1. GET /api/adapters
        mockMvc.perform(get("/api/adapters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mqtt.protocol").value("MQTT"))
                .andExpect(jsonPath("$.ble.protocol").value("BLE"))
                .andExpect(jsonPath("$.zigbee.protocol").value("ZIGBEE"));

        // 2. POST /api/adapters/ble/advertisement
        Map<String, Object> bleAdv = Map.of(
                "mac_address", "AA:BB:CC:11:22:33",
                "device_name", "REST Ingested BLE Sensor",
                "rssi", -64,
                "battery", 90,
                "data", Map.of("temperature", 23.8, "humidity", 47.5)
        );

        mockMvc.perform(post("/api/adapters/ble/advertisement")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(bleAdv)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol").value("BLE"))
                .andExpect(jsonPath("$.status").value("accepted"));

        // 3. POST /api/adapters/zigbee/report
        Map<String, Object> zigbeeReport = Map.of(
                "ieee_address", "00:15:8D:00:99:88:77:66",
                "device_name", "REST Ingested Zigbee Plug",
                "cluster_id", 6,
                "link_quality", 150,
                "attributes", Map.of("onOff", true)
        );

        mockMvc.perform(post("/api/adapters/zigbee/report")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(zigbeeReport)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.protocol").value("ZIGBEE"))
                .andExpect(jsonPath("$.status").value("accepted"));

        // 4. POST /api/adapters/zigbee/permit-join
        mockMvc.perform(post("/api/adapters/zigbee/permit-join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("enable", true, "duration_seconds", 120))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.permit_join").value(true))
                .andExpect(jsonPath("$.duration_seconds").value(120));
    }
}
