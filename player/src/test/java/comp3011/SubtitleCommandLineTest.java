package comp3011;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Assignment 3 subtitle command-line regression tests.
 *
 * Author: Raúl Ruiz Sánchez, a3229619
 * Developed with assistance from ChatGPT.
 */
class SubtitleCommandLineTest {

    @TempDir
    Path tempDir;

    @Test
    void acceptsShortLongAndRepeatedSubtitleOptions() throws IOException {
        Path video = Files.createFile(tempDir.resolve("video.mp4"));

        CommandLineController controller =
                new CommandLineController(new String[] {
                        "-nS",
                        "--subtitle-frames",
                        video.toString()
                });

        assertEquals(List.of(
                FrameProcessorOption.NUMBER,
                FrameProcessorOption.SUBTITLE,
                FrameProcessorOption.SUBTITLE
        ), controller.getFrameProcessorOptions());

        assertEquals(0, controller.getExitCode());
        assertTrue(controller.shouldLaunchApplication());
    }

    @Test
    void createsSubtitleProcessor() {
        assertInstanceOf(
                FrameSubtitler.class,
                FrameProcessorOption.SUBTITLE.createProcessor()
        );
    }
}
