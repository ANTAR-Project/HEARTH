package com.homeapp.model.dto;

import java.time.LocalDateTime;

public class StateChangeEventDto {

    private String type = "state_changed";
    private String entityId;
    private EntityStateDto oldState;
    private EntityStateDto newState;
    private LocalDateTime timestamp;

    public StateChangeEventDto() {
        this.timestamp = LocalDateTime.now();
    }

    public StateChangeEventDto(String entityId, EntityStateDto oldState, EntityStateDto newState) {
        this.type = "state_changed";
        this.entityId = entityId;
        this.oldState = oldState;
        this.newState = newState;
        this.timestamp = LocalDateTime.now();
    }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public EntityStateDto getOldState() { return oldState; }
    public void setOldState(EntityStateDto oldState) { this.oldState = oldState; }

    public EntityStateDto getNewState() { return newState; }
    public void setNewState(EntityStateDto newState) { this.newState = newState; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }
}
