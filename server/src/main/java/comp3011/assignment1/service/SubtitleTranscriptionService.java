package comp3011.assignment1.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

import comp3011.assignment1.dto.TimedTranscript;

/**
 * Sends original audio to the Whisper-1 transcription API.
 *
 * Credentials remain exclusively on the server and are read
 * from the environment, never from client requests.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
@Service
public class SubtitleTranscriptionService {

    private final String apiKey;
    private final RestClient client;
    private final SubtitleUsageService usageService;

    public SubtitleTranscriptionService(
            @Value("${OPENAI_API_KEY:}") String apiKey,
            RestClient.Builder builder,
            SubtitleUsageService usageService) {

        this.apiKey = apiKey;
        this.usageService = usageService;

        this.client = builder
                .baseUrl("https://api.openai.com")
                .build();
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    public TimedTranscript transcribe(MultipartFile audioFile) {

        MultiValueMap<String, Object> body =
                new LinkedMultiValueMap<>();

        body.add("file", audioFile.getResource());
        body.add("model", "whisper-1");
        body.add("response_format", "verbose_json");

        // Both granularities are explicitly required by A3.
        body.add("timestamp_granularities[]", "word");
        body.add("timestamp_granularities[]", "segment");

        TimedTranscript result = client.post()
                .uri("/v1/audio/transcriptions")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey)
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(TimedTranscript.class);

        if (result == null) {
            throw new IllegalStateException(
                    "Whisper returned an empty response");
        }

        // Count only successful transcription requests.
        usageService.addSeconds(result.duration());

        return result;
    }
}
