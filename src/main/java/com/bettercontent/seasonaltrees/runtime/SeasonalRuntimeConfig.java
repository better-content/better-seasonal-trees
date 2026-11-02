package com.bettercontent.seasonaltrees.runtime;

import net.minecraftforge.common.ForgeConfigSpec;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.config.ModConfig;

/** Server-wide work ceilings. Defaults are the native-qualified Gate A limits. */
public final class SeasonalRuntimeConfig {
    public static final int DEFAULT_MUTATIONS = 64;
    public static final int DEFAULT_INSPECTIONS = 2048;
    private static final ForgeConfigSpec SPEC;
    private static final ForgeConfigSpec.IntValue MUTATIONS;
    private static final ForgeConfigSpec.IntValue INSPECTIONS;
    private static boolean registered;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();
        builder.comment("Global seasonal work per server tick, shared across dimensions and roots").push("work");
        MUTATIONS = builder.comment("Maximum seasonal block mutation attempts per server tick")
                .defineInRange("maxMutations", DEFAULT_MUTATIONS, 1, 4096);
        INSPECTIONS = builder.comment("Maximum candidate and topology inspections per server tick")
                .defineInRange("maxInspections", DEFAULT_INSPECTIONS, 64, 131072);
        builder.pop();
        SPEC = builder.build();
    }

    private SeasonalRuntimeConfig() {}

    public static synchronized void register() {
        if (!registered) {
            ModLoadingContext.get().registerConfig(ModConfig.Type.SERVER, SPEC);
            registered = true;
        }
    }

    public static int maxMutations() { return MUTATIONS.get(); }
    public static int maxInspections() { return INSPECTIONS.get(); }
}
