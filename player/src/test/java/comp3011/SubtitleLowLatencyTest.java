package comp3011;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Checks early chunk production and real parallel uploads without OpenAI.
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
class SubtitleLowLatencyTest {

    @TempDir Path temporary;

    @Test
    void chunksAreShortEnoughForEarlyCaptions() {
        assertTrue(AudioChunkExtractor.CHUNK_DURATION_US <= 8_000_000L);
        assertTrue(AudioChunkExtractor.CHUNK_DURATION_US >= 5_000_000L);
    }

    @Test
    void parallelRequestsPublishBothEarlyAndLaterCues() throws Exception {
        var httpWorkers = Executors.newCachedThreadPool();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(httpWorkers);
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger active = new AtomicInteger();
        AtomicInteger maximumActive = new AtomicInteger();
        CountDownLatch twoActive = new CountDownLatch(2);
        server.createContext("/api/v1/subtitles", exchange -> {
            int current = active.incrementAndGet();
            maximumActive.accumulateAndGet(current, Math::max);
            try {
                exchange.getRequestBody().readAllBytes();
                requests.incrementAndGet();
                twoActive.countDown();
                // First request waits for the second; sequential HTTP cannot pass.
                twoActive.await(7, TimeUnit.SECONDS);
                byte[] response = """
                        {"text":"Hello", "segments":[
                         {"start":0.0,"end":1.0,"text":"Hello",
                          "no_speech_prob":0.01,"avg_logprob":-0.2}]}
                        """.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (var output = exchange.getResponseBody()) {
                    output.write(response);
                }
            } catch (InterruptedException cancelled) {
                Thread.currentThread().interrupt();
            } finally {
                active.decrementAndGet();
                exchange.close();
            }
        });
        server.start();
        try {
            Path media = temporary.resolve("seventeen-seconds.wav");
            byte[] pcm = new byte[48_000 * 17 * 2];
            AudioFormat format = new AudioFormat(48_000f, 16, 1, true, false);
            try (var stream = new AudioInputStream(
                    new ByteArrayInputStream(pcm), format, 48_000 * 17L)) {
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, media.toFile());
            }

            SubtitleState state = new SubtitleState();
            state.setEnabled(true);
            URI endpoint = URI.create("http://127.0.0.1:"
                    + server.getAddress().getPort() + "/api/v1/subtitles");
            try (var coordinator = new SubtitleCoordinator(
                    new AudioChunkExtractor(), new SubtitleHttpClient(endpoint),
                    new SubtitleCache(temporary.resolve("cache")))) {
                coordinator.startIfNeeded(media.toFile(), state);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(18);
                while ((requests.get() < 3 || state.textAt(16_500_000) == null)
                        && System.nanoTime() < deadline) {
                    Thread.sleep(40);
                }
                assertTrue(maximumActive.get() >= 2,
                        "Two independent subtitle HTTP requests must overlap");
                assertEquals(3, requests.get(), "17 seconds should be split into 3 WAVs");
                assertEquals("Hello", state.textAt(500_000));
                assertEquals("Hello", state.textAt(16_500_000));
            }
        } finally {
            server.stop(0);
            httpWorkers.shutdownNow();
        }
    }
}
