package com.antar.cameraorchestrator.config;

import org.springframework.web.socket.WebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final WebSocketHandler stateWebSocketHandler;

    public WebSocketConfig(WebSocketHandler stateWebSocketHandler) {
        this.stateWebSocketHandler = stateWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(stateWebSocketHandler, "/ws/states")
                .setAllowedOrigins("*");
    }
}
