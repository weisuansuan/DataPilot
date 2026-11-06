package com.datapilot.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class QueryDatabaseIT {
    @Autowired private QueryService queryService;
    @Autowired private SqlPolicy policy;
    @Autowired private JdbcTemplate jdbc;

    @Test
    void executesAsReaderAndCapsRowsEvenWhenSqlRequestsMore() {
        QueryService.QueryResponse response = queryService.execute(
                "SELECT o.order_id FROM sales_order o ORDER BY o.order_id LIMIT 999999");
        assertTrue(response.success(), response.message());
        assertEquals(200, response.rowCount());
        assertTrue(response.truncated());
        assertEquals("datapilot_reader@%", jdbc.queryForObject("SELECT CURRENT_USER()", String.class));
    }

    @Test
    void appliesServerExecutionDeadlineAndReturnsSanitizedError() {
        QueryService shortDeadline = new QueryService(jdbc, policy, 1, 1, new BigDecimal("100000000000000000000"));
        QueryService.QueryResponse response = shortDeadline.execute(
                "SELECT COUNT(*) AS pair_count FROM sales_order a JOIN sales_order b ON a.order_id < b.order_id");
        assertFalse(response.success());
        assertEquals("QUERY_TIMEOUT", response.errorCode());
        assertTrue(response.message().contains("时间限制"));
        QueryService.QueryResponse afterTimeout = queryService.execute("SELECT COUNT(*) AS paid_count FROM payment p");
        assertTrue(afterTimeout.success(), afterTimeout.message());
    }

    @Test
    void returnsExactDecimalAggregatesMatchingIndependentSql() {
        QueryService.QueryResponse response = queryService.execute(
                "SELECT SUM(p.amount) AS paid_amount FROM payment p WHERE p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')");
        BigDecimal independent = jdbc.queryForObject(
                "SELECT SUM(amount) FROM payment WHERE status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')", BigDecimal.class);
        assertTrue(response.success(), response.message());
        assertEquals(independent, response.rows().getFirst().get("paid_amount"));
    }

    @Test
    void limitsConcurrentManualQueries() throws Exception {
        QueryService oneAtATime = new QueryService(jdbc, policy, 5, 1, new BigDecimal("100000000000000000000"));
        try (ExecutorService executor = Executors.newSingleThreadExecutor()) {
            Future<QueryService.QueryResponse> first = executor.submit(() -> oneAtATime.execute(
                    "SELECT COUNT(*) AS pair_count FROM sales_order a JOIN sales_order b ON a.order_id < b.order_id"));
            Thread.sleep(100);
            QueryService.QueryResponse rejected = oneAtATime.execute("SELECT COUNT(*) AS order_count FROM sales_order o");
            assertEquals("QUERY_BUSY", rejected.errorCode());
            first.get();
        }
    }

    @Test
    void rejectsPlansAboveConfiguredCostThreshold() {
        QueryService restrictive = new QueryService(jdbc, policy, 5, 1, BigDecimal.ZERO);
        QueryService.QueryResponse response = restrictive.execute("SELECT COUNT(*) AS order_count FROM sales_order o");
        assertEquals("QUERY_PLAN_LIMIT", response.errorCode());
    }
}
