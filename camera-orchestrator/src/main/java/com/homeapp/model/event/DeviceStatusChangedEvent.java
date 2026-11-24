package com.homeapp.model.event;

import org.springframework.context.ApplicationEvent;

public class DeviceStatusChangedEvent extends ApplicationEvent {

    private final String deviceId;
    private final boolean online;

    public DeviceStatusChangedEvent(Object source, String deviceId, boolean online) {
        super(source);
        this.deviceId = deviceId;
        this.online = online;
    }

    public String getDeviceId() { return deviceId; }
    public boolean isOnline() { return online; }
}
