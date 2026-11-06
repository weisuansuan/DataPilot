package com.datapilot.analytics;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record AnalyticsOverview(
        boolean syntheticData,
        String datasetCode,
        long orderCount,
        long paidOrderCount,
        BigDecimal paidSalesAmount,
        BigDecimal completedRefundAmount,
        LocalDate refundObservationCutoff,
        Instant generatedAt,
        List<RegionComparison> regionComparisons,
        List<CategoryRefundRate> categoryRefundRates) {

    public record RegionComparison(
            String regionCode,
            String regionName,
            long septemberPaidOrders,
            long octoberPaidOrders,
            BigDecimal septemberSales,
            BigDecimal octoberSales,
            BigDecimal salesChange,
            BigDecimal salesChangePercent) { }

    public record CategoryRefundRate(
            String categoryCode,
            String categoryName,
            long soldUnits,
            long refundedUnits,
            BigDecimal refundRate) { }
}
