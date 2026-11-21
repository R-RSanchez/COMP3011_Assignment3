package comp3011;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks that enabling subtitles mid-video prioritizes the current audio. */
class SubtitlePositionTest {
    @TempDir Path temp;

    @Test
    void extractorCanStartNearCurrentPlaybackPosition() throws Exception {
        int sampleRate = 16_000;
        int durationSeconds = 5;
        byte[] pcm = new byte[sampleRate * durationSeconds * 2];
        AudioFormat format = new AudioFormat(sampleRate, 16, 1, true, false);
        Path media = temp.resolve("five-seconds.wav");
        try (AudioInputStream stream = new AudioInputStream(
                new ByteArrayInputStream(pcm), format,
                (long) sampleRate * durationSeconds)) {
            AudioSystem.write(stream, AudioFileFormat.Type.WAVE, media.toFile());
        }

        List<Long> starts = new ArrayList<>();
        new AudioChunkExtractor().extract(media.toFile(), 2_000_000L, chunk -> {
            starts.add(chunk.startUs());
            Files.deleteIfExists(chunk.file());
        });
        assertFalse(starts.isEmpty(), "A chunk should be produced after seeking");
        assertTrue(starts.get(0) >= 2_000_000L,
                "The extractor should not transcribe speech before the seek");
    }
}
