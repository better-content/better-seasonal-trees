package com.bettercontent.seasonaltrees.gametest;

import com.bettercontent.seasonaltrees.BetterSeasonalTrees;
import com.bettercontent.seasonaltrees.catalogue.SeasonalCatalogue;
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
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.season.SeasonHandler;

import java.util.*;

/** Single serial native scenario: SS calendar is dimension-global, so phase tests cannot run in parallel. */
@GameTestHolder(BetterSeasonalTrees.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeasonalPilotGameTests {
    @GameTest(template = "empty", timeoutTicks = 2100)
    public static void nativeBirchCherryDormancyAndOneUseRestoration(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        int[] maxWork = new int[2];
        h.onEachTick(() -> {
            var budget = SeasonalRuntime.budgetSnapshot();
            h.assertTrue(budget.mutationLimit() == 64 && budget.inspectionLimit() == 2048,
                    "Fixture must qualify shipped budgets, not raised limits: " + budget);
            h.assertTrue(budget.mutations() >= 0 && budget.mutations() <= 64
                    && budget.inspections() >= 0 && budget.inspections() <= 2048,
                    "Global seasonal work cap exceeded: " + budget);
            maxWork[0] = Math.max(maxWork[0], budget.mutations());
            maxWork[1] = Math.max(maxWork[1], budget.inspections());
        });
        // Userdev anchors its template below terrain; place the isolated native-tree scene above it.
        List<BlockPos> roots = List.of(h.absolutePos(new BlockPos(10, 2, 10)).atY(180),
                h.absolutePos(new BlockPos(34, 2, 10)).atY(180), h.absolutePos(new BlockPos(58, 2, 10)).atY(180));
        String[] species = {"birch", "cherry", "spruce"};
        List<Map<BlockPos, BlockState>> originalLeaves = new ArrayList<>();
        List<Map<BlockPos, BlockState>> originalBranches = new ArrayList<>();
        Map<BlockPos, String> nativeEligibility = new HashMap<>();
        BlockPos[] lightProbe = new BlockPos[2];
        int[] beforeLight = new int[2];
        BlockPos[] prunedAfterRestore = new BlockPos[2];
        h.runAtTickTime(1, () -> {
            h.assertTrue(SeasonalCatalogue.all().size() == 3, "Gate A must load exactly the three pilot profiles");
            var commands = level.getServer().getCommands();
            var operator = level.getServer().createCommandSourceStack().withPermission(2);
            var command = commands.getDispatcher().getRoot().getChild("seasonaltrees");
            h.assertTrue(command != null && command.canUse(operator)
                    && !command.canUse(operator.withPermission(0)), "Seasonal commands must be operator-only");
            h.assertTrue(commands.performPrefixedCommand(operator, "seasonaltrees validate") == 3,
                    "Live catalogue/leaf-reference validation failed");
            setPhase(level, 4.0);
            for (int i = 0; i < roots.size(); i++) generate(h, roots.get(i), species[i]);
        });
        // Qualify native support before taking the seasonal baseline: JoCode can leave
        // newly expanded edge leaves outside its aged voxel map.
        for (int settleTick : new int[]{5, 10, 15, 20, 25, 30, 35, 38}) h.runAtTickTime(settleTick, () -> {
            for (BlockPos root : roots) {
                level.random.setSeed(8675309L + settleTick * 31L + root.asLong());
                for (var entry : find(level, root, true).entrySet()) {
                    BlockState current = level.getBlockState(entry.getKey());
                    if (current.getBlock() instanceof DynamicLeavesBlock leaves) {
                        leaves.updateHydro(level, entry.getKey(), current, false);
                        BlockState hydrated = level.getBlockState(entry.getKey());
                        if (hydrated.getBlock() instanceof DynamicLeavesBlock live)
                            live.removeIfLightIsInadequate(hydrated, level, entry.getKey(), level.random);
                    }
                }
            }
        });
        h.runAtTickTime(40, () -> {
            for (int i = 0; i < roots.size(); i++) {
                Map<BlockPos, BlockState> leaves = find(level, roots.get(i), true);
                Map<BlockPos, BlockState> branches = find(level, roots.get(i), false);
                h.assertTrue(leaves.size() >= 20, "Native generation produced insufficient " + species[i] + " foliage: " + leaves.size());
                h.assertTrue(branches.size() >= 5, "Native generation produced insufficient branch network");
                originalLeaves.add(leaves); originalBranches.add(branches);
                leaves.forEach((pos, leafState) -> {
                    DynamicLeavesBlock leaf = (DynamicLeavesBlock) leafState.getBlock();
                    var properties = leaf.getProperties(leafState);
                    String neighbors = Arrays.stream(Direction.values()).map(d -> d + "=" + level.getBlockState(pos.relative(d))).toList().toString();
                    int hydration = leaf.getHydrationLevelFromNeighbors(level, pos, properties);
                    h.assertTrue(hydration > 0, "Native baseline still has unsupported foliage at " + pos);
                    h.assertTrue(leaf.hasAdequateLight(leafState, level, properties, pos), "Native baseline has unsuitable survival light at " + pos);
                    nativeEligibility.put(pos, "hydration=" + hydration
                            + " lightSuitable=" + leaf.hasAdequateLight(leafState, level, properties, pos) + " neighbors=" + neighbors);
                });
                if (i < 2) {
                    lightProbe[i] = leaves.keySet().stream()
                            .filter(p -> level.getBrightness(LightLayer.SKY, p) < 15)
                            .max(Comparator.comparingInt(BlockPos::getY)).orElseThrow(() -> new AssertionError("No shaded canopy light probe"));
                    beforeLight[i] = level.getBrightness(LightLayer.SKY, lightProbe[i]);
                    h.assertTrue(SeasonalRuntime.ensureTracked(level, roots.get(i)), "Native pilot root not admitted");
                } else h.assertTrue(!SeasonalRuntime.ensureTracked(level, roots.get(i)), "Evergreen control admitted for shedding");
            }
            setPhase(level, 9.1);
        });
        h.runAtTickTime(400, () -> {
            for (int i = 0; i < 2; i++) {
                var state = SeasonalRuntime.inspectSnapshot(level, roots.get(i));
                int remaining = find(level, roots.get(i), true).size();
                h.assertTrue(remaining == 0, "Winter canopy still has " + remaining + " leaves: " + species[i] + " " + state);
                for (BlockPos formerLeaf : originalLeaves.get(i).keySet()) {
                    BlockState empty = level.getBlockState(formerLeaf);
                    h.assertTrue(empty.isAir() && empty.getCollisionShape(level, formerLeaf).isEmpty(),
                            "Winter canopy left an invisible or colliding placeholder at " + formerLeaf);
                }
                h.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(roots.get(i).offset(-12, -2, -12), roots.get(i).offset(12, 32, 12))).isEmpty(),
                        "Seasonal shedding produced harvest/seed/leaf item entities");
                var record = com.bettercontent.seasonaltrees.runtime.SeasonalLedger.get(level).find(roots.get(i));
                if (record != null) for (var entry : originalLeaves.get(i).entrySet())
                    if (!record.tickets.containsKey(entry.getKey().asLong()))
                        System.out.println("SEASONAL_MISSING_TICKET " + species[i] + " pos=" + entry.getKey() + " baseline=" + entry.getValue() + " current=" + level.getBlockState(entry.getKey()) + " nativeBaseline=" + nativeEligibility.get(entry.getKey()));
                h.assertTrue(state.tickets() == originalLeaves.get(i).size(), "Seasonal provenance lost leaves: " + species[i] + " " + state + " expected=" + originalLeaves.get(i).size());
                h.assertTrue(level.getBlockState(roots.get(i)).getBlock() instanceof RootyBlock, "Dormancy killed root");
                h.assertTrue(find(level, roots.get(i), false).equals(originalBranches.get(i)), "Dormancy changed wood network");
                h.assertTrue(level.getBrightness(LightLayer.SKY, lightProbe[i]) > beforeLight[i], "Real shedding did not admit skylight");
            }
            h.assertTrue(find(level, roots.get(2), true).equals(originalLeaves.get(2)), "Spruce canopy altered");
            setPhase(level, 0.20);
        });
        h.runAtTickTime(650, () -> {
            h.assertTrue(find(level, roots.get(0), true).size() > 0, "Birch did not leaf out in early spring");
            h.assertTrue(find(level, roots.get(1), true).isEmpty(), "Cherry gained physical leaves during blossom-only phase");
            setPhase(level, 1.0 / 3.0);
        });
        h.runAtTickTime(850, () -> {
            var record = com.bettercontent.seasonaltrees.runtime.SeasonalLedger.get(level).find(roots.get(0));
            for (var ticket : record.tickets.values()) if (level.getBlockState(ticket.pos).getBlock() != ticket.state.getBlock())
                System.out.println("SEASONAL_DAY7_GAP pos=" + ticket.pos + " outcome=" + ticket.outcome + " suitabilityMask=" + ticket.suitabilityMask + " support=" + ticket.support + "=" + level.getBlockState(ticket.support));
            h.assertTrue(find(level, roots.get(0), true).keySet().containsAll(originalLeaves.get(0).keySet()),
                    "Birch missed its seven-day opportunity window: " + SeasonalRuntime.inspectSnapshot(level, roots.get(0)));
            setPhase(level, 2.0 / 3.0);
        });
        h.runAtTickTime(1050, () -> {
            for (int i = 0; i < 2; i++) {
                Map<BlockPos, BlockState> after = find(level, roots.get(i), true);
                var state = SeasonalRuntime.inspectSnapshot(level, roots.get(i));
                for (BlockPos missing : originalLeaves.get(i).keySet()) if (!after.containsKey(missing)) {
                    var ticket = com.bettercontent.seasonaltrees.runtime.SeasonalLedger.get(level).find(roots.get(i)).tickets.get(missing.asLong());
                    DynamicLeavesBlock leaf = (DynamicLeavesBlock) originalLeaves.get(i).get(missing).getBlock();
                    var properties = leaf.getProperties(originalLeaves.get(i).get(missing));
                    System.out.println("SEASONAL_RESTORE_GAP species=" + species[i] + " pos=" + missing + " ticketSupport=" + (ticket == null ? "none" : ticket.support + "=" + level.getBlockState(ticket.support) + " outcome=" + ticket.outcome + " suitabilityMask=" + ticket.suitabilityMask)
                            + " nativeHydrationNow=" + leaf.getHydrationLevelFromNeighbors(level, missing, properties)
                            + " nativeBaseline=" + nativeEligibility.get(missing));
                }
                h.assertTrue(after.keySet().equals(originalLeaves.get(i).keySet()), "Native canopy did not fully recover: " + species[i] + " actual=" + after.size() + " expected=" + originalLeaves.get(i).size() + " " + state);
                h.assertTrue(state.pending() == 0 && state.consumed() == state.tickets(), "Restoration attempts not consumed");
                h.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                        new net.minecraft.world.phys.AABB(roots.get(i).offset(-12, -2, -12), roots.get(i).offset(12, 32, 12))).isEmpty(),
                        "Seasonal restoration produced harvest/seed/leaf item entities");
                // Explicit native hydration/maintenance, not simply counting freshly placed unstable leaves.
                for (var entry : after.entrySet()) {
                    var block = (DynamicLeavesBlock) entry.getValue().getBlock();
                    block.updateHydro(level, entry.getKey(), level.getBlockState(entry.getKey()), false);
                }
            }
        });
        h.runAtTickTime(1200, () -> {
            for (int i = 0; i < 2; i++) {
                h.assertTrue(find(level, roots.get(i), true).keySet().equals(originalLeaves.get(i).keySet()), "Restored canopy died during native hydration maintenance");
                BlockPos target = originalLeaves.get(i).keySet().stream().max(Comparator.comparingInt(BlockPos::getY)).orElseThrow();
                prunedAfterRestore[i] = target;
                level.destroyBlock(target, false);
            }
        });
        h.runAtTickTime(1400, () -> {
            for (int i = 0; i < 2; i++) {
                // Native DT maintenance is allowed; only the seasonal opportunity ledger must remain consumed.
                var snapshot = SeasonalRuntime.inspectSnapshot(level, roots.get(i));
                h.assertTrue(snapshot.pending() == 0 && snapshot.consumed() == snapshot.tickets(), "Pruning created new restoration opportunities");
                // Remove again immediately before next capture, so native maintenance cannot confound provenance.
                level.destroyBlock(prunedAfterRestore[i], false);
            }
            // The next autumn capture must not manufacture historical foliage where the player pruned.
            setPhase(level, 9.2);
        });
        h.runAtTickTime(1800, () -> {
            for (int i = 0; i < 2; i++) {
                var state = SeasonalRuntime.inspectSnapshot(level, roots.get(i));
                h.assertTrue(find(level, roots.get(i), true).isEmpty(), "Second winter failed");
                h.assertTrue(state.pending() <= originalLeaves.get(i).size() - 1, "Pruned leaf acquired next-year ticket");
            }
            System.out.println("SEASONAL_NATIVE_BUDGET maxima=" + maxWork[0] + "/" + maxWork[1] + " limits=64/2048");
            setPhase(level, 4.0);
            h.succeed();
        });
    }

    static void setPhase(ServerLevel level, double phase) {
        var data = SeasonHandler.getSeasonSavedData(level);
        int duration = SeasonHelper.getSeasonState(level).getSubSeasonDuration();
        data.seasonCycleTicks = (int) (phase * duration);
        data.setDirty();
        SeasonHandler.sendSeasonUpdate(level);
    }
    static void generate(GameTestHelper h, BlockPos root, String name) {
        ServerLevel level = h.getLevel();
        // Explicit fixture loading is not addon behavior; isolate a fully loaded crown + native safety ring.
        for (int cx = (root.getX() >> 4) - 2; cx <= (root.getX() >> 4) + 2; cx++)
            for (int cz = (root.getZ() >> 4) - 2; cz <= (root.getZ() >> 4) + 2; cz++) level.getChunk(cx, cz);
        for (BlockPos p : BlockPos.betweenClosed(root.offset(-10, 0, -10), root.offset(10, 0, 10)))
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
        Species species = Species.REGISTRY.get(new ResourceLocation("dynamictrees", name));
        var allCodes = JoCodeRegistry.getCodes(species.getRegistryName());
        int radius = allCodes.keySet().stream().min(Comparator.comparingInt(r -> Math.abs(r - 5))).orElseThrow();
        JoCode nativeCode = allCodes.get(radius).get(0);
        level.random.setSeed(8675309L + name.hashCode());
        new JoCode(nativeCode.toString()) {
            @Override protected void generateAndAgeLeaves(GenerationContext context, com.ferreusveritas.dynamictrees.util.SimpleVoxmap map, boolean worldGen) {
                int cells = 0;
                for (var cell : map.getAllNonZeroCells((byte)15)) cells++;
                System.out.println("SEASONAL_NATIVE_LEAFMAP " + name + " cells=" + cells + " worldGen=" + worldGen + " light=" + level.getBrightness(LightLayer.SKY, root.above(5)) + " surface=" + level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, root.getX(), root.getZ()));
                super.generateAndAgeLeaves(context, map, worldGen);
            }
        }.setCareful(true).generate(new GenerationContext(
                LevelContext.create(level), species, root, root.mutable(), level.getBiome(root), Direction.NORTH,
                radius, SafeChunkBounds.ANY));
        System.out.println("SEASONAL_NATIVE_GENERATION species=" + name + " root=" + root + " radius=" + radius + " code=" + nativeCode + " phase=" + com.bettercontent.seasonaltrees.scheduler.SeasonClock.read(level) + " branches=" + find(level, root, false).size() + " leaves=" + find(level, root, true).size() + " leafType=" + species.getLeavesBlock());
        h.assertTrue(level.getBlockState(root).getBlock() instanceof RootyBlock, "Native JoCode did not create " + name + " root");
    }
    static Map<BlockPos, BlockState> find(ServerLevel level, BlockPos root, boolean leaves) {
        Map<BlockPos, BlockState> result = new LinkedHashMap<>();
        for (BlockPos p : BlockPos.betweenClosed(root.offset(-10, 0, -10), root.offset(10, 30, 10))) {
            BlockState state = level.getBlockState(p);
            if (leaves ? state.getBlock() instanceof DynamicLeavesBlock : state.getBlock() instanceof BranchBlock)
                result.put(p.immutable(), state);
        }
        return result;
    }
}
