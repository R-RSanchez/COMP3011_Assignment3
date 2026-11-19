package comp3011.assignment1.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment1.service.TranscriptionService;

@WebMvcTest(TranscriptionController.class)
class TranscriptionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TranscriptionService transcriptionService;

    @Test
    void shouldReturnTranscriptionForUploadedAudio() throws Exception {

        MockMultipartFile audioFile = new MockMultipartFile(
                "file",
                "recording.webm",
                "audio/webm",
                "fake audio data".getBytes()
        );

        when(transcriptionService.transcribe(any(MultipartFile.class)))
                .thenReturn("Hello world");

        mockMvc.perform(
                multipart("/api/transcribe")
                        .file(audioFile)
        )
        .andExpect(status().isOk())
        .andExpect(content().string("Hello world"));
    }
}