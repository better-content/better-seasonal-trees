package com.bettercontent.seasonaltrees.catalogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class CataloguePublicationTest {
    private static final ResourceLocation BIRCH = new ResourceLocation("dynamictrees:birch");
    private static final ResourceLocation CHERRY = new ResourceLocation("dynamictrees:cherry");

    @Test
    void rejectedReloadKeepsPreviousProfilesAndRevision() {
        var publication = new CataloguePublication();
        publication.publish(Map.of(file("birch"), profile(BIRCH)), mod -> true, id -> true);
        Map<ResourceLocation, SpeciesProfile> before = publication.all();
        long revision = publication.revision();

        Map<ResourceLocation, JsonElement> invalid = new LinkedHashMap<>();
        invalid.put(file("birch"), profile(BIRCH));
        var badCherry = profile(CHERRY);
        badCherry.getAsJsonObject().addProperty("shedEnd", 13);
        invalid.put(file("cherry"), badCherry);
        assertThrows(IllegalArgumentException.class,
            () -> publication.publish(invalid, mod -> true, id -> true));
        assertSame(before, publication.all());
        assertEquals(revision, publication.revision());
        assertNull(publication.profile(CHERRY));

        invalid.put(file("cherry"), profile(CHERRY));
        assertThrows(IllegalArgumentException.class,
            () -> publication.publish(invalid, mod -> true, id -> !id.equals(CHERRY)));
        assertSame(before, publication.all());
        assertEquals(revision, publication.revision());

        publication.publish(invalid, mod -> true, id -> true);
        assertEquals(revision + 1, publication.revision());
        assertEquals(2, publication.all().size());
        assertNotSame(before, publication.all());
        assertThrows(UnsupportedOperationException.class, () -> publication.all().clear());
    }

    @Test
    void removingProfilePublishesOneNewSnapshot() {
        var publication = new CataloguePublication();
        publication.publish(Map.of(file("birch"), profile(BIRCH)), mod -> true, id -> true);
        publication.publish(Map.of(), mod -> true, id -> true);
        assertEquals(2, publication.revision());
        assertTrue(publication.all().isEmpty());
        assertNull(publication.profile(BIRCH));
    }

    private static ResourceLocation file(String name) {
        return new ResourceLocation("better_seasonal_trees", name);
    }

    private static JsonElement profile(ResourceLocation id) {
        return JsonParser.parseString("{\"speciesId\":\"" + id + "\",\"habit\":\"TEMPERATE\","
            + "\"leafOutStart\":0,\"leafOutDuration\":0.3333333333333333,"
            + "\"shedStart\":8,\"shedEnd\":9,\"bloomStart\":0,\"bloomEnd\":0,"
            + "\"autumnColor\":\"#FFAA00\",\"bloomVisual\":\"none\"}");
    }
}
