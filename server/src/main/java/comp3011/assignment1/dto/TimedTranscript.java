package comp3011.assignment1.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Timed transcription returned by Whisper-1.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record TimedTranscript(
        String text,
        double duration,
        List<Word> words,
        List<Segment> segments) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Word(
            double start,
            double end,
            String word) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Segment(
            double start,
            double end,
            String text,
            @JsonProperty("no_speech_prob") double noSpeechProb,
            @JsonProperty("avg_logprob") double avgLogProb) {
    }
}
