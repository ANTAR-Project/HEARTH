package com.homeapp.service.adapter.zigbee;

import com.homeapp.config.ZigbeeConfig;
import com.homeapp.model.entity.DeviceEntity;
import com.homeapp.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ZigbeeAdapterService {

    private final ZigbeeConfig zigbeeConfig;
    private final DeviceRepository deviceRepository;
    private final ZigbeeInboundHandler zigbeeInboundHandler;
    private final ZigbeeOutboundService zigbeeOutboundService;

    private boolean permitJoinActive = false;
    private long permitJoinExpiresAt = 0;

    public ZigbeeAdapterService(ZigbeeConfig zigbeeConfig,
                                DeviceRepository deviceRepository,
                                ZigbeeInboundHandler zigbeeInboundHandler,
                                ZigbeeOutboundService zigbeeOutboundService) {
        this.zigbeeConfig = zigbeeConfig;
        this.deviceRepository = deviceRepository;
        this.zigbeeInboundHandler = zigbeeInboundHandler;
        this.zigbeeOutboundService = zigbeeOutboundService;
    }

    public Map<String, Object> getAdapterStatus() {
        List<DeviceEntity> zigbeeDevices = getZigbeeDevices();
        long onlineCount = zigbeeDevices.stream().filter(DeviceEntity::isOnline).count();

        boolean isJoinOpen = permitJoinActive && System.currentTimeMillis() < permitJoinExpiresAt;

        Map<String, Object> status = new HashMap<>();
        status.put("protocol", "ZIGBEE");
        status.put("enabled", zigbeeConfig.isEnabled());
        status.put("pan_id", zigbeeConfig.getPanId());
        status.put("channel", zigbeeConfig.getChannel());
        status.put("coordinator_ieee", zigbeeConfig.getCoordinatorIeee());
        status.put("permit_join", isJoinOpen);
        status.put("permit_join_remaining_seconds", isJoinOpen ? Math.max(0, (permitJoinExpiresAt - System.currentTimeMillis()) / 1000) : 0);
        status.put("total_devices", zigbeeDevices.size());
        status.put("online_devices", onlineCount);
        return status;
    }

    public List<DeviceEntity> getZigbeeDevices() {
        return deviceRepository.findAll().stream()
                .filter(d -> "ZIGBEE".equalsIgnoreCase(d.getProtocol()))
                .collect(Collectors.toList());
    }

    public boolean setPermitJoin(boolean enable, Integer durationSeconds) {
        this.permitJoinActive = enable;
        int duration = (durationSeconds != null && durationSeconds > 0)
                ? durationSeconds
                : zigbeeConfig.getPermitJoinDurationSeconds();
        if (enable) {
            this.permitJoinExpiresAt = System.currentTimeMillis() + (duration * 1000L);
        } else {
            this.permitJoinExpiresAt = 0;
        }
        zigbeeOutboundService.sendPermitJoin(enable, duration);
        return this.permitJoinActive;
    }

    public void processZclReport(ZigbeeZclMessage message) {
        zigbeeInboundHandler.handleZclMessage(message);
    }

    public void disconnectDevice(String deviceId) {
        zigbeeInboundHandler.handleDeviceOffline(deviceId);
    }
}
