package com.antar.cameraorchestrator.hearth;

import com.antar.hearth.common.DiskBackedJwkSource;
import com.antar.hearth.common.Hearth;
import com.antar.hearth.common.JwtVerifier;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URL;
import java.time.Duration;

@Configuration
public class CameraHearthConfig {
    @Value("${hearth.gateway.jwks-uri}")
    private String gatewayJwksUri;

    @Value("${spring.application.name:camera-orchestrator}")
    private String selfName;

    @Bean
    public JwtVerifier contextTokenVerifier() throws Exception {
        DiskBackedJwkSource src = new DiskBackedJwkSource(new URL(gatewayJwksUri), "hearth-gateway");
        return new JwtVerifier(src,
            new JwtVerifier.Spec(Hearth.TYP_CTX, Hearth.ISS_GATEWAY, selfName, Duration.ofSeconds(5)));
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper().findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }
}
