package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies the real extraction/HTTP/publication chain using a local fake server.
 * No external API requests, credentials, or JavaFX runtime are required.
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
class SubtitleCoordinatorTest {
    @TempDir Path temporary;

    @Test
    void publishesServerSubtitlesInBackgroundAndAvoidsDuplicateRequests() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/subtitles", exchange -> {
            requests.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            byte[] response = """
                    {"text":"Hello world","duration":1.0,
                     "words":[{"start":0.1,"end":0.7,"word":"Hello world"}],
                     "segments":[{"start":0.0,"end":1.0,"text":"Hello world",
                                  "no_speech_prob":0.01,"avg_logprob":-0.3}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        try {
            Path audio = makeWav();
            SubtitleState state = new SubtitleState();
            state.setEnabled(true);
            URI endpoint = URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/api/v1/subtitles");
            try (var coordinator = new SubtitleCoordinator(
                    new AudioChunkExtractor(),
                    new SubtitleHttpClient(endpoint),
                    new SubtitleCache(temporary.resolve("cache")))) {
                coordinator.startIfNeeded(audio.toFile(), state);
                long deadline = System.nanoTime() + 10_000_000_000L;
                while (state.textAt(300_000) == null && System.nanoTime() < deadline) {
                    Thread.sleep(25);
                }
                assertEquals("Hello world", state.textAt(300_000));
                assertNull(state.textAt(3_000_000));

                // The same file is already running or done: no second request.
                coordinator.startIfNeeded(audio.toFile(), state);
                Thread.sleep(100);
                assertEquals(1, requests.get());
            }
        } finally {
            server.stop(0);
        }
    }

    private Path makeWav() throws Exception {
        Path media = temporary.resolve("one-second.wav");
        byte[] pcm = new byte[48_000 * 2];
        AudioFormat format = new AudioFormat(48_000f, 16, 1, true, false);
        try (var input = new AudioInputStream(
                new ByteArrayInputStream(pcm), format, 48_000)) {
            AudioSystem.write(input, AudioFileFormat.Type.WAVE, media.toFile());
        }
        return media;
    }

    @Test
    void recoversAutomaticallyAfterServerOutage() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/subtitles", exchange -> {
            exchange.getRequestBody().readAllBytes();
            boolean recovered = requests.incrementAndGet() > 1;
            byte[] body = """
                    {"segments":[{"start":0,"end":1,"text":"Recovered"}]}
                    """.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(recovered ? 200 : 503, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try (var coordinator = new SubtitleCoordinator(new AudioChunkExtractor(),
                new SubtitleHttpClient(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + "/api/v1/subtitles")),
                new SubtitleCache(temporary.resolve("recovery-cache")))) {
            SubtitleState state = new SubtitleState();
            state.setEnabled(true);
            coordinator.startIfNeeded(makeWav().toFile(), state);
            long deadline = System.nanoTime() + 12_000_000_000L;
            while (!"Recovered".equals(state.textAt(300_000))
                    && System.nanoTime() < deadline) { Thread.sleep(25); }
            assertEquals("Recovered", state.textAt(300_000));
            assertEquals(2, requests.get());
            assertEquals(1, state.snapshot().size());
        } finally { server.stop(0); }
    }

    @Test
    void seekingBeforeInitialSubtitlePositionTranscribesEarlierAudio() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/subtitles", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body = "{\"segments\":[{\"start\":0,\"end\":1,\"text\":\"Speech\"}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        server.start();
        try (var coordinator = new SubtitleCoordinator(new AudioChunkExtractor(),
                new SubtitleHttpClient(URI.create("http://127.0.0.1:"
                        + server.getAddress().getPort() + "/api/v1/subtitles")),
                new SubtitleCache(temporary.resolve("seek-cache")))) {
            var media = makeWav().toFile();
            SubtitleState state = new SubtitleState();
            state.setEnabled(true);
            coordinator.startIfNeeded(media, state, 500_000);
            long deadline = System.nanoTime() + 10_000_000_000L;
            while (state.textAt(800_000) == null && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertEquals("Speech", state.textAt(800_000));
            assertEquals("Speech", state.textAt(100_000));
            coordinator.startIfNeeded(media, state, 0);
            deadline = System.nanoTime() + 10_000_000_000L;
            while (state.textAt(100_000) == null && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            assertEquals("Speech", state.textAt(100_000));
        } finally { server.stop(0); }
    }
}
