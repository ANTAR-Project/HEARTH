package com.antar.cameraorchestrator.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.antar.cameraorchestrator.model.dto.EntityStateDto;
import com.antar.cameraorchestrator.model.entity.EntityStateEntity;
import com.antar.cameraorchestrator.model.entity.StateHistoryEntity;
import com.antar.cameraorchestrator.model.event.StateChangedEvent;
import com.antar.cameraorchestrator.repository.EntityStateRepository;
import com.antar.cameraorchestrator.repository.StateHistoryRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Service
public class StateEngineService {

    private static final Logger log = LoggerFactory.getLogger(StateEngineService.class);

    private final EntityStateRepository entityRepository;
    private final StateHistoryRepository historyRepository;
    private final Cache<String, EntityStateDto> stateCache;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    // Fast domain index in memory for O(1) domain lookups
    private final Map<String, Set<String>> domainIndex = new ConcurrentHashMap<>();

    public StateEngineService(EntityStateRepository entityRepository,
                              StateHistoryRepository historyRepository,
                              Cache<String, EntityStateDto> stateCache,
                              ApplicationEventPublisher eventPublisher,
                              ObjectMapper objectMapper) {
        this.entityRepository = entityRepository;
        this.historyRepository = historyRepository;
        this.stateCache = stateCache;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void warmupCache() {
        log.info("Warming up State Engine cache from database...");
        List<EntityStateEntity> all = entityRepository.findAll();
        for (EntityStateEntity entity : all) {
            EntityStateDto dto = toDto(entity);
            stateCache.put(entity.getId(), dto);
            domainIndex.computeIfAbsent(entity.getDomain(), k -> ConcurrentHashMap.newKeySet()).add(entity.getId());
        }
        log.info("State Engine cache warmed up with {} entities.", all.size());
    }

    public Optional<EntityStateDto> getState(String entityId) {
        EntityStateDto cached = stateCache.getIfPresent(entityId);
        if (cached != null) {
            return Optional.of(cached);
        }
        return entityRepository.findById(entityId).map(entity -> {
            EntityStateDto dto = toDto(entity);
            stateCache.put(entity.getId(), dto);
            domainIndex.computeIfAbsent(entity.getDomain(), k -> ConcurrentHashMap.newKeySet()).add(entity.getId());
            return dto;
        });
    }

    public List<EntityStateDto> getAllStates() {
        // Collect from cache, or fallback to DB if cache is cold
        Map<String, EntityStateDto> map = stateCache.asMap();
        if (map.isEmpty()) {
            return entityRepository.findAll().stream()
                    .map(this::toDto)
                    .collect(Collectors.toList());
        }
        return new ArrayList<>(map.values());
    }

    public List<EntityStateDto> getStatesByDomain(String domain) {
        Set<String> ids = domainIndex.get(domain);
        if (ids != null && !ids.isEmpty()) {
            List<EntityStateDto> result = new ArrayList<>();
            for (String id : ids) {
                getState(id).ifPresent(result::add);
            }
            return result;
        }
        return entityRepository.findByDomain(domain).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    public List<EntityStateDto> getStatesByArea(String areaId) {
        return entityRepository.findByAreaId(areaId).stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    @Transactional
    public EntityStateDto updateState(String entityId, String newState, Map<String, Object> attributes) {
        Optional<EntityStateEntity> opt = entityRepository.findById(entityId);
        LocalDateTime now = LocalDateTime.now();

        EntityStateEntity entity;
        EntityStateDto oldDto = stateCache.getIfPresent(entityId);
        boolean stateChanged = false;

        if (opt.isPresent()) {
            entity = opt.get();
            if (oldDto == null) {
                oldDto = toDto(entity);
            }
            stateChanged = !Objects.equals(entity.getCurrentState(), newState);

            entity.setCurrentState(newState);
            entity.setLastUpdated(now);
            if (stateChanged) {
                entity.setLastChanged(now);
            }
            if (attributes != null && !attributes.isEmpty()) {
                Map<String, Object> merged = parseAttributes(entity.getAttributesJson());
                merged.putAll(attributes);
                entity.setAttributesJson(toJson(merged));
            }
        } else {
            // Auto-discover entity on the fly
            String domain = entityId.contains(".") ? entityId.split("\\.")[0] : "sensor";
            String name = formatEntityName(entityId);
            entity = new EntityStateEntity(entityId, name, null, null, domain, newState, toJson(attributes != null ? attributes : Collections.emptyMap()));
            entity.setLastChanged(now);
            entity.setLastUpdated(now);
            stateChanged = true;
            log.info("Auto-discovered new entity: {}", entityId);
        }

        entity = entityRepository.save(entity);
        EntityStateDto newDto = toDto(entity);

        // 1. Update in-memory Caffeine Cache (sub-millisecond reads)
        stateCache.put(entityId, newDto);
        domainIndex.computeIfAbsent(entity.getDomain(), k -> ConcurrentHashMap.newKeySet()).add(entityId);

        // 2. Persist state history if state changed
        if (stateChanged) {
            recordHistory(entityId, newState, entity.getAttributesJson(), now);
        }

        // 3. Publish Spring ApplicationEvent for Automation Engine and WebSocket push
        eventPublisher.publishEvent(new StateChangedEvent(this, entityId, oldDto, newDto));

        log.info("Entity [{}] state updated: {} -> {} (changed={})",
                entityId, oldDto != null ? oldDto.getState() : "null", newState, stateChanged);

        return newDto;
    }

    @Async("taskExecutor")
    public void recordHistory(String entityId, String state, String attributesJson, LocalDateTime timestamp) {
        try {
            StateHistoryEntity history = new StateHistoryEntity(entityId, state, attributesJson, timestamp);
            historyRepository.save(history);
        } catch (Exception e) {
            log.error("Failed to record history for entity [{}]: {}", entityId, e.getMessage());
        }
    }

    private EntityStateDto toDto(EntityStateEntity entity) {
        return new EntityStateDto(
                entity.getId(),
                entity.getName(),
                entity.getDomain(),
                entity.getDeviceId(),
                entity.getAreaId(),
                entity.getCurrentState(),
                parseAttributes(entity.getAttributesJson()),
                entity.getLastChanged(),
                entity.getLastUpdated()
        );
    }

    private Map<String, Object> parseAttributes(String json) {
        if (json == null || json.isBlank()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            log.warn("Failed to parse attributes JSON: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    private String toJson(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize attributes map to JSON: {}", e.getMessage());
            return "{}";
        }
    }

    private String formatEntityName(String entityId) {
        String base = entityId.contains(".") ? entityId.split("\\.")[1] : entityId;
        return Arrays.stream(base.split("_"))
                .map(s -> s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1).toLowerCase())
                .collect(Collectors.joining(" "));
    }
}
