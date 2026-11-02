package com.bettercontent.seasonaltrees.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.client.particle.TextureSheetParticle;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Purely cosmetic generated petals; it never places blocks or interacts with entities/items. */
@Mod.EventBusSubscriber(modid = "better_seasonal_trees", bus = Mod.EventBusSubscriber.Bus.FORGE,
        value = Dist.CLIENT)
public final class PetalParticles {
    private static final int MAX_LIVE = 24;
    private static final int MAX_PER_TICK = 2;
    private static final int SPAWN_INTERVAL = 5;
    private static final int SEARCH_RADIUS = 32;
    private static final Set<Petal> LIVE = new LinkedHashSet<>();
    private static int tick;

    private PetalParticles() {}

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null || !ClientCalendar.enabled()) {
            clear();
            return;
        }
        if (++tick % SPAWN_INTERVAL != 0 || LIVE.size() >= MAX_LIVE) return;

        int wanted = ThreadLocalRandom.current().nextInt(1, MAX_PER_TICK + 1);
        int emitted = 0;
        int attempts = 0;
        while (emitted < wanted && LIVE.size() < MAX_LIVE && attempts++ < MAX_PER_TICK * 4) {
            BlockPos branch = ClientCalendar.nearbyOwnedBloomBranch(minecraft.level,
                    minecraft.player.blockPosition(), SEARCH_RADIUS);
            if (branch == null) break;
            var owner = ClientCalendar.visualAt(branch, "BRANCH");
            if (owner == null) continue;
            ClientVisualDefinitions.Definition definition = ClientVisualDefinitions.current().get(owner.bloomVisual());
            if (definition == null || definition.petalSprite() == null) break;
            TextureAtlasSprite sprite = minecraft.getTextureAtlas(TextureAtlas.LOCATION_BLOCKS)
                    .apply(definition.petalSprite());
            if (sprite == null) break;
            double x = branch.getX() + 0.2 + ThreadLocalRandom.current().nextDouble(0.6);
            double y = branch.getY() + 0.55 + ThreadLocalRandom.current().nextDouble(0.45);
            double z = branch.getZ() + 0.2 + ThreadLocalRandom.current().nextDouble(0.6);
            Petal petal = new Petal(minecraft.level, x, y, z, sprite, ThreadLocalRandom.current());
            LIVE.add(petal);
            minecraft.particleEngine.add(petal);
            emitted++;
        }
    }

    public static void clear() {
        for (Petal petal : Set.copyOf(LIVE)) petal.remove();
        LIVE.clear();
    }

    private static void released(Petal petal) { LIVE.remove(petal); }

    private static final class Petal extends TextureSheetParticle {
        private final float spin;

        private Petal(ClientLevel level, double x, double y, double z,
                      TextureAtlasSprite sprite, java.util.Random random) {
            super(level, x, y, z,
                    (random.nextDouble() - 0.5) * 0.018,
                    -0.018 - random.nextDouble() * 0.02,
                    (random.nextDouble() - 0.5) * 0.018);
            setSprite(sprite);
            setSize(0.04F, 0.04F);
            quadSize = 0.075F + random.nextFloat() * 0.035F;
            lifetime = 45 + random.nextInt(36);
            gravity = 0.0035F;
            friction = 0.985F;
            alpha = 0.95F;
            spin = (random.nextFloat() - 0.5F) * 0.12F;
            roll = random.nextFloat() * (float) (Math.PI * 2);
            oRoll = roll;
        }

        @Override
        public void tick() {
            oRoll = roll;
            super.tick();
            if (!removed) roll += spin;
        }

        @Override
        public ParticleRenderType getRenderType() { return ParticleRenderType.TERRAIN_SHEET; }

        @Override
        public void remove() {
            boolean wasAlive = !removed;
            super.remove();
            if (wasAlive) PetalParticles.released(this);
        }
    }
}
