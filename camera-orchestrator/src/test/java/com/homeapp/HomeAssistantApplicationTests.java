package com.homeapp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("dev")
class HomeAssistantApplicationTests {

    @Test
    void contextLoads() {
        // Verifies Spring context initializes with dev profile (H2 database & in-JVM setup)
    }
}
