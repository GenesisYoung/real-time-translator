package com.vct.tts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TtsVoiceTest {

    private static void touch(Path dir, String... names) throws IOException {
        for (String n : names) Files.writeString(dir.resolve(n), "x");
    }

    @Test
    void detectsAPiperVoice(@TempDir Path dir) throws Exception {
        // Layout of sherpa-onnx's vits-piper-zh_CN-huayan-medium release.
        touch(dir, "zh_CN-huayan-medium.onnx", "tokens.txt", "MODEL_CARD");
        Files.createDirectory(dir.resolve("espeak-ng-data"));
        TtsVoice v = TtsVoice.detect(dir);
        assertEquals(TtsVoice.Kind.VITS, v.kind());
        assertEquals(dir.resolve("zh_CN-huayan-medium.onnx"), v.model());
        assertEquals(dir.resolve("espeak-ng-data"), v.dataDir());
        assertNull(v.voices());
        assertTrue(v.lexicons().isEmpty());
    }

    @Test
    void detectsAKokoroVoiceWithChineseRulesInSherpaOrder(@TempDir Path dir) throws Exception {
        // Layout of sherpa-onnx's kokoro-multi-lang-v1_1 release.
        touch(dir, "model.onnx", "voices.bin", "tokens.txt", "lexicon-us-en.txt", "lexicon-zh.txt",
                "number-zh.fst", "date-zh.fst", "phone-zh.fst");
        Files.createDirectory(dir.resolve("espeak-ng-data"));
        Files.createDirectory(dir.resolve("dict"));
        TtsVoice v = TtsVoice.detect(dir);
        assertEquals(TtsVoice.Kind.KOKORO, v.kind());
        assertEquals(dir.resolve("voices.bin"), v.voices());
        assertEquals(List.of(dir.resolve("lexicon-us-en.txt"), dir.resolve("lexicon-zh.txt")), v.lexicons());
        assertEquals(List.of(dir.resolve("phone-zh.fst"), dir.resolve("date-zh.fst"), dir.resolve("number-zh.fst")),
                v.ruleFsts());
    }

    @Test
    void prefersTheFullPrecisionModel(@TempDir Path dir) throws Exception {
        touch(dir, "model.int8.onnx", "model.onnx", "tokens.txt", "lexicon.txt");
        assertEquals(dir.resolve("model.onnx"), TtsVoice.detect(dir).model());
    }

    @Test
    void explainsWhatIsMissing(@TempDir Path dir) throws Exception {
        assertTrue(assertThrows(TtsException.class, () -> TtsVoice.detect(dir.resolve("nope")))
                .getMessage().startsWith("Voice folder not found"));
        assertTrue(assertThrows(TtsException.class, () -> TtsVoice.detect(dir))
                .getMessage().startsWith("No tokens.txt"));
        touch(dir, "tokens.txt");
        assertTrue(assertThrows(TtsException.class, () -> TtsVoice.detect(dir))
                .getMessage().startsWith("No .onnx model"));
        touch(dir, "model.onnx");
        assertTrue(assertThrows(TtsException.class, () -> TtsVoice.detect(dir))
                .getMessage().contains("neither espeak-ng-data nor a lexicon"));
    }
}
