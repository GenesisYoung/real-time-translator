package com.vct.transcript;

import com.vct.model.Source;
import com.vct.model.Utterance;
import com.vct.translate.TranslationResult;
import com.vct.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonlTranscriptWriterTest {

    static final Instant T0 = Instant.parse("2026-10-22T18:00:00Z");
    final Clock clock = Clock.fixed(T0, ZoneOffset.UTC);

    @TempDir
    Path dir;

    static Utterance u(String id, String text) {
        return new Utterance(id, Source.REMOTE, T0, T0.plusSeconds(2), text, "de", 0.91, 0.83, false);
    }

    static final TranslationResult TR = new TranslationResult("We go left.", "openai", "m", Duration.ofMillis(800), false);

    List<Map<String, Object>> lines(Path f) throws Exception {
        return Files.readAllLines(f, StandardCharsets.UTF_8).stream().map(Json::parseObject).toList();
    }

    @Test
    void cleanCloseProducesOneLinePerUtteranceWithTranslations() throws Exception {
        var w = JsonlTranscriptWriter.create(dir, "abc", Map.of("native", "en"), clock);
        w.writeUtterance(u("r-0001", "Wir gehen links."));
        w.writeUtterance(u("r-0002", "Schön, äh… ja!"));
        w.writeTranslation("r-0001", "en", TR);
        w.close();

        var lines = lines(w.file());
        assertEquals(4, lines.size());
        assertEquals("session", lines.get(0).get("type"));
        assertEquals("r-0001", lines.get(1).get("id"));
        assertEquals("We go left.", ((Map<?, ?>) ((List<?>) lines.get(1).get("translations")).getFirst()).get("text"));
        assertEquals("Schön, äh… ja!", lines.get(2).get("text"), "UTF-8 preserved");
        assertNull(lines.get(2).get("translations"));
        assertEquals("session_end", lines.get(3).get("type"));
        assertEquals(Boolean.TRUE, lines.get(3).get("compacted"));
        assertTrue(lines.get(1).containsKey("speaker"));
    }

    @Test
    void recoversAfterCrashWithTruncatedLastLine() throws Exception {
        var w = JsonlTranscriptWriter.create(dir, "crash", Map.of(), clock);
        w.writeUtterance(u("r-0001", "eins"));
        w.writeTranslation("r-0001", "en", TR);
        w.writeUtterance(u("r-0002", "zwei"));
        // Simulate a crash mid-write: partial JSON line, no session_end, no compaction.
        w.abandonForCrashTest();
        Files.writeString(w.file(), "{\"type\":\"utt\",\"id\":\"r-0003\",\"te", StandardCharsets.UTF_8, StandardOpenOption.APPEND);

        List<Path> repaired = JsonlTranscriptWriter.recover(dir);
        assertEquals(List.of(w.file()), repaired);
        var lines = lines(w.file());
        assertEquals(List.of("session", "utt", "utt", "session_end"), lines.stream().map(m -> m.get("type")).toList());
        assertEquals(Boolean.TRUE, lines.get(3).get("recovered"));
        assertEquals(1L, lines.get(3).get("skipped_lines"));
        assertNotNull(lines.get(1).get("translations"));

        assertTrue(JsonlTranscriptWriter.recover(dir).isEmpty(), "already-compacted files are left alone");
    }

    @Test
    void neverOverwritesAndRejectsWritesAfterClose() throws Exception {
        var w = JsonlTranscriptWriter.create(dir, "same", Map.of(), clock);
        assertThrows(java.nio.file.FileAlreadyExistsException.class,
                () -> JsonlTranscriptWriter.create(dir, "same", Map.of(), clock));
        w.close();
        assertThrows(java.io.IOException.class, () -> w.writeUtterance(u("x", "y")));
    }
}
