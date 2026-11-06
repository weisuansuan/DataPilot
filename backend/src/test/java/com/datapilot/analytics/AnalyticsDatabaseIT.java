package com.datapilot.analytics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest
class AnalyticsDatabaseIT {

    @Autowired
    private AnalyticsService analyticsService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void returnsRealSeededDatabaseAggregatesAndExpectedAnomalies() {
        AnalyticsOverview overview = analyticsService.overview();

        assertTrue(overview.syntheticData());
        assertEquals("SYNTHETIC_ECOMMERCE_V1", overview.datasetCode());
        assertEquals(java.time.LocalDate.of(2026, 11, 6), overview.refundObservationCutoff());
        assertEquals(100_000, overview.orderCount());
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM refund r JOIN dataset_metadata d ON d.dataset_code = 'SYNTHETIC_ECOMMERCE_V1' WHERE DATE(r.refunded_at) > d.refund_observation_cutoff", Integer.class));
        BigDecimal expectedSales = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM payment WHERE status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')", BigDecimal.class);
        BigDecimal expectedRefunds = jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM refund WHERE status = 'COMPLETED'", BigDecimal.class);
        assertEquals(expectedSales, overview.paidSalesAmount());
        assertEquals(expectedRefunds, overview.completedRefundAmount());
        assertEquals(4, overview.regionComparisons().size());
        assertEquals(5, overview.categoryRefundRates().size());

        AnalyticsOverview.RegionComparison west = overview.regionComparisons().stream()
                .filter(region -> "west".equals(region.regionCode())).findFirst().orElseThrow();
        assertTrue(west.salesChange().signum() < 0);
        assertEquals(west, overview.regionComparisons().stream()
                .min(java.util.Comparator.comparing(AnalyticsOverview.RegionComparison::salesChange)).orElseThrow());

        AnalyticsOverview.CategoryRefundRate electronics = overview.categoryRefundRates().stream()
                .filter(category -> "electronics".equals(category.categoryCode())).findFirst().orElseThrow();
        assertTrue(electronics.refundRate().compareTo(new java.math.BigDecimal("0.10")) > 0);
        assertEquals(electronics, overview.categoryRefundRates().stream()
                .max(java.util.Comparator.comparing(AnalyticsOverview.CategoryRefundRate::refundRate)).orElseThrow());
    }

    @Test
    void applicationReaderCannotModifyDataset() {
        assertEquals("datapilot_reader@%", jdbc.queryForObject("SELECT CURRENT_USER()", String.class));
        assertThrows(DataAccessException.class,
                () -> jdbc.update("UPDATE dataset_metadata SET order_count = order_count WHERE dataset_code = 'SYNTHETIC_ECOMMERCE_V1'"));
        assertThrows(DataAccessException.class,
                () -> jdbc.execute("CREATE TABLE datapilot.reader_write_probe (id INT PRIMARY KEY)"));
    }
}
