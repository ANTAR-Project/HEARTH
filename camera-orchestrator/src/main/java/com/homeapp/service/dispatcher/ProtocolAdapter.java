package com.homeapp.service.dispatcher;

import com.homeapp.model.dto.DeviceCommand;

public interface ProtocolAdapter {
    String getProtocol();
    boolean sendCommand(String deviceId, DeviceCommand command);
}
