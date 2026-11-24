package com.homeapp.controller.api;

import com.homeapp.model.entity.AutomationRuleEntity;
import com.homeapp.repository.AutomationRuleRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/automations")
public class AutomationApiController {

    private final AutomationRuleRepository ruleRepository;

    public AutomationApiController(AutomationRuleRepository ruleRepository) {
        this.ruleRepository = ruleRepository;
    }

    @GetMapping
    public ResponseEntity<List<AutomationRuleEntity>> getAllRules() {
        return ResponseEntity.ok(ruleRepository.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<AutomationRuleEntity> getRuleById(@PathVariable String id) {
        return ruleRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<AutomationRuleEntity> createRule(@RequestBody AutomationRuleEntity rule) {
        if (rule.getId() == null || rule.getId().isBlank()) {
            rule.setId("rule_" + System.currentTimeMillis());
        }
        return ResponseEntity.ok(ruleRepository.save(rule));
    }

    @PostMapping("/{id}/toggle")
    public ResponseEntity<AutomationRuleEntity> toggleRule(@PathVariable String id) {
        return ruleRepository.findById(id)
                .map(rule -> {
                    rule.setEnabled(!rule.isEnabled());
                    return ResponseEntity.ok(ruleRepository.save(rule));
                })
                .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteRule(@PathVariable String id) {
        if (ruleRepository.existsById(id)) {
            ruleRepository.deleteById(id);
            return ResponseEntity.ok(Map.of("deleted", true, "id", id));
        }
        return ResponseEntity.notFound().build();
    }
}
