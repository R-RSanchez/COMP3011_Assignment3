package comp3011.assignment1.controller;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment1.service.SubtitleTranscriptionService;

/**
 * HTTP entry point for Assignment 3 subtitle transcription.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
@RestController
public class SubtitleController {

    private final SubtitleTranscriptionService service;

    public SubtitleController(SubtitleTranscriptionService service) {
        this.service = service;
    }

    @PostMapping(
            path = "/api/v1/subtitles",
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> subtitles(
            @RequestParam("file") MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Audio file is empty"));
        }

        if (!service.isConfigured()) {
            return ResponseEntity.status(
                    HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "error",
                            "OpenAI API key is not configured"));
        }

        return ResponseEntity.ok(service.transcribe(file));
    }
}
