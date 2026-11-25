package com.antar.cameraorchestrator.service.adapter.ble;

import com.antar.cameraorchestrator.config.BleConfig;
import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import com.antar.cameraorchestrator.repository.DeviceRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class BleAdapterService {

    private final BleConfig bleConfig;
    private final DeviceRepository deviceRepository;
    private final BleInboundHandler bleInboundHandler;

    public BleAdapterService(BleConfig bleConfig,
                             DeviceRepository deviceRepository,
                             BleInboundHandler bleInboundHandler) {
        this.bleConfig = bleConfig;
        this.deviceRepository = deviceRepository;
        this.bleInboundHandler = bleInboundHandler;
    }

    public Map<String, Object> getAdapterStatus() {
        List<DeviceEntity> bleDevices = getBleDevices();
        long onlineCount = bleDevices.stream().filter(DeviceEntity::isOnline).count();

        Map<String, Object> status = new HashMap<>();
        status.put("protocol", "BLE");
        status.put("enabled", bleConfig.isEnabled());
        status.put("adapter_name", bleConfig.getAdapterName());
        status.put("scan_active", bleConfig.isScanActive());
        status.put("rssi_threshold", bleConfig.getRssiThreshold());
        status.put("total_devices", bleDevices.size());
        status.put("online_devices", onlineCount);
        return status;
    }

    public List<DeviceEntity> getBleDevices() {
        return deviceRepository.findAll().stream()
                .filter(d -> "BLE".equalsIgnoreCase(d.getProtocol()))
                .collect(Collectors.toList());
    }

    public void processAdvertisement(BleAdvertisementPayload payload) {
        bleInboundHandler.handleAdvertisement(payload);
    }

    public void disconnectDevice(String deviceId) {
        bleInboundHandler.handleDisconnect(deviceId);
    }
}
