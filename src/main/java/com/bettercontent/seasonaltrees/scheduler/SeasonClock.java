package com.bettercontent.seasonaltrees.scheduler;

import net.minecraft.server.level.ServerLevel;
import sereneseasons.api.season.ISeasonState;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.init.ModConfig;

/** Reads the authoritative Serene Seasons calendar; all units below are sub-seasons. */
public final class SeasonClock {
    private SeasonClock() {}

    public record Reading(double phase, int cycleTick, int cycleDuration, int dayDuration) {
        public boolean in(double start, double end) { return phase >= start && phase < end; }
    }

    /** Calendar as experienced by a tropical root: phase zero is SS early wet. */
    public record LocalReading(double phase, long cycle) {}

    /**
     * Serene Seasons starts its northern calendar at phase zero, while its tropical
     * year starts at northern phase nine (early wet). The cycle number is adjusted
     * so the local year remains continuous across the northern 11->0 boundary.
     */
    public static LocalReading tropical(Reading reading, long northernCycle) {
        double phase = reading.phase();
        double effectivePhase = phase >= 9.0 ? phase - 9.0 : phase + 3.0;
        long effectiveCycle = northernCycle - (phase < 9.0 ? 1L : 0L);
        return new LocalReading(effectivePhase, effectiveCycle);
    }

    public static Reading read(ServerLevel level) {
        if (ModConfig.seasons == null || !ModConfig.seasons.isDimensionWhitelisted(level.dimension())) return null;
        ISeasonState state = SeasonHelper.getSeasonState(level);
        if (state == null || state.getCycleDuration() <= 0 || state.getDayDuration() <= 0) return null;
        int cycle = state.getCycleDuration();
        int tick = Math.floorMod(state.getSeasonCycleTicks(), cycle);
        return new Reading(12.0 * tick / cycle, tick, cycle, state.getDayDuration());
    }

    /** Inverse of the cumulative smoothstep: deterministic, distributed deadlines. */
    public static double inverseSmoothstep(double fraction) {
        double low = 0, high = 1;
        for (int i = 0; i < 32; i++) {
            double mid = (low + high) * .5;
            double cumulative = mid * mid * (3 - 2 * mid);
            if (cumulative < fraction) low = mid; else high = mid;
        }
        return (low + high) * .5;
    }

    public static double fraction(long rootSeed, long position, long cycle) {
        long z = rootSeed ^ Long.rotateLeft(position, 23) ^ (cycle * 0x9E3779B97F4A7C15L);
        z ^= z >>> 30; z *= 0xBF58476D1CE4E5B9L;
        z ^= z >>> 27; z *= 0x94D049BB133111EBL;
        z ^= z >>> 31;
        return (z >>> 11) * 0x1.0p-53;
    }
}
