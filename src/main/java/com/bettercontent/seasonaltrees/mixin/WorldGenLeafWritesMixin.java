package com.bettercontent.seasonaltrees.mixin;

import com.bettercontent.seasonaltrees.runtime.GeneratedTreeWrites;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Record only actual blocks written in a JoCode generation scope, not overlapping older trees. */
@Mixin(WorldGenRegion.class)
public abstract class WorldGenLeafWritesMixin {
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("HEAD"))
    private void seasonal$before(BlockPos pos, BlockState state, int flags, int recursion,
                                 CallbackInfoReturnable<Boolean> cir) {
        WorldGenRegion region = (WorldGenRegion) (Object) this;
        // Failed out-of-range calls must not occupy a capture slot. This predicate is also used
        // by native setBlock, whose RETURN is the only path that records an actual write.
        if (GeneratedTreeWrites.active(region)
                && pos.getY() >= region.getMinBuildHeight() && pos.getY() < region.getMaxBuildHeight()
                && region.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)
                && region.ensureCanWrite(pos))
            GeneratedTreeWrites.beforeWrite(region, pos, region.getBlockState(pos));
    }
    @Inject(method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z", at = @At("RETURN"))
    private void seasonal$written(BlockPos pos, BlockState state, int flags, int recursion,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValue()) GeneratedTreeWrites.wrote((WorldGenRegion) (Object) this, pos, state, true);
    }
}
