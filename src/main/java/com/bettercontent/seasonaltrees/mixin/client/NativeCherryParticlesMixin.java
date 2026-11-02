package com.bettercontent.seasonaltrees.mixin.client;

import com.bettercontent.seasonaltrees.client.ClientCalendar;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.util.ParticleUtils;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/** Leave vanilla/static leaves alone; managed DT cherry uses the bounded generated seasonal petals. */
@Mixin(targets = "com.ferreusveritas.dynamictrees.block.leaves.CherryLeavesProperties$1", remap = false)
public abstract class NativeCherryParticlesMixin {
    // DT's anonymous subclass overrides a Minecraft method. Its pinned 1.20.1 names differ
    // between userdev and a production SRG jar; the invoked Minecraft utility is remapped normally.
    @Redirect(method = {
            "animateTick(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V",
            "m_214162_(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;)V"
    }, at = @At(value = "INVOKE", target = "Lnet/minecraft/util/ParticleUtils;spawnParticleBelow(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Lnet/minecraft/util/RandomSource;Lnet/minecraft/core/particles/ParticleOptions;)V", remap = true), require = 1)
    private void seasonalTrees$managedPetals(Level level, BlockPos pos, RandomSource random,
                                             ParticleOptions particle) {
        if (!ClientCalendar.suppressNativeCherryParticles(pos))
            ParticleUtils.spawnParticleBelow(level, pos, random, particle);
    }
}
