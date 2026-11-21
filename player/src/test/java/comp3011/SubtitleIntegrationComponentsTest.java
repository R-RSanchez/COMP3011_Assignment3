package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Isolated A3 integration tests: no API key or public internet required. */
class SubtitleIntegrationComponentsTest {
    @TempDir Path tempDir;

    @Test
    void timestampsAreShiftedByChunkStartAndNaturalWordPauseSplitsCues() {
        String json = """
                {"words":[
                 {"start":0.0,"end":0.4,"word":"Hello"},
                 {"start":0.5,"end":0.8,"word":"world"},
                 {"start":2.1,"end":2.4,"word":"Again"}],
                 "segments":[{"start":0.0,"end":3.0,
                  "text":"Hello world Again",
                  "no_speech_prob":0.01,"avg_logprob":-0.2}]}
                """;
        List<SubtitleCue> cues = SubtitleCueMapper.fromJson(json, 20_000_000);
        assertEquals(2, cues.size());
        assertEquals("Hello world", cues.get(0).text());
        assertEquals(20_000_000, cues.get(0).startUs());
        assertEquals("Again", cues.get(1).text());
        assertEquals(22_100_000, cues.get(1).startUs());
    }

    @Test
    void noisySegmentDoesNotGenerateSubtitle() {
        String json = """
                {"segments":[{"start":0,"end":2,"text":"Noise",
                  "no_speech_prob":0.98,"avg_logprob":-1.1}]}
                """;
        assertTrue(SubtitleCueMapper.fromJson(json, 0).isEmpty());
    }

    @Test
    void confidenceFilterPreservesSpeechUnlessBothStrictThresholdsAreExceeded() {
        double[][] retained = {{0.98, -0.2}, {0.1, -1.5}, {0.6, -1.5}, {0.9, -1.0}};
        for (double[] scores : retained) {
            String json = "{\"segments\":[{\"start\":1,\"end\":3,\"text\":\"Real dialogue\","
                    + "\"no_speech_prob\":" + scores[0] + ",\"avg_logprob\":" + scores[1] + "}]}";
            var cues = SubtitleCueMapper.fromJson(json, 24_000_000);
            assertEquals(1, cues.size(), json);
            assertEquals(25_000_000, cues.getFirst().startUs());
            assertEquals("Real dialogue", cues.getFirst().text());
        }
        assertTrue(SubtitleCueMapper.fromJson("""
                {"segments":[{"start":1,"end":3,"text":"Noise",
                "no_speech_prob":0.61,"avg_logprob":-1.01}]}
                """, 0).isEmpty());
    }

    @Test
    void multipartClientSendsAudioAndReadsJsonWithoutOpenAi() throws Exception {
        AtomicReference<String> posted = new AtomicReference<>();
        HttpServer fakeServer = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0), 0);
        fakeServer.createContext("/api/v1/subtitles", exchange -> {
            posted.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.ISO_8859_1));
            byte[] response = "{\"text\":\"Hello\",\"segments\":[]}".getBytes(
                    StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(response);
            }
        });
        fakeServer.start();
        try {
            Path audio = tempDir.resolve("test.wav");
            Files.write(audio, new byte[] {82, 73, 70, 70, 1, 2, 3, 4});
            URI url = URI.create("http://127.0.0.1:"
                    + fakeServer.getAddress().getPort() + "/api/v1/subtitles");
            String result = new SubtitleHttpClient(url).transcribe(audio);
            assertTrue(result.contains("Hello"));
            assertNotNull(posted.get());
            assertTrue(posted.get().contains("name=\"file\""));
            assertTrue(posted.get().contains("RIFF"));
        } finally {
            fakeServer.stop(0);
        }
    }

    @Test
    void extractsWavFromAudioWithoutDecodingVideoImages() throws Exception {
        Path media = tempDir.resolve("original.wav");
        byte[] pcm = new byte[48_000 * 2];  // One second of 48 kHz mono PCM silence.
        AudioFormat fmt = new AudioFormat(48_000f, 16, 1, true, false);
        try (var audio = new AudioInputStream(
                new ByteArrayInputStream(pcm), fmt, 48_000)) {
            AudioSystem.write(audio, AudioFileFormat.Type.WAVE, media.toFile());
        }
        List<AudioChunkExtractor.Chunk> chunks = new ArrayList<>();
        try {
            new AudioChunkExtractor().extract(media.toFile(), chunks::add);
            assertEquals(1, chunks.size());
            assertTrue(Files.size(chunks.get(0).file()) > 44);
            try (var result = AudioSystem.getAudioInputStream(
                    chunks.get(0).file().toFile())) {
                assertEquals(16_000f, result.getFormat().getSampleRate());
                assertEquals(1, result.getFormat().getChannels());
            }
        } finally {
            for (var chunk : chunks) {
                Files.deleteIfExists(chunk.file());
            }
        }
    }
}
