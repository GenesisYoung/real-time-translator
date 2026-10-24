package com.vct.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppHomeTest {

    @AfterEach
    void reset() {
        System.clearProperty(AppHome.PROPERTY);
    }

    @Test
    void fromTheRepositoryRelativePathsStayInTheWorkingDirectory() {
        assertFalse(AppHome.installed());
        assertEquals(Path.of("models", "w.bin").toAbsolutePath(), AppHome.resolve("models/w.bin"));
    }

    @Test
    void inTheInstalledAppTheyAreFoundNextToTheProgram(@TempDir Path app) {
        System.setProperty(AppHome.PROPERTY, app.toString());
        assertTrue(AppHome.installed());
        assertEquals(app.resolve("models/whisper/ggml.bin"), AppHome.resolve("models/whisper/ggml.bin"));
        assertEquals(app.resolve(Path.of("native", "vct_audio.dll")), AppHome.resolve("native", "vct_audio.dll"));
        Path elsewhere = app.getRoot().resolve("data").resolve("w.bin");
        assertEquals(elsewhere, AppHome.resolve(elsewhere.toString()), "a path chosen with Browse stays as it is");
    }
}
