package comp3011;

/**
 * One timed subtitle phrase.
 *
 * Times are measured in microseconds, matching InfoFrame.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
public record SubtitleCue(
        long startUs,
        long endUs,
        String text) {

    public SubtitleCue {
        if (startUs < 0 || endUs <= startUs) {
            throw new IllegalArgumentException("Invalid subtitle timing");
        }

        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Subtitle text cannot be blank");
        }
    }

    public boolean contains(long timestampUs) {
        return timestampUs >= startUs && timestampUs < endUs;
    }
}
