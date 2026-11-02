package com.bettercontent.seasonaltrees.mixin;

import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Prevents ordinary growth and the leafless-root decay path only while a trunk still exists. */
@Mixin(value = RootyBlock.class, remap = false)
public abstract class RootyDormancyMixin {
    @Inject(method = "updateTree", at = @At("HEAD"), cancellable = true)
    private void betterSeasonalTrees$dormantRoot(BlockState state, Level level, BlockPos root,
                                                RandomSource random, boolean natural,
                                                CallbackInfo ci) {
        if (level instanceof ServerLevel server && SeasonalRuntime.dormantRoot(server, root, state, (RootyBlock)(Object)this))
            ci.cancel();
    }

    /** DT may place the trunk during updateTree after the HEAD admission check; enroll the finished tree. */
    @Inject(method = "updateTree", at = @At("RETURN"))
    private void betterSeasonalTrees$admitGeneratedRoot(BlockState state, Level level, BlockPos root,
                                                         RandomSource random, boolean natural,
                                                         CallbackInfo ci) {
        if (level instanceof ServerLevel server) SeasonalRuntime.ensureTracked(server, root);
    }
}
