package comp3011.assignment1.controller;

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import comp3011.assignment1.service.SubtitleUsageService;

/**
 * Reports cumulative transcription duration in seconds.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
@RestController
public class SubtitleUsageController {

    private final SubtitleUsageService usageService;

    public SubtitleUsageController(SubtitleUsageService usageService) {
        this.usageService = usageService;
    }

    @GetMapping("/api/v1/global/usage")
    public Map<String, Double> getUsage() {
        return Map.of("usage", usageService.getSeconds());
    }
}
