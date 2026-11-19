package comp3011.assignment1.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import comp3011.assignment1.service.ServerLifecycleService;

@WebMvcTest(AdminController.class)
class AdminControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ServerLifecycleService serverLifecycleService;

    @MockitoBean
    private ConfigurableApplicationContext applicationContext;

    @Test
    void shouldReturnServerUptime() throws Exception {

        Instant start =
                Instant.parse("2026-09-07T00:00:00Z");

        when(serverLifecycleService.getUtcServerStart())
                .thenReturn(start);

        mockMvc.perform(
                get("/api/v1/admin/uptime")
        )
        .andExpect(status().isOk())
        .andExpect(
                jsonPath("$.utcServerStart")
                        .value("2026-09-07T00:00:00Z")
        )
        .andExpect(jsonPath("$.utcNow").exists())
        .andExpect(
                jsonPath("$.serverUptimeSeconds").isNumber()
        );
    }

    @Test
    void shouldAcceptGracefulShutdown() throws Exception {

        when(serverLifecycleService.beginShutdown())
                .thenReturn(true);

        mockMvc.perform(
                post("/api/v1/admin/shutdown")
        )
        .andExpect(status().isAccepted())
        .andExpect(
                jsonPath("$.message")
                        .value("Graceful shutdown requested.")
        );
    }

    @Test
    void shouldReturnConflictWhenShutdownAlreadyInProgress()
            throws Exception {

        when(serverLifecycleService.beginShutdown())
                .thenReturn(false);

        mockMvc.perform(
                post("/api/v1/admin/shutdown")
        )
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.error").value("Conflict"))
        .andExpect(
                jsonPath("$.message")
                        .value(
                            "Graceful shutdown is already in progress."
                        )
        )
        .andExpect(
                jsonPath("$.path")
                        .value("/api/v1/admin/shutdown")
        );
    }
}