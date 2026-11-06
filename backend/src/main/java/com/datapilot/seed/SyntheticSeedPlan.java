package com.datapilot.seed;

import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;

public final class SyntheticSeedPlan {

    public static final long RANDOM_SEED = 20261106L;
    public static final int CUSTOMER_COUNT = 10_000;
    public static final int PRODUCT_COUNT = 100;
    public static final int MIN_ORDER_COUNT = 10;
    public static final int MAX_ORDER_COUNT = 1_000_000;
    public static final List<String> REGION_CODES = List.of("east", "north", "south", "west");
    public static final List<String> REGION_NAMES = List.of("华东", "华北", "华南", "西部");
    public static final List<String> CATEGORY_CODES = List.of("electronics", "home", "sports", "beauty", "grocery");
    public static final List<String> CATEGORY_NAMES = List.of("电子产品", "家居生活", "运动户外", "美妆个护", "食品饮料");
    public static final YearMonth FIRST_MONTH = YearMonth.of(2026, 1);

    private SyntheticSeedPlan() {
    }

    public static void validateOrderCount(int orderCount) {
        if (orderCount < MIN_ORDER_COUNT || orderCount > MAX_ORDER_COUNT) {
            throw new IllegalArgumentException("Order count must be between 10 and 1,000,000.");
        }
    }

    public static int monthOrderCount(int totalOrders, int monthIndex) {
        return totalOrders / 10 + (monthIndex < totalOrders % 10 ? 1 : 0);
    }

    public static List<Integer> regionalOrderCounts(int monthIndex, int ordersInMonth) {
        int[] counts = new int[REGION_CODES.size()];
        if (monthIndex == 9) {
            counts[3] = ordersInMonth * 8 / 100;
            distributeEvenly(counts, ordersInMonth - counts[3], 0, 3);
        } else {
            distributeEvenly(counts, ordersInMonth, 0, counts.length);
        }
        List<Integer> result = new ArrayList<>(counts.length);
        for (int count : counts) {
            result.add(count);
        }
        return List.copyOf(result);
    }

    private static void distributeEvenly(int[] counts, int amount, int start, int end) {
        int groups = end - start;
        for (int i = start; i < end; i++) {
            counts[i] = amount / groups + (i - start < amount % groups ? 1 : 0);
        }
    }
}
