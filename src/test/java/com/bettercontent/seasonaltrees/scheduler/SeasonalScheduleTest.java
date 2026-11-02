package com.bettercontent.seasonaltrees.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SeasonalScheduleTest {
    @Test void everyRankOwnsExactlyOneQuantileStratum() {
        for (int count : new int[] {1, 4, 257, 65536}) {
            double previous = -1;
            for (int rank = 0; rank < count; rank++) {
                double fraction = SeasonalSchedule.quantile(rank, count, .37);
                assertTrue(fraction >= (double) rank / count);
                assertTrue(fraction < (double) (rank + 1) / count);
                assertTrue(fraction > previous);
                previous = fraction;
            }
        }
    }

    @Test void inverseSmoothstepSpansIntervalAndReverseRankRestoresInsideFirst() {
        int count = 312;
        double firstShed = SeasonalSchedule.deadline(8.5, .5, 0, count, .5);
        double lastShed = SeasonalSchedule.deadline(8.5, .5, count - 1, count, .5);
        assertTrue(firstShed >= 8.5 && lastShed < 9);
        assertTrue(firstShed < lastShed);
        double outerRestore = SeasonalSchedule.deadline(0, 1.0 / 3, count - 1, count, .5);
        double innerRestore = SeasonalSchedule.deadline(0, 1.0 / 3, 0, count, .5);
        assertTrue(innerRestore < outerRestore);
        assertEquals(0.5, SeasonalSchedule.quantile(0, 1, .5));
    }

    @Test void rejectsInvalidRankInputs() {
        assertThrows(IllegalArgumentException.class, () -> SeasonalSchedule.quantile(-1, 5, .5));
        assertThrows(IllegalArgumentException.class, () -> SeasonalSchedule.quantile(5, 5, .5));
        assertThrows(IllegalArgumentException.class, () -> SeasonalSchedule.quantile(0, 0, .5));
        assertThrows(IllegalArgumentException.class, () -> SeasonalSchedule.quantile(0, 1, 1));
    }

    @Test void outerFirstOrderAlwaysPrecedesCloserSupport() {
        assertTrue(SeasonalSchedule.compareOuterFirst(4, .99, 9, 1, .01, 1) < 0);
        assertTrue(SeasonalSchedule.compareOuterFirst(3, .2, 9, 3, .8, 1) < 0);
        assertTrue(SeasonalSchedule.compareOuterFirst(3, .2, 1, 3, .2, 2) < 0);
    }

    @Test void finalPlanFreezesAtShedStartNotDuringSummerGrowth() {
        assertTrue(!SeasonalSchedule.planEligible(4, 8));
        assertTrue(!SeasonalSchedule.planEligible(7.999, 8));
        assertTrue(SeasonalSchedule.planEligible(8, 8));
    }
}
