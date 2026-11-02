package com.bettercontent.seasonaltrees.persistence;

import com.bettercontent.seasonaltrees.BetterSeasonalTrees;
import com.bettercontent.seasonaltrees.catalogue.SeasonalCatalogue;
import com.bettercontent.seasonaltrees.runtime.SeasonalLedger;
import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import com.bettercontent.seasonaltrees.scheduler.SeasonClock;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import com.ferreusveritas.dynamictrees.util.LevelContext;
import com.ferreusveritas.dynamictrees.util.SafeChunkBounds;
import com.ferreusveritas.dynamictrees.worldgen.GenerationContext;
import com.ferreusveritas.dynamictrees.worldgen.JoCode;
import com.ferreusveritas.dynamictrees.worldgen.JoCodeRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;
import sereneseasons.api.season.SeasonHelper;
import sereneseasons.season.SeasonHandler;

import java.io.IOException;
import java.io.Reader;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Two independent server processes, one exact isolated world. Never included in the shipping JAR. */
@Mod.EventBusSubscriber(modid = BetterSeasonalTrees.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class PersistenceFixture {
    private static final BlockPos ROOT = new BlockPos(0, 180, 0);
    private static final ResourceLocation BIRCH = new ResourceLocation("dynamictrees:birch");
    private static final Path EXPECTED_PARENT = Path.of(
        "/home/dev/mod_source/better-seasonal-trees/evidence/persistence");
    private static final int WRITE_DEADLINE = 2000;
    private static final int READ_DEADLINE = 2000;
    private static boolean initialized, stopped;
    private static String stage, mode;
    private static Path attempt;
    private static int tick;
    private static JsonObject summer;
    private static JsonObject baseline;
    private static JsonArray fullPlan;
    private static boolean partialSpring;
    private static BlockPos partialBrokenLeaf;
    private static int partialBreakTick, partialTicketCount;
    private static boolean earlyDamageStarted;
    private static BlockPos earlyBranch, earlyDependent;

    private PersistenceFixture() { }

    /** Runs before the first scheduler tick, while RootWork's transient branch map is empty. */
    @SubscribeEvent
    public static void onServerStarted(ServerStartedEvent event) {
        if (!Boolean.getBoolean("better_seasonal_trees.persistence") ||
            !"read".equals(System.getProperty("better_seasonal_trees.persistenceStage", "")) ||
            !"earlyBranchDamage".equals(System.getProperty("better_seasonal_trees.persistenceMode", "winter")))
            return;
        try {
            if (!initialized) initialize();
            MinecraftServer server = event.getServer();
            require(tick == 0 && server.getTickCount() == 0,
                "early damage hook did not run before the first server/runtime tick");
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            require(level != null, "Overworld unavailable at ServerStarted");
            loadSafetyRing(level); // Fixture preparation only; never addon chunk forcing.
            SeasonalLedger ledger = SeasonalLedger.get(level);
            SeasonalLedger.Tree tree = ledger.find(ROOT);
            require(tree != null && tree.incarnation.toString().equals(
                baseline.get("rootIncarnation").getAsString()), "saved native root incarnation unavailable");
            require(Marker.get(level).id.toString().equals(baseline.get("worldMarker").getAsString()),
                "early damage read loaded a different world");
            require(ticketStates(tree).equals(baseline.getAsJsonArray("tickets")),
                "saved winter tickets changed before branch damage");
            earlyBranch = BlockPos.of(baseline.get("damageBranch").getAsLong());
            earlyDependent = BlockPos.of(baseline.get("damageDependent").getAsLong());
            SeasonalLedger.Ticket dependent = tree.tickets.get(earlyDependent.asLong());
            require(dependent != null && !dependent.consumed && dependent.support.equals(earlyBranch),
                "saved dependent ticket/branch relation changed");
            require(tree.managedBranchesView().contains(earlyBranch.asLong()) &&
                    ledger.managedBranchOwnersAt(earlyBranch).contains(ROOT.asLong()),
                "persisted branch ownership was not available before transient capture");
            require(SeasonalRuntime.transientBranchOwnersAt(level, earlyBranch).isEmpty(),
                "transient branch ownership was already rebuilt before startup damage");
            BlockState branch = level.getBlockState(earlyBranch);
            require(branch.getBlock() instanceof BranchBlock &&
                    stateKey(branch).equals(baseline.get("damageBranchState").getAsString()),
                "saved distal native branch changed before startup damage");
            require(level.setBlock(earlyBranch, Blocks.AIR.defaultBlockState(), 2),
                "startup branch-to-air damage failed");
            require(level.getBlockState(earlyBranch).isAir(), "startup branch damage did not remove block");
            require(level.setBlock(earlyBranch, branch, 2),
                "startup same-state branch replacement failed");
            require(level.getBlockState(earlyBranch).equals(branch),
                "startup replacement changed native branch state");
            require(ledger.isBranchTombstoned(tree, earlyBranch, dependent.targetCycle) &&
                    ledger.managedBranchOwnersAt(earlyBranch).contains(ROOT.asLong()),
                "persisted branch index did not tombstone the pending dependent ticket");
            earlyDamageStarted = true;
        } catch (Exception ex) {
            fail(ex);
        }
    }

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stopped ||
            !Boolean.getBoolean("better_seasonal_trees.persistence")) return;
        try {
            if (!initialized) initialize();
            MinecraftServer server = event.getServer();
            ServerLevel level = server.getLevel(Level.OVERWORLD);
            require(level != null, "Overworld is unavailable");
            tick++;
            if (tick == 1) require(level.getGameRules().getInt(GameRules.RULE_RANDOMTICKING) > 0,
                "randomTickSpeed must remain active during persistence proof");
            if (stage.equals("write")) writeStage(server, level);
            else if (mode.equals("partialAutumn")) readPartialAutumn(server, level);
            else if (mode.equals("earlyBranchDamage")) readEarlyBranchDamage(server, level);
            else readStage(server, level);
        } catch (Exception ex) {
            fail(ex);
        }
    }

    private static void initialize() throws IOException {
        stage = System.getProperty("better_seasonal_trees.persistenceStage", "");
        require(stage.equals("write") || stage.equals("read"), "persistenceStage must be write or read");
        mode = System.getProperty("better_seasonal_trees.persistenceMode", "winter");
        require(mode.equals("winter") || mode.equals("partialAutumn") || mode.equals("earlyBranchDamage"),
            "persistenceMode must be winter, partialAutumn, or earlyBranchDamage");
        attempt = Path.of(System.getProperty("user.dir", "")).toRealPath();
        require(attempt.getParent() != null && attempt.getParent().equals(EXPECTED_PARENT.toRealPath()) &&
                attempt.getFileName().toString().matches("[A-Za-z0-9_-]{1,80}"),
            "refusing world outside evidence/persistence/<attempt>: " + attempt);
        require(SeasonalCatalogue.all().size() == 3 && SeasonalCatalogue.profile(BIRCH) != null,
            "Gate A pilot catalogue is not loaded");
        require(!Files.exists(attempt.resolve("success-read.json")), "read success already exists");
        require(!Files.exists(attempt.resolve("failure-" + stage + ".json")), "prior stage failure exists");
        if (stage.equals("write")) {
            require(!Files.exists(attempt.resolve("baseline.json")), "write stage refuses existing baseline");
        } else {
            require(Files.isRegularFile(attempt.resolve("baseline.json")), "read stage requires write baseline");
            require(Files.isRegularFile(attempt.resolve("success-write.json")), "read stage requires write receipt");
            try (Reader reader = Files.newBufferedReader(attempt.resolve("baseline.json"), StandardCharsets.UTF_8)) {
                baseline = JsonParser.parseReader(reader).getAsJsonObject();
            }
            require(baseline.get("schema").getAsString().equals("bst.persistence.v1"), "baseline schema mismatch");
            require(mode.equals(baseline.has("mode") ? baseline.get("mode").getAsString() : "winter"),
                "read mode differs from write baseline mode");
            require(baseline.get("attempt").getAsString().equals(attempt.getFileName().toString()),
                "baseline attempt mismatch");
            long priorPid = baseline.get("writePid").getAsLong();
            long priorVmStart = baseline.get("writeVmStart").getAsLong();
            require(priorPid != ProcessHandle.current().pid() ||
                    priorVmStart != ManagementFactory.getRuntimeMXBean().getStartTime(),
                "read stage must run in a different JVM");
        }
        initialized = true;
    }

    private static void writeStage(MinecraftServer server, ServerLevel level) throws IOException {
        if (tick == 20) {
            require(SeasonClock.read(level) != null, "Serene Seasons calendar unavailable");
            loadSafetyRing(level);
            setPhase(level, 4.0);
            generateBirch(level);
        }
        if (tick == 30 || tick == 35 || tick == 40 || tick == 45 ||
            tick == 50 || tick == 55 || tick == 60 || tick == 70) {
            for (long key : leafPositions(level)) {
                BlockPos pos = BlockPos.of(key);
                BlockState state = level.getBlockState(pos);
                if (state.getBlock() instanceof DynamicLeavesBlock leaves) {
                    leaves.updateHydro(level, pos, state, false);
                    BlockState hydrated = level.getBlockState(pos);
                    if (hydrated.getBlock() instanceof DynamicLeavesBlock live)
                        live.removeIfLightIsInadequate(hydrated, level, pos, level.random);
                }
            }
        }
        if (tick == 80) {
            require(level.getBlockState(ROOT).getBlock() instanceof RootyBlock, "native birch root absent");
            List<Long> leaves = leafPositions(level);
            require(leaves.size() >= 20, "native birch foliage insufficient: " + leaves.size());
            for (long key : leaves) {
                BlockPos pos = BlockPos.of(key);
                BlockState leafState = level.getBlockState(pos);
                DynamicLeavesBlock leaf = (DynamicLeavesBlock) leafState.getBlock();
                var properties = leaf.getProperties(leafState);
                require(leaf.getHydrationLevelFromNeighbors(level, pos, properties) > 0,
                    "native baseline has unsupported foliage at " + pos);
                require(leaf.hasAdequateLight(leafState, level, properties, pos),
                    "native baseline has inadequate survival light at " + pos);
            }
            JsonArray branches = branchStates(level);
            require(branches.size() >= 5, "native birch wood insufficient: " + branches.size());
            summer = new JsonObject();
            summer.add("leaves", longs(leaves));
            summer.add("branches", branches);
            require(SeasonalRuntime.ensureTracked(level, ROOT), "native birch root not admitted");
            setPhase(level, mode.equals("partialAutumn") ? 8.0 : 9.1);
        }
        if (mode.equals("partialAutumn")) {
            writePartialAutumn(server, level);
            return;
        }
        if (tick > 80 && tick % 20 == 0) {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            if (tree != null && leafPositions(level).isEmpty() &&
                tree.tickets.size() == summer.getAsJsonArray("leaves").size()) {
                require(branchStates(level).equals(summer.getAsJsonArray("branches")),
                    "seasonal shedding altered native wood");
                for (SeasonalLedger.Ticket ticket : tree.tickets.values())
                    require(!ticket.consumed, "winter ticket was already consumed");
                baseline = snapshot(level, tree);
                if (mode.equals("earlyBranchDamage")) {
                    SeasonalLedger.Ticket dependent = chooseDamageTicket(level, tree);
                    baseline.addProperty("mode", "earlyBranchDamage");
                    baseline.addProperty("damageBranch", dependent.support.asLong());
                    baseline.addProperty("damageDependent", dependent.pos.asLong());
                    baseline.addProperty("damageBranchState", stateKey(level.getBlockState(dependent.support)));
                    require(tree.managedBranchesView().contains(dependent.support.asLong()),
                        "damage branch absent from persisted membership before save");
                }
                require(baseline.getAsJsonArray("tickets").size() == summer.getAsJsonArray("leaves").size(),
                    "winter ticket count differs from summer leaves");
                require(ticketPositions(tree).equals(summer.getAsJsonArray("leaves")),
                    "tickets do not correspond exactly to native summer foliage");
                require(server.saveEverything(false, true, true), "write stage world save failed");
                writeJson("baseline.json", baseline);
                writeJson("success-write.json", receipt("write", tree, level));
                stopped = true;
                server.halt(false);
            }
        }
        require(tick <= WRITE_DEADLINE, "write stage timed out without a complete winter canopy/ticket set");
    }

    private static void writePartialAutumn(MinecraftServer server, ServerLevel level) throws IOException {
        if (tick < 80) return;
        if (tick % 20 == 0) {
            SeasonalLedger ledger = SeasonalLedger.get(level);
            SeasonalLedger.Tree tree = ledger.find(ROOT);
            int expected = summer.getAsJsonArray("leaves").size();
            if (fullPlan == null && tree != null && tree.planComplete &&
                    tree.planCycle == ledger.cycle() && tree.planSize == expected &&
                    tree.plansView().size() == expected && tree.tickets.isEmpty() &&
                    SeasonClock.read(level).phase() >= 8.0 &&
                    SeasonClock.read(level).phase() < 8.5) {
                fullPlan = planStates(tree);
                require(planRanksValid(fullPlan, expected), "pre-shed plan ranks are not a full 0..N-1 set");
                require(planPositions(tree).equals(summer.getAsJsonArray("leaves")),
                    "complete pre-shed plan does not match qualified summer foliage");
                require(leafPositions(level).equals(jsonLongs(summer.getAsJsonArray("leaves"))),
                    "pre-shed native foliage changed before the plan freeze");
                setPhase(level, 8.5);
            } else if (fullPlan != null && tree != null && tree.planComplete &&
                    tree.planCycle == ledger.cycle() && tree.planSize == expected &&
                    !tree.tickets.isEmpty() && !tree.plansView().isEmpty() &&
                    tree.tickets.size() + tree.plansView().size() == expected &&
                    tree.plansView().values().stream().allMatch(leaf ->
                        leaf.shedDeadline() > SeasonClock.read(level).phase())) {
                require(partitionMatches(tree, fullPlan),
                    "partially shed positions do not partition the original plan");
                require(leafPositions(level).size() == tree.plansView().size(),
                    "physical unshed foliage differs from remaining plan");
                require(branchStates(level).equals(summer.getAsJsonArray("branches")),
                    "partial autumn changed native wood");
                require(tree.tickets.values().stream().noneMatch(ticket -> ticket.consumed),
                    "partial autumn already consumed a restoration ticket");
                baseline = snapshot(level, tree);
                baseline.addProperty("mode", "partialAutumn");
                baseline.addProperty("planCycle", tree.planCycle);
                baseline.addProperty("planComplete", tree.planComplete);
                baseline.addProperty("planSize", tree.planSize);
                baseline.add("allPlan", fullPlan);
                baseline.add("remainingPlan", planStates(tree));
                baseline.add("partialLeaves", longs(leafPositions(level)));
                baseline.add("ticketDetails", ticketDetails(tree));
                require(server.saveEverything(false, true, true), "partial write world save failed");
                writeJson("baseline.json", baseline);
                writeJson("success-write.json", receipt("write", tree, level));
                stopped = true;
                server.halt(false);
            }
        }
        require(tick <= WRITE_DEADLINE,
            "partial write timed out before a complete plan and strict partially shed canopy");
    }

    private static void readPartialAutumn(MinecraftServer server, ServerLevel level) throws IOException {
        if (tick == 20) {
            loadSafetyRing(level);
            SeasonalLedger ledger = SeasonalLedger.get(level);
            SeasonalLedger.Tree tree = ledger.find(ROOT);
            require(level.getBlockState(ROOT).getBlock() instanceof RootyBlock, "saved native root absent");
            require(tree != null, "saved partial-autumn tree absent");
            require(Marker.get(level).id.toString().equals(baseline.get("worldMarker").getAsString()),
                "world marker mismatch: a fresh or different world was loaded");
            require(tree.incarnation.toString().equals(baseline.get("rootIncarnation").getAsString()),
                "partial-autumn root incarnation changed between JVMs");
            require(tree.species.equals(BIRCH) && ledger.cycle() == baseline.get("cycle").getAsLong(),
                "saved species or cycle changed before read-stage phase change");
            require(tree.planCycle == baseline.get("planCycle").getAsLong() &&
                    tree.planComplete == baseline.get("planComplete").getAsBoolean() &&
                    tree.planSize == baseline.get("planSize").getAsInt(),
                "persisted capture-plan header changed across JVMs");
            require(planStates(tree).equals(baseline.getAsJsonArray("remainingPlan")),
                "unshed plan positions, ranks, states or raw-bit deadlines changed across JVMs");
            require(ticketStates(tree).equals(baseline.getAsJsonArray("tickets")) &&
                    ticketDetails(tree).equals(baseline.getAsJsonArray("ticketDetails")),
                "actual partial-autumn ticket state changed across JVMs");
            require(partitionMatches(tree, baseline.getAsJsonArray("allPlan")),
                "reloaded plan/ticket partition does not cover original ranked candidates");
            require(longs(leafPositions(level)).equals(baseline.getAsJsonArray("partialLeaves")) &&
                    branchStates(level).equals(baseline.getAsJsonArray("branches")),
                "partial physical canopy or wood changed across JVMs");
            require(SeasonClock.read(level) != null && SeasonClock.read(level).phase() >= 8.5 &&
                    SeasonClock.read(level).phase() < 9.0,
                "saved calendar is not partial autumn before phase change");
            setPhase(level, 9.1);
        }
        if (tick > 20 && tick % 20 == 0) {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            require(tree != null && tree.incarnation.toString().equals(
                baseline.get("rootIncarnation").getAsString()), "root provenance vanished after restart");
            int expected = baseline.getAsJsonArray("summerLeaves").size();
            if (!partialSpring && tree.tickets.size() == expected &&
                    tree.plansView().isEmpty() && leafPositions(level).isEmpty()) {
                require(ticketPositions(tree).equals(baseline.getAsJsonArray("summerLeaves")),
                    "winter completion missed historical native foliage");
                require(tree.tickets.values().stream().noneMatch(ticket -> ticket.consumed),
                    "winter completion consumed tickets before spring");
                partialSpring = true;
                setPhase(level, 0.4);
            } else if (partialSpring && partialBrokenLeaf == null &&
                    tree.tickets.size() == expected && allConsumed(tree) &&
                    leafPositions(level).equals(jsonLongs(baseline.getAsJsonArray("summerLeaves")))) {
                require(branchStates(level).equals(baseline.getAsJsonArray("branches")),
                    "partial restart restoration changed native wood");
                for (SeasonalLedger.Ticket ticket : tree.tickets.values()) {
                    require(ticket.outcome == SeasonalLedger.Outcome.RESTORED &&
                            level.getBlockState(ticket.pos).getBlock() == ticket.state.getBlock(),
                        "partial restart failed one-use native restoration at " + ticket.pos);
                    require(level.hasChunkAt(ticket.support) && ticket.supportBlock.equals(
                            ForgeRegistries.BLOCKS.getKey(level.getBlockState(ticket.support).getBlock())),
                        "partial restart restored leaf without original support at " + ticket.pos);
                }
                partialBrokenLeaf = tree.tickets.values().stream()
                    .filter(ticket -> ticket.outcome == SeasonalLedger.Outcome.RESTORED)
                    .map(ticket -> ticket.pos).findFirst().orElseThrow();
                partialTicketCount = tree.tickets.size();
                require(level.destroyBlock(partialBrokenLeaf, false),
                    "one-use proof could not break a restored native leaf");
                partialBreakTick = tick;
            } else if (partialBrokenLeaf != null && tick >= partialBreakTick + 100) {
                SeasonalLedger.Ticket broken = tree.tickets.get(partialBrokenLeaf.asLong());
                require(tree.tickets.size() == partialTicketCount && broken != null && broken.consumed &&
                        broken.outcome == SeasonalLedger.Outcome.RESTORED &&
                        SeasonalRuntime.inspectSnapshot(level, ROOT).pending() == 0,
                    "breaking a restored leaf reopened or duplicated a one-use ticket");
                require(server.saveEverything(false, true, true), "partial read world save failed");
                writeJson("success-read.json", receipt("read", tree, level));
                stopped = true;
                server.halt(false);
            }
        }
        require(tick <= READ_DEADLINE,
            "partial read timed out before complete winter shedding and one-use spring recovery");
    }

    private static SeasonalLedger.Ticket chooseDamageTicket(ServerLevel level, SeasonalLedger.Tree tree) {
        return tree.tickets.values().stream()
            .filter(ticket -> ticket.support.getY() > ROOT.getY() + 2 &&
                level.getBlockState(ticket.support).getBlock() instanceof BranchBlock &&
                tree.managedBranchesView().contains(ticket.support.asLong()))
            .max(Comparator.comparingInt(ticket -> ticket.support.getY()))
            .orElseThrow(() -> new IllegalStateException("No saved distal branch-supported ticket"));
    }

    private static void readEarlyBranchDamage(MinecraftServer server, ServerLevel level) throws IOException {
        require(earlyDamageStarted, "ServerStarted branch damage did not precede runtime ticks");
        if (tick == 20) {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            require(tree != null && tree.incarnation.toString().equals(
                baseline.get("rootIncarnation").getAsString()), "startup damage changed root incarnation");
            SeasonalLedger.Ticket dependent = tree.tickets.get(earlyDependent.asLong());
            require(dependent != null && !dependent.consumed &&
                    SeasonalLedger.get(level).isBranchTombstoned(tree, earlyBranch, dependent.targetCycle),
                "pre-tick branch tombstone did not survive transient recapture");
            require(SeasonClock.read(level) != null && SeasonClock.read(level).phase() >= 9.0,
                "startup damage read lost saved winter before spring");
            setPhase(level, 0.4);
        }
        if (tick > 20 && tick % 20 == 0) {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            require(tree != null && tree.incarnation.toString().equals(
                baseline.get("rootIncarnation").getAsString()), "damaged root provenance vanished");
            SeasonalLedger.Ticket dependent = tree.tickets.get(earlyDependent.asLong());
            require(dependent != null, "damaged dependent ticket vanished");
            if (tree.tickets.values().stream().allMatch(ticket -> ticket.consumed)) {
                require(dependent.outcome == SeasonalLedger.Outcome.UNSUPPORTED &&
                        level.getBlockState(earlyDependent).isAir(),
                    "pre-tick branch damage resurrected dependent foliage or missed UNSUPPORTED outcome");
                require(level.getBlockState(earlyBranch).getBlock() instanceof BranchBlock &&
                        stateKey(level.getBlockState(earlyBranch)).equals(
                            baseline.get("damageBranchState").getAsString()),
                    "same native branch did not survive startup damage proof");
                require(SeasonalLedger.get(level).isBranchTombstoned(tree, earlyBranch, dependent.targetCycle),
                    "startup branch tombstone disappeared before dependent resolution");
                require(server.saveEverything(false, true, true), "early damage read world save failed");
                writeJson("success-read.json", receipt("read", tree, level));
                stopped = true;
                server.halt(false);
            }
        }
        require(tick <= READ_DEADLINE,
            "early damage read timed out without dependent UNSUPPORTED resolution");
    }

    private static void readStage(MinecraftServer server, ServerLevel level) throws IOException {
        if (tick == 20) {
            loadSafetyRing(level);
            require(level.getBlockState(ROOT).getBlock() instanceof RootyBlock, "saved native root absent");
            require(leafPositions(level).isEmpty(), "saved winter tree has physical leaves before phase change");
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            require(tree != null, "saved seasonal ledger tree absent");
            require(Marker.get(level).id.toString().equals(baseline.get("worldMarker").getAsString()),
                "world marker mismatch: a fresh or different world was loaded");
            require(tree.incarnation.toString().equals(baseline.get("rootIncarnation").getAsString()),
                "root incarnation changed between processes");
            require(tree.species.equals(BIRCH), "saved root species changed");
            require(SeasonalLedger.get(level).cycle() == baseline.get("cycle").getAsLong(),
                "saved cycle changed before read stage phase change");
            require(ticketStates(tree).equals(baseline.getAsJsonArray("tickets")),
                "saved ticket positions, support, state, cycle, or deadline changed");
            require(branchStates(level).equals(baseline.getAsJsonArray("branches")),
                "saved wood network differs from write process");
            require(SeasonClock.read(level) != null && SeasonClock.read(level).phase() >= 9.0,
                "saved calendar is not winter before phase change");
            setPhase(level, 0.4);
        }
        if (tick > 20 && tick % 20 == 0) {
            SeasonalLedger.Tree tree = SeasonalLedger.get(level).find(ROOT);
            require(tree != null, "root provenance vanished during spring");
            int expected = baseline.getAsJsonArray("summerLeaves").size();
            if (tree.tickets.size() == expected && allConsumed(tree) &&
                leafPositions(level).equals(jsonLongs(baseline.getAsJsonArray("summerLeaves")))) {
                require(branchStates(level).equals(baseline.getAsJsonArray("branches")),
                    "restoration changed native wood network");
                for (SeasonalLedger.Ticket ticket : tree.tickets.values()) {
                    BlockState leaf = level.getBlockState(ticket.pos);
                    require(leaf.getBlock() == ticket.state.getBlock(),
                        "restored leaf has different native block at " + ticket.pos);
                    require(level.hasChunkAt(ticket.support) &&
                            ticket.supportBlock.equals(ForgeRegistries.BLOCKS.getKey(
                                level.getBlockState(ticket.support).getBlock())),
                        "restored leaf lost support at " + ticket.pos);
                }
                require(server.saveEverything(false, true, true), "read stage world save failed");
                writeJson("success-read.json", receipt("read", tree, level));
                stopped = true;
                server.halt(false);
            }
        }
        require(tick <= READ_DEADLINE, "read stage timed out without complete supported restoration");
    }

    private static JsonObject snapshot(ServerLevel level, SeasonalLedger.Tree tree) {
        JsonObject result = new JsonObject();
        result.addProperty("schema", "bst.persistence.v1");
        result.addProperty("attempt", attempt.getFileName().toString());
        result.addProperty("worldMarker", Marker.get(level).id.toString());
        result.addProperty("writePid", ProcessHandle.current().pid());
        result.addProperty("writeVmStart", ManagementFactory.getRuntimeMXBean().getStartTime());
        result.addProperty("root", ROOT.asLong());
        result.addProperty("rootIncarnation", tree.incarnation.toString());
        result.addProperty("species", tree.species.toString());
        result.addProperty("cycle", SeasonalLedger.get(level).cycle());
        result.add("summerLeaves", summer.getAsJsonArray("leaves"));
        result.add("branches", summer.getAsJsonArray("branches"));
        result.add("tickets", ticketStates(tree));
        return result;
    }

    private static JsonArray ticketStates(SeasonalLedger.Tree tree) {
        JsonArray result = new JsonArray();
        tree.tickets.values().stream().sorted(Comparator.comparingLong(t -> t.pos.asLong())).forEach(ticket -> {
            JsonObject item = new JsonObject();
            item.addProperty("pos", ticket.pos.asLong());
            item.addProperty("support", ticket.support.asLong());
            item.addProperty("state", stateKey(ticket.state));
            item.addProperty("supportBlock", ticket.supportBlock.toString());
            item.addProperty("targetCycle", ticket.targetCycle);
            item.addProperty("deadlineBits", Long.toHexString(Double.doubleToRawLongBits(ticket.deadline)));
            item.addProperty("consumed", ticket.consumed);
            result.add(item);
        });
        return result;
    }

    private static JsonArray ticketDetails(SeasonalLedger.Tree tree) {
        JsonArray result = new JsonArray();
        tree.tickets.values().stream().sorted(Comparator.comparingLong(t -> t.pos.asLong())).forEach(ticket -> {
            JsonObject item = new JsonObject();
            item.addProperty("pos", ticket.pos.asLong());
            item.addProperty("support", ticket.support.asLong());
            item.addProperty("state", stateKey(ticket.state));
            item.addProperty("supportBlock", ticket.supportBlock.toString());
            item.addProperty("targetCycle", ticket.targetCycle);
            item.addProperty("deadlineBits", Long.toHexString(Double.doubleToRawLongBits(ticket.deadline)));
            item.addProperty("consumed", ticket.consumed);
            item.addProperty("outcome", ticket.outcome.name());
            item.addProperty("suitabilityMask", ticket.suitabilityMask);
            result.add(item);
        });
        return result;
    }

    private static JsonObject plannedItem(SeasonalLedger.PlannedLeaf leaf) {
        JsonObject item = new JsonObject();
        item.addProperty("pos", leaf.pos().asLong());
        item.addProperty("support", leaf.support().asLong());
        item.addProperty("state", stateKey(leaf.state()));
        item.addProperty("supportState", stateKey(leaf.supportState()));
        item.addProperty("rank", leaf.rank());
        item.addProperty("count", leaf.count());
        item.addProperty("shedDeadlineBits", Long.toHexString(Double.doubleToRawLongBits(leaf.shedDeadline())));
        item.addProperty("restoreDeadlineBits", Long.toHexString(Double.doubleToRawLongBits(leaf.restoreDeadline())));
        return item;
    }

    private static JsonArray planStates(SeasonalLedger.Tree tree) {
        JsonArray result = new JsonArray();
        tree.plansView().values().stream().sorted(Comparator.comparingLong(leaf -> leaf.pos().asLong()))
            .forEach(leaf -> result.add(plannedItem(leaf)));
        return result;
    }

    private static JsonArray planPositions(SeasonalLedger.Tree tree) {
        return longs(tree.plansView().values().stream().map(leaf -> leaf.pos().asLong()).sorted().toList());
    }

    private static boolean planRanksValid(JsonArray plan, int expected) {
        if (plan.size() != expected) return false;
        Set<Integer> ranks = new HashSet<>();
        for (var value : plan) {
            JsonObject leaf = value.getAsJsonObject();
            int rank = leaf.get("rank").getAsInt();
            if (rank < 0 || rank >= expected || leaf.get("count").getAsInt() != expected || !ranks.add(rank))
                return false;
        }
        return ranks.size() == expected;
    }

    private static boolean partitionMatches(SeasonalLedger.Tree tree, JsonArray allPlan) {
        if (allPlan.size() != tree.planSize ||
                tree.plansView().size() + tree.tickets.size() != tree.planSize) return false;
        Set<Long> seen = new HashSet<>();
        for (var value : allPlan) {
            JsonObject original = value.getAsJsonObject();
            long position = original.get("pos").getAsLong();
            if (!seen.add(position)) return false;
            SeasonalLedger.PlannedLeaf unshed = tree.plansView().get(position);
            SeasonalLedger.Ticket shed = tree.tickets.get(position);
            if ((unshed == null) == (shed == null)) return false;
            if (unshed != null && !plannedItem(unshed).equals(original)) return false;
            if (shed != null && (shed.support.asLong() != original.get("support").getAsLong() ||
                    shed.targetCycle != tree.planCycle + 1 ||
                    !shed.supportBlock.toString().equals(
                        original.get("supportState").getAsString().split(";", 2)[0]) ||
                    !stateKey(shed.state).equals(original.get("state").getAsString()) ||
                    !Long.toHexString(Double.doubleToRawLongBits(shed.deadline))
                        .equals(original.get("restoreDeadlineBits").getAsString()))) return false;
        }
        return seen.size() == tree.planSize;
    }

    private static JsonArray ticketPositions(SeasonalLedger.Tree tree) {
        List<Long> positions = tree.tickets.values().stream().map(t -> t.pos.asLong()).sorted().toList();
        return longs(positions);
    }

    private static boolean allConsumed(SeasonalLedger.Tree tree) {
        return tree.tickets.values().stream().allMatch(t -> t.consumed);
    }

    private static void loadSafetyRing(ServerLevel level) {
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.getChunk(x, z);
    }

    private static void generateBirch(ServerLevel level) {
        for (BlockPos p : BlockPos.betweenClosed(ROOT.offset(-10, 0, -10), ROOT.offset(10, 0, 10)))
            level.setBlock(p, Blocks.DIRT.defaultBlockState(), 2);
        require(level.canSeeSky(ROOT.above()), "fixture root at y180 lacks clear sky");
        Species species = Species.REGISTRY.get(BIRCH);
        require(species != null && species.isValid(), "native birch species missing");
        var codes = JoCodeRegistry.getCodes(BIRCH);
        require(!codes.isEmpty(), "native birch JoCode registry empty");
        int radius = codes.keySet().stream().min(Comparator.comparingInt(value -> Math.abs(value - 5))).orElseThrow();
        JoCode nativeCode = codes.get(radius).get(0);
        new JoCode(nativeCode.toString()).setCareful(true).generate(new GenerationContext(
            LevelContext.create(level), species, ROOT, ROOT.mutable(), level.getBiome(ROOT), Direction.NORTH,
            radius, SafeChunkBounds.ANY));
        require(level.getBlockState(ROOT).getBlock() instanceof RootyBlock, "native JoCode did not create birch root");
    }

    private static void setPhase(ServerLevel level, double phase) {
        var data = SeasonHandler.getSeasonSavedData(level);
        int subSeasonDuration = SeasonHelper.getSeasonState(level).getSubSeasonDuration();
        data.seasonCycleTicks = (int) (phase * subSeasonDuration);
        data.setDirty();
        SeasonHandler.sendSeasonUpdate(level);
    }

    private static List<Long> leafPositions(ServerLevel level) {
        List<Long> positions = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(ROOT.offset(-10, 0, -10), ROOT.offset(10, 30, 10))) {
            if (level.getBlockState(p).getBlock() instanceof DynamicLeavesBlock) positions.add(p.asLong());
        }
        positions.sort(Long::compare);
        return positions;
    }

    private static JsonArray branchStates(ServerLevel level) {
        List<BlockPos> branches = new ArrayList<>();
        for (BlockPos p : BlockPos.betweenClosed(ROOT.offset(-10, 0, -10), ROOT.offset(10, 30, 10))) {
            if (level.getBlockState(p).getBlock() instanceof BranchBlock) branches.add(p.immutable());
        }
        branches.sort(Comparator.comparingLong(BlockPos::asLong));
        JsonArray result = new JsonArray();
        for (BlockPos p : branches) {
            JsonObject item = new JsonObject();
            item.addProperty("pos", p.asLong());
            item.addProperty("state", stateKey(level.getBlockState(p)));
            result.add(item);
        }
        return result;
    }

    private static String stateKey(BlockState state) {
        ResourceLocation id = ForgeRegistries.BLOCKS.getKey(state.getBlock());
        require(id != null, "unregistered block state in fixture");
        StringBuilder key = new StringBuilder(id.toString());
        state.getProperties().stream().sorted(Comparator.comparing(Property::getName)).forEach(property ->
            key.append(';').append(property.getName()).append('=').append(state.getValue(property)));
        return key.toString();
    }

    private static JsonArray longs(List<Long> values) {
        JsonArray result = new JsonArray();
        for (long value : values) result.add(value);
        return result;
    }

    private static List<Long> jsonLongs(JsonArray values) {
        List<Long> result = new ArrayList<>();
        values.forEach(value -> result.add(value.getAsLong()));
        result.sort(Long::compare);
        return result;
    }

    private static JsonObject receipt(String name, SeasonalLedger.Tree tree, ServerLevel level) {
        JsonObject result = new JsonObject();
        result.addProperty("schema", "bst.persistence.receipt.v1");
        result.addProperty("status", "PASS");
        result.addProperty("stage", name);
        if (!mode.equals("winter")) result.addProperty("mode", mode);
        result.addProperty("pid", ProcessHandle.current().pid());
        result.addProperty("vmStart", ManagementFactory.getRuntimeMXBean().getStartTime());
        result.addProperty("attempt", attempt.getFileName().toString());
        result.addProperty("worldMarker", Marker.get(level).id.toString());
        result.addProperty("rootIncarnation", tree.incarnation.toString());
        result.addProperty("tickets", tree.tickets.size());
        result.addProperty("consumed", tree.tickets.values().stream().filter(t -> t.consumed).count());
        result.addProperty("cycle", SeasonalLedger.get(level).cycle());
        result.addProperty("serverTick", level.getGameTime());
        return result;
    }

    private static void writeJson(String filename, JsonObject value) throws IOException {
        Path target = attempt.resolve(filename);
        require(!Files.exists(target), "refusing to overwrite " + filename);
        Path partial = attempt.resolve(filename + ".partial");
        Files.writeString(partial, value.toString() + "\n", StandardCharsets.UTF_8,
            StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE);
    }

    private static void fail(Exception problem) {
        stopped = true;
        JsonObject failure = new JsonObject();
        failure.addProperty("schema", "bst.persistence.receipt.v1");
        failure.addProperty("status", "FAIL");
        failure.addProperty("stage", stage == null ? "uninitialized" : stage);
        failure.addProperty("tick", tick);
        failure.addProperty("reason", problem.toString());
        if (attempt != null && attempt.getParent() != null &&
            attempt.getParent().equals(EXPECTED_PARENT)) {
            try { writeJson("failure-" + stage + ".json", failure); }
            catch (Exception writeFailure) { problem.addSuppressed(writeFailure); }
        }
        throw new IllegalStateException("Seasonal persistence fixture failed: " + problem, problem);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }

    private static final class Marker extends SavedData {
        private static final String ID = "bst_persistence_fixture_world_marker";
        final UUID id;
        Marker() { this(UUID.randomUUID()); setDirty(); }
        private Marker(UUID id) { this.id = id; }
        static Marker get(ServerLevel level) {
            return level.getDataStorage().computeIfAbsent(Marker::load, Marker::new, ID);
        }
        static Marker load(CompoundTag tag) {
            return tag.hasUUID("id") ? new Marker(tag.getUUID("id")) : new Marker();
        }
        @Override public CompoundTag save(CompoundTag tag) {
            tag.putUUID("id", id);
            return tag;
        }
    }
}
