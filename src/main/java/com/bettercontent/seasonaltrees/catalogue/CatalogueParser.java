package com.bettercontent.seasonaltrees.catalogue;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.resources.ResourceLocation;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/** Side-effect-free validation, shared by reloads and tests. */
public final class CatalogueParser {
    private static final Set<String> FIELDS = Set.of(
        "speciesId", "habit", "leafOutStart", "leafOutDuration", "shedStart", "shedEnd",
        "bloomStart", "bloomEnd", "autumnColor", "bloomVisual", "requiredMods", "optionalMods"
    );

    private CatalogueParser() { }

    public record Result(Map<ResourceLocation, SpeciesProfile> profiles, Map<ResourceLocation, String> skipped) {
        public Result {
            profiles = Map.copyOf(profiles);
            skipped = Map.copyOf(skipped);
        }
    }

    public static Result parse(Map<ResourceLocation, JsonElement> resources, Predicate<String> installedMod) {
        Map<ResourceLocation, SpeciesProfile> parsed = new LinkedHashMap<>();
        Map<ResourceLocation, String> skipped = new LinkedHashMap<>();
        for (var entry : resources.entrySet()) {
            ResourceLocation file = entry.getKey();
            try {
                if (!entry.getValue().isJsonObject()) throw new IllegalArgumentException("expected object");
                JsonObject json = entry.getValue().getAsJsonObject();
                for (String field : json.keySet()) {
                    if (!FIELDS.contains(field)) throw new IllegalArgumentException("unknown field " + field);
                }
                String rawId = string(json, "speciesId");
                int separator = rawId.indexOf(':');
                ResourceLocation id = ResourceLocation.tryParse(rawId);
                if (id == null || separator <= 0 || separator == rawId.length() - 1) {
                    throw new IllegalArgumentException("speciesId must be a namespaced resource location");
                }
                for (String mod : mods(json, "requiredMods")) {
                    if (!installedMod.test(mod)) throw new IllegalArgumentException("required mod absent: " + mod);
                }
                // The species namespace itself must be present unless explicitly optional.
                Set<String> optional = mods(json, "optionalMods");
                if (!installedMod.test(id.getNamespace()) && !optional.contains(id.getNamespace())) {
                    throw new IllegalArgumentException("species namespace mod absent: " + id.getNamespace());
                }
                SpeciesProfile.Habit habit;
                try {
                    habit = SpeciesProfile.Habit.valueOf(string(json, "habit"));
                } catch (IllegalArgumentException ex) {
                    throw new IllegalArgumentException("invalid habit", ex);
                }
                String color = string(json, "autumnColor");
                if (!color.matches("#[0-9a-fA-F]{6}")) {
                    throw new IllegalArgumentException("autumnColor must be #RRGGBB");
                }
                SpeciesProfile profile = new SpeciesProfile(id, habit,
                    number(json, "leafOutStart"), number(json, "leafOutDuration"),
                    number(json, "shedStart"), number(json, "shedEnd"),
                    number(json, "bloomStart"), number(json, "bloomEnd"),
                    Integer.parseInt(color.substring(1), 16), string(json, "bloomVisual"));
                String missing = optional.stream().filter(mod -> !installedMod.test(mod)).findFirst().orElse(null);
                if (missing != null) {
                    skipped.put(file, "optional mod absent: " + missing);
                    continue;
                }
                if (parsed.putIfAbsent(id, profile) != null) {
                    throw new IllegalArgumentException("duplicate speciesId " + id);
                }
            } catch (RuntimeException ex) {
                throw new IllegalArgumentException("Invalid seasonal profile " + file + ": " + ex.getMessage(), ex);
            }
        }
        return new Result(parsed, skipped);
    }

    private static String string(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(name + " must be a string");
        }
        return value.getAsString();
    }

    private static double number(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IllegalArgumentException(name + " must be a number");
        }
        return value.getAsDouble();
    }

    private static Set<String> mods(JsonObject json, String name) {
        if (!json.has(name)) return Set.of();
        JsonElement value = json.get(name);
        if (!value.isJsonArray()) throw new IllegalArgumentException(name + " must be an array");
        JsonArray array = value.getAsJsonArray();
        java.util.HashSet<String> mods = new java.util.HashSet<>();
        for (JsonElement item : array) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() ||
                !item.getAsString().matches("[a-z][a-z0-9_]*")) {
                throw new IllegalArgumentException(name + " contains invalid mod id");
            }
            if (!mods.add(item.getAsString())) throw new IllegalArgumentException(name + " contains duplicate mod id");
        }
        return Set.copyOf(mods);
    }
}
