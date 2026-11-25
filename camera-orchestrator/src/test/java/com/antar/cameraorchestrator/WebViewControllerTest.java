package com.antar.cameraorchestrator;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("dev")
class WebViewControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void testDashboardView() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(view().name("dashboard"))
                .andExpect(model().attributeExists("areas"))
                .andExpect(model().attributeExists("entitiesByArea"))
                .andExpect(model().attributeExists("devices"))
                .andExpect(model().attributeExists("allStates"))
                .andExpect(model().attributeExists("stats"));
    }

    @Test
    void testDevicesView() throws Exception {
        mockMvc.perform(get("/devices"))
                .andExpect(status().isOk())
                .andExpect(view().name("devices"))
                .andExpect(model().attributeExists("devices"))
                .andExpect(model().attributeExists("areaNames"))
                .andExpect(model().attributeExists("mqttCount"))
                .andExpect(model().attributeExists("bleCount"))
                .andExpect(model().attributeExists("zigbeeCount"))
                .andExpect(model().attributeExists("cameraCount"));
    }

    @Test
    void testAdaptersView() throws Exception {
        mockMvc.perform(get("/adapters"))
                .andExpect(status().isOk())
                .andExpect(view().name("adapters"))
                .andExpect(model().attributeExists("mqtt"))
                .andExpect(model().attributeExists("ble"))
                .andExpect(model().attributeExists("zigbee"))
                .andExpect(model().attributeExists("camera"));
    }
}
