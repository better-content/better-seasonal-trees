package com.bettercontent.seasonaltrees.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Generation-identity ledger transitions without a chunk, server or mod-loader bootstrap. */
final class SpringBirthLedgerTest {
    private static final BlockPos ROOT = new BlockPos(37, 72, -14);
    private static final ResourceLocation BIRCH = new ResourceLocation("dynamictrees", "birch");

    @Test void theSameGenerationMayBeReadmittedButCannotReplaceAnExistingIncarnation() {
        SeasonalLedger ledger = new SeasonalLedger();
        UUID generation = UUID.randomUUID();
        SeasonalLedger.Tree born = ledger.admitGenerated(ROOT, BIRCH, generation);
        assertTrue(born.generated);
        assertEquals(generation, born.incarnation);
        assertSame(born, ledger.admitGenerated(ROOT, BIRCH, generation));
        assertNull(ledger.admitGenerated(ROOT, BIRCH, UUID.randomUUID()));
        assertSame(born, ledger.find(ROOT));
    }

    @Test void loadedPreexistingTreeDoesNotBecomeAGeneratedTree() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree old = new SeasonalLedger.Tree(ROOT, UUID.randomUUID(), BIRCH);
        ledger.trees.put(ROOT.asLong(), old);
        assertNull(ledger.admitGenerated(ROOT, BIRCH, UUID.randomUUID()));
        assertFalse(old.generated);
        assertSame(old, ledger.find(ROOT));
    }

    @Test void rootRemovalTombstonesExactGenerationAcrossSaveAndReload() {
        SeasonalLedger ledger = new SeasonalLedger();
        UUID original = UUID.randomUUID();
        ledger.admitGenerated(ROOT, BIRCH, original);
        ledger.forget(ROOT);
        assertTrue(ledger.removedGeneration(original));
        assertNull(ledger.admitGenerated(ROOT, BIRCH, original));
        SeasonalLedger reloaded = SeasonalLedger.load(ledger.save(new CompoundTag()));
        assertTrue(reloaded.removedGeneration(original));
        assertNull(reloaded.admitGenerated(ROOT, BIRCH, original));
        // A truly new tree at this coordinate has a different DT generation identity.
        UUID replacement = UUID.randomUUID();
        SeasonalLedger.Tree newTree = reloaded.admitGenerated(ROOT, BIRCH, replacement);
        assertEquals(replacement, newTree.incarnation);
        assertFalse(reloaded.removedGeneration(replacement));
    }

    @Test void obstructedBirthOpportunityIsConsumedAndCannotRegrowAfterPruning() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree tree = ledger.admitGenerated(ROOT, BIRCH, UUID.randomUUID());
        BlockPos pos = ROOT.above(3);
        SeasonalLedger.Ticket ticket = new SeasonalLedger.Ticket(pos, pos.below(),
                null, BIRCH, 1, .18, false);
        tree.tickets.put(pos.asLong(), ticket);
        ledger.resolve(ticket, SeasonalLedger.Outcome.OBSTRUCTED);
        assertTrue(ticket.consumed);
        ledger.resolve(ticket, SeasonalLedger.Outcome.RESTORED);
        assertEquals(SeasonalLedger.Outcome.OBSTRUCTED, ticket.outcome);
        ledger.prune(tree, 1);
        assertTrue(tree.tickets.containsKey(pos.asLong()));
        ledger.prune(tree, 2);
        assertFalse(tree.tickets.containsKey(pos.asLong()));
    }

    @Test void invalidRootHeaderAlsoEvictsPreviouslyAdmittedGeneration() {
        SeasonalLedger ledger = new SeasonalLedger();
        UUID generation = UUID.randomUUID();
        ledger.admitGenerated(ROOT, BIRCH, generation);
        ledger.tombstoneGeneration(generation);
        assertNull(ledger.find(ROOT));
        assertTrue(ledger.removedGeneration(generation));
        assertNull(ledger.admitGenerated(ROOT, BIRCH, generation));
    }

    @Test void invalidHeaderTombstoneRejectsLaterLeafChunkReceiptAcrossReload() {
        SeasonalLedger ledger = new SeasonalLedger();
        UUID badHeader = UUID.randomUUID();
        ledger.tombstoneGeneration(badHeader);
        assertNull(ledger.admitGenerated(ROOT, BIRCH, badHeader));
        SeasonalLedger reloaded = SeasonalLedger.load(ledger.save(new CompoundTag()));
        assertTrue(reloaded.removedGeneration(badHeader));
        assertNull(reloaded.admitGenerated(ROOT, BIRCH, badHeader));
    }

    @Test void oldSchemaTreeCannotAcquireGenerationProvenanceByReload() {
        SeasonalLedger ledger = new SeasonalLedger();
        SeasonalLedger.Tree old = new SeasonalLedger.Tree(ROOT, UUID.randomUUID(), BIRCH);
        ledger.trees.put(ROOT.asLong(), old);
        CompoundTag persisted = ledger.save(new CompoundTag());
        persisted.putInt("schemaVersion", 7);
        persisted.getList("trees", Tag.TAG_COMPOUND).getCompound(0).remove("generated");
        SeasonalLedger reloaded = SeasonalLedger.load(persisted);
        SeasonalLedger.Tree loaded = reloaded.find(ROOT);
        assertFalse(loaded.generated);
        assertNull(reloaded.admitGenerated(ROOT, BIRCH, UUID.randomUUID()));
    }
}
