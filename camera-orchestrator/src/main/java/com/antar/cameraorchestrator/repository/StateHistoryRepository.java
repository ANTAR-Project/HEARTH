package com.antar.cameraorchestrator.repository;

import com.antar.cameraorchestrator.model.entity.StateHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface StateHistoryRepository extends JpaRepository<StateHistoryEntity, Long> {

    List<StateHistoryEntity> findByEntityIdOrderByTimestampDesc(String entityId);

    @Query("SELECT h FROM StateHistoryEntity h WHERE h.entityId = :entityId AND h.timestamp >= :since ORDER BY h.timestamp ASC")
    List<StateHistoryEntity> findHistorySince(@Param("entityId") String entityId, @Param("since") LocalDateTime since);

    @Query("SELECT h FROM StateHistoryEntity h WHERE h.entityId = :entityId AND h.timestamp BETWEEN :start AND :end ORDER BY h.timestamp ASC")
    List<StateHistoryEntity> findHistoryBetween(@Param("entityId") String entityId,
                                                @Param("start") LocalDateTime start,
                                                @Param("end") LocalDateTime end);
}
