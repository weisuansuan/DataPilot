package com.datapilot.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datapilot.query.QueryService;
import com.datapilot.query.SqlPolicy;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
class Text2SqlEvaluationDatabaseIT {
    @Autowired private QueryService queryService;
    @Autowired private SqlPolicy policy;

    @Test
    void referenceQueriesRunAgainstTheSeededDatabaseWithExpectedColumns() throws Exception {
        try (InputStream input = new ClassPathResource("text2sql/evaluation-v1.json").getInputStream()) {
            JsonNode cases = new ObjectMapper().readTree(input);
            for (JsonNode item : cases) {
                String id = item.get("id").asString();
                String sql = item.get("expectedSql").asString();
                assertTrue(policy.validate(sql).allowed(), id + " rejected by SQL policy");
                QueryService.QueryResponse result = queryService.execute(sql);
                assertTrue(result.success(), id + " failed: " + result.message());
                List<String> actualColumns = result.columns().stream().map(QueryService.QueryColumn::name).toList();
                List<String> expectedColumns = new java.util.ArrayList<>();
                item.get("expectedColumns").forEach(column -> expectedColumns.add(column.asString()));
                assertEquals(expectedColumns, actualColumns, id + " output shape");
                if (item.has("expectedScalar")) {
                    BigDecimal actual = new BigDecimal(result.rows().getFirst().get(expectedColumns.getFirst()).toString());
                    assertEquals(0, actual.compareTo(new BigDecimal(item.get("expectedScalar").asString())), id + " canonical value");
                }
            }
        }
    }
}
