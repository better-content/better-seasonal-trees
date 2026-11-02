package com.bettercontent.seasonaltrees.scheduler;

/** Stable one-position-per-stratum deadline math; ordering is supplied by the caller. */
public final class SeasonalSchedule {
    private SeasonalSchedule() {}

    /** A plan freezes the final pre-shed canopy, not a summer growth snapshot. */
    public static boolean planEligible(double phase, double shedStart) {
        return phase >= shedStart;
    }

    /** Higher support distance sheds first; hash and position break same-depth ties. */
    public static int compareOuterFirst(int depthA, double hashA, long posA,
                                        int depthB, double hashB, long posB) {
        int depth = Integer.compare(depthB, depthA);
        if (depth != 0) return depth;
        int hash = Double.compare(hashA, hashB);
        return hash != 0 ? hash : Long.compare(posA, posB);
    }

    public static double quantile(int rank, int count, double jitter) {
        if (count <= 0 || rank < 0 || rank >= count || !Double.isFinite(jitter)
                || jitter < 0 || jitter >= 1) throw new IllegalArgumentException("Invalid rank quantile");
        return (rank + jitter) / count;
    }

    public static double deadline(double start, double duration, int rank, int count, double jitter) {
        return start + duration * SeasonClock.inverseSmoothstep(quantile(rank, count, jitter));
    }
}
