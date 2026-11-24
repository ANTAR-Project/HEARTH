package com.homeapp.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeapp.model.dto.ServiceCallRequest;
import com.homeapp.model.entity.AutomationRuleEntity;
import com.homeapp.model.event.StateChangedEvent;
import com.homeapp.repository.AutomationRuleRepository;
import com.homeapp.service.dispatcher.ServiceDispatcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

@Service
public class AutomationEngineService {

    private static final Logger log = LoggerFactory.getLogger(AutomationEngineService.class);

    private final AutomationRuleRepository ruleRepository;
    private final ServiceDispatcher serviceDispatcher;
    private final ObjectMapper objectMapper;

    public AutomationEngineService(AutomationRuleRepository ruleRepository,
                                  ServiceDispatcher serviceDispatcher,
                                  ObjectMapper objectMapper) {
        this.ruleRepository = ruleRepository;
        this.serviceDispatcher = serviceDispatcher;
        this.objectMapper = objectMapper;
    }

    @Async("taskExecutor")
    @EventListener
    public void onStateChanged(StateChangedEvent event) {
        String entityId = event.getEntityId();
        String oldState = event.getOldState() != null ? event.getOldState().getState() : null;
        String newState = event.getNewState() != null ? event.getNewState().getState() : null;

        log.debug("Automation Engine evaluating rules for entity [{}] state change: {} -> {}",
                entityId, oldState, newState);

        List<AutomationRuleEntity> matchingRules =
                ruleRepository.findByTriggerEntityIdAndEnabledTrue(entityId);

        for (AutomationRuleEntity rule : matchingRules) {
            try {
                if (matchesTrigger(rule, oldState, newState)) {
                    log.info("Trigger matched for rule [{}]: '{}'. Executing action: {}.{} on {}",
                            rule.getId(), rule.getName(),
                            rule.getTargetDomain(), rule.getTargetService(), rule.getTargetEntityId());

                    Map<String, Object> actionData = parseJsonData(rule.getActionPayloadJson());
                    ServiceCallRequest request = new ServiceCallRequest(
                            rule.getTargetDomain(),
                            rule.getTargetService(),
                            rule.getTargetEntityId(),
                            actionData
                    );
                    serviceDispatcher.dispatch(request);

                    rule.setLastTriggered(LocalDateTime.now());
                    ruleRepository.save(rule);
                }
            } catch (Exception e) {
                log.error("Error evaluating/executing automation rule [{}]: {}", rule.getId(), e.getMessage(), e);
            }
        }
    }

    boolean matchesTrigger(AutomationRuleEntity rule, String oldState, String newState) {
        String triggerState = rule.getTriggerState();
        if (triggerState == null || triggerState.isBlank()) {
            return true;
        }

        if (newState == null) {
            return false;
        }

        triggerState = triggerState.trim();
        String trimmedNewState = newState.trim();

        // 1. Numeric comparisons: >, <, >=, <=
        if (triggerState.startsWith(">=") || triggerState.startsWith("<=") ||
                triggerState.startsWith(">") || triggerState.startsWith("<")) {
            try {
                double newVal = Double.parseDouble(trimmedNewState);
                if (triggerState.startsWith(">=")) {
                    double threshold = Double.parseDouble(triggerState.substring(2).trim());
                    return newVal >= threshold;
                } else if (triggerState.startsWith("<=")) {
                    double threshold = Double.parseDouble(triggerState.substring(2).trim());
                    return newVal <= threshold;
                } else if (triggerState.startsWith(">")) {
                    double threshold = Double.parseDouble(triggerState.substring(1).trim());
                    return newVal > threshold;
                } else if (triggerState.startsWith("<")) {
                    double threshold = Double.parseDouble(triggerState.substring(1).trim());
                    return newVal < threshold;
                }
            } catch (NumberFormatException e) {
                log.debug("Failed to parse numeric comparison for trigger '{}' vs state '{}'", triggerState, newState);
                return false;
            }
        }

        // 2. Exact string match
        return triggerState.equalsIgnoreCase(trimmedNewState);
    }

    private Map<String, Object> parseJsonData(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyMap();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse automation action data JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }
}
