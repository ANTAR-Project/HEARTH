package com.antar.cameraorchestrator;

import com.antar.cameraorchestrator.model.dto.EntityStateDto;
import com.antar.cameraorchestrator.model.entity.StateHistoryEntity;
import com.antar.cameraorchestrator.repository.StateHistoryRepository;
import com.antar.cameraorchestrator.service.StateEngineService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("dev")
class StateEngineServiceTest {

    @Autowired
    private StateEngineService stateEngineService;

    @Autowired
    private StateHistoryRepository historyRepository;

    @Test
    void testStateUpdateAndCacheLookup() {
        String entityId = "light.living_room";
        EntityStateDto updated = stateEngineService.updateState(entityId, "ON", Map.of("brightness", 80));

        assertNotNull(updated);
        assertEquals("ON", updated.getState());
        assertEquals("light.living_room", updated.getEntityId());
        assertEquals(80, updated.getAttributes().get("brightness"));

        // Fast in-memory cache lookup
        Optional<EntityStateDto> cached = stateEngineService.getState(entityId);
        assertTrue(cached.isPresent());
        assertEquals("ON", cached.get().getState());
        assertEquals("esp32_relay1", cached.get().getDeviceId(), "EntityStateDto must retain deviceId");
        assertEquals("living_room", cached.get().getAreaId(), "EntityStateDto must retain areaId");
    }

    @Test
    void testStateHistoryPersisted() throws InterruptedException {
        String entityId = "sensor.living_room_climate";
        stateEngineService.updateState(entityId, "24.5", Map.of("humidity", 60));

        // Allow async persistence to write
        Thread.sleep(300);

        List<StateHistoryEntity> history = historyRepository.findAll();
        assertFalse(history.isEmpty());
        assertTrue(history.stream().anyMatch(h -> h.getEntityId().equals(entityId)));
    }
}
