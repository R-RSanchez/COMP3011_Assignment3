package comp3011;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Persistent cache of Whisper verbose_json responses keyed by decoded WAV
 * CONTENT, not by media filename, path or modification time.
 *
 * Each completed chunk is cached independently. Renamed media with identical
 * extracted audio can reuse it; successfully transcribed chunks survive an
 * interrupted request or player restart. Invalid/failed responses are never
 * cached. Cached times remain relative to the chunk: the cue mapper applies
 * the current media offset when reading them.
 *
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
public final class SubtitleCache {

    @FunctionalInterface
    public interface TranscriptSource {
        String load(Path audio) throws IOException, InterruptedException;
    }

    private final Path directory;

    public SubtitleCache() {
        this(Path.of(System.getProperty("user.home"),
                ".comp3011", "subtitle-cache", "v1"));
    }

    // Package-private: tests use a temporary directory, never the user's cache.
    SubtitleCache(Path directory) {
        this.directory = directory;
    }

    public String lookupOrTranscribe(Path wav, TranscriptSource source)
            throws IOException, InterruptedException {

        String digest = sha256(wav);
        Path cached = directory.resolve(digest + ".json");
        SubtitleExcerptCache excerpts = new SubtitleExcerptCache(directory);

        try {
            if (Files.isRegularFile(cached)) {
                String value = Files.readString(cached, StandardCharsets.UTF_8);
                if (isValidTranscript(value)) {
                    try { excerpts.remember(wav, digest); } catch (IOException ignored) { }
                    return value;
                }
                Files.deleteIfExists(cached);
            }
        } catch (IOException ignored) {
            // Cache is best-effort. An unreadable cache must not break playback.
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Subtitle request cancelled");
        }

        // The network call is made by SubtitleCoordinator's dedicated worker.
        String result = null;
        try { result = excerpts.find(wav); } catch (IOException | RuntimeException ignored) { }
        if (result == null) result = source.load(wav);
        if (!isValidTranscript(result)) {
            throw new IOException("Subtitle server returned invalid timed JSON");
        }

        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("Subtitle request cancelled");
        }

        Path temporary = null;
        try {
            Files.createDirectories(directory);
            temporary = Files.createTempFile(directory, digest + "-", ".tmp");
            Files.writeString(temporary, result, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, cached,
                        StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temporary, cached, StandardCopyOption.REPLACE_EXISTING);
            }
            excerpts.remember(wav, digest);
        } catch (IOException ignored) {
            // Successful transcript must still be used when disk caching fails.
        } finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // Best-effort cleanup.
                }
            }
        }
        return result;
    }

    private static String sha256(Path file) throws IOException, InterruptedException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] block = new byte[65536];
            int count;
            while ((count = input.read(block)) != -1) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Subtitle request cancelled");
                }
                digest.update(block, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static boolean isValidTranscript(String json) {
        if (json == null || json.isBlank()) {
            return false;
        }
        try {
            JsonElement parsed = JsonParser.parseString(json);
            if (!parsed.isJsonObject()) {
                return false;
            }
            JsonObject data = parsed.getAsJsonObject();
            // Whisper with word/segment granularities supplies these arrays.
            return (data.has("segments") && data.get("segments").isJsonArray())
                    || (data.has("words") && data.get("words").isJsonArray());
        } catch (RuntimeException invalidJson) {
            return false;
        }
    }
}
