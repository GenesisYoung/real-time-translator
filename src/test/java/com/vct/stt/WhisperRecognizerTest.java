package com.vct.stt;

import com.vct.audio.TestWav;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WhisperRecognizerTest {

    @Test
    void languageTagsBecomeSortedWhisperCodes() {
        assertEquals("en,ja,zh", WhisperRecognizer.languageList(Set.of("zh-Hans", "ja-JP", "en", "EN-us")));
        assertEquals("", WhisperRecognizer.languageList(Set.of()));
    }

    @Test
    void parsesResultJsonIncludingNullNumbers() {
        WhisperRecognizer.Transcription t = WhisperRecognizer.parse(
                "{\"text\":\" こんにちは\\\"世界\\\" \",\"language\":\"ja\",\"language_probability\":0.9712,"
                        + "\"confidence\":null,\"no_speech_probability\":0.0100,\"detect_ms\":41,\"decode_ms\":310}");
        assertEquals("こんにちは\"世界\"", t.result().text());
        assertEquals("ja", t.result().language());
        assertEquals(0.9712, t.result().languageConfidence(), 1e-9);
        assertTrue(Double.isNaN(t.result().confidence()));
        assertEquals(41, t.detectMillis());
        assertEquals(310, t.decodeMillis());
    }

    @Test
    void missingFilesGiveClearMessages(@TempDir Path dir) {
        SttException noLib = assertThrows(SttException.class,
                () -> WhisperRecognizer.open(dir.resolve("vct_stt.dll"), dir.resolve("m.bin"), false, 1));
        assertTrue(noLib.getMessage().startsWith("Speech library not found"), noLib.getMessage());
    }

    /**
     * Runs the real native library with whisper.cpp's placeholder test model. Its weights are not
     * trained, so the text is meaningless; the tests check the whole path around it.
     * Skipped until native/stt is built.
     */
    @Nested
    class WithNativeLibrary {

        private static Path library;
        private static Path model;
        private static WhisperRecognizer whisper;
        private static float[] jfk;

        @BeforeAll
        static void open(@TempDir Path dir) throws Exception {
            String lib = System.getProperty(WhisperRecognizer.LIBRARY_PROPERTY);
            assumeTrue(lib != null && Files.isRegularFile(Path.of(lib)), "native/stt not built");
            library = Path.of(lib);
            model = dir.resolve("for-tests-ggml-tiny.bin");
            try (InputStream in = WhisperRecognizerTest.class.getResourceAsStream("/whisper/for-tests-ggml-tiny.bin")) {
                Files.copy(in, model, StandardCopyOption.REPLACE_EXISTING);
            }
            whisper = WhisperRecognizer.open(library, model, false, 2);
            jfk = Arrays.copyOf(TestWav.read16kMono("/audio/jfk.wav"), 3 * 16_000);
        }

        @AfterAll
        static void close() {
            if (whisper != null) whisper.close();
        }

        @Test
        void reportsBuildFeatures() {
            assertTrue(whisper.systemInfo().contains("WHISPER"), whisper.systemInfo());
        }

        @Test
        void detectionChoosesOnlyAmongAllowedLanguages() throws SttException {
            WhisperRecognizer.Transcription t = whisper.transcribe(jfk, Set.of("en", "ja", "zh"));
            SttResult r = t.result();
            assertTrue(Set.of("en", "ja", "zh").contains(r.language()), r.language());
            assertTrue(r.languageConfidence() >= 0 && r.languageConfidence() <= 1, "" + r.languageConfidence());
            assertNotNull(r.text());
            assertTrue(t.decodeMillis() >= 0 && t.detectMillis() >= 0);
        }

        @Test
        void oneAllowedLanguageIsForced() throws SttException {
            SttResult r = whisper.transcribe(jfk, Set.of("ja")).result();
            assertEquals("ja", r.language());
            assertEquals(1.0, r.languageConfidence());
        }

        @Test
        void veryShortClipsArePaddedNotRejected() throws SttException {
            SttResult r = whisper.transcribe(new float[800], Set.of("en")).result(); // 50 ms
            assertNotNull(r.text());
        }

        @Test
        void invalidModelFileIsReportedWithWhisperLog(@TempDir Path dir) throws IOException {
            Path bogus = Files.writeString(dir.resolve("bogus.bin"), "not a model");
            SttException e = assertThrows(SttException.class, () -> WhisperRecognizer.open(library, bogus, false, 1));
            assertTrue(e.getMessage().startsWith("whisper.cpp could not load the model"), e.getMessage());
            assertTrue(e.getMessage().contains("invalid model"), e.getMessage());
        }

        @Test
        void closedRecognizerRefusesWork() throws Exception {
            WhisperRecognizer w = WhisperRecognizer.open(library, model, false, 1);
            w.close();
            w.close(); // second close is a no-op
            assertThrows(SttException.class, () -> w.transcribe(jfk, Set.of("en")));
        }
    }
}
