package com.bettercontent.seasonaltrees.catalogue;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class CatalogueParserTest {
    private static final String PATH = "/data/better_seasonal_trees/seasonal_trees/";

    @Test
    void pilotProfilesHavePinnedTimingAndUnchangedControl() {
        Map<ResourceLocation, JsonElement> json = new LinkedHashMap<>();
        for (String name : new String[] {"birch", "cherry", "spruce"}) {
            try (var stream = getClass().getResourceAsStream(PATH + name + ".json")) {
                assertNotNull(stream, name + " resource must be packaged");
                json.put(new ResourceLocation("better_seasonal_trees", name),
                    JsonParser.parseReader(new InputStreamReader(stream, StandardCharsets.UTF_8)));
            } catch (java.io.IOException ex) {
                throw new AssertionError(ex);
            }
        }
        var result = CatalogueParser.parse(json, mod -> mod.equals("dynamictrees"));
        assertEquals(3, result.profiles().size());
        assertTrue(result.skipped().isEmpty());
        var birch = result.profiles().get(new ResourceLocation("dynamictrees:birch"));
        var cherry = result.profiles().get(new ResourceLocation("dynamictrees:cherry"));
        var spruce = result.profiles().get(new ResourceLocation("dynamictrees:spruce"));
        assertEquals(SpeciesProfile.Habit.TEMPERATE, birch.habit());
        assertEquals(0.0, birch.leafOutStart());
        assertEquals(1.0 / 3.0, birch.leafOutDuration());
        assertEquals(1.0 / 3.0, cherry.leafOutStart());
        assertEquals(2.0 / 3.0, cherry.leafOutStart() + cherry.leafOutDuration());
        assertEquals(8.0, birch.shedStart());
        assertEquals(9.0, cherry.shedEnd());
        assertEquals("cherry", cherry.bloomVisual());
        assertEquals(SpeciesProfile.Habit.UNCHANGED, spruce.habit());
        assertEquals(0.0, spruce.leafOutDuration());
        assertThrows(UnsupportedOperationException.class,
            () -> result.profiles().clear());
    }

    @Test
    void activeRosterHasValidatedOptionalCoverageAndTropicalWindows() throws Exception {
        Path root = Path.of("src/main/resources/data/better_seasonal_trees/seasonal_trees");
        Map<ResourceLocation, JsonElement> json = new LinkedHashMap<>();
        try (var files = Files.list(root)) {
            for (Path path : files.filter(name -> name.toString().endsWith(".json")).sorted().toList()) {
                try (var reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                    String name = path.getFileName().toString().replaceFirst("\\.json$", "");
                    json.put(file(name), JsonParser.parseReader(reader));
                }
            }
        }
        var complete = CatalogueParser.parse(json, mod -> true);
        assertEquals(48, complete.profiles().size());
        assertTrue(complete.skipped().isEmpty());
        assertEquals(31, complete.profiles().values().stream()
            .filter(profile -> profile.habit() == SpeciesProfile.Habit.TEMPERATE).count());
        assertEquals(4, complete.profiles().values().stream()
            .filter(profile -> profile.habit() == SpeciesProfile.Habit.TROPICAL).count());
        assertEquals(8, complete.profiles().values().stream()
            .filter(profile -> profile.habit() == SpeciesProfile.Habit.EVERGREEN).count());
        assertEquals(5, complete.profiles().values().stream()
            .filter(profile -> profile.habit() == SpeciesProfile.Habit.UNCHANGED).count());

        var paloVerde = complete.profiles().get(new ResourceLocation("dtnatures_spirit:palo_verde"));
        assertEquals(0.0, paloVerde.leafOutStart());
        assertEquals(1.0 / 3.0, paloVerde.leafOutDuration());
        assertEquals(6.0, paloVerde.shedStart());
        assertEquals(7.0, paloVerde.shedEnd());
        assertEquals("palo_verde", paloVerde.bloomVisual());
        for (String id : new String[] {"dtnatures_spirit:mahogany", "better_hexerei_dynamic_trees:mahogany"}) {
            var mahogany = complete.profiles().get(new ResourceLocation(id));
            assertEquals(11.5, mahogany.shedStart());
            assertEquals(12.0, mahogany.shedEnd());
            assertEquals("none", mahogany.bloomVisual());
        }

        var coreOnly = CatalogueParser.parse(json, mod -> mod.equals("dynamictrees"));
        assertEquals(11, coreOnly.profiles().size());
        assertEquals(37, coreOnly.skipped().size());
        assertTrue(coreOnly.profiles().keySet().stream()
            .allMatch(id -> id.getNamespace().equals("dynamictrees")));
    }

    @Test
    void optionalMissingModSkipsWithoutActivatingSpecies() {
        var json = profile("dtnatures_spirit:aspen");
        json.getAsJsonObject().add("optionalMods", JsonParser.parseString("[\"dtnatures_spirit\"]"));
        var result = CatalogueParser.parse(Map.of(file("aspen"), json), mod -> false);
        assertTrue(result.profiles().isEmpty());
        assertEquals("optional mod absent: dtnatures_spirit", result.skipped().get(file("aspen")));
    }

    @Test
    void requiredMissingModRejectsWholeBatch() {
        var valid = profile("dynamictrees:birch");
        var invalid = profile("addon:aspen");
        invalid.getAsJsonObject().add("requiredMods", JsonParser.parseString("[\"addon\"]"));
        var batch = Map.of(file("birch"), valid, file("aspen"), invalid);
        assertThrows(IllegalArgumentException.class,
            () -> CatalogueParser.parse(batch, mod -> mod.equals("dynamictrees")));
    }

    @Test
    void absentOptionalModDoesNotMaskMalformedProfile() {
        var json = profile("dtnatures_spirit:aspen");
        json.getAsJsonObject().add("optionalMods", JsonParser.parseString("[\"dtnatures_spirit\"]"));
        json.getAsJsonObject().addProperty("shedEnd", 13);
        assertThrows(IllegalArgumentException.class,
            () -> CatalogueParser.parse(Map.of(file("aspen"), json), mod -> false));
    }

    @Test
    void malformedSchemaAndIntervalsRejectEntireBatch() {
        for (String change : Set.of("unknown", "duration", "nan", "color", "bloom")) {
            var json = profile("dynamictrees:birch");
            switch (change) {
                case "unknown" -> json.getAsJsonObject().addProperty("madeUp", true);
                case "duration" -> json.getAsJsonObject().addProperty("leafOutDuration", -1);
                case "nan" -> json.getAsJsonObject().addProperty("shedStart", "NaN");
                case "color" -> json.getAsJsonObject().addProperty("autumnColor", "orange");
                case "bloom" -> json.getAsJsonObject().addProperty("bloomVisual", "cherry");
                default -> throw new AssertionError(change);
            }
            assertThrows(IllegalArgumentException.class,
                () -> CatalogueParser.parse(Map.of(file(change), json), mod -> true), change);
        }
    }

    @Test
    void duplicateSpeciesIdCannotBeSilentlyOverridden() {
        var json = profile("dynamictrees:birch");
        assertThrows(IllegalArgumentException.class,
            () -> CatalogueParser.parse(Map.of(file("a"), json, file("b"), json), mod -> true));
    }

    @Test
    void missingNamespaceModAndMalformedModListsAreRejected() {
        var missingNamespace = profile("dtquark:fiery_blossom");
        assertThrows(IllegalArgumentException.class,
            () -> CatalogueParser.parse(Map.of(file("quark"), missingNamespace),
                mod -> mod.equals("dynamictrees")));
        for (String mods : new String[] {"[\"dynamictrees\",\"dynamictrees\"]", "[42]", "\"dynamictrees\""}) {
            var malformed = profile("dynamictrees:birch");
            malformed.getAsJsonObject().add("requiredMods", JsonParser.parseString(mods));
            assertThrows(IllegalArgumentException.class,
                () -> CatalogueParser.parse(Map.of(file("birch"), malformed), mod -> true));
        }
    }

    @Test
    void invalidSpeciesIdentifiersAndHabitPolicyAreRejected() {
        for (String id : new String[] {"birch", "dynamictrees:Bad_Name", "dynamictrees:"}) {
            assertThrows(IllegalArgumentException.class,
                () -> CatalogueParser.parse(Map.of(file("bad"), profile(id)), mod -> true), id);
        }
        var unchangedWithShedding = profile("dynamictrees:spruce");
        unchangedWithShedding.getAsJsonObject().addProperty("habit", "UNCHANGED");
        assertThrows(IllegalArgumentException.class,
            () -> CatalogueParser.parse(Map.of(file("spruce"), unchangedWithShedding), mod -> true));
        var evergreenWithLeafOut = profile("dynamictrees:azalea");
        evergreenWithLeafOut.getAsJsonObject().addProperty("habit", "EVERGREEN");
        assertThrows(IllegalArgumentException.class,
            () -> CatalogueParser.parse(Map.of(file("azalea"), evergreenWithLeafOut), mod -> true));
    }

    private static ResourceLocation file(String path) {
        return new ResourceLocation("better_seasonal_trees", path);
    }

    private static JsonElement profile(String speciesId) {
        return JsonParser.parseString("{\"speciesId\":\"" + speciesId + "\",\"habit\":\"TEMPERATE\","
            + "\"leafOutStart\":0,\"leafOutDuration\":0.3333333333333333,"
            + "\"shedStart\":8,\"shedEnd\":9,\"bloomStart\":0,\"bloomEnd\":0,"
            + "\"autumnColor\":\"#FFAA00\",\"bloomVisual\":\"none\"}");
    }
}
