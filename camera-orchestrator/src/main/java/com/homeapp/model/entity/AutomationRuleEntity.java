package com.homeapp.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "automation_rules")
public class AutomationRuleEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "description", columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_enabled", nullable = false)
    private boolean enabled = true;

    @Column(name = "trigger_entity_id", length = 128)
    private String triggerEntityId;

    @Column(name = "trigger_state", length = 128)
    private String triggerState;

    @Column(name = "target_domain", length = 32, nullable = false)
    private String targetDomain;

    @Column(name = "target_service", length = 32, nullable = false)
    private String targetService;

    @Column(name = "target_entity_id", length = 128, nullable = false)
    private String targetEntityId;

    @Column(name = "action_payload_json", columnDefinition = "TEXT")
    private String actionPayloadJson;

    @Column(name = "last_triggered")
    private LocalDateTime lastTriggered;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public AutomationRuleEntity() {}

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getTriggerEntityId() { return triggerEntityId; }
    public void setTriggerEntityId(String triggerEntityId) { this.triggerEntityId = triggerEntityId; }

    public String getTriggerState() { return triggerState; }
    public void setTriggerState(String triggerState) { this.triggerState = triggerState; }

    public String getTargetDomain() { return targetDomain; }
    public void setTargetDomain(String targetDomain) { this.targetDomain = targetDomain; }

    public String getTargetService() { return targetService; }
    public void setTargetService(String targetService) { this.targetService = targetService; }

    public String getTargetEntityId() { return targetEntityId; }
    public void setTargetEntityId(String targetEntityId) { this.targetEntityId = targetEntityId; }

    public String getActionPayloadJson() { return actionPayloadJson; }
    public void setActionPayloadJson(String actionPayloadJson) { this.actionPayloadJson = actionPayloadJson; }

    public LocalDateTime getLastTriggered() { return lastTriggered; }
    public void setLastTriggered(LocalDateTime lastTriggered) { this.lastTriggered = lastTriggered; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
