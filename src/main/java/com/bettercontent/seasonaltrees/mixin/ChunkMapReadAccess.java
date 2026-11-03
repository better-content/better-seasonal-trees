package com.bettercontent.seasonaltrees.mixin;

import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ChunkMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Read an existing holder without adding tickets, scheduling or waiting. */
@Mixin(ChunkMap.class)
public interface ChunkMapReadAccess {
    @Invoker("getVisibleChunkIfPresent")
    ChunkHolder betterSeasonalTrees$visibleHolder(long position);
}
