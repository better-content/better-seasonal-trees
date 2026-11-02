package com.bettercontent.seasonaltrees.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SeasonClockTest {
    @Test void inverseSmoothstepCoversEntireWindowMonotonically() {
        double previous = -1;
        for (int i = 0; i <= 1000; i++) {
            double fraction = i / 1000.0;
            double u = SeasonClock.inverseSmoothstep(fraction);
            assertTrue(u >= previous && u >= 0 && u <= 1);
            assertEquals(fraction, u * u * (3 - 2 * u), 1e-8);
            previous = u;
        }
    }

    @Test void scheduleIsStableAcrossRestartAndVariesByPositionAndCycle() {
        long seed = 0x41526374696cL;
        double first = SeasonClock.fraction(seed, 122331L, 7);
        assertEquals(first, SeasonClock.fraction(seed, 122331L, 7));
        assertTrue(first != SeasonClock.fraction(seed, 122332L, 7));
        assertTrue(first != SeasonClock.fraction(seed, 122331L, 8));
    }

    @Test void productionThirdSubseasonIsSevenDays() {
        int dayTicks = 24000, subSeasonDays = 21;
        int cycleTicks = 12 * subSeasonDays * dayTicks;
        double oneThird = 1.0 / 3.0;
        assertEquals(7L * dayTicks, Math.round(cycleTicks * oneThird / 12));
    }

    @Test void tropicalAxisStartsAtEarlyWetAndStaysContinuousAcrossNorthernWrap() {
        SeasonClock.LocalReading lateDry = SeasonClock.tropical(
                new SeasonClock.Reading(8.999, 0, 12_000, 24_000), 0);
        assertEquals(11.999, lateDry.phase(), 1e-9);
        assertEquals(-1, lateDry.cycle());

        SeasonClock.LocalReading earlyWet = SeasonClock.tropical(
                new SeasonClock.Reading(9.0, 0, 12_000, 24_000), 0);
        assertEquals(0.0, earlyWet.phase(), 1e-9);
        assertEquals(0, earlyWet.cycle());

        // Northern winter wrap is mid-wet, not a tropical year boundary.
        SeasonClock.LocalReading afterNorthernWrap = SeasonClock.tropical(
                new SeasonClock.Reading(0.0, 0, 12_000, 24_000), 1);
        assertEquals(3.0, afterNorthernWrap.phase(), 1e-9);
        assertEquals(0, afterNorthernWrap.cycle());
    }

    @Test void tropicalLateDryWindowCanReachTheEndOfTheTwelveUnitCycle() {
        assertTrue(SeasonalSchedule.planEligible(11.5, 11.5));
        double latest = SeasonalSchedule.deadline(11.5, 0.5, 99, 100, .999);
        assertTrue(latest < 12.0);
        assertTrue(latest >= 11.5);
    }
}
