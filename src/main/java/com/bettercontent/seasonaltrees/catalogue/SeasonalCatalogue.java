package com.bettercontent.seasonaltrees.catalogue;

import com.ferreusveritas.dynamictrees.tree.species.Species;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.logging.LogUtils;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraftforge.fml.ModList;
import org.slf4j.Logger;

import java.util.Map;

/** Active catalogue; publication is a single volatile snapshot swap after full validation. */
public final class SeasonalCatalogue extends SimpleJsonResourceReloadListener {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final CataloguePublication publication = new CataloguePublication();
    public static final SeasonalCatalogue INSTANCE = new SeasonalCatalogue();

    private SeasonalCatalogue() {
        super(new Gson(), "seasonal_trees");
    }

    public static SpeciesProfile profile(ResourceLocation id) {
        return publication.profile(id);
    }

    public static Map<ResourceLocation, SpeciesProfile> all() {
        return publication.all();
    }

    public static long revision() {
        return publication.revision();
    }

    @Override
    protected synchronized void apply(Map<ResourceLocation, JsonElement> resources,
                                      ResourceManager manager, ProfilerFiller profiler) {
        try {
            CatalogueParser.Result next = publication.publish(resources,
                mod -> ModList.get().isLoaded(mod), Species.REGISTRY::has);
            for (var skip : next.skipped().entrySet()) {
                LOGGER.warn("Skipping seasonal profile {}: {}", skip.getKey(), skip.getValue());
            }
            LOGGER.info("Loaded {} seasonal tree profiles, revision {}", next.profiles().size(), revision());
        } catch (RuntimeException ex) {
            LOGGER.error("Seasonal tree catalogue reload rejected; retaining revision {}", revision(), ex);
            throw ex;
        }
    }
}
