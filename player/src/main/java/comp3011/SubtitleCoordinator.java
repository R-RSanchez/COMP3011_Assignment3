package comp3011;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs audio extraction, multipart HTTP uploads and subtitle mapping away from
 * JavaFX and frame-processing workers. Only one audio job runs at once.
 *
 * A generation check discards results from cancelled/previous media jobs.
 * Video timestamps determine subtitle selection; network arrival time does not.
 *
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
public final class SubtitleCoordinator implements AutoCloseable {

    private final AudioChunkExtractor extractor;
    private final SubtitleHttpClient client;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
            runnable -> Thread.ofPlatform()
                    .name("comp3011-subtitle-io")
                    .daemon(true)
                    .unstarted(runnable));

    private Future<?> activeTask;
    private File activeMedia;
    private boolean completed;
    private long generation;

    public SubtitleCoordinator() {
        this(new AudioChunkExtractor(), new SubtitleHttpClient());
    }

    // Package-private dependency injection allows isolated tests without OpenAI.
    SubtitleCoordinator(AudioChunkExtractor extractor, SubtitleHttpClient client) {
        this.extractor = extractor;
        this.client = client;
    }

    /**
     * Called only from the JavaFX application thread. Duplicate enable actions
     * do not resubmit the same file while it is running or already completed.
     */
    public synchronized void startIfNeeded(File media, SubtitleState state) {
        if (media == null || executor.isShutdown()) {
            return;
        }
        if (media.equals(activeMedia) &&
                (completed || (activeTask != null && !activeTask.isDone()))) {
            return;
        }

        cancelCurrent();
        activeMedia = media;
        state.setCues(List.of()); // Never show demo cues or prior-media results.
        final long requestGeneration = generation;
        activeTask = executor.submit(() -> runTranscription(media, state, requestGeneration));
    }

    private void runTranscription(File media, SubtitleState state, long requestGeneration) {
        List<SubtitleCue> accumulated = new ArrayList<>();
        try {
            extractor.extract(media, chunk -> {
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Subtitle task cancelled");
                    }

                    // Blocking request happens ONLY on the subtitle I/O thread.
                    String response = client.transcribe(chunk.file());
                    List<SubtitleCue> newCues =
                            SubtitleCueMapper.fromJson(response, chunk.startUs());
                    accumulated.addAll(newCues);
                    accumulated.sort(Comparator.comparingLong(SubtitleCue::startUs));

                    // Synchronize publication and cancellation on the same lock.
                    synchronized (this) {
                        if (generation != requestGeneration) {
                            throw new InterruptedException("Superseded subtitle task");
                        }
                        state.setCues(accumulated);
                    }
                } finally {
                    // Audio data must not persist in temporary files.
                    Files.deleteIfExists(chunk.file());
                }
            });
            synchronized (this) {
                if (generation == requestGeneration) {
                    completed = true;
                }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            // The video player keeps running if the network/server is unavailable.
            synchronized (this) {
                if (generation == requestGeneration) {
                    System.err.println("Subtitles unavailable: "
                            + failure.getClass().getSimpleName() + ": "
                            + safeMessage(failure));
                }
            }
        }
    }

    private static String safeMessage(Exception exception) {
        // Avoid echoing API keys, response bodies or sensitive request data.
        if (exception instanceof IOException) {
            return "network or server request failed";
        }
        return "could not transcribe this audio";
    }

    public synchronized void cancel() {
        cancelCurrent();
    }

    private void cancelCurrent() {
        generation++;
        if (activeTask != null) {
            activeTask.cancel(true);
        }
        activeTask = null;
        activeMedia = null;
        completed = false;
    }

    @Override
    public synchronized void close() {
        cancelCurrent();
        executor.shutdownNow();
    }
}
