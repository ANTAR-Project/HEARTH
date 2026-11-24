package com.homeapp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "camera")
public class CameraConfig {

    private boolean enabled = true;
    private String defaultIp = "192.168.30.247";
    private int rtspPort = 5543;
    private int httpPort = 8000;
    private String username = "admin";
    private String password = "";
    private String substreamPath = "/live/channel1";
    private String mainstreamPath = "/live/channel0";
    private String snapshotPath = "/cgi-bin/snapshot.cgi?channel=1";
    private int pollIntervalMs = 15000;
    private String subnet = "192.168.30.0/24";
    private int motionResetSeconds = 15;
    private String go2rtcUrl = "http://localhost:1984";
    private String onvifProfileToken = "PROFILE_1676656330";
    private String videoServiceUrl = "http://localhost:8082";
    private String nasOrchestratorUrl = "http://localhost:8081";
    private String streamingServiceUrl = "http://localhost:8084";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public String getDefaultIp() { return defaultIp; }
    public void setDefaultIp(String defaultIp) { this.defaultIp = defaultIp; }

    public int getRtspPort() { return rtspPort; }
    public void setRtspPort(int rtspPort) { this.rtspPort = rtspPort; }

    public int getHttpPort() { return httpPort; }
    public void setHttpPort(int httpPort) { this.httpPort = httpPort; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getSubstreamPath() { return substreamPath; }
    public void setSubstreamPath(String substreamPath) { this.substreamPath = substreamPath; }

    public String getMainstreamPath() { return mainstreamPath; }
    public void setMainstreamPath(String mainstreamPath) { this.mainstreamPath = mainstreamPath; }

    public String getSnapshotPath() { return snapshotPath; }
    public void setSnapshotPath(String snapshotPath) { this.snapshotPath = snapshotPath; }

    public int getPollIntervalMs() { return pollIntervalMs; }
    public void setPollIntervalMs(int pollIntervalMs) { this.pollIntervalMs = pollIntervalMs; }

    public String getSubnet() { return subnet; }
    public void setSubnet(String subnet) { this.subnet = subnet; }

    public int getMotionResetSeconds() { return motionResetSeconds; }
    public void setMotionResetSeconds(int motionResetSeconds) { this.motionResetSeconds = motionResetSeconds; }

    public String getGo2rtcUrl() { return go2rtcUrl; }
    public void setGo2rtcUrl(String go2rtcUrl) { this.go2rtcUrl = go2rtcUrl; }

    public String getOnvifProfileToken() { return onvifProfileToken; }
    public void setOnvifProfileToken(String onvifProfileToken) { this.onvifProfileToken = onvifProfileToken; }

    public String getVideoServiceUrl() { return videoServiceUrl; }
    public void setVideoServiceUrl(String videoServiceUrl) { this.videoServiceUrl = videoServiceUrl; }

    public String getNasOrchestratorUrl() { return nasOrchestratorUrl; }
    public void setNasOrchestratorUrl(String nasOrchestratorUrl) { this.nasOrchestratorUrl = nasOrchestratorUrl; }

    public String getStreamingServiceUrl() { return streamingServiceUrl; }
    public void setStreamingServiceUrl(String streamingServiceUrl) { this.streamingServiceUrl = streamingServiceUrl; }
}
