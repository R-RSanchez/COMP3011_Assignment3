package comp3011;

import static org.junit.jupiter.api.Assertions.*;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.Random;
import javax.sound.sampled.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SubtitleExcerptCacheTest {
    @TempDir Path temp;

    private byte[] signal(int seconds, long seed) {
        Random random = new Random(seed);
        byte[] bytes = new byte[seconds * 32000];
        for (int i = 0; i < bytes.length; i += 32) {
            short sample = (short) (random.nextInt(16000) - 8000);
            for (int j = 0; j < 32; j += 2) {
                bytes[i + j] = (byte) sample;
                bytes[i + j + 1] = (byte) (sample >> 8);
            }
        }
        return bytes;
    }

    private Path wav(String name, byte[] bytes, int from, int length) throws Exception {
        Path path = temp.resolve(name);
        var format = new AudioFormat(16000, 16, 1, true, false);
        try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes, from, length),
                format, length / 2)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, path.toFile());
        }
        return path;
    }

    @Test
    void shiftedClipCrossesChunkBoundaryAndRebasesTimestampsAfterRestart() throws Exception {
        byte[] audio = signal(16, 42);
        Path cacheDir = temp.resolve("cache");
        var cache = new SubtitleCache(cacheDir);
        cache.lookupOrTranscribe(wav("first.wav", audio, 0, 256000), p ->
                "{\"segments\":[{\"start\":6,\"end\":7,\"text\":\"First\"}]}");
        cache.lookupOrTranscribe(wav("second.wav", audio, 256000, 256000), p ->
                "{\"segments\":[{\"start\":1,\"end\":2,\"text\":\"Second\"}]}");
        Path clip = wav("clip.wav", audio, 169600, 256000); // 5.3s, crosses 8s boundary.
        String json = new SubtitleCache(cacheDir).lookupOrTranscribe(clip, p -> {
            fail("A fully cached excerpt must not call the network"); return null;
        });
        var cues = SubtitleCueMapper.fromJson(json, 0);
        assertEquals(2, cues.size());
        assertEquals(700000, cues.get(0).startUs());
        assertEquals(3700000, cues.get(1).startUs());
    }

    @Test
    void unrelatedAudioAndSilenceDoNotInventCacheHits() throws Exception {
        var cache = new SubtitleCache(temp.resolve("cache"));
        cache.lookupOrTranscribe(wav("known.wav", signal(8, 42), 0, 256000), p ->
                "{\"segments\":[]}");
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        cache.lookupOrTranscribe(wav("other.wav", signal(8, 43), 0, 256000), p -> {
            calls.incrementAndGet(); return "{\"segments\":[]}";
        });
        cache.lookupOrTranscribe(wav("silence.wav", new byte[256000], 0, 256000), p -> {
            calls.incrementAndGet(); return "{\"segments\":[]}";
        });
        assertEquals(2, calls.get());
    }

    @Test
    void extractionPreservesPcmSamplesAcrossChunkBoundaries() throws Exception {
        byte[] original = signal(17, 123);
        Path media = wav("seventeen.wav", original, 0, original.length);
        var recovered = new java.io.ByteArrayOutputStream();
        new AudioChunkExtractor().extract(media.toFile(), chunk -> {
            try (var input = AudioSystem.getAudioInputStream(chunk.file().toFile())) {
                byte[] pcm = input.readAllBytes();
                assertEquals(pcm.length / 2L * 1_000_000 / 16000, chunk.durationUs());
                assertEquals(recovered.size() / 2L * 1_000_000 / 16000, chunk.startUs());
                recovered.write(pcm);
            } finally { java.nio.file.Files.deleteIfExists(chunk.file()); }
        });
        assertArrayEquals(original, recovered.toByteArray(), "Extraction must neither pad nor corrupt PCM");
    }

    @Test
    @org.junit.jupiter.api.condition.EnabledIfSystemProperty(named="comp3011.test.media", matches=".+")
    void realMediaExcerptReusesEveryChunk() throws Exception {
        var cache = new SubtitleCache(temp.resolve("real-cache"));
        var extractor = new AudioChunkExtractor();
        extractor.extract(Path.of(System.getProperty("comp3011.test.media")).toFile(), chunk -> {
            try { cache.lookupOrTranscribe(chunk.file(), p -> "{\"segments\":[]}"); }
            finally { java.nio.file.Files.deleteIfExists(chunk.file()); }
        });
        var misses = new java.util.concurrent.atomic.AtomicInteger();
        extractor.extract(Path.of(System.getProperty("comp3011.test.clip")).toFile(), chunk -> {
            try { cache.lookupOrTranscribe(chunk.file(), p -> {
                misses.incrementAndGet(); return "{\"segments\":[]}";
            }); } finally { java.nio.file.Files.deleteIfExists(chunk.file()); }
        });
        assertEquals(0, misses.get(), "Real excerpt must reuse all already-transcribed audio");
    }
}
