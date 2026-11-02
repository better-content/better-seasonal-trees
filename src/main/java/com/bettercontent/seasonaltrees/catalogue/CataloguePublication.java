package com.bettercontent.seasonaltrees.catalogue;

import com.google.gson.JsonElement;
import net.minecraft.resources.ResourceLocation;

import java.util.Map;
import java.util.function.Predicate;

/** Package-private publication boundary, independent of Forge and the DT registry singleton. */
final class CataloguePublication {
    private volatile Snapshot current = new Snapshot(Map.of(), 0);

    private record Snapshot(Map<ResourceLocation, SpeciesProfile> profiles, long revision) { }

    SpeciesProfile profile(ResourceLocation id) {
        return current.profiles().get(id);
    }

    Map<ResourceLocation, SpeciesProfile> all() {
        return current.profiles();
    }

    long revision() {
        return current.revision();
    }

    synchronized CatalogueParser.Result publish(Map<ResourceLocation, JsonElement> resources,
                                                 Predicate<String> installedMod,
                                                 Predicate<ResourceLocation> registeredSpecies) {
        CatalogueParser.Result candidate = CatalogueParser.parse(resources, installedMod);
        for (ResourceLocation id : candidate.profiles().keySet()) {
            if (!registeredSpecies.test(id)) {
                throw new IllegalArgumentException("Unknown live Dynamic Trees species: " + id);
            }
        }
        current = new Snapshot(candidate.profiles(), current.revision() + 1);
        return candidate;
    }
}
