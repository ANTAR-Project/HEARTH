package com.antar.cameraorchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class StateControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testGetStates() throws Exception {
        mockMvc.perform(get("/api/states"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON));
    }

    @Test
    void testCallServiceEndpoint() throws Exception {
        String requestJson = """
                {
                    "entity_id": "light.living_room"
                }
                """;

        mockMvc.perform(post("/api/services/light/turn_on")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entity_id").value("light.living_room"))
                .andExpect(jsonPath("$.domain").value("light"))
                .andExpect(jsonPath("$.service").value("turn_on"));
    }
}
