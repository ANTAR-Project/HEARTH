package com.homeapp.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeapp.model.dto.EntityStateDto;
import com.homeapp.model.dto.StateChangeEventDto;
import com.homeapp.model.event.DeviceStatusChangedEvent;
import com.homeapp.model.event.StateChangedEvent;
import com.homeapp.service.StateEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class StateWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(StateWebSocketHandler.class);

    private final StateEngineService stateEngineService;
    private final ObjectMapper objectMapper;

    // Active client sessions thread-safe set
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    public StateWebSocketHandler(StateEngineService stateEngineService, ObjectMapper objectMapper) {
        this.stateEngineService = stateEngineService;
        this.objectMapper = objectMapper;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        log.info("New WebSocket client connected: [session_id={}] (Total active: {})",
                session.getId(), sessions.size());

        // Push initial state dump so the UI immediately populates on connect
        List<EntityStateDto> allStates = stateEngineService.getAllStates();
        Map<String, Object> initialDump = Map.of(
                "type", "initial_states",
                "count", allStates.size(),
                "states", allStates
        );
        String json = objectMapper.writeValueAsString(initialDump);
        session.sendMessage(new TextMessage(json));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        log.info("WebSocket client disconnected: [session_id={}] (Remaining: {})",
                session.getId(), sessions.size());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.warn("WebSocket transport error for session [{}]: {}", session.getId(), exception.getMessage());
        sessions.remove(session);
        try {
            session.close(CloseStatus.SERVER_ERROR);
        } catch (Exception ignored) {}
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.debug("Received message from client [{}]: {}", session.getId(), payload);
        // Clients can send ping / subscribe requests
        if ("ping".equalsIgnoreCase(payload.trim())) {
            session.sendMessage(new TextMessage("{\"type\":\"pong\"}"));
        }
    }

    @Async("taskExecutor")
    @EventListener
    public void onStateChanged(StateChangedEvent event) {
        if (sessions.isEmpty()) {
            return;
        }

        StateChangeEventDto dto = new StateChangeEventDto(
                event.getEntityId(),
                event.getOldState(),
                event.getNewState()
        );

        try {
            String json = objectMapper.writeValueAsString(dto);
            broadcastMessage(json);
        } catch (Exception e) {
            log.error("Failed to serialize StateChangeEvent for WebSocket broadcast: {}", e.getMessage());
        }
    }

    @Async("taskExecutor")
    @EventListener
    public void onDeviceStatusChanged(DeviceStatusChangedEvent event) {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            Map<String, Object> payload = Map.of(
                    "type", "device_status_changed",
                    "device_id", event.getDeviceId(),
                    "online", event.isOnline()
            );
            String json = objectMapper.writeValueAsString(payload);
            broadcastMessage(json);
        } catch (Exception e) {
            log.error("Failed to serialize DeviceStatusChangedEvent for WebSocket broadcast: {}", e.getMessage());
        }
    }

    private void broadcastMessage(String messageText) {
        TextMessage textMessage = new TextMessage(messageText);
        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    synchronized (session) {
                        session.sendMessage(textMessage);
                    }
                } catch (IOException e) {
                    log.warn("Failed to push WebSocket message to session [{}]: {}", session.getId(), e.getMessage());
                    sessions.remove(session);
                }
            } else {
                sessions.remove(session);
            }
        }
    }

    public int getActiveSessionCount() {
        return sessions.size();
    }
}
