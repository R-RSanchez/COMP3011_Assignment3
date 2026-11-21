package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Assignment 3 subtitle rendering regression tests.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
class SubtitleRenderingTest {
    private static SubtitleState fixtureState() {
        SubtitleState state = new SubtitleState();
        state.setCues(List.of(
                new SubtitleCue(0, 3_000_000, "TEST: Subtitles are working."),
                new SubtitleCue(4_000_000, 7_000_000, "Press S to toggle subtitles.")));
        return state;
    }

    @Test
    void productionStateStartsWithoutInventedSubtitles() {
        SubtitleState state = new SubtitleState();
        state.setEnabled(true);
        assertNull(state.textAt(1_000_000));
        assertTrue(state.snapshot().isEmpty());
    }

    @Test
    void selectsSubtitleByMediaTimestamp() {
        SubtitleState state = fixtureState();
        state.setEnabled(true);

        assertEquals(
                "TEST: Subtitles are working.",
                state.textAt(1_000_000));

        assertNull(state.textAt(3_500_000));

        assertEquals(
                "Press S to toggle subtitles.",
                state.textAt(5_000_000));
    }

    @Test
    void disablingSubtitlesHidesText() {
        SubtitleState state = fixtureState();

        assertNull(state.textAt(1_000_000));

        state.setEnabled(true);
        assertNotNull(state.textAt(1_000_000));

        state.setEnabled(false);
        assertNull(state.textAt(1_000_000));
    }

    @Test
    void supportsReplacingDemonstrationCues() {
        SubtitleState state = fixtureState();

        state.setCues(List.of(
                new SubtitleCue(
                        1_000_000,
                        3_000_000,
                        "Actual transcription")));

        state.setEnabled(true);

        assertNull(state.textAt(500_000));
        assertEquals(
                "Actual transcription",
                state.textAt(2_000_000));
        assertNull(state.textAt(3_000_000));
    }

    @Test
    void rendersSubtitlePixelsWhenEnabled() throws Exception {
        Frame frame = new Frame(
                640, 360, Frame.DEPTH_UBYTE, 3);

        ByteBuffer pixels = (ByteBuffer) frame.image[0];

        for (int i = 0; i < pixels.capacity(); i++) {
            pixels.put(i, (byte) 0);
        }

        SubtitleState state = fixtureState();

        FrameSubtitler processor = new FrameSubtitler(state);

        processor.initialise(new InfoVideo(
                "test.mp4",
                240,
                frame.imageWidth,
                frame.imageHeight,
                frame.imageDepth,
                frame.imageChannels,
                frame.imageStride,
                24.0,
                24,
                41667,
                0));

        // Disabled: the image must remain unchanged.
        processor.process(
                frame,
                new InfoFrame(24, 1_000_000));

        for (int i = 0; i < pixels.capacity(); i++) {
            assertEquals(0, pixels.get(i));
        }

        // Enabled: subtitle glyphs must change image pixels.
        state.setEnabled(true);

        processor.process(
                frame,
                new InfoFrame(24, 1_000_000));

        boolean changed = false;

        for (int i = 0; i < pixels.capacity(); i++) {
            if (pixels.get(i) != 0) {
                changed = true;
                break;
            }
        }

        assertTrue(changed, "Subtitles must be drawn onto the frame");
    }
}
