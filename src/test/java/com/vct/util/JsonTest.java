package com.vct.util;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class JsonTest {

    @Test
    void roundTripsNestedStructuresAndUnicode() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "Wir müssen links rum — \"jetzt\"\n日本語 🎮");
        m.put("n", 42L);
        m.put("d", 0.5);
        m.put("ok", true);
        m.put("none", null);
        m.put("list", List.of(1L, "two", List.of()));
        String json = Json.write(m);
        assertFalse(json.contains("\n"), "JSONL lines must not contain raw newlines");
        assertEquals(m, Json.parse(json));
    }

    @Test
    void parsesEscapesAndNumbers() {
        Map<String, Object> m = Json.parseObject("{\"a\":\"\\u00fc\\t\\/\",\"b\":-1.5e2,\"c\":12345678901234,\"d\":[]}");
        assertEquals("ü\t/", m.get("a"));
        assertEquals(-150.0, m.get("b"));
        assertEquals(12345678901234L, m.get("c"));
        assertEquals(List.of(), m.get("d"));
    }

    @Test
    void escapesControlCharacters() {
        assertEquals("\"\\u0001\"", Json.write("\u0001"));
    }

    @Test
    void rejectsMalformedInput() {
        for (String bad : Arrays.asList("{", "{\"a\":}", "[1,]", "{\"a\":1} x", "\"unterminated", "tru")) {
            assertThrows(Json.JsonException.class, () -> Json.parse(bad), bad);
        }
    }
}
