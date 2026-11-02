package com.bettercontent.seasonaltrees.gametest;

import com.bettercontent.seasonaltrees.BetterSeasonalTrees;
import com.bettercontent.seasonaltrees.runtime.SeasonalLedger;
import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import com.ferreusveritas.dynamictrees.util.LevelContext;
import com.ferreusveritas.dynamictrees.util.SafeChunkBounds;
import com.ferreusveritas.dynamictrees.worldgen.GenerationContext;
import com.ferreusveritas.dynamictrees.worldgen.JoCode;
import com.ferreusveritas.dynamictrees.worldgen.JoCodeRegistry;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.season.SeasonHandler;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Serial native safety cases: the calendar is dimension-global, and no player world is touched. */
@GameTestHolder(BetterSeasonalTrees.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeasonalSafetyGameTests {
    private static final ResourceLocation BIRCH = new ResourceLocation("dynamictrees:birch");
    private static final int PRUNED = 0, STONE = 1, FLUID = 2, REPLACED_ROOT = 3, REMOVED_BRANCH = 4;

    private SeasonalSafetyGameTests() { }

    @GameTest(batch = "seasonal_safety", template = "safety", timeoutTicks = 2400)
    public static void nativePruningObstructionAndTopologySafety(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<BlockPos> roots = new ArrayList<>();
        for (int index = 0; index < 5; index++)
            roots.add(h.absolutePos(new BlockPos(128 + index * 32, 2, 10)).atY(220));
        Set<ChunkPos> ownedForcedChunks = new LinkedHashSet<>();
        List<Map<BlockPos, BlockState>> summerLeaves = new ArrayList<>();
        BlockPos[] prunedLeaf = new BlockPos[1];
        BlockPos[] restoredLeaf = new BlockPos[1];
        BlockPos[] stonePos = new BlockPos[1];
        BlockPos[] waterPos = new BlockPos[1];
        BlockPos[] cutBranch = new BlockPos[1];
        BlockPos[] orphanLeaf = new BlockPos[1];
        UUID[] oldIncarnation = new UUID[1];

        h.runAtTickTime(1, () -> {
            setPhase(level, 4.0);
            for (int index = 0; index < roots.size(); index++)
                generate(h, roots.get(index), index, ownedForcedChunks);
        });
        // Native hydration maintenance removes unqualified JoCode edge leaves without
        // stochastic age() regrowth, before any baseline or seasonal capture is taken.
        for (int settleTick : new int[]{10, 20, 30, 40, 50, 60, 70, 75}) h.runAtTickTime(settleTick, () -> {
            for (BlockPos root : roots) for (var entry : findLeaves(level, root).entrySet()) {
                BlockState state = level.getBlockState(entry.getKey());
                if (state.getBlock() instanceof DynamicLeavesBlock leaves) {
                    leaves.updateHydro(level, entry.getKey(), state, false);
                    BlockState hydrated = level.getBlockState(entry.getKey());
                    if (hydrated.getBlock() instanceof DynamicLeavesBlock live)
                        live.removeIfLightIsInadequate(hydrated, level, entry.getKey(), level.random);
                }
            }
        });
        h.runAtTickTime(80, () -> {
            for (BlockPos root : roots) {
                Map<BlockPos, BlockState> leaves = findLeaves(level, root);
                h.assertTrue(leaves.size() >= 20, "Native birch canopy too small at " + root);
                h.assertTrue(findBranches(level, root).size() >= 5, "Native birch wood too small at " + root);
                for (var entry : leaves.entrySet()) {
                    DynamicLeavesBlock block = (DynamicLeavesBlock) entry.getValue().getBlock();
                    h.assertTrue(block.getHydrationLevelFromNeighbors(level, entry.getKey(),
                            block.getProperties(entry.getValue())) > 0,
                        "Unqualified native baseline leaf at " + entry.getKey());
                    h.assertTrue(block.hasAdequateLight(entry.getValue(), level,
                            block.getProperties(entry.getValue()), entry.getKey()),
                        "Native baseline leaf has inadequate survival light at " + entry.getKey());
                }
                summerLeaves.add(leaves);
            }
            prunedLeaf[0] = summerLeaves.get(PRUNED).keySet().stream()
                    .max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
            h.assertTrue(level.destroyBlock(prunedLeaf[0], false), "Pre-shed pruning failed");
            for (BlockPos root : roots) {
                h.assertTrue(SeasonalRuntime.ensureTracked(level, root), "Native root not tracked at " + root);
                SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(root);
                h.assertTrue(tree == null || tree.tickets.isEmpty(), "Pre-shed capture made a ticket");
            }
            setPhase(level, 9.1);
        });
        h.runAtTickTime(800, () -> {
            SeasonalLedger ledger = SeasonalLedger.get(level);
            for (int index = 0; index < roots.size(); index++) {
                BlockPos root = roots.get(index);
                h.assertTrue(level.hasChunkAt(root), "Winter root chunk unloaded at " + root);
                BlockState rootState = level.getBlockState(root);
                h.assertTrue(rootState.getBlock() instanceof RootyBlock,
                    "Winter native root block missing at " + root + ": " + rootState);
                RootyBlock rootBlock = (RootyBlock) rootState.getBlock();
                BlockPos trunk = root.relative(rootBlock.getTrunkDirection(level, root));
                h.assertTrue(level.hasChunkAt(trunk), "Winter trunk chunk unloaded at " + trunk);
                BlockState trunkState = level.getBlockState(trunk);
                h.assertTrue(trunkState.getBlock() instanceof BranchBlock,
                    "Winter native trunk missing at " + trunk + ": " + trunkState);
                SeasonalLedger.Tree tree = ledger.find(root);
                h.assertTrue(tree != null, "Winter root record missing at " + root);
                h.assertTrue(findLeaves(level, root).isEmpty(), "Winter canopy still has leaves at " + root);
                int expected = summerLeaves.get(index).size() - (index == PRUNED ? 1 : 0);
                h.assertTrue(tree.tickets.size() == expected,
                    "Winter ticket count differs from qualified summer foliage at " + root
                    + ": " + tree.tickets.size() + " != " + expected);
                h.assertTrue(tree.tickets.values().stream().noneMatch(ticket -> ticket.consumed),
                    "Winter ticket was consumed early at " + root);
            }
            h.assertTrue(!ledger.find(roots.get(PRUNED)).tickets.containsKey(prunedLeaf[0].asLong()),
                "Pre-shed pruned leaf acquired a seasonal ticket");

            SeasonalLedger.Tree stoneTree = ledger.find(roots.get(STONE));
            SeasonalLedger.Tree waterTree = ledger.find(roots.get(FLUID));
            stonePos[0] = directBranchTicket(level, stoneTree).pos;
            waterPos[0] = directBranchTicket(level, waterTree).pos;
            h.assertTrue(level.setBlock(stonePos[0], Blocks.STONE.defaultBlockState(), 3),
                "Stone obstruction placement failed");
            h.assertTrue(level.setBlock(waterPos[0], Blocks.WATER.defaultBlockState(), 3),
                "Fluid obstruction placement failed");

            BlockPos replacement = roots.get(REPLACED_ROOT);
            SeasonalLedger.Tree oldTree = ledger.find(replacement);
            oldIncarnation[0] = oldTree.incarnation;
            BlockState originalRoot = level.getBlockState(replacement);
            h.assertTrue(originalRoot.getBlock() instanceof RootyBlock, "Native root absent before replacement");
            h.assertTrue(level.setBlock(replacement, Blocks.AIR.defaultBlockState(), 3),
                "Root-to-air replacement failed");
            h.assertTrue(ledger.find(replacement) == null,
                "Root removal hook retained the old incarnation/tickets");
            h.assertTrue(level.setBlock(replacement, originalRoot, 3), "Same-root replacement failed");
            h.assertTrue(SeasonalRuntime.ensureTracked(level, replacement), "Replacement root not tracked");

            SeasonalLedger.Tree branchTree = ledger.find(roots.get(REMOVED_BRANCH));
            SeasonalLedger.Ticket dependent = branchRemovalTicket(level, branchTree);
            cutBranch[0] = dependent.support;
            orphanLeaf[0] = dependent.pos;
            BlockState originalBranch = level.getBlockState(cutBranch[0]);
            h.assertTrue(originalBranch.getBlock() instanceof BranchBlock,
                "Dependent native branch absent before cut");
            h.assertTrue(level.setBlock(cutBranch[0], Blocks.AIR.defaultBlockState(), 2),
                "True branch removal failed");
            h.assertTrue(level.getBlockState(cutBranch[0]).isAir(), "Cut branch remained present");
            h.assertTrue(branchTree.prunedBranchesView().getOrDefault(cutBranch[0].asLong(), Long.MIN_VALUE)
                    >= dependent.targetCycle,
                "Branch removal did not tombstone the dependent restoration cycle");
            h.assertTrue(level.setBlock(cutBranch[0], originalBranch, 2),
                "Same native branch replacement failed");
            h.assertTrue(level.getBlockState(cutBranch[0]).equals(originalBranch),
                "Same native branch state was not restored");
            setPhase(level, 0.4);
        });
        h.runAtTickTime(1700, () -> {
            SeasonalLedger ledger = SeasonalLedger.get(level);
            SeasonalLedger.Tree prunedTree = ledger.find(roots.get(PRUNED));
            h.assertTrue(prunedTree != null && !prunedTree.tickets.containsKey(prunedLeaf[0].asLong()),
                "Pre-shed pruning was repaired from absence");
            restoredLeaf[0] = prunedTree.tickets.values().stream()
                    .filter(ticket -> ticket.outcome == SeasonalLedger.Outcome.RESTORED)
                    .map(ticket -> ticket.pos).findFirst().orElseThrow();
            int ticketCount = prunedTree.tickets.size();
            h.assertTrue(level.destroyBlock(restoredLeaf[0], false), "Restored leaf break failed");
            h.assertTrue(prunedTree.tickets.size() == ticketCount &&
                    prunedTree.tickets.get(restoredLeaf[0].asLong()).consumed,
                "Breaking a restored leaf re-opened its one-use ticket");

            SeasonalLedger.Ticket stoneTicket = ledger.find(roots.get(STONE)).tickets.get(stonePos[0].asLong());
            SeasonalLedger.Ticket waterTicket = ledger.find(roots.get(FLUID)).tickets.get(waterPos[0].asLong());
            h.assertTrue(level.getBlockState(stonePos[0]).is(Blocks.STONE)
                    && stoneTicket.consumed && stoneTicket.outcome == SeasonalLedger.Outcome.OBSTRUCTED,
                "Stone obstruction was overwritten or not resolved as obstructed");
            h.assertTrue(level.getBlockState(waterPos[0]).getFluidState().isSource()
                    && waterTicket.consumed && waterTicket.outcome == SeasonalLedger.Outcome.OBSTRUCTED,
                "Fluid obstruction was overwritten or not resolved as obstructed");

            SeasonalLedger.Tree replacement = ledger.find(roots.get(REPLACED_ROOT));
            h.assertTrue(replacement != null && !replacement.incarnation.equals(oldIncarnation[0])
                    && replacement.tickets.isEmpty(),
                "Root-to-air-to-same-root reused an old incarnation or its historical tickets");
            SeasonalLedger.Ticket orphan = ledger.find(roots.get(REMOVED_BRANCH))
                    .tickets.get(orphanLeaf[0].asLong());
            h.assertTrue(ledger.find(roots.get(REMOVED_BRANCH)).prunedBranchesView()
                    .getOrDefault(cutBranch[0].asLong(), Long.MIN_VALUE) >= orphan.targetCycle,
                "Branch tombstone did not survive same-block replacement");
            h.assertTrue(level.getBlockState(cutBranch[0]).getBlock() instanceof BranchBlock,
                "Same native branch disappeared before dependent-ticket proof");
            h.assertTrue(orphan.consumed && orphan.outcome == SeasonalLedger.Outcome.UNSUPPORTED,
                "Cut-branch dependent ticket was not invalidated: " + orphan.outcome);
            h.assertTrue(level.getBlockState(orphanLeaf[0]).isAir(),
                "Cut-branch dependent foliage was resurrected");
        });
        h.runAtTickTime(1900, () -> {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(roots.get(PRUNED));
            h.assertTrue(tree != null && tree.tickets.get(restoredLeaf[0].asLong()).consumed,
                "Broken restored leaf became pending again");
            h.assertTrue(SeasonalRuntime.inspectSnapshot(level, roots.get(PRUNED)).pending() == 0,
                "Restored-leaf break created a new spring restoration opportunity");
            setPhase(level, 4.0);
            // The disposable fixture owns only tickets it added. GameTest's own
            // forced chunks (and any pre-existing tickets) are left untouched.
            for (ChunkPos chunk : ownedForcedChunks) {
                h.assertTrue(level.getForcedChunks().contains(chunk.toLong()),
                    "Fixture-owned chunk ticket vanished before cleanup: " + chunk);
                h.assertTrue(level.setChunkForced(chunk.x, chunk.z, false),
                    "Could not release fixture-owned chunk ticket: " + chunk);
            }
            ownedForcedChunks.clear();
            h.succeed();
        });
    }

    private static SeasonalLedger.Ticket directBranchTicket(ServerLevel level, SeasonalLedger.Tree tree) {
        return tree.tickets.values().stream()
                .filter(ticket -> level.getBlockState(ticket.support).getBlock() instanceof BranchBlock)
                .findFirst().orElseThrow(() -> new AssertionError("No branch-supported native ticket"));
    }

    private static SeasonalLedger.Ticket branchRemovalTicket(ServerLevel level, SeasonalLedger.Tree tree) {
        return tree.tickets.values().stream()
                .filter(ticket -> level.getBlockState(ticket.support).getBlock() instanceof BranchBlock
                        && ticket.support.getY() > tree.root.getY() + 2)
                .max(Comparator.comparingInt(ticket -> ticket.support.getY()))
                .orElseThrow(() -> new AssertionError("No distal branch-supported native ticket"));
    }

    private static void setPhase(ServerLevel level, double phase) {
        var data = SeasonHandler.getSeasonSavedData(level);
        int duration = SeasonHelper.getSeasonState(level).getSubSeasonDuration();
        data.seasonCycleTicks = (int) (phase * duration);
        data.setDirty();
        SeasonHandler.sendSeasonUpdate(level);
    }

    private static void generate(GameTestHelper h, BlockPos root, int index,
                                 Set<ChunkPos> ownedForcedChunks) {
        ServerLevel level = h.getLevel();
        // GameTest forceLoadChunks covers only a 3x3 area around the template
        // origin even with a wide SNBT. These are fixture workload controls,
        // never addon runtime behavior; only newly forced tickets are owned.
        for (int cx = (root.getX() >> 4) - 2; cx <= (root.getX() >> 4) + 2; cx++)
            for (int cz = (root.getZ() >> 4) - 2; cz <= (root.getZ() >> 4) + 2; cz++) {
                ChunkPos chunk = new ChunkPos(cx, cz);
                if (!level.getForcedChunks().contains(chunk.toLong())) {
                    h.assertTrue(level.setChunkForced(cx, cz, true),
                        "Could not force isolated fixture chunk " + chunk);
                    ownedForcedChunks.add(chunk);
                }
                level.getChunk(cx, cz);
            }
        for (BlockPos p : BlockPos.betweenClosed(root.offset(-10, 0, -10), root.offset(10, 0, 10)))
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
        Species species = Species.REGISTRY.get(BIRCH);
        h.assertTrue(species != null, "Native Dynamic Trees birch registry entry absent");
        var codes = JoCodeRegistry.getCodes(species.getRegistryName());
        int radius = codes.keySet().stream().min(Comparator.comparingInt(r -> Math.abs(r - 5))).orElseThrow();
        JoCode code = codes.get(radius).get(0);
        level.random.setSeed(8675309L + index * 104729L);
        new JoCode(code.toString()).setCareful(true).generate(new GenerationContext(
            LevelContext.create(level), species, root, root.mutable(), level.getBiome(root),
            Direction.NORTH, radius, SafeChunkBounds.ANY));
        h.assertTrue(level.getBlockState(root).getBlock() instanceof RootyBlock,
            "Native JoCode did not create birch root at " + root);
    }

    private static Map<BlockPos, BlockState> findLeaves(ServerLevel level, BlockPos root) {
        return find(level, root, true);
    }

    private static Map<BlockPos, BlockState> findBranches(ServerLevel level, BlockPos root) {
        return find(level, root, false);
    }

    private static Map<BlockPos, BlockState> find(ServerLevel level, BlockPos root, boolean leaves) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(root.offset(-10, 0, -10), root.offset(10, 30, 10))) {
            BlockState state = level.getBlockState(pos);
            if (leaves ? state.getBlock() instanceof DynamicLeavesBlock : state.getBlock() instanceof BranchBlock)
                result.put(pos.immutable(), state);
        }
        return result;
    }
}
