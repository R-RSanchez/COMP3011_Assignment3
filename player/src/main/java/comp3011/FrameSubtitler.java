package comp3011;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.bytedeco.javacv.Frame;

/**
 * Draws timestamped subtitles into frame pixels after other pipeline effects.
 *
 * The A2 pipeline gives each processor instance to exactly one stage worker.
 * Therefore the most recently rasterised subtitle can be cached here without
 * locks. The independently mutable SubtitleState is published with volatiles.
 *
 * Rendering the glyphs and scanning their transparent pixels happens only when
 * the active phrase or video dimensions change, not once for every video frame.
 *
 * Author: Raúl Ruiz Sánchez, a3229619.
 * Developed with assistance from ChatGPT.
 */
public final class FrameSubtitler extends FrameProcessor {

    private static final int MAX_CHARS = 42;
    private static final int MAX_LINES = 2;

    private final SubtitleState subtitleState;

    // Only read/written by this processor's pipeline stage worker.
    private RenderMask lastMask;
    private int maskBuilds;

    public FrameSubtitler() {
        this(new SubtitleState());
    }

    public FrameSubtitler(SubtitleState subtitleState) {
        this.subtitleState = java.util.Objects.requireNonNull(subtitleState);
    }

    @Override
    public void process(Frame frame, InfoFrame info) {
        String phrase = subtitleState.textAt(info.timestampUs());
        if (phrase == null || frame.image == null || frame.image.length == 0
                || !(frame.image[0] instanceof ByteBuffer pixels)) {
            return;
        }
        if (frame.imageDepth != Frame.DEPTH_UBYTE
                && frame.imageDepth != Frame.DEPTH_BYTE) {
            return;
        }
        int width = frame.imageWidth;
        int height = frame.imageHeight;
        int channels = frame.imageChannels;
        int stride = frame.imageStride;
        if (width <= 0 || height <= 0 || channels <= 0 || stride <= 0) {
            return;
        }

        RenderMask mask = lastMask;
        if (mask == null || !mask.matches(phrase, width, height, channels, stride)) {
            mask = createMask(phrase, width, height, channels, stride);
            lastMask = mask;
            maskBuilds++;
        }

        // Only blend pixels actually covered by glyphs or black outline.
        int coloredChannels = Math.min(channels, 3);
        for (int i = 0; i < mask.offsets.length; i++) {
            int offset = mask.offsets[i];
            if (offset < 0 || offset > pixels.capacity() - coloredChannels) {
                continue;
            }
            int argb = mask.argb[i];
            int alpha = (argb >>> 24) & 0xff;
            int foreground = (argb >>> 16) & 0xff;
            for (int channel = 0; channel < coloredChannels; channel++) {
                int old = pixels.get(offset + channel) & 0xff;
                int mixed = (old * (255 - alpha) + foreground * alpha + 127) / 255;
                pixels.put(offset + channel, (byte) mixed);
            }
        }
    }

    // Exposes a deterministic performance property to unit tests (not public API).
    int maskBuildCount() {
        return maskBuilds;
    }

    private static RenderMask createMask(
            String phrase, int width, int height, int channels, int stride) {
        List<String> lines = wrapText(phrase);
        if (lines.isEmpty()) {
            return new RenderMask(phrase, width, height, channels, stride,
                    new int[0], new int[0]);
        }

        int fontSize = Math.max(12,
                Math.min(38, Math.min(width / 38, height / 13)));
        int regionHeight = Math.min(height, Math.max(48, fontSize * 4 + 16));
        int regionTop = Math.max(0, height - regionHeight - 8);

        BufferedImage image = new BufferedImage(
                width, regionHeight, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            Font font = new Font(Font.SANS_SERIF, Font.BOLD, fontSize);
            g.setFont(font);
            FontMetrics metrics = g.getFontMetrics();
            int lineHeight = metrics.getHeight() + 2;
            int baseline = regionHeight - 12 - metrics.getDescent()
                    - (lines.size() - 1) * lineHeight;

            for (String line : lines) {
                int x = Math.max(0, (width - metrics.stringWidth(line)) / 2);
                GlyphVector glyphs = font.createGlyphVector(
                        g.getFontRenderContext(), line);
                Shape outline = glyphs.getOutline(x, baseline);
                g.setColor(Color.BLACK);
                g.setStroke(new BasicStroke(
                        Math.max(2.5f, fontSize / 7.0f),
                        BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(outline);
                g.setColor(Color.WHITE);
                g.fill(outline);
                baseline += lineHeight;
            }
        } finally {
            g.dispose();
        }

        int[] overlay = image.getRGB(0, 0, width, regionHeight,
                null, 0, width);
        int[] offsets = new int[overlay.length];
        int[] pixels = new int[overlay.length];
        int used = 0;
        for (int y = 0; y < regionHeight; y++) {
            for (int x = 0; x < width; x++) {
                int color = overlay[y * width + x];
                if ((color >>> 24) == 0) {
                    continue;
                }
                offsets[used] = (regionTop + y) * stride + x * channels;
                pixels[used] = color;
                used++;
            }
        }
        return new RenderMask(phrase, width, height, channels, stride,
                Arrays.copyOf(offsets, used), Arrays.copyOf(pixels, used));
    }

    private static List<String> wrapText(String text) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String originalWord : text.strip().split("\\s+")) {
            String word = originalWord.length() > MAX_CHARS
                    ? originalWord.substring(0, MAX_CHARS) : originalWord;
            int length = current.length()
                    + (current.isEmpty() ? 0 : 1) + word.length();
            if (length > MAX_CHARS) {
                if (!current.isEmpty()) {
                    lines.add(current.toString());
                }
                if (lines.size() >= MAX_LINES) {
                    return lines;
                }
                current = new StringBuilder(word);
            } else {
                if (!current.isEmpty()) {
                    current.append(' ');
                }
                current.append(word);
            }
        }
        if (!current.isEmpty() && lines.size() < MAX_LINES) {
            lines.add(current.toString());
        }
        return lines;
    }

    /** Coordinates are in byte offsets for this specific frame layout. */
    private record RenderMask(
            String phrase, int width, int height, int channels, int stride,
            int[] offsets, int[] argb) {
        boolean matches(String newPhrase, int newWidth, int newHeight,
                int newChannels, int newStride) {
            return phrase.equals(newPhrase) && width == newWidth
                    && height == newHeight && channels == newChannels
                    && stride == newStride;
        }
    }
}
