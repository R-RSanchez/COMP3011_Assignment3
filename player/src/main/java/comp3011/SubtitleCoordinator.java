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
    private final SubtitleCache cache;

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
        this(extractor, client, new SubtitleCache());
    }

    SubtitleCoordinator(AudioChunkExtractor extractor, SubtitleHttpClient client,
                        SubtitleCache cache) {
        this.extractor = extractor;
        this.client = client;
        this.cache = cache;
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

    /**
     * Extracts short audio chunks while two independent HTTP workers transcribe
     * them. Requests never block the JavaFX thread or the frame pipeline.
     * The number of outstanding WAV files is bounded by the worker count.
     * A late response from an old media generation is never published.
     */
    private void runTranscription(File media, SubtitleState state, long requestGeneration) {
        final int maxInFlight = 2;
        final java.util.concurrent.Semaphore available =
                new java.util.concurrent.Semaphore(maxInFlight);
        final java.util.concurrent.atomic.AtomicBoolean anyFailure =
                new java.util.concurrent.atomic.AtomicBoolean();
        final ExecutorService uploads = Executors.newFixedThreadPool(maxInFlight,
                runnable -> Thread.ofPlatform()
                        .name("comp3011-subtitle-http")
                        .daemon(true)
                        .unstarted(runnable));
        final List<Future<?>> requests = new ArrayList<>();
        final List<SubtitleCue> accumulated = new ArrayList<>();

        try {
            extractor.extract(media, chunk -> {
                // Interruptible backpressure: at most two WAV chunks outstanding.
                available.acquire();
                boolean accepted = false;
                try {
                    if (Thread.currentThread().isInterrupted()) {
                        throw new InterruptedException("Subtitle job cancelled");
                    }
                    synchronized (this) {
                        if (generation != requestGeneration) {
                            throw new InterruptedException("Superseded subtitle job");
                        }
                    }

                    Future<?> request = uploads.submit(() -> {
                        try {
                            String response = cache.lookupOrTranscribe(
                                    chunk.file(), client::transcribe);
                            List<SubtitleCue> received =
                                    SubtitleCueMapper.fromJson(response, chunk.startUs());

                            // Every HTTP worker publishes a complete, sorted snapshot.
                            // Out-of-order responses are safe, as are seeks and toggles.
                            synchronized (this) {
                                if (generation == requestGeneration) {
                                    accumulated.addAll(received);
                                    accumulated.sort(Comparator.comparingLong(
                                            SubtitleCue::startUs));
                                    state.setCues(accumulated);
                                }
                            }
                        } catch (InterruptedException cancelled) {
                            Thread.currentThread().interrupt();
                        } catch (Exception failure) {
                            // One failed chunk must not prevent later speech appearing.
                            anyFailure.set(true);
                            synchronized (this) {
                                if (generation == requestGeneration) {
                                    System.err.println("Subtitle chunk unavailable: "
                                            + safeMessage(failure));
                                }
                            }
                        } finally {
                            try {
                                Files.deleteIfExists(chunk.file());
                            } catch (IOException ignored) {
                                // Temporary-file cleanup is best effort.
                            }
                            available.release();
                        }
                    });
                    requests.add(request);
                    accepted = true;
                } finally {
                    if (!accepted) {
                        // AudioChunkExtractor deletes the unaccepted WAV itself.
                        available.release();
                    }
                }
            });

            // This wait runs ONLY on the dedicated extraction thread.
            for (Future<?> request : requests) {
                request.get();
            }
            synchronized (this) {
                if (generation == requestGeneration) {
                    // A retry remains possible after partial network failures.
                    completed = !anyFailure.get();
                }
            }
        } catch (InterruptedException cancelled) {
            Thread.currentThread().interrupt();
        } catch (Exception failure) {
            synchronized (this) {
                if (generation == requestGeneration) {
                    System.err.println("Subtitles unavailable: "
                            + failure.getClass().getSimpleName() + ": "
                            + safeMessage(failure));
                }
            }
        } finally {
            // Cancels in-flight HTTP calls when the player is stopped/replaced.
            uploads.shutdownNow();
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
