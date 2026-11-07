package com.datapilot.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

final class Text2SqlOutputParser {
    private final ObjectMapper mapper = new ObjectMapper();

    Text2SqlOutput parse(String raw) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("empty model response");
        String text = raw.trim();
        if (text.startsWith("```")) {
            int firstLine = text.indexOf('\n');
            int closing = text.lastIndexOf("```");
            if (firstLine < 0 || closing <= firstLine) throw new IllegalArgumentException("invalid JSON fence");
            text = text.substring(firstLine + 1, closing).trim();
        } else if (!text.startsWith("{")) {
            int first = text.indexOf('{');
            int last = text.lastIndexOf('}');
            if (first < 0 || last < first) throw new IllegalArgumentException("missing JSON object");
            text = text.substring(first, last + 1);
        }
        JsonNode root = mapper.readTree(text);
        if (root == null || !root.isObject()) throw new IllegalArgumentException("expected JSON object");
        String intent = string(root.get("intent"));
        String sql = string(root.get("sql"));
        String explanation = string(root.get("explanation"));
        boolean clarification = root.get("clarificationRequired") != null
                && root.get("clarificationRequired").asBoolean(false);
        String clarificationQuestion = string(root.get("clarificationQuestion"));
        String reason = string(root.get("reason"));
        if (clarification) {
            if (clarificationQuestion.isBlank()) throw new IllegalArgumentException("clarification missing");
            return new Text2SqlOutput(intent, "", explanation, true, clarificationQuestion, reason);
        }
        if (sql.isBlank()) {
            if (reason.isBlank() && explanation.isBlank()) throw new IllegalArgumentException("missing SQL and reason");
            return new Text2SqlOutput(intent, "", explanation, false, "", reason);
        }
        if (sql.length() > 16_384) throw new IllegalArgumentException("invalid SQL field");
        return new Text2SqlOutput(intent, sql, explanation, false, "", reason);
    }

    private String string(JsonNode node) { return node != null && node.isString() ? node.asString().trim() : ""; }

    record Text2SqlOutput(String intent, String sql, String explanation,
            boolean clarificationRequired, String clarificationQuestion, String reason) { }
}
