package com.antar.cameraorchestrator.repository;

import com.antar.cameraorchestrator.model.entity.DeviceEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface DeviceRepository extends JpaRepository<DeviceEntity, String> {
    List<DeviceEntity> findByAreaId(String areaId);
    List<DeviceEntity> findByOnline(boolean online);
    List<DeviceEntity> findByProtocol(String protocol);
    Optional<DeviceEntity> findByMacAddress(String macAddress);
}
