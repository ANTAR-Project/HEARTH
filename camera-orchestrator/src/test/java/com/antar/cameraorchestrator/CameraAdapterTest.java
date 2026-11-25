package com.antar.cameraorchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.antar.cameraorchestrator.model.dto.EntityStateDto;
import com.antar.cameraorchestrator.model.dto.ServiceCallRequest;
import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import com.antar.cameraorchestrator.service.StateEngineService;
import com.antar.cameraorchestrator.service.adapter.ble.BleOutboundService;
import com.antar.cameraorchestrator.service.adapter.camera.CameraAdapterService;
import com.antar.cameraorchestrator.service.dispatcher.ServiceDispatcher;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class CameraAdapterTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private CameraAdapterService cameraAdapterService;

    @Autowired
    private ServiceDispatcher serviceDispatcher;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private StateEngineService stateEngineService;

    @Autowired
    private BleOutboundService bleOutboundService;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        bleOutboundService.clearDispatchedOperations();
        deviceRepository.findById("cpplus_front_cam").ifPresent(d -> {
            d.setOnline(true);
            deviceRepository.save(d);
        });
        stateEngineService.updateState("camera.front_porch_cam", "idle",
                Map.of("protocol", "CAMERA", "available", true));
        stateEngineService.updateState("binary_sensor.front_porch_cam_motion", "OFF",
                Map.of("protocol", "CAMERA", "device_class", "motion"));
    }

    @Test
    void testCameraAdapterRegisteredAndSeeded() {
        // 1. Verify Camera protocol adapter is registered in ServiceDispatcher
        assertNotNull(serviceDispatcher.getAdapterRegistry().get("CAMERA"));
        assertEquals("CAMERA", cameraAdapterService.getProtocol());

        // 2. Verify seeded CP Plus camera device
        Optional<DeviceEntity> camDevice = deviceRepository.findById("cpplus_front_cam");
        assertTrue(camDevice.isPresent(), "CP Plus camera device should be seeded in DB");
        assertEquals("CAMERA", camDevice.get().getProtocol());
        assertEquals("192.168.30.247", camDevice.get().getIpAddress());
        assertNotNull(camDevice.get().getName());

        // 3. Verify camera entity
        Optional<EntityStateDto> camEntity = stateEngineService.getState("camera.front_porch_cam");
        assertTrue(camEntity.isPresent());
        assertNotNull(camEntity.get().getState());

        // 4. Verify motion sensor entity
        Optional<EntityStateDto> motionEntity = stateEngineService.getState("binary_sensor.front_porch_cam_motion");
        assertTrue(motionEntity.isPresent());
        assertNotNull(motionEntity.get().getState());
    }

    @Test
    void testSnapshotProxyEndpointReturnsValidJpeg() throws Exception {
        mockMvc.perform(get("/api/camera/camera.front_porch_cam/snapshot"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.IMAGE_JPEG))
                .andExpect(header().string("Cache-Control", "no-cache, no-store, must-revalidate"))
                .andExpect(result -> {
                    byte[] bytes = result.getResponse().getContentAsByteArray();
                    assertTrue(bytes.length > 500, "Snapshot byte stream should be a valid non-empty JPEG");
                    // Check JPEG magic bytes 0xFF 0xD8
                    assertEquals((byte) 0xFF, bytes[0]);
                    assertEquals((byte) 0xD8, bytes[1]);
                });
    }

    @Test
    void testStreamInfoEndpoint() throws Exception {
        mockMvc.perform(get("/api/camera/camera.front_porch_cam/stream-url"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity_id").value("camera.front_porch_cam"))
                .andExpect(jsonPath("$.device_id").value("cpplus_front_cam"))
                .andExpect(jsonPath("$.hls_stream_url").value("/api/camera/camera.front_porch_cam/live/index.m3u8"))
                .andExpect(jsonPath("$.protocol").value("HLS"))
                .andExpect(jsonPath("$.substream_rtsp").value(org.hamcrest.Matchers.containsString("rtsp://")))
                .andExpect(jsonPath("$.snapshot_url").value("/api/camera/camera.front_porch_cam/snapshot"));
    }

    @Test
    void testLiveHlsEndpointReturnsM3u8OrInitializing() throws Exception {
        // In dev test environment where physical camera stream is offline,
        // it gracefully returns 503 or 200 with m3u8 playlist header, never 404 or 500 error
        mockMvc.perform(get("/api/camera/camera.front_porch_cam/live/index.m3u8"))
                .andExpect(result -> {
                    int status = result.getResponse().getStatus();
                    assertTrue(status == 200 || status == 503,
                            "Status should be 200 (live stream ready) or 503 (stream initializing)");
                    String content = result.getResponse().getContentAsString();
                    assertTrue(content.contains("#EXTM3U"), "HLS playlist response must contain #EXTM3U header");
                });
    }

    @Test
    void testCameraMotionWebhookTriggersCrossProtocolAutomation() throws Exception {
        // Initially Porch BLE light is OFF
        stateEngineService.updateState("light.porch_ble_bulb", "OFF", Map.of("protocol", "BLE"));

        // Trigger camera motion webhook
        mockMvc.perform(post("/api/camera/camera.front_porch_cam/motion")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("detected", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.motion_detected").value(true))
                .andExpect(jsonPath("$.status").value("acknowledged"));

        // Verify motion binary sensor transitioned to ON
        Optional<EntityStateDto> motionState = stateEngineService.getState("binary_sensor.front_porch_cam_motion");
        assertTrue(motionState.isPresent());
        assertEquals("ON", motionState.get().getState());

        // Allow async automation engine execution
        Thread.sleep(600);

        // Verify cross-protocol rule "rule_camera_motion_porch_light" triggered BLE outbound command!
        assertEquals(1, bleOutboundService.getDispatchedOperations().size(),
                "Camera motion detection should trigger BLE porch light ON via Automation Engine");
        Map<String, Object> op = bleOutboundService.getDispatchedOperations().get(0);
        assertEquals("ble/ble_porch_bulb/write", op.get("topic"));
        assertEquals("ON", op.get("state"));
    }

    @Test
    void testDispatchCameraPtzServiceCall() {
        ServiceCallRequest ptzReq = new ServiceCallRequest("camera", "ptz_up", "camera.front_porch_cam", Map.of("speed", 4));
        Map<String, Object> result = serviceDispatcher.dispatch(ptzReq);

        assertNotNull(result);
        assertEquals("camera.front_porch_cam", result.get("entity_id"));
        assertEquals("CAMERA", result.get("protocol"));
        assertTrue((Boolean) result.get("success"));
    }

    @Test
    void testSubnetDiscoveryEndpoint() throws Exception {
        mockMvc.perform(post("/api/camera/scan")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("target", "192.168.30.247"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.count").exists());
    }
}
