package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Persistent cache tests with fake Whisper JSON, no credentials or internet.
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
class SubtitleCacheTest {

    private static final String TRANSCRIPT = """
            {"text":"Hello", "duration":1.0,
             "segments":[{"start":0,"end":1,"text":"Hello"}],
             "words":[{"start":0,"end":1,"word":"Hello"}]}
            """;

    @TempDir
    Path temp;

    @Test
    void identicalAudioWithDifferentNamesSurvivesRestart() throws Exception {
        Path original = temp.resolve("first.wav");
        Path renamed = temp.resolve("renamed.wav");
        Files.write(original, new byte[] {82, 73, 70, 70, 1, 2, 3});
        Files.copy(original, renamed);
        AtomicInteger calls = new AtomicInteger();
        SubtitleCache first = new SubtitleCache(temp.resolve("cache"));
        String text = first.lookupOrTranscribe(original, wav -> {
            calls.incrementAndGet();
            return TRANSCRIPT;
        });
        SubtitleCache restarted = new SubtitleCache(temp.resolve("cache"));
        assertEquals(text, restarted.lookupOrTranscribe(renamed, wav -> {
            calls.incrementAndGet();
            return TRANSCRIPT;
        }));
        assertEquals(1, calls.get(), "The renamed file should not use the network");
    }

    @Test
    void changedAudioCausesNewRequest() throws Exception {
        Path first = temp.resolve("a.wav");
        Path second = temp.resolve("b.wav");
        Files.write(first, new byte[] {1, 2, 3});
        Files.write(second, new byte[] {1, 2, 4});
        AtomicInteger calls = new AtomicInteger();
        SubtitleCache cache = new SubtitleCache(temp.resolve("cache"));
        cache.lookupOrTranscribe(first, wav -> {
            calls.incrementAndGet(); return TRANSCRIPT;
        });
        cache.lookupOrTranscribe(second, wav -> {
            calls.incrementAndGet(); return TRANSCRIPT;
        });
        assertEquals(2, calls.get());
    }

    @Test
    void failedRequestDoesNotPoisonCacheAndCanRetry() throws Exception {
        Path wav = temp.resolve("audio.wav");
        Files.write(wav, new byte[] {1, 5, 9});
        SubtitleCache cache = new SubtitleCache(temp.resolve("cache"));
        assertThrows(IOException.class, () -> cache.lookupOrTranscribe(wav, file -> {
            throw new IOException("HTTP 503");
        }));
        assertEquals(TRANSCRIPT, cache.lookupOrTranscribe(wav, file -> TRANSCRIPT));
    }

    @Test
    void corruptCacheIsReplacedAndInvalidJsonIsRejected() throws Exception {
        Path wav = temp.resolve("audio.wav");
        Files.write(wav, new byte[] {8, 9, 10});
        Path directory = temp.resolve("cache");
        SubtitleCache cache = new SubtitleCache(directory);
        assertThrows(IOException.class,
                () -> cache.lookupOrTranscribe(wav, file -> "not JSON"));
        cache.lookupOrTranscribe(wav, file -> TRANSCRIPT);
        Path cached;
        try (var entries = Files.list(directory)) {
            cached = entries.filter(path -> path.toString().endsWith(".json"))
                    .findFirst().orElseThrow();
        }
        Files.writeString(cached, "broken", StandardCharsets.UTF_8);
        AtomicInteger calls = new AtomicInteger();
        assertEquals(TRANSCRIPT, cache.lookupOrTranscribe(wav, file -> {
            calls.incrementAndGet(); return TRANSCRIPT;
        }));
        assertEquals(1, calls.get());
    }
}
