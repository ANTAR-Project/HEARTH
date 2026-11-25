package com.antar.cameraorchestrator;

import com.antar.cameraorchestrator.model.dto.EntityStateDto;
import com.antar.cameraorchestrator.model.entity.AutomationRuleEntity;
import com.antar.cameraorchestrator.model.event.StateChangedEvent;
import com.antar.cameraorchestrator.repository.AutomationRuleRepository;
import com.antar.cameraorchestrator.service.AutomationEngineService;
import com.antar.cameraorchestrator.service.dispatcher.ServiceDispatcher;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class AutomationEngineTest {

    @Autowired
    private AutomationEngineService automationEngineService;

    @Autowired
    private AutomationRuleRepository ruleRepository;

    @Autowired
    private ServiceDispatcher serviceDispatcher;

    @Test
    void testRuleEvaluationTrigger() throws InterruptedException {
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId("rule_test_climate");
        rule.setName("Test Climate Rule");
        rule.setEnabled(true);
        rule.setTriggerEntityId("sensor.test_temp");
        rule.setTriggerState("29.5");
        rule.setTargetDomain("switch");
        rule.setTargetService("turn_on");
        rule.setTargetEntityId("switch.living_room_fan");
        rule.setActionPayloadJson("{\"state\":\"ON\"}");
        ruleRepository.saveAndFlush(rule);

        EntityStateDto oldDto = new EntityStateDto("sensor.test_temp", "Test Temp", "sensor", "25.0", Map.of(), null, null);
        EntityStateDto newDto = new EntityStateDto("sensor.test_temp", "Test Temp", "sensor", "29.5", Map.of(), null, null);
        StateChangedEvent event = new StateChangedEvent(this, "sensor.test_temp", oldDto, newDto);

        automationEngineService.onStateChanged(event);

        // Allow async execution
        Thread.sleep(600);

        Optional<AutomationRuleEntity> found = ruleRepository.findById("rule_test_climate");
        assertTrue(found.isPresent());
        assertNotNull(found.get().getLastTriggered());
    }

    @Test
    void testNumericThresholdTriggers() throws InterruptedException {
        AutomationRuleEntity rule = new AutomationRuleEntity();
        rule.setId("rule_threshold_fan");
        rule.setName("Threshold Fan Rule");
        rule.setEnabled(true);
        rule.setTriggerEntityId("sensor.bedroom_temp");
        rule.setTriggerState("> 28.0");
        rule.setTargetDomain("switch");
        rule.setTargetService("turn_on");
        rule.setTargetEntityId("switch.living_room_fan");
        rule.setActionPayloadJson("{\"state\":\"ON\"}");
        ruleRepository.saveAndFlush(rule);

        // 1. Temperature below threshold (27.5) -> should NOT trigger
        EntityStateDto state27 = new EntityStateDto("sensor.bedroom_temp", "Bedroom Temp", "sensor", "27.5", Map.of(), null, null);
        StateChangedEvent eventBelow = new StateChangedEvent(this, "sensor.bedroom_temp", null, state27);
        automationEngineService.onStateChanged(eventBelow);
        Thread.sleep(300);
        Optional<AutomationRuleEntity> checked1 = ruleRepository.findById("rule_threshold_fan");
        assertTrue(checked1.isPresent());

        // 2. Temperature crosses threshold (29.2) -> MUST trigger
        EntityStateDto state29 = new EntityStateDto("sensor.bedroom_temp", "Bedroom Temp", "sensor", "29.2", Map.of(), null, null);
        StateChangedEvent eventAbove = new StateChangedEvent(this, "sensor.bedroom_temp", state27, state29);
        automationEngineService.onStateChanged(eventAbove);
        Thread.sleep(600);

        Optional<AutomationRuleEntity> checked2 = ruleRepository.findById("rule_threshold_fan");
        assertTrue(checked2.isPresent());
        assertNotNull(checked2.get().getLastTriggered(), "Rule with '> 28.0' should trigger when temp is 29.2");
    }
}
