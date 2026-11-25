package com.antar.cameraorchestrator;

import com.antar.cameraorchestrator.model.dto.DeviceCommand;
import com.antar.cameraorchestrator.model.dto.EntityStateDto;
import com.antar.cameraorchestrator.model.dto.ServiceCallRequest;
import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.model.entity.EntityStateEntity;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import com.antar.cameraorchestrator.repository.EntityStateRepository;
import com.antar.cameraorchestrator.service.StateEngineService;
import com.antar.cameraorchestrator.service.dispatcher.ServiceDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class ServiceDispatcherTest {

    @Autowired
    private ServiceDispatcher serviceDispatcher;

    @Autowired
    private DeviceRepository deviceRepository;

    @Autowired
    private EntityStateRepository entityStateRepository;

    @Autowired
    private StateEngineService stateEngineService;

    @Test
    void testDispatchTurnOnService() {
        ServiceCallRequest request = new ServiceCallRequest("light", "turn_on", "light.living_room", Map.of());
        Map<String, Object> result = serviceDispatcher.dispatch(request);

        assertNotNull(result);
        assertEquals("light.living_room", result.get("entity_id"));
        assertEquals("light", result.get("domain"));
        assertEquals("turn_on", result.get("service"));
    }

    @Test
    void testSetStateServiceTranslatesExplicitStateFromBody() {
        ServiceCallRequest request = new ServiceCallRequest("climate", "set_state", "climate.living_room",
                Map.of("state", "HEAT", "temperature", 22.5));
        Map<String, Object> result = serviceDispatcher.dispatch(request);

        assertNotNull(result);
        DeviceCommand cmd = (DeviceCommand) result.get("command");
        assertNotNull(cmd);
        assertEquals("HEAT", cmd.getState(), "set_state service must read explicit state from request body");
        assertEquals(22.5, cmd.getAttributes().get("temperature"));
    }

    @Test
    void testMissingAdapterReportsFailureWithoutStateDrift() {
        String devId = "unknown_matter_device";
        String entityId = "lock.matter_front_door";

        DeviceEntity dev = new DeviceEntity();
        dev.setId(devId);
        dev.setName("Matter Lock");
        dev.setProtocol("MATTER"); // No adapter exists for MATTER
        dev.setOnline(true);
        deviceRepository.saveAndFlush(dev);

        EntityStateEntity entity = new EntityStateEntity();
        entity.setId(entityId);
        entity.setName("Front Door Lock");
        entity.setDeviceId(devId);
        entity.setDomain("lock");
        entity.setCurrentState("LOCKED");
        entityStateRepository.saveAndFlush(entity);
        stateEngineService.updateState(entityId, "LOCKED", Map.of());

        // Dispatch command to entity with missing protocol adapter
        ServiceCallRequest request = new ServiceCallRequest("lock", "unlock", entityId, Map.of());
        Map<String, Object> result = serviceDispatcher.dispatch(request);

        assertNotNull(result);
        assertFalse((Boolean) result.get("success"),
                "When protocol adapter is missing, dispatch MUST report false and not pretend success");

        // Verify State Engine was NOT optimistically spoofed
        Optional<EntityStateDto> current = stateEngineService.getState(entityId);
        assertTrue(current.isPresent());
        assertEquals("LOCKED", current.get().getState(),
                "State engine must not be optimistically updated if hardware command cannot be dispatched");
    }
}
