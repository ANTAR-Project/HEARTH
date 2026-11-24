package com.homeapp.model.event;

import com.homeapp.model.dto.EntityStateDto;
import org.springframework.context.ApplicationEvent;

public class StateChangedEvent extends ApplicationEvent {

    private final String entityId;
    private final EntityStateDto oldState;
    private final EntityStateDto newState;

    public StateChangedEvent(Object source, String entityId, EntityStateDto oldState, EntityStateDto newState) {
        super(source);
        this.entityId = entityId;
        this.oldState = oldState;
        this.newState = newState;
    }

    public String getEntityId() { return entityId; }
    public EntityStateDto getOldState() { return oldState; }
    public EntityStateDto getNewState() { return newState; }
}
