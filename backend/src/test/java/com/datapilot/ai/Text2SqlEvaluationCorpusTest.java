package com.datapilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datapilot.query.SqlPolicy;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class Text2SqlEvaluationCorpusTest {
    @Test
    void containsTwentyUniqueNaturalQuestionsWithSafeExpectedSql() throws Exception {
        try (InputStream input = new ClassPathResource("text2sql/evaluation-v1.json").getInputStream()) {
            JsonNode cases = new ObjectMapper().readTree(input);
            assertNotNull(cases);
            assertTrue(cases.isArray());
            assertEquals(20, cases.size());
            Set<String> ids = new HashSet<>();
            SqlPolicy policy = new SqlPolicy();
            for (JsonNode item : cases) {
                assertTrue(ids.add(item.get("id").asString()));
                assertFalse(item.get("question").asString().isBlank());
                assertFalse(item.get("metricId").asString().isBlank());
                assertTrue(policy.validate(item.get("expectedSql").asString()).allowed(), item.get("id").asString());
                assertTrue(item.get("expectedColumns").isArray() && !item.get("expectedColumns").isEmpty());
            }
        }
    }
}
