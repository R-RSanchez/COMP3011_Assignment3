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

    private final java.util.concurrent.ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor(
            runnable -> Thread.ofPlatform()
                    .name("comp3011-subtitle-io")
                    .daemon(true)
                    .unstarted(runnable));

    private Future<?> activeTask;
    private File activeMedia;
    private boolean completed;
    private long generation;
    private long activeStartUs;

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
        startIfNeeded(media, state, 0);
    }

    /** Start from the current position when captions are enabled mid-playback. */
    public synchronized void startIfNeeded(File media, SubtitleState state, long startUs) {
        // Decode from a stable origin: arbitrary seeks change chunk PCM and
        // invalidate exact cache keys. Cached chunks are read without HTTP.
        startUs = 0;
        if (media == null || executor.isShutdown()) {
            return;
        }
        if (media.equals(activeMedia) && Math.max(0, startUs) >= activeStartUs &&
                (completed || (activeTask != null && !activeTask.isDone()))) {
            return;
        }

        cancelCurrent();
        activeMedia = media;
        activeStartUs = Math.max(0, startUs);
        state.setCues(List.of()); // Never show demo cues or prior-media results.
        final long requestGeneration = generation;
        activeTask = executor.submit(() -> runTranscription(
                media, state, requestGeneration, 0));
    }

    /**
     * Extracts short audio chunks while two independent HTTP workers transcribe
     * them. Requests never block the JavaFX thread or the frame pipeline.
     * The number of outstanding WAV files is bounded by the worker count.
     * A late response from an old media generation is never published.
     */
    private void runTranscription(File media, SubtitleState state, long requestGeneration, long startUs) {
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
        final List<SubtitleCue> accumulated = new ArrayList<>(state.snapshot());
        if (Boolean.getBoolean("comp3011.playback.diagnostics")) System.out.printf("[A3-SUBTITLES] TRANSCRIPTION_START media=%s%n",
                media.getName());

        try {
            extractor.extract(media, startUs, chunk -> {
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
                        final long startedNs = System.nanoTime();
                        try {
                            String response = cache.lookupOrTranscribe(
                                    chunk.file(), client::transcribe);
                            List<SubtitleCue> received =
                                    SubtitleCueMapper.fromJson(response, chunk.startUs());

                            // Every HTTP worker publishes a complete, sorted snapshot.
                            // Out-of-order responses are safe, as are seeks and toggles.
                            synchronized (this) {
                                if (generation == requestGeneration) {
                                    accumulated.removeAll(received);
                                    accumulated.addAll(received);
                                    accumulated.sort(Comparator.comparingLong(
                                            SubtitleCue::startUs));
                                    state.setCues(accumulated);
                                    if (Boolean.getBoolean("comp3011.playback.diagnostics")) {
                                    // Number and timing of cues, not text or API data.
                                    int receivedCount = received.size();
                                    long earliestUs = received.stream()
                                            .mapToLong(SubtitleCue::startUs)
                                            .min().orElse(-1L);
                                    long latestUs = received.stream()
                                            .mapToLong(SubtitleCue::endUs)
                                            .max().orElse(-1L);
                                    System.out.printf("[A3-SUBTITLES] CUES "
                                                    + "chunk_ms=%d response_ms=%d "
                                                    + "received=%d total=%d first_ms=%d "
                                                    + "last_ms=%d cover28=%b cover32=%b "
                                                    + "cover35=%b%n",
                                            chunk.startUs() / 1_000,
                                            (System.nanoTime() - startedNs) / 1_000_000,
                                            receivedCount, accumulated.size(),
                                            earliestUs / 1_000, latestUs / 1_000,
                                            state.textAt(28_000_000L) != null,
                                            state.textAt(32_000_000L) != null,
                                            state.textAt(35_000_000L) != null);
                                    }
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
            synchronized (this) {
                if (generation == requestGeneration && anyFailure.get()
                        && !executor.isShutdown()) {
                    // Retry failed chunks after an outage; successful chunks come
                    // from disk cache. Keep already-visible cues during recovery.
                    activeTask = executor.schedule(
                            () -> runTranscription(media, state, requestGeneration, startUs),
                            2, java.util.concurrent.TimeUnit.SECONDS);
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
