package com.bettercontent.seasonaltrees.harness;

import com.bettercontent.seasonaltrees.client.ClientCalendar;
import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import com.ferreusveritas.dynamictrees.util.LevelContext;
import com.ferreusveritas.dynamictrees.util.SafeChunkBounds;
import com.ferreusveritas.dynamictrees.worldgen.GenerationContext;
import com.ferreusveritas.dynamictrees.worldgen.JoCode;
import com.ferreusveritas.dynamictrees.worldgen.JoCodeRegistry;
import com.google.gson.GsonBuilder;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.Screenshot;
import net.minecraft.client.CloudStatus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundLevelChunkWithLightPacket;
import net.minecraft.world.level.LightLayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.versions.forge.ForgeVersion;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.season.SeasonHandler;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;

/** Test-source-only native client proof. Never include this source set in the shipping JAR. */
@Mod.EventBusSubscriber(modid = "better_seasonal_trees", value = Dist.CLIENT,
        bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SeasonalTreesVisualHarness {
    private static final String WORLD = "seasonal_visual_fixture";
    private static final BlockPos BIRCH = new BlockPos(0, 180, 0);
    private static final BlockPos CHERRY = new BlockPos(30, 180, 0);
    private static final BlockPos SPRUCE = new BlockPos(60, 180, 0);
    private static final ResourceLocation CHERRY_ID = new ResourceLocation("dynamictrees", "cherry");
    private static final List<Scene> SCENES = List.of(
            new Scene("01-summer-overview.png", 4.0, Stage.SUMMER, false),
            new Scene("02-summer-cherry-closeup.png", 4.0, Stage.SUMMER, true),
            new Scene("03-autumn-overview.png", 8.5, Stage.AUTUMN, false),
            new Scene("04-winter-overview.png", 9.5, Stage.WINTER, false),
            new Scene("05-bloom-overview.png", 1.0 / 6, Stage.BLOOM, false),
            new Scene("06-bloom-cherry-closeup.png", 1.0 / 6, Stage.BLOOM, true),
            new Scene("07-leafout-overview.png", 0.70, Stage.LEAFOUT, false));

    private static boolean bootRequested, prepared, phaseRequested, capturePending;
    private static boolean initialRefreshStarted, initialLightingReady;
    private static int startupTicks, sceneTicks, sceneIndex, finishTicks;
    private static int qualificationClientTicks;
    private static volatile int qualificationSweepCount;
    private static volatile boolean qualificationSweepPending, nativeQualificationComplete;
    private static int lightWaitTicks, stableLightTicks, rebakeWaitTicks;
    private static int lastServerLightProbeTick = -20, lightRefreshPacketCount;
    private static boolean lightRefreshRequested;
    private static List<Integer> INITIAL_SKY_SAMPLES = List.of();
    private static int initialRebakedChunkCount;
    private static List<Integer> qualifiedNativeLeafCounts = List.of();
    private static int initialBirch, initialCherry;
    private static volatile Probe latestProbe;
    private static volatile ClientLeafCounts latestClientLeafCounts;
    private static volatile ServerLightSnapshot latestServerLightSnapshot;
    private static volatile boolean probePending;
    private static UUID playerId;
    private static final List<Map<String, Object>> CAPTURE_RECEIPTS = new ArrayList<>();

    private SeasonalTreesVisualHarness() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END
                || !Boolean.getBoolean("better_seasonal_trees.visualHarness")) return;
        Minecraft minecraft = Minecraft.getInstance();
        requireIsolatedDirectory(minecraft);

        if (minecraft.level == null || minecraft.player == null) {
            startupTicks++;
            if (!bootRequested && minecraft.getOverlay() == null && minecraft.screen != null
                    && startupTicks >= 30) {
                Path levelDat = minecraft.gameDirectory.toPath().resolve("saves")
                        .resolve(WORLD).resolve("level.dat");
                if (!Files.isRegularFile(levelDat))
                    throw new IllegalStateException("Missing isolated visual fixture: " + levelDat);
                bootRequested = true;
                minecraft.createWorldOpenFlows().loadLevel(minecraft.screen, WORLD);
            }
            if (startupTicks > 1200)
                throw new IllegalStateException("Timed out loading isolated seasonal visual world");
            return;
        }

        if (!prepared) {
            if (minecraft.getSingleplayerServer() == null)
                throw new IllegalStateException("Visual harness requires an isolated integrated server");
            prepared = true;
            playerId = minecraft.player.getUUID();
            minecraft.options.hideGui = true;
            minecraft.options.cloudStatus().set(CloudStatus.OFF);
            minecraft.getSingleplayerServer().execute(() -> prepare(minecraft, playerId));
            return;
        }
        if (!advanceNativeQualification(minecraft)) return;
        if (!prepareInitialClientLighting(minecraft)) return;
        if (sceneIndex >= SCENES.size()) {
            if (++finishTicks >= 30) minecraft.stop();
            return;
        }
        if (!phaseRequested) {
            phaseRequested = true;
            sceneTicks = 0;
            latestProbe = null;
            Scene scene = SCENES.get(sceneIndex);
            minecraft.getSingleplayerServer().execute(() -> {
                setPhase(minecraft.getSingleplayerServer().overworld(), scene.phase());
                setCamera(minecraft.getSingleplayerServer().overworld(), minecraft, scene.closeup());
            });
            return;
        }
        sceneTicks++;
        if (sceneTicks % 20 == 0 && !probePending) {
            probePending = true;
            minecraft.getSingleplayerServer().execute(() -> {
                latestProbe = probe(minecraft.getSingleplayerServer().overworld());
                probePending = false;
            });
        }
        if (latestProbe != null) latestClientLeafCounts = probeClientLeaves(minecraft.level);
        if (sceneTicks > 1500)
            throw new IllegalStateException("Native seasonal visual scene timed out: " +
                    SCENES.get(sceneIndex).file() + " probe=" + latestProbe);
        if (capturePending || sceneTicks < 80 || !ready(latestProbe)) return;

        capturePending = true;
        String file = SCENES.get(sceneIndex).file();
        Screenshot.grab(minecraft.gameDirectory, file, minecraft.getMainRenderTarget(), message ->
                minecraft.execute(() -> finishCapture(minecraft, file, message)));
    }

    /** Waits for client sky light, then rebuilds only loaded tree chunks within the Y180 fixture band. */
    private static boolean prepareInitialClientLighting(Minecraft minecraft) {
        if (initialLightingReady) return true;
        var level = minecraft.level;
        if (!initialRefreshStarted) {
            lightWaitTicks++;
            ClientLightSnapshot clientLight = inspectClientLight(level);
            if (lightWaitTicks - lastServerLightProbeTick >= 20) {
                lastServerLightProbeTick = lightWaitTicks;
                minecraft.getSingleplayerServer().execute(() -> latestServerLightSnapshot =
                        inspectServerLight(minecraft.getSingleplayerServer().overworld()));
            }
            ServerLightSnapshot serverLight = latestServerLightSnapshot;
            int[] samples = clientLight.airSky().stream().mapToInt(Integer::intValue).toArray();
            if (samples[0] >= 14 && samples[1] >= 14 && samples[2] >= 14) stableLightTicks++;
            else stableLightTicks = 0;
            if (serverLight != null && serverLight.ready() && !lightRefreshRequested
                    && (samples[0] < 14 || samples[1] < 14 || samples[2] < 14)) {
                lightRefreshRequested = true;
                minecraft.getSingleplayerServer().execute(() -> {
                    ServerLevel serverLevel = minecraft.getSingleplayerServer().overworld();
                    ServerPlayer player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(playerId);
                    if (player == null) throw new IllegalStateException("Visual harness light-refresh player missing");
                    lightRefreshPacketCount = resendFixtureChunksWithLight(serverLevel, player);
                    System.out.println("SEASONAL_VISUAL_HARNESS resent bounded chunks-with-light packets="
                            + lightRefreshPacketCount + " server=" + inspectServerLight(serverLevel)
                            + " client=" + clientLight);
                });
            }
            if (lightWaitTicks % 100 == 0) {
                System.out.println("SEASONAL_VISUAL_HARNESS initial light diagnostic tick=" + lightWaitTicks
                        + " server=" + serverLight + " client=" + clientLight
                        + " resentPackets=" + lightRefreshPacketCount);
            }
            if (stableLightTicks < 60) {
                if (lightWaitTicks > 1200)
                    throw new IllegalStateException("Timed out waiting for native client sky light; server="
                            + serverLight + " client=" + clientLight
                            + " resentPackets=" + lightRefreshPacketCount);
                return false;
            }
            INITIAL_SKY_SAMPLES = List.copyOf(clientLight.airSky());
            TreeSet<Long> chunks = new TreeSet<>();
            for (BlockPos root : List.of(BIRCH, CHERRY, SPRUCE)) {
                int minX = (root.getX() - 10) >> 4, maxX = (root.getX() + 10) >> 4;
                int minZ = (root.getZ() - 10) >> 4, maxZ = (root.getZ() + 10) >> 4;
                for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                    if (level.hasChunk(x, z)) chunks.add(ChunkPos.asLong(x, z));
            }
            initialRebakedChunkCount = ClientCalendar.scheduleBoundedSectionRefresh(
                    level, chunks, 176, 214, 32);
            initialRefreshStarted = true;
            System.out.println("SEASONAL_VISUAL_HARNESS sky stable server=" + serverLight
                    + " client=" + clientLight + " resentPackets=" + lightRefreshPacketCount
                    + " boundedChunks=" + initialRebakedChunkCount);
        }
        if (ClientCalendar.pendingSectionRefreshes() > 0) return false;
        if (++rebakeWaitTicks < 60) return false;
        initialLightingReady = true;
        System.out.println("SEASONAL_VISUAL_HARNESS bounded initial chunk rebake settled");
        return true;
    }

    private static List<BlockPos> airLightSamples() {
        return List.of(BIRCH.offset(11, 20, 11), CHERRY.offset(11, 20, 11),
                SPRUCE.offset(11, 20, 11));
    }

    /** Samples canopy-facing air and the same off-tree air points reported by the server. */
    private static List<BlockPos> faceLightSamples() {
        return List.of(BIRCH.offset(0, 14, -11), CHERRY.offset(0, 14, -11),
                SPRUCE.offset(0, 14, -11));
    }

    private static ClientLightSnapshot inspectClientLight(ClientLevel level) {
        return new ClientLightSnapshot(sampleSky(level, airLightSamples()),
                sampleSky(level, faceLightSamples()));
    }

    private static ServerLightSnapshot inspectServerLight(ServerLevel level) {
        List<Long> chunks = fixtureChunkKeys(level);
        List<Boolean> lightCorrect = chunks.stream().map(key -> level.getChunk(
                ChunkPos.getX(key), ChunkPos.getZ(key)).isLightCorrect()).toList();
        List<Integer> airSky = sampleSky(level, airLightSamples());
        List<Integer> faceSky = sampleSky(level, faceLightSamples());
        boolean ready = chunks.size() > 0 && lightCorrect.stream().allMatch(Boolean::booleanValue)
                && airSky.stream().allMatch(value -> value >= 14);
        return new ServerLightSnapshot(airSky, faceSky, lightCorrect, ready);
    }

    private static List<Integer> sampleSky(net.minecraft.world.level.Level level, List<BlockPos> positions) {
        return positions.stream().map(pos -> level.hasChunkAt(pos)
                ? level.getBrightness(LightLayer.SKY, pos) : 0).toList();
    }

    private static List<Long> fixtureChunkKeys(ServerLevel level) {
        TreeSet<Long> chunks = new TreeSet<>();
        for (BlockPos root : List.of(BIRCH, CHERRY, SPRUCE)) {
            int minX = (root.getX() - 10) >> 4, maxX = (root.getX() + 10) >> 4;
            int minZ = (root.getZ() - 10) >> 4, maxZ = (root.getZ() + 10) >> 4;
            for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++)
                if (level.hasChunk(x, z)) chunks.add(ChunkPos.asLong(x, z));
        }
        if (chunks.size() > 32) throw new IllegalStateException("Fixture light refresh exceeds 32 chunks");
        return List.copyOf(chunks);
    }

    private static int resendFixtureChunksWithLight(ServerLevel level, ServerPlayer player) {
        List<Long> chunks = fixtureChunkKeys(level);
        int sent = 0;
        for (long key : chunks) {
            var chunk = level.getChunk(ChunkPos.getX(key), ChunkPos.getZ(key));
            if (!chunk.isLightCorrect())
                throw new IllegalStateException("Refusing chunk-with-light resend before server lighting is ready: " + key);
            player.connection.send(new ClientboundLevelChunkWithLightPacket(
                    chunk, level.getLightEngine(), null, null));
            sent++;
        }
        return sent;
    }

    private static void requireIsolatedDirectory(Minecraft minecraft) {
        String path = minecraft.gameDirectory.toPath().toAbsolutePath().normalize().toString();
        if (!(path.endsWith("/evidence/visual") || path.contains("/evidence/visual/")))
            throw new IllegalStateException("Refusing visual harness outside project evidence/visual: " + path);
    }

    private static void prepare(Minecraft minecraft, UUID playerId) {
        ServerLevel level = minecraft.getSingleplayerServer().overworld();
        setPhase(level, 4.0);
        generate(level, BIRCH, "birch");
        generate(level, CHERRY, "cherry");
        generate(level, SPRUCE, "spruce");
        if (!(level.getBlockState(BIRCH).getBlock() instanceof RootyBlock)
                || !(level.getBlockState(CHERRY).getBlock() instanceof RootyBlock)
                || !(level.getBlockState(SPRUCE).getBlock() instanceof RootyBlock))
            throw new IllegalStateException("Native JoCode did not create all three roots");
        ServerPlayer player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Visual harness player missing");
        player.setGameMode(GameType.SPECTATOR);
        setCamera(level, minecraft, false);
        level.getGameRules().getRule(GameRules.RULE_DAYLIGHT).set(false, level.getServer());
        level.getGameRules().getRule(GameRules.RULE_WEATHER_CYCLE).set(false, level.getServer());
        level.setWeatherParameters(6000, 0, false, false);
        level.setDayTime(6000);
        System.out.println("SEASONAL_VISUAL_HARNESS native trees prepared birch=" + BIRCH +
                " cherry=" + CHERRY + " spruce=" + SPRUCE);
    }

    /** Mirrors the proven native-fixture hydration/survival sweep before seasonal baseline capture. */
    private static boolean advanceNativeQualification(Minecraft minecraft) {
        if (nativeQualificationComplete) return true;
        if (qualificationSweepPending) return false;
        qualificationClientTicks++;
        if (qualificationClientTicks > 1200)
            throw new IllegalStateException("Timed out qualifying native visual baselines: sweeps="
                    + qualificationSweepCount + " pending=" + qualificationSweepPending);
        if (qualificationClientTicks % 5 != 0) return false;
        if (qualificationSweepCount >= 8)
            throw new IllegalStateException("Native qualification did not finalize");
        int settleTick = new int[]{5, 10, 15, 20, 25, 30, 35, 38}[qualificationSweepCount];
        qualificationSweepPending = true;
        minecraft.getSingleplayerServer().execute(() -> {
            ServerLevel level = minecraft.getSingleplayerServer().overworld();
            for (BlockPos root : List.of(BIRCH, CHERRY, SPRUCE)) {
                level.random.setSeed(8675309L + settleTick * 31L + root.asLong());
                for (var entry : findLeaves(level, root).entrySet()) {
                    BlockState state = level.getBlockState(entry.getKey());
                    if (state.getBlock() instanceof DynamicLeavesBlock leaves) {
                        leaves.updateHydro(level, entry.getKey(), state, false);
                        BlockState hydrated = level.getBlockState(entry.getKey());
                        if (hydrated.getBlock() instanceof DynamicLeavesBlock live)
                            live.removeIfLightIsInadequate(hydrated, level, entry.getKey(), level.random);
                    }
                }
            }
            qualificationSweepCount++;
            if (qualificationSweepCount == 8) {
                ArrayList<Integer> counts = new ArrayList<>();
                for (BlockPos root : List.of(BIRCH, CHERRY, SPRUCE)) {
                    Map<BlockPos, BlockState> leaves = findLeaves(level, root);
                    for (var entry : leaves.entrySet()) {
                        DynamicLeavesBlock leaf = (DynamicLeavesBlock) entry.getValue().getBlock();
                        var properties = leaf.getProperties(entry.getValue());
                        int hydration = leaf.getHydrationLevelFromNeighbors(level, entry.getKey(), properties);
                        boolean survivalLight = leaf.hasAdequateLight(entry.getValue(), level,
                                properties, entry.getKey());
                        if (hydration <= 0 || !survivalLight)
                            throw new IllegalStateException("Unqualified native visual baseline leaf "
                                    + entry.getKey() + " hydration=" + hydration + " survivalLight=" + survivalLight);
                    }
                    counts.add(leaves.size());
                }
                if (counts.stream().anyMatch(count -> count < 20))
                    throw new IllegalStateException("Native visual fixture canopy too small after qualification: " + counts);
                if (!SeasonalRuntime.ensureTracked(level, BIRCH)
                        || !SeasonalRuntime.ensureTracked(level, CHERRY))
                    throw new IllegalStateException("Qualified native birch/cherry roots were not admitted to seasonal tracking");
                qualifiedNativeLeafCounts = List.copyOf(counts);
                nativeQualificationComplete = true;
                System.out.println("SEASONAL_VISUAL_HARNESS native survival baseline qualified sweeps="
                        + qualificationSweepCount + " leaves=" + qualifiedNativeLeafCounts);
            }
            qualificationSweepPending = false;
        });
        return false;
    }

    private static void setCamera(ServerLevel level, Minecraft minecraft, boolean closeup) {
        ServerPlayer player = minecraft.getSingleplayerServer().getPlayerList().getPlayer(playerId);
        if (player == null) throw new IllegalStateException("Visual harness camera player missing");
        if (closeup) player.teleportTo(level, 30.5, 188.0, -19.5, 0.0F, -4.0F);
        else player.teleportTo(level, 30.5, 190.0, -49.5, 0.0F, -2.0F);
    }

    private static void generate(ServerLevel level, BlockPos root, String name) {
        for (int cx = (root.getX() >> 4) - 2; cx <= (root.getX() >> 4) + 2; cx++)
            for (int cz = (root.getZ() >> 4) - 2; cz <= (root.getZ() >> 4) + 2; cz++)
                level.getChunk(cx, cz);
        for (BlockPos p : BlockPos.betweenClosed(root.offset(-10, -1, -10), root.offset(10, -1, 10)))
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
        Species species = Species.REGISTRY.get(new ResourceLocation("dynamictrees", name));
        var codes = JoCodeRegistry.getCodes(species.getRegistryName());
        int radius = codes.keySet().stream()
                .min(Comparator.comparingInt(r -> Math.abs(r - 5))).orElseThrow();
        JoCode nativeCode = codes.get(radius).get(0);
        level.random.setSeed(8675309L + name.hashCode());
        new JoCode(nativeCode.toString()).setCareful(true).generate(new GenerationContext(
                LevelContext.create(level), species, root, root.mutable(), level.getBiome(root),
                Direction.NORTH, radius, SafeChunkBounds.ANY));
        System.out.println("SEASONAL_VISUAL_HARNESS generated " + name +
                " leaves=" + countLeaves(level, root));
    }

    private static void setPhase(ServerLevel level, double phase) {
        var state = SeasonHelper.getSeasonState(level);
        if (state == null) throw new IllegalStateException("Serene Seasons unavailable in visual world");
        var data = SeasonHandler.getSeasonSavedData(level);
        data.seasonCycleTicks = (int) Math.round(phase * state.getSubSeasonDuration());
        data.setDirty();
        SeasonHandler.sendSeasonUpdate(level);
        System.out.println("SEASONAL_VISUAL_HARNESS phase=" + phase);
    }

    private static Probe probe(ServerLevel level) {
        var branches = SeasonalRuntime.managedBranches(level, new ChunkPos(CHERRY), CHERRY_ID, 2048);
        long sample = branches.positions().stream().findFirst().map(BlockPos::asLong).orElse(0L);
        return new Probe(countLeaves(level, BIRCH), countLeaves(level, CHERRY),
                countLeaves(level, SPRUCE), branches.complete(), sample,
                SeasonalRuntime.inspectSnapshot(level, BIRCH).toString(),
                SeasonalRuntime.inspectSnapshot(level, CHERRY).toString());
    }

    private static ClientLeafCounts probeClientLeaves(ClientLevel level) {
        return new ClientLeafCounts(countLeaves(level, BIRCH), countLeaves(level, CHERRY),
                countLeaves(level, SPRUCE));
    }

    private static int countLeaves(ServerLevel level, BlockPos root) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(root.offset(-10, 0, -10), root.offset(10, 30, 10)))
            if (level.getBlockState(pos).getBlock() instanceof DynamicLeavesBlock) count++;
        return count;
    }

    private static Map<BlockPos, BlockState> findLeaves(ServerLevel level, BlockPos root) {
        Map<BlockPos, BlockState> leaves = new LinkedHashMap<>();
        for (BlockPos pos : BlockPos.betweenClosed(root.offset(-10, 0, -10), root.offset(10, 30, 10))) {
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() instanceof DynamicLeavesBlock) leaves.put(pos.immutable(), state);
        }
        return leaves;
    }

    private static int countLeaves(ClientLevel level, BlockPos root) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(root.offset(-10, 0, -10), root.offset(10, 30, 10)))
            if (level.hasChunkAt(pos) && level.getBlockState(pos).getBlock() instanceof DynamicLeavesBlock) count++;
        return count;
    }

    private static boolean ready(Probe probe) {
        ClientLeafCounts client = latestClientLeafCounts;
        if (probe == null || client == null) return false;
        if (!clientMatchesServer(client.birch(), probe.birch())
                || !clientMatchesServer(client.cherry(), probe.cherry())
                || !clientMatchesServer(client.spruce(), probe.spruce())) return false;
        switch (SCENES.get(sceneIndex).stage()) {
            case SUMMER -> {
                if (probe.birch() < 20 || probe.cherry() < 20 || probe.spruce() < 20) return false;
                if (sceneIndex == 0) {
                    initialBirch = probe.birch();
                    initialCherry = probe.cherry();
                }
                return ClientCalendar.enabled()
                        && ClientCalendar.visualStage(CHERRY_ID) == ClientCalendar.VisualStage.GREEN;
            }
            case AUTUMN -> { return probe.birch() < initialBirch && probe.cherry() < initialCherry; }
            case WINTER -> { return probe.birch() == 0 && probe.cherry() == 0 && probe.spruce() >= 20; }
            case BLOOM -> {
                return probe.birch() > 0 && probe.cherry() == 0
                        && ClientCalendar.enabled()
                        && (ClientCalendar.visualStage(CHERRY_ID) == ClientCalendar.VisualStage.SPARSE_BLOOM
                            || ClientCalendar.visualStage(CHERRY_ID) == ClientCalendar.VisualStage.FULL_BLOOM)
                        && probe.branchComplete() && probe.sampleBranch() != 0
                        && ClientCalendar.ownsBranch(BlockPos.of(probe.sampleBranch()));
            }
            case LEAFOUT -> {
                return probe.birch() >= Math.max(1, initialBirch / 10)
                        && probe.cherry() >= Math.max(1, initialCherry / 10)
                        && ClientCalendar.visualStage(CHERRY_ID) == ClientCalendar.VisualStage.GREEN;
            }
        }
        throw new IllegalStateException("Unknown visual stage: " + SCENES.get(sceneIndex).stage());
    }

    private static boolean clientMatchesServer(int client, int server) {
        int allowedDifference = server == 0 ? 0 : Math.max(2, server / 4);
        return Math.abs(client - server) <= allowedDifference;
    }

    private static void finishCapture(Minecraft minecraft, String file, Component message) {
        Path screenshot = minecraft.gameDirectory.toPath().resolve("screenshots").resolve(file);
        if (!Files.isRegularFile(screenshot))
            throw new IllegalStateException("Native visual screenshot failed: " + file + " (" + message.getString() + ")");
        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("file", file);
        receipt.put("phase", SCENES.get(sceneIndex).phase());
        receipt.put("camera", SCENES.get(sceneIndex).closeup() ? "cherry-closeup" : "three-tree-overview");
        receipt.put("birchLeaves", latestProbe.birch());
        receipt.put("cherryLeaves", latestProbe.cherry());
        receipt.put("spruceLeaves", latestProbe.spruce());
        receipt.put("clientBirchLeaves", latestClientLeafCounts.birch());
        receipt.put("clientCherryLeaves", latestClientLeafCounts.cherry());
        receipt.put("clientSpruceLeaves", latestClientLeafCounts.spruce());
        receipt.put("branchSnapshotComplete", latestProbe.branchComplete());
        receipt.put("initialSkySamples", INITIAL_SKY_SAMPLES);
        CAPTURE_RECEIPTS.add(receipt);
        System.out.println("SEASONAL_VISUAL_HARNESS captured " + file + " " + latestProbe);
        sceneIndex++;
        sceneTicks = 0;
        phaseRequested = false;
        capturePending = false;
        if (sceneIndex == SCENES.size()) writeCompletion(minecraft.gameDirectory.toPath());
    }

    private static void writeCompletion(Path directory) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("status", "complete");
            metadata.put("minecraft", SharedConstants.getCurrentVersion().getName());
            metadata.put("forge", ForgeVersion.getVersion());
            metadata.put("world", WORLD);
            metadata.put("nativeRoots", List.of(BIRCH.toShortString(), CHERRY.toShortString(), SPRUCE.toShortString()));
            metadata.put("initialLighting", Map.of("skySamples", INITIAL_SKY_SAMPLES,
                    "lightWaitTicks", lightWaitTicks, "stableTicks", stableLightTicks,
                    "rebakedChunks", initialRebakedChunkCount,
                    "rebakeSettleTicks", rebakeWaitTicks,
                    "lightRefreshPackets", lightRefreshPacketCount,
                    "serverLight", latestServerLightSnapshot));
            metadata.put("nativeQualification", Map.of("sweeps", qualificationSweepCount,
                    "qualifiedLeafCounts", qualifiedNativeLeafCounts));
            metadata.put("captures", CAPTURE_RECEIPTS);
            Files.writeString(directory.resolve("seasonal-visual-complete.json"),
                    new GsonBuilder().setPrettyPrinting().create().toJson(metadata) + "\n");
        } catch (Exception exception) {
            throw new IllegalStateException("Unable to write native visual completion receipt", exception);
        }
    }

    private enum Stage { SUMMER, AUTUMN, WINTER, BLOOM, LEAFOUT }
    private record Scene(String file, double phase, Stage stage, boolean closeup) {}
    private record ClientLeafCounts(int birch, int cherry, int spruce) {}
    private record ClientLightSnapshot(List<Integer> airSky, List<Integer> canopyFaceSky) {}
    private record ServerLightSnapshot(List<Integer> airSky, List<Integer> canopyFaceSky,
                                       List<Boolean> chunksLightCorrect, boolean ready) {}
    private record Probe(int birch, int cherry, int spruce, boolean branchComplete,
                         long sampleBranch, String birchRuntime, String cherryRuntime) {}
}
