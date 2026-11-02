package com.bettercontent.seasonaltrees.runtime;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pure NBT marker validation; does not create chunks or assert game/world publication. */
final class GenerationCanopyDataTest {
    private static final ChunkPos CHUNK = new ChunkPos(2, -1);
    private static final int MIN_Y = -64;
    private static final int MAX_Y = 320;
    private static final BlockPos ROOT = new BlockPos(37, 72, -14);
    private static final BlockPos LEAF = new BlockPos(38, 75, -14);
    private static final BlockPos SUPPORT = new BlockPos(38, 74, -14);
    private static final UUID GENERATION = UUID.fromString("5e229d14-1d47-4df1-8685-45af74c4ae8a");

    @Test void exactRootHeaderAndNativeLeafReceiptValidateWithinTheirChunk() {
        CompoundTag data = payload(header(), leaf(LEAF, SUPPORT, 0));
        assertTrue(valid(data, CHUNK));
        CompoundTag stripped = leaf(LEAF, SUPPORT, 1);
        stripped.putLong("targetCycle", 3);
        stripped.putDouble("deadline", .18);
        assertTrue(valid(payload(header(), stripped), CHUNK));
        assertTrue(valid(payload(header(), leaf(LEAF, SUPPORT, 2)), CHUNK));
    }

    @Test void identityAndChunkBoundaryCannotBeForgedOrDuplicated() {
        CompoundTag missingId = leaf(LEAF, SUPPORT, 0);
        missingId.remove("generation");
        assertFalse(valid(payload(missingId), CHUNK));
        assertFalse(valid(payload(header(), leaf(LEAF, SUPPORT, 0)), new ChunkPos(3, -1)));
        assertFalse(valid(payload(leaf(LEAF, SUPPORT, 0), leaf(LEAF, SUPPORT, 0)), CHUNK));
        CompoundTag otherGenerationHeader = header();
        otherGenerationHeader.putUUID("generation", UUID.randomUUID());
        assertFalse(valid(payload(header(), otherGenerationHeader), CHUNK),
                "two generations cannot claim the same physical root slot");
        CompoundTag wrongChunk = leaf(new BlockPos(48, 75, -14), SUPPORT, 0);
        assertFalse(valid(payload(wrongChunk), CHUNK));
        CompoundTag distantRoot = leaf(LEAF, SUPPORT, 0);
        distantRoot.putLong("root", new BlockPos(1000, 72, -14).asLong());
        assertFalse(valid(payload(distantRoot), CHUNK));
        assertFalse(GenerationCanopyData.validatePayload(payload(header()), CHUNK, 73, MAX_Y),
                "root header must be inside the declared build height");
        assertFalse(GenerationCanopyData.validatePayload(payload(leaf(
                new BlockPos(38, MIN_Y, -14), new BlockPos(38, MIN_Y - 1, -14), 0)),
                CHUNK, MIN_Y, MAX_Y), "leaf support cannot escape the build height");
    }

    @Test void malformedReceiptTypesSupportAndDeadlineFailClosed() {
        CompoundTag missingState = leaf(LEAF, SUPPORT, 0);
        missingState.remove("state");
        assertFalse(valid(payload(missingState), CHUNK));
        CompoundTag nonAdjacent = leaf(LEAF, ROOT, 0);
        assertFalse(valid(payload(nonAdjacent), CHUNK));
        assertFalse(valid(payload(leaf(LEAF, SUPPORT, 3)), CHUNK));
        assertFalse(valid(payload(leaf(LEAF, SUPPORT, 1)), CHUNK));
        CompoundTag missingRank = leaf(LEAF, SUPPORT, 0);
        missingRank.remove("rank");
        assertFalse(valid(payload(missingRank), CHUNK));
        CompoundTag outOfRangeRank = leaf(LEAF, SUPPORT, 0);
        outOfRangeRank.putInt("rank", 1);
        assertFalse(valid(payload(outOfRangeRank), CHUNK));
        CompoundTag largeGeneration = leaf(LEAF, SUPPORT, 0);
        largeGeneration.putInt("count", 65537);
        assertTrue(valid(payload(largeGeneration), CHUNK), "old arbitrary global cap is gone");
        largeGeneration.putInt("count", (int) GenerationCanopyData.maxGenerationPositions(MIN_Y, MAX_Y) + 1);
        assertFalse(valid(payload(largeGeneration), CHUNK));
        CompoundTag invalidDeadline = leaf(LEAF, SUPPORT, 1);
        invalidDeadline.putLong("targetCycle", 3);
        invalidDeadline.putDouble("deadline", Double.NaN);
        assertFalse(valid(payload(invalidDeadline), CHUNK));
        invalidDeadline.putDouble("deadline", -0.01);
        assertFalse(valid(payload(invalidDeadline), CHUNK));
        invalidDeadline.putDouble("deadline", 12.0);
        assertFalse(valid(payload(invalidDeadline), CHUNK));
        CompoundTag wrongType = payload();
        ListTag strings = new ListTag();
        strings.add(StringTag.valueOf("not a canopy receipt"));
        wrongType.put("leaves", strings);
        assertFalse(valid(wrongType, CHUNK));
    }

    @Test void disabledCalendarRetiresOnlyUninitializedLeafReceiptsOnce() {
        CompoundTag active = leaf(LEAF.east(), SUPPORT.east(), 1);
        active.putLong("targetCycle", 4);
        active.putDouble("deadline", .21);
        CompoundTag marker = payload(header(), leaf(LEAF, SUPPORT, 0), active,
                leaf(LEAF.east(2), SUPPORT.east(2), 2));
        assertTrue(valid(marker, CHUNK));
        assertEquals(1, GenerationCanopyData.retirePending(marker));
        ListTag entries = marker.getList("leaves", net.minecraft.nbt.Tag.TAG_COMPOUND);
        assertTrue(entries.getCompound(0).getBoolean("header"));
        assertFalse(entries.getCompound(0).contains("status"));
        assertEquals(2, entries.getCompound(1).getInt("status"));
        assertEquals(1, entries.getCompound(2).getInt("status"));
        assertEquals(4, entries.getCompound(2).getLong("targetCycle"));
        assertEquals(.21, entries.getCompound(2).getDouble("deadline"));
        assertEquals(2, entries.getCompound(3).getInt("status"));
        assertTrue(valid(marker, CHUNK));
        assertEquals(0, GenerationCanopyData.retirePending(marker),
                "reload must never renew an initial status0 opportunity");
    }

    @Test void onlyExactRootHeadersContributePreTreeTombstoneEpochs() {
        BlockPos otherRoot = ROOT.east(2);
        UUID otherHeaderEpoch = UUID.fromString("967fc58f-47d0-4e5b-a5a4-04e6fac32ba8");
        UUID leafOnlyEpoch = UUID.fromString("2eaf92c2-ec6d-4f57-bcc7-88ac4ca68aa1");
        CompoundTag otherHeader = header();
        otherHeader.putLong("root", otherRoot.asLong());
        otherHeader.putUUID("generation", otherHeaderEpoch);
        CompoundTag leafOnly = leaf(LEAF, SUPPORT, 0);
        leafOnly.putUUID("generation", leafOnlyEpoch);
        CompoundTag marker = payload(header(), otherHeader, leafOnly);
        assertTrue(valid(marker, CHUNK));
        assertEquals(Set.of(GENERATION), GenerationCanopyData.rootGenerations(marker, ROOT));
        assertEquals(Set.of(otherHeaderEpoch), GenerationCanopyData.rootGenerations(marker, otherRoot));
        assertTrue(GenerationCanopyData.rootGenerations(marker, ROOT.east(4)).isEmpty());
    }

    @Test void unsupportedTerminalLeafIsTrackedWithoutInventingRestorationSupport() {
        CompoundTag unsupported = leaf(LEAF, LEAF, 0);
        unsupported.putBoolean("restorable", false);
        assertTrue(valid(payload(header(), unsupported), CHUNK));
        unsupported.putInt("status", 2);
        assertTrue(valid(payload(header(), unsupported), CHUNK));
        unsupported.putInt("status", 1);
        unsupported.putLong("targetCycle", 4);
        unsupported.putDouble("deadline", .21);
        assertFalse(valid(payload(header(), unsupported), CHUNK),
                "terminal unsupported leaves cannot become restoration tickets");
        unsupported.putInt("status", 0);
        unsupported.putLong("support", SUPPORT.asLong());
        assertFalse(valid(payload(header(), unsupported), CHUNK),
                "unsupported sentinel must point to the exact leaf itself");
        CompoundTag missingFlag = leaf(LEAF, SUPPORT, 0);
        missingFlag.remove("restorable");
        assertFalse(valid(payload(missingFlag), CHUNK));
    }

    @Test void geometryBoundAdmits8193DistinctLeavesAndRejectsOverHeightPayload() {
        CompoundTag wrongVersion = payload(header());
        wrongVersion.putInt("version", 9);
        assertFalse(valid(wrongVersion, CHUNK));
        assertEquals(8448L, GenerationCanopyData.chunkSlots(64, 97));
        assertEquals(16896L, GenerationCanopyData.maxReceiptEntries(64, 97));
        assertEquals(76032L, GenerationCanopyData.maxGenerationPositions(64, 97));
        ListTag entries = new ListTag();
        entries.add(header());
        for (int i = 0; i < 8193; i++) {
            BlockPos pos = new BlockPos(32 + i % 16, 64 + i / 256, -16 + i / 16 % 16);
            BlockPos support = pos.getY() == 64 ? pos.above() : pos.below();
            CompoundTag item = leaf(pos, support, 0);
            item.putInt("rank", i);
            item.putInt("count", 8193);
            entries.add(item);
        }
        CompoundTag data = payload();
        data.put("leaves", entries);
        assertTrue(GenerationCanopyData.validatePayload(data, CHUNK, 64, 97),
                "8193 distinct native leaves are physically possible and must not be dropped");
        assertFalse(GenerationCanopyData.validatePayload(data, CHUNK, 64, 96),
                "the same marker exceeds a shorter chunk's real vertical geometry");
        // Size is checked before entries are interpreted, independently of any individual tag.
        ListTag oversized = new ListTag();
        CompoundTag oneHeader = header();
        for (long i = 0; i <= GenerationCanopyData.maxReceiptEntries(64, 97); i++)
            oversized.add(oneHeader);
        data.put("leaves", oversized);
        assertFalse(GenerationCanopyData.validatePayload(data, CHUNK, 64, 97));
    }

    private static boolean valid(CompoundTag data, ChunkPos chunk) {
        return GenerationCanopyData.validatePayload(data, chunk, MIN_Y, MAX_Y);
    }

    private static CompoundTag payload(CompoundTag... entries) {
        CompoundTag data = new CompoundTag();
        data.putInt("version", 1);
        ListTag leaves = new ListTag();
        for (CompoundTag entry : entries) leaves.add(entry);
        data.put("leaves", leaves);
        return data;
    }

    private static CompoundTag header() {
        CompoundTag item = identity();
        item.putBoolean("header", true);
        return item;
    }

    private static CompoundTag leaf(BlockPos pos, BlockPos support, int status) {
        CompoundTag item = identity();
        item.putLong("pos", pos.asLong());
        item.putLong("support", support.asLong());
        item.put("state", new CompoundTag()); // Codec checks type; native registry validation occurs on admission.
        item.put("supportState", new CompoundTag());
        item.putBoolean("restorable", true);
        item.putInt("status", status);
        item.putInt("rank", 0);
        item.putInt("count", 1);
        return item;
    }

    private static CompoundTag identity() {
        CompoundTag item = new CompoundTag();
        item.putLong("root", ROOT.asLong());
        item.putUUID("generation", GENERATION);
        item.putString("species", "dynamictrees:birch");
        return item;
    }
}
