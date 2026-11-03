package com.bettercontent.seasonaltrees.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;

import com.bettercontent.seasonaltrees.catalogue.SeasonalCatalogue;
import com.bettercontent.seasonaltrees.catalogue.SpeciesProfile;
import com.bettercontent.seasonaltrees.scheduler.SeasonClock;
import com.bettercontent.seasonaltrees.scheduler.SpringBirthSchedule;
import com.bettercontent.seasonaltrees.scheduler.SeasonalSchedule;
import com.ferreusveritas.dynamictrees.api.cell.Cell;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.SnowyDirtBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ImposterProtoChunk;
import net.minecraftforge.event.level.ChunkDataEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.registries.ForgeRegistries;

/** Forge chunk-data receipt for exact native leaves written by a new DT tree. No worker touches SavedData. */
public final class GenerationCanopyData {
    public static final String KEY = "better_seasonal_trees:birth";
    private static final int CHUNK_WIDTH = 16;
    private static final int FEATURE_DIAMETER_CHUNKS = 3; // FEATURES writable radius one.
    static long chunkSlots(int minBuildHeight, int maxBuildHeight) {
        return (long) CHUNK_WIDTH * CHUNK_WIDTH * Math.max(0L, (long) maxBuildHeight - minBuildHeight);
    }
    /** One leaf position plus one distinct root-header position per physical chunk block slot. */
    static long maxReceiptEntries(int minBuildHeight, int maxBuildHeight) {
        return 2L * chunkSlots(minBuildHeight, maxBuildHeight);
    }
    static long maxGenerationPositions(int minBuildHeight, int maxBuildHeight) {
        return (long) FEATURE_DIAMETER_CHUNKS * FEATURE_DIAMETER_CHUNKS
                * chunkSlots(minBuildHeight, maxBuildHeight);
    }
    private static final Map<ChunkAccess, CompoundTag> CARRIERS = Collections.synchronizedMap(new WeakHashMap<>());
    private GenerationCanopyData() {}

    public static void transfer(ChunkAccess source, ChunkAccess target) {
        synchronized (CARRIERS) {
            CompoundTag data = CARRIERS.get(source);
            if (data != null) CARRIERS.put(target, data.copy());
        }
    }
    public static CompoundTag snapshot(ChunkAccess chunk) {
        synchronized (CARRIERS) {
            CompoundTag data = CARRIERS.get(chunk);
            if (data == null && chunk instanceof ImposterProtoChunk wrapper)
                data = CARRIERS.get(wrapper.getWrapped());
            return data == null ? new CompoundTag() : data.copy();
        }
    }
    public static void replace(ChunkAccess chunk, CompoundTag data) {
        synchronized (CARRIERS) {
            if (data.isEmpty()) CARRIERS.remove(chunk);
            else CARRIERS.put(chunk, data.copy());
            chunk.setUnsaved(true);
        }
    }
    /** Native geometry, not an arbitrary receipt cap, bounds successful FEATURES writes. */
    public static boolean stage(WorldGenRegion region, BlockPos root, Species species, Set<BlockPos> writes) {
        if (species.getRegistryName() == null || writes.isEmpty()
                || writes.size() > maxGenerationPositions(region.getMinBuildHeight(), region.getMaxBuildHeight())
                || region.getChunk(root.getX() >> 4, root.getZ() >> 4).getStatus()
                    .isOrAfter(net.minecraft.world.level.chunk.ChunkStatus.FULL))
            return false;
        Map<ChunkAccess, ListTag> additions = new HashMap<>();
        UUID generation = UUID.randomUUID();
        ChunkAccess origin = region.getChunk(root.getX() >> 4, root.getZ() >> 4);
        // A header in the root chunk allows removal to tombstone this exact incarnation before
        // any later leaf chunk is loaded; never infer generation from a normal root discovery.
        CompoundTag header = new CompoundTag();
        header.putLong("root", root.asLong()); header.putUUID("generation", generation);
        header.putString("species", species.getRegistryName().toString());
        header.putBoolean("tropical", sereneseasons.api.season.SeasonHelper.usesTropicalSeasons(region.getBiome(root)));
        header.putBoolean("header", true);
        additions.computeIfAbsent(origin, ignored -> new ListTag()).add(header);
        Map<BlockPos, BlockState> exactLeaves = new HashMap<>();
        for (BlockPos pos : writes) {
            if (!region.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            ChunkAccess chunk = region.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            if (chunk.getStatus().isOrAfter(net.minecraft.world.level.chunk.ChunkStatus.FULL)) continue;
            BlockState state = region.getBlockState(pos);
            if (state.getBlock() instanceof DynamicLeavesBlock leaves && species.isValidLeafBlock(leaves))
                exactLeaves.put(pos, state);
        }
        // Every exact new native leaf is an initialization intent. Only leaves reachable from
        // new branch writes by positive native hydration earn later restoration authority.
        Map<BlockPos, BlockPos> supports = supportPaths(region, species, root, writes, exactLeaves);
        Map<Long, Integer> depths = new HashMap<>();
        List<CompoundTag> ranked = new java.util.ArrayList<>();
        for (var candidate : exactLeaves.entrySet()) {
            BlockPos pos = candidate.getKey();
            BlockState state = candidate.getValue();
            BlockPos support = supports.get(pos);
            boolean restorable = support != null;
            CompoundTag item = new CompoundTag();
            item.putLong("root", root.asLong());
            item.putUUID("generation", generation);
            item.putString("species", species.getRegistryName().toString());
            item.putBoolean("tropical", header.getBoolean("tropical"));
            item.putBoolean("restorable", restorable);
            item.putLong("pos", pos.asLong()); item.putLong("support", restorable ? support.asLong() : pos.asLong());
            item.put("state", encode(state));
            item.put("supportState", encode(restorable ? region.getBlockState(support) : Blocks.AIR.defaultBlockState()));
            item.putInt("status", 0); // pending: never infer removal from a later chunk scan
            depths.put(pos.asLong(), 5 - state.getValue(LeavesBlock.DISTANCE));
            ranked.add(item);
            additions.computeIfAbsent(region.getChunk(pos.getX() >> 4, pos.getZ() >> 4),
                    ignored -> new ListTag()).add(item);
        }
        ranked.sort((a, b) -> {
            long posA = a.getLong("pos"), posB = b.getLong("pos");
            return SeasonalSchedule.compareOuterFirst(depths.get(posA),
                    SeasonClock.fraction(generation.getMostSignificantBits(), posA, 0), posA,
                    depths.get(posB), SeasonClock.fraction(generation.getMostSignificantBits(), posB, 0), posB);
        });
        for (int rank = 0; rank < ranked.size(); rank++) {
            ranked.get(rank).putInt("rank", rank);
            ranked.get(rank).putInt("count", ranked.size());
        }
        synchronized (CARRIERS) {
            Map<ChunkAccess, CompoundTag> staged = new HashMap<>();
            for (var entry : additions.entrySet()) {
                CompoundTag data = snapshot(entry.getKey());
                ListTag leaves = data.getList("leaves", Tag.TAG_COMPOUND);
                Set<Long> replacedPositions = new java.util.HashSet<>();
                for (Tag candidate : entry.getValue()) {
                    CompoundTag leaf = (CompoundTag) candidate;
                    if (!leaf.getBoolean("header")) replacedPositions.add(leaf.getLong("pos"));
                }
                leaves.removeIf(candidate -> {
                    CompoundTag old = (CompoundTag) candidate;
                    return old.getBoolean("header") && old.getLong("root") == root.asLong()
                            || !old.getBoolean("header") && replacedPositions.contains(old.getLong("pos"));
                });
                if ((long) leaves.size() + entry.getValue().size() > maxReceiptEntries(
                        entry.getKey().getMinBuildHeight(), entry.getKey().getMaxBuildHeight())) return false;
                leaves.addAll(entry.getValue());
                data.putInt("version", 1); data.put("leaves", leaves);
                staged.put(entry.getKey(), data);
            }
            staged.forEach(GenerationCanopyData::replace);
        }
        return additions.size() > 0;
    }
    /** BFS parent links are acyclic even when native hydration of two adjacent leaves is equal. */
    private static Map<BlockPos, BlockPos> supportPaths(WorldGenRegion region, Species species, BlockPos root,
            Set<BlockPos> generationWrites, Map<BlockPos, BlockState> leaves) {
        // A new branch island from postGeneration is not proof of a path to this root.
        Set<BlockPos> rootedBranches = new HashSet<>();
        java.util.ArrayDeque<BlockPos> branches = new java.util.ArrayDeque<>();
        BlockState rootState = region.getBlockState(root);
        if (rootState.getBlock() instanceof RootyBlock rooty) {
            BlockPos trunk = root.relative(rooty.getTrunkDirection(region, root));
            if (generationWrites.contains(trunk) && region.hasChunk(trunk.getX() >> 4, trunk.getZ() >> 4)
                    && region.getBlockState(trunk).getBlock() instanceof BranchBlock branch
                    && branch.getFamily() == species.getFamily()) {
                rootedBranches.add(trunk.immutable());
                branches.addLast(trunk.immutable());
            }
        }
        while (!branches.isEmpty()) {
            BlockPos source = branches.removeFirst();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = source.relative(direction);
                if (!generationWrites.contains(neighbor) || rootedBranches.contains(neighbor)
                        || !region.hasChunk(neighbor.getX() >> 4, neighbor.getZ() >> 4)) continue;
                BlockState state = region.getBlockState(neighbor);
                if (state.getBlock() instanceof BranchBlock branch && branch.getFamily() == species.getFamily()
                        && rootedBranches.add(neighbor.immutable())) branches.addLast(neighbor.immutable());
            }
        }
        Map<BlockPos, BlockPos> parent = new HashMap<>();
        java.util.ArrayDeque<BlockPos> frontier = new java.util.ArrayDeque<>();
        for (var candidate : leaves.entrySet()) {
            BlockPos pos = candidate.getKey();
            BlockState child = candidate.getValue();
            for (Direction direction : Direction.values()) {
                BlockPos neighbor = pos.relative(direction);
                if (!rootedBranches.contains(neighbor)) continue;
                BlockState source = region.getBlockState(neighbor);
                if (!(source.getBlock() instanceof BranchBlock branch)
                        || branch.getFamily() != species.getFamily()) continue;
                Cell hydration = branch.getHydrationCell(region, neighbor, source, direction,
                        ((DynamicLeavesBlock) child.getBlock()).getProperties(child));
                if (hydration.getValueFromSide(direction.getOpposite()) > 0) {
                    parent.put(pos, neighbor.immutable());
                    frontier.addLast(pos);
                    break;
                }
            }
        }
        while (!frontier.isEmpty()) {
            BlockPos sourcePos = frontier.removeFirst();
            BlockState source = leaves.get(sourcePos);
            DynamicLeavesBlock sourceLeaves = (DynamicLeavesBlock) source.getBlock();
            for (Direction direction : Direction.values()) {
                BlockPos childPos = sourcePos.relative(direction);
                BlockState child = leaves.get(childPos);
                if (child == null || parent.containsKey(childPos)) continue;
                DynamicLeavesBlock childLeaves = (DynamicLeavesBlock) child.getBlock();
                var childProperties = childLeaves.getProperties(child);
                if (!sourceLeaves.getProperties(source).isCompatibleLeaves(childProperties)) continue;
                Cell hydration = sourceLeaves.getHydrationCell(region, sourcePos, source, direction,
                        childProperties);
                if (hydration.getValueFromSide(direction.getOpposite()) > 0) {
                    parent.put(childPos, sourcePos);
                    frontier.addLast(childPos);
                }
            }
        }
        return parent;
    }
    /** Pure NBT/coordinate validation: no registry, level, chunk or loader access. */
    static boolean validatePayload(CompoundTag data, net.minecraft.world.level.ChunkPos chunk,
                                   int minBuildHeight, int maxBuildHeight) {
        if (data.getInt("version") != 1 || !data.contains("leaves", Tag.TAG_LIST)) return false;
        if (!(data.get("leaves") instanceof ListTag entries)
                || (entries.size() > 0 && entries.getElementType() != Tag.TAG_COMPOUND)
                || entries.size() > maxReceiptEntries(minBuildHeight, maxBuildHeight)) return false;
        Set<Long> positions = new java.util.HashSet<>();
        Set<Long> headerRoots = new java.util.HashSet<>();
        for (Tag entry : entries) {
            CompoundTag item = (CompoundTag) entry;
            if (!item.hasUUID("generation") || !item.contains("root", Tag.TAG_LONG)
                    || !item.contains("species", Tag.TAG_STRING)
                    || item.getString("species").length() > 128
                    || ResourceLocation.tryParse(item.getString("species")) == null) return false;
            BlockPos root = BlockPos.of(item.getLong("root"));
            if (item.getBoolean("header")) {
                if (!chunk.equals(new net.minecraft.world.level.ChunkPos(root))
                        || root.getY() < minBuildHeight || root.getY() >= maxBuildHeight
                        || !headerRoots.add(root.asLong())) return false;
                continue;
            }
            if (!item.contains("pos", Tag.TAG_LONG) || !item.contains("support", Tag.TAG_LONG)
                    || !item.contains("state", Tag.TAG_COMPOUND)
                    || !item.contains("supportState", Tag.TAG_COMPOUND)
                    || !item.contains("restorable", Tag.TAG_BYTE)
                    || !item.contains("status", Tag.TAG_INT)
                    || !item.contains("rank", Tag.TAG_INT)
                    || !item.contains("count", Tag.TAG_INT)) return false;
            int rank = item.getInt("rank"), count = item.getInt("count");
            if (count <= 0 || count > maxGenerationPositions(minBuildHeight, maxBuildHeight)
                    || rank < 0 || rank >= count) return false;
            int status = item.getInt("status");
            boolean restorable = item.getBoolean("restorable");
            if (status < 0 || status > 2 || !restorable && status == 1) return false;
            BlockPos pos = BlockPos.of(item.getLong("pos"));
            BlockPos support = BlockPos.of(item.getLong("support"));
            long sx = (long) pos.getX() - support.getX(), sy = (long) pos.getY() - support.getY();
            long sz = (long) pos.getZ() - support.getZ();
            if (!chunk.equals(new net.minecraft.world.level.ChunkPos(pos))
                    || pos.getY() < minBuildHeight || pos.getY() >= maxBuildHeight
                    || support.getY() < minBuildHeight || support.getY() >= maxBuildHeight
                    || root.getY() < minBuildHeight || root.getY() >= maxBuildHeight
                    || !positions.add(pos.asLong())
                    || (restorable ? Math.abs(sx) + Math.abs(sy) + Math.abs(sz) != 1
                                   : !pos.equals(support))
                    || Math.abs((long) pos.getX() - root.getX()) > (long) FEATURE_DIAMETER_CHUNKS * CHUNK_WIDTH
                    || Math.abs((long) pos.getZ() - root.getZ()) > (long) FEATURE_DIAMETER_CHUNKS * CHUNK_WIDTH) return false;
            if (status == 1 && (!item.contains("targetCycle", Tag.TAG_LONG)
                    || !item.contains("deadline", Tag.TAG_DOUBLE)
                    || !Double.isFinite(item.getDouble("deadline"))
                    || item.getDouble("deadline") < 0 || item.getDouble("deadline") >= 12)) return false;
        }
        return true;
    }
    public static CompoundTag encode(BlockState state) {
        CompoundTag tag = new CompoundTag();
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        if (id == null) return tag;
        tag.putString("block", id.toString());
        CompoundTag properties = new CompoundTag();
        for (var property : state.getProperties())
            properties.putString(property.getName(), propertyName(state, property));
        tag.put("properties", properties);
        return tag;
    }
    private static <T extends Comparable<T>> String propertyName(BlockState state,
                                                                   net.minecraft.world.level.block.state.properties.Property<T> property) {
        return property.getName(state.getValue(property));
    }
    public static BlockState decode(CompoundTag tag) {
        ResourceLocation id = ResourceLocation.tryParse(tag.getString("block"));
        if (id == null || !ForgeRegistries.BLOCKS.containsKey(id)) return null;
        BlockState state = ForgeRegistries.BLOCKS.getValue(id).defaultBlockState();
        CompoundTag properties = tag.getCompound("properties");
        for (String key : properties.getAllKeys()) {
            var property = state.getBlock().getStateDefinition().getProperty(key);
            if (property == null || !properties.contains(key, Tag.TAG_STRING)) return null;
            state = withProperty(state, property, properties.getString(key));
            if (state == null) return null;
        }
        return state;
    }
    private static <T extends Comparable<T>> BlockState withProperty(BlockState state,
                     net.minecraft.world.level.block.state.properties.Property<T> property, String value) {
        var parsed = property.getValue(value);
        return parsed.isPresent() ? state.setValue(property, parsed.get()) : null;
    }
    /** ChunkMap posts ChunkEvent.Load on its main-thread mailbox before sending a client chunk packet. */
    public static void admit(ServerLevel level, LevelChunk chunk) {
        CompoundTag data = snapshot(chunk);
        if (data.isEmpty() || !validatePayload(data, chunk.getPos(),
                chunk.getMinBuildHeight(), chunk.getMaxBuildHeight())) return;
        SeasonClock.Reading reading = SeasonClock.read(level);
        if (reading == null) {
            // An explicitly nonseasonal dimension is native/unchanged. SS initializes its
            // provider and config before ServerLevel creation and creates SavedData on demand;
            // a missing clock in an enabled dimension is exceptional, never an inferred phase.
            if (sereneseasons.init.ModConfig.seasons != null
                    && !sereneseasons.init.ModConfig.seasons.isDimensionWhitelisted(level.dimension())) {
                if (retirePending(data) > 0) replace(chunk, data);
                return;
            }
            int excluded = retireExcludedPending(data);
            if (hasEligiblePending(data))
                throw new IllegalStateException("Seasonal tree birth requires Serene Seasons calendar before chunk publication: "
                        + level.dimension().location() + " " + chunk.getPos());
            if (excluded > 0) replace(chunk, data);
            return;
        }
        SeasonalLedger ledger = SeasonalLedger.get(level);
        ledger.observeCycle(reading.cycleTick(), reading.cycleDuration(), level.getGameTime());
        ListTag leaves = data.getList("leaves", Tag.TAG_COMPOUND);
        boolean changed = false;
        Set<BlockPos> canopySnow = new HashSet<>();
        for (Tag value : leaves) {
            CompoundTag item = (CompoundTag) value;
            if (!item.hasUUID("generation")) continue;
            UUID generation = item.getUUID("generation");
            ResourceLocation species = ResourceLocation.tryParse(item.getString("species"));
            if (species == null || ledger.removedGeneration(generation)) continue;
            BlockPos root = BlockPos.of(item.getLong("root"));
            SpeciesProfile profile = SeasonalCatalogue.profile(species);
            if (profile == null) {
                if (!item.getBoolean("header") && item.getInt("status") == 0) {
                    item.putInt("status", 2); changed = true;
                }
                continue;
            }
            boolean tropical = profile.habit() == SpeciesProfile.Habit.TROPICAL && item.getBoolean("tropical");
            double phase = tropical ? SeasonClock.tropical(reading, ledger.cycle()).phase() : reading.phase();
            long cycle = tropical ? SeasonClock.tropical(reading, ledger.cycle()).cycle() : ledger.cycle();
            boolean initialize = SpringBirthSchedule.shouldInitialize(profile.habit(),
                    profile.habit() != SpeciesProfile.Habit.TROPICAL || tropical,
                    phase, profile.leafOutStart(), profile.leafOutDuration(),
                    profile.shedStart(), profile.shedEnd());
            SeasonalLedger.Tree known = ledger.find(root);
            if (known != null && (!known.incarnation.equals(generation) || !known.species.equals(species))) continue;
            // Load is posted before this FULL future completes. hasChunkAt/getBlockState
            // can recursively await that future (or a neighbor's), deadlocking publication.
            var rootChunkPos = new net.minecraft.world.level.ChunkPos(root);
            LevelChunk publishedRoot = publishedChunk(level, rootChunkPos.x, rootChunkPos.z);
            LevelChunk rootView = chunk.getPos().equals(rootChunkPos) ? chunk : publishedRoot;
            if (rootView != null) {
                BlockState rootState = rootView.getBlockState(root);
                if (!(rootState.getBlock() instanceof com.ferreusveritas.dynamictrees.block.rooty.RootyBlock rootBlock)) {
                    ledger.tombstoneGeneration(generation); continue;
                }
                // Native species resolution reads through LevelAccessor. Only invoke it
                // for published root/trunk chunks; the immutable generation receipt is
                // already authoritative when a crown's root is not yet available.
                if (publishedRoot != null) {
                    BlockPos trunk = root.relative(rootBlock.getTrunkDirection(level, root));
                    if (publishedChunk(level, trunk.getX() >> 4, trunk.getZ() >> 4) != null) {
                        Species live = rootBlock.getSpecies(rootState, level, root);
                        if (live == null || !species.equals(live.getRegistryName())) {
                            ledger.tombstoneGeneration(generation); continue;
                        }
                    }
                }
            }
            if (item.getBoolean("header")) {
                ledger.admitGenerated(root, species, generation);
                continue;
            }
            int status = item.getInt("status");
            if (status == 2) continue;
            BlockPos pos = BlockPos.of(item.getLong("pos"));
            BlockState expected = decode(item.getCompound("state"));
            BlockState support = decode(item.getCompound("supportState"));
            Species nativeSpecies = Species.REGISTRY.getOptional(species).orElse(null);
            if (!(expected != null && expected.getBlock() instanceof DynamicLeavesBlock dynamicLeaves)
                    || support == null || nativeSpecies == null || !nativeSpecies.isValidLeafBlock(dynamicLeaves)) continue;
            BlockState actual = chunk.getBlockState(pos);
            boolean restorable = item.getBoolean("restorable");
            SeasonalLedger.Tree tree = restorable ? ledger.admitGenerated(root, species, generation) : null;
            if (restorable && tree == null) continue;
            SeasonalLedger.Ticket previous = restorable ? tree.tickets.get(pos.asLong()) : null;
            if (status == 1) {
                // Marker is the durable generation receipt. Reproject only an actually absent leaf,
                // never a pruned or deliberately broken restored leaf.
                if (previous != null) {
                    if (previous.consumed) { item.putInt("status", 2); changed = true; }
                } else if (actual.isAir()) {
                    ledger.freezeTiming(tree, cycle, profile, tropical);
                    ledger.record(tree, pos, BlockPos.of(item.getLong("support")), expected, support,
                            item.getLong("targetCycle"), item.getDouble("deadline"));
                } else { item.putInt("status", 2); changed = true; }
                continue;
            }
            if (!initialize || actual != expected) {
                item.putInt("status", 2); changed = true; continue;
            }
            long targetCycle = phase >= profile.shedStart() ? cycle + 1 : cycle;
            int rank = item.getInt("rank"), count = item.getInt("count");
            double restoreHash = SeasonClock.fraction(generation.getLeastSignificantBits(), pos.asLong(), targetCycle);
            double deadline = SpringBirthSchedule.rankedDeadline(profile.leafOutStart(),
                    profile.leafOutDuration(), count - 1 - rank, count, restoreHash);
            double shedHash = SeasonClock.fraction(generation.getMostSignificantBits(), pos.asLong(), cycle);
            double shedDeadline = SpringBirthSchedule.rankedDeadline(profile.shedStart(),
                    profile.shedEnd() - profile.shedStart(), rank, count, shedHash);
            if (!SpringBirthSchedule.initialLeafAbsent(phase, deadline, shedDeadline)) {
                item.putInt("status", 2); changed = true; continue;
            }
            if (!restorable) {
                // A genuine new native leaf without a root-owned path is still season-filtered,
                // but must never mint a restoration ticket through somebody else's canopy.
                if (!removeBeforePublication(level, chunk, pos, canopySnow))
                    throw new IllegalStateException("Could not initialize unsupported generated leaf before chunk publication: " + pos);
                item.putInt("status", 2); changed = true;
                continue;
            }
            if (previous != null) {
                item.putInt("status", previous.consumed ? 2 : 1); changed = true; continue;
            }
            ledger.freezeTiming(tree, cycle, profile, tropical);
            BlockPos supportPos = BlockPos.of(item.getLong("support"));
            if (!removeBeforePublication(level, chunk, pos, canopySnow))
                throw new IllegalStateException("Could not initialize generated leaf before chunk publication: " + pos);
            ledger.record(tree, pos, supportPos, actual, support, targetCycle, deadline);
            item.putInt("status", 1); item.putLong("targetCycle", targetCycle);
            item.putDouble("deadline", deadline); changed = true;
        }
        // SnowAndFreeze runs after FEATURES, while the native canopy is still present.
        // Move only snow actually supported by leaves removed in this admission.
        settleCanopySnow(level, chunk, canopySnow);
        if (changed) replace(chunk, data);
    }
    private static LevelChunk publishedChunk(ServerLevel level, int x, int z) {
        // A cached LevelChunk (getChunkNow) is not a publication guarantee in this
        // pack. Read the existing holder's FULL-accessibility future instead.
        // Do not call ServerChunkCache.getChunkFuture: it managed-blocks on the
        // main thread even with create=false. This path adds no tickets or work.
        var map = (com.bettercontent.seasonaltrees.mixin.ChunkMapReadAccess) level.getChunkSource().chunkMap;
        var holder = map.betterSeasonalTrees$visibleHolder(net.minecraft.world.level.ChunkPos.asLong(x, z));
        if (holder == null) return null;
        var future = holder.getFullChunkFuture();
        if (!future.isDone() || future.isCompletedExceptionally()) return null;
        var ready = future.getNow(null);
        return ready == null ? null : ready.left().orElse(null);
    }
    private static boolean removeBeforePublication(ServerLevel level, LevelChunk chunk, BlockPos pos,
                                                    Set<BlockPos> canopySnow) {
        // No Level.setBlock: it requests the unfinished FULL chunk again. This mutation
        // remains synchronous before first visibility, never a later stripping queue.
        if (!chunk.getPos().equals(new net.minecraft.world.level.ChunkPos(pos)))
            throw new IllegalArgumentException("Canopy receipt belongs to another chunk: " + pos);
        BlockPos above = pos.above();
        boolean snowAbove = above.getY() < chunk.getMaxBuildHeight() && chunk.getBlockState(above).is(Blocks.SNOW);
        if (chunk.setBlockState(pos, Blocks.AIR.defaultBlockState(), false) == null) return false;
        if (snowAbove) canopySnow.add(above);
        // LevelChunk maintains section/heightmap state; schedule the native light update
        // without asking the server chunk cache to load or await anything.
        level.getChunkSource().getLightEngine().checkBlock(pos);
        return true;
    }
    private static void settleCanopySnow(ServerLevel level, LevelChunk chunk, Set<BlockPos> canopySnow) {
        for (BlockPos snow : canopySnow) {
            if (!chunk.getBlockState(snow).is(Blocks.SNOW) || !chunk.getBlockState(snow.below()).isAir()) continue;
            BlockState layer = chunk.getBlockState(snow);
            if (chunk.setBlockState(snow, Blocks.AIR.defaultBlockState(), false) == null)
                throw new IllegalStateException("Could not clear unsupported generated canopy snow: " + snow);
            level.getChunkSource().getLightEngine().checkBlock(snow);
            int y = chunk.getHeight(Heightmap.Types.MOTION_BLOCKING, snow.getX() & 15, snow.getZ() & 15);
            if (y >= snow.getY() || y <= chunk.getMinBuildHeight() || y >= chunk.getMaxBuildHeight()) continue;
            BlockPos ground = new BlockPos(snow.getX(), y, snow.getZ());
            BlockPos below = ground.below();
            BlockState support = chunk.getBlockState(below);
            if (!chunk.getBlockState(ground).isAir()
                    || !chunk.getNoiseBiome(ground.getX() >> 2, ground.getY() >> 2,
                            ground.getZ() >> 2).value().coldEnoughToSnow(ground)
                    || level.getChunkSource().getLightEngine().getLayerListener(LightLayer.BLOCK)
                            .getLightValue(ground) >= 10
                    || support.is(BlockTags.SNOW_LAYER_CANNOT_SURVIVE_ON)
                    || !(support.is(BlockTags.SNOW_LAYER_CAN_SURVIVE_ON)
                            || Block.isFaceFull(support.getCollisionShape(chunk, below), Direction.UP))) continue;
            // The source layer already existed. Never invent snow in a column without one.
            if (chunk.setBlockState(ground, layer, false) == null)
                throw new IllegalStateException("Could not settle generated canopy snow: " + ground);
            if (support.hasProperty(SnowyDirtBlock.SNOWY))
                chunk.setBlockState(below, support.setValue(SnowyDirtBlock.SNOWY, true), false);
            level.getChunkSource().getLightEngine().checkBlock(ground);
        }
    }
    private static boolean eligiblePending(CompoundTag item) {
        if (item.getBoolean("header") || item.getInt("status") != 0) return false;
        SpeciesProfile profile = SeasonalCatalogue.profile(ResourceLocation.tryParse(item.getString("species")));
        return profile != null && (profile.habit() == SpeciesProfile.Habit.TEMPERATE
                || profile.habit() == SpeciesProfile.Habit.TROPICAL && item.getBoolean("tropical"));
    }
    private static boolean hasEligiblePending(CompoundTag data) {
        for (Tag value : data.getList("leaves", Tag.TAG_COMPOUND))
            if (eligiblePending((CompoundTag) value)) return true;
        return false;
    }
    private static int retireExcludedPending(CompoundTag data) {
        int retired = 0;
        for (Tag value : data.getList("leaves", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) value;
            if (!item.getBoolean("header") && item.getInt("status") == 0 && !eligiblePending(item)) {
                item.putInt("status", 2);
                retired++;
            }
        }
        return retired;
    }
    /** One-shot transition for an explicitly disabled/nonseasonal dimension. */
    static int retirePending(CompoundTag data) {
        if (!data.contains("leaves", Tag.TAG_LIST)) return 0;
        int retired = 0;
        for (Tag value : data.getList("leaves", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) value;
            if (!item.getBoolean("header") && item.getInt("status") == 0) {
                item.putInt("status", 2);
                retired++;
            }
        }
        return retired;
    }
    /** Header epochs survive without a ledger Tree: removing that root must invalidate them too. */
    static Set<UUID> rootGenerations(CompoundTag data, BlockPos root) {
        Set<UUID> result = new java.util.HashSet<>();
        if (!data.contains("leaves", Tag.TAG_LIST)) return result;
        for (Tag value : data.getList("leaves", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) value;
            if (item.getBoolean("header") && item.getLong("root") == root.asLong()
                    && item.hasUUID("generation")) result.add(item.getUUID("generation"));
        }
        return result;
    }
    public static void invalidateRootHeader(ServerLevel level, BlockPos root, SeasonalLedger ledger) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(root.getX() >> 4, root.getZ() >> 4);
        if (chunk == null) return;
        CompoundTag data = snapshot(chunk);
        if (!validatePayload(data, chunk.getPos(),
                chunk.getMinBuildHeight(), chunk.getMaxBuildHeight())) return;
        for (UUID generation : rootGenerations(data, root)) ledger.tombstoneGeneration(generation);
    }
    /** Close a generated one-shot opportunity when the existing scheduler resolves it. */
    public static void resolved(ServerLevel level, SeasonalLedger.Ticket ticket) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(ticket.pos.getX() >> 4, ticket.pos.getZ() >> 4);
        if (chunk == null) return;
        CompoundTag data = snapshot(chunk);
        if (data.isEmpty() || !validatePayload(data, chunk.getPos(),
                chunk.getMinBuildHeight(), chunk.getMaxBuildHeight())) return;
        for (Tag value : data.getList("leaves", Tag.TAG_COMPOUND)) {
            CompoundTag item = (CompoundTag) value;
            if (item.getBoolean("header") || item.getInt("status") != 1
                    || item.getLong("pos") != ticket.pos.asLong()) continue;
            item.putInt("status", 2);
            replace(chunk, data);
            return;
        }
    }
    @SubscribeEvent public static void onSave(ChunkDataEvent.Save event) {
        CompoundTag data = snapshot(event.getChunk());
        if (data.isEmpty()) event.getData().remove(KEY);
        else event.getData().put(KEY, data);
    }
    @SubscribeEvent public static void onLoad(ChunkDataEvent.Load event) {
        if (!event.getData().contains(KEY, Tag.TAG_COMPOUND)) return;
        CompoundTag data = event.getData().getCompound(KEY);
        if (!validatePayload(data, event.getChunk().getPos(),
                event.getChunk().getMinBuildHeight(), event.getChunk().getMaxBuildHeight())) return;
        replace(event.getChunk(), data);
        if (event.getChunk() instanceof ImposterProtoChunk imposter)
            transfer(imposter, imposter.getWrapped());
    }
}
