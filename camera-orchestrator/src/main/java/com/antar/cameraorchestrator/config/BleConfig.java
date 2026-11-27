package com.antar.cameraorchestrator.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "ble")
public class BleConfig {

    private boolean enabled = true;
    private String adapterName = "hci0";
    private int scanIntervalMs = 5000;
    private int rssiThreshold = -90;
    private int timeoutSeconds = 120;
    private boolean scanActive = true;

    public boolean isScanActive() {
        return scanActive;
    }

    public void setScanActive(boolean scanActive) {
        this.scanActive = scanActive;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getAdapterName() {
        return adapterName;
    }

    public void setAdapterName(String adapterName) {
        this.adapterName = adapterName;
    }

    public int getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(int scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public int getRssiThreshold() {
        return rssiThreshold;
    }

    public void setRssiThreshold(int rssiThreshold) {
        this.rssiThreshold = rssiThreshold;
    }

    public int getTimeoutSeconds() {
        return timeoutSeconds;
    }

    public void setTimeoutSeconds(int timeoutSeconds) {
        this.timeoutSeconds = timeoutSeconds;
    }
}
