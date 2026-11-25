package com.antar.cameraorchestrator.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "entities")
public class EntityStateEntity {

    @Id
    @Column(name = "id", length = 128, nullable = false)
    private String id; // e.g. 'light.living_room'

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "device_id", length = 64)
    private String deviceId;

    @Column(name = "area_id", length = 64)
    private String areaId;

    @Column(name = "domain", length = 32, nullable = false)
    private String domain; // e.g. 'light', 'switch', 'sensor'

    @Column(name = "current_state", length = 64, nullable = false)
    private String currentState; // e.g. 'ON', 'OFF', '23.5', 'unavailable'

    @Column(name = "attributes_json", columnDefinition = "TEXT")
    private String attributesJson; // JSON string for attributes

    @Column(name = "last_changed", nullable = false)
    private LocalDateTime lastChanged;

    @Column(name = "last_updated", nullable = false)
    private LocalDateTime lastUpdated;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (lastChanged == null) {
            lastChanged = now;
        }
        if (lastUpdated == null) {
            lastUpdated = now;
        }
        if (createdAt == null) {
            createdAt = now;
        }
    }

    public EntityStateEntity() {}

    public EntityStateEntity(String id, String name, String deviceId, String areaId, String domain, String currentState, String attributesJson) {
        this.id = id;
        this.name = name;
        this.deviceId = deviceId;
        this.areaId = areaId;
        this.domain = domain;
        this.currentState = currentState;
        this.attributesJson = attributesJson;
        this.lastChanged = LocalDateTime.now();
        this.lastUpdated = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getAreaId() { return areaId; }
    public void setAreaId(String areaId) { this.areaId = areaId; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getCurrentState() { return currentState; }
    public void setCurrentState(String currentState) { this.currentState = currentState; }

    public String getAttributesJson() { return attributesJson; }
    public void setAttributesJson(String attributesJson) { this.attributesJson = attributesJson; }

    public LocalDateTime getLastChanged() { return lastChanged; }
    public void setLastChanged(LocalDateTime lastChanged) { this.lastChanged = lastChanged; }

    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
