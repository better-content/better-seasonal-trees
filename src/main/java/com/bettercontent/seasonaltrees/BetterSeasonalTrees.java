package com.bettercontent.seasonaltrees;

import com.bettercontent.seasonaltrees.catalogue.SeasonalCatalogue;
import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.AddReloadListenerEvent;
import net.minecraftforge.fml.common.Mod;

@Mod(BetterSeasonalTrees.MOD_ID)
public final class BetterSeasonalTrees {
    public static final String MOD_ID = "better_seasonal_trees";
    public BetterSeasonalTrees() {
        MinecraftForge.EVENT_BUS.addListener(this::reloadListeners);
        com.bettercontent.seasonaltrees.network.SeasonalNetwork.register();
        SeasonalRuntime.register();
    }
    private void reloadListeners(AddReloadListenerEvent event) {
        event.addListener(SeasonalCatalogue.INSTANCE);
    }
}
