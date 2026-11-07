package com.datapilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class Text2SqlOutputParserTest {
    private final Text2SqlOutputParser parser = new Text2SqlOutputParser();

    @Test
    void parsesPlainAndFencedJsonAndClarification() {
        var plain = parser.parse("{\"intent\":\"orders\",\"sql\":\"SELECT COUNT(*) FROM sales_order\",\"explanation\":\"订单数\"}");
        assertEquals("orders", plain.intent());
        assertTrue(plain.sql().startsWith("SELECT"));
        var fenced = parser.parse("```json\n{\"intent\":\"trend\",\"sql\":\"SELECT COUNT(*) FROM payment\"}\n```");
        assertEquals("trend", fenced.intent());
        var clarification = parser.parse("{\"clarificationRequired\":true,\"clarificationQuestion\":\"按哪个月份？\"}");
        assertTrue(clarification.clarificationRequired());
        assertTrue(clarification.sql().isEmpty());
    }

    @Test
    void rejectsMalformedEmptyAndMissingSqlResponses() {
        assertThrows(RuntimeException.class, () -> parser.parse(""));
        assertThrows(RuntimeException.class, () -> parser.parse("not JSON"));
        assertThrows(RuntimeException.class, () -> parser.parse("{\"intent\":\"x\"}"));
        assertFalse(parser.parse("{\"clarificationRequired\":true,\"clarificationQuestion\":\"范围？\"}").sql().length() > 0);
    }
}
