package com.vct.tools;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechCheckTest {

    @Test
    void joinsTheTextToSpeakAndReadsOptions() {
        var o = SpeechCheck.parse(new String[]{"say", "我们", "走吧", "--speed", "1.2", "--cpu"});
        assertEquals("我们 走吧", o.argument());
        assertEquals("1.2", o.get("speed", "1.0"));
        assertTrue(o.flags().contains("cpu"));
        assertEquals(Set.of("en", "ja", "zh"), o.languages());
    }

    @Test
    void rejectsMistakesWithAClearMessage() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SpeechCheck.parse(new String[]{}))
                .getMessage().startsWith("Choose a mode"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> SpeechCheck.parse(new String[]{"transcribe"}))
                .getMessage().startsWith("Give the WAV file"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SpeechCheck.parse(new String[]{"live", "--remote", "discordm"})).getMessage().contains("\"discordm\""));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> SpeechCheck.parse(new String[]{"live", "--openai-model"})).getMessage().endsWith("needs a value."));
    }
}
