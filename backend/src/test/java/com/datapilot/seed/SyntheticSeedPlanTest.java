package com.datapilot.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SyntheticSeedPlanTest {

    @Test
    void splitsRequestedOrderCountAcrossTenMonthsWithoutLoss() {
        int total = 100_003;
        int generated = 0;
        for (int month = 0; month < 10; month++) {
            generated += SyntheticSeedPlan.monthOrderCount(total, month);
        }
        assertEquals(total, generated);
    }

    @Test
    void introducesSpecificWestRegionDeclineInOctober() {
        var september = SyntheticSeedPlan.regionalOrderCounts(8, 10_000);
        var october = SyntheticSeedPlan.regionalOrderCounts(9, 10_000);
        assertEquals(2_500, september.get(3));
        assertEquals(800, october.get(3));
        assertEquals(10_000, october.stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void constrainsScaleToTenThroughOneMillionOrders() {
        assertThrows(IllegalArgumentException.class, () -> SyntheticSeedPlan.validateOrderCount(9));
        assertThrows(IllegalArgumentException.class, () -> SyntheticSeedPlan.validateOrderCount(1_000_001));
        SyntheticSeedPlan.validateOrderCount(100_000);
        SyntheticSeedPlan.validateOrderCount(1_000_000);
    }
}
