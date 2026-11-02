package com.bettercontent.seasonaltrees.mixin;

import com.bettercontent.seasonaltrees.runtime.GeneratedTreeWrites;
import com.ferreusveritas.dynamictrees.worldgen.GenerationContext;
import com.ferreusveritas.dynamictrees.worldgen.JoCode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** DT posts SpeciesPostGenerationEvent inside this scope after leaf aging and post-generation features. */
@Mixin(JoCode.class)
public abstract class JoCodeBirthScopeMixin {
    @Inject(method = "generate", at = @At("HEAD"), remap = false)
    private void seasonal$begin(GenerationContext context, CallbackInfo ci) {
        GeneratedTreeWrites.begin(context);
    }
    @Inject(method = "generate", at = @At("RETURN"), remap = false)
    private void seasonal$end(GenerationContext context, CallbackInfo ci) {
        GeneratedTreeWrites.end();
    }
}
