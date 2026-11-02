package com.bettercontent.seasonaltrees.mixin;

import com.bettercontent.seasonaltrees.runtime.GenerationCanopyData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Carry Forge chunk-data generation markers across in-memory ProtoChunk -> LevelChunk promotion. */
@Mixin(LevelChunk.class)
public abstract class LevelChunkBirthPromotionMixin {
    @Inject(method = "<init>(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/level/chunk/ProtoChunk;Lnet/minecraft/world/level/chunk/LevelChunk$PostLoadProcessor;)V", at = @At("RETURN"))
    private void seasonal$promote(ServerLevel level, ProtoChunk source,
                                  LevelChunk.PostLoadProcessor processor, CallbackInfo ci) {
        GenerationCanopyData.transfer(source, (LevelChunk) (Object) this);
    }
}
