package com.bettercontent.seasonaltrees.client;

import com.bettercontent.seasonaltrees.network.SeasonalNetwork.ManagedVisual;
import com.ferreusveritas.dynamictrees.block.branch.BranchBlock;
import com.ferreusveritas.dynamictrees.block.leaves.DynamicLeavesBlock;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.ChunkRenderTypeSet;
import net.minecraftforge.client.event.ModelEvent;
import net.minecraftforge.client.model.BakedModelWrapper;
import net.minecraftforge.client.model.data.ModelData;
import net.minecraftforge.client.model.data.ModelProperty;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Chunk-baked wrappers which select generated models only for server-owned positions. */
@Mod.EventBusSubscriber(modid = "better_seasonal_trees", bus = Mod.EventBusSubscriber.Bus.MOD,
        value = Dist.CLIENT)
public final class SeasonalModels {
    private SeasonalModels() {}

    @SubscribeEvent
    public static void registerAdditional(ModelEvent.RegisterAdditional event) {
        Map<String, ClientVisualDefinitions.Definition> definitions =
                ClientVisualDefinitions.reload(Minecraft.getInstance().getResourceManager());
        for (ClientVisualDefinitions.Definition definition : definitions.values()) {
            event.register(definition.greenModel());
            event.register(definition.autumnModel());
            if (definition.sparseModel() != null) {
                event.register(definition.sparseModel());
                event.register(definition.fullModel());
            }
            if (definition.branchModel() != null) {
                event.register(definition.branchModel());
            }
        }
    }

    @SubscribeEvent
    public static void modifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<String, ClientVisualDefinitions.Definition> definitions =
                ClientVisualDefinitions.reload(Minecraft.getInstance().getResourceManager());
        ClientCalendar.visualDefinitionsReloaded(definitions);
        Map<ResourceLocation, BakedModel> models = event.getModels();
        Map<String, ModelVariants> variants = loadVariants(models, definitions);
        Set<ResourceLocation> leavesWrapped = new HashSet<>();
        Set<ResourceLocation> branchesWrapped = new HashSet<>();
        List<DynamicLeavesBlock> registeredLeafBlocks = new ArrayList<>();
        for (Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (block instanceof DynamicLeavesBlock leaves) registeredLeafBlocks.add(leaves);
        }

        for (Species species : Species.REGISTRY.getAll()) {
            for (DynamicLeavesBlock leaves : registeredLeafBlocks) {
                if (species.isValidLeafBlock(leaves)) {
                    ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(leaves);
                    if (leavesWrapped.add(id)) wrapModel(models, id, false, variants);
                }
            }
            BranchBlock branch = species.getFamily().getBranch().orElse(null);
            if (branch != null) {
                ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(branch);
                if (branchesWrapped.add(id)) wrapModel(models, id, true, variants);
            }
        }
    }

    private static Map<String, ModelVariants> loadVariants(Map<ResourceLocation, BakedModel> models,
                                                            Map<String, ClientVisualDefinitions.Definition> definitions) {
        Map<String, ModelVariants> result = new java.util.HashMap<>();
        definitions.forEach((id, definition) -> result.put(id, new ModelVariants(
                requiredModel(models, definition.greenModel()), requiredModel(models, definition.autumnModel()),
                definition.sparseModel() == null ? null : requiredModel(models, definition.sparseModel()),
                definition.fullModel() == null ? null : requiredModel(models, definition.fullModel()),
                definition.branchModel() == null ? null : requiredModel(models, definition.branchModel()))));
        return Map.copyOf(result);
    }

    private static void wrapModel(Map<ResourceLocation, BakedModel> models, ResourceLocation blockId,
                                  boolean branch, Map<String, ModelVariants> variants) {
        boolean found = false;
        for (Map.Entry<ResourceLocation, BakedModel> entry : models.entrySet()) {
            ResourceLocation key = entry.getKey();
            if (!(key instanceof ModelResourceLocation) || !key.getNamespace().equals(blockId.getNamespace())
                    || !key.getPath().equals(blockId.getPath())) continue;
            found = true;
            if (branch) entry.setValue(new SeasonalBranch(entry.getValue(), variants));
            else entry.setValue(new SeasonalLeaves(entry.getValue(), variants));
        }
        if (!found) throw new IllegalStateException("Missing installed Dynamic Trees block model for " + blockId);
    }

    private static BakedModel requiredModel(Map<ResourceLocation, BakedModel> models, ResourceLocation id) {
        BakedModel model = models.get(id);
        if (model == null) throw new IllegalStateException("Missing installed seasonal model " + id);
        return model;
    }

    private record ModelVariants(BakedModel green, BakedModel autumn, BakedModel sparse,
                                 BakedModel full, BakedModel branch) {}

    private static final ModelProperty<ManagedVisual> OWNER = new ModelProperty<>();

    private abstract static class OwnedModel extends BakedModelWrapper<BakedModel> {
        private final boolean branch;
        protected final Map<String, ModelVariants> variants;
        private OwnedModel(BakedModel original, boolean branch, Map<String, ModelVariants> variants) {
            super(original); this.branch = branch; this.variants = variants;
        }

        @Override
        public ModelData getModelData(BlockAndTintGetter level, BlockPos pos, BlockState state, ModelData data) {
            ModelData base = originalModel.getModelData(level, pos, state, data);
            ManagedVisual owner = ClientCalendar.visualAt(pos, branch ? "BRANCH" : "LEAF");
            return base.derive().with(OWNER, owner).build();
        }

        protected BakedModel selected(ModelData data) {
            ManagedVisual owner = data.get(OWNER);
            if (owner == null) return originalModel;
            ModelVariants models = variants.get(owner.bloomVisual());
            if (models == null) return originalModel;
            return switch (ClientCalendar.visualStage(owner)) {
                case ORIGINAL -> originalModel;
                case GREEN -> branch ? originalModel : models.green();
                case AUTUMN -> branch ? originalModel : models.autumn();
                case SPARSE_BLOOM -> branch || models.sparse() == null ? originalModel : models.sparse();
                case FULL_BLOOM -> branch || models.full() == null ? originalModel : models.full();
            };
        }

        @Override public TextureAtlasSprite getParticleIcon() { return originalModel.getParticleIcon(); }
        @Override public TextureAtlasSprite getParticleIcon(ModelData data) { return selected(data).getParticleIcon(data); }
        @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random) {
            return originalModel.getQuads(state, side, random);
        }
        @Override public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                                                ModelData data, RenderType renderType) {
            return selected(data).getQuads(state, side, random, data, renderType);
        }
    }

    private static final class SeasonalLeaves extends OwnedModel {
        private SeasonalLeaves(BakedModel original, Map<String, ModelVariants> variants) { super(original, false, variants); }
    }

    private static final class SeasonalBranch extends OwnedModel {
        private SeasonalBranch(BakedModel original, Map<String, ModelVariants> variants) { super(original, true, variants); }

        @Override
        public ChunkRenderTypeSet getRenderTypes(BlockState state, RandomSource random, ModelData data) {
            ManagedVisual owner = data.get(OWNER);
            if (owner == null) return originalModel.getRenderTypes(state, random, data);
            ClientCalendar.VisualStage stage = ClientCalendar.visualStage(owner);
            ModelVariants variants = variants(owner);
            if (variants == null || variants.branch() == null
                    || (stage != ClientCalendar.VisualStage.SPARSE_BLOOM
                    && stage != ClientCalendar.VisualStage.FULL_BLOOM))
                return originalModel.getRenderTypes(state, random, data);
            return ChunkRenderTypeSet.union(originalModel.getRenderTypes(state, random, data),
                    ChunkRenderTypeSet.of(RenderType.cutout()));
        }

        @Override
        public List<BakedQuad> getQuads(BlockState state, Direction side, RandomSource random,
                                        ModelData data, RenderType renderType) {
            List<BakedQuad> result = new ArrayList<>(originalModel.getQuads(state, side, random, data, renderType));
            ManagedVisual owner = data.get(OWNER);
            if (owner == null || (renderType != null && renderType != RenderType.cutout())) return result;
            ClientCalendar.VisualStage stage = ClientCalendar.visualStage(owner);
            ModelVariants variants = variants(owner);
            if (variants == null || variants.branch() == null
                    || (stage != ClientCalendar.VisualStage.SPARSE_BLOOM
                    && stage != ClientCalendar.VisualStage.FULL_BLOOM)) return result;
            result.addAll(variants.branch().getQuads(state, side, random, data, null));
            return result;
        }

        private ModelVariants variants(ManagedVisual owner) { return this.variants.get(owner.bloomVisual()); }
    }
}
