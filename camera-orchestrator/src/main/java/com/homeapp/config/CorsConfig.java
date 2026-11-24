package com.homeapp.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Allows browser-based clients on other subnets (e.g. VLAN 40) to call the REST API
 * (PTZ, service calls, etc). Without this, GET requests like <img src="/api/camera/.../stream">
 * work fine (images aren't subject to CORS), but any POST/PUT via fetch() - such as PTZ
 * control - is silently blocked by the browser.
 *
 * NOTE: allowed-origins defaults to "*" to match the current no-auth state of this app
 * (see WebSocketConfig). Once JWT auth is added, tighten this to your actual client
 * origin(s) instead of "*".
 */
@Configuration
public class CorsConfig implements WebMvcConfigurer {

    @Value("${app.cors.allowed-origins:*}")
    private String[] allowedOrigins;

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(allowedOrigins)
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}