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

    private volatile List<SubtitleCue> cues = List.of();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setCues(List<SubtitleCue> newCues) {
        this.cues = List.copyOf(Objects.requireNonNull(newCues));
    }

    /** Immutable snapshot retained while retrying unavailable transcription chunks. */
    List<SubtitleCue> snapshot() {
        return cues;
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
