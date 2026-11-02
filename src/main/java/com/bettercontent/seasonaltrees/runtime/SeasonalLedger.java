package com.bettercontent.seasonaltrees.runtime;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashSet;

import com.bettercontent.seasonaltrees.catalogue.SpeciesProfile;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.registries.ForgeRegistries;

/** A ticket exists only after a successful seasonal setBlock. Absence is never a repair request. */
public final class SeasonalLedger extends SavedData {
    public static final String ID = "better_seasonal_trees_tickets";
    public static final int SCHEMA_VERSION = 8;
    public static final int MAX_MANAGED_BRANCHES_PER_TREE = 16384;
    public enum Outcome { PENDING, RESTORED, OBSTRUCTED, UNSUPPORTED, UNSUITABLE, INVALID_STATE, UNKNOWN_LEGACY }
    public static final int LOCATION_REJECTED = 1, LIGHT_REJECTED = 2, HYDRATION_REJECTED = 4;
    public static final class Tree {
        public final BlockPos root;
        public final UUID incarnation;
        public final ResourceLocation species;
        public long shedCycle = Long.MIN_VALUE;
        public boolean generated;
        public long timingCycle = Long.MIN_VALUE;
        public double shedStart, shedEnd, leafOutStart, leafOutDuration;
        public SpeciesProfile.Habit timingHabit = SpeciesProfile.Habit.UNCHANGED;
        public double bloomStart, bloomEnd;
        public int autumnColor;
        public String bloomVisual = "";
        public boolean visualTimingStored;
        public boolean tropicalEligible;
        public long carryCycle = Long.MIN_VALUE;
        public double carryLeafOutEnd, carryShedStart;
        public final Map<Long, Ticket> tickets = new LinkedHashMap<>();
        public long planCycle = Long.MIN_VALUE;
        public boolean planComplete;
        public int planSize;
        private Map<Long, PlannedLeaf> plans = new LinkedHashMap<>();
        public Map<Long, PlannedLeaf> plansView() { return Collections.unmodifiableMap(plans); }
        private final Map<Long, Long> prunedBranches = new LinkedHashMap<>();
        public Map<Long, Long> prunedBranchesView() { return Collections.unmodifiableMap(prunedBranches); }
        private final Set<Long> managedBranches = new LinkedHashSet<>();
        public Set<Long> managedBranchesView() { return Collections.unmodifiableSet(managedBranches); }
        public boolean branchIndexOverflow;
        Tree(BlockPos root, UUID incarnation, ResourceLocation species) {
            this.root = root.immutable(); this.incarnation = incarnation; this.species = species;
        }
    }
    public static final class Ticket {
        public final BlockPos pos, support;
        public final BlockState state;
        public final ResourceLocation supportBlock;
        public final long targetCycle;
        public final double deadline;
        public boolean consumed;
        public Outcome outcome;
        public int suitabilityMask;
        long ownerRoot = Long.MIN_VALUE;
        Ticket(BlockPos pos, BlockPos support, BlockState state, ResourceLocation supportBlock,
               long targetCycle, double deadline, boolean consumed) {
            this.pos = pos.immutable(); this.support = support.immutable(); this.state = state;
            this.supportBlock = supportBlock; this.targetCycle = targetCycle;
            this.deadline = deadline; this.consumed = consumed;
            this.outcome = consumed ? Outcome.UNKNOWN_LEGACY : Outcome.PENDING;
        }
    }
    /** A capture plan is scheduling state, never restoration provenance. */
    public record PlannedLeaf(BlockPos pos, BlockPos support, BlockState state, BlockState supportState,
                              int rank, int count, double shedDeadline, double restoreDeadline) {
        public PlannedLeaf {
            pos = pos.immutable(); support = support.immutable();
        }
    }
    final Map<Long, Tree> trees = new LinkedHashMap<>();
    /** A removed generated root must never re-admit an old leaf-chunk marker. */
    private final Set<UUID> removedGenerations = new LinkedHashSet<>();
    private final Map<Long, Set<Long>> pendingTicketOwners = new LinkedHashMap<>();
    private final Map<Long, Set<Long>> managedBranchOwners = new LinkedHashMap<>();
    long cycle;
    int lastCycleTick = -1;
    int lastCycleDuration;
    long lastGameTick = -1;

    public static SeasonalLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(SeasonalLedger::load, SeasonalLedger::new, ID);
    }
    public Tree find(BlockPos root) { return trees.get(root.asLong()); }
    public boolean removedGeneration(UUID id) { return removedGenerations.contains(id); }
    public void tombstoneGeneration(UUID id) {
        if (removedGenerations.add(id)) setDirty();
        for (Tree tree : List.copyOf(trees.values()))
            if (tree.generated && tree.incarnation.equals(id)) forget(tree.root);
    }
    /** Only an exact DT worldgen receipt can install this generation identity. */
    public Tree admitGenerated(BlockPos root, ResourceLocation species, UUID generation) {
        if (removedGenerations.contains(generation)) return null;
        Tree existing = find(root);
        if (existing != null) return existing.incarnation.equals(generation) && existing.species.equals(species)
                ? existing : null;
        Tree born = new Tree(root, generation, species);
        born.generated = true;
        trees.put(root.asLong(), born);
        setDirty();
        return born;
    }
    public Tree ensure(ServerLevel level, BlockPos root, ResourceLocation species) {
        Tree prior = trees.get(root.asLong());
        if (prior != null && prior.species.equals(species)) return prior;
        if (prior != null) {
            if (prior.generated) removedGenerations.add(prior.incarnation);
            for (Ticket ticket : prior.tickets.values()) unindex(ticket);
            unindexBranches(prior);
        }
        Tree tree = new Tree(root, UUID.randomUUID(), species);
        trees.put(root.asLong(), tree); setDirty(); return tree;
    }
    public void forget(BlockPos root) {
        Tree removed = trees.remove(root.asLong());
        if (removed != null) {
            if (removed.generated) removedGenerations.add(removed.incarnation);
            for (Ticket ticket : removed.tickets.values()) unindex(ticket);
            unindexBranches(removed);
            setDirty();
        }
    }
    public Set<Long> pendingTicketOwnersAt(BlockPos pos) {
        Set<Long> owners = pendingTicketOwners.get(pos.asLong());
        return owners == null ? Set.of() : Collections.unmodifiableSet(owners);
    }
    /** Adds one bounded, persisted branch-to-root ownership edge. */
    public boolean claimManagedBranch(Tree tree, BlockPos branch) {
        long branchKey = branch.asLong();
        if (tree.managedBranches.contains(branchKey)) return true;
        if (tree.managedBranches.size() >= MAX_MANAGED_BRANCHES_PER_TREE) {
            if (!tree.branchIndexOverflow) {
                tree.branchIndexOverflow = true;
                setDirty();
            }
            return false;
        }
        tree.managedBranches.add(branchKey);
        managedBranchOwners.computeIfAbsent(branchKey, ignored -> new HashSet<>()).add(tree.root.asLong());
        setDirty();
        return true;
    }
    public Set<Long> managedBranchOwnersAt(BlockPos branch) {
        Set<Long> owners = managedBranchOwners.get(branch.asLong());
        return owners == null ? Set.of() : Set.copyOf(owners);
    }
    private void indexBranch(Tree tree, long branchKey) {
        managedBranchOwners.computeIfAbsent(branchKey, ignored -> new HashSet<>()).add(tree.root.asLong());
    }
    private void unindexBranches(Tree tree) {
        for (long branchKey : tree.managedBranches) {
            Set<Long> owners = managedBranchOwners.get(branchKey);
            if (owners != null) {
                owners.remove(tree.root.asLong());
                if (owners.isEmpty()) managedBranchOwners.remove(branchKey);
            }
        }
    }
    private void index(Ticket ticket, long rootKey) {
        ticket.ownerRoot = rootKey;
        if (!ticket.consumed)
            pendingTicketOwners.computeIfAbsent(ticket.pos.asLong(), ignored -> new HashSet<>()).add(rootKey);
    }
    private void unindex(Ticket ticket) {
        Set<Long> owners = pendingTicketOwners.get(ticket.pos.asLong());
        if (owners != null) {
            owners.remove(ticket.ownerRoot);
            if (owners.isEmpty()) pendingTicketOwners.remove(ticket.pos.asLong());
        }
    }
    /** A published profile cannot retime a cycle already underway. */
    public void freezeTiming(Tree tree, long currentCycle, SpeciesProfile profile) {
        freezeTiming(tree, currentCycle, profile, false);
    }
    public void freezeTiming(Tree tree, long currentCycle, SpeciesProfile profile, boolean tropicalEligible) {
        if (tree.timingCycle == currentCycle) {
            if (!tree.visualTimingStored) freezeVisualTiming(tree, profile, tropicalEligible);
            return;
        }
        boolean outstanding = tree.timingCycle != Long.MIN_VALUE && tree.tickets.values().stream()
                .anyMatch(ticket -> !ticket.consumed && ticket.targetCycle <= currentCycle);
        if (outstanding) {
            double previousCarryEnd = tree.carryCycle == tree.timingCycle ? tree.carryLeafOutEnd : 0;
            double previousCarryShedStart = tree.carryCycle == tree.timingCycle ? tree.carryShedStart : 0;
            tree.carryCycle = currentCycle;
            tree.carryLeafOutEnd = Math.max(previousCarryEnd, tree.leafOutStart + tree.leafOutDuration);
            tree.carryShedStart = Math.max(previousCarryShedStart, tree.shedStart);
        } else {
            tree.carryCycle = Long.MIN_VALUE;
            tree.carryLeafOutEnd = tree.carryShedStart = 0;
        }
        tree.timingCycle = currentCycle;
        tree.shedStart = profile.shedStart();
        tree.shedEnd = profile.shedEnd();
        tree.leafOutStart = profile.leafOutStart();
        tree.leafOutDuration = profile.leafOutDuration();
        freezeVisualTiming(tree, profile, tropicalEligible);
        setDirty();
    }
    private void freezeVisualTiming(Tree tree, SpeciesProfile profile, boolean tropicalEligible) {
        tree.timingHabit = profile.habit();
        tree.bloomStart = profile.bloomStart();
        tree.bloomEnd = profile.bloomEnd();
        tree.autumnColor = profile.autumnColor();
        tree.bloomVisual = profile.bloomVisual();
        tree.tropicalEligible = profile.habit() == SpeciesProfile.Habit.TROPICAL && tropicalEligible;
        tree.visualTimingStored = true;
        setDirty();
    }
    public long cycle() { return cycle; }
    public void observeCycle(int tick, int duration, long gameTick) {
        boolean changedCycle = false;
        if (lastCycleTick >= 0 && lastCycleDuration > 0 && duration > 0
                && lastGameTick >= 0 && gameTick >= lastGameTick
                && (double) lastCycleTick / lastCycleDuration - (double) tick / duration > .5) {
            cycle++; changedCycle = true;
        }
        if (lastCycleTick != tick || lastCycleDuration != duration || lastGameTick != gameTick) {
            lastCycleTick = tick; lastCycleDuration = duration; lastGameTick = gameTick;
            if (changedCycle || gameTick % 1200 == 0) setDirty();
        }
    }
    public void record(Tree tree, BlockPos pos, BlockPos support, BlockState state,
                       BlockState supportState, long targetCycle, double deadline) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(supportState.getBlock());
        Ticket prior = tree.tickets.put(pos.asLong(), new Ticket(pos, support, state, id, targetCycle, deadline, false));
        if (prior != null) unindex(prior);
        index(tree.tickets.get(pos.asLong()), tree.root.asLong());
        tree.plans.remove(pos.asLong());
        setDirty();
    }
    public void beginPlan(Tree tree, long cycle, int count) {
        tree.planCycle = cycle; tree.planComplete = false; tree.planSize = count;
        tree.plans = new LinkedHashMap<>(); setDirty();
    }
    public void addPlan(Tree tree, PlannedLeaf leaf) {
        if (tree.planComplete || tree.planCycle == Long.MIN_VALUE || leaf.count() != tree.planSize)
            throw new IllegalStateException("Capture plan is not accepting leaves");
        tree.plans.put(leaf.pos().asLong(), leaf); setDirty();
    }
    public void completePlan(Tree tree) {
        if (tree.plans.size() != tree.planSize) throw new IllegalStateException("Incomplete capture plan");
        tree.planComplete = true; setDirty();
    }
    public void tombstoneBranch(Tree tree, BlockPos branch, long throughTargetCycle) {
        tree.prunedBranches.merge(branch.asLong(), throughTargetCycle, Math::max);
        setDirty();
    }
    public boolean isBranchTombstoned(Tree tree, BlockPos branch, long targetCycle) {
        return tree.prunedBranches.getOrDefault(branch.asLong(), Long.MIN_VALUE) >= targetCycle;
    }
    public void resolve(Ticket ticket, Outcome outcome) {
        resolve(ticket, outcome, 0);
    }
    public void resolve(Ticket ticket, Outcome outcome, int suitabilityMask) {
        if (outcome == Outcome.PENDING || outcome == Outcome.UNKNOWN_LEGACY)
            throw new IllegalArgumentException("Ticket resolution requires a concrete outcome");
        if (!ticket.consumed) {
            ticket.consumed = true;
            unindex(ticket);
            ticket.outcome = outcome;
            ticket.suitabilityMask = outcome == Outcome.UNSUITABLE ? suitabilityMask : 0;
            setDirty();
        }
    }
    public void prune(Tree tree, long currentCycle) {
        // A ticket in an unloaded chunk may miss its first spring. Never erase
        // pending provenance merely because the calendar has rolled over.
        if (tree.tickets.values().removeIf(t -> t.consumed && t.targetCycle < currentCycle)) setDirty();
        long oldestPending = tree.tickets.values().stream().filter(t -> !t.consumed)
                .mapToLong(t -> t.targetCycle).min().orElse(Long.MAX_VALUE);
        if (tree.prunedBranches.values().removeIf(target -> target < currentCycle && target < oldestPending))
            setDirty();
    }

    static SeasonalLedger load(CompoundTag tag) {
        SeasonalLedger ledger = new SeasonalLedger();
        int schema = tag.getInt("schemaVersion");
        if (schema > SCHEMA_VERSION) throw new IllegalArgumentException("Unsupported seasonal ledger schema " + schema);
        ledger.cycle = tag.getLong("cycle"); ledger.lastCycleTick = tag.getInt("lastCycleTick");
        if (schema >= 8) for (Tag dead : tag.getList("removedGenerations", Tag.TAG_COMPOUND)) {
            CompoundTag id = (CompoundTag) dead;
            if (id.hasUUID("id")) ledger.removedGenerations.add(id.getUUID("id"));
        }
        ledger.lastCycleDuration = tag.getInt("lastCycleDuration"); ledger.lastGameTick = tag.getLong("lastGameTick");
        for (Tag value : tag.getList("trees", Tag.TAG_COMPOUND)) {
            CompoundTag treeTag = (CompoundTag) value;
            ResourceLocation species = ResourceLocation.tryParse(treeTag.getString("species"));
            if (species == null || !treeTag.hasUUID("incarnation")) continue;
            Tree tree = new Tree(BlockPos.of(treeTag.getLong("root")), treeTag.getUUID("incarnation"), species);
            tree.shedCycle = treeTag.getLong("shedCycle");
            tree.generated = schema >= 8 && treeTag.getBoolean("generated");
            if (schema >= 5) {
                tree.planCycle = treeTag.getLong("planCycle");
                tree.planComplete = treeTag.getBoolean("planComplete");
                tree.planSize = treeTag.getInt("planSize");
            }
            if (schema >= 6) {
                tree.branchIndexOverflow = treeTag.getBoolean("branchIndexOverflow");
                ListTag managed = treeTag.getList("managedBranches", Tag.TAG_COMPOUND);
                int loadedBranches = 0;
                for (Tag branchValue : managed) {
                    if (loadedBranches++ >= MAX_MANAGED_BRANCHES_PER_TREE) {
                        tree.branchIndexOverflow = true;
                        break;
                    }
                    CompoundTag branchTag = (CompoundTag) branchValue;
                    BlockPos branch = tree.root.offset(branchTag.getInt("relX"),
                            branchTag.getInt("relY"), branchTag.getInt("relZ"));
                    tree.managedBranches.add(branch.asLong());
                }
            }
            if (schema >= 4 && treeTag.contains("timingCycle", Tag.TAG_LONG)) {
                tree.timingCycle = treeTag.getLong("timingCycle");
                tree.shedStart = treeTag.getDouble("shedStart");
                tree.shedEnd = treeTag.getDouble("shedEnd");
                tree.leafOutStart = treeTag.getDouble("leafOutStart");
                tree.leafOutDuration = treeTag.getDouble("leafOutDuration");
                tree.carryCycle = treeTag.contains("carryCycle", Tag.TAG_LONG)
                        ? treeTag.getLong("carryCycle") : Long.MIN_VALUE;
                tree.carryLeafOutEnd = treeTag.getDouble("carryLeafOutEnd");
                tree.carryShedStart = treeTag.getDouble("carryShedStart");
            }
            if (schema >= 7 && treeTag.contains("timingHabit", Tag.TAG_STRING)) {
                try { tree.timingHabit = SpeciesProfile.Habit.valueOf(treeTag.getString("timingHabit")); }
                catch (IllegalArgumentException ignored) { tree.timingHabit = SpeciesProfile.Habit.UNCHANGED; }
                tree.bloomStart = treeTag.getDouble("bloomStart");
                tree.bloomEnd = treeTag.getDouble("bloomEnd");
                tree.autumnColor = treeTag.getInt("autumnColor");
                tree.bloomVisual = treeTag.getString("bloomVisual");
                tree.visualTimingStored = true;
                tree.tropicalEligible = treeTag.getBoolean("tropicalEligible");
            }
            if (schema >= 2) {
                List<BlockState> palette = new ArrayList<>();
                for (Tag stateTag : treeTag.getList("palette", Tag.TAG_COMPOUND))
                    palette.add(readState((CompoundTag) stateTag));
                for (Tag chunkValue : treeTag.getList("chunks", Tag.TAG_COMPOUND)) {
                    CompoundTag chunkTag = (CompoundTag) chunkValue;
                    long chunkKey = chunkTag.getLong("chunk");
                    for (Tag ticketValue : chunkTag.getList("tickets", Tag.TAG_COMPOUND)) {
                        CompoundTag t = (CompoundTag) ticketValue;
                        int stateIndex = t.getInt("stateIndex");
                        if (stateIndex < 0 || stateIndex >= palette.size()) continue;
                        BlockState state = palette.get(stateIndex);
                        ResourceLocation support = ResourceLocation.tryParse(t.getString("supportBlock"));
                        if (state == null || support == null) continue;
                        BlockPos pos = tree.root.offset(t.getInt("relX"), t.getInt("relY"), t.getInt("relZ"));
                        if (new ChunkPos(pos).toLong() != chunkKey) continue;
                        BlockPos supportPos = tree.root.offset(t.getInt("supportRelX"),
                                t.getInt("supportRelY"), t.getInt("supportRelZ"));
                        Ticket ticket = new Ticket(pos, supportPos, state, support,
                                t.getLong("targetCycle"), t.getDouble("deadline"), t.getBoolean("consumed"));
                        if (schema >= 3) {
                            ticket.outcome = readOutcome(t.getString("outcome"), ticket.consumed);
                            ticket.suitabilityMask = t.getInt("suitabilityMask");
                        }
                        tree.tickets.put(ticket.pos.asLong(), ticket);
                        ledger.index(ticket, tree.root.asLong());
                    }
                }
                if (schema >= 5) for (Tag planValue : treeTag.getList("plans", Tag.TAG_COMPOUND)) {
                    CompoundTag p = (CompoundTag) planValue;
                    int stateIndex = p.getInt("stateIndex"), supportIndex = p.getInt("supportStateIndex");
                    if (stateIndex < 0 || stateIndex >= palette.size()
                            || supportIndex < 0 || supportIndex >= palette.size()) continue;
                    BlockState state = palette.get(stateIndex), supportState = palette.get(supportIndex);
                    if (state == null || supportState == null) continue;
                    BlockPos pos = tree.root.offset(p.getInt("relX"), p.getInt("relY"), p.getInt("relZ"));
                    BlockPos support = tree.root.offset(p.getInt("supportRelX"),
                            p.getInt("supportRelY"), p.getInt("supportRelZ"));
                    PlannedLeaf leaf = new PlannedLeaf(pos, support, state, supportState,
                            p.getInt("rank"), tree.planSize, p.getDouble("shedDeadline"),
                            p.getDouble("restoreDeadline"));
                    tree.plans.put(pos.asLong(), leaf);
                }
                if (schema >= 5) for (Tag pruneValue : treeTag.getList("prunedBranches", Tag.TAG_COMPOUND)) {
                    CompoundTag p = (CompoundTag) pruneValue;
                    BlockPos pos = tree.root.offset(p.getInt("relX"), p.getInt("relY"), p.getInt("relZ"));
                    tree.prunedBranches.put(pos.asLong(), p.getLong("throughTargetCycle"));
                }
            } else {
                // Read the original flat format once; the next save writes chunked v2.
                for (Tag ticketValue : treeTag.getList("tickets", Tag.TAG_COMPOUND)) {
                    CompoundTag t = (CompoundTag) ticketValue;
                    BlockState state = readState(t.getCompound("state"));
                    ResourceLocation support = ResourceLocation.tryParse(t.getString("supportBlock"));
                    if (state == null || support == null) continue;
                    Ticket ticket = new Ticket(BlockPos.of(t.getLong("pos")), BlockPos.of(t.getLong("support")),
                            state, support, t.getLong("targetCycle"), t.getDouble("deadline"), t.getBoolean("consumed"));
                    tree.tickets.put(ticket.pos.asLong(), ticket);
                    ledger.index(ticket, tree.root.asLong());
                }
                ledger.setDirty();
            }
            ledger.trees.put(tree.root.asLong(), tree);
            for (long branchKey : tree.managedBranches) ledger.indexBranch(tree, branchKey);
        }
        return ledger;
    }
    @Override public CompoundTag save(CompoundTag tag) {
        tag.putInt("schemaVersion", SCHEMA_VERSION);
        ListTag removed = new ListTag();
        for (UUID id : removedGenerations) {
            CompoundTag dead = new CompoundTag(); dead.putUUID("id", id); removed.add(dead);
        }
        tag.put("removedGenerations", removed);
        tag.putLong("cycle", cycle); tag.putInt("lastCycleTick", lastCycleTick);
        tag.putInt("lastCycleDuration", lastCycleDuration); tag.putLong("lastGameTick", lastGameTick);
        ListTag treesTag = new ListTag();
        for (Tree tree : trees.values()) {
            CompoundTag t = new CompoundTag();
            t.putLong("root", tree.root.asLong()); t.putUUID("incarnation", tree.incarnation);
            t.putString("species", tree.species.toString()); t.putLong("shedCycle", tree.shedCycle);
            t.putBoolean("generated", tree.generated);
            t.putLong("planCycle", tree.planCycle); t.putBoolean("planComplete", tree.planComplete);
            t.putInt("planSize", tree.planSize);
            t.putLong("timingCycle", tree.timingCycle);
            t.putDouble("shedStart", tree.shedStart); t.putDouble("shedEnd", tree.shedEnd);
            t.putDouble("leafOutStart", tree.leafOutStart); t.putDouble("leafOutDuration", tree.leafOutDuration);
            t.putString("timingHabit", tree.timingHabit.name());
            t.putDouble("bloomStart", tree.bloomStart); t.putDouble("bloomEnd", tree.bloomEnd);
            t.putInt("autumnColor", tree.autumnColor); t.putString("bloomVisual", tree.bloomVisual);
            t.putBoolean("tropicalEligible", tree.tropicalEligible);
            t.putLong("carryCycle", tree.carryCycle);
            t.putDouble("carryLeafOutEnd", tree.carryLeafOutEnd);
            t.putDouble("carryShedStart", tree.carryShedStart);
            t.putBoolean("branchIndexOverflow", tree.branchIndexOverflow);
            ListTag palette = new ListTag();
            Map<BlockState, Integer> paletteIndices = new LinkedHashMap<>();
            Map<Long, ListTag> chunkTickets = new LinkedHashMap<>();
            for (Ticket ticket : tree.tickets.values()) {
                CompoundTag item = new CompoundTag();
                item.putInt("relX", ticket.pos.getX() - tree.root.getX());
                item.putInt("relY", ticket.pos.getY() - tree.root.getY());
                item.putInt("relZ", ticket.pos.getZ() - tree.root.getZ());
                item.putInt("supportRelX", ticket.support.getX() - tree.root.getX());
                item.putInt("supportRelY", ticket.support.getY() - tree.root.getY());
                item.putInt("supportRelZ", ticket.support.getZ() - tree.root.getZ());
                int stateIndex = paletteIndices.computeIfAbsent(ticket.state, state -> {
                    palette.add(writeState(state));
                    return palette.size() - 1;
                });
                item.putInt("stateIndex", stateIndex);
                item.putString("supportBlock", ticket.supportBlock.toString());
                item.putLong("targetCycle", ticket.targetCycle); item.putDouble("deadline", ticket.deadline);
                item.putBoolean("consumed", ticket.consumed);
                item.putString("outcome", ticket.outcome.name());
                item.putInt("suitabilityMask", ticket.suitabilityMask);
                chunkTickets.computeIfAbsent(new ChunkPos(ticket.pos).toLong(), ignored -> new ListTag()).add(item);
            }
            ListTag plans = new ListTag();
            for (PlannedLeaf leaf : tree.plans.values()) {
                CompoundTag item = new CompoundTag();
                item.putInt("relX", leaf.pos().getX() - tree.root.getX());
                item.putInt("relY", leaf.pos().getY() - tree.root.getY());
                item.putInt("relZ", leaf.pos().getZ() - tree.root.getZ());
                item.putInt("supportRelX", leaf.support().getX() - tree.root.getX());
                item.putInt("supportRelY", leaf.support().getY() - tree.root.getY());
                item.putInt("supportRelZ", leaf.support().getZ() - tree.root.getZ());
                item.putInt("stateIndex", paletteIndices.computeIfAbsent(leaf.state(), state -> {
                    palette.add(writeState(state)); return palette.size() - 1;
                }));
                item.putInt("supportStateIndex", paletteIndices.computeIfAbsent(leaf.supportState(), state -> {
                    palette.add(writeState(state)); return palette.size() - 1;
                }));
                item.putInt("rank", leaf.rank());
                item.putDouble("shedDeadline", leaf.shedDeadline());
                item.putDouble("restoreDeadline", leaf.restoreDeadline());
                plans.add(item);
            }
            ListTag prunedBranches = new ListTag();
            for (Map.Entry<Long, Long> prune : tree.prunedBranches.entrySet()) {
                BlockPos pos = BlockPos.of(prune.getKey());
                CompoundTag item = new CompoundTag();
                item.putInt("relX", pos.getX() - tree.root.getX());
                item.putInt("relY", pos.getY() - tree.root.getY());
                item.putInt("relZ", pos.getZ() - tree.root.getZ());
                item.putLong("throughTargetCycle", prune.getValue());
                prunedBranches.add(item);
            }
            ListTag managedBranches = new ListTag();
            for (long branchKey : tree.managedBranches) {
                BlockPos pos = BlockPos.of(branchKey);
                CompoundTag item = new CompoundTag();
                item.putInt("relX", pos.getX() - tree.root.getX());
                item.putInt("relY", pos.getY() - tree.root.getY());
                item.putInt("relZ", pos.getZ() - tree.root.getZ());
                managedBranches.add(item);
            }
            ListTag chunks = new ListTag();
            for (Map.Entry<Long, ListTag> chunk : chunkTickets.entrySet()) {
                CompoundTag bucket = new CompoundTag();
                bucket.putLong("chunk", chunk.getKey());
                bucket.put("tickets", chunk.getValue());
                chunks.add(bucket);
            }
            t.put("palette", palette);
            t.put("chunks", chunks);
            t.put("plans", plans);
            t.put("prunedBranches", prunedBranches);
            t.put("managedBranches", managedBranches);
            treesTag.add(t);
        }
        tag.put("trees", treesTag); return tag;
    }
    private static Outcome readOutcome(String value, boolean consumed) {
        try {
            Outcome parsed = Outcome.valueOf(value);
            if (consumed == (parsed != Outcome.PENDING)) return parsed;
        } catch (IllegalArgumentException ignored) {
            // A malformed or older ticket must never claim a false resolution reason.
        }
        return consumed ? Outcome.UNKNOWN_LEGACY : Outcome.PENDING;
    }
    private static CompoundTag writeState(BlockState state) {
        CompoundTag tag = new CompoundTag();
        tag.putString("block", ForgeRegistries.BLOCKS.getKey(state.getBlock()).toString());
        CompoundTag properties = new CompoundTag();
        for (Property<?> property : state.getProperties())
            properties.putString(property.getName(), propertyValueName(state, property));
        tag.put("properties", properties); return tag;
    }
    private static <T extends Comparable<T>> String propertyValueName(BlockState state, Property<T> property) {
        return property.getName(state.getValue(property));
    }
    private static BlockState readState(CompoundTag tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("block"));
        if (id == null || !ForgeRegistries.BLOCKS.containsKey(id)) return null;
        BlockState state = ForgeRegistries.BLOCKS.getValue(id).defaultBlockState();
        CompoundTag properties = tag.getCompound("properties");
        for (String key : properties.getAllKeys()) {
            Property<?> property = state.getBlock().getStateDefinition().getProperty(key);
            if (property != null) state = withProperty(state, property, properties.getString(key));
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState withProperty(BlockState state, Property<T> property, String value) {
        Optional<T> parsed = property.getValue(value);
        return parsed.isPresent() ? state.setValue(property, parsed.get()) : state;
    }
}
