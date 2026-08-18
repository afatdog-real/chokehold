package com.chokehold.chokehold.network;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import com.chokehold.chokehold.ChokeholdMod;

import java.util.UUID;
import java.util.function.Supplier;

/**
 * Channel registration + packet codec + dispatch helpers. We use a SimpleChannel here
 * because it works on both Forge 1.20.x and NeoForge 1.20.x, where the registry layout
 * is identical. If you want to migrate to NeoForge's own PayloadRegistrar later, the
 * shape of these packets is already a clean record-style split that maps 1:1.
 *
 * <h2>Protocol summary</h2>
 * All packets are registered against the single channel
 * {@code chokehold:main} with the same protocol version "1" on both sides.
 * Adding a packet is a 3-step change:
 *
 * <ol>
 *   <li>Add a public record to this file (and its encode/decode/handle).</li>
 *   <li>Register it in {@link #register()} via {@code CHANNEL.registerMessage(...)}.</li>
 *   <li>Send it from server-side code via {@link #sendTo} /
 *       {@link #sendToTracking} / {@link #sendToServer}, depending on direction.</li>
 * </ol>
 *
 * <h2>Direction conventions</h2>
 * <ul>
 *   <li><b>S2C</b> = server → client. These update {@code ClientChokeholdCache}
 *       via the {@code onX(...)} handlers, which the HUD reads every frame.</li>
 *   <li><b>C2S</b> = client → server. Currently only one: the Space press
 *       report ({@code C2SSpacePressPacket}) used to resolve wheel rounds and
 *       gasp QTEs.</li>
 * </ul>
 */
public final class ModNetworking {
    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ChokeholdMod.MODID, "main"),
            () -> "1",
            s -> "1".equals(s),
            s -> "1".equals(s)
    );

    private static int id = 0;
    private static int nextId() { return id++; }

    public static void register() {
        CHANNEL.registerMessage(nextId(), S2CChokeholdStartPacket.class,
                S2CChokeholdStartPacket::encode, S2CChokeholdStartPacket::decode, S2CChokeholdStartPacket::handle);
        CHANNEL.registerMessage(nextId(), S2CWheelSyncPacket.class,
                S2CWheelSyncPacket::encode, S2CWheelSyncPacket::decode, S2CWheelSyncPacket::handle);
        CHANNEL.registerMessage(nextId(), C2SSpacePressPacket.class,
                C2SSpacePressPacket::encode, C2SSpacePressPacket::decode, C2SSpacePressPacket::handle);
        CHANNEL.registerMessage(nextId(), S2CRoundResultPacket.class,
                S2CRoundResultPacket::encode, S2CRoundResultPacket::decode, S2CRoundResultPacket::handle);
        CHANNEL.registerMessage(nextId(), S2CGaspQTEPacket.class,
                S2CGaspQTEPacket::encode, S2CGaspQTEPacket::decode, S2CGaspQTEPacket::handle);
        CHANNEL.registerMessage(nextId(), S2CFaintStartPacket.class,
                S2CFaintStartPacket::encode, S2CFaintStartPacket::decode, S2CFaintStartPacket::handle);
        CHANNEL.registerMessage(nextId(), S2CFaintEndPacket.class,
                S2CFaintEndPacket::encode, S2CFaintEndPacket::decode, S2CFaintEndPacket::handle);
        CHANNEL.registerMessage(nextId(), S2CChokeholdEndPacket.class,
                S2CChokeholdEndPacket::encode, S2CChokeholdEndPacket::decode, S2CChokeholdEndPacket::handle);
    }

    public static void sendTo(ServerPlayer player, Object msg) {
        CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), msg);
    }

    /**
     * Player-typed overload that no-ops for non-{@link ServerPlayer} entities
     * (e.g. {@code TestDummyEntity}). For real players, forwards to the
     * canonical {@link ServerPlayer} overload via {@link PacketDistributor.PLAYER}.
     */
    public static void sendTo(net.minecraft.world.entity.player.Player player, Object msg) {
        if (player instanceof ServerPlayer sp) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> sp), msg);
        }
        // else: entity has no client connection; nothing to send.
    }

    public static void sendToTracking(net.minecraft.world.entity.Entity e, Object msg) {
        CHANNEL.send(PacketDistributor.TRACKING_ENTITY.with(() -> e), msg);
    }

    public static void sendToServer(Object msg) {
        CHANNEL.sendToServer(msg);
    }

    // -- Packet bodies --------------------------------------------------------

    /** S2C: tell the client that a chokehold is starting and what role this player has. */
    public record S2CChokeholdStartPacket(UUID partnerId, boolean isChokeholder) {
        public static void encode(S2CChokeholdStartPacket p, FriendlyByteBuf buf) {
            buf.writeUUID(p.partnerId);
            buf.writeBoolean(p.isChokeholder);
        }
        public static S2CChokeholdStartPacket decode(FriendlyByteBuf buf) {
            return new S2CChokeholdStartPacket(buf.readUUID(), buf.readBoolean());
        }
        public static void handle(S2CChokeholdStartPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.chokehold.chokehold.client.ClientChokeholdCache.onChokeholdStart(p));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * S2C: wheel sync. Sent once per round: contains the round-start tick, the
     * rotation speed (as the round number, since speed is a pure function of it
     * — round n rotates 1.5^(n-1)× faster than base) and the air bar. Clients
     * then compute needle angle locally every frame to avoid per-tick packet spam.
     */
    public record S2CWheelSyncPacket(long roundStartTick, long roundTimeoutTick, int air, int roundNumber) {
        public static void encode(S2CWheelSyncPacket p, FriendlyByteBuf buf) {
            buf.writeLong(p.roundStartTick);
            buf.writeLong(p.roundTimeoutTick);
            buf.writeInt(p.air);
            buf.writeInt(p.roundNumber);
        }
        public static S2CWheelSyncPacket decode(FriendlyByteBuf buf) {
            return new S2CWheelSyncPacket(buf.readLong(), buf.readLong(), buf.readInt(), buf.readInt());
        }
        public static void handle(S2CWheelSyncPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.chokehold.chokehold.client.ClientChokeholdCache.onWheelSync(p));
            ctx.get().setPacketHandled(true);
        }
    }

    /** C2S: client reports a Space key press during a wheel round. */
    public record C2SSpacePressPacket(long clientTick) {
        public static void encode(C2SSpacePressPacket p, FriendlyByteBuf buf) {
            buf.writeLong(p.clientTick);
        }
        public static C2SSpacePressPacket decode(FriendlyByteBuf buf) {
            return new C2SSpacePressPacket(buf.readLong());
        }
        public static void handle(C2SSpacePressPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() ->
                    com.chokehold.chokehold.event.ChokeholdEventHandlers.handleSpacePress(
                            ctx.get().getSender(), p.clientTick()));
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: round result — the two press angles, the winner, air delta, streak. */
    public record S2CRoundResultPacket(int chokeholderAngle, int restrainedAngle, int chokeholderValue, int restrainedValue,
                                       String winner, int airAfter, int streak) {
        public static void encode(S2CRoundResultPacket p, FriendlyByteBuf buf) {
            buf.writeInt(p.chokeholderAngle); buf.writeInt(p.restrainedAngle);
            buf.writeInt(p.chokeholderValue); buf.writeInt(p.restrainedValue);
            buf.writeUtf(p.winner); buf.writeInt(p.airAfter); buf.writeInt(p.streak);
        }
        public static S2CRoundResultPacket decode(FriendlyByteBuf buf) {
            return new S2CRoundResultPacket(
                    buf.readInt(), buf.readInt(), buf.readInt(), buf.readInt(),
                    buf.readUtf(32), buf.readInt(), buf.readInt());
        }
        public static void handle(S2CRoundResultPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.chokehold.chokehold.client.ClientChokeholdCache.onRoundResult(p));
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: open a 0.5s gasp QTE on the restrained player's client. */
    public record S2CGaspQTEPacket(long openTick, long closeTick) {
        public static void encode(S2CGaspQTEPacket p, FriendlyByteBuf buf) {
            buf.writeLong(p.openTick); buf.writeLong(p.closeTick);
        }
        public static S2CGaspQTEPacket decode(FriendlyByteBuf buf) {
            return new S2CGaspQTEPacket(buf.readLong(), buf.readLong());
        }
        public static void handle(S2CGaspQTEPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.chokehold.chokehold.client.ClientChokeholdCache.onGasp(p));
            ctx.get().setPacketHandled(true);
        }
    }

    /**
     * S2C: faint start — force SLEEPING pose, set duration. Carries the fainted
     * entity's UUID so any client (the victim, or anyone tracking a fainted
     * test dummy) can find the right entity and mirror the faint onto its
     * client-side capability.
     */
    public record S2CFaintStartPacket(UUID entityId, int durationTicks) {
        public static void encode(S2CFaintStartPacket p, FriendlyByteBuf buf) {
            buf.writeUUID(p.entityId);
            buf.writeInt(p.durationTicks);
        }
        public static S2CFaintStartPacket decode(FriendlyByteBuf buf) {
            return new S2CFaintStartPacket(buf.readUUID(), buf.readInt());
        }
        public static void handle(S2CFaintStartPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                net.minecraft.world.entity.Entity fainted =
                        com.chokehold.chokehold.client.ClientChokeholdCache.findEntityByUuid(p.entityId);
                if (fainted instanceof net.minecraft.world.entity.player.Player pp) {
                    // Mirror the faint onto the client-side capability so client-side
                    // handlers (notably the sleeping auto-wake cancel in
                    // ChokeholdEventHandlers) can see it. The server set its own copy in
                    // startFaint; capabilities aren't auto-synced, so we set the
                    // client copy from this packet. Without this the client's
                    // LivingEntity.tick() would auto-wake the fainted body.
                    com.chokehold.chokehold.capability.FaintedState fs =
                            com.chokehold.chokehold.capability.FaintedStateProvider.getOrNull(pp);
                    if (fs != null) {
                        fs.setFainted(true);
                        fs.setFaintTicksRemaining(p.durationTicks);
                    }
                }
                // Only the victim's own client shows the K.O. screen + cache.
                if (fainted == mc.player) {
                    com.chokehold.chokehold.client.ClientChokeholdCache.onFaintStart(p);
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: faint end — restore pose. Carries the fainted entity's UUID (see above). */
    public record S2CFaintEndPacket(UUID entityId) {
        public static void encode(S2CFaintEndPacket p, FriendlyByteBuf buf) {
            buf.writeUUID(p.entityId);
        }
        public static S2CFaintEndPacket decode(FriendlyByteBuf buf) {
            return new S2CFaintEndPacket(buf.readUUID());
        }
        public static void handle(S2CFaintEndPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> {
                net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
                net.minecraft.world.entity.Entity fainted =
                        com.chokehold.chokehold.client.ClientChokeholdCache.findEntityByUuid(p.entityId);
                if (fainted instanceof net.minecraft.world.entity.player.Player pp) {
                    // Clear the client-side capability mirror (see S2CFaintStartPacket.handle).
                    com.chokehold.chokehold.capability.FaintedState fs =
                            com.chokehold.chokehold.capability.FaintedStateProvider.getOrNull(pp);
                    if (fs != null) fs.clear();
                }
                if (fainted == mc.player) {
                    com.chokehold.chokehold.client.ClientChokeholdCache.onFaintEnd();
                }
            });
            ctx.get().setPacketHandled(true);
        }
    }

    /** S2C: chokehold ended, with a reason string for client-side display. */
    public record S2CChokeholdEndPacket(String reason) {
        public static final String REASON_ESCAPE = "escape";
        public static final String REASON_FAINT = "faint";
        public static final String REASON_MANUAL = "manual_release";
        public static final String REASON_DISCONNECT = "disconnect";
        public static final String REASON_RANGE = "out_of_range";
        /** Chokeholder missed all four zones; the restrained player is freed (no air loss). */
        public static final String REASON_OPPONENT_MISS = "opponent_miss";

        public static void encode(S2CChokeholdEndPacket p, FriendlyByteBuf buf) {
            buf.writeUtf(p.reason);
        }
        public static S2CChokeholdEndPacket decode(FriendlyByteBuf buf) {
            return new S2CChokeholdEndPacket(buf.readUtf(32));
        }
        public static void handle(S2CChokeholdEndPacket p, Supplier<NetworkEvent.Context> ctx) {
            ctx.get().enqueueWork(() -> com.chokehold.chokehold.client.ClientChokeholdCache.onChokeholdEnd(p));
            ctx.get().setPacketHandled(true);
        }
    }
}