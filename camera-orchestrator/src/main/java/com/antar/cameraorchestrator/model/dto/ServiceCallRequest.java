package com.antar.cameraorchestrator.model.dto;

import java.util.Map;

public class ServiceCallRequest {

    private String domain;     // e.g. 'light', 'switch'
    private String service;    // e.g. 'turn_on', 'turn_off', 'toggle'
    private String entityId;   // e.g. 'light.living_room'
    private Map<String, Object> data; // e.g. { "brightness": 255 }

    public ServiceCallRequest() {}

    public ServiceCallRequest(String domain, String service, String entityId, Map<String, Object> data) {
        this.domain = domain;
        this.service = service;
        this.entityId = entityId;
        this.data = data;
    }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getService() { return service; }
    public void setService(String service) { this.service = service; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }
}
