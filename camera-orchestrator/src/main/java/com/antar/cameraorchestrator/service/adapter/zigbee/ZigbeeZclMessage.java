package com.antar.cameraorchestrator.service.adapter.zigbee;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

public class ZigbeeZclMessage {

    public static final int CLUSTER_BASIC = 0x0000;
    public static final int CLUSTER_ON_OFF = 0x0006;
    public static final int CLUSTER_LEVEL_CONTROL = 0x0008;
    public static final int CLUSTER_TEMPERATURE = 0x0402;
    public static final int CLUSTER_HUMIDITY = 0x0405;
    public static final int CLUSTER_OCCUPANCY = 0x0406;
    public static final int CLUSTER_IAS_ZONE = 0x0500;
    public static final int CLUSTER_ELECTRICAL_MEASUREMENT = 0x0B04;

    public static final int CMD_OFF = 0x00;
    public static final int CMD_ON = 0x01;
    public static final int CMD_TOGGLE = 0x02;
    public static final int CMD_MOVE_TO_LEVEL_WITH_ON_OFF = 0x04;

    @JsonProperty("device_id")
    private String deviceId;

    @JsonProperty("ieee_address")
    private String ieeeAddress;

    @JsonProperty("device_name")
    private String deviceName;

    @JsonProperty("endpoint")
    private Integer endpoint = 1;

    @JsonProperty("cluster_id")
    private Integer clusterId;

    @JsonProperty("command_id")
    private Integer commandId;

    @JsonProperty("link_quality")
    private Integer linkQuality;

    @JsonProperty("rssi")
    private Integer rssi;

    @JsonProperty("attributes")
    private Map<String, Object> attributes;

    public ZigbeeZclMessage() {}

    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }

    public String getIeeeAddress() { return ieeeAddress; }
    public void setIeeeAddress(String ieeeAddress) { this.ieeeAddress = ieeeAddress; }

    public String getDeviceName() { return deviceName; }
    public void setDeviceName(String deviceName) { this.deviceName = deviceName; }

    public Integer getEndpoint() { return endpoint; }
    public void setEndpoint(Integer endpoint) { this.endpoint = endpoint; }

    public Integer getClusterId() { return clusterId; }
    public void setClusterId(Integer clusterId) { this.clusterId = clusterId; }

    public Integer getCommandId() { return commandId; }
    public void setCommandId(Integer commandId) { this.commandId = commandId; }

    public Integer getLinkQuality() { return linkQuality; }
    public void setLinkQuality(Integer linkQuality) { this.linkQuality = linkQuality; }

    public Integer getRssi() { return rssi; }
    public void setRssi(Integer rssi) { this.rssi = rssi; }

    public Map<String, Object> getAttributes() { return attributes; }
    public void setAttributes(Map<String, Object> attributes) { this.attributes = attributes; }
}
