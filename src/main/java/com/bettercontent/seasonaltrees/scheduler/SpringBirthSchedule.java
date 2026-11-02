package com.bettercontent.seasonaltrees.scheduler;

import com.bettercontent.seasonaltrees.catalogue.SpeciesProfile;

/** Calendar-local generation decisions; native-ranked partial autumn deadlines apply before first publication. */
public final class SpringBirthSchedule {
    private SpringBirthSchedule() {}

    public static boolean shouldInitialize(SpeciesProfile.Habit habit, boolean tropicalEligible,
                                           double phase, double leafOutStart, double leafOutDuration,
                                           double shedStart, double shedEnd) {
        return (habit == SpeciesProfile.Habit.TEMPERATE
                || habit == SpeciesProfile.Habit.TROPICAL && tropicalEligible)
                && Double.isFinite(phase) && phase >= 0 && phase < 12
                && Double.isFinite(leafOutStart) && Double.isFinite(leafOutDuration)
                && leafOutStart >= 0 && leafOutStart < 12
                && leafOutDuration > 0 && leafOutStart + leafOutDuration <= 12
                && Double.isFinite(shedStart) && Double.isFinite(shedEnd)
                && shedStart >= 0 && shedStart < shedEnd && shedEnd <= 12
                && (phase < leafOutStart + leafOutDuration || phase >= shedStart);
    }

    public static double initialDeadline(double start, double duration, long generationSeed,
                                         long position, long cycle) {
        if (!Double.isFinite(start) || !Double.isFinite(duration) || start < 0 || start >= 12
                || duration <= 0 || start + duration > 12)
            throw new IllegalArgumentException("Invalid leaf-out window");
        return start + duration * SeasonClock.inverseSmoothstep(
                SeasonClock.fraction(generationSeed, position, cycle));
    }
    /** Native outer-first rank is frozen per generated tree, rather than erasing the entire autumn canopy. */
    public static double rankedDeadline(double start, double duration, int rank, int count, double jitter) {
        return SeasonalSchedule.deadline(start, duration, rank, count, jitter);
    }
    public static boolean initialLeafAbsent(double phase, double leafOutDeadline, double shedDeadline) {
        return Double.isFinite(phase) && phase >= 0 && phase < 12
                && Double.isFinite(leafOutDeadline) && Double.isFinite(shedDeadline)
                && (phase >= shedDeadline || phase < leafOutDeadline);
    }
    public static boolean admit(boolean generated, boolean previouslyKnown, boolean tombstoned) {
        return generated && !previouslyKnown && !tombstoned;
    }
}
