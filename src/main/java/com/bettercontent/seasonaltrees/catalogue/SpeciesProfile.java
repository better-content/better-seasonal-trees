package com.bettercontent.seasonaltrees.catalogue;

import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/** Immutable seasonal policy. Times are sub-seasons in the half-open year [0, 12]. */
public record SpeciesProfile(ResourceLocation speciesId, Habit habit,
                             double leafOutStart, double leafOutDuration,
                             double shedStart, double shedEnd,
                             double bloomStart, double bloomEnd,
                             int autumnColor, String bloomVisual) {
    public enum Habit { UNCHANGED, EVERGREEN, TEMPERATE, TROPICAL }

    public SpeciesProfile {
        Objects.requireNonNull(speciesId, "speciesId");
        Objects.requireNonNull(habit, "habit");
        Objects.requireNonNull(bloomVisual, "bloomVisual");
        if (!bloomVisual.matches("[a-z0-9_]+")) {
            throw new IllegalArgumentException("Invalid bloomVisual for " + speciesId);
        }
        time(leafOutStart, "leafOutStart");
        time(leafOutDuration, "leafOutDuration");
        time(shedStart, "shedStart");
        time(shedEnd, "shedEnd");
        time(bloomStart, "bloomStart");
        time(bloomEnd, "bloomEnd");
        if (leafOutStart + leafOutDuration > 12.0) {
            throw new IllegalArgumentException(speciesId + ": leaf-out crosses year boundary");
        }
        if (shedEnd < shedStart || bloomEnd < bloomStart) {
            throw new IllegalArgumentException(speciesId + ": interval end precedes start");
        }
        if (autumnColor < 0 || autumnColor > 0xFFFFFF) {
            throw new IllegalArgumentException(speciesId + ": autumnColor is not RGB");
        }
        if (bloomVisual.equals("none") != (bloomStart == bloomEnd)) {
            throw new IllegalArgumentException(speciesId + ": bloom visual and interval disagree");
        }
        if (habit == Habit.UNCHANGED &&
            (leafOutStart != 0 || leafOutDuration != 0 || shedStart != 0 || shedEnd != 0 ||
             bloomStart != 0 || bloomEnd != 0 || !bloomVisual.equals("none"))) {
            throw new IllegalArgumentException(speciesId + ": unchanged species must have no seasonal actions");
        }
        if (habit == Habit.EVERGREEN && (leafOutDuration != 0 || shedStart != shedEnd)) {
            throw new IllegalArgumentException(speciesId + ": evergreen cannot shed or leaf out");
        }
        if ((habit == Habit.TEMPERATE || habit == Habit.TROPICAL) &&
            (leafOutDuration <= 0 || shedEnd <= shedStart)) {
            throw new IllegalArgumentException(speciesId + ": deciduous species needs leaf-out and shedding intervals");
        }
    }

    private static void time(double value, String name) {
        if (!Double.isFinite(value) || value < 0 || value > 12) {
            throw new IllegalArgumentException(name + " must be finite and in [0, 12]");
        }
    }
}
