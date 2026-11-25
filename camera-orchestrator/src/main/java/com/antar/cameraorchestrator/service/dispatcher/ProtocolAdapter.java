package com.antar.cameraorchestrator.service.dispatcher;

import com.antar.cameraorchestrator.model.dto.DeviceCommand;

public interface ProtocolAdapter {
    String getProtocol();
    boolean sendCommand(String deviceId, DeviceCommand command);
}
