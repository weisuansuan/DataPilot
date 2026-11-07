package com.datapilot.ai;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.datapilot.ai.evaluation.QueryResultComparator;
import com.datapilot.query.QueryService.QueryColumn;
import com.datapilot.query.QueryService.QueryResponse;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class QueryResultComparatorTest {
    @Test
    void comparesExactDecimalsAndIgnoresUnspecifiedRowOrder() {
        QueryResponse expected = result(List.of(Map.of("region", "北部", "amount", new BigDecimal("10.00")),
                Map.of("region", "西部", "amount", new BigDecimal("20.00"))));
        QueryResponse actual = result(List.of(Map.of("amount", new BigDecimal("20.0"), "region", "西部"),
                Map.of("amount", new BigDecimal("10"), "region", "北部")));
        assertTrue(QueryResultComparator.equivalent(expected, actual));
        assertTrue(QueryResultComparator.digest(expected).equals(QueryResultComparator.digest(actual)));
    }

    @Test
    void detectsWrongValuesColumnsAndFailedQueries() {
        QueryResponse expected = result(List.of(Map.of("amount", new BigDecimal("10.00"))));
        assertFalse(QueryResultComparator.equivalent(expected, result(List.of(Map.of("amount", new BigDecimal("10.01"))))));
        assertFalse(QueryResultComparator.equivalent(expected, new QueryResponse(true, List.of(new QueryColumn("other", "DECIMAL")), expected.rows(), 1, 0, false, null, null)));
        assertFalse(QueryResultComparator.equivalent(expected, new QueryResponse(false, List.of(), List.of(), 0, 0, false, "QUERY_FAILED", "failed")));
    }

    @Test
    void comparesExactDecimalStringsUsingNumericColumnMetadata() {
        QueryResponse expected = result(List.of(Map.of("amount", new BigDecimal("90071992547409.92"))));
        QueryResponse actual = result(List.of(Map.of("amount", "90071992547409.9200")));
        assertTrue(QueryResultComparator.equivalent(expected, actual));
        assertTrue(QueryResultComparator.digest(expected).equals(QueryResultComparator.digest(actual)));
    }

    private QueryResponse result(List<Map<String, Object>> rows) {
        return new QueryResponse(true, List.of(new QueryColumn("amount", "DECIMAL"), new QueryColumn("region", "VARCHAR")), rows, rows.size(), 0, false, null, null);
    }
}
