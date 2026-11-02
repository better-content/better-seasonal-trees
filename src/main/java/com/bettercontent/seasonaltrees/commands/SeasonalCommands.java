package com.bettercontent.seasonaltrees.commands;

import com.bettercontent.seasonaltrees.BetterSeasonalTrees;
import com.bettercontent.seasonaltrees.catalogue.SeasonalCatalogue;
import com.bettercontent.seasonaltrees.catalogue.SpeciesProfile;
import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Operator-only, single-position diagnostics; never scans or forces world chunks. */
@Mod.EventBusSubscriber(modid = BetterSeasonalTrees.MOD_ID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class SeasonalCommands {
    private SeasonalCommands() { }

    @SubscribeEvent
    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("seasonaltrees")
            .requires(source -> source.hasPermission(2))
            .then(Commands.literal("validate").executes(SeasonalCommands::validate))
            .then(Commands.literal("inspect")
                .then(Commands.argument("root", BlockPosArgument.blockPos())
                    .executes(SeasonalCommands::inspect)))
            .then(Commands.literal("reconcile")
                .then(Commands.argument("root", BlockPosArgument.blockPos())
                    .executes(SeasonalCommands::reconcile))));
    }

    private static int validate(CommandContext<CommandSourceStack> context) {
        var profiles = SeasonalCatalogue.all();
        List<String> failures = new ArrayList<>();
        if (profiles.isEmpty()) failures.add("active catalogue is empty");
        // Iterate only the active immutable catalogue snapshot, never the full DT registry.
        for (var entry : profiles.entrySet().stream()
                .sorted(Comparator.comparing(item -> item.getKey().toString())).toList()) {
            ResourceLocation id = entry.getKey();
            SpeciesProfile profile = entry.getValue();
            if (profile == null || !id.equals(profile.speciesId())) {
                failures.add(id + ": profile ID differs from catalogue key");
                continue;
            }
            if (!Species.REGISTRY.has(id)) {
                failures.add(id + ": exact Dynamic Trees species ID is absent");
                continue;
            }
            Species species = Species.REGISTRY.get(id);
            if (species == null || !id.equals(species.getRegistryName())) {
                failures.add(id + ": Dynamic Trees registry returned a different species");
                continue;
            }
            var leaves = species.getLeavesBlock().orElse(null);
            ResourceLocation leafId = leaves == null ? null : ForgeRegistries.BLOCKS.getKey(leaves);
            if (leafId == null || !ForgeRegistries.BLOCKS.containsKey(leafId)
                    || ForgeRegistries.BLOCKS.getValue(leafId) != leaves)
                failures.add(id + ": native leaves reference is absent or unregistered");
        }
        if (!failures.isEmpty()) {
            context.getSource().sendFailure(Component.literal("Seasonal catalogue validation failed: "
                + failures.size() + " issue(s), revision " + SeasonalCatalogue.revision()));
            for (String failure : failures) context.getSource().sendFailure(Component.literal(failure));
            return 0;
        }
        context.getSource().sendSuccess(() -> Component.literal("Seasonal catalogue valid: "
            + profiles.size() + " exact Dynamic Trees species/leaves, revision "
            + SeasonalCatalogue.revision()), false);
        return profiles.size();
    }

    private static int inspect(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos root = BlockPosArgument.getBlockPos(context, "root");
        if (!loadedRoot(source, level, root)) return 0;
        var snapshot = SeasonalRuntime.inspectSnapshot(level, root);
        source.sendSuccess(() -> Component.literal("Seasonal root " + root + ": " + snapshot), false);
        return 1;
    }

    private static int reconcile(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        BlockPos root = BlockPosArgument.getBlockPos(context, "root");
        if (!loadedRoot(source, level, root)) return 0;
        // Exactly one bounded enrollment request. The scheduler performs later work.
        if (!SeasonalRuntime.ensureTracked(level, root)) {
            source.sendFailure(Component.literal("Seasonal root not eligible for reconciliation at " + root));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Seasonal reconciliation queued for " + root), false);
        return 1;
    }

    private static boolean loadedRoot(CommandSourceStack source, ServerLevel level, BlockPos root) {
        if (!level.hasChunkAt(root)) {
            source.sendFailure(Component.literal("Root chunk is not loaded at " + root));
            return false;
        }
        if (!(level.getBlockState(root).getBlock() instanceof RootyBlock)) {
            source.sendFailure(Component.literal("No native Dynamic Trees root at " + root));
            return false;
        }
        return true;
    }
}
