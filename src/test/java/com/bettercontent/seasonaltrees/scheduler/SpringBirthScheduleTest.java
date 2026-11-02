package com.bettercontent.seasonaltrees.scheduler;

import com.bettercontent.seasonaltrees.catalogue.SpeciesProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure generation-calendar decisions, not a test of native generation or chunk publication. */
final class SpringBirthScheduleTest {
    private static final long GENERATION = 0x61DC3381L;
    private static final double BIRCH_END = 1.0 / 3.0;
    private static final double SHED_START = 8.0;
    private static final double WINTER_START = 9.0;

    @Test void earlySpringAndAutumnThroughWinterAreBirthInitializationWindows() {
        assertTrue(temperate(0));
        assertTrue(temperate(BIRCH_END / 2));
        assertFalse(temperate(BIRCH_END));
        assertFalse(temperate(3));
        assertTrue(temperate(SHED_START));
        assertTrue(temperate(8.5)); // Only due native-ranked leaves are absent at first visibility.
        assertTrue(temperate(WINTER_START));
        assertTrue(temperate(11.999));
    }

    @Test void partialAutumnInitiallyRemovesOnlyTheDueNativeRankedFraction() {
        double outer = SpringBirthSchedule.rankedDeadline(SHED_START, 1, 0, 4, .25);
        double inner = SpringBirthSchedule.rankedDeadline(SHED_START, 1, 3, 4, .75);
        assertTrue(outer < 8.5 && inner > 8.5);
        assertTrue(outer < inner, "caller orders native leaves outer-first before assigning ranks");
        assertFalse(SpringBirthSchedule.initialLeafAbsent(SHED_START, .1, outer));
        assertTrue(SpringBirthSchedule.initialLeafAbsent(8.5, .1, outer));
        assertFalse(SpringBirthSchedule.initialLeafAbsent(8.5, .1, inner));
        assertTrue(SpringBirthSchedule.initialLeafAbsent(WINTER_START, .1, inner));
        assertEquals(outer, SpringBirthSchedule.rankedDeadline(SHED_START, 1, 0, 4, .25));
        assertThrows(IllegalArgumentException.class,
                () -> SpringBirthSchedule.rankedDeadline(SHED_START, 1, -1, 4, .25));
    }

    @Test void exactBirthDeadlinesYieldGradualSpringCanopyAndSummerNoRestrip() {
        int midwayVisible = 0;
        for (int i = 0; i < 512; i++) {
            long position = 31L * i + 100;
            double deadline = SpringBirthSchedule.initialDeadline(0, BIRCH_END,
                    GENERATION, position, 7);
            assertTrue(deadline >= 0 && deadline <= BIRCH_END);
            assertEquals(deadline, SpringBirthSchedule.initialDeadline(0, BIRCH_END,
                    GENERATION, position, 7));
            if (!SpringBirthSchedule.initialLeafAbsent(BIRCH_END / 2, deadline, WINTER_START))
                midwayVisible++;
            assertFalse(SpringBirthSchedule.initialLeafAbsent(3, deadline, WINTER_START));
            assertFalse(SpringBirthSchedule.initialLeafAbsent(8.5, deadline, WINTER_START));
            assertTrue(SpringBirthSchedule.initialLeafAbsent(WINTER_START, deadline, WINTER_START));
        }
        assertTrue(midwayVisible > 180 && midwayVisible < 332,
                "At mid-window both present and absent native leaf positions must be possible");
    }

    @Test void cherryWaitsForItsOwnLeafOutAndTropicalUsesLocalWetCalendar() {
        assertTrue(SpringBirthSchedule.shouldInitialize(SpeciesProfile.Habit.TEMPERATE,
                false, 0, 1.0 / 3, 1.0 / 3, SHED_START, WINTER_START));
        double cherry = SpringBirthSchedule.initialDeadline(1.0 / 3, 1.0 / 3,
                GENERATION, 17, 7);
        assertTrue(cherry >= 1.0 / 3 && cherry <= 2.0 / 3);
        assertTrue(SpringBirthSchedule.initialLeafAbsent(.1, cherry, WINTER_START));
        assertFalse(SpringBirthSchedule.initialLeafAbsent(1, cherry, WINTER_START));
        SeasonClock.LocalReading earlyWet = SeasonClock.tropical(
                new SeasonClock.Reading(9, 0, 1200, 100), 4);
        SeasonClock.LocalReading northernSpring = SeasonClock.tropical(
                new SeasonClock.Reading(0, 0, 1200, 100), 5);
        assertEquals(0, earlyWet.phase());
        assertEquals(3, northernSpring.phase());
        assertTrue(SpringBirthSchedule.shouldInitialize(SpeciesProfile.Habit.TROPICAL,
                true, earlyWet.phase(), 0, BIRCH_END, 6, 7));
        assertFalse(SpringBirthSchedule.shouldInitialize(SpeciesProfile.Habit.TROPICAL,
                true, northernSpring.phase(), 0, BIRCH_END, 6, 7));
    }

    @Test void evergreenUnchangedAndTropicalOutsideEligibleBiomeRemainNative() {
        for (SpeciesProfile.Habit habit : new SpeciesProfile.Habit[]{
                SpeciesProfile.Habit.EVERGREEN, SpeciesProfile.Habit.UNCHANGED})
            assertFalse(SpringBirthSchedule.shouldInitialize(habit, true, 0, 0, BIRCH_END,
                    SHED_START, WINTER_START));
        assertFalse(SpringBirthSchedule.shouldInitialize(SpeciesProfile.Habit.TROPICAL,
                false, 0, 0, BIRCH_END, 6, 7));
    }

    @Test void generationIdentityAndRootTombstonesPreventRenewedTickets() {
        assertTrue(SpringBirthSchedule.admit(true, false, false));
        assertFalse(SpringBirthSchedule.admit(false, false, false), "chunk reload is not generation");
        assertFalse(SpringBirthSchedule.admit(true, true, false), "known tree retains old tickets");
        assertFalse(SpringBirthSchedule.admit(true, false, true), "removed generation stays removed");
    }

    @Test void invalidCalendarWindowsFailClosed() {
        assertFalse(temperate(Double.NaN));
        assertFalse(temperate(-1));
        assertFalse(SpringBirthSchedule.shouldInitialize(SpeciesProfile.Habit.TEMPERATE,
                false, 0, 0, 0, SHED_START, WINTER_START));
        assertFalse(SpringBirthSchedule.shouldInitialize(SpeciesProfile.Habit.TEMPERATE,
                false, 0, 0, BIRCH_END, SHED_START, Double.NaN));
        assertFalse(SpringBirthSchedule.shouldInitialize(SpeciesProfile.Habit.TEMPERATE,
                false, 8.5, 0, BIRCH_END, WINTER_START, SHED_START));
        assertFalse(SpringBirthSchedule.initialLeafAbsent(Double.NaN, .1, WINTER_START));
        assertFalse(SpringBirthSchedule.initialLeafAbsent(0, Double.NaN, WINTER_START));
        assertFalse(SpringBirthSchedule.initialLeafAbsent(0, .1, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> SpringBirthSchedule.initialDeadline(0, 0, GENERATION, 12, 7));
        assertThrows(IllegalArgumentException.class,
                () -> SpringBirthSchedule.initialDeadline(Double.NaN, BIRCH_END, GENERATION, 12, 7));
    }

    private static boolean temperate(double phase) {
        return SpringBirthSchedule.shouldInitialize(SpeciesProfile.Habit.TEMPERATE,
                false, phase, 0, BIRCH_END, SHED_START, WINTER_START);
    }
}
