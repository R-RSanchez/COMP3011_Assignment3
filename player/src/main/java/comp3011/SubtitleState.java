package comp3011;

import java.util.List;
import java.util.Objects;

/**
 * Thread-safe shared subtitle state.
 *
 * The JavaFX thread controls enabled/disabled state.
 * Frame-processing workers read immutable subtitle snapshots.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
public final class SubtitleState {

    private volatile boolean enabled;

    /*
     * Temporary demonstration cues.
     * The server integration will replace these with Whisper results.
     */
    private volatile List<SubtitleCue> cues = List.of(
            new SubtitleCue(
                    0,
                    3_000_000,
                    "TEST: Subtitles are working."),
            new SubtitleCue(
                    4_000_000,
                    7_000_000,
                    "Press S to toggle subtitles."),
            new SubtitleCue(
                    8_000_000,
                    11_000_000,
                    "Next: real speech transcription.")
    );

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setCues(List<SubtitleCue> newCues) {
        this.cues = List.copyOf(Objects.requireNonNull(newCues));
    }

    public String textAt(long timestampUs) {
        if (!enabled) {
            return null;
        }

        List<SubtitleCue> snapshot = cues;

        for (SubtitleCue cue : snapshot) {
            if (cue.contains(timestampUs)) {
                return cue.text();
            }
        }

        return null;
    }
}
