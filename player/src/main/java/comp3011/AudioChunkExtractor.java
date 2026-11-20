package comp3011;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.FFmpegFrameRecorder;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.FrameGrabber;

import static org.bytedeco.ffmpeg.global.avcodec.AV_CODEC_ID_PCM_S16LE;

/**
 * Extracts only original audio from the media into short, 16 kHz mono WAVs.
 * Uses a separate grabber: never touches the video playback grabber.
 * The caller owns (and must delete) every delivered temporary WAV.
 *
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
public final class AudioChunkExtractor {
    public static final long CHUNK_DURATION_US = 20_000_000L;

    public record Chunk(Path file, long startUs, long durationUs) {
    }

    @FunctionalInterface
    public interface ChunkConsumer {
        void accept(Chunk chunk) throws Exception;
    }

    public void extract(File media, ChunkConsumer consumer) throws Exception {
        try (FFmpegFrameGrabber grabber = new FFmpegFrameGrabber(media)) {
            grabber.setSampleMode(FrameGrabber.SampleMode.SHORT);
            grabber.start();

            if (!grabber.hasAudio()) {
                return;
            }

            FFmpegFrameRecorder recorder = null;
            Path temporary = null;
            long startUs = -1;
            long lastUs = -1;

            try {
                Frame samples;
                while ((samples = grabber.grabSamples()) != null) {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Audio extraction cancelled");
                    }
                    if (samples.samples == null || samples.samples.length == 0) {
                        continue;
                    }

                    long timeUs = Math.max(0, grabber.getTimestamp());
                    if (recorder != null && timeUs - startUs >= CHUNK_DURATION_US) {
                        recorder.close();
                        recorder = null;
                        Path completed = temporary;
                        temporary = null;
                        // On success the consumer owns this temporary file.
                        try {
                            consumer.accept(new Chunk(completed, startUs, timeUs - startUs));
                        } catch (Exception failure) {
                            Files.deleteIfExists(completed);
                            throw failure;
                        }
                    }

                    if (recorder == null) {
                        temporary = Files.createTempFile("comp3011-a3-audio-", ".wav");
                        recorder = new FFmpegFrameRecorder(temporary.toFile(), 0, 0, 1);
                        recorder.setFormat("wav");
                        recorder.setAudioCodec(AV_CODEC_ID_PCM_S16LE);
                        recorder.setSampleRate(16_000);
                        recorder.setAudioChannels(1);
                        recorder.start();
                        startUs = timeUs;
                    }

                    // Recorder resamples/downmixes the original decoded audio.
                    recorder.recordSamples(
                            grabber.getSampleRate(),
                            grabber.getAudioChannels(),
                            samples.samples);
                    lastUs = timeUs;
                }

                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("Audio extraction cancelled");
                }

                if (recorder != null) {
                    recorder.close();
                    recorder = null;
                    Path completed = temporary;
                    temporary = null;
                    try {
                        consumer.accept(new Chunk(
                                completed,
                                startUs,
                                Math.max(1, lastUs - startUs + 35_000)));
                    } catch (Exception failure) {
                        Files.deleteIfExists(completed);
                        throw failure;
                    }
                }
            } finally {
                if (recorder != null) {
                    recorder.close();
                }
                if (temporary != null) {
                    Files.deleteIfExists(temporary);
                }
            }
        }
    }
}
