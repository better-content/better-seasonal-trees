package com.bettercontent.seasonaltrees.gametest;

import com.bettercontent.seasonaltrees.BetterSeasonalTrees;
import com.bettercontent.seasonaltrees.runtime.SeasonalLedger;
import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FireBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import sereneseasons.api.season.SeasonHelper;

import java.util.*;

import static com.bettercontent.seasonaltrees.gametest.SeasonalPilotGameTests.*;

/** Actual native fire, explosion, branch felling and container preservation; isolated fixture only. */
@GameTestHolder(BetterSeasonalTrees.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeasonalDamageGameTests {
    @GameTest(template = "empty", batch = "seasonal_damage", timeoutTicks = 1800)
    public static void nativeDamageDoesNotBecomeRestoration(GameTestHelper h) {
        ServerLevel level = h.getLevel();
        List<BlockPos> roots = List.of(new BlockPos(2048, 220, 2048), new BlockPos(2096, 220, 2048),
                new BlockPos(2144, 220, 2048), new BlockPos(2192, 220, 2048));
        Set<ChunkPos> ownedTickets = new LinkedHashSet<>();
        int[] original = new int[4];
        SeasonalLedger.Ticket[] damaged = new SeasonalLedger.Ticket[2];
        BlockPos[] fire = new BlockPos[1];
        BlockPos[] container = new BlockPos[1];
        CompoundTag[] containerData = new CompoundTag[1];
        UUID[] felledIncarnation = new UUID[1];
        Map<BlockPos, BlockState> controlWood = new LinkedHashMap<>();
        double[] heldPhase = {Double.NaN};
        h.onEachTick(() -> {
            // Isolate restoration fertility from legitimate native structural growth after leaf-out.
            // This is fixture calendar control, not a production setting or random-tick change.
            if (Double.isFinite(heldPhase[0])) {
                var clock = sereneseasons.season.SeasonHandler.getSeasonSavedData(level);
                clock.seasonCycleTicks = (int) (heldPhase[0]
                        * SeasonHelper.getSeasonState(level).getSubSeasonDuration());
            }
        });
        h.runAtTickTime(1, () -> {
            setPhase(level, 4.0);
            level.setWeatherParameters(6000, 0, false, false);
            for (BlockPos root : roots) {
                for (int x = (root.getX() >> 4) - 2; x <= (root.getX() >> 4) + 2; x++)
                    for (int z = (root.getZ() >> 4) - 2; z <= (root.getZ() >> 4) + 2; z++) {
                        ChunkPos chunk = new ChunkPos(x, z);
                        if (!level.getForcedChunks().contains(chunk.toLong()) && ownedTickets.add(chunk))
                            level.setChunkForced(x, z, true);
                    }
                generate(h, root, "birch");
            }
        });
        for (int time : new int[]{5, 10, 15, 20, 25, 30, 35, 38}) h.runAtTickTime(time, () -> {
            for (BlockPos root : roots) for (BlockPos pos : find(level, root, true).keySet()) {
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof DynamicLeavesBlock leaves) {
                    leaves.updateHydro(level, pos, state, false);
                    state = level.getBlockState(pos);
                    if (state.getBlock() instanceof DynamicLeavesBlock live)
                        live.removeIfLightIsInadequate(state, level, pos, level.random);
                }
            }
        });
        h.runAtTickTime(40, () -> {
            for (int i = 0; i < roots.size(); i++) {
                var leaves = find(level, roots.get(i), true);
                original[i] = leaves.size();
                h.assertTrue(original[i] > 20, "Damage fixture lacks mature native foliage");
                for (var entry : leaves.entrySet()) {
                    DynamicLeavesBlock block = (DynamicLeavesBlock) entry.getValue().getBlock();
                    var properties = block.getProperties(entry.getValue());
                    h.assertTrue(block.getHydrationLevelFromNeighbors(level, entry.getKey(), properties) > 0
                            && block.hasAdequateLight(entry.getValue(), level, properties, entry.getKey()),
                            "Damage fixture baseline is not natively supported");
                }
                h.assertTrue(SeasonalRuntime.ensureTracked(level, roots.get(i)), "Damage root not tracked");
            }
            // Positive fertility makes an accidental restoration debit observable. Native growth remains
            // dormant until the last due deadline, selected below strictly inside the leaf-out window.
            BlockPos control = roots.get(3);
            level.setBlock(control, level.getBlockState(control).setValue(RootyBlock.FERTILITY, 4), 2);
            controlWood.putAll(find(level, control, false));
            setPhase(level, 9.1);
        });
        h.runAtTickTime(500, () -> {
            for (int i = 0; i < roots.size(); i++) {
                var tree = SeasonalLedger.get(level).find(roots.get(i));
                h.assertTrue(tree != null && tree.tickets.size() == original[i]
                        && find(level, roots.get(i), true).isEmpty(), "Damage fixture winter provenance incomplete");
            }
            for (int i = 0; i < 2; i++) {
                BlockPos root = roots.get(i);
                damaged[i] = SeasonalLedger.get(level).find(root).tickets.values().stream()
                        .filter(t -> level.getBlockState(t.support).getBlock() instanceof BranchBlock branch
                                && branch.getRadius(level.getBlockState(t.support)) == 1
                                && t.support.getY() > root.getY() + 5 && level.isEmptyBlock(t.support.above()))
                        .max(Comparator.comparingInt(t -> t.support.getY())).orElseThrow();
            }
            fire[0] = damaged[0].support.above();
            BlockPos blast = damaged[1].support;
            level.explode(null, blast.getX() + .5, blast.getY() + .5, blast.getZ() + .5,
                    2.0F, Level.ExplosionInteraction.BLOCK);
            h.assertTrue(!(level.getBlockState(blast).getBlock() instanceof BranchBlock),
                    "Native explosion did not actually remove its target branch");
            BlockPos trunk = roots.get(2).above();
            felledIncarnation[0] = SeasonalLedger.get(level).find(roots.get(2)).incarnation;
            ((BranchBlock) level.getBlockState(trunk).getBlock())
                    .destroyBranchFromNode(level, trunk, Direction.DOWN, false, null);
            h.assertTrue(!(level.getBlockState(trunk).getBlock() instanceof BranchBlock),
                    "Native felling did not actually remove trunk");
            container[0] = SeasonalLedger.get(level).find(roots.get(3)).tickets.values().iterator().next().pos;
            level.setBlock(container[0], Blocks.BARREL.defaultBlockState(), 3);
            ((Container) level.getBlockEntity(container[0])).setItem(0, new ItemStack(Items.DIAMOND, 7));
            containerData[0] = level.getBlockEntity(container[0]).saveWithFullMetadata();
        });
        // Fixture supplies repeated ignition, but only the actual vanilla/native fire tick may remove wood.
        for (int time = 505; time <= 650; time += 5) {
            final int fireTick = time;
            h.runAtTickTime(time, () -> {
                if (!(level.getBlockState(damaged[0].support).getBlock() instanceof BranchBlock)) return;
                if (level.isEmptyBlock(fire[0])) level.setBlock(fire[0], Blocks.FIRE.defaultBlockState(), 3);
                BlockState state = level.getBlockState(fire[0]);
                if (state.getBlock() == Blocks.FIRE)
                    ((FireBlock) Blocks.FIRE).tick(state, level, fire[0], RandomSource.create(8675309L + fireTick));
            });
        }
        h.runAtTickTime(700, () -> {
            h.assertTrue(!(level.getBlockState(damaged[0].support).getBlock() instanceof BranchBlock),
                    "Native fire did not actually remove its target branch");
            for (BlockPos pos : BlockPos.betweenClosed(roots.get(0).offset(-10, 0, -10), roots.get(0).offset(10, 30, 10)))
                if (level.getBlockState(pos).getBlock() == Blocks.FIRE) level.setBlock(pos, Blocks.AIR.defaultBlockState(), 3);
            double lastDeadline = roots.stream().map(root -> SeasonalLedger.get(level).find(root))
                    .filter(Objects::nonNull).flatMap(tree -> tree.tickets.values().stream())
                    .mapToDouble(ticket -> ticket.deadline).max().orElseThrow();
            int duration = SeasonHelper.getSeasonState(level).getSubSeasonDuration();
            double phase = (Math.ceil(lastDeadline * duration) + 2) / duration;
            h.assertTrue(phase < 1.0 / 3.0, "Fixture must restore while native structural growth remains dormant");
            heldPhase[0] = phase;
            setPhase(level, phase);
        });
        h.runAtTickTime(1300, () -> {
            for (int i = 0; i < 2; i++) {
                var tree = SeasonalLedger.get(level).find(roots.get(i));
                h.assertTrue(tree != null && tree.tickets.get(damaged[i].pos.asLong()).outcome
                        == SeasonalLedger.Outcome.UNSUPPORTED, "Native damage did not invalidate dependent seasonal opportunity");
                h.assertTrue(tree.tickets.values().stream().allMatch(t -> t.consumed), "Damaged canopy work did not drain");
            }
            var felled = SeasonalLedger.get(level).find(roots.get(2));
            h.assertTrue(felled == null || !felled.incarnation.equals(felledIncarnation[0]),
                    "Felled tree retained old incarnation opportunities");
            h.assertTrue(find(level, roots.get(2), true).isEmpty()
                    && !(level.getBlockState(roots.get(2).above()).getBlock() instanceof BranchBlock),
                    "Seasonal restoration resurrected native-felled wood/foliage");
            var control = SeasonalLedger.get(level).find(roots.get(3));
            h.assertTrue(control.tickets.get(container[0].asLong()).outcome == SeasonalLedger.Outcome.OBSTRUCTED,
                    "Container obstruction was not permanently consumed");
            h.assertTrue(level.getBlockState(container[0]).getBlock() == Blocks.BARREL
                    && containerData[0].equals(level.getBlockEntity(container[0]).saveWithFullMetadata()),
                    "Seasonal restoration changed player container or inventory");
            h.assertTrue(level.getBlockState(roots.get(3)).getValue(RootyBlock.FERTILITY) == 4
                    && find(level, roots.get(3), false).equals(controlWood),
                    "Restoration debited positive soil fertility or grew structural wood");
            System.out.println("SEASONAL_NATIVE_DAMAGE fire/explosion/felling/container/fertility PASS");
            for (ChunkPos chunk : ownedTickets) level.setChunkForced(chunk.x, chunk.z, false);
            heldPhase[0] = Double.NaN;
            setPhase(level, 4.0);
            h.succeed();
        });
    }
}
