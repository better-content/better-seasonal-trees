package com.bettercontent.seasonaltrees.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.bettercontent.seasonaltrees.catalogue.SeasonalCatalogue;
import com.bettercontent.seasonaltrees.catalogue.SpeciesProfile;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.event.SpeciesPostGenerationEvent;
import com.ferreusveritas.dynamictrees.worldgen.GenerationContext;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;

/** Exact worldgen writes of one JoCode, never foliage discovered on later chunk load. */
public final class GeneratedTreeWrites {
    // Unique positions are bounded by WorldGenRegion's writable chunks and the level's finite height.
    private static final ThreadLocal<Deque<Scope>> SCOPES = ThreadLocal.withInitial(ArrayDeque::new);
    private static final class Scope {
        final GenerationContext context;
        final Set<BlockPos> writes = new HashSet<>();
        final Map<BlockPos, BlockState> firstStates = new HashMap<>();
        Scope(GenerationContext context) { this.context = context; }
    }
    private GeneratedTreeWrites() {}

    public static void begin(GenerationContext context) {
        SCOPES.get().push(new Scope(context));
    }
    public static void end() {
        Deque<Scope> scopes = SCOPES.get();
        if (!scopes.isEmpty()) scopes.pop();
        if (scopes.isEmpty()) SCOPES.remove();
    }
    public static boolean active(WorldGenRegion region) {
        Deque<Scope> scopes = SCOPES.get();
        return !scopes.isEmpty() && scopes.peek().context.level() == region;
    }
    public static void beforeWrite(WorldGenRegion region, BlockPos pos, BlockState previous) {
        Deque<Scope> scopes = SCOPES.get();
        if (scopes.isEmpty() || scopes.peek().context.level() != region) return;
        Scope scope = scopes.peek();
        scope.firstStates.putIfAbsent(pos.immutable(), previous);
    }
    public static void wrote(WorldGenRegion region, BlockPos pos, BlockState state, boolean success) {
        if (!success) return;
        Deque<Scope> scopes = SCOPES.get();
        if (scopes.isEmpty() || scopes.peek().context.level() != region) return;
        Scope scope = scopes.peek();
        BlockState original = scope.firstStates.get(pos);
        // Rewriting older native leaves OR branches does not create this tree's own cell/support.
        if (original != null && !(original.getBlock() instanceof DynamicLeavesBlock)
                && !(original.getBlock() instanceof BranchBlock))
            scope.writes.add(pos.immutable());
    }
    public static void afterGeneration(SpeciesPostGenerationEvent event) {
        if (!(event.getLevel() instanceof WorldGenRegion region)) return;
        Deque<Scope> scopes = SCOPES.get();
        if (scopes.isEmpty()) return;
        Scope scope = scopes.peek();
        if (scope.context.level() != region
                || scope.context.species() != event.getSpecies()
                || !scope.context.rootPos().equals(event.getRootPos())
                || event.getSpecies().getRegistryName() == null) return;
        SpeciesProfile profile = SeasonalCatalogue.profile(event.getSpecies().getRegistryName());
        if (profile == null || profile.habit() != SpeciesProfile.Habit.TEMPERATE
                && profile.habit() != SpeciesProfile.Habit.TROPICAL) return;
        // The scope includes alternate postGeneration feature writes. Never infer leaves by
        // walking a neighboring mature tree merely because its canopy touches this one.
        GenerationCanopyData.stage(region, event.getRootPos().immutable(), event.getSpecies(), scope.writes);
    }
}
