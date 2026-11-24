package com.homeapp.service.adapter.ble;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class BleAdvertisementPayload {

    @JsonProperty("device_id")
    private String deviceId;

    @JsonProperty("mac_address")
    private String macAddress;

    @JsonProperty("device_name")
    private String deviceName;

    @JsonProperty("rssi")
    private Integer rssi;

    @JsonProperty("battery")
    private Integer battery;

    @JsonProperty("data")
    private Map<String, Object> data;

    @JsonProperty("state")
    private String state;

    @JsonProperty("domain")
    private String domain;

    @JsonProperty("entity_id")
    private String entityId;

    public BleAdvertisementPayload() {}

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public Integer getRssi() { return rssi; }
    public void setRssi(Integer rssi) { this.rssi = rssi; }

    public Integer getBattery() { return battery; }
    public void setBattery(Integer battery) { this.battery = battery; }

    public Map<String, Object> getData() { return data; }
    public void setData(Map<String, Object> data) { this.data = data; }

    public String getState() { return state; }
    public void setState(String state) { this.state = state; }

    public String getDomain() { return domain; }
    public void setDomain(String domain) { this.domain = domain; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }
}
