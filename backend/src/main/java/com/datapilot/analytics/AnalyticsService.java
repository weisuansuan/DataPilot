package com.datapilot.analytics;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class AnalyticsService {

    private final JdbcTemplate jdbc;

    public AnalyticsService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public AnalyticsOverview overview() {
        DatasetSummary summary = jdbc.queryForObject("""
                SELECT d.dataset_code, d.order_count, d.generated_at, d.refund_observation_cutoff,
                       COUNT(DISTINCT o.order_id) AS actual_orders,
                       COUNT(DISTINCT CASE WHEN p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED') THEN p.payment_id END) AS paid_orders,
                       COALESCE(SUM(CASE WHEN p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED') THEN p.amount END), 0) AS paid_sales,
                       COALESCE((SELECT SUM(r.amount) FROM refund r WHERE r.status = 'COMPLETED'), 0) AS refunded_amount
                FROM dataset_metadata d
                LEFT JOIN sales_order o ON o.synthetic_data = TRUE
                LEFT JOIN payment p ON p.order_id = o.order_id
                WHERE d.dataset_code = 'SYNTHETIC_ECOMMERCE_V1' AND d.synthetic_data = TRUE
                GROUP BY d.dataset_code, d.order_count, d.generated_at, d.refund_observation_cutoff
                """, (rs, rowNum) -> new DatasetSummary(
                rs.getString("dataset_code"), rs.getLong("actual_orders"), rs.getLong("paid_orders"),
                rs.getBigDecimal("paid_sales"), rs.getBigDecimal("refunded_amount"), rs.getDate("refund_observation_cutoff").toLocalDate(),
                rs.getTimestamp("generated_at").toInstant()));

        List<AnalyticsOverview.RegionComparison> regions = jdbc.query("""
                SELECT o.ship_region_code, o.ship_region_name,
                       SUM(CASE WHEN p.paid_at >= '2026-09-01' AND p.paid_at < '2026-10-01' THEN 1 ELSE 0 END) AS september_orders,
                       SUM(CASE WHEN p.paid_at >= '2026-10-01' AND p.paid_at < '2026-11-01' THEN 1 ELSE 0 END) AS october_orders,
                       COALESCE(SUM(CASE WHEN p.paid_at >= '2026-09-01' AND p.paid_at < '2026-10-01' THEN p.amount ELSE 0 END), 0) AS september_sales,
                       COALESCE(SUM(CASE WHEN p.paid_at >= '2026-10-01' AND p.paid_at < '2026-11-01' THEN p.amount ELSE 0 END), 0) AS october_sales
                FROM sales_order o
                JOIN payment p ON p.order_id = o.order_id AND p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')
                WHERE p.paid_at >= '2026-09-01' AND p.paid_at < '2026-11-01'
                GROUP BY o.ship_region_code, o.ship_region_name
                ORDER BY o.ship_region_code
                """, (rs, rowNum) -> {
            BigDecimal september = rs.getBigDecimal("september_sales");
            BigDecimal october = rs.getBigDecimal("october_sales");
            BigDecimal change = october.subtract(september);
            BigDecimal changePercent = september.signum() == 0 ? BigDecimal.ZERO
                    : change.divide(september, 6, RoundingMode.HALF_UP);
            return new AnalyticsOverview.RegionComparison(rs.getString("ship_region_code"), rs.getString("ship_region_name"),
                    rs.getLong("september_orders"), rs.getLong("october_orders"), september, october, change, changePercent);
        });

        List<AnalyticsOverview.CategoryRefundRate> categories = jdbc.query("""
                SELECT c.category_code, c.category_name,
                       SUM(i.quantity) AS sold_units,
                       SUM(COALESCE(r.refunded_units, 0)) AS refunded_units
                FROM product_category c
                JOIN product pr ON pr.category_id = c.category_id
                JOIN order_item i ON i.product_id = pr.product_id
                JOIN payment p ON p.order_id = i.order_id AND p.status IN ('SUCCEEDED','PARTIALLY_REFUNDED','REFUNDED')
                LEFT JOIN (
                    SELECT order_item_id, SUM(quantity) AS refunded_units
                    FROM refund WHERE status = 'COMPLETED'
                    GROUP BY order_item_id
                ) r ON r.order_item_id = i.order_item_id
                GROUP BY c.category_code, c.category_name
                ORDER BY c.category_code
                """, (rs, rowNum) -> {
            long sold = rs.getLong("sold_units");
            long refunded = rs.getLong("refunded_units");
            BigDecimal rate = sold == 0 ? BigDecimal.ZERO : BigDecimal.valueOf(refunded)
                    .divide(BigDecimal.valueOf(sold), 6, RoundingMode.HALF_UP);
            return new AnalyticsOverview.CategoryRefundRate(rs.getString("category_code"), rs.getString("category_name"), sold, refunded, rate);
        });

        return new AnalyticsOverview(true, summary.datasetCode(), summary.orderCount(), summary.paidOrderCount(),
                summary.paidSales(), summary.refundedAmount(), summary.refundObservationCutoff(), summary.generatedAt(), regions, categories);
    }

    private record DatasetSummary(String datasetCode, long orderCount, long paidOrderCount, BigDecimal paidSales,
            BigDecimal refundedAmount, LocalDate refundObservationCutoff, Instant generatedAt) { }
}
