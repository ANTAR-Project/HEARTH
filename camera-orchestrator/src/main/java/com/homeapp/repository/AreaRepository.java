package com.homeapp.repository;

import com.homeapp.model.entity.AreaEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AreaRepository extends JpaRepository<AreaEntity, String> {
    Optional<AreaEntity> findByNameIgnoreCase(String name);
}
