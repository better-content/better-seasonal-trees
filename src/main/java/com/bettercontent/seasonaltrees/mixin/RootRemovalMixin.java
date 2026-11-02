package com.bettercontent.seasonaltrees.mixin;

import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import com.ferreusveritas.dynamictrees.block.rooty.RootyBlock;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Invalidates root provenance or branch support on actual block replacement. */
@Mixin(BlockBehaviour.class)
public abstract class RootRemovalMixin {
    @Inject(method = "onRemove", at = @At("HEAD"))
    private void betterSeasonalTrees$rootRemoved(BlockState state, Level level, BlockPos pos,
                                                  BlockState replacement, boolean moving,
                                                  CallbackInfo ci) {
        if (!(level instanceof ServerLevel server) || state.getBlock() == replacement.getBlock()) return;
        if (state.getBlock() instanceof RootyBlock) SeasonalRuntime.onRootReplaced(server, pos);
        else if (state.getBlock() instanceof BranchBlock) SeasonalRuntime.onBranchReplaced(server, pos);
    }
}
