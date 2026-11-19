package comp3011.assignment1.service;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;

@Service
public class TranscriptionService {

    private static final Logger logger =
            LoggerFactory.getLogger(TranscriptionService.class);

    private final String apiKey;
    private final RestClient restClient;
    private final GlobalStatsService globalStatsService;

    public TranscriptionService(
            @Value("${OPENAI_API_KEY:}") String apiKey,
            RestClient.Builder restClientBuilder,
            GlobalStatsService globalStatsService) {

        this.apiKey = apiKey;
        this.globalStatsService = globalStatsService;

        this.restClient = restClientBuilder
                .baseUrl("https://api.openai.com")
                .build();
    }

    public String transcribe(MultipartFile file) {

        if (apiKey.isBlank()) {

            logger.warn(
                    "STT request cannot run because OPENAI_API_KEY is not configured"
            );

            return "OPENAI_API_KEY is not configured";
        }

        logger.info(
                "Starting STT request: audioSize={} bytes, contentType={}",
                file.getSize(),
                file.getContentType()
        );

        MultiValueMap<String, Object> body =
                new LinkedMultiValueMap<>();

        body.add("model", "gpt-4o-mini-transcribe");
        body.add("file", file.getResource());

        TranscriptionResponse response = restClient.post()
                .uri("/v1/audio/transcriptions")
                .header(
                        HttpHeaders.AUTHORIZATION,
                        "Bearer " + apiKey
                )
                .contentType(MediaType.MULTIPART_FORM_DATA)
                .body(body)
                .retrieve()
                .body(TranscriptionResponse.class);

        if (response == null) {

            logger.warn(
                    "STT request completed without a response body"
            );

            return "No transcription received";
        }

        if (response.usage() != null) {

            globalStatsService.addUsage(
                    response.usage().inputTokens(),
                    response.usage().outputTokens()
            );

            logger.info(
                    "STT request completed successfully: inputTokens={}, outputTokens={}",
                    response.usage().inputTokens(),
                    response.usage().outputTokens()
            );

        } else {

            logger.info(
                    "STT request completed successfully without usage metadata"
            );
        }

        return response.text();
    }

    private record TranscriptionResponse(
            String text,
            Usage usage) {
    }

    private record Usage(
            @JsonProperty("input_tokens") long inputTokens,
            @JsonProperty("output_tokens") long outputTokens) {
    }
}