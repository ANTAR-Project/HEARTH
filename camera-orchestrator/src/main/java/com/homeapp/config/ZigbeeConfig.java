package com.homeapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "zigbee")
public class ZigbeeConfig {

    private boolean enabled = true;
    private String panId = "0x1A62";
    private int channel = 15;
    private String coordinatorIeee = "00:12:4B:00:1C:DF:88:99";
    private int permitJoinDurationSeconds = 180;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPanId() {
        return panId;
    }

    public void setPanId(String panId) {
        this.panId = panId;
    }

    public int getChannel() {
        return channel;
    }

    public void setChannel(int channel) {
        this.channel = channel;
    }

    public String getCoordinatorIeee() {
        return coordinatorIeee;
    }

    public void setCoordinatorIeee(String coordinatorIeee) {
        this.coordinatorIeee = coordinatorIeee;
    }

    public int getPermitJoinDurationSeconds() {
        return permitJoinDurationSeconds;
    }

    public void setPermitJoinDurationSeconds(int permitJoinDurationSeconds) {
        this.permitJoinDurationSeconds = permitJoinDurationSeconds;
    }
}
