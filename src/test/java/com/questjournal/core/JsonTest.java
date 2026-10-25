package com.questjournal.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.questjournal.core.json.Json;
import com.questjournal.core.json.JsonException;

class JsonTest {
    @Test
    void parsesNestedValuesAndKeepsKeyOrder() throws Exception {
        Map<String, Object> m = Json.asObject(Json.parse("{\"b\":1,\"a\":[true,null,2.5,\"x\"],\"c\":{\"d\":-7}}"));
        assertEquals(List.of("b", "a", "c"), List.copyOf(m.keySet()));
        assertEquals(1L, m.get("b"));
        assertEquals(2.5, ((Number) Json.asArray(m.get("a")).get(2)).doubleValue(), 0.0);
        assertEquals(-7L, Json.asObject(m.get("c")).get("d"));
    }

    @Test
    void acceptsCommentsAndBom() throws Exception {
        Object o = Json.parse("\uFEFF// header\n{ /* inline */ \"a\": 1 } // trailer");
        assertEquals(1L, Json.asObject(o).get("a"));
    }

    @Test
    void rejectsDuplicateKeysTrailingCommasAndGarbage() {
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1,\"a\":2}"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":1,}"));
        assertThrows(JsonException.class, () -> Json.parse("[1,2,]"));
        assertThrows(JsonException.class, () -> Json.parse("{} extra"));
        assertThrows(JsonException.class, () -> Json.parse("{\"a\":01}"));
        assertThrows(JsonException.class, () -> Json.parse("\"unterminated"));
    }

    @Test
    void reportsLineAndColumn() {
        JsonException e = assertThrows(JsonException.class, () -> Json.parse("{\n  \"a\": 1,\n  \"b\": @\n}"));
        assertEquals(3, e.line);
    }

    @Test
    void deepNestingIsRejectedInsteadOfOverflowingTheStack() {
        String deep = "[".repeat(5000) + "]".repeat(5000);
        assertThrows(JsonException.class, () -> Json.parse(deep));
    }

    @Test
    void unicodeEscapesAndRoundTrip() throws Exception {
        Object o = Json.parse("\"caf\\u00e9 \\ud83d\\ude00 \\n\"");
        assertEquals("caf\u00e9 \uD83D\uDE00 \n", o);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("text", "line1\nline2\t\"quoted\" \\ \u0001");
        m.put("n", 64.0);
        m.put("raw", new Json.Raw("{\"x\":1}"));
        String s = Json.write(m, false);
        assertTrue(s.contains("\"n\":64"));
        Map<String, Object> back = Json.asObject(Json.parse(s));
        assertEquals(m.get("text"), back.get("text"));
        assertEquals(1L, Json.asObject(back.get("raw")).get("x"));
    }
}
