package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.util.List;

import org.bytedeco.javacv.Frame;
import org.junit.jupiter.api.Test;

/**
 * Verifies that glyph rasterisation is reused, and only pixels in active
 * subtitle intervals are changed. No real Whisper call is involved.
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
class SubtitleRenderCacheTest {

    @Test
    void rasterisesOncePerPhraseAndRerendersOnlyOnChange() {
        SubtitleState state = new SubtitleState();
        state.setCues(List.of(
                new SubtitleCue(0, 3_000_000, "First example"),
                new SubtitleCue(4_000_000, 7_000_000, "Second example")));
        state.setEnabled(true);
        FrameSubtitler processor = new FrameSubtitler(state);

        Frame first = blankFrame();
        processor.process(first, new InfoFrame(1, 1_000_000));
        assertEquals(1, processor.maskBuildCount());
        assertTrue(anyPixelChanged(first));

        Frame samePhrase = blankFrame();
        processor.process(samePhrase, new InfoFrame(2, 2_000_000));
        assertEquals(1, processor.maskBuildCount(),
                "Do not rasterise unchanged subtitle text for each frame");
        assertFramesEqual(first, samePhrase);

        Frame quiet = blankFrame();
        processor.process(quiet, new InfoFrame(3, 3_500_000));
        assertFalse(anyPixelChanged(quiet));
        assertEquals(1, processor.maskBuildCount());

        Frame next = blankFrame();
        processor.process(next, new InfoFrame(4, 5_000_000));
        assertTrue(anyPixelChanged(next));
        assertEquals(2, processor.maskBuildCount());

        state.setEnabled(false);
        Frame off = blankFrame();
        processor.process(off, new InfoFrame(5, 5_000_000));
        assertFalse(anyPixelChanged(off));
        assertEquals(2, processor.maskBuildCount());

        state.setEnabled(true);
        processor.process(blankFrame(), new InfoFrame(6, 5_100_000));
        assertEquals(2, processor.maskBuildCount(),
                "Re-enabling the same cue should reuse its mask");
    }

    @Test
    void reusesMaskOnlyForMatchingFrameGeometry() {
        SubtitleState state = new SubtitleState();
        state.setCues(List.of(new SubtitleCue(0, 3_000_000, "Hello")));
        state.setEnabled(true);
        FrameSubtitler processor = new FrameSubtitler(state);
        processor.process(blankFrame(), new InfoFrame(1, 1_000_000));
        assertEquals(1, processor.maskBuildCount());

        Frame larger = new Frame(800, 450, Frame.DEPTH_UBYTE, 3);
        processor.process(larger, new InfoFrame(2, 1_200_000));
        assertEquals(2, processor.maskBuildCount());
        assertTrue(anyPixelChanged(larger));
    }

    private static Frame blankFrame() {
        Frame frame = new Frame(640, 360, Frame.DEPTH_UBYTE, 3);
        ByteBuffer buffer = (ByteBuffer) frame.image[0];
        for (int i = 0; i < buffer.capacity(); i++) buffer.put(i, (byte) 0);
        return frame;
    }

    private static boolean anyPixelChanged(Frame frame) {
        ByteBuffer b = (ByteBuffer) frame.image[0];
        for (int i = 0; i < b.capacity(); i++) {
            if (b.get(i) != 0) return true;
        }
        return false;
    }

    private static void assertFramesEqual(Frame left, Frame right) {
        ByteBuffer a = (ByteBuffer) left.image[0];
        ByteBuffer b = (ByteBuffer) right.image[0];
        assertEquals(a.capacity(), b.capacity());
        byte[] leftBytes = new byte[a.capacity()];
        byte[] rightBytes = new byte[b.capacity()];
        a.duplicate().clear().get(leftBytes);
        b.duplicate().clear().get(rightBytes);
        assertArrayEquals(leftBytes, rightBytes);
    }
}
