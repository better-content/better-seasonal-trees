package com.bettercontent.seasonaltrees.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

import com.bettercontent.seasonaltrees.catalogue.SeasonalCatalogue;
import com.ferreusveritas.dynamictrees.event.SpeciesPostGenerationEvent;
import com.bettercontent.seasonaltrees.catalogue.SpeciesProfile;
import com.bettercontent.seasonaltrees.network.SeasonalNetwork;
import com.bettercontent.seasonaltrees.scheduler.SeasonClock;
import com.bettercontent.seasonaltrees.scheduler.SeasonalSchedule;
import com.ferreusveritas.dynamictrees.api.TreeHelper;
import com.ferreusveritas.dynamictrees.api.cell.Cell;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.block.leaves.LeavesProperties;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import com.ferreusveritas.dynamictrees.tree.family.Family;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.LiquidBlock;
import sereneseasons.api.season.SeasonHelper;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.level.ChunkEvent;
import net.minecraftforge.event.level.LevelEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;

/** Bounded, loaded-chunk-only seasonal work. No random ticks or tree growth calls drive it. */
public final class SeasonalRuntime {
    private static int tickMutationLimit = SeasonalRuntimeConfig.DEFAULT_MUTATIONS;
    private static int tickInspectionLimit = SeasonalRuntimeConfig.DEFAULT_INSPECTIONS;
    private static final int MAX_ROOT_BRANCHES = 16384, MAX_ROOT_LEAVES = 65536;
    private static final Map<ServerLevel, LevelWork> LEVELS = new IdentityHashMap<>();
    private static boolean registered;
    private static int mutations, inspections;
    private static boolean mutationSaturated, inspectionSaturated;
    private static int levelRotation;
    private static volatile BudgetSnapshot lastBudget = new BudgetSnapshot(0, 0,
            SeasonalRuntimeConfig.DEFAULT_MUTATIONS, SeasonalRuntimeConfig.DEFAULT_INSPECTIONS, 0, 0);

    public record BudgetSnapshot(int mutations, int inspections, int mutationLimit, int inspectionLimit,
                                 int rootBacklog, int chunkBacklog) {}

    public enum VisualKind { LEAF, BRANCH }
    /** effectivePhase is the current 0..12 phase on effectiveCycle using these frozen root timings. */
    public record VisualContext(BlockPos root, ResourceLocation speciesId, SpeciesProfile.Habit habit,
                                long effectiveCycle, double effectivePhase,
                                double leafOutStart, double leafOutDuration,
                                double shedStart, double shedEnd,
                                double bloomStart, double bloomEnd,
                                int autumnColor, String bloomVisual) {
        public VisualContext { root = root.immutable(); }
    }
    public record VisualOwnership(BlockPos position, VisualKind kind, VisualContext context) {
        public VisualOwnership { position = position.immutable(); }
    }
    public record VisualSnapshot(List<VisualOwnership> positions, boolean complete) {
        public VisualSnapshot { positions = List.copyOf(positions); }
    }
    private record RootClock(double phase, long cycle) {}

    /** Immutable previous-tick global counters; safe for operator and test observations. */
    public static BudgetSnapshot budgetSnapshot() { return lastBudget; }

    private SeasonalRuntime() {}
    public static synchronized void register() {
        if (!registered) {
            SeasonalRuntimeConfig.register();
            MinecraftForge.EVENT_BUS.register(SeasonalRuntime.class);
            MinecraftForge.EVENT_BUS.register(GenerationCanopyData.class);
            registered = true;
        }
    }

    /** DT's post-generation callback runs before chunk promotion; only exact worker writes are captured. */
    @SubscribeEvent public static void onGenerated(SpeciesPostGenerationEvent event) {
        GeneratedTreeWrites.afterGeneration(event);
    }
    @SubscribeEvent public static void onChunkLoad(ChunkEvent.Load event) {
        if (event.getLevel() instanceof ServerLevel level && event.getChunk() instanceof LevelChunk chunk) {
            // Pinned ChunkMap posts this from its main-thread mailbox before playerLoadedChunk sends a packet.
            GenerationCanopyData.admit(level, chunk);
            work(level).chunks.addLast(new ChunkScan(chunk));
        }
    }
    /** Admit player-placed native root blocks immediately; DT tree generation is also caught by dormantRoot. */
    @SubscribeEvent public static void onRootPlaced(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel() instanceof ServerLevel level
                && event.getPlacedBlock().getBlock() instanceof RootyBlock)
            ensureTracked(level, event.getPos());
    }
    @SubscribeEvent public static void onLevelUnload(LevelEvent.Unload event) {
        if (event.getLevel() instanceof ServerLevel level) LEVELS.remove(level);
    }
    @SubscribeEvent(priority = EventPriority.MONITOR) public static void onBlockBroken(BlockEvent.BreakEvent event) {
        if (!event.isCanceled() && event.getLevel() instanceof ServerLevel level)
            invalidateStructure(level, event.getPos(), event.getState());
    }
    @SubscribeEvent public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!(event.getLevel() instanceof ServerLevel level)) return;
        for (BlockPos pos : event.getAffectedBlocks())
            if (level.hasChunkAt(pos)) invalidateStructure(level, pos, level.getBlockState(pos));
    }
    private static void invalidateStructure(ServerLevel level, BlockPos pos, BlockState state) {
        LevelWork work = work(level);
        if (state.getBlock() instanceof RootyBlock) {
            onRootReplaced(level, pos);
        }
        if (state.getBlock() instanceof BranchBlock) {
            onBranchReplaced(level, pos);
        }
        if (state.getBlock() instanceof DynamicLeavesBlock) {
            onManagedLeafBroken(level, pos);
        }
    }
    /** A player/explosion leaf removal is not seasonal provenance; rebuild only live ownership. */
    private static void onManagedLeafBroken(ServerLevel level, BlockPos leafPos) {
        LevelWork work = work(level);
        Set<Long> owners = work.leafOwners.get(leafPos.asLong());
        if (owners == null) return;
        for (long rootKey : List.copyOf(owners)) {
            RootWork root = work.roots.get(rootKey);
            if (root != null) {
                root.invalidateTopology(level);
                work.offer(root);
            }
        }
    }
    /** Persists a damage barrier before an axed/programmatically replaced branch can regrow. */
    public static void onBranchReplaced(ServerLevel level, BlockPos branchPos) {
        LevelWork work = work(level);
        SeasonalLedger ledger = SeasonalLedger.get(level);
        Set<Long> owners = new HashSet<>(ledger.managedBranchOwnersAt(branchPos));
        Set<Long> transientOwners = work.branchOwners.get(branchPos.asLong());
        if (transientOwners != null) owners.addAll(transientOwners);
        if (owners.isEmpty()) return;
        for (long rootKey : List.copyOf(owners)) {
            SeasonalLedger.Tree tree = ledger.find(BlockPos.of(rootKey));
            if (tree != null) {
                long throughTarget = tree.planComplete && tree.planCycle == tree.timingCycle
                        ? tree.timingCycle + 1 : tree.timingCycle;
                ledger.tombstoneBranch(tree, branchPos, throughTarget);
            }
            RootWork root = work.roots.get(rootKey);
            if (root != null) root.invalidateTopology(level);
        }
    }
    /** BlockBehaviour.onRemove observes root-to-air-to-same-root replacement without a break event. */
    public static void onRootReplaced(ServerLevel level, BlockPos rootPos) {
        SeasonalLedger ledger = SeasonalLedger.get(level);
        GenerationCanopyData.invalidateRootHeader(level, rootPos, ledger);
        ledger.forget(rootPos);
        RootWork root = work(level).roots.get(rootPos.asLong());
        if (root != null) root.invalidateTopology(level);
    }
    @SubscribeEvent public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            SeasonalNetwork.sendCalendar(player, SeasonClock.read(player.serverLevel()));
    }
    @SubscribeEvent public static void onDimensionChange(PlayerEvent.PlayerChangedDimensionEvent event) {
        if (event.getEntity() instanceof ServerPlayer player)
            SeasonalNetwork.sendCalendar(player, SeasonClock.read(player.serverLevel()));
    }
    @SubscribeEvent public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        tickMutationLimit = SeasonalRuntimeConfig.maxMutations();
        tickInspectionLimit = SeasonalRuntimeConfig.maxInspections();
        mutations = inspections = 0; mutationSaturated = inspectionSaturated = false;
        List<ServerLevel> levels = new ArrayList<>();
        event.getServer().getAllLevels().forEach(levels::add);
        for (int levelIndex = 0; levelIndex < levels.size(); levelIndex++) {
            ServerLevel level = levels.get(Math.floorMod(levelIndex + levelRotation, levels.size()));
            SeasonClock.Reading clock = SeasonClock.read(level);
            if (clock == null) continue;
            SeasonalLedger ledger = SeasonalLedger.get(level);
            ledger.observeCycle(clock.cycleTick(), clock.cycleDuration(), level.getGameTime());
            LevelWork work = work(level);
            int day = clock.cycleTick() / clock.dayDuration();
            if (work.lastBroadcastDay != day) {
                work.lastBroadcastDay = day;
                for (ServerPlayer player : level.players()) SeasonalNetwork.sendCalendar(player, clock);
            }
            if (work.revision != SeasonalCatalogue.revision()) {
                work.revision = SeasonalCatalogue.revision();
                for (SeasonalLedger.Tree tree : ledger.trees.values())
                    if (level.hasChunkAt(tree.root)) work.offer(new RootWork(tree.root));
            }
            int scanQuota = Math.max(1, (tickInspectionLimit - inspections) / 2);
            while (scanQuota > 0 && inspections < tickInspectionLimit && !work.chunks.isEmpty()) {
                ChunkScan scan = work.chunks.peekFirst();
                int used = scan.advance(level, work, scanQuota);
                scanQuota -= used;
                if (scan.done()) work.chunks.removeFirst(); else break;
            }
            int roots = work.active.size();
            for (int i = 0; i < roots && inspections < tickInspectionLimit && mutations < tickMutationLimit; i++) {
                RootWork root = work.active.removeFirst(); work.enqueued.remove(root.root.asLong());
                boolean retain = root.advance(level, ledger, clock);
                if (retain) work.offer(root); else work.roots.remove(root.root.asLong());
            }
            // Visual validation shares the same inspection ceiling and runs after tree work.
            // If the graph scheduler used the budget, publication simply waits until a later tick.
            if (inspections < tickInspectionLimit && !work.dirtyBranchChunks.isEmpty()) {
                long key = work.dirtyBranchChunks.removeFirst();
                ChunkPos dirtyChunk = new ChunkPos(key);
                if (!level.hasChunk(dirtyChunk.x, dirtyChunk.z)) {
                    work.dirtyBranchChunks.addLast(key);
                } else {
                    VisualSnapshot snapshot = visualSnapshot(level, dirtyChunk,
                            SeasonalRuntimeConfig.DEFAULT_INSPECTIONS, true);
                    if (!snapshot.complete()) {
                        if (work.incompletePublished.add(key))
                            SeasonalNetwork.publishManagedVisuals(level, dirtyChunk, snapshot);
                        work.dirtyBranchChunks.addLast(key);
                    } else {
                        work.dirtyChunkKeys.remove(key);
                        work.incompletePublished.remove(key);
                        SeasonalNetwork.publishManagedVisuals(level, dirtyChunk, snapshot);
                    }
                }
            }
        }
        int rootBacklog = 0, chunkBacklog = 0;
        for (LevelWork work : LEVELS.values()) {
            rootBacklog += work.active.size();
            chunkBacklog += work.chunks.size();
        }
        lastBudget = new BudgetSnapshot(mutations, inspections, tickMutationLimit, tickInspectionLimit,
                rootBacklog, chunkBacklog);
        levelRotation++;
        inspectionSaturated = inspections >= tickInspectionLimit;
        mutationSaturated = mutations >= tickMutationLimit;
    }

    /** Called from DT's root update hook. A broken trunk is never protected. */
    public static boolean dormantRoot(ServerLevel level, BlockPos root, BlockState state, RootyBlock block) {
        SeasonClock.Reading clock = SeasonClock.read(level);
        if (clock == null) return false;
        BlockPos trunk = root.relative(block.getTrunkDirection(level, root));
        if (!level.hasChunkAt(trunk)) return false;
        Species species = block.getSpecies(state, level, root);
        SpeciesProfile profile = species == null ? null : SeasonalCatalogue.profile(species.getRegistryName());
        if (profile == null || (profile.habit() != SpeciesProfile.Habit.EVERGREEN
                && profile.habit() != SpeciesProfile.Habit.TEMPERATE
                && profile.habit() != SpeciesProfile.Habit.TROPICAL)) return false;
        if (profile.habit() == SpeciesProfile.Habit.TROPICAL
                && !SeasonHelper.usesTropicalSeasons(level.getBiome(root))) return false;
        if (!(level.getBlockState(trunk).getBlock() instanceof BranchBlock trunkBranch)
                || trunkBranch.getFamily() != species.getFamily()) return false;
        if (profile.habit() != SpeciesProfile.Habit.UNCHANGED)
            work(level).offer(new RootWork(root));
        if (profile.habit() == SpeciesProfile.Habit.EVERGREEN) return false;
        if (profile.habit() != SpeciesProfile.Habit.TEMPERATE
                && profile.habit() != SpeciesProfile.Habit.TROPICAL) return false;
        SeasonalLedger ledger = SeasonalLedger.get(level);
        SeasonalLedger.Tree tree = ledger.find(root);
        RootClock rootTime = rootClock(level, root, profile.habit(), ledger.cycle());
        if (tree != null && tree.timingCycle == rootTime.cycle()) {
            double leafOutEnd = tree.leafOutStart + tree.leafOutDuration;
            if (tree.carryCycle == rootTime.cycle()) leafOutEnd = Math.max(leafOutEnd, tree.carryLeafOutEnd);
            return rootTime.phase() >= tree.shedStart
                    || rootTime.phase() < leafOutEnd;
        }
        return dormantPhase(rootTime.phase(), profile);
    }

    /** Called after DT's native placement routine returns; only cached, unambiguous tree ownership is extended. */
    public static void onNativeLeafPlaced(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof ServerLevel level) || SeasonClock.read(level) == null
                || !level.hasChunkAt(pos)) return;
        LevelWork work = LEVELS.get(level);
        if (work == null) return;
        BlockState placed = level.getBlockState(pos);
        if (!(placed.getBlock() instanceof DynamicLeavesBlock dynamicLeaves)) return;

        // Each cached live leaf claims its six adjacent candidate cells. This O(1) lookup avoids
        // finding roots by world/trunk traversal and naturally coalesces repeated growth by chunk.
        Set<Long> owners = work.ownedCells.get(pos.asLong());
        if (owners == null || owners.size() != 1) return;
        long rootKey = owners.iterator().next();
        RootWork rootWork = work.roots.get(rootKey);
        if (rootWork == null || !rootWork.initialized || rootWork.captureOverflow
                || rootWork.expectedSpecies == null || !rootWork.expectedSpecies.isValidLeafBlock(dynamicLeaves)
                || (!rootWork.cachedLeaves.contains(pos.asLong())
                        && rootWork.cachedLeaves.size() >= MAX_ROOT_LEAVES)) return;
        if (!level.hasChunkAt(rootWork.root)) return;
        BlockState rootState = level.getBlockState(rootWork.root);
        if (!(rootState.getBlock() instanceof RootyBlock rootBlock)) return;
        Species species = rootBlock.getSpecies(rootState, level, rootWork.root);
        if (species == null || !species.getRegistryName().equals(rootWork.currentSpeciesId)) return;
        SpeciesProfile profile = SeasonalCatalogue.profile(species.getRegistryName());
        if (profile == null || profile.habit() == SpeciesProfile.Habit.UNCHANGED) return;
        if (profile.habit() == SpeciesProfile.Habit.TROPICAL
                && !SeasonHelper.usesTropicalSeasons(level.getBiome(rootWork.root))) return;

        rootWork.cache(level, pos);
        // cache() deduplicates chunk keys, so a canopy burst schedules one bounded packet refresh per chunk.
        work.markBranchChunk(new ChunkPos(pos).toLong());
    }

    /** The placement hook only suppresses leaves attached to an eligible live tree. */
    public static boolean suppressLeafGrowth(LevelAccessor accessor, BlockPos pos) {
        if (!(accessor instanceof ServerLevel level)) return false;
        SeasonClock.Reading clock = SeasonClock.read(level);
        if (clock == null) return false;
        SeasonalLedger ledger = SeasonalLedger.get(level);
        Set<Long> pendingOwners = ledger.pendingTicketOwnersAt(pos);
        Set<Long> roots = new HashSet<>(pendingOwners);
        Set<Long> capturedOwners = work(level).ownedCells.get(pos.asLong());
        if (capturedOwners != null) roots.addAll(capturedOwners);
        if (roots.isEmpty()) return false;
        for (long rootKey : roots) {
            BlockPos root = BlockPos.of(rootKey);
            SeasonalLedger.Tree knownTree = ledger.find(root);
            if (knownTree != null && knownTree.timingHabit == SpeciesProfile.Habit.TROPICAL
                    && !knownTree.tropicalEligible) continue;
            boolean rootLoaded = level.hasChunkAt(root);
            if (knownTree != null && knownTree.timingHabit == SpeciesProfile.Habit.TROPICAL
                    && rootLoaded && !SeasonHelper.usesTropicalSeasons(level.getBiome(root))) continue;
            if (pendingOwnerUnavailable(pendingOwners, rootKey, rootLoaded, false)) return true;
            if (!rootLoaded) continue;
            BlockState state = level.getBlockState(root);
            if (!(state.getBlock() instanceof RootyBlock block)) {
                if (pendingOwners.contains(rootKey)) return true;
                continue;
            }
            SeasonalLedger.Tree tree = ledger.find(root);
            if (pendingOwners.contains(rootKey)) {
                if (tree == null) return true;
                SeasonalLedger.Ticket ticket = tree.tickets.get(pos.asLong());
                if (ticket == null || ticket.consumed) return true;
                BlockPos trunk = root.relative(block.getTrunkDirection(level, root));
                boolean trunkLoaded = level.hasChunkAt(trunk);
                if (pendingOwnerUnavailable(pendingOwners, rootKey, rootLoaded, trunkLoaded)) return true;
                // Keep this exact position empty until its persisted restoration ticket resolves.
                return true;
            }
            if (dormantRoot(level, root, state, block)) return true;
        }
        return false;
    }

    static boolean pendingOwnerUnavailable(Set<Long> pendingOwners, long rootKey,
                                           boolean rootChunkLoaded, boolean trunkChunkLoaded) {
        return pendingOwners.contains(rootKey) && (!rootChunkLoaded || !trunkChunkLoaded);
    }

    /** Persistent ownership is indexed separately; this reports only the transient per-level cache. */
    public static Set<Long> transientBranchOwnersAt(ServerLevel level, BlockPos branch) {
        LevelWork work = LEVELS.get(level);
        if (work == null) return Set.of();
        Set<Long> owners = work.branchOwners.get(branch.asLong());
        return owners == null ? Set.of() : Set.copyOf(owners);
    }

    private static boolean dormantPhase(double phase, SpeciesProfile profile) {
        return phase >= profile.shedStart() || phase < profile.leafOutStart() + profile.leafOutDuration();
    }
    public record RuntimeSnapshot(String rootId, long cycle, int tickets, int pending, int consumed,
                                  int chunkBacklog, int rootBacklog, boolean mutationSaturated,
                                  boolean inspectionSaturated, boolean captureSaturated,
                                  int restored, int obstructed, int unsupported, int unsuitable,
                                  int invalidState, int unknownLegacy,
                                  int locationRejected, int lightRejected, int hydrationRejected) {}
    public record BranchSnapshot(List<BlockPos> positions, boolean complete) {}
    /** Immutable, bounded live-leaf and branch ownership for chunk-baked client visuals. */
    public static VisualSnapshot visualSnapshot(ServerLevel level, ChunkPos chunk, int limit) {
        return visualSnapshot(level, chunk, limit, false);
    }
    private static VisualSnapshot visualSnapshot(ServerLevel level, ChunkPos chunk, int limit,
                                                  boolean chargeTickBudget) {
        if (limit <= 0 || limit > SeasonalRuntimeConfig.DEFAULT_INSPECTIONS
                || !level.hasChunk(chunk.x, chunk.z)) return new VisualSnapshot(List.of(), false);
        LevelWork work = LEVELS.get(level);
        if (work == null) return new VisualSnapshot(List.of(), true);
        Set<Long> leaves = work.leavesByChunk.getOrDefault(chunk.toLong(), Set.of());
        Set<Long> branches = work.branchesByChunk.getOrDefault(chunk.toLong(), Set.of());
        if ((long) leaves.size() + branches.size() > limit) return new VisualSnapshot(List.of(), false);
        List<VisualOwnership> result = new ArrayList<>(leaves.size() + branches.size());
        SeasonalLedger ledger = SeasonalLedger.get(level);
        int visited = 0;
        for (long positionKey : leaves) {
            if (++visited > limit) return new VisualSnapshot(List.of(), false);
            if (chargeTickBudget && !inspected()) return new VisualSnapshot(List.of(), false);
            BlockPos pos = BlockPos.of(positionKey);
            if (!level.hasChunkAt(pos)) return new VisualSnapshot(List.of(), false);
            Set<Long> owners = work.leafOwners.get(positionKey);
            if (owners == null || owners.size() != 1)
                return new VisualSnapshot(List.of(), false);
            VisualOwnership owner = visualOwner(level, ledger, work, pos, owners, VisualKind.LEAF);
            if (owner == null) return new VisualSnapshot(List.of(), false);
            // A managed leaf may currently be absent because it was seasonally shed.
            if (owner != NO_VISUAL_OWNER) result.add(owner);
        }
        for (long positionKey : branches) {
            if (++visited > limit) return new VisualSnapshot(List.of(), false);
            if (chargeTickBudget && !inspected()) return new VisualSnapshot(List.of(), false);
            BlockPos pos = BlockPos.of(positionKey);
            if (!level.hasChunkAt(pos)) return new VisualSnapshot(List.of(), false);
            Set<Long> owners = work.branchOwners.get(positionKey);
            if (owners == null || owners.size() != 1)
                return new VisualSnapshot(List.of(), false);
            VisualOwnership owner = visualOwner(level, ledger, work, pos, owners, VisualKind.BRANCH);
            if (owner == null) return new VisualSnapshot(List.of(), false);
            if (owner != NO_VISUAL_OWNER) result.add(owner);
        }
        return new VisualSnapshot(result, true);
    }
    private static VisualOwnership visualOwner(ServerLevel level, SeasonalLedger ledger, LevelWork work,
                                                 BlockPos pos, Set<Long> owners, VisualKind kind) {
        // Multiple roots at one block are intrinsically ambiguous; fail closed without an unbounded fanout walk.
        if (owners == null || owners.size() != 1)
            return null;
        VisualOwnership selected = null;
        for (long rootKey : owners) {
            RootWork rootWork = work.roots.get(rootKey);
            SeasonalLedger.Tree tree = ledger.find(BlockPos.of(rootKey));
            if (rootWork == null || tree == null || !rootWork.captured || rootWork.captureOverflow
                    || rootWork.cycle != tree.timingCycle
                    || !tree.species.equals(rootWork.currentSpeciesId) || tree.branchIndexOverflow)
                return null;
            if (!level.hasChunkAt(rootWork.root)) return null;
            BlockState rootState = level.getBlockState(rootWork.root);
            if (!(rootState.getBlock() instanceof RootyBlock rootBlock)) return null;
            Species species = rootBlock.getSpecies(rootState, level, rootWork.root);
            if (species == null || !tree.species.equals(species.getRegistryName())) return null;
            SpeciesProfile profile = SeasonalCatalogue.profile(tree.species);
            if (profile == null || profile.habit() == SpeciesProfile.Habit.UNCHANGED
                    || tree.timingHabit == SpeciesProfile.Habit.UNCHANGED) return null;
            if (tree.timingHabit == SpeciesProfile.Habit.TROPICAL
                    && !SeasonHelper.usesTropicalSeasons(level.getBiome(rootWork.root))) return null;
            BlockState live = level.getBlockState(pos);
            if (kind == VisualKind.LEAF) {
                if (!(live.getBlock() instanceof DynamicLeavesBlock dynamicLeaves)
                        || !species.isValidLeafBlock(dynamicLeaves)) {
                    if (tree.tickets.containsKey(pos.asLong()) && live.isAir()) continue;
                    return null;
                }
            } else if (!(live.getBlock() instanceof BranchBlock branch)
                    || branch.getFamily() != species.getFamily()) return null;
            RootClock time = rootClock(level, rootWork.root, tree.timingHabit, tree.timingCycle);
            if (time.cycle() != tree.timingCycle) return null;
            VisualContext context = new VisualContext(rootWork.root, tree.species, tree.timingHabit,
                    tree.timingCycle, time.phase(), tree.leafOutStart, tree.leafOutDuration,
                    tree.shedStart, tree.shedEnd, tree.bloomStart, tree.bloomEnd,
                    tree.autumnColor, tree.bloomVisual);
            VisualOwnership candidate = new VisualOwnership(pos, kind, context);
            if (selected != null && !selected.equals(candidate)) return null;
            selected = candidate;
        }
        return selected == null ? NO_VISUAL_OWNER : selected;
    }
    private static RootClock rootClock(ServerLevel level, BlockPos root, SpeciesProfile.Habit habit,
                                       long fallbackCycle) {
        SeasonClock.Reading reading = SeasonClock.read(level);
        if (reading == null) return new RootClock(0, fallbackCycle);
        if (habit == SpeciesProfile.Habit.TROPICAL
                && SeasonHelper.usesTropicalSeasons(level.getBiome(root))) {
            SeasonClock.LocalReading tropical = SeasonClock.tropical(reading, SeasonalLedger.get(level).cycle());
            return new RootClock(tropical.phase(), tropical.cycle());
        }
        return new RootClock(reading.phase(), SeasonalLedger.get(level).cycle());
    }
    private static final VisualOwnership NO_VISUAL_OWNER = new VisualOwnership(BlockPos.ZERO, VisualKind.LEAF,
            new VisualContext(BlockPos.ZERO, new ResourceLocation("minecraft", "air"),
                    SpeciesProfile.Habit.UNCHANGED, 0, 0, 0, 0, 0, 0, 0, 0, 0, ""));
    /** Immutable, bounded chunk ownership for client baked branch blossoms. */
    public static BranchSnapshot managedBranches(ServerLevel level, ChunkPos chunk,
                                                  ResourceLocation speciesId, int limit) {
        if (limit <= 0 || !level.hasChunk(chunk.x, chunk.z)) return new BranchSnapshot(List.of(), false);
        LevelWork work = work(level);
        Set<Long> positions = work.branchesByChunk.get(chunk.toLong());
        if (positions == null) return new BranchSnapshot(List.of(), true);
        if (positions.size() > limit) return new BranchSnapshot(List.of(), false);
        SpeciesProfile activeProfile = SeasonalCatalogue.profile(speciesId);
        if (activeProfile == null || (activeProfile.habit() != SpeciesProfile.Habit.TEMPERATE
                && activeProfile.habit() != SpeciesProfile.Habit.TROPICAL))
            return new BranchSnapshot(List.of(), false);
        List<BlockPos> result = new ArrayList<>();
        SeasonalLedger ledger = SeasonalLedger.get(level);
        for (long key : positions) {
            Set<Long> owners = work.branchOwners.get(key);
            if (owners == null) continue;
            boolean eligible = false;
            for (long rootKey : owners) {
                RootWork root = work.roots.get(rootKey);
                if (root == null || !speciesId.equals(root.currentSpeciesId)) continue;
                if (!root.captured || root.captureOverflow) return new BranchSnapshot(List.of(), false);
                SeasonalLedger.Tree record = ledger.find(root.root);
                if (record != null && record.species.equals(speciesId)) eligible = true;
            }
            if (!eligible) continue;
            BlockPos pos = BlockPos.of(key);
            // Packet/watch probes have their own hard per-chunk cap above. They
            // can run after the scheduler's graph budget is exhausted.
            if (!(level.getBlockState(pos).getBlock() instanceof BranchBlock)) continue;
            if (result.size() == limit) return new BranchSnapshot(List.of(), false);
            result.add(pos);
        }
        return new BranchSnapshot(List.copyOf(result), true);
    }
    /** Operator and fixture entry: enrolls a loaded native root without relying on random ticks. */
    public static boolean ensureTracked(ServerLevel level, BlockPos root) {
        if (!level.hasChunkAt(root)) return false;
        BlockState state = level.getBlockState(root);
        if (!(state.getBlock() instanceof RootyBlock block)) return false;
        if (!level.hasChunkAt(root.relative(block.getTrunkDirection(level, root)))) return false;
        Species species = block.getSpecies(state, level, root);
        SpeciesProfile profile = species == null ? null : SeasonalCatalogue.profile(species.getRegistryName());
        if (profile == null || profile.habit() == SpeciesProfile.Habit.UNCHANGED) return false;
        if (profile.habit() == SpeciesProfile.Habit.TROPICAL
                && !SeasonHelper.usesTropicalSeasons(level.getBiome(root))) return false;
        work(level).offer(new RootWork(root));
        return true;
    }
    public static RuntimeSnapshot inspectSnapshot(ServerLevel level, BlockPos root) {
        SeasonalLedger ledger = SeasonalLedger.get(level);
        SeasonalLedger.Tree tree = ledger.find(root);
        int pending = 0, consumed = 0, total = 0;
        int restored = 0, obstructed = 0, unsupported = 0, unsuitable = 0, invalidState = 0, unknownLegacy = 0;
        int locationRejected = 0, lightRejected = 0, hydrationRejected = 0;
        if (tree != null) for (SeasonalLedger.Ticket ticket : tree.tickets.values()) {
            total++; if (ticket.consumed) consumed++; else pending++;
            if ((ticket.suitabilityMask & SeasonalLedger.LOCATION_REJECTED) != 0) locationRejected++;
            if ((ticket.suitabilityMask & SeasonalLedger.LIGHT_REJECTED) != 0) lightRejected++;
            if ((ticket.suitabilityMask & SeasonalLedger.HYDRATION_REJECTED) != 0) hydrationRejected++;
            switch (ticket.outcome) {
                case RESTORED -> restored++;
                case OBSTRUCTED -> obstructed++;
                case UNSUPPORTED -> unsupported++;
                case UNSUITABLE -> unsuitable++;
                case INVALID_STATE -> invalidState++;
                case UNKNOWN_LEGACY -> unknownLegacy++;
                case PENDING -> { }
            }
        }
        LevelWork work = work(level);
        RootWork rootWork = work.roots.get(root.asLong());
        return new RuntimeSnapshot(tree == null ? "none" : tree.incarnation.toString(), ledger.cycle(),
                total, pending, consumed, work.chunks.size(), work.active.size(), mutationSaturated,
                inspectionSaturated, rootWork != null && rootWork.captureOverflow,
                restored, obstructed, unsupported, unsuitable, invalidState, unknownLegacy,
                locationRejected, lightRejected, hydrationRejected);
    }
    public static String inspect(ServerLevel level, BlockPos root) { return inspectSnapshot(level, root).toString(); }

    private static LevelWork work(ServerLevel level) { return LEVELS.computeIfAbsent(level, ignored -> new LevelWork()); }
    private static boolean inspected() { if (inspections >= tickInspectionLimit) return false; inspections++; return true; }
    private static boolean mutate() { if (mutations >= tickMutationLimit) return false; mutations++; return true; }

    private static final class LevelWork {
        long revision = Long.MIN_VALUE;
        int lastBroadcastDay = -1;
        final Deque<ChunkScan> chunks = new ArrayDeque<>();
        final Deque<RootWork> active = new ArrayDeque<>();
        final Map<Long, RootWork> roots = new HashMap<>();
        final Set<Long> enqueued = new HashSet<>();
        final Map<Long, Set<Long>> ownedCells = new HashMap<>();
        final Map<Long, Set<Long>> leafOwners = new HashMap<>();
        final Map<Long, Set<Long>> leavesByChunk = new HashMap<>();
        final Map<Long, Set<Long>> branchOwners = new HashMap<>();
        final Map<Long, Set<Long>> branchesByChunk = new HashMap<>();
        final Deque<Long> dirtyBranchChunks = new ArrayDeque<>();
        final Set<Long> dirtyChunkKeys = new HashSet<>();
        final Set<Long> incompletePublished = new HashSet<>();
        void offer(RootWork root) {
            if (enqueued.add(root.root.asLong())) {
                roots.put(root.root.asLong(), root);
                active.addLast(root);
            }
        }
        void claim(long root, BlockPos pos) { ownedCells.computeIfAbsent(pos.asLong(), ignored -> new HashSet<>()).add(root); }
        void claimLeaf(long root, BlockPos pos) {
            long key = pos.asLong();
            leafOwners.computeIfAbsent(key, ignored -> new HashSet<>()).add(root);
            leavesByChunk.computeIfAbsent(new ChunkPos(pos).toLong(), ignored -> new HashSet<>()).add(key);
        }
        void releaseLeaves(long root, Set<Long> positions) {
            for (long key : positions) {
                Set<Long> owners = leafOwners.get(key);
                if (owners != null) {
                    owners.remove(root);
                    if (owners.isEmpty()) {
                        leafOwners.remove(key);
                        long chunk = new ChunkPos(BlockPos.of(key)).toLong();
                        Set<Long> chunkPositions = leavesByChunk.get(chunk);
                        if (chunkPositions != null) {
                            chunkPositions.remove(key);
                            if (chunkPositions.isEmpty()) leavesByChunk.remove(chunk);
                        }
                    }
                }
                markBranchChunk(new ChunkPos(BlockPos.of(key)).toLong());
            }
            positions.clear();
        }
        void release(long root, Set<Long> positions) {
            for (long key : positions) {
                Set<Long> owners = ownedCells.get(key);
                if (owners != null) { owners.remove(root); if (owners.isEmpty()) ownedCells.remove(key); }
            }
            positions.clear();
        }
        void claimBranch(long root, BlockPos pos) {
            long key = pos.asLong();
            branchOwners.computeIfAbsent(key, ignored -> new HashSet<>()).add(root);
            long chunk = new ChunkPos(pos).toLong();
            branchesByChunk.computeIfAbsent(chunk, ignored -> new HashSet<>()).add(key);
            markBranchChunk(chunk);
        }
        void releaseBranches(long root, Set<Long> positions) {
            for (long key : positions) {
                Set<Long> owners = branchOwners.get(key);
                if (owners != null) {
                    owners.remove(root);
                    if (owners.isEmpty()) {
                        branchOwners.remove(key);
                        BlockPos pos = BlockPos.of(key);
                        long chunk = new ChunkPos(pos).toLong();
                        Set<Long> chunkPositions = branchesByChunk.get(chunk);
                        if (chunkPositions != null) {
                            chunkPositions.remove(key);
                            if (chunkPositions.isEmpty()) branchesByChunk.remove(chunk);
                        }
                    }
                }
                markBranchChunk(new ChunkPos(BlockPos.of(key)).toLong());
            }
            positions.clear();
        }
        void markBranchChunk(long chunk) {
            if (dirtyChunkKeys.add(chunk)) dirtyBranchChunks.addLast(chunk);
        }
    }
    private static final class ChunkScan {
        final LevelChunk chunk;
        int section, index;
        ChunkScan(LevelChunk chunk) { this.chunk = chunk; }
        boolean done() { return section >= chunk.getSections().length; }
        int advance(ServerLevel level, LevelWork work, int quota) {
            int used = 0;
            LevelChunkSection[] sections = chunk.getSections();
            while (section < sections.length && used < quota && inspections < tickInspectionLimit) {
                LevelChunkSection current = sections[section];
                if (!current.getStates().maybeHas(state -> state.getBlock() instanceof RootyBlock)) {
                    section++; index = 0; continue;
                }
                int x = index & 15, z = (index >>> 4) & 15, y = (index >>> 8) & 15;
                BlockPos pos = new BlockPos(chunk.getPos().getMinBlockX() + x,
                        (level.getMinSection() + section) * 16 + y, chunk.getPos().getMinBlockZ() + z);
                if (!inspected()) break;
                used++;
                if (current.getBlockState(x, y, z).getBlock() instanceof RootyBlock) work.offer(new RootWork(pos));
                if (++index == 4096) { section++; index = 0; }
            }
            return used;
        }
    }
    private record LeafNode(BlockPos pos, BlockPos support, int depth) {}
    private record Candidate(BlockPos pos, BlockPos support, BlockState state, BlockState supportState,
                             int depth, double deadline, double restorationDeadline) {}
    private record RankedCandidate(Candidate leaf, double hash) {}
    private static final class RootWork {
        final BlockPos root;
        final Deque<BlockPos> branches = new ArrayDeque<>();
        final Set<Long> seenBranches = new HashSet<>(), seenLeaves = new HashSet<>();
        final Map<Long, Long> topologyParents = new HashMap<>();
        final Set<Long> capturedPositions = new HashSet<>();
        final Deque<LeafNode> leaves = new ArrayDeque<>();
        final List<Candidate> capturedLeaves = new ArrayList<>();
        final Set<Long> cachedCells = new HashSet<>();
        final Set<Long> cachedLeaves = new HashSet<>();
        final Set<Long> cachedBranches = new HashSet<>();
        final Set<Long> ownedChunkKeys = new HashSet<>();
        final PriorityQueue<Candidate> due = new PriorityQueue<>(Comparator.comparingDouble(Candidate::deadline));
        final PriorityQueue<RankedCandidate> ranked = new PriorityQueue<>((a, b) ->
                SeasonalSchedule.compareOuterFirst(a.leaf().depth(), a.hash(), a.leaf().pos().asLong(),
                        b.leaf().depth(), b.hash(), b.leaf().pos().asLong()));
        final PriorityQueue<SeasonalLedger.Ticket> restoration = new PriorityQueue<>(
                Comparator.comparingDouble(ticket -> ticket.deadline));
        BlockPos scanningBranch;
        LeafNode scanningLeaf;
        int neighbor;
        boolean initialized, captured, captureOverflow;
        boolean finalizing;
        int finalizeIndex;
        int finalizeRank;
        boolean autumnPrepared, summerPrepared, planStarted, planJustCompleted;
        Species expectedSpecies;
        Family expectedFamily;
        ResourceLocation currentSpeciesId;
        long cycle = Long.MIN_VALUE;
        long catalogueRevision = Long.MIN_VALUE;
        RootWork(BlockPos root) { this.root = root.immutable(); }
        boolean advance(ServerLevel level, SeasonalLedger ledger, SeasonClock.Reading clock) {
            if (!level.hasChunkAt(root)) { releaseOwnership(level); return false; }
            if (!inspected()) return true;
            BlockState rootState = level.getBlockState(root);
            if (!(rootState.getBlock() instanceof RootyBlock rootBlock)) {
                releaseOwnership(level); ledger.forget(root); return false;
            }
            BlockPos trunk = root.relative(rootBlock.getTrunkDirection(level, root));
            if (!level.hasChunkAt(trunk)) return true;
            Species species = rootBlock.getSpecies(rootState, level, root);
            SpeciesProfile profile = species == null ? null : SeasonalCatalogue.profile(species.getRegistryName());
            if (species == null) { releaseOwnership(level); ledger.forget(root); return false; }
            currentSpeciesId = species.getRegistryName();
            BlockState trunkState = level.getBlockState(trunk);
            if (!(trunkState.getBlock() instanceof BranchBlock trunkBranch)
                    || trunkBranch.getFamily() != species.getFamily()) {
                releaseOwnership(level); ledger.forget(root); return false;
            }
            if (profile != null && profile.habit() == SpeciesProfile.Habit.TROPICAL
                    && !SeasonHelper.usesTropicalSeasons(level.getBiome(root))) {
                // Tropical profiles are strictly biome-scoped. Leave this tree to native DT.
                releaseOwnership(level);
                return false;
            }
            if (profile == null || profile.habit() == SpeciesProfile.Habit.UNCHANGED) {
                SeasonalLedger.Tree old = ledger.find(root);
                if (old == null) { releaseOwnership(level); return false; }
                if (!old.species.equals(species.getRegistryName())) {
                    releaseOwnership(level); ledger.forget(root); return false;
                }
                if (!initialized || catalogueRevision != SeasonalCatalogue.revision()) {
                    initialized = true; catalogueRevision = SeasonalCatalogue.revision();
                    captured = false; captureOverflow = false; finalizing = false;
                    finalizeIndex = finalizeRank = 0; ranked.clear();
                    releaseOwnership(level);
                    branches.clear(); leaves.clear(); capturedLeaves.clear(); capturedPositions.clear();
                    seenBranches.clear(); seenLeaves.clear(); topologyParents.clear(); due.clear(); restoration.clear();
                    scanningBranch = null; scanningLeaf = null;
                    for (SeasonalLedger.Ticket ticket : old.tickets.values()) if (!ticket.consumed) {
                        restoration.add(ticket);
                        if (cachedCells.add(ticket.pos.asLong())) work(level).claim(root.asLong(), ticket.pos);
                    }
                    branches.add(trunk); seenBranches.add(trunk.asLong());
                    topologyParents.put(trunk.asLong(), root.asLong());
                }
                expectedSpecies = species;
                expectedFamily = species.getFamily();
                if (!captured && !captureOverflow) capture(level, ledger, old, null, false);
                if (captured) restoreTickets(level, ledger, old,
                        new RootClock(clock.phase(), ledger.cycle()), null);
                if (restoration.isEmpty()) releaseOwnership(level);
                return !restoration.isEmpty();
            }
            RootClock rootClock = rootClock(level, root, profile.habit(), ledger.cycle());
            if (profile.habit() != SpeciesProfile.Habit.EVERGREEN
                    && profile.habit() != SpeciesProfile.Habit.TEMPERATE
                    && profile.habit() != SpeciesProfile.Habit.TROPICAL) return false;
            SeasonalLedger.Tree tree = ledger.ensure(level, root, species.getRegistryName());
            ledger.freezeTiming(tree, rootClock.cycle(), profile,
                    profile.habit() == SpeciesProfile.Habit.TROPICAL);
            if (!initialized || cycle != rootClock.cycle() || catalogueRevision != SeasonalCatalogue.revision()) {
                if (!initialized || cycle != rootClock.cycle()) ledger.prune(tree, rootClock.cycle());
                initialized = true; cycle = rootClock.cycle(); catalogueRevision = SeasonalCatalogue.revision();
                captured = false; autumnPrepared = summerPrepared = planStarted = planJustCompleted = false;
                captureOverflow = false;
                finalizing = false; finalizeIndex = finalizeRank = 0; ranked.clear();
                releaseOwnership(level);
                branches.clear(); leaves.clear(); capturedLeaves.clear(); capturedPositions.clear();
                seenBranches.clear(); seenLeaves.clear(); topologyParents.clear();
                due.clear(); restoration.clear(); scanningBranch = null; scanningLeaf = null;
                for (SeasonalLedger.Ticket ticket : tree.tickets.values()) if (!ticket.consumed) {
                    if (ticket.targetCycle <= cycle) restoration.add(ticket);
                    if (cachedCells.add(ticket.pos.asLong())) work(level).claim(root.asLong(), ticket.pos);
                }
                branches.add(trunk); seenBranches.add(trunk.asLong());
                topologyParents.put(trunk.asLong(), root.asLong());
            }
            expectedSpecies = species;
            expectedFamily = species.getFamily();
            if (profile.habit() == SpeciesProfile.Habit.EVERGREEN) {
                if (!captured && !captureOverflow) capture(level, ledger, tree, profile, false);
                return true; // Evergreen profiles provide visuals only; native DT owns growth and survival.
            }
            double leafOutEnd = tree.leafOutStart + tree.leafOutDuration;
            if (tree.carryCycle == cycle) leafOutEnd = Math.max(leafOutEnd, tree.carryLeafOutEnd);
            if (rootClock.phase() >= leafOutEnd && !summerPrepared) {
                summerPrepared = true;
                if (!tree.planComplete || tree.planCycle != cycle) resetCapture(level, tree, trunk);
            }
            boolean shedWindow = SeasonalSchedule.planEligible(rootClock.phase(), tree.shedStart);
            if (shedWindow && !autumnPrepared) {
                autumnPrepared = true;
                if (!tree.planComplete || tree.planCycle != cycle || !captured) resetCapture(level, tree, trunk);
            }
            if (!captured && !captureOverflow) capture(level, ledger, tree, profile, shedWindow);
            if (planJustCompleted) { planJustCompleted = false; return true; }
            if (shedWindow && captured) shedDue(level, ledger, tree, rootClock, profile);
            if (captured) restoreTickets(level, ledger, tree, rootClock, profile);
            return true; // Persistently rotate loaded roots; the next autumn must not require a chunk reload.
        }
        private void resetCapture(ServerLevel level, SeasonalLedger.Tree tree, BlockPos trunk) {
            captured = captureOverflow = finalizing = planStarted = false;
            finalizeIndex = finalizeRank = 0; ranked.clear();
            releaseOwnership(level);
            branches.clear(); leaves.clear(); capturedLeaves.clear(); capturedPositions.clear();
            seenBranches.clear(); seenLeaves.clear(); topologyParents.clear(); due.clear();
            scanningBranch = null; scanningLeaf = null;
            for (SeasonalLedger.Ticket ticket : tree.tickets.values()) if (!ticket.consumed
                    && cachedCells.add(ticket.pos.asLong())) work(level).claim(root.asLong(), ticket.pos);
            branches.add(trunk); seenBranches.add(trunk.asLong());
            topologyParents.put(trunk.asLong(), root.asLong());
        }
        private void capture(ServerLevel level, SeasonalLedger ledger, SeasonalLedger.Tree tree,
                             SpeciesProfile profile, boolean planEligible) {
            while (!finalizing && inspections < tickInspectionLimit && (scanningBranch != null || !branches.isEmpty())) {
                if (scanningBranch == null) {
                    scanningBranch = branches.removeFirst(); neighbor = 0;
                    if (cachedBranches.add(scanningBranch.asLong())) {
                        if (!ledger.claimManagedBranch(tree, scanningBranch)) {
                            captureOverflow = true;
                            return;
                        }
                        work(level).claimBranch(root.asLong(), scanningBranch);
                        ownedChunkKeys.add(new ChunkPos(scanningBranch).toLong());
                    }
                }
                if (!level.hasChunkAt(scanningBranch)) return;
                if (!inspected()) return;
                BlockState branchState = level.getBlockState(scanningBranch);
                if (!(branchState.getBlock() instanceof BranchBlock branchBlock)
                        || branchBlock.getFamily() != expectedFamily) { scanningBranch = null; continue; }
                while (neighbor < 6 && inspections < tickInspectionLimit) {
                    BlockPos pos = scanningBranch.relative(Direction.from3DDataValue(neighbor));
                    if (!level.hasChunkAt(pos)) return;
                    if (!inspected()) return;
                    neighbor++;
                    BlockState state = level.getBlockState(pos);
                    if (state.getBlock() instanceof BranchBlock branch && branch.getFamily() == expectedFamily
                            && seenBranches.add(pos.asLong())) {
                        branches.addLast(pos.immutable()); topologyParents.put(pos.asLong(), scanningBranch.asLong());
                    }
                    if (isManagedLeaf(state)
                            && nativeBranchSupportsLeaf(level, scanningBranch, branchBlock, branchState, state,
                                    Direction.from3DDataValue(neighbor - 1))
                            && seenLeaves.add(pos.asLong())) {
                        topologyParents.put(pos.asLong(), scanningBranch.asLong());
                        leaves.addLast(new LeafNode(pos.immutable(), scanningBranch,
                                5 - state.getValue(LeavesBlock.DISTANCE)));
                    }
                    if (seenBranches.size() > MAX_ROOT_BRANCHES || seenLeaves.size() > MAX_ROOT_LEAVES) {
                        captureOverflow = true; return;
                    }
                }
                if (neighbor == 6) scanningBranch = null;
            }
            if (scanningBranch != null || !branches.isEmpty()) return;
            while (!finalizing && inspections < tickInspectionLimit && (scanningLeaf != null || !leaves.isEmpty())) {
                if (scanningLeaf == null) { scanningLeaf = leaves.removeFirst(); neighbor = 0; }
                if (!level.hasChunkAt(scanningLeaf.pos()) || !level.hasChunkAt(scanningLeaf.support())) return;
                if (!inspected()) return;
                BlockState state = level.getBlockState(scanningLeaf.pos());
                BlockState support = level.getBlockState(scanningLeaf.support());
                if (!isManagedLeaf(state)) { scanningLeaf = null; continue; }
                if (neighbor == 0 && capturedPositions.add(scanningLeaf.pos().asLong())) {
                    capturedLeaves.add(new Candidate(scanningLeaf.pos(), scanningLeaf.support(), state, support,
                            scanningLeaf.depth(), 0, 0));
                    cache(level, scanningLeaf.pos());
                }
                while (neighbor < 6 && inspections < tickInspectionLimit) {
                    BlockPos pos = scanningLeaf.pos().relative(Direction.from3DDataValue(neighbor));
                    if (!level.hasChunkAt(pos)) return;
                    if (!inspected()) return;
                    neighbor++;
                    BlockState adjacent = level.getBlockState(pos);
                    if (isManagedLeaf(adjacent)
                            && nativeLeafSupportsLeaf(level, scanningLeaf.pos(), state, adjacent,
                                    Direction.from3DDataValue(neighbor - 1))
                            && seenLeaves.add(pos.asLong())) {
                        topologyParents.put(pos.asLong(), scanningLeaf.pos().asLong());
                        leaves.addLast(new LeafNode(pos.immutable(), scanningLeaf.pos(),
                                5 - adjacent.getValue(LeavesBlock.DISTANCE)));
                    }
                    if (seenLeaves.size() > MAX_ROOT_LEAVES) { captureOverflow = true; return; }
                }
                if (neighbor == 6) scanningLeaf = null;
            }
            if (scanningLeaf != null || !leaves.isEmpty()) return;
            finalizing = true;
            if (profile == null || !planEligible) {
                captured = true;
                for (long chunk : ownedChunkKeys) work(level).markBranchChunk(chunk);
                capturedLeaves.clear();
                return;
            }
            if (tree.planComplete && tree.planCycle == cycle) {
                Map<Long, SeasonalLedger.PlannedLeaf> plans = tree.plansView();
                while (finalizeIndex < capturedLeaves.size() && inspected()) {
                    Candidate leaf = capturedLeaves.get(finalizeIndex++);
                    SeasonalLedger.PlannedLeaf plan = plans.get(leaf.pos().asLong());
                    if (plan != null) due.add(new Candidate(plan.pos(), plan.support(), plan.state(),
                            plan.supportState(), leaf.depth(), plan.shedDeadline(), plan.restoreDeadline()));
                }
                if (finalizeIndex < capturedLeaves.size()) return;
                captured = true;
                for (long chunk : ownedChunkKeys) work(level).markBranchChunk(chunk);
                capturedLeaves.clear();
                return;
            }
            if (!planStarted) {
                ledger.beginPlan(tree, cycle, capturedLeaves.size());
                planStarted = true;
            }
            while (finalizeIndex < capturedLeaves.size() && inspected()) {
                Candidate leaf = capturedLeaves.get(finalizeIndex++);
                double hash = SeasonClock.fraction(tree.incarnation.getMostSignificantBits(), leaf.pos().asLong(), cycle);
                ranked.add(new RankedCandidate(leaf, hash));
            }
            if (finalizeIndex < capturedLeaves.size()) return;
            int count = capturedLeaves.size();
            while (!ranked.isEmpty() && inspected()) {
                RankedCandidate entry = ranked.remove();
                Candidate leaf = entry.leaf();
                int rank = finalizeRank++;
                double shedDeadline = SeasonalSchedule.deadline(tree.shedStart,
                        tree.shedEnd - tree.shedStart, rank, count, entry.hash());
                double restorationHash = SeasonClock.fraction(tree.incarnation.getLeastSignificantBits(),
                        leaf.pos().asLong(), cycle + 1);
                double restoreDeadline = SeasonalSchedule.deadline(tree.leafOutStart,
                        tree.leafOutDuration, count - 1 - rank, count, restorationHash);
                SeasonalLedger.PlannedLeaf plan = new SeasonalLedger.PlannedLeaf(leaf.pos(), leaf.support(),
                        leaf.state(), leaf.supportState(), rank, count, shedDeadline, restoreDeadline);
                ledger.addPlan(tree, plan);
                due.add(new Candidate(leaf.pos(), leaf.support(), leaf.state(), leaf.supportState(),
                        leaf.depth(), shedDeadline, restoreDeadline));
            }
            if (!ranked.isEmpty()) return;
            ledger.completePlan(tree);
            planJustCompleted = true;
            captured = true;
            for (long chunk : ownedChunkKeys) work(level).markBranchChunk(chunk);
            capturedLeaves.clear();
        }
        private boolean isManagedLeaf(BlockState state) {
            return expectedSpecies != null && state.getBlock() instanceof DynamicLeavesBlock leaves
                    && expectedSpecies.isValidLeafBlock(leaves);
        }
        /** Use DT's branch cell, including family-derived radius/meta and the kit's side orientation. */
        private boolean nativeBranchSupportsLeaf(LevelAccessor level, BlockPos branchPos,
                                                 BranchBlock branch, BlockState branchState,
                                                 BlockState leafState, Direction branchToLeaf) {
            if (!(leafState.getBlock() instanceof DynamicLeavesBlock leaves)) return false;
            Direction leafToBranch = branchToLeaf.getOpposite();
            Cell cell = branch.getHydrationCell(level, branchPos, branchState, leafToBranch,
                    leaves.getProperties(leafState));
            return cell.getValueFromSide(branchToLeaf) > 0;
        }
        /** Match DT's directional hydration contribution while keeping the topology monotonic. */
        private boolean nativeLeafSupportsLeaf(LevelAccessor level, BlockPos supportPos,
                                               BlockState support, BlockState child,
                                               Direction supportToChild) {
            if (!(support.getBlock() instanceof DynamicLeavesBlock source)
                    || !(child.getBlock() instanceof DynamicLeavesBlock target)) return false;
            if (support.getValue(LeavesBlock.DISTANCE) <= child.getValue(LeavesBlock.DISTANCE)) return false;
            LeavesProperties sourceProperties = source.getProperties(support);
            LeavesProperties targetProperties = target.getProperties(child);
            if (!sourceProperties.isCompatibleLeaves(targetProperties)) return false;
            // Directional cell lookup matters for conifer kits; monotonic hydration prevents equal-level cycles.
            Cell cell = source.getHydrationCell(level, supportPos, support,
                    supportToChild.getOpposite(), targetProperties);
            return cell.getValueFromSide(supportToChild) > 0;
        }
        private void cache(ServerLevel level, BlockPos pos) {
            LevelWork work = work(level);
            if (cachedCells.add(pos.asLong())) work.claim(root.asLong(), pos);
            if (cachedLeaves.add(pos.asLong())) {
                work.claimLeaf(root.asLong(), pos);
                work.markBranchChunk(new ChunkPos(pos).toLong());
            }
            for (Direction direction : Direction.values()) {
                BlockPos neighborPos = pos.relative(direction);
                if (cachedCells.add(neighborPos.asLong())) work.claim(root.asLong(), neighborPos);
            }
        }
        private void releaseOwnership(ServerLevel level) {
            LevelWork work = work(level);
            work.release(root.asLong(), cachedCells);
            work.releaseLeaves(root.asLong(), cachedLeaves);
            work.releaseBranches(root.asLong(), cachedBranches);
            ownedChunkKeys.clear();
        }
        private void invalidateTopology(ServerLevel level) {
            releaseOwnership(level);
            initialized = false; captured = false; autumnPrepared = false; captureOverflow = false;
            finalizing = false; finalizeIndex = finalizeRank = 0; ranked.clear();
        }
        private void shedDue(ServerLevel level, SeasonalLedger ledger, SeasonalLedger.Tree tree,
                             RootClock clock, SpeciesProfile profile) {
            List<Candidate> deferred = new ArrayList<>();
            int attempts = due.size();
            try {
                while (attempts-- > 0 && !due.isEmpty() && due.peek().deadline() <= clock.phase()
                        && inspections < tickInspectionLimit && mutations < tickMutationLimit) {
                    Candidate candidate = due.remove();
                    if (!inspected()) { deferred.add(candidate); break; }
                    if (!level.hasChunkAt(candidate.pos()) || !level.hasChunkAt(candidate.support())) {
                        deferred.add(candidate); continue;
                    }
                    SupportStatus supportPath = capturedPathConnected(level, tree, candidate.support(), cycle + 1);
                    if (supportPath == SupportStatus.DEFER) { deferred.add(candidate); continue; }
                    if (supportPath == SupportStatus.INVALID) continue;
                    BlockState actual = level.getBlockState(candidate.pos());
                    if (actual.getBlock() != candidate.state().getBlock()) continue;
                    BlockState actualSupport = level.getBlockState(candidate.support());
                    if (actualSupport.getBlock() != candidate.supportState().getBlock()) continue;
                    BlockPos snowPos = candidate.pos().above();
                    BlockState snow = level.getBlockState(snowPos);
                    // Reserve all three mutation attempts before shedding, so the snow
                    // correction cannot bypass the ordinary per-tick mutation budget.
                    if (snow.is(net.minecraft.world.level.block.Blocks.SNOW)
                            && (long) mutations + 3 > tickMutationLimit) {
                        deferred.add(candidate); break;
                    }
                    if (!mutate()) { deferred.add(candidate); break; }
                    if (level.setBlock(candidate.pos(), net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 2)) {
                        // Flag 2 does not notify the snow above this formerly supporting leaf.
                        // Preserve its layer count and settle it on the newly exposed surface.
                        if (snow.is(net.minecraft.world.level.block.Blocks.SNOW)
                                && !snow.canSurvive(level, snowPos)) {
                            mutate();
                            if (level.setBlock(snowPos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), 3)) {
                                BlockPos ground = level.getHeightmapPos(
                                        net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, snowPos);
                                if (ground.getY() < snowPos.getY()
                                        && level.getBiome(ground).value().shouldSnow(level, ground)) {
                                    mutate();
                                    level.setBlock(ground, snow, 3);
                                }
                            }
                        }
                        ledger.record(tree, candidate.pos(), candidate.support(), actual,
                                actualSupport, cycle + 1, candidate.restorationDeadline());
                    }
                }
            } finally {
                due.addAll(deferred);
            }
        }
        private void restoreTickets(ServerLevel level, SeasonalLedger ledger, SeasonalLedger.Tree tree,
                                    RootClock clock, SpeciesProfile profile) {
            boolean force = profile == null;
            double restoreClose = tree.carryCycle == clock.cycle()
                    ? Math.max(tree.shedStart, tree.carryShedStart) : tree.shedStart;
            if (!force && clock.phase() >= restoreClose) return;
            List<SeasonalLedger.Ticket> deferred = new ArrayList<>();
            int attempts = restoration.size();
            try {
                while (attempts-- > 0 && !restoration.isEmpty()
                        && (force || restoration.peek().deadline <= clock.phase())) {
                    if (!inspected() || mutations >= tickMutationLimit) return;
                    SeasonalLedger.Ticket ticket = restoration.remove();
                    if (ticket.consumed || (!force && ticket.targetCycle > clock.cycle())) continue;
                    if (!level.hasChunkAt(ticket.pos) || !level.hasChunkAt(ticket.support)) {
                        deferred.add(ticket); continue;
                    }
                    BlockState present = level.getBlockState(ticket.pos);
                    boolean validSupport = net.minecraftforge.registries.ForgeRegistries.BLOCKS.getKey(
                            level.getBlockState(ticket.support).getBlock()).equals(ticket.supportBlock);
                    SupportStatus support = supportConnected(level, tree, ticket);
                    if (support == SupportStatus.DEFER) { deferred.add(ticket); continue; }
                    if (!validSupport || support == SupportStatus.INVALID) {
                        ledger.resolve(ticket, SeasonalLedger.Outcome.UNSUPPORTED);
                        GenerationCanopyData.resolved(level, ticket); continue;
                    }
                    if (!present.isAir() || !present.getFluidState().isEmpty()) {
                        ledger.resolve(ticket, SeasonalLedger.Outcome.OBSTRUCTED);
                        GenerationCanopyData.resolved(level, ticket); continue;
                    }
                if (!(ticket.state.getBlock() instanceof DynamicLeavesBlock leavesBlock)
                        || expectedSpecies == null || !expectedSpecies.isValidLeafBlock(leavesBlock)) {
                        ledger.resolve(ticket, SeasonalLedger.Outcome.INVALID_STATE);
                        GenerationCanopyData.resolved(level, ticket); continue;
                    }
                    boolean neighborsLoaded = true;
                    for (Direction direction : Direction.values()) {
                        if (!level.hasChunkAt(ticket.pos.relative(direction)) || !inspected()) {
                            neighborsLoaded = false; break;
                        }
                    }
                    if (!neighborsLoaded) { deferred.add(ticket); continue; }
                    var properties = leavesBlock.getProperties(ticket.state);
                    boolean locationSuitable = nativeExistingLeafSiteSuitable(level, ticket.pos, properties);
                    boolean survivalLight = leavesBlock.hasAdequateLight(ticket.state, level, properties, ticket.pos);
                    boolean hydrated = leavesBlock.getHydrationLevelFromNeighbors(level, ticket.pos, properties) > 0;
                    int rejectionMask = (locationSuitable ? 0 : SeasonalLedger.LOCATION_REJECTED)
                            | (survivalLight ? 0 : SeasonalLedger.LIGHT_REJECTED)
                            | (hydrated ? 0 : SeasonalLedger.HYDRATION_REJECTED);
                    if (rejectionMask != 0) {
                        ledger.resolve(ticket, SeasonalLedger.Outcome.UNSUITABLE, rejectionMask);
                        GenerationCanopyData.resolved(level, ticket); continue;
                    }
                    if (!mutate()) { deferred.add(ticket); return; }
                    if (level.setBlock(ticket.pos, ticket.state, 2)) {
                        ledger.resolve(ticket, SeasonalLedger.Outcome.RESTORED);
                        GenerationCanopyData.resolved(level, ticket);
                        cache(level, ticket.pos);
                    } else deferred.add(ticket);
                }
            } finally {
                restoration.addAll(deferred);
            }
        }
        /** Native ground/fluid constraints without DT's stricter new-growth light predicate. */
        private boolean nativeExistingLeafSiteSuitable(ServerLevel level, BlockPos pos, LeavesProperties properties) {
            BlockState below = level.getBlockState(pos.below());
            return properties.canGrowOnGround()
                    || ((!below.canOcclude() || TreeHelper.isBranch(below)
                            || below.getBlock() instanceof LeavesBlock)
                            && !(below.getBlock() instanceof LiquidBlock));
        }
        private enum SupportStatus { CONNECTED, INVALID, DEFER }
        private SupportStatus capturedPathConnected(ServerLevel level, SeasonalLedger.Tree tree,
                                                    BlockPos support, long targetCycle) {
            Set<Long> walked = new HashSet<>();
            BlockPos cursor = support;
            for (int depth = 0; depth < 256 && walked.add(cursor.asLong()); depth++) {
                if (!level.hasChunkAt(cursor) || !inspected()) return SupportStatus.DEFER;
                BlockState state = level.getBlockState(cursor);
                if (!(state.getBlock() instanceof BranchBlock branch && branch.getFamily() == expectedFamily)
                        && !isManagedLeaf(state)) return SupportStatus.INVALID;
                if (state.getBlock() instanceof BranchBlock
                        && SeasonalLedger.get(level).isBranchTombstoned(tree, cursor, targetCycle))
                    return SupportStatus.INVALID;
                Long parent = topologyParents.get(cursor.asLong());
                if (parent == null) return SupportStatus.INVALID;
                if (parent == root.asLong()) return SupportStatus.CONNECTED;
                cursor = BlockPos.of(parent);
            }
            return SupportStatus.DEFER;
        }
        private SupportStatus supportConnected(ServerLevel level, SeasonalLedger.Tree tree, SeasonalLedger.Ticket ticket) {
            BlockPos support = ticket.support;
            Set<Long> walked = new HashSet<>();
            for (int depth = 0; depth < 256 && walked.add(support.asLong()); depth++) {
                if (!level.hasChunkAt(support) || !inspected()) return SupportStatus.DEFER;
                BlockState state = level.getBlockState(support);
                if (state.getBlock() instanceof BranchBlock)
                    return seenBranches.contains(support.asLong())
                            ? capturedPathConnected(level, tree, support, ticket.targetCycle) : SupportStatus.INVALID;
                SeasonalLedger.Ticket parent = tree.tickets.get(support.asLong());
                if (!isManagedLeaf(state))
                    return parent != null && !parent.consumed ? SupportStatus.DEFER : SupportStatus.INVALID;
                if (parent == null)
                    return seenLeaves.contains(support.asLong())
                            ? capturedPathConnected(level, tree, support, ticket.targetCycle)
                            : SupportStatus.INVALID;
                support = parent.support;
            }
            return SupportStatus.DEFER;
        }
    }
}
