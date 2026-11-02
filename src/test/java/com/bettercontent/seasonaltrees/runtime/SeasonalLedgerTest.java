package com.bettercontent.seasonaltrees.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import java.util.Set;
import com.bettercontent.seasonaltrees.catalogue.SpeciesProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

final class SeasonalLedgerTest {
    private static final ResourceLocation BIRCH = new ResourceLocation("dynamictrees", "birch");

    @Test void missedSpringRetainsPendingTicketButPrunesConsumedHistory() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree tree = new SeasonalLedger.Tree(BlockPos.ZERO, UUID.randomUUID(), BIRCH);
        SeasonalLedger.Ticket pending = ticket(new BlockPos(1, 0, 0), false);
        SeasonalLedger.Ticket consumed = ticket(new BlockPos(2, 0, 0), true);
        tree.tickets.put(pending.pos.asLong(), pending);
        tree.tickets.put(consumed.pos.asLong(), consumed);

        ledger.prune(tree, 2);

        assertEquals(1, tree.tickets.size());
        assertTrue(tree.tickets.containsKey(pending.pos.asLong()));
        assertFalse(tree.tickets.containsKey(consumed.pos.asLong()));
    }

    @Test void savesVersionAndRejectsUnknownFutureSchema() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree tree = new SeasonalLedger.Tree(BlockPos.ZERO, UUID.randomUUID(), BIRCH);
        ledger.trees.put(tree.root.asLong(), tree);
        CompoundTag saved = ledger.save(new CompoundTag());
        assertEquals(SeasonalLedger.SCHEMA_VERSION, saved.getInt("schemaVersion"));
        CompoundTag savedTree = saved.getList("trees", Tag.TAG_COMPOUND).getCompound(0);
        assertTrue(savedTree.contains("palette", Tag.TAG_LIST));
        assertTrue(savedTree.contains("chunks", Tag.TAG_LIST));
        assertEquals(tree.incarnation, SeasonalLedger.load(saved).find(BlockPos.ZERO).incarnation);
        saved.putInt("schemaVersion", SeasonalLedger.SCHEMA_VERSION + 1);
        assertThrows(IllegalArgumentException.class, () -> SeasonalLedger.load(saved));
    }

    @Test void resolutionRecordsOneConcreteOutcome() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Ticket ticket = ticket(new BlockPos(1, 0, 0), false);
        assertEquals(SeasonalLedger.Outcome.PENDING, ticket.outcome);
        ledger.resolve(ticket, SeasonalLedger.Outcome.RESTORED);
        assertTrue(ticket.consumed);
        assertEquals(SeasonalLedger.Outcome.RESTORED, ticket.outcome);
        ledger.resolve(ticket, SeasonalLedger.Outcome.OBSTRUCTED);
        assertEquals(SeasonalLedger.Outcome.RESTORED, ticket.outcome);
        SeasonalLedger.Ticket unsuitable = ticket(new BlockPos(2, 0, 0), false);
        ledger.resolve(unsuitable, SeasonalLedger.Outcome.UNSUITABLE,
                SeasonalLedger.LOCATION_REJECTED | SeasonalLedger.HYDRATION_REJECTED);
        assertEquals(5, unsuitable.suitabilityMask);
    }

    @Test void cycleTimingSurvivesReloadAndOnlyChangesAfterCycleRoll() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree tree = new SeasonalLedger.Tree(BlockPos.ZERO, UUID.randomUUID(), BIRCH);
        ledger.trees.put(tree.root.asLong(), tree);
        SpeciesProfile original = profile(0, 1.0 / 3, 8.5, 9);
        SpeciesProfile reloaded = profile(.5, .25, 8, 8.75);
        ledger.freezeTiming(tree, 12, original);
        ledger.freezeTiming(tree, 12, reloaded);
        assertEquals(original.shedStart(), tree.shedStart);
        SeasonalLedger loaded = SeasonalLedger.load(ledger.save(new CompoundTag()));
        SeasonalLedger.Tree current = loaded.find(BlockPos.ZERO);
        assertEquals(12, current.timingCycle);
        assertEquals(original.leafOutDuration(), current.leafOutDuration);
        loaded.freezeTiming(current, 12, reloaded);
        assertEquals(original.shedStart(), current.shedStart);
        loaded.freezeTiming(current, 13, reloaded);
        assertEquals(reloaded.shedStart(), current.shedStart);
    }

    @Test void legacySchemaWithoutFrozenTimingCanAcquireCurrentProfile() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree tree = new SeasonalLedger.Tree(BlockPos.ZERO, UUID.randomUUID(), BIRCH);
        ledger.trees.put(tree.root.asLong(), tree);
        CompoundTag old = ledger.save(new CompoundTag());
        old.putInt("schemaVersion", 3);
        old.getList("trees", Tag.TAG_COMPOUND).getCompound(0).remove("timingCycle");
        SeasonalLedger loaded = SeasonalLedger.load(old);
        assertEquals(Long.MIN_VALUE, loaded.find(BlockPos.ZERO).timingCycle);
        loaded.freezeTiming(loaded.find(BlockPos.ZERO), 4, profile(0, 1.0 / 3, 8.5, 9));
        assertEquals(4, loaded.find(BlockPos.ZERO).timingCycle);
    }

    @Test void frozenVisualTimingAndTropicalEligibilitySurviveReload() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree tree = new SeasonalLedger.Tree(BlockPos.ZERO, UUID.randomUUID(), BIRCH);
        ledger.trees.put(tree.root.asLong(), tree);
        SpeciesProfile tropical = new SpeciesProfile(BIRCH, SpeciesProfile.Habit.TROPICAL,
                0, 1.0 / 3, 6, 7, 0, 1.0 / 3, 0xD5A832, "palo_verde");
        ledger.freezeTiming(tree, 4, tropical, true);

        SeasonalLedger.Tree loaded = SeasonalLedger.load(ledger.save(new CompoundTag())).find(BlockPos.ZERO);
        assertEquals(SpeciesProfile.Habit.TROPICAL, loaded.timingHabit);
        assertEquals(4, loaded.timingCycle);
        assertEquals(6, loaded.shedStart);
        assertEquals(7, loaded.shedEnd);
        assertEquals(0, loaded.bloomStart);
        assertEquals(1.0 / 3, loaded.bloomEnd);
        assertEquals(0xD5A832, loaded.autumnColor);
        assertEquals("palo_verde", loaded.bloomVisual);
        assertTrue(loaded.tropicalEligible);
    }

    @Test void autumnTicketsCarryOldSpringWindowAcrossRolloverAndReload() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree tree = new SeasonalLedger.Tree(BlockPos.ZERO, UUID.randomUUID(), BIRCH);
        ledger.trees.put(tree.root.asLong(), tree);
        ledger.freezeTiming(tree, 0, profile(0, 1.0 / 3, 8.5, 9));
        SeasonalLedger.Ticket pending = ticket(new BlockPos(1, 0, 0), false);
        tree.tickets.put(pending.pos.asLong(), pending);
        ledger.freezeTiming(tree, 1, profile(0, .1, 8, 8.5));
        assertEquals(.1, tree.leafOutDuration);
        assertEquals(1.0 / 3, tree.carryLeafOutEnd);
        assertEquals(8.5, tree.carryShedStart);
        assertEquals(1, tree.carryCycle);
        // Keep this test independent of block-registry bootstrap while checking NBT timing persistence.
        tree.tickets.clear();
        SeasonalLedger.Tree reloaded = SeasonalLedger.load(ledger.save(new CompoundTag())).find(BlockPos.ZERO);
        assertEquals(1.0 / 3, reloaded.carryLeafOutEnd);
        assertEquals(8.5, reloaded.carryShedStart);
        assertEquals(1, reloaded.carryCycle);
    }

    @Test void completedUnshedPlanPublishesOnlyWhenCompleteAndKeepsRankAndDeadlines() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree tree = new SeasonalLedger.Tree(BlockPos.ZERO, UUID.randomUUID(), BIRCH);
        BlockPos first = new BlockPos(1, 2, 3), firstSupport = new BlockPos(1, 2, 2);
        BlockPos second = new BlockPos(4, 5, 6), secondSupport = new BlockPos(4, 5, 5);
        SeasonalLedger.PlannedLeaf plan0 = new SeasonalLedger.PlannedLeaf(first, firstSupport,
                null, null, 0, 2, 8.25, .125);
        SeasonalLedger.PlannedLeaf plan1 = new SeasonalLedger.PlannedLeaf(second, secondSupport,
                null, null, 1, 2, 8.75, .375);
        ledger.beginPlan(tree, 7, 2);
        ledger.addPlan(tree, plan0);
        assertThrows(IllegalStateException.class, () -> ledger.completePlan(tree));
        assertFalse(tree.planComplete);
        ledger.addPlan(tree, plan1);
        ledger.completePlan(tree);
        assertEquals(7, tree.planCycle);
        assertTrue(tree.planComplete);
        assertEquals(2, tree.planSize);
        assertEquals(0, tree.plansView().get(first.asLong()).rank());
        assertEquals(1, tree.plansView().get(second.asLong()).rank());
        assertEquals(Double.doubleToRawLongBits(8.25),
                Double.doubleToRawLongBits(tree.plansView().get(first.asLong()).shedDeadline()));
        assertEquals(Double.doubleToRawLongBits(.125),
                Double.doubleToRawLongBits(tree.plansView().get(first.asLong()).restoreDeadline()));
        assertEquals(Double.doubleToRawLongBits(8.75),
                Double.doubleToRawLongBits(tree.plansView().get(second.asLong()).shedDeadline()));
        assertEquals(Double.doubleToRawLongBits(.375),
                Double.doubleToRawLongBits(tree.plansView().get(second.asLong()).restoreDeadline()));
        assertThrows(UnsupportedOperationException.class, () -> tree.plansView().clear());
    }

    @Test void durationChangeAtSamePhaseDoesNotAdvanceYearButRealWrapDoes() {
        SeasonalLedger ledger = new SeasonalLedger();
        ledger.observeCycle(900, 1200, 1);
        ledger.observeCycle(300, 400, 2);
        assertEquals(0, ledger.cycle());
        ledger.observeCycle(390, 400, 3);
        ledger.observeCycle(10, 400, 4);
        assertEquals(1, ledger.cycle());
    }

    @Test void branchPruneTombstonePersistsThroughRestorationCycle() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree tree = new SeasonalLedger.Tree(BlockPos.ZERO, UUID.randomUUID(), BIRCH);
        ledger.trees.put(tree.root.asLong(), tree);
        BlockPos cut = new BlockPos(0, 3, 0);
        ledger.tombstoneBranch(tree, cut, 8);
        assertTrue(ledger.isBranchTombstoned(tree, cut, 8));
        assertFalse(ledger.isBranchTombstoned(tree, cut, 9));
        SeasonalLedger.Tree loaded = SeasonalLedger.load(ledger.save(new CompoundTag())).find(BlockPos.ZERO);
        assertEquals(8L, loaded.prunedBranchesView().get(cut.asLong()).longValue());
        ledger.prune(tree, 8);
        assertTrue(ledger.isBranchTombstoned(tree, cut, 8));
        ledger.prune(tree, 9);
        assertFalse(ledger.isBranchTombstoned(tree, cut, 8));
    }

    @Test void persistedBranchMembershipRebuildsOwnerIndexAcrossReload() {
        SeasonalLedger ledger = new SeasonalLedger();
        BlockPos root = BlockPos.ZERO;
        SeasonalLedger.Tree tree = new SeasonalLedger.Tree(root, UUID.randomUUID(), BIRCH);
        ledger.trees.put(root.asLong(), tree);
        BlockPos branch = new BlockPos(3, 8, -2);
        assertTrue(ledger.claimManagedBranch(tree, branch));
        assertTrue(ledger.claimManagedBranch(tree, branch));
        ledger.tombstoneBranch(tree, branch, 11);

        SeasonalLedger loaded = SeasonalLedger.load(ledger.save(new CompoundTag()));
        SeasonalLedger.Tree restored = loaded.find(root);
        assertTrue(restored.managedBranchesView().contains(branch.asLong()));
        assertEquals(Set.of(root.asLong()), loaded.managedBranchOwnersAt(branch));
        assertTrue(loaded.isBranchTombstoned(restored, branch, 11));
        assertThrows(UnsupportedOperationException.class, () -> restored.managedBranchesView().clear());
    }

    @Test void pendingCellFailsClosedWhenItsOwnerRootOrTrunkIsUnloaded() {
        long root = BlockPos.ZERO.asLong();
        Set<Long> pendingOwners = Set.of(root);
        assertTrue(SeasonalRuntime.pendingOwnerUnavailable(pendingOwners, root, false, false));
        assertTrue(SeasonalRuntime.pendingOwnerUnavailable(pendingOwners, root, true, false));
        assertFalse(SeasonalRuntime.pendingOwnerUnavailable(pendingOwners, root, true, true));
        assertFalse(SeasonalRuntime.pendingOwnerUnavailable(Set.of(), root, false, false));
    }

    private static SpeciesProfile profile(double leafOutStart, double leafOutDuration,
                                          double shedStart, double shedEnd) {
        return new SpeciesProfile(BIRCH, SpeciesProfile.Habit.TEMPERATE,
                leafOutStart, leafOutDuration, shedStart, shedEnd, 0, 0, 0, "none");
    }

    private static SeasonalLedger.Ticket ticket(BlockPos pos, boolean consumed) {
        return new SeasonalLedger.Ticket(pos, BlockPos.ZERO, null, BIRCH, 1, 0.2, consumed);
    }
}
