package com.bettercontent.seasonaltrees.mixin;

import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.block.leaves.LeavesProperties;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Covers DT's boxed overload; the primitive overload forwards to it. */
@Mixin(value = DynamicLeavesBlock.class, remap = false)
public abstract class LeavesDormancyMixin {
    @Inject(method = "growLeavesIfLocationIsSuitable(Lnet/minecraft/world/level/LevelAccessor;Lcom/ferreusveritas/dynamictrees/block/leaves/LeavesProperties;Lnet/minecraft/core/BlockPos;Ljava/lang/Integer;)Z",
            at = @At("HEAD"), cancellable = true)
    private void betterSeasonalTrees$noSeasonalLeafGrowth(LevelAccessor level, LeavesProperties properties,
                                                           BlockPos pos, Integer hydration,
                                                           CallbackInfoReturnable<Boolean> cir) {
        if (SeasonalRuntime.suppressLeafGrowth(level, pos)) cir.setReturnValue(false);
    }

    @Inject(method = "growLeavesIfLocationIsSuitable(Lnet/minecraft/world/level/LevelAccessor;Lcom/ferreusveritas/dynamictrees/block/leaves/LeavesProperties;Lnet/minecraft/core/BlockPos;Ljava/lang/Integer;)Z",
            at = @At("RETURN"))
    private void betterSeasonalTrees$refreshManagedVisuals(LevelAccessor level, LeavesProperties properties,
                                                            BlockPos pos, Integer hydration,
                                                            CallbackInfoReturnable<Boolean> cir) {
        if (Boolean.TRUE.equals(cir.getReturnValue())) SeasonalRuntime.onNativeLeafPlaced(level, pos);
    }
}
