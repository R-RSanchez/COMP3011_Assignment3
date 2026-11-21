package comp3011;

import java.io.*;
import java.nio.ShortBuffer;
import java.nio.file.*;
import javax.sound.sampled.*;
import org.bytedeco.javacv.*;

/**
 * Decodes original audio to mono PCM16 at 16 kHz on an independent grabber.
 * Chunks contain exactly the counted samples, avoiding encoder padding at seams.
 * Author: Raúl Ruiz Sánchez. Developed with assistance from ChatGPT.
 */
public final class AudioChunkExtractor {
    public static final long CHUNK_DURATION_US = 8_000_000L;
    private static final int RATE = 16_000;
    private static final int CHUNK_BYTES = RATE * 8 * 2;
    public record Chunk(Path file, long startUs, long durationUs) { }
    @FunctionalInterface public interface ChunkConsumer {
        void accept(Chunk chunk) throws Exception;
    }
    public void extract(File media, ChunkConsumer consumer) throws Exception {
        extract(media, 0, consumer);
    }
    public void extract(File media, long requestedUs, ChunkConsumer consumer) throws Exception {
        requestedUs = Math.max(0, requestedUs);
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(media)) {
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.setSampleRate(RATE);
            grabber.setAudioChannels(1);
            grabber.start();
            if (!grabber.hasAudio()) return;
            if (requestedUs > 0) grabber.setTimestamp(requestedUs);
            ByteArrayOutputStream pcm = new ByteArrayOutputStream(CHUNK_BYTES);
            long baseUs = -1;
            long emittedSamples = 0;
            Frame frame;
            while ((frame = grabber.grabSamples()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (frame.samples == null || frame.samples.length == 0) continue;
                ShortBuffer samples = ((ShortBuffer) frame.samples[0]).duplicate();
                long timestamp = Math.max(0, frame.timestamp);
                if (baseUs < 0) {
                    long skip = Math.max(0, (requestedUs - timestamp) * RATE / 1_000_000);
                    if (skip >= samples.remaining()) continue;
                    samples.position(samples.position() + (int) skip);
                    baseUs = timestamp + skip * 1_000_000 / RATE;
                }
                while (samples.hasRemaining()) {
                    short sample = samples.get();
                    pcm.write(sample & 255);
                    pcm.write((sample >>> 8) & 255);
                    if (pcm.size() == CHUNK_BYTES) {
                        emit(pcm, baseUs + emittedSamples * 1_000_000 / RATE, consumer);
                        emittedSamples += CHUNK_BYTES / 2;
                    }
                }
            }
            if (pcm.size() > 0) emit(pcm, baseUs + emittedSamples * 1_000_000 / RATE, consumer);
        }
    }
    private static void emit(ByteArrayOutputStream pcm, long startUs, ChunkConsumer consumer) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        byte[] bytes = pcm.toByteArray();
        pcm.reset();
        Path file = Files.createTempFile("comp3011-a3-audio-", ".wav");
        boolean accepted = false;
        try {
            try (var stream = new AudioInputStream(new ByteArrayInputStream(bytes),
                    new AudioFormat(RATE, 16, 1, true, false), bytes.length / 2)) {
                AudioSystem.write(stream, AudioFileFormat.Type.WAVE, file.toFile());
            }
            consumer.accept(new Chunk(file, startUs, bytes.length / 2L * 1_000_000 / RATE));
            accepted = true;
        } finally { if (!accepted) Files.deleteIfExists(file); }
    }
}
