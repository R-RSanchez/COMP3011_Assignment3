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
import java.util.List;

import org.bytedeco.javacv.Frame;

/**
 * Renders timed subtitles directly onto video frames.
 *
 * Uses an off-screen Java2D image as a text mask, then blends
 * only its subtitle region into the frame's pixel buffer.
 *
 * No JavaFX scene graph access occurs on worker threads.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
public final class FrameSubtitler extends FrameProcessor {

    private static final int MAX_CHARS = 42;
    private static final int MAX_LINES = 2;

    private final SubtitleState subtitleState;

    public FrameSubtitler() {
        this(new SubtitleState());
    }

    public FrameSubtitler(SubtitleState subtitleState) {
        this.subtitleState = subtitleState;
    }

    @Override
    public void process(Frame frame, InfoFrame info) {

        String text = subtitleState.textAt(info.timestampUs());

        if (text == null
                || frame.image == null
                || frame.image.length == 0
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

        if (width <= 0 || height <= 0 || channels <= 0) {
            return;
        }

        List<String> lines = wrapText(text);

        if (lines.isEmpty()) {
            return;
        }

        int fontSize = Math.max(
                12,
                Math.min(38, Math.min(width / 38, height / 13)));

        int regionHeight = Math.min(
                height,
                Math.max(48, fontSize * 4 + 16));

        int regionTop = Math.max(
                0,
                height - regionHeight - 8);

        BufferedImage mask = new BufferedImage(
                width,
                regionHeight,
                BufferedImage.TYPE_INT_ARGB);

        Graphics2D graphics = mask.createGraphics();

        try {
            graphics.setRenderingHint(
                    RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);

            graphics.setRenderingHint(
                    RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            Font font = new Font(
                    Font.SANS_SERIF,
                    Font.BOLD,
                    fontSize);

            graphics.setFont(font);

            FontMetrics metrics = graphics.getFontMetrics();

            int lineHeight = metrics.getHeight() + 2;

            int baseline = regionHeight
                    - 12
                    - metrics.getDescent()
                    - (lines.size() - 1) * lineHeight;

            for (String line : lines) {

                int x = Math.max(
                        0,
                        (width - metrics.stringWidth(line)) / 2);

                GlyphVector glyphs = font.createGlyphVector(
                        graphics.getFontRenderContext(),
                        line);

                Shape outline = glyphs.getOutline(x, baseline);

                graphics.setColor(Color.BLACK);

                graphics.setStroke(new BasicStroke(
                        Math.max(2.5f, fontSize / 7.0f),
                        BasicStroke.CAP_ROUND,
                        BasicStroke.JOIN_ROUND));

                graphics.draw(outline);

                graphics.setColor(Color.WHITE);
                graphics.fill(outline);

                baseline += lineHeight;
            }
        } finally {
            graphics.dispose();
        }

        blendMask(
                pixels,
                frame.imageStride,
                channels,
                width,
                regionHeight,
                regionTop,
                mask);
    }

    private static List<String> wrapText(String text) {

        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String word : text.strip().split("\\s+")) {

            if (word.length() > MAX_CHARS) {
                word = word.substring(0, MAX_CHARS);
            }

            int proposedLength = current.length()
                    + (current.length() == 0 ? 0 : 1)
                    + word.length();

            if (proposedLength > MAX_CHARS) {

                if (current.length() > 0) {
                    lines.add(current.toString());
                }

                if (lines.size() >= MAX_LINES) {
                    return lines;
                }

                current = new StringBuilder(word);

            } else {

                if (current.length() > 0) {
                    current.append(' ');
                }

                current.append(word);
            }
        }

        if (current.length() > 0
                && lines.size() < MAX_LINES) {
            lines.add(current.toString());
        }

        return lines;
    }

    private static void blendMask(
            ByteBuffer pixels,
            int stride,
            int channels,
            int width,
            int regionHeight,
            int regionTop,
            BufferedImage mask) {

        int[] overlay = mask.getRGB(
                0, 0, width, regionHeight,
                null, 0, width);

        int colorChannels = Math.min(channels, 3);

        for (int y = 0; y < regionHeight; y++) {

            for (int x = 0; x < width; x++) {

                int argb = overlay[y * width + x];

                int alpha = (argb >>> 24) & 0xff;

                if (alpha == 0) {
                    continue;
                }

                // Both outline and glyph are shades of grey.
                // Their RGB channel order is therefore irrelevant.
                int foreground = (argb >>> 16) & 0xff;

                int offset = (regionTop + y) * stride
                        + x * channels;

                if (offset < 0
                        || offset + colorChannels > pixels.capacity()) {
                    continue;
                }

                for (int channel = 0;
                        channel < colorChannels;
                        channel++) {

                    int original = pixels.get(offset + channel) & 0xff;

                    int blended = (
                            original * (255 - alpha)
                            + foreground * alpha
                            + 127) / 255;

                    pixels.put(
                            offset + channel,
                            (byte) blended);
                }
            }
        }
    }
}
