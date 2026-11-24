package com.homeapp.model.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "devices")
public class DeviceEntity {

    @Id
    @Column(name = "id", length = 64, nullable = false)
    private String id;

    @Column(name = "name", length = 128, nullable = false)
    private String name;

    @Column(name = "area_id", length = 64)
    private String areaId;

    @Column(name = "protocol", length = 32, nullable = false)
    private String protocol = "MQTT";

    @Column(name = "ip_address", length = 64)
    private String ipAddress;

    @Column(name = "mac_address", length = 64)
    private String macAddress;

    @Column(name = "mqtt_topic_prefix", length = 128)
    private String mqttTopicPrefix;

    @Column(name = "firmware_version", length = 64)
    private String firmwareVersion;

    @Column(name = "is_online", nullable = false)
    private boolean online = false;

    @Column(name = "last_seen")
    private LocalDateTime lastSeen;

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

    public DeviceEntity() {}

    public DeviceEntity(String id, String name, String areaId, String protocol, String ipAddress, String mqttTopicPrefix) {
        this.id = id;
        this.name = name;
        this.areaId = areaId;
        this.protocol = protocol != null ? protocol : "MQTT";
        this.ipAddress = ipAddress;
        this.mqttTopicPrefix = mqttTopicPrefix;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getAreaId() { return areaId; }
    public void setAreaId(String areaId) { this.areaId = areaId; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getIpAddress() { return ipAddress; }
    public void setIpAddress(String ipAddress) { this.ipAddress = ipAddress; }

    public String getMacAddress() { return macAddress; }
    public void setMacAddress(String macAddress) { this.macAddress = macAddress; }

    public String getMqttTopicPrefix() { return mqttTopicPrefix; }
    public void setMqttTopicPrefix(String mqttTopicPrefix) { this.mqttTopicPrefix = mqttTopicPrefix; }

    public String getFirmwareVersion() { return firmwareVersion; }
    public void setFirmwareVersion(String firmwareVersion) { this.firmwareVersion = firmwareVersion; }

    public boolean isOnline() { return online; }
    public void setOnline(boolean online) { this.online = online; }

    public LocalDateTime getLastSeen() { return lastSeen; }
    public void setLastSeen(LocalDateTime lastSeen) { this.lastSeen = lastSeen; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
