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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Loaded crown, actually unloaded owner root: the native DT growth method must fail closed. */
@GameTestHolder(BetterSeasonalTrees.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeasonalInverseChunkGameTests {
    private static final BlockPos ROOT = new BlockPos(2047, 220, 2047);
    private static final ChunkPos ROOT_CHUNK = new ChunkPos(ROOT);
    private static final ChunkPos CROWN_CHUNK = new ChunkPos(ROOT.east());
    private static final ResourceLocation BIRCH = new ResourceLocation("dynamictrees:birch");
    private static final TicketType<ChunkPos> CROWN_BORDER = TicketType.create(
        "better_seasonal_trees_inverse_crown", Comparator.comparingLong(ChunkPos::toLong));

    private SeasonalInverseChunkGameTests() { }

    @GameTest(batch = "seasonal_chunk_inverse", template = "seasonal_chunk", timeoutTicks = 2200)
    public static void loadedCrownCannotGrowIntoPendingCellWithoutOwner(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        Set<ChunkPos> ownedForced = new LinkedHashSet<>();
        List<ChunkPos> ring = new ArrayList<>();
        for (int cx = ROOT_CHUNK.x - 2; cx <= ROOT_CHUNK.x + 2; cx++)
            for (int cz = ROOT_CHUNK.z - 2; cz <= ROOT_CHUNK.z + 2; cz++)
                ring.add(new ChunkPos(cx, cz));
        int[] baselineCount = new int[1];
        BlockPos[] pendingCell = new BlockPos[1];
        boolean[] crownHeld = new boolean[1];

        h.runAtTickTime(1, () -> {
            setPhase(level, 4.0);
            for (ChunkPos chunk : ring) {
                h.assertTrue(!level.getForcedChunks().contains(chunk.toLong()),
                    "Inverse fixture ring was already forced: " + chunk);
                h.assertTrue(level.setChunkForced(chunk.x, chunk.z, true),
                    "Could not force fixture preparation chunk " + chunk);
                ownedForced.add(chunk);
                level.getChunk(chunk.x, chunk.z);
            }
            generate(h, level);
        });
        for (int settleTick : new int[]{5, 10, 15, 20, 25, 30, 35, 38})
            h.runAtTickTime(settleTick, () -> {
                for (BlockPos pos : leaves(level)) {
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof DynamicLeavesBlock leaves) {
                        leaves.updateHydro(level, pos, state, false);
                        BlockState hydrated = level.getBlockState(pos);
                        if (hydrated.getBlock() instanceof DynamicLeavesBlock live)
                            live.removeIfLightIsInadequate(hydrated, level, pos, level.random);
                    }
                }
            });
        h.runAtTickTime(50, () -> {
            List<BlockPos> canopy = leaves(level);
            baselineCount[0] = canopy.size();
            h.assertTrue(baselineCount[0] >= 20, "Native inverse canopy too small");
            h.assertTrue(canopy.stream().anyMatch(pos -> new ChunkPos(pos).equals(CROWN_CHUNK)),
                "Native inverse canopy did not cross into crown chunk");
            for (BlockPos pos : canopy) {
                BlockState state = level.getBlockState(pos);
                DynamicLeavesBlock leaf = (DynamicLeavesBlock) state.getBlock();
                var properties = leaf.getProperties(state);
                h.assertTrue(leaf.getHydrationLevelFromNeighbors(level, pos, properties) > 0 &&
                    leaf.hasAdequateLight(state, level, properties, pos),
                    "Unqualified native inverse baseline leaf at " + pos);
            }
            h.assertTrue(SeasonalRuntime.ensureTracked(level, ROOT), "Inverse native root not tracked");
            setPhase(level, 9.1);
        });
        h.runAtTickTime(500, () -> {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            h.assertTrue(tree != null && tree.tickets.size() == baselineCount[0] && leaves(level).isEmpty(),
                "Inverse winter tickets do not match native canopy");
            h.assertTrue(tree.tickets.values().stream().noneMatch(ticket -> ticket.consumed),
                "Inverse winter ticket consumed before spring");
            setPhase(level, 0.25);
        });
        h.runAtTickTime(900, () -> {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            h.assertTrue(tree != null, "Inverse root ledger missing before unload");
            SeasonalLedger.Ticket qualified = tree.tickets.values().stream()
                .filter(ticket -> !ticket.consumed && new ChunkPos(ticket.pos).equals(CROWN_CHUNK)
                    && ticket.pos.getX() >= CROWN_CHUNK.getMinBlockX() + 1
                    && ticket.pos.getX() <= CROWN_CHUNK.getMaxBlockX() - 1
                    && ticket.pos.getZ() >= CROWN_CHUNK.getMinBlockZ() + 1
                    && ticket.pos.getZ() <= CROWN_CHUNK.getMaxBlockZ() - 1
                    && level.getBlockState(ticket.pos).isAir())
                .filter(ticket -> nativeSiteQualified(level, ticket))
                .findFirst().orElseThrow(() -> new AssertionError(
                    "No native-qualified pending crown cell before inverse unload"));
            pendingCell[0] = qualified.pos;
            h.assertTrue(SeasonalLedger.get(level).pendingTicketOwnersAt(pendingCell[0])
                    .contains(ROOT.asLong()), "Pending crown position lacks persisted owner index");
            level.getChunkSource().addRegionTicket(CROWN_BORDER, CROWN_CHUNK, 0, CROWN_CHUNK);
            crownHeld[0] = true;
            for (ChunkPos chunk : ownedForced) {
                h.assertTrue(level.setChunkForced(chunk.x, chunk.z, false),
                    "Could not release inverse preparation ticket " + chunk);
            }
            ownedForced.clear();
        });
        h.runAtTickTime(1300, () -> {
            h.assertTrue(level.getChunkSource().hasChunk(CROWN_CHUNK.x, CROWN_CHUNK.z) &&
                    level.getChunkSource().getChunkNow(CROWN_CHUNK.x, CROWN_CHUNK.z) != null,
                "Inverse crown chunk is not actually FULL-loaded");
            h.assertTrue(!level.getChunkSource().hasChunk(ROOT_CHUNK.x, ROOT_CHUNK.z) &&
                    level.getChunkSource().getChunkNow(ROOT_CHUNK.x, ROOT_CHUNK.z) == null,
                "Owner root chunk did not actually unload; inverse proof inconclusive");
            SeasonalLedger.Ticket ticket = SeasonalLedger.get(level).find(ROOT)
                .tickets.get(pendingCell[0].asLong());
            h.assertTrue(ticket != null && !ticket.consumed && level.getBlockState(pendingCell[0]).isAir(),
                "Pending crown cell changed before native growth attempt");
            h.assertTrue(nativeSiteQualified(level, ticket),
                "Pending crown cell is not natively suitable with owner unloaded");
            h.assertTrue(level.getChunkSource().getChunkNow(ROOT_CHUNK.x, ROOT_CHUNK.z) == null,
                "Native suitability check force-loaded absent owner root chunk");
            for (int attempt = 0; attempt < 8; attempt++) {
                DynamicLeavesBlock leaves = (DynamicLeavesBlock) ticket.state.getBlock();
                var properties = leaves.getProperties(ticket.state);
                int hydration = leaves.getHydrationLevelFromNeighbors(level, ticket.pos, properties);
                boolean grew = leaves.growLeavesIfLocationIsSuitable(level, properties,
                    ticket.pos, Integer.valueOf(hydration));
                h.assertTrue(!grew && level.getBlockState(ticket.pos).isAir() && !ticket.consumed,
                    "Native DT growth filled or consumed a pending crown cell without its owner");
                h.assertTrue(level.getChunkSource().getChunkNow(ROOT_CHUNK.x, ROOT_CHUNK.z) == null,
                    "Native growth attempt force-loaded absent owner root chunk");
            }
            h.assertTrue(SeasonalLedger.get(level).pendingTicketOwnersAt(pendingCell[0])
                    .contains(ROOT.asLong()), "Native growth lost pending owner provenance");
            if (crownHeld[0]) {
                level.getChunkSource().removeRegionTicket(CROWN_BORDER, CROWN_CHUNK, 0, CROWN_CHUNK);
                crownHeld[0] = false;
            }
            setPhase(level, 4.0);
            h.succeed();
        });
    }

    private static boolean nativeSiteQualified(ServerLevel level, SeasonalLedger.Ticket ticket) {
        if (!(ticket.state.getBlock() instanceof DynamicLeavesBlock leaves)) return false;
        var properties = leaves.getProperties(ticket.state);
        return leaves.isLocationSuitableForNewLeaves(level, properties, ticket.pos)
            && leaves.hasAdequateLight(ticket.state, level, properties, ticket.pos)
            && leaves.getHydrationLevelFromNeighbors(level, ticket.pos, properties) > 0;
    }

    private static void generate(GameTestHelper h, ServerLevel level) {
        for (BlockPos pos : BlockPos.betweenClosed(ROOT.offset(-10, 0, -10), ROOT.offset(10, 0, 10)))
            level.setBlock(pos, Blocks.DIRT.defaultBlockState(), 2);
        Species species = Species.REGISTRY.get(BIRCH);
        h.assertTrue(species != null, "Native inverse birch species absent");
        var codes = JoCodeRegistry.getCodes(BIRCH);
        int radius = codes.keySet().stream().min(Comparator.comparingInt(r -> Math.abs(r - 5))).orElseThrow();
        JoCode code = codes.get(radius).get(0);
        level.random.setSeed(8675309L + ROOT.asLong());
        new JoCode(code.toString()).setCareful(true).generate(new GenerationContext(
            LevelContext.create(level), species, ROOT, ROOT.mutable(), level.getBiome(ROOT),
            Direction.NORTH, radius, SafeChunkBounds.ANY));
        h.assertTrue(level.getBlockState(ROOT).getBlock() instanceof RootyBlock,
            "Native inverse JoCode failed to create root");
    }

    private static List<BlockPos> leaves(ServerLevel level) {
        List<BlockPos> result = new ArrayList<>();
        for (BlockPos pos : BlockPos.betweenClosed(ROOT.offset(-10, 0, -10), ROOT.offset(10, 30, 10)))
            if (level.getBlockState(pos).getBlock() instanceof DynamicLeavesBlock) result.add(pos.immutable());
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
