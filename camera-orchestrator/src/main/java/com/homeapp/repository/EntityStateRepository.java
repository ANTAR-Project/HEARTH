package com.homeapp.repository;

import com.homeapp.model.entity.EntityStateEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface EntityStateRepository extends JpaRepository<EntityStateEntity, String> {
    List<EntityStateEntity> findByDomain(String domain);
    List<EntityStateEntity> findByAreaId(String areaId);
    List<EntityStateEntity> findByDeviceId(String deviceId);
}
