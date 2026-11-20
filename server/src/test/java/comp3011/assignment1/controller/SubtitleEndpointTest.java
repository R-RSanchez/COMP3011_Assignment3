package comp3011.assignment1.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment1.dto.TimedTranscript;
import comp3011.assignment1.service.SubtitleTranscriptionService;
import comp3011.assignment1.service.SubtitleUsageService;

/**
 * Assignment 3 endpoint tests without external API calls.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
@WebMvcTest({
        SubtitleController.class,
        SubtitleUsageController.class
})
class SubtitleEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SubtitleTranscriptionService transcriptionService;

    @MockitoBean
    private SubtitleUsageService usageService;

    private MockMultipartFile sampleAudio() {
        return new MockMultipartFile(
                "file",
                "speech.wav",
                "audio/wav",
                new byte[] {1, 2, 3});
    }

    @Test
    void returnsTimedSubtitleJson() throws Exception {

        when(transcriptionService.isConfigured()).thenReturn(true);

        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenReturn(new TimedTranscript(
                        "Hello world",
                        2.5,
                        List.of(new TimedTranscript.Word(
                                0.0, 0.5, "Hello")),
                        List.of(new TimedTranscript.Segment(
                                0.0, 2.5, "Hello world",
                                0.01, -0.2))));

        mockMvc.perform(
                multipart("/api/v1/subtitles").file(sampleAudio()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.text").value("Hello world"))
                .andExpect(jsonPath("$.duration").value(2.5))
                .andExpect(jsonPath("$.words[0].word").value("Hello"))
                .andExpect(jsonPath("$.segments[0].start").value(0.0));
    }

    @Test
    void returnsServiceUnavailableWithoutApiKey() throws Exception {

        mockMvc.perform(
                multipart("/api/v1/subtitles").file(sampleAudio()))
                .andExpect(status().isServiceUnavailable());
    }

    @Test
    void reportsUsageInSeconds() throws Exception {

        when(usageService.getSeconds()).thenReturn(12.5);

        mockMvc.perform(get("/api/v1/global/usage"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.usage").value(12.5));
    }
}
