package com.homeapp.model.dto;

import java.time.LocalDateTime;
import java.util.Map;

public class EntityStateDto {

    private String entityId;
    private String name;
    private String domain;
    private String deviceId;
    private String areaId;
    private String state;
    private Map<String, Object> attributes;
    private LocalDateTime lastChanged;
    private LocalDateTime lastUpdated;

    public EntityStateDto() {}

    public EntityStateDto(String entityId, String name, String domain, String state,
                          Map<String, Object> attributes, LocalDateTime lastChanged, LocalDateTime lastUpdated) {
        this(entityId, name, domain, null, null, state, attributes, lastChanged, lastUpdated);
    }

    public EntityStateDto(String entityId, String name, String domain, String deviceId, String areaId, String state,
                          Map<String, Object> attributes, LocalDateTime lastChanged, LocalDateTime lastUpdated) {
        this.entityId = entityId;
        this.name = name;
        this.domain = domain;
        this.deviceId = deviceId;
        this.areaId = areaId;
        this.state = state;
        this.attributes = attributes;
        this.lastChanged = lastChanged;
        this.lastUpdated = lastUpdated;
    }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getAreaId() { return areaId; }
    public void setAreaId(String areaId) { this.areaId = areaId; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }

    public LocalDateTime getLastChanged() { return lastChanged; }
    public void setLastChanged(LocalDateTime lastChanged) { this.lastChanged = lastChanged; }

    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
}
