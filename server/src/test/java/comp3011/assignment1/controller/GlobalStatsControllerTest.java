package comp3011.assignment1.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import comp3011.assignment1.dto.GlobalStatsResponse;
import comp3011.assignment1.service.GlobalStatsService;

@WebMvcTest(GlobalStatsController.class)
class GlobalStatsControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GlobalStatsService globalStatsService;

    @Test
    void shouldReturnGlobalStats() throws Exception {

        when(globalStatsService.getStats())
                .thenReturn(new GlobalStatsResponse(123, 45));

        mockMvc.perform(get("/api/v1/global/stats"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.inputTokens").value(123))
                .andExpect(jsonPath("$.outputTokens").value(45));
    }
}