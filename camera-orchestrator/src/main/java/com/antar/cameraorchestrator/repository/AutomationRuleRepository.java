package com.antar.cameraorchestrator.repository;

import com.antar.cameraorchestrator.model.entity.AutomationRuleEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface AutomationRuleRepository extends JpaRepository<AutomationRuleEntity, String> {
    List<AutomationRuleEntity> findByEnabledTrue();
    List<AutomationRuleEntity> findByTriggerEntityIdAndEnabledTrue(String triggerEntityId);
}
