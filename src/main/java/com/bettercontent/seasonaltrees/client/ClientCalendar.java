package com.bettercontent.seasonaltrees.client;

import com.bettercontent.seasonaltrees.network.SeasonalNetwork.VisualProfile;
import com.bettercontent.seasonaltrees.network.SeasonalNetwork.VisualProfilesPacket;
import com.bettercontent.seasonaltrees.network.SeasonalNetwork.ManagedVisual;
import com.ferreusveritas.dynamictrees.block.branch.BasicBranchBlock;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.ConcurrentHashMap;

/** Client copy of server-published profiles and complete per-position visual ownership snapshots. */
@Mod.EventBusSubscriber(modid = "better_seasonal_trees", bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class ClientCalendar {
    public enum VisualStage { ORIGINAL, GREEN, AUTUMN, SPARSE_BLOOM, FULL_BLOOM }

    private static volatile double phase;
    private static volatile boolean enabled;
    private static volatile long profileRevision = -1;
    private static volatile Map<ResourceLocation, VisualProfile> visualProfiles = Map.of();
    private static final Map<Long, Map<Long, ManagedVisual>> OWNED_VISUALS = new ConcurrentHashMap<>();
    private static final Set<Long> COMPLETE_VISUAL_CHUNKS = ConcurrentHashMap.newKeySet();
    private static final Map<Long, List<Long>> BLOOM_BRANCH_CANDIDATES = new ConcurrentHashMap<>();
    private static final Set<Long> PENDING_CHUNKS = new LinkedHashSet<>();
    private static final Deque<DirtySection> SECTION_REFRESHES = new ArrayDeque<>();
    private static volatile ResourceLocation calendarDimension;
    private static ClientLevel lastLevel;
    private static int refreshIndex = -1;
    private static int refreshCenterX, refreshCenterZ, refreshRadius;
    private static ClientLevel refreshLevel;

    private ClientCalendar() {}

    public static boolean enabled() { return enabled; }
    public static double phase() { return phase; }
    public static long profileRevision() { return profileRevision; }
    public static Map<ResourceLocation, VisualProfile> visualProfiles() { return visualProfiles; }
    public static VisualProfile visualProfile(ResourceLocation species) { return visualProfiles.get(species); }

    public static ManagedVisual visualAt(BlockPos pos, String kind) {
        Minecraft minecraft = Minecraft.getInstance();
        if (!enabled || minecraft.level == null || calendarDimension == null
                || !minecraft.level.dimension().location().equals(calendarDimension)) return null;
        long chunk = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
        if (!COMPLETE_VISUAL_CHUNKS.contains(chunk)) return null;
        ManagedVisual visual = OWNED_VISUALS.getOrDefault(chunk, Map.of()).get(pos.asLong());
        if (visual == null || !visual.kind().equals(kind)
                || !minecraft.level.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) return null;
        var species = com.ferreusveritas.dynamictrees.tree.species.Species.REGISTRY.getOptional(visual.speciesId());
        if (species.isEmpty()) return null;
        Block actual = minecraft.level.getBlockState(pos).getBlock();
        if (kind.equals("LEAF")) {
            return actual instanceof DynamicLeavesBlock dynamicLeaves
                    && species.get().isValidLeafBlock(dynamicLeaves) ? visual : null;
        }
        if (kind.equals("BRANCH")) {
            return species.get().getFamily().getBranch().orElse(null) == actual ? visual : null;
        }
        return null;
    }

    public static VisualStage visualStage(ManagedVisual visual) {
        return visualStage(visual, phase, enabled);
    }

    private static VisualStage visualStage(ManagedVisual visual, double rawPhase, boolean calendarEnabled) {
        if (visual == null || !calendarEnabled) return VisualStage.ORIGINAL;
        ClientVisualDefinitions.Definition definition = ClientVisualDefinitions.current().get(visual.bloomVisual());
        if (definition == null) return VisualStage.ORIGINAL;
        double atPhase = visual.habit().equals("TROPICAL")
                ? (rawPhase >= 9.0 ? rawPhase - 9.0 : rawPhase + 3.0) : rawPhase;
        if (!visual.bloomVisual().equals("none") && visual.bloomEnd() > visual.bloomStart()
                && atPhase >= visual.bloomStart() && atPhase < visual.bloomEnd()) {
            double progress = (atPhase - visual.bloomStart()) / (visual.bloomEnd() - visual.bloomStart());
            return progress < definition.bloomSparseUntil() || progress >= definition.bloomFullUntil()
                    ? VisualStage.SPARSE_BLOOM : VisualStage.FULL_BLOOM;
        }
        if (visual.habit().equals("TEMPERATE")
                && visual.shedEnd() > visual.shedStart()
                && atPhase >= visual.shedStart() && atPhase < visual.shedEnd()) return VisualStage.AUTUMN;
        return VisualStage.GREEN;
    }

    public static void replaceManagedVisuals(ResourceLocation dimension, long chunkKey,
                                             List<ManagedVisual> positions, boolean complete) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.dimension().location().equals(dimension)
                || !dimension.equals(calendarDimension)) return;
        if (!enabled || !complete) {
            OWNED_VISUALS.remove(chunkKey);
            COMPLETE_VISUAL_CHUNKS.remove(chunkKey);
            BLOOM_BRANCH_CANDIDATES.remove(chunkKey);
        } else {
            Map<Long, ManagedVisual> next = new HashMap<>();
            for (ManagedVisual visual : positions) {
                if (next.putIfAbsent(visual.position(), visual) != null) {
                    OWNED_VISUALS.remove(chunkKey);
                    COMPLETE_VISUAL_CHUNKS.remove(chunkKey);
                    PENDING_CHUNKS.add(chunkKey);
                    return;
                }
            }
            OWNED_VISUALS.put(chunkKey, Map.copyOf(next));
            COMPLETE_VISUAL_CHUNKS.add(chunkKey);
            BLOOM_BRANCH_CANDIDATES.put(chunkKey, positions.stream()
                    .filter(visual -> visual.kind().equals("BRANCH"))
                    .map(ManagedVisual::position).toList());
        }
        PENDING_CHUNKS.add(chunkKey);
    }

    public static boolean suppressNativeCherryParticles(BlockPos pos) {
        ManagedVisual visual = visualAt(pos, "LEAF");
        return visual != null && visual.speciesId().equals(new ResourceLocation("dynamictrees", "cherry"));
    }

    /** Schedules a caller-bounded set of section rebakes, shared by the isolated visual fixture only. */
    public static int scheduleBoundedSectionRefresh(ClientLevel level, Collection<Long> chunks,
                                                    int minY, int maxY, int maxChunks) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level || chunks.size() > maxChunks || maxChunks < 0
                || minY < level.getMinBuildHeight() || maxY >= level.getMaxBuildHeight() || maxY < minY)
            throw new IllegalArgumentException("Invalid bounded client section refresh request");
        int scheduled = 0;
        for (long key : chunks) {
            if (level.hasChunk(ChunkPos.getX(key), ChunkPos.getZ(key))) {
                SECTION_REFRESHES.addLast(new DirtySection(level, key, minY, maxY));
                scheduled++;
            }
        }
        return scheduled;
    }

    public static int pendingSectionRefreshes() { return SECTION_REFRESHES.size(); }

    public static void acceptProfiles(VisualProfilesPacket packet) {
        if (packet.revision() < profileRevision) return;
        Map<String, ClientVisualDefinitions.Definition> definitions = ClientVisualDefinitions.current();
        if (definitions.isEmpty()) {
            definitions = ClientVisualDefinitions.reload(Minecraft.getInstance().getResourceManager());
        }
        Map<ResourceLocation, VisualProfile> next = new HashMap<>();
        for (VisualProfile profile : packet.profiles()) {
            validate(profile);
            ClientVisualDefinitions.Definition definition = definitions.get(profile.bloomVisual());
            if (definition == null)
                throw new IllegalStateException("Missing installed client visual for active profile "
                        + profile.speciesId() + " with bloom visual " + profile.bloomVisual());
            if (next.putIfAbsent(profile.speciesId(), profile) != null)
                throw new IllegalStateException("Duplicate server visual profile " + profile.speciesId());
        }
        boolean changed = !next.equals(visualProfiles);
        visualProfiles = Map.copyOf(next);
        profileRevision = packet.revision();
        if (changed) requestRefresh();
    }

    public static void visualDefinitionsReloaded(Map<String, ClientVisualDefinitions.Definition> definitions) {
        if (!visualProfiles.isEmpty()) {
            for (VisualProfile profile : visualProfiles.values()) {
                if (!definitions.containsKey(profile.bloomVisual()))
                    throw new IllegalStateException("Reloaded client visuals are missing " + profile.bloomVisual());
            }
            requestRefresh();
        }
    }

    private static void validate(VisualProfile profile) {
        double[] times = {profile.leafOutStart(), profile.leafOutDuration(), profile.shedStart(),
                profile.shedEnd(), profile.bloomStart(), profile.bloomEnd()};
        for (double time : times) {
            if (!Double.isFinite(time) || time < 0 || time > 12)
                throw new IllegalArgumentException("Invalid timing in server visual profile " + profile.speciesId());
        }
        if (profile.leafOutStart() + profile.leafOutDuration() > 12
                || profile.shedEnd() < profile.shedStart() || profile.bloomEnd() < profile.bloomStart()
                || profile.autumnColor() < 0 || profile.autumnColor() > 0xFFFFFF
                || profile.bloomVisual() == null || !profile.bloomVisual().matches("[a-z0-9_]+")
                || (profile.bloomVisual().equals("none") != (profile.bloomStart() == profile.bloomEnd())))
            throw new IllegalArgumentException("Invalid server visual profile " + profile.speciesId());
    }

    public static void acceptCalendar(com.bettercontent.seasonaltrees.network.SeasonalNetwork.CalendarPacket packet) {
        if (!Double.isFinite(packet.phase()) || packet.phase() < 0 || packet.phase() >= 12
                || packet.cycleDuration() <= 0 || packet.cycleTick() < 0
                || packet.cycleTick() >= packet.cycleDuration()) return;
        ResourceLocation previousDimension = calendarDimension;
        double previousPhase = phase;
        boolean previousEnabled = enabled;
        boolean dimensionChanged = !packet.dimension().equals(previousDimension);
        if (dimensionChanged || !packet.enabled()) {
            OWNED_VISUALS.clear();
            COMPLETE_VISUAL_CHUNKS.clear();
            BLOOM_BRANCH_CANDIDATES.clear();
            PENDING_CHUNKS.clear();
            if (dimensionChanged) SECTION_REFRESHES.clear();
            calendarDimension = packet.dimension();
            PetalParticles.clear();
        }
        phase = packet.enabled() ? packet.phase() : 0;
        enabled = packet.enabled();
        // Calendar packets arrive at bounded daily/stage intervals; avoid scanning ownership
        // positions here. Re-bakes are limited to eight chunks per client tick below.
        boolean stagesChanged = previousEnabled != enabled || dimensionChanged || previousPhase != phase;
        if (stagesChanged) requestRefresh();
    }

    /** Samples only complete server-owned bloom branches in nearby loaded chunks. */
    public static BlockPos nearbyOwnedBloomBranch(ClientLevel level, BlockPos center, int radius) {
        if (!enabled) return null;
        int centerChunkX = center.getX() >> 4, centerChunkZ = center.getZ() >> 4;
        int chunkRadius = Math.max(0, radius >> 4);
        long maxDistance = (long) radius * radius;
        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                if (!level.hasChunk(cx, cz)) continue;
                long key = ChunkPos.asLong(cx, cz);
                if (!COMPLETE_VISUAL_CHUNKS.contains(key)) continue;
                List<Long> candidates = BLOOM_BRANCH_CANDIDATES.get(key);
                if (candidates == null || candidates.isEmpty()) continue;
                long packed = candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
                ManagedVisual owner = OWNED_VISUALS.getOrDefault(key, Map.of()).get(packed);
                if (owner == null || (visualStage(owner) != VisualStage.SPARSE_BLOOM
                        && visualStage(owner) != VisualStage.FULL_BLOOM)) continue;
                BlockPos pos = BlockPos.of(packed);
                if (pos.distToCenterSqr(center.getX() + 0.5, center.getY() + 0.5,
                        center.getZ() + 0.5) > maxDistance) continue;
                var species = com.ferreusveritas.dynamictrees.tree.species.Species.REGISTRY.getOptional(owner.speciesId());
                if (species.isEmpty() || species.get().getFamily().getBranch().isEmpty()
                        || species.get().getFamily().getBranch().get() != level.getBlockState(pos).getBlock()) continue;
                BlockState state = level.getBlockState(pos);
                if (!(state.getBlock() instanceof BasicBranchBlock branch) || branch.getRadius(state) > 2) continue;
                return pos;
            }
        }
        return null;
    }

    private static void requestChunkRefresh(int chunkX, int chunkZ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null)
            minecraft.levelRenderer.setBlocksDirty(chunkX << 4, minecraft.level.getMinBuildHeight(), chunkZ << 4,
                    (chunkX << 4) + 15, minecraft.level.getMaxBuildHeight() - 1, (chunkZ << 4) + 15);
    }

    private static void requestRefresh() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null && minecraft.player != null) {
            refreshLevel = minecraft.level;
            refreshCenterX = minecraft.player.getBlockX() >> 4;
            refreshCenterZ = minecraft.player.getBlockZ() >> 4;
            refreshRadius = minecraft.options.renderDistance().get() + 1;
            refreshIndex = 0;
        }
    }

    /** Rebuild at most eight loaded chunks per client tick after a visual transition. */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            refreshIndex = -1;
            if (lastLevel != null) {
                phase = 0;
                enabled = false;
                profileRevision = -1;
                visualProfiles = Map.of();
                calendarDimension = null;
                OWNED_VISUALS.clear();
                COMPLETE_VISUAL_CHUNKS.clear();
                BLOOM_BRANCH_CANDIDATES.clear();
                PENDING_CHUNKS.clear();
                SECTION_REFRESHES.clear();
                PetalParticles.clear();
                lastLevel = null;
            }
            return;
        }
        if (lastLevel != null && lastLevel != minecraft.level
                && !minecraft.level.dimension().location().equals(calendarDimension)) {
            phase = 0;
            enabled = false;
            OWNED_VISUALS.clear();
            COMPLETE_VISUAL_CHUNKS.clear();
            BLOOM_BRANCH_CANDIDATES.clear();
            PENDING_CHUNKS.clear();
            SECTION_REFRESHES.clear();
            PetalParticles.clear();
            refreshIndex = -1;
        }
        lastLevel = minecraft.level;
        int budget = 8;
        while (budget > 0 && !SECTION_REFRESHES.isEmpty()) {
            DirtySection request = SECTION_REFRESHES.removeFirst();
            if (request.level() != minecraft.level) continue;
            int chunkX = ChunkPos.getX(request.chunkKey()), chunkZ = ChunkPos.getZ(request.chunkKey());
            if (!request.level().hasChunk(chunkX, chunkZ)) continue;
            minecraft.levelRenderer.setBlocksDirty(chunkX << 4, request.minY(), chunkZ << 4,
                    (chunkX << 4) + 15, request.maxY(), (chunkZ << 4) + 15);
            budget--;
        }
        Iterator<Long> pending = PENDING_CHUNKS.iterator();
        while (pending.hasNext() && budget > 0) {
            long key = pending.next();
            pending.remove();
            requestChunkRefresh(ChunkPos.getX(key), ChunkPos.getZ(key));
            budget--;
        }
        if (refreshIndex < 0) return;
        if (minecraft.level != refreshLevel) {
            refreshIndex = -1;
            return;
        }
        ClientLevel level = minecraft.level;
        int width = 2 * refreshRadius + 1;
        int total = width * width;
        int scanBudget = 32;
        while (refreshIndex < total && budget > 0 && scanBudget-- > 0) {
            int x = refreshCenterX + refreshIndex % width - refreshRadius;
            int z = refreshCenterZ + refreshIndex / width - refreshRadius;
            refreshIndex++;
            if (!level.hasChunk(x, z)) continue;
            levelRenderer(minecraft, level, x, z);
            budget--;
        }
        if (refreshIndex >= total) refreshIndex = -1;
    }

    private static void levelRenderer(Minecraft minecraft, ClientLevel level, int chunkX, int chunkZ) {
        minecraft.levelRenderer.setBlocksDirty(chunkX << 4, level.getMinBuildHeight(), chunkZ << 4,
                (chunkX << 4) + 15, level.getMaxBuildHeight() - 1, (chunkZ << 4) + 15);
    }

    private record DirtySection(ClientLevel level, long chunkKey, int minY, int maxY) {}

}
