package com.homeapp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homeapp.model.dto.EntityStateDto;
import com.homeapp.model.event.StateChangedEvent;
import com.homeapp.websocket.StateWebSocketHandler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.security.Principal;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
@ActiveProfiles("dev")
class StateWebSocketHandlerTest {

    @Autowired
    private StateWebSocketHandler webSocketHandler;

    @Autowired
    private ObjectMapper objectMapper;

    static class TestWebSocketSession implements WebSocketSession {
        private final String id = "mock-session-" + UUID.randomUUID();
        private boolean open = true;
        final List<WebSocketMessage<?>> sentMessages = new CopyOnWriteArrayList<>();

        @Override public String getId() { return id; }
        @Override public URI getUri() { return URI.create("/ws/states"); }
        @Override public HttpHeaders getHandshakeHeaders() { return new HttpHeaders(); }
        @Override public Map<String, Object> getAttributes() { return new HashMap<>(); }
        @Override public Principal getPrincipal() { return null; }
        @Override public InetSocketAddress getLocalAddress() { return null; }
        @Override public InetSocketAddress getRemoteAddress() { return null; }
        @Override public String getAcceptedProtocol() { return null; }
        @Override public void setTextMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getTextMessageSizeLimit() { return 65536; }
        @Override public void setBinaryMessageSizeLimit(int messageSizeLimit) {}
        @Override public int getBinaryMessageSizeLimit() { return 65536; }
        @Override public List<WebSocketExtension> getExtensions() { return Collections.emptyList(); }
        @Override public synchronized void sendMessage(WebSocketMessage<?> message) throws IOException { sentMessages.add(message); }
        @Override public boolean isOpen() { return open; }
        @Override public synchronized void close() throws IOException { open = false; }
        @Override public synchronized void close(CloseStatus status) throws IOException { open = false; }
    }

    @Test
    void testClientConnectionReceivesSnapshot() throws Exception {
        TestWebSocketSession session = new TestWebSocketSession();

        webSocketHandler.afterConnectionEstablished(session);

        assertTrue(session.sentMessages.size() >= 1);
        String snapshotJson = (String) session.sentMessages.get(0).getPayload();
        assertTrue(snapshotJson.contains("initial_states") || snapshotJson.contains("states"));
        assertEquals(1, webSocketHandler.getActiveSessionCount());

        // Simulate State Changed Event broadcast
        EntityStateDto oldDto = new EntityStateDto("light.living_room", "Living Room Light", "light", "OFF", Map.of(), null, null);
        EntityStateDto newDto = new EntityStateDto("light.living_room", "Living Room Light", "light", "ON", Map.of(), null, null);
        StateChangedEvent event = new StateChangedEvent(this, "light.living_room", oldDto, newDto);

        webSocketHandler.onStateChanged(event);

        // Allow async broadcast execution
        Thread.sleep(300);

        assertTrue(session.sentMessages.size() >= 2);
        String broadcastJson = (String) session.sentMessages.get(1).getPayload();
        assertTrue(broadcastJson.contains("state_changed"));

        webSocketHandler.afterConnectionClosed(session, CloseStatus.NORMAL);
        assertEquals(0, webSocketHandler.getActiveSessionCount());
    }
}
