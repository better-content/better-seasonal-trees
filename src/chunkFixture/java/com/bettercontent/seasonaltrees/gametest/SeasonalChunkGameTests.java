package com.bettercontent.seasonaltrees.gametest;

import com.bettercontent.seasonaltrees.BetterSeasonalTrees;
import com.bettercontent.seasonaltrees.runtime.SeasonalLedger;
import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
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
import net.minecraft.server.level.TicketType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.season.SeasonHandler;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Disposable isolated-world fixture: native cross-chunk canopy and actual loaded-state interruption. */
@GameTestHolder(BetterSeasonalTrees.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeasonalChunkGameTests {
    private static final BlockPos ROOT = new BlockPos(1023, 220, 1023);
    private static final ChunkPos ROOT_CHUNK = new ChunkPos(ROOT);
    private static final ChunkPos EAST_CHUNK = new ChunkPos(ROOT.east());
    private static final ResourceLocation BIRCH = new ResourceLocation("dynamictrees:birch");
    private static final TicketType<ChunkPos> ROOT_BORDER = TicketType.create(
        "better_seasonal_trees_chunk_fixture_border", Comparator.comparingLong(ChunkPos::toLong));

    private SeasonalChunkGameTests() { }

    @GameTest(batch = "seasonal_chunk", template = "seasonal_chunk", timeoutTicks = 2200)
    public static void interruptedCrossChunkCanopyDefersAndReenters(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Set<ChunkPos> ownedForced = new LinkedHashSet<>();
        Set<ChunkPos> ring = new LinkedHashSet<>();
        for (int cx = ROOT_CHUNK.x - 2; cx <= ROOT_CHUNK.x + 2; cx++)
            for (int cz = ROOT_CHUNK.z - 2; cz <= ROOT_CHUNK.z + 2; cz++)
                ring.add(new ChunkPos(cx, cz));
        Map<BlockPos, BlockState> summer = new LinkedHashMap<>();
        UUID[] incarnation = new UUID[1];
        int[] eastTickets = new int[1];
        boolean[] borderHeld = new boolean[1];

        h.runAtTickTime(1, () -> {
            setPhase(level, 4.0);
            holdForcedRing(h, level, ring, ownedForced);
            generate(h, level);
        });
        for (int settleTick : new int[]{5, 10, 15, 20, 25, 30, 35, 38})
            h.runAtTickTime(settleTick, () -> {
                for (var entry : leaves(level).entrySet()) {
                    BlockState state = level.getBlockState(entry.getKey());
                    if (state.getBlock() instanceof DynamicLeavesBlock leaf) {
                        leaf.updateHydro(level, entry.getKey(), state, false);
                        BlockState current = level.getBlockState(entry.getKey());
                        if (current.getBlock() instanceof DynamicLeavesBlock live)
                            live.removeIfLightIsInadequate(current, level, entry.getKey(), level.random);
                    }
                }
            });
        h.runAtTickTime(50, () -> {
            summer.putAll(leaves(level));
            h.assertTrue(summer.size() >= 20, "Native cross-chunk birch canopy too small");
            h.assertTrue(summer.keySet().stream().anyMatch(pos -> new ChunkPos(pos).equals(EAST_CHUNK)),
                "Native canopy did not cross into eastern chunk");
            for (var entry : summer.entrySet()) {
                DynamicLeavesBlock leaf = (DynamicLeavesBlock) entry.getValue().getBlock();
                var properties = leaf.getProperties(entry.getValue());
                h.assertTrue(leaf.getHydrationLevelFromNeighbors(level, entry.getKey(), properties) > 0
                    && leaf.hasAdequateLight(entry.getValue(), level, properties, entry.getKey()),
                    "Unqualified native summer leaf at " + entry.getKey());
            }
            h.assertTrue(SeasonalRuntime.ensureTracked(level, ROOT), "Native root not tracked");
            setPhase(level, 9.1);
        });
        h.runAtTickTime(500, () -> {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            h.assertTrue(tree != null && tree.tickets.size() == summer.size() && leaves(level).isEmpty(),
                "Winter provenance does not match the native cross-chunk canopy");
            h.assertTrue(tree.tickets.values().stream().noneMatch(ticket -> ticket.consumed),
                "Winter tickets consumed before interruption");
            incarnation[0] = tree.incarnation;
            eastTickets[0] = (int) tree.tickets.values().stream()
                .filter(ticket -> new ChunkPos(ticket.pos).equals(EAST_CHUNK)).count();
            h.assertTrue(eastTickets[0] > 0, "No east-chunk ticket to defer");

            // Fixture-only FULL border ticket (level 33), not FORCED level 31:
            // the root remains loaded but does not keep its adjacent crown FULL.
            level.getChunkSource().addRegionTicket(ROOT_BORDER, ROOT_CHUNK, 0, ROOT_CHUNK);
            borderHeld[0] = true;
            for (ChunkPos chunk : ownedForced) {
                h.assertTrue(level.setChunkForced(chunk.x, chunk.z, false),
                    "Could not release fixture generation ticket " + chunk);
            }
            ownedForced.clear();
        });
        h.runAtTickTime(900, () -> {
            h.assertTrue(level.getChunkSource().hasChunk(ROOT_CHUNK.x, ROOT_CHUNK.z)
                && level.getChunkSource().getChunkNow(ROOT_CHUNK.x, ROOT_CHUNK.z) != null,
                "Border ticket failed to keep root chunk FULL");
            h.assertTrue(!level.getChunkSource().hasChunk(EAST_CHUNK.x, EAST_CHUNK.z)
                && level.getChunkSource().getChunkNow(EAST_CHUNK.x, EAST_CHUNK.z) == null,
                "Eastern canopy chunk did not actually unload; interruption inconclusive");
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            h.assertTrue(tree != null && tree.incarnation.equals(incarnation[0]),
                "Root incarnation changed during chunk interruption");
            h.assertTrue(tree.tickets.values().stream()
                .filter(ticket -> new ChunkPos(ticket.pos).equals(EAST_CHUNK))
                .allMatch(ticket -> !ticket.consumed),
                "Unloaded east-chunk ticket consumed prematurely");
            setPhase(level, 0.4);
        });
        h.runAtTickTime(1100, () -> {
            h.assertTrue(level.getChunkSource().getChunkNow(EAST_CHUNK.x, EAST_CHUNK.z) == null,
                "Eastern canopy reloaded before deliberate re-entry");
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            long pendingEast = tree.tickets.values().stream()
                .filter(ticket -> new ChunkPos(ticket.pos).equals(EAST_CHUNK) && !ticket.consumed).count();
            h.assertTrue(pendingEast == eastTickets[0],
                "Unloaded east-chunk tickets did not defer across spring: " + pendingEast);
            holdForcedRing(h, level, ring, ownedForced);
            h.assertTrue(SeasonalRuntime.ensureTracked(level, ROOT), "Re-entered native root not admitted");
            h.assertTrue(SeasonalLedger.get(level).find(ROOT).incarnation.equals(incarnation[0]),
                "Re-entry replaced saved root incarnation");
        });
        h.runAtTickTime(1800, () -> {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            h.assertTrue(tree != null && tree.incarnation.equals(incarnation[0])
                && tree.tickets.size() == summer.size(), "Cross-chunk provenance changed after re-entry");
            h.assertTrue(tree.tickets.values().stream().allMatch(ticket -> ticket.consumed),
                "Cross-chunk restoration left pending tickets");
            h.assertTrue(leaves(level).keySet().equals(summer.keySet()),
                "Native cross-chunk canopy not fully restored after re-entry");
            for (ChunkPos chunk : ownedForced) {
                h.assertTrue(level.setChunkForced(chunk.x, chunk.z, false),
                    "Could not release fixture re-entry ticket " + chunk);
            }
            ownedForced.clear();
            if (borderHeld[0]) {
                level.getChunkSource().removeRegionTicket(ROOT_BORDER, ROOT_CHUNK, 0, ROOT_CHUNK);
                borderHeld[0] = false;
            }
            setPhase(level, 4.0);
            h.succeed();
        });
    }

    private static void holdForcedRing(GameTestHelper h, ServerLevel level,
                                       Set<ChunkPos> ring, Set<ChunkPos> owned) {
        for (ChunkPos chunk : ring) {
            h.assertTrue(!level.getForcedChunks().contains(chunk.toLong()),
                "Fixture requires an uncontaminated far-away chunk ring: " + chunk);
            h.assertTrue(level.setChunkForced(chunk.x, chunk.z, true),
                "Could not force fixture preparation chunk " + chunk);
            owned.add(chunk);
            level.getChunk(chunk.x, chunk.z);
        }
    }

    private static void generate(GameTestHelper h, ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(ROOT.offset(-10, 0, -10), ROOT.offset(10, 0, 10)))
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
        Species species = Species.REGISTRY.get(BIRCH);
        h.assertTrue(species != null, "Native birch species absent");
        var codes = JoCodeRegistry.getCodes(species.getRegistryName());
        int radius = codes.keySet().stream().min(Comparator.comparingInt(r -> Math.abs(r - 5))).orElseThrow();
        JoCode code = codes.get(radius).get(0);
        level.random.setSeed(8675309L + ROOT.asLong());
        new JoCode(code.toString()).setCareful(true).generate(new GenerationContext(
            LevelContext.create(level), species, ROOT, ROOT.mutable(), level.getBiome(ROOT),
            Direction.NORTH, radius, SafeChunkBounds.ANY));
        h.assertTrue(level.getBlockState(ROOT).getBlock() instanceof RootyBlock,
            "Native JoCode failed to create cross-chunk root");
    }

    private static Map<BlockPos, BlockState> leaves(ServerLevel level) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(ROOT.offset(-10, 0, -10), ROOT.offset(10, 30, 10))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DynamicLeavesBlock) result.put(pos.immutable(), state);
        }
        return result;
    }

    private static void setPhase(ServerLevel level, double phase) {
        var data = SeasonHandler.getSeasonSavedData(level);
        int duration = SeasonHelper.getSeasonState(level).getSubSeasonDuration();
        data.seasonCycleTicks = (int) (phase * duration);
        data.setDirty();
        SeasonHandler.sendSeasonUpdate(level);
    }
}
