package com.antar.cameraorchestrator.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.antar.cameraorchestrator.model.dto.EntityStateDto;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableCaching
public class CacheConfig {

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager cacheManager = new CaffeineCacheManager("entityStates", "devices", "areas");
        cacheManager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(10_000)
                .expireAfterAccess(24, TimeUnit.HOURS)
                .recordStats());
        return cacheManager;
    }

    @Bean
    public Cache<String, EntityStateDto> stateCache() {
        return Caffeine.newBuilder()
                .maximumSize(10_000)
                .recordStats()
                .build();
    }
}
