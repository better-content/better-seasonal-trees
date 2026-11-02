package com.bettercontent.seasonaltrees.network;

import com.bettercontent.seasonaltrees.scheduler.SeasonClock;
import com.bettercontent.seasonaltrees.runtime.SeasonalRuntime;
import com.bettercontent.seasonaltrees.catalogue.SeasonalCatalogue;
import com.bettercontent.seasonaltrees.catalogue.SpeciesProfile;
import com.ferreusveritas.dynamictrees.tree.species.Species;
import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.level.ChunkWatchEvent;
import net.minecraftforge.event.OnDatapackSyncEvent;
import net.minecraftforge.network.NetworkDirection;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.Optional;
import java.util.List;
import java.util.ArrayList;
import java.util.function.Supplier;
import java.util.Map;

/** Small authoritative calendar sync; callers publish at login, dimension change and visual transitions. */
public final class SeasonalNetwork {
    private static final String PROTOCOL = "4";
    private static final int MAX_VISUAL_POSITIONS = 2048;
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation("better_seasonal_trees", "calendar"), () -> PROTOCOL,
            PROTOCOL::equals, PROTOCOL::equals);

    private SeasonalNetwork() {}

    public static void register() {
        CHANNEL.registerMessage(0, CalendarPacket.class, CalendarPacket::encode,
                CalendarPacket::decode, CalendarPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(1, VisualProfilesPacket.class, VisualProfilesPacket::encode,
                VisualProfilesPacket::decode, VisualProfilesPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        CHANNEL.registerMessage(2, ManagedVisualsPacket.class, ManagedVisualsPacket::encode,
                ManagedVisualsPacket::decode, ManagedVisualsPacket::handle,
                Optional.of(NetworkDirection.PLAY_TO_CLIENT));
        MinecraftForge.EVENT_BUS.addListener(SeasonalNetwork::onWatch);
        MinecraftForge.EVENT_BUS.addListener(SeasonalNetwork::onUnWatch);
        MinecraftForge.EVENT_BUS.addListener(SeasonalNetwork::onDatapackSync);
    }

    public static void sendCalendar(ServerPlayer player, SeasonClock.Reading reading) {
        boolean enabled = reading != null;
        sendVisualProfiles(player);
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new CalendarPacket(player.serverLevel().dimension().location(),
                        enabled, enabled ? reading.phase() : 0,
                        enabled ? reading.cycleTick() : 0,
                        enabled ? reading.cycleDuration() : 1));
    }

    private static void onDatapackSync(OnDatapackSyncEvent event) {
        for (ServerPlayer player : event.getPlayers()) sendVisualProfiles(player);
    }

    private static void sendVisualProfiles(ServerPlayer player) {
        ProfileSnapshot snapshot = publishedProfiles();
        List<VisualProfile> profiles = snapshot.profiles().values().stream()
                .sorted(java.util.Comparator.comparing(SpeciesProfile::speciesId)).map(profile -> {
            return new VisualProfile(profile.speciesId(), profile.leafOutStart(), profile.leafOutDuration(),
                    profile.shedStart(), profile.shedEnd(), profile.bloomStart(), profile.bloomEnd(),
                    profile.autumnColor(), profile.bloomVisual());
        }).toList();
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new VisualProfilesPacket(snapshot.revision(), profiles));
    }

    private static ProfileSnapshot publishedProfiles() {
        for (int attempt = 0; attempt < 8; attempt++) {
            long before = SeasonalCatalogue.revision();
            Map<ResourceLocation, SpeciesProfile> profiles = SeasonalCatalogue.all();
            long after = SeasonalCatalogue.revision();
            if (before == after) return new ProfileSnapshot(after, profiles);
        }
        throw new IllegalStateException("Seasonal catalogue changed repeatedly during visual profile sync");
    }

    private record ProfileSnapshot(long revision, Map<ResourceLocation, SpeciesProfile> profiles) {}

    private static void onWatch(ChunkWatchEvent.Watch event) {
        sendManagedVisuals(event.getPlayer(), event.getPos());
    }

    private static void onUnWatch(ChunkWatchEvent.UnWatch event) {
        CHANNEL.send(PacketDistributor.PLAYER.with(event::getPlayer), new ManagedVisualsPacket(
                event.getLevel().dimension().location(), event.getPos().toLong(), false, List.of()));
    }

    private static ManagedVisualsPacket snapshot(ServerLevel level, ChunkPos chunk) {
        if (SeasonClock.read(level) == null)
            return new ManagedVisualsPacket(level.dimension().location(), chunk.toLong(), false, List.of());
        SeasonalRuntime.VisualSnapshot snapshot = SeasonalRuntime.visualSnapshot(level, chunk, MAX_VISUAL_POSITIONS);
        return visualPacket(level, chunk, snapshot);
    }

    private static ManagedVisualsPacket visualPacket(ServerLevel level, ChunkPos chunk,
                                                      SeasonalRuntime.VisualSnapshot snapshot) {
        return new ManagedVisualsPacket(level.dimension().location(), chunk.toLong(), snapshot.complete(),
                snapshot.complete() ? snapshot.positions().stream().map(ManagedVisual::from).toList() : List.of());
    }

    private static void sendManagedVisuals(ServerPlayer player, ChunkPos chunk) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), snapshot(player.serverLevel(), chunk));
    }

    /** Called by the runtime's bounded dirty-chunk publisher after ownership changes. */
    public static void publishManagedVisuals(ServerLevel level, ChunkPos chunk) {
        if (!level.hasChunk(chunk.x, chunk.z)) return;
        ManagedVisualsPacket packet = snapshot(level, chunk);
        CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunk(chunk.x, chunk.z)), packet);
    }

    /** Publishes the runtime's already-budgeted immutable snapshot without another ownership traversal. */
    public static void publishManagedVisuals(ServerLevel level, ChunkPos chunk,
                                             SeasonalRuntime.VisualSnapshot snapshot) {
        if (!level.hasChunk(chunk.x, chunk.z)) return;
        ManagedVisualsPacket packet = SeasonClock.read(level) == null
                ? new ManagedVisualsPacket(level.dimension().location(), chunk.toLong(), false, List.of())
                : visualPacket(level, chunk, snapshot);
        CHANNEL.send(PacketDistributor.TRACKING_CHUNK.with(() -> level.getChunk(chunk.x, chunk.z)), packet);
    }

    public record CalendarPacket(ResourceLocation dimension, boolean enabled,
                                 double phase, int cycleTick, int cycleDuration) {
        static void encode(CalendarPacket packet, FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(packet.dimension);
            buffer.writeBoolean(packet.enabled);
            buffer.writeDouble(packet.phase);
            buffer.writeVarInt(packet.cycleTick);
            buffer.writeVarInt(packet.cycleDuration);
        }

        static CalendarPacket decode(FriendlyByteBuf buffer) {
            return new CalendarPacket(buffer.readResourceLocation(), buffer.readBoolean(), buffer.readDouble(),
                    buffer.readVarInt(), buffer.readVarInt());
        }

        static void handle(CalendarPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() ->
                    com.bettercontent.seasonaltrees.client.ClientCalendar.acceptCalendar(packet));
            context.get().setPacketHandled(true);
        }
    }

    public record VisualProfile(ResourceLocation speciesId, double leafOutStart, double leafOutDuration,
                                double shedStart, double shedEnd, double bloomStart, double bloomEnd,
                                int autumnColor, String bloomVisual) {
        private static void encode(VisualProfile profile, FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(profile.speciesId());
            buffer.writeDouble(profile.leafOutStart());
            buffer.writeDouble(profile.leafOutDuration());
            buffer.writeDouble(profile.shedStart());
            buffer.writeDouble(profile.shedEnd());
            buffer.writeDouble(profile.bloomStart());
            buffer.writeDouble(profile.bloomEnd());
            buffer.writeInt(profile.autumnColor());
            buffer.writeUtf(profile.bloomVisual(), 64);
        }

        private static VisualProfile decode(FriendlyByteBuf buffer) {
            return new VisualProfile(buffer.readResourceLocation(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readInt(), buffer.readUtf(64));
        }
    }

    public record VisualProfilesPacket(long revision, List<VisualProfile> profiles) {
        static void encode(VisualProfilesPacket packet, FriendlyByteBuf buffer) {
            buffer.writeVarLong(packet.revision());
            buffer.writeVarInt(packet.profiles().size());
            for (VisualProfile profile : packet.profiles()) VisualProfile.encode(profile, buffer);
        }

        static VisualProfilesPacket decode(FriendlyByteBuf buffer) {
            long revision = buffer.readVarLong();
            int count = buffer.readVarInt();
            if (revision < 0 || count < 0 || count > 64)
                throw new IllegalArgumentException("Invalid seasonal visual profile payload");
            List<VisualProfile> profiles = new ArrayList<>(count);
            for (int index = 0; index < count; index++) profiles.add(VisualProfile.decode(buffer));
            return new VisualProfilesPacket(revision, List.copyOf(profiles));
        }

        static void handle(VisualProfilesPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() ->
                    com.bettercontent.seasonaltrees.client.ClientCalendar.acceptProfiles(packet));
            context.get().setPacketHandled(true);
        }
    }

    public record ManagedVisual(ResourceLocation speciesId, long root, String kind, String habit,
                                long effectiveCycle, double effectivePhase, double leafOutStart,
                                double leafOutDuration, double shedStart, double shedEnd,
                                double bloomStart, double bloomEnd, int autumnColor, String bloomVisual,
                                long position) {
        private static ManagedVisual from(SeasonalRuntime.VisualOwnership ownership) {
            SeasonalRuntime.VisualContext context = ownership.context();
            return new ManagedVisual(context.speciesId(), context.root().asLong(), ownership.kind().name(),
                    context.habit().name(), context.effectiveCycle(), context.effectivePhase(),
                    context.leafOutStart(), context.leafOutDuration(), context.shedStart(), context.shedEnd(),
                    context.bloomStart(), context.bloomEnd(), context.autumnColor(), context.bloomVisual(),
                    ownership.position().asLong());
        }

        private static void encode(ManagedVisual visual, FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(visual.speciesId());
            buffer.writeLong(visual.root());
            buffer.writeUtf(visual.kind(), 16);
            buffer.writeUtf(visual.habit(), 16);
            buffer.writeLong(visual.effectiveCycle());
            buffer.writeDouble(visual.effectivePhase());
            buffer.writeDouble(visual.leafOutStart());
            buffer.writeDouble(visual.leafOutDuration());
            buffer.writeDouble(visual.shedStart());
            buffer.writeDouble(visual.shedEnd());
            buffer.writeDouble(visual.bloomStart());
            buffer.writeDouble(visual.bloomEnd());
            buffer.writeInt(visual.autumnColor());
            buffer.writeUtf(visual.bloomVisual(), 64);
            buffer.writeLong(visual.position());
        }

        private static ManagedVisual decode(FriendlyByteBuf buffer) {
            return new ManagedVisual(buffer.readResourceLocation(), buffer.readLong(), buffer.readUtf(16),
                    buffer.readUtf(16), buffer.readLong(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readDouble(), buffer.readDouble(), buffer.readDouble(),
                    buffer.readDouble(), buffer.readInt(), buffer.readUtf(64), buffer.readLong());
        }
    }

    public record ManagedVisualsPacket(ResourceLocation dimension, long chunkKey,
                                       boolean complete, List<ManagedVisual> positions) {
        private static void encode(ManagedVisualsPacket packet, FriendlyByteBuf buffer) {
            buffer.writeResourceLocation(packet.dimension());
            buffer.writeLong(packet.chunkKey());
            buffer.writeBoolean(packet.complete());
            buffer.writeVarInt(packet.positions().size());
            for (ManagedVisual visual : packet.positions()) ManagedVisual.encode(visual, buffer);
        }

        private static ManagedVisualsPacket decode(FriendlyByteBuf buffer) {
            ResourceLocation dimension = buffer.readResourceLocation();
            long chunk = buffer.readLong();
            boolean complete = buffer.readBoolean();
            int count = buffer.readVarInt();
            if (count < 0 || count > MAX_VISUAL_POSITIONS || (!complete && count != 0))
                throw new IllegalArgumentException("Invalid managed visual position count " + count);
            List<ManagedVisual> positions = new ArrayList<>(count);
            for (int index = 0; index < count; index++) positions.add(ManagedVisual.decode(buffer));
            return new ManagedVisualsPacket(dimension, chunk, complete, List.copyOf(positions));
        }

        private static void handle(ManagedVisualsPacket packet, Supplier<NetworkEvent.Context> context) {
            context.get().enqueueWork(() -> {
                boolean valid = packet.complete() && packet.positions().stream().allMatch(visual -> {
                    BlockPos pos = BlockPos.of(visual.position());
                    try {
                        if (Species.REGISTRY.getOptional(visual.speciesId()).isEmpty()) return false;
                        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4) == packet.chunkKey()
                                && List.of("LEAF", "BRANCH").contains(visual.kind())
                                && List.of("UNCHANGED", "TEMPERATE", "TROPICAL", "EVERGREEN").contains(visual.habit())
                                && Double.isFinite(visual.effectivePhase())
                                && visual.effectivePhase() >= 0 && visual.effectivePhase() < 12
                                && validTime(visual.leafOutStart()) && validTime(visual.leafOutDuration())
                                && validTime(visual.shedStart()) && validTime(visual.shedEnd())
                                && validTime(visual.bloomStart()) && validTime(visual.bloomEnd())
                                && visual.shedEnd() >= visual.shedStart()
                                && visual.bloomEnd() >= visual.bloomStart()
                                && visual.autumnColor() >= 0 && visual.autumnColor() <= 0xFFFFFF
                                && visual.bloomVisual().matches("[a-z0-9_]+")
                                && (visual.bloomVisual().equals("none") == (visual.bloomStart() == visual.bloomEnd()));
                    } catch (RuntimeException exception) {
                        return false;
                    }
                });
                com.bettercontent.seasonaltrees.client.ClientCalendar.replaceManagedVisuals(
                        packet.dimension(), packet.chunkKey(), valid ? packet.positions() : List.of(), valid);
            });
            context.get().setPacketHandled(true);
        }

        private static boolean validTime(double value) {
            return Double.isFinite(value) && value >= 0 && value <= 12;
        }
    }
}
