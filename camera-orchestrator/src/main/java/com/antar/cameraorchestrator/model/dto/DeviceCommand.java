package com.antar.cameraorchestrator.model.dto;

import java.util.Map;

public class DeviceCommand {

    private String state;
    private Map<String, Object> attributes;

    public DeviceCommand() {}

    public DeviceCommand(String state, Map<String, Object> attributes) {
        this.state = state;
        this.attributes = attributes;
    }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
}
