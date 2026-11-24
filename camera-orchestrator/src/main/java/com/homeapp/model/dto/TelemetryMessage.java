package com.homeapp.model.dto;

import java.util.Map;

public class TelemetryMessage {

    private String deviceId;
    private String entityId;
    private String domain;
    private String state;
    private Map<String, Object> attributes;

    public TelemetryMessage() {}

    public TelemetryMessage(String deviceId, String entityId, String domain, String state, Map<String, Object> attributes) {
        this.deviceId = deviceId;
        this.entityId = entityId;
        this.domain = domain;
        this.state = state;
        this.attributes = attributes;
    }

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
}
