package com.bettercontent.seasonaltrees.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;

import java.io.IOException;
import java.io.Reader;
import java.util.LinkedHashMap;
import java.util.Map;

/** Validated generated-model catalogue keyed by the server's stable bloomVisual IDs. */
public final class ClientVisualDefinitions {
    private static final ResourceLocation CATALOGUE = new ResourceLocation(
            "better_seasonal_trees", "seasonal_visuals/catalogue.json");
    private static volatile Map<String, Definition> definitions = Map.of();

    private ClientVisualDefinitions() {}

    public static Map<String, Definition> reload(ResourceManager manager) {
        Resource resource = manager.getResource(CATALOGUE).orElseThrow(() ->
                new IllegalStateException("Missing installed seasonal visual catalogue " + CATALOGUE));
        try (Reader reader = resource.openAsReader()) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            JsonObject families = object(root, "families");
            Map<String, Definition> byFamily = new LinkedHashMap<>();
            for (Map.Entry<String, JsonElement> entry : families.entrySet()) {
                String family = entry.getKey();
                if (!family.matches("[a-z0-9_]+")) throw new IllegalArgumentException("Invalid visual family " + family);
                JsonObject value = entry.getValue().getAsJsonObject();
                ResourceLocation green = id(string(value, "greenLeaves"));
                ResourceLocation autumn = id(string(value, "autumnLeaves"));
                ResourceLocation sparse = nullableId(value, "sparseLeaves");
                ResourceLocation full = nullableId(value, "fullLeaves");
                ResourceLocation branches = nullableId(value, "branchFlowers");
                ResourceLocation petals = nullableId(value, "petalSprite");
                double sparseUntil = optionalNumber(value, "sparseUntil", 0.25);
                double fullFrom = optionalNumber(value, "fullFrom", 0.75);
                boolean hasFlowers = branches != null;
                if (hasFlowers && (petals == null || ((sparse == null) != (full == null))
                        || (sparse != null && !(sparseUntil > 0 && sparseUntil < fullFrom && fullFrom < 1))))
                    throw new IllegalArgumentException("Invalid bloom transition for visual family " + family);
                byFamily.put(family, new Definition(family, green, autumn, sparse, full,
                        branches, petals, sparseUntil, fullFrom));
            }
            JsonObject visuals = object(root, "visuals");
            Map<String, Definition> next = new LinkedHashMap<>();
            next.put("none", requiredFamily(byFamily, "generic", "none"));
            for (Map.Entry<String, JsonElement> entry : visuals.entrySet()) {
                String visualId = entry.getKey();
                if (!visualId.matches("[a-z0-9_]+") || visualId.equals("none"))
                    throw new IllegalArgumentException("Invalid bloom visual ID " + visualId);
                if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Visual mapping must name a family for " + visualId);
                next.put(visualId, requiredFamily(byFamily, entry.getValue().getAsString(), visualId));
            }
            if (!root.keySet().equals(java.util.Set.of("families", "visuals")))
                throw new IllegalArgumentException("Unknown root field in seasonal visual catalogue");
            for (Definition definition : next.values()) {
                requireModel(manager, definition.greenModel());
                requireModel(manager, definition.autumnModel());
                if (definition.sparseModel() != null) {
                    requireModel(manager, definition.sparseModel());
                    requireModel(manager, definition.fullModel());
                }
                if (definition.branchModel() != null) {
                    requireModel(manager, definition.branchModel());
                    requireTexture(manager, definition.petalSprite());
                }
            }
            definitions = Map.copyOf(next);
            return definitions;
        } catch (IOException | RuntimeException exception) {
            throw new IllegalStateException("Unable to load seasonal client visual catalogue", exception);
        }
    }

    public static Map<String, Definition> current() { return definitions; }

    private static void requireModel(ResourceManager manager, ResourceLocation model) throws IOException {
        ResourceLocation file = new ResourceLocation(model.getNamespace(), "models/" + model.getPath() + ".json");
        Resource resource = manager.getResource(file).orElseThrow(() ->
                new IllegalStateException("Missing installed seasonal model asset " + file));
        try (Reader reader = resource.openAsReader()) {
            JsonElement parsed = JsonParser.parseReader(reader);
            if (!parsed.isJsonObject() || !parsed.getAsJsonObject().has("textures"))
                throw new IllegalStateException("Seasonal model has no textures object: " + file);
            JsonObject textures = parsed.getAsJsonObject().getAsJsonObject("textures");
            for (Map.Entry<String, JsonElement> texture : textures.entrySet()) {
                if (!texture.getValue().isJsonPrimitive() || !texture.getValue().getAsJsonPrimitive().isString())
                    throw new IllegalStateException("Invalid texture reference in " + file + ": " + texture.getKey());
                requireTexture(manager, id(texture.getValue().getAsString()));
            }
        }
    }

    private static void requireTexture(ResourceManager manager, ResourceLocation sprite) {
        ResourceLocation file = new ResourceLocation(sprite.getNamespace(), "textures/" + sprite.getPath() + ".png");
        if (manager.getResource(file).isEmpty())
            throw new IllegalStateException("Missing installed seasonal texture asset " + file);
    }

    private static String string(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(field + " must be a string");
        return value.getAsString();
    }

    private static JsonObject object(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonObject()) throw new IllegalArgumentException(field + " must be an object");
        return value.getAsJsonObject();
    }

    private static Definition requiredFamily(Map<String, Definition> families, String family, String visualId) {
        Definition definition = families.get(family);
        if (definition == null) throw new IllegalArgumentException("Missing visual family " + family + " for " + visualId);
        return new Definition(visualId, definition.greenModel(), definition.autumnModel(), definition.sparseModel(),
                definition.fullModel(), definition.branchModel(), definition.petalSprite(),
                definition.bloomSparseUntil(), definition.bloomFullUntil());
    }

    private static ResourceLocation nullableId(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || value.isJsonNull()) return null;
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException(field + " must be a resource location or null");
        return id(value.getAsString());
    }

    private static double optionalNumber(JsonObject object, String field, double fallback) {
        return object.has(field) ? number(object, field) : fallback;
    }

    private static double number(JsonObject object, String field) {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException(field + " must be numeric");
        double result = value.getAsDouble();
        if (!Double.isFinite(result)) throw new IllegalArgumentException(field + " must be finite");
        return result;
    }

    private static ResourceLocation id(String value) {
        ResourceLocation id = ResourceLocation.tryParse(value);
        if (id == null) throw new IllegalArgumentException("Invalid resource location " + value);
        return id;
    }

    public record Definition(String visualId, ResourceLocation greenModel, ResourceLocation autumnModel,
                             ResourceLocation sparseModel, ResourceLocation fullModel,
                             ResourceLocation branchModel, ResourceLocation petalSprite,
                             double bloomSparseUntil, double bloomFullUntil) {}
}
