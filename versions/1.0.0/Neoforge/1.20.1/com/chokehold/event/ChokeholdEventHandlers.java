package com.chokehold.chokehold.event;

import com.chokehold.chokehold.ChokeholdMod;
import com.chokehold.chokehold.capability.FaintedState;
import com.chokehold.chokehold.capability.FaintedStateProvider;
import com.chokehold.chokehold.capability.ChokeholdState;
import com.chokehold.chokehold.capability.ChokeholdStateProvider;
import com.chokehold.chokehold.config.ChokeholdConfig;
import com.chokehold.chokehold.entity.ModEntities;
import com.chokehold.chokehold.entity.TestDummyEntity;
import com.chokehold.chokehold.network.ModNetworking;
import com.chokehold.chokehold.network.ModNetworking.S2CFaintEndPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CFaintStartPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CGaspQTEPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CChokeholdEndPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CChokeholdStartPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CRoundResultPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CWheelSyncPacket;
import com.chokehold.chokehold.network.PacketHelper;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.TickEvent.PlayerTickEvent;
import net.minecraftforge.event.ServerChatEvent;
import net.minecraftforge.event.entity.item.ItemTossEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.event.entity.player.CriticalHitEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.entity.player.SleepingLocationCheckEvent;
import net.minecraftforge.event.entity.player.SleepingTimeCheckEvent;
import net.minecraftforge.eventbus.api.Event;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.UUID;

/**
 * All server-side gameplay logic. Each phase of the duel is handled here.
 * Hooked through @SubscribeEvent on the FORGE bus. All handlers and helpers are
 * static — this class is a stateless server-side coordinator.
 *
 * <p>Helpers and event casts are typed on {@link Player} rather than
 * {@link ServerPlayer} so non-networked test dummies (see
 * {@link TestDummyEntity}) can participate. Network sends to non-{@link
 * ServerPlayer} entities are dropped by {@code ModNetworking.sendTo(Player, ...)}.
 */
@Mod.EventBusSubscriber(modid = ChokeholdMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class ChokeholdEventHandlers {

    private ChokeholdEventHandlers() {}

    // --- Initiation ---------------------------------------------------------

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickEntity(PlayerInteractEvent.EntityInteract event) {
        Player chokeholder = event.getEntity();
        if (chokeholder == null) return;
        if (event.getLevel().isClientSide()) return;

        // Require sneaking and empty hand (no tool needed)
        if (!chokeholder.isCrouching()) return;
        ItemStack held = chokeholder.getMainHandItem();
        if (!held.isEmpty()) return;

        if (!(event.getTarget() instanceof Player)) return;
        Player target = (Player) event.getTarget();
        if (target.getUUID().equals(chokeholder.getUUID())) return; // covers self-target too

        // Check if chokeholder is behind target (within ~120° cone behind)
        // toTarget = vector from chokeholder TO target
        // targetLook = direction target is facing
        // Behind = toTarget roughly aligns with targetLook (dot > 0)
        // Generous 120° cone: reject if angle > 60° from directly behind (dot < 0.5)
        Vec3 toTarget = target.position().subtract(chokeholder.position()).normalize();
        Vec3 targetLook = target.getViewVector(1.0F).normalize();
        double dot = toTarget.dot(targetLook);
        if (dot < 0.5) { // cos(60°) = 0.5; behind cone = dot > 0.5
            chokeholder.sendSystemMessage(Component.literal("Must approach from behind.").withStyle(ChatFormatting.RED));
            return;
        }

        // Block starting if either participant is already grappling, fainted, or on cooldown.
        ChokeholdState gg = ChokeholdStateProvider.getOrNull(chokeholder);
        ChokeholdState tg = ChokeholdStateProvider.getOrNull(target);
        FaintedState gf = FaintedStateProvider.getOrNull(chokeholder);
        FaintedState tf = FaintedStateProvider.getOrNull(target);
        if (gg != null && gg.isActive()) return;
        if (tg != null && tg.isActive()) return;
        if (tf != null && tf.isFainted()) return;
        if (gf != null && gf.isFainted()) return;

        System.out.println("[ChokeholdMod DEBUG] onRightClickEntity: chokeholder=" + chokeholder.getName().getString() + "(" + chokeholder.getUUID() + ") target=" + target.getName().getString() + "(" + target.getUUID() + ")");
        System.out.println("[ChokeholdMod DEBUG] onRightClickEntity: gg.globalCooldown=" + (gg != null ? gg.getChokeholdCooldownTicks() : "null") + " tg.globalCooldown=" + (tg != null ? tg.getChokeholdCooldownTicks() : "null"));
        System.out.println("[ChokeholdMod DEBUG] onRightClickEntity: gg.partnerCooldown vs target=" + (gg != null ? gg.getPartnerCooldownRemaining(target.getUUID(), chokeholder.level().getGameTime()) : "null"));

        if (gg != null && gg.getChokeholdCooldownTicks() > 0) {
            System.out.println("[ChokeholdMod DEBUG] onRightClickEntity: BLOCKED by chokeholder global cooldown=" + gg.getChokeholdCooldownTicks());
            return;
        }
        if (tg != null && tg.getChokeholdCooldownTicks() > 0) {
            System.out.println("[ChokeholdMod DEBUG] onRightClickEntity: BLOCKED by target global cooldown=" + tg.getChokeholdCooldownTicks());
            return;
        }
        // Per-partner cooldown: block if chokeholder is on cooldown against THIS target
        if (gg != null && gg.getPartnerCooldownRemaining(target.getUUID(), chokeholder.level().getGameTime()) > 0) {
            System.out.println("[ChokeholdMod DEBUG] onRightClickEntity: BLOCKED by partner cooldown");
            return;
        }

        // Range check (minimum 0.5 blocks)
        double distance = chokeholder.distanceTo(target);
        double maxRange = ChokeholdConfig.MAX_CHOKEHOLD_RANGE.get();
        System.out.println("[ChokeholdMod DEBUG] Range check: distance=" + distance + " maxRange=" + maxRange + " minRange=0.5");
        if (distance > maxRange) {
            chokeholder.sendSystemMessage(Component.literal("Target is too far.").withStyle(ChatFormatting.RED));
            System.out.println("[ChokeholdMod DEBUG] BLOCKED: too far");
            return;
        }
        if (distance < 0.5) {
            chokeholder.sendSystemMessage(Component.literal("Target is too close.").withStyle(ChatFormatting.RED));
            System.out.println("[ChokeholdMod DEBUG] BLOCKED: too close");
            return;
        }

        // Begin chokehold
        startChokehold(chokeholder, target);
    }

    public static void startChokehold(Player chokeholder, Player target) {
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(chokeholder);
        ChokeholdState ts = ChokeholdStateProvider.getOrNull(target);
        if (gs == null || ts == null) return;

        long now = chokeholder.level().getGameTime();

        System.out.println("[ChokeholdMod DEBUG] startChokehold: chokeholder=" + chokeholder.getName().getString() + "(" + chokeholder.getUUID() + ") target=" + target.getName().getString() + "(" + target.getUUID() + ")");
        System.out.println("[ChokeholdMod DEBUG] startChokehold: gs.globalCooldown=" + gs.getChokeholdCooldownTicks() + " ts.globalCooldown=" + ts.getChokeholdCooldownTicks());
        System.out.println("[ChokeholdMod DEBUG] startChokehold: gs.partnerCooldown vs target=" + gs.getPartnerCooldownRemaining(target.getUUID(), now));

        // Global anti-spam: check if either player is on cooldown since their last chokehold END
        if (gs.getChokeholdCooldownTicks() > 0 || ts.getChokeholdCooldownTicks() > 0) {
            System.out.println("[ChokeholdMod DEBUG] startChokehold: BLOCKED by global cooldown (gs=" + gs.getChokeholdCooldownTicks() + " ts=" + ts.getChokeholdCooldownTicks() + ")");
            return; // silently ignore — player tried to chokehold too fast
        }

        // Per-partner cooldown: check if chokeholder is on cooldown against this specific target
        if (gs.getPartnerCooldownRemaining(target.getUUID(), now) > 0) {
            System.out.println("[ChokeholdMod DEBUG] startChokehold: BLOCKED by partner cooldown");
            return; // silently ignore — chokeholder tried to chokehold same target too fast
        }

        gs.clear();
        ts.clear();
        gs.setPartnerId(target.getUUID());
        gs.setChokeholder(true);
        gs.setAir(0);
        gs.setPhase(ChokeholdState.RoundPhase.WHEEL);
        gs.setRoundStartTick(now);
        gs.setRoundTimeoutTick(now + ChokeholdConfig.ROUND_TIMEOUT_TICKS.get());
        gs.setChokeholdStartTick(now);

        ts.setPartnerId(chokeholder.getUUID());
        ts.setChokeholder(false);
        ts.setAir(ChokeholdConfig.AIR_MAX.get());
        ts.setPhase(ChokeholdState.RoundPhase.WHEEL);
        ts.setRoundStartTick(now);
        ts.setRoundTimeoutTick(now + ChokeholdConfig.ROUND_TIMEOUT_TICKS.get());
        ts.setChokeholdStartTick(now);

        ModNetworking.sendTo(chokeholder, new S2CChokeholdStartPacket(target.getUUID(), true));
        ModNetworking.sendTo(target, new S2CChokeholdStartPacket(chokeholder.getUUID(), false));
        // The air bar is the restrained player's remaining air — both sides see
        // the same draining progress (the chokeholder's own "air" is always 0 by
        // design). So the chokeholder gets the target's air here, not 0. Both sides
        // start at round 1 (the base wheel speed).
        ModNetworking.sendTo(chokeholder, new S2CWheelSyncPacket(gs.getRoundStartTick(), gs.getRoundTimeoutTick(), ts.getAir(), gs.getRoundNumber()));
        ModNetworking.sendTo(target, new S2CWheelSyncPacket(ts.getRoundStartTick(), ts.getRoundTimeoutTick(), ts.getAir(), ts.getRoundNumber()));
    }

    // --- Movement / input cancellation -------------------------------------

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof Player)) return;
        Player sp = (Player) event.player;
        if (sp.level().isClientSide()) return;
        // Skip non-ServerPlayer entities (TestDummyEntity handles its own
        // motion-lock in its tick()). This also avoids double-counting
        // server-tick time for the dummy.
        if (!(sp instanceof ServerPlayer)) return;

        FaintedState fs = FaintedStateProvider.getOrNull(sp);
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(sp);

        boolean shouldLock = (fs != null && fs.isFainted())
                || (gs != null && gs.isActive()); // both chokeholder + restrained are locked during the QTE

        if (shouldLock) {
            // Safety net: startFaint's startSleeping + the two auto-wake cancels
            // already hold the SLEEPING pose for the whole duration; this re-assert
            // is a harmless no-op that would also recover if something cleared the
            // sleeping position mid-faint.
            if (fs != null && fs.isFainted()) {
                sp.setPose(Pose.SLEEPING);
                // Ensure body stays on block surface: vanilla's startSleeping() centers at
                // block center (y+0.5), but startFaint() corrected to floor.getY() + 1.2.
                // Re-apply correction each tick in case updatePose() re-centers.
                BlockPos floor = sp.blockPosition().below();
                if (Math.abs(sp.getY() - (floor.getY() + 1.2)) > 0.01) {
                    sp.setPos(sp.getX(), floor.getY() + 1.2, sp.getZ());
                }
                // Vanilla collapses the SLEEPING pose to a 0.2x0.2 box; restore
                // a lying-body hitbox matching the rendered pose.
                applyFaintedHitbox(sp);
            }
            // Kill ALL velocity, including vertical, so a jump impulse can't
            // carry the player airborne. Horizontal keeps them from drifting;
            // vertical covers the jump that the client-side input zeroing
            // (ClientInputHandler.onClientTick) can race on the first frame.
            sp.setDeltaMovement(0.0, 0.0, 0.0);
        }
    }

    // --- Per-tick duel logic ------------------------------------------------

    @SubscribeEvent
    public static void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        for (ServerPlayer player : event.getServer().getPlayerList().getPlayers()) {
            tickPlayer(player);
        }
        for (TestDummyEntity dummy : ModEntities.ACTIVE_DUMMIES) {
            tickDummy(dummy);
        }
    }

    private static void tickPlayer(ServerPlayer player) {
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(player);
        FaintedState fs = FaintedStateProvider.getOrNull(player);
        if (gs != null) gs.tickCooldown();

        // Faint tick
        if (fs != null && fs.isFainted()) {
            fs.tickFaint();
            if (player.getHealth() > 1.0f) player.setHealth(1.0f);
            if (!fs.isFainted()) {
                ModNetworking.sendTo(player, new S2CFaintEndPacket(player.getUUID()));
                ModNetworking.sendToTracking(player, new S2CFaintEndPacket(player.getUUID()));
                player.stopSleeping();
                int debuffTicks = ChokeholdConfig.WAKE_DEBUFF_SECONDS.get() * 20;
                if (debuffTicks > 0) {
                    player.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, debuffTicks, 1, false, false));
                    player.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, debuffTicks, 1, false, false));
                }
            }
        }

        if (gs == null || !gs.isActive()) return;
        if (gs.isChokeholder()) player.setSprinting(false);
        tickDuelPhase(gs, player);
    }

    /**
     * Server-side per-tick logic for {@link TestDummyEntity} dummies. Mirrors
     * {@link #tickPlayer(ServerPlayer)} for the parts that matter for a
     * non-networked participant: faint countdown, chokehold auto-end on
     * partner-missing / out-of-range, wheel-round timeout resolution.
     *
     * <p>Unlike {@code tickPlayer}, this method skips packet sends — there's
     * no client receiving them — but the dummy's own tick() handles
     * auto-press and motion-lock.
     */
    private static void tickDummy(TestDummyEntity dummy) {
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(dummy);
        FaintedState fs = FaintedStateProvider.getOrNull(dummy);
        if (gs != null) gs.tickCooldown();

        if (fs != null && fs.isFainted()) {
            fs.tickFaint();
            if (dummy.getHealth() > 1.0f) dummy.setHealth(1.0f);
            if (!fs.isFainted()) {
                ModNetworking.sendToTracking(dummy, new S2CFaintEndPacket(dummy.getUUID()));
                dummy.stopSleeping();
                int debuffTicks = ChokeholdConfig.WAKE_DEBUFF_SECONDS.get() * 20;
                if (debuffTicks > 0) {
                    dummy.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SLOWDOWN, debuffTicks, 1, false, false));
                    dummy.addEffect(new MobEffectInstance(MobEffects.WEAKNESS, debuffTicks, 1, false, false));
                }
            }
        }

        if (gs == null || !gs.isActive()) return;
        tickDuelPhase(gs, dummy);
    }

    /**
     * Shared per-tick duel logic for both real players and test dummies:
     * range check, passive air drain, "passed" penalty, round-timeout
     * resolution, and GASP-timeout re-roll. Called after the caller has
     * handled cooldown + faint tick + motion lock.
     */
    private static void tickDuelPhase(ChokeholdState gs, Player self) {
        long now = self.level().getGameTime();

        // Auto-end on range / partner missing
        if (gs.getPartnerId() != null) {
            Player partner = resolvePartner(self, gs.getPartnerId());
            if (partner == null) {
                endChokehold(self, gs, S2CChokeholdEndPacket.REASON_DISCONNECT);
                return;
            }
            if (self.distanceTo(partner) > ChokeholdConfig.MAX_CHOKEHOLD_RANGE.get()) {
                endChokehold(self, gs, S2CChokeholdEndPacket.REASON_RANGE);
                endChokehold(partner, ChokeholdStateProvider.getOrNull(partner), S2CChokeholdEndPacket.REASON_RANGE);
                return;
            }
        }

        // Optional passive air drain
        if (!gs.isChokeholder() && ChokeholdConfig.PASSIVE_AIR_DRAIN.get()
                && gs.getPhase() == ChokeholdState.RoundPhase.WHEEL
                && now % 20 == 0) {
            double drainPerSec = ChokeholdConfig.PASSIVE_AIR_DRAIN_PER_SEC.get();
            if (drainPerSec > 0) {
                int newAir = Math.max(0, gs.getAir() - (int) Math.ceil(drainPerSec));
                if (newAir != gs.getAir()) {
                    gs.setAir(newAir);
                    syncAir(self, gs, newAir);
                    if (newAir <= 0) {
                        startFaint(self, gs);
                        return;
                    }
                }
            }
        }

        // "Passed" penalty: needle completed a full revolution with a side unpressed.
        if (gs.getPhase() == ChokeholdState.RoundPhase.WHEEL) {
            checkPassedRevolution(self, gs, now);
        }

        // Resolve wheel round on timeout
        if (gs.getPhase() == ChokeholdState.RoundPhase.WHEEL && now >= gs.getRoundTimeoutTick()) {
            Player partner = resolvePartner(self, gs.getPartnerId());
            if (partner != null) resolveRound(self, partner);
        }

        // GASP window elapsed → roll into a fresh wheel round (prevents soft-lock).
        // Only the RESTRAINED player's tick should advance past the gasp window.
        // The chokeholder also has GASP_QTE phase (for needle reset visual), but must not
        // drive the round transition or it races with the restrained player's tick.
        if (gs.getPhase() == ChokeholdState.RoundPhase.GASP_QTE
                && !gs.isChokeholder()  // Only restrained side advances the round
                && now > gs.getGaspCloseTick()) {
            Player partner = resolvePartner(self, gs.getPartnerId());
            ChokeholdState pg = partner != null ? ChokeholdStateProvider.getOrNull(partner) : null;
            if (partner != null && pg != null) {
                Player chokeholder = partner;  // partner is the chokeholder
                Player restrained = self;   // self is the restrained
                beginNewRound(chokeholder, restrained, pg, gs, now, false);
            }
        }
    }

    // --- Space press handling ----------------------------------------------

    /**
     * Public entry point for both client (via {@code C2SSpacePressPacket.handle})
     * and server-side AI (the {@code TestDummyEntity.tick()} auto-press path).
     * Accepts any {@link Player} (real or dummy); packet sends go through
     * {@code ModNetworking.sendTo(Player, …)} which no-ops for non-ServerPlayers.
     */
    public static void handleSpacePress(Player player, long clientTick) {
        if (player == null) return;
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(player);
        if (gs == null || !gs.isActive()) return;

        long now = player.level().getGameTime();
        // Ignore obviously-stale client packets.
        if (Math.abs(now - clientTick) > 200) clientTick = now;

        switch (gs.getPhase()) {
            case WHEEL -> {
                Player partner = resolvePartner(player, gs.getPartnerId());
                if (partner == null) return;
                ChokeholdState pg = ChokeholdStateProvider.getOrNull(partner);
                if (pg == null) return;
                // A press timestamp slightly before the round start is normal
                // client-clock skew: the client's gameTime lags the server's by
                // a tick or two (more in single-player after a modal screen), so
                // without this the very first press of a fresh chokehold would be
                // dropped and the user would have to wait out the lag before
                // Space starts working. Clamp such presses up to the round start.
                // A press far before the round start is a genuinely stale packet
                // from a previous round (delayed through the network) and is
                // dropped instead.
                if (clientTick < gs.getRoundStartTick() - 100) return;
                long press = Math.max(clientTick, gs.getRoundStartTick());
                // First press of the round locks in the timing; re-presses
                // while the round is still open are ignored so the angle can't
                // be re-rolled.
                if (gs.getPressTick() < 0) gs.setPressTick(press);
                // A round resolves only once BOTH players have pressed (or the
                // round times out in tickPlayer/tickDummy). If the partner
                // hasn't pressed yet, we just wait — the needle keeps spinning
                // and this press stays recorded until the partner (or timeout)
                // completes the round.
                if (pg.getPressTick() < 0) return;
                resolveRound(player, partner);
            }
            case GASP_QTE -> {
                // Only the restrained player responds to the gasp
                if (gs.isChokeholder()) return;
                if (now >= gs.getGaspOpenTick() && now <= gs.getGaspCloseTick()) {
                    int newAir = Math.min(ChokeholdConfig.AIR_MAX.get(),
                            gs.getAir() + ChokeholdConfig.AIR_GAIN_PER_GASP.get());
                    gs.setAir(newAir);
                    gs.incrementStreak();
                    Player partner = resolvePartner(player, gs.getPartnerId());
                    if (gs.getConsecutiveWinStreak() >= ChokeholdConfig.STREAK_TO_ESCAPE.get()) {
                        endChokehold(player, gs, S2CChokeholdEndPacket.REASON_ESCAPE);
                        if (partner != null) endChokehold(partner, ChokeholdStateProvider.getOrNull(partner), S2CChokeholdEndPacket.REASON_ESCAPE);
                        return;
                    }
                    // Delegate round setup to the shared helper (was duplicated inline).
                    if (partner != null) {
                        ChokeholdState pg = ChokeholdStateProvider.getOrNull(partner);
                        if (pg != null) beginNewRound(partner, player, pg, gs, now, false);
                    }
                }
                // Outside window: ignore (does not reset streak per spec).
            }
            default -> {}
        }
    }

    private static void resolveRound(Player a, Player b) {
        ChokeholdState ga = ChokeholdStateProvider.getOrNull(a);
        ChokeholdState gb = ChokeholdStateProvider.getOrNull(b);
        if (ga == null || gb == null) return;

        Player chokeholder = ga.isChokeholder() ? a : b;
        Player restrained = ga.isChokeholder() ? b : a;
        ChokeholdState gs = ga.isChokeholder() ? ga : gb;
        ChokeholdState rs = ga.isChokeholder() ? gb : ga;

        long now = chokeholder.level().getGameTime();

        // If a player never pressed (pressTick -1), sample the wheel at the round's
        // actual deadline — the player who actually pressed benefits from having
        // chosen a better zone. (Sampling at `now + ROUND_TIMEOUT` instead would
        // land a full timeout AFTER the deadline, an arbitrary angle.)
        long deadline = gs.getRoundTimeoutTick();
        int gAngle = PacketHelper.needleAngleAt(gs.getPressTick() >= 0 ? gs.getPressTick() : deadline, gs.getRoundStartTick(), gs.getRoundNumber());
        int rAngle = PacketHelper.needleAngleAt(rs.getPressTick() >= 0 ? rs.getPressTick() : deadline, rs.getRoundStartTick(), rs.getRoundNumber());
        int gVal = PacketHelper.pointValueAt(gAngle, gs.getRoundStartTick(), gs.getRoundNumber());
        int rVal = PacketHelper.pointValueAt(rAngle, rs.getRoundStartTick(), rs.getRoundNumber());
        boolean gMissed = (gVal == 0);
        boolean rMissed = (rVal == 0);

        // --- Miss handling ---------------------------------------------------
        // Restrained missed (chokeholder hit): drain all air → faint → chokehold ends.
        if (rMissed && !gMissed) {
            ModNetworking.sendTo(chokeholder, new S2CRoundResultPacket(gAngle, rAngle, gVal, rVal, "restrained_miss", 0, 0));
            ModNetworking.sendTo(restrained, new S2CRoundResultPacket(gAngle, rAngle, gVal, rVal, "restrained_miss", 0, 0));
            rs.setAir(0);
            startFaint(restrained, rs);
            return;
        }
        // Chokeholder missed (restrained hit): free the restrained player; no air loss.
        if (gMissed && !rMissed) {
            ModNetworking.sendTo(chokeholder, new S2CRoundResultPacket(gAngle, rAngle, gVal, rVal, "chokeholdr_miss", rs.getAir(), 0));
            ModNetworking.sendTo(restrained, new S2CRoundResultPacket(gAngle, rAngle, gVal, rVal, "chokeholdr_miss", rs.getAir(), 0));
            endChokehold(restrained, rs, S2CChokeholdEndPacket.REASON_OPPONENT_MISS);
            endChokehold(chokeholder, gs, S2CChokeholdEndPacket.REASON_OPPONENT_MISS);
            return;
        }
        // Both missed: with the (much smaller) hit area a mutual whiff is the most
        // common outcome — treat it as an inconclusive round and re-roll rather
        // than instantly fainting the restrained player. No air/streak change; a
        // miss by exactly one player is still punished in the branches above.
        if (gMissed && rMissed) {
            ModNetworking.sendTo(chokeholder, new S2CRoundResultPacket(gAngle, rAngle, gVal, rVal, "both_miss", rs.getAir(), 0));
            ModNetworking.sendTo(restrained, new S2CRoundResultPacket(gAngle, rAngle, gVal, rVal, "both_miss", rs.getAir(), 0));
            beginNewRound(chokeholder, restrained, gs, rs, now, true); // reset speed on double miss
            return;
        }

        if (gVal >= rVal) {
            // Chokeholder wins this round
            int newAir = Math.max(0, rs.getAir() - ChokeholdConfig.AIR_LOSS_PER_ROUND.get());
            rs.setAir(newAir);
            rs.resetStreak();

            ModNetworking.sendTo(chokeholder, new S2CRoundResultPacket(gAngle, rAngle, gVal, rVal, "chokeholder", newAir, 0));
            ModNetworking.sendTo(restrained, new S2CRoundResultPacket(gAngle, rAngle, gVal, rVal, "chokeholder", newAir, 0));

            if (newAir <= 0) {
                startFaint(restrained, rs);
                return;
            }

            // Begin a new round
            beginNewRound(chokeholder, restrained, gs, rs, now, false);
        } else {
            // Restrained player wins → open gasp QTE
            long open = now;
            long close = open + ChokeholdConfig.GASP_WINDOW_TICKS.get();
            rs.setPhase(ChokeholdState.RoundPhase.GASP_QTE);
            rs.setGaspOpenTick(open);
            rs.setGaspCloseTick(close);
            gs.setPhase(ChokeholdState.RoundPhase.GASP_QTE);

            ModNetworking.sendTo(restrained, new S2CGaspQTEPacket(open, close));
            // Send the GASP packet to the chokeholder too — they can't answer it
            // (pressSpace guards on isChokeholder), but their client uses gaspOpen to
            // pin the needle at 0° so the arrow visibly resets to the top when the
            // round ends instead of keeping on spinning the finished round.
            ModNetworking.sendTo(chokeholder, new S2CGaspQTEPacket(open, close));
            ModNetworking.sendTo(chokeholder, new S2CRoundResultPacket(gAngle, rAngle, gVal, rVal, "restrained", rs.getAir(), rs.getConsecutiveWinStreak()));
        }
    }

    /**
     * The "passed" penalty: once the needle completes a full revolution of a
     * wheel round while a side hasn't pressed, that side "passed" the wheel.
     * Milder than a miss — instead of an instant faint it shifts air by
     * {@code PASSED_PENALTY_AIR}: the restrained player loses that much air if
     * THEY passed, and gains that much if the CHOKEHOLDER passed (the chokeholder's
     * hesitation is the restrained player's reprieve). Runs once per round per
     * side, from both participants' ticks; the shared {@code passedApplied}
     * flag on each side makes the whole penalty idempotent.
     */
    private static void checkPassedRevolution(Player self, ChokeholdState gs, long now) {
        if (gs.getPhase() != ChokeholdState.RoundPhase.WHEEL || gs.isPassedApplied()) return;
        if (PacketHelper.revolutionsCompleted(now - gs.getRoundStartTick(), gs.getRoundNumber()) < 1) return;

        Player partner = resolvePartner(self, gs.getPartnerId());
        ChokeholdState pg = partner != null ? ChokeholdStateProvider.getOrNull(partner) : null;
        if (partner == null || pg == null) return;

        // Mark both sides so the partner's same-tick tick can't double-fire.
        gs.setPassedApplied(true);
        pg.setPassedApplied(true);

        Player chokeholder = gs.isChokeholder() ? self : partner;
        Player restrained = gs.isChokeholder() ? partner : self;
        ChokeholdState gg = gs.isChokeholder() ? gs : pg;
        ChokeholdState rs = gs.isChokeholder() ? pg : gs;

        boolean restrainedPassed = rs.getPressTick() < 0;
        boolean chokeholdrPassed = gg.getPressTick() < 0;
        if (!restrainedPassed && !chokeholdrPassed) return;

        int delta = ChokeholdConfig.PASSED_PENALTY_AIR.get();
        int air = rs.getAir();
        if (restrainedPassed) air = Math.max(0, air - delta);
        if (chokeholdrPassed) air = Math.min(ChokeholdConfig.AIR_MAX.get(), air + delta);
        rs.setAir(air);
        syncAir(restrained, rs, air);

        // Tell the (real) participants what happened. The penalty is also the
        // "passed" event's only UI — the air bar moves by the delta either way.
        notifyPassed(chokeholder, restrained, delta, restrainedPassed, chokeholdrPassed);

        if (air <= 0) {
            startFaint(restrained, rs);
        }
    }

    private static void notifyPassed(Player chokeholder, Player restrained, int delta,
                                     boolean restrainedPassed, boolean chokeholdrPassed) {
        String restrainedMsg;
        String chokeholdrMsg;
        if (restrainedPassed && chokeholdrPassed) {
            restrainedMsg = "Both of you PASSED the wheel — no air change.";
            chokeholdrMsg = restrainedMsg;
        } else if (restrainedPassed) {
            restrainedMsg = "You PASSED the wheel! -" + delta + " air.";
            chokeholdrMsg = "Your opponent PASSED the wheel — they lose " + delta + " air.";
        } else {
            restrainedMsg = "The chokeholder PASSED the wheel! +" + delta + " air.";
            chokeholdrMsg = "You PASSED the wheel — your opponent regains " + delta + " air.";
        }
        // Dummies have no client connection — only notify real ServerPlayers.
        if (restrained instanceof ServerPlayer) {
            restrained.sendSystemMessage(Component.literal(restrainedMsg).withStyle(ChatFormatting.YELLOW));
        }
        if (chokeholder instanceof ServerPlayer) {
            chokeholder.sendSystemMessage(Component.literal(chokeholdrMsg).withStyle(ChatFormatting.YELLOW));
        }
    }

    private static void beginNewRound(Player chokeholder, Player restrained, ChokeholdState gs, ChokeholdState rs, long now, boolean resetRoundNumber) {
        long start = now;
        long timeout = start + ChokeholdConfig.ROUND_TIMEOUT_TICKS.get();
        // The wheel rotates 2x faster with every round that passes (100% → 200% → 400%), capped at 400%.
        if (resetRoundNumber) {
            gs.setRoundNumber(1);
            rs.setRoundNumber(1);
        } else {
            gs.setRoundNumber(gs.getRoundNumber() + 1);
            rs.setRoundNumber(rs.getRoundNumber() + 1);
        }
        gs.setRoundStartTick(start);
        gs.setRoundTimeoutTick(timeout);
        gs.setPressTick(-1);
        gs.setPassedApplied(false);
        gs.setPhase(ChokeholdState.RoundPhase.WHEEL);
        rs.setRoundStartTick(start);
        rs.setRoundTimeoutTick(timeout);
        rs.setPressTick(-1);
        rs.setPassedApplied(false);
        rs.setPhase(ChokeholdState.RoundPhase.WHEEL);

        // Air bar = restrained player's remaining air; both sides get it.
        ModNetworking.sendTo(chokeholder, new S2CWheelSyncPacket(start, timeout, rs.getAir(), gs.getRoundNumber()));
        ModNetworking.sendTo(restrained, new S2CWheelSyncPacket(start, timeout, rs.getAir(), rs.getRoundNumber()));
    }

    // --- Faint / end --------------------------------------------------------

    /**
     * Set a fainted player's hitbox to match the body that actually renders
     * lying down. Vanilla's {@code Player.getDimensions(Pose.SLEEPING)} returns
     * a tiny 0.2x0.2 box (the size exists for beds), so a fainted body would
     * otherwise be unhittable and the F3+B box wouldn't cover it.
     *
     * <p>The sleeping render for a non-bed sleeper is fixed by vanilla: it
     * rotates the model with {@code getBedOrientation() == UP}, laying the body
     * flat along the X axis — about 1.8 long and 0.6 wide/tall, centered ~0.6
     * blocks west of the entity position (the entity position itself is the
     * body's mid-point from {@code LivingEntity.setPosToBed}). This AABB tracks
     * that exactly.
     *
     * <p>Applied every tick on the server ({@link #onPlayerTick},
     * {@link #startFaint}, {@code TestDummyEntity.tick}) and on the client
     * ({@code ClientInputHandler.onClientTick}) because vanilla recomputes the
     * bounding box from the pose's dimensions whenever the SLEEPING pose syncs.
     */
    public static void applyFaintedHitbox(Player player) {
        Vec3 pos = player.position();
        player.setBoundingBox(new AABB(
                pos.x - 1.5, pos.y - 0.3, pos.z - 0.3,
                pos.x + 0.3, pos.y + 0.3, pos.z + 0.3));
    }

    /**
     * Push the restrained player's current air to BOTH participants so their
     * client air bars stay accurate. The bar is otherwise only updated on round
     * events ({@code S2CWheelSyncPacket} / {@code S2CRoundResultPacket}); the
     * passive drain and an imminent faint happen between those, leaving the bar
     * stale. Sends via {@link ModNetworking#sendTo(Player, Object)}, which
     * no-ops for a {@code TestDummyEntity} participant (no client connection).
     */
    private static void syncAir(Player restrained, ChokeholdState rs, int air) {
        long start = rs.getRoundStartTick();
        long timeout = rs.getRoundTimeoutTick();
        // Same round number for both sides — a mid-round air refresh doesn't
        // change the wheel speed.
        ModNetworking.sendTo(restrained, new S2CWheelSyncPacket(start, timeout, air, rs.getRoundNumber()));
        Player partner = resolvePartner(restrained, rs.getPartnerId());
        if (partner != null) {
            ModNetworking.sendTo(partner, new S2CWheelSyncPacket(start, timeout, air, rs.getRoundNumber()));
        }
    }

    private static void startFaint(Player restrained, ChokeholdState rs) {
        FaintedState fs = FaintedStateProvider.getOrNull(restrained);
        if (fs == null) return;

        int duration = ChokeholdConfig.FAINT_DURATION_SECONDS.get() * 20;
        fs.setFainted(true);
        fs.setFaintTicksRemaining(duration);

        // Use startSleeping to set SLEEPING_POS + isSleeping state (for client render & isSleeping()),
        // then manually correct Y so body rests on block surface (not block center).
        BlockPos floor = restrained.blockPosition().below(); // block player is standing ON
        restrained.startSleeping(floor);
        // Fix position: sleeping eye height ≈0.2, so body bottom = posY - 0.2.
        // Want body bottom = floor.getY() + 1.0 (top of floor block). So posY = floor.getY() + 1.2.
        // +1 Y level higher for visibility.
        restrained.setPos(restrained.getX(), floor.getY() + 2.0, restrained.getZ());
        // Apply lying-body hitbox immediately (vanilla collapses SLEEPING to 0.2x0.2).
        applyFaintedHitbox(restrained);
        // Hardcore-revival style: drop the fainted player to half a heart so the
        // state reads unmistakably and they are genuinely at death's door — one
        // hit away from dying (faintedInvulnerable defaults to false).
        restrained.setHealth(1.0f);
        // Ensure both clients see the bar hit 0 the instant the faint lands.
        // The passive-drain faint path reaches here with NO preceding air sync
        // (unlike the miss / round-loss paths, which already sent a round result
        // with airAfter=0) — without this the bar would freeze at its last
        // synced value (e.g. 89/98) while the player lies fainted.
        syncAir(restrained, rs, 0);
        // The victim's client shows the K.O. screen; everyone tracking the victim
        // (relevant for a fainted test dummy) gets the capability mirror so the
        // body stays down on their client too.
        ModNetworking.sendTo(restrained, new S2CFaintStartPacket(restrained.getUUID(), duration));
        ModNetworking.sendToTracking(restrained, new S2CFaintStartPacket(restrained.getUUID(), duration));

        // End the chokehold but keep the fainted state running independently.
        Player chokeholder = null;
        if (rs.getPartnerId() != null) {
            chokeholder = resolvePartner(restrained, rs.getPartnerId());
            if (chokeholder != null) {
                endChokehold(chokeholder, ChokeholdStateProvider.getOrNull(chokeholder), S2CChokeholdEndPacket.REASON_FAINT);
            }
        }
        endChokehold(restrained, rs, S2CChokeholdEndPacket.REASON_FAINT);
    }

    private static void endChokehold(Player player, ChokeholdState gs, String reason) {
        if (gs == null) return;
        long now = player.level().getGameTime();
        UUID partnerId = gs.getPartnerId();
        boolean wasChokeholder = gs.isChokeholder(); // Capture BEFORE clear()
        System.out.println("[ChokeholdMod DEBUG] endChokehold: player=" + player.getName().getString() + "(" + player.getUUID() + ") partnerId=" + partnerId + " wasChokeholder=" + wasChokeholder + " now=" + now);
        // Clear chokehold state.
        gs.clear();
        // Global anti-spam: 1 second cooldown from chokehold END for BOTH sides.
        int globalCooldownTicks = 20; // 1 second = 20 ticks
        gs.setChokeholdCooldownTicks(globalCooldownTicks);
        // Per-partner cooldown ONLY on the chokeholder side (10s default).
        // The chokeholder can't chokehold the same target for 10s, but the restrained
        // player CAN chokehold the chokeholder back immediately.
        if (partnerId != null && wasChokeholder) {
            gs.setPartnerCooldown(partnerId, now);
        }
        ModNetworking.sendTo(player, new S2CChokeholdEndPacket(reason));
    }

    private static Player resolvePartner(Player self, UUID id) {
        if (id == null) return null;
        if (!(self.level() instanceof ServerLevel sl)) return null;
        Entity e = sl.getEntity(id);
        if (e instanceof Player) return (Player) e;
        return null;
    }

    // --- Sleeping-state auto-wake cancels -----------------------------------

    /**
     * A fainted player is held in a vanilla "sleeping" state (see
     * {@link #startFaint}). Vanilla auto-wakes any sleeper whose feet aren't
     * in a bed via {@code SleepingLocationCheckEvent}, fired from
     * {@code LivingEntity.tick()} on BOTH sides (server + client). Setting the
     * result to ALLOW for fainted players keeps the body down for the full
     * configured faint duration — without it, the pose fights vanilla's wake
     * logic every tick and flickers. Capabilities attach on both sides, so the
     * client-side location check finds the faint state too.
     */
    @SubscribeEvent
    public static void onSleepingLocationCheck(SleepingLocationCheckEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        FaintedState fs = FaintedStateProvider.getOrNull(p);
        if (fs != null && fs.isFainted()) event.setResult(Event.Result.ALLOW);
    }

    /**
     * Second vanilla auto-wake path: {@code Player.tick()} stops a player's
     * sleep when it's daytime (fired server-side only via
     * {@code SleepingTimeCheckEvent}). A fainted player must not wake at day —
     * ALLOW keeps them down for the full duration. This also covers
     * {@link TestDummyEntity} instances, which extend {@link Player}.
     */
    @SubscribeEvent
    public static void onSleepingTimeCheck(SleepingTimeCheckEvent event) {
        FaintedState fs = FaintedStateProvider.getOrNull(event.getEntity());
        if (fs != null && fs.isFainted()) event.setResult(Event.Result.ALLOW);
    }

    // --- Damage / combat gating --------------------------------------------

    @SubscribeEvent
    public static void onLivingHurt(LivingHurtEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        FaintedState fs = FaintedStateProvider.getOrNull(p);
        if (fs != null && fs.isFainted() && ChokeholdConfig.FAINTED_INVULNERABLE.get()) {
            event.setCanceled(true);
        }
    }

    /**
     * A fainted player who gets finished off (they're at half a heart) is freed
     * from the K.O. state so the respawn is clean — no lingering "fainted" flag
     * forcing them back onto the ground. Also tears down any active chokehold the
     * dead player was in.
     */
    @SubscribeEvent
    public static void onLivingDeath(LivingDeathEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        FaintedState fs = FaintedStateProvider.getOrNull(p);
        if (fs != null && fs.isFainted()) {
            fs.clear();
            ModNetworking.sendTo(p, new S2CFaintEndPacket(p.getUUID()));
            ModNetworking.sendToTracking(p, new S2CFaintEndPacket(p.getUUID()));
        }
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(p);
        if (gs != null && gs.isActive()) {
            Player partner = resolvePartner(p, gs.getPartnerId());
            endChokehold(p, gs, S2CChokeholdEndPacket.REASON_DISCONNECT);
            if (partner != null) {
                endChokehold(partner, ChokeholdStateProvider.getOrNull(partner), S2CChokeholdEndPacket.REASON_DISCONNECT);
            }
        }
    }

    @SubscribeEvent
    public static void onPlayerAttack(AttackEntityEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        FaintedState fs = FaintedStateProvider.getOrNull(p);
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(p);
        if ((fs != null && fs.isFainted()) || (gs != null && gs.isActive() && !gs.isChokeholder())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onCriticalHit(CriticalHitEvent event) {
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        FaintedState fs = FaintedStateProvider.getOrNull(p);
        if (fs != null && fs.isFainted()) event.setDamageModifier(0.0F);
    }

    // --- Item use / drop / chat mute ---------------------------------------

    @SubscribeEvent
    public static void onItemUse(PlayerInteractEvent.RightClickItem event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getEntity() instanceof Player)) return;
        Player p = (Player) event.getEntity();
        FaintedState fs = FaintedStateProvider.getOrNull(p);
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(p);
        if (fs != null && fs.isFainted()) {
            event.setCanceled(true);
            return;
        }
        if (gs != null && gs.isActive() && !gs.isChokeholder()) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onItemToss(ItemTossEvent event) {
        if (event.getEntity().level().isClientSide()) return;
        Player sp = event.getPlayer();
        FaintedState fs = FaintedStateProvider.getOrNull(sp);
        if (fs != null && fs.isFainted()) event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onServerChat(ServerChatEvent event) {
        ServerPlayer p = event.getPlayer();
        FaintedState fs = FaintedStateProvider.getOrNull(p);
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(p);
        boolean mute = (fs != null && fs.isFainted())
                || (gs != null && gs.isRestrained());
        if (mute) {
            event.setCanceled(true);
            p.sendSystemMessage(Component.literal("You can't speak right now.").withStyle(ChatFormatting.GRAY));
        }
    }

    // --- Logout cleanup ----------------------------------------------------

    @SubscribeEvent
    public static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p)) return;
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(p);
        if (gs != null && gs.isActive() && gs.getPartnerId() != null) {
            Player partner = resolvePartner(p, gs.getPartnerId());
            if (partner != null) {
                endChokehold(partner, ChokeholdStateProvider.getOrNull(partner), S2CChokeholdEndPacket.REASON_DISCONNECT);
            }
        }
    }

    // --- Manual release (sneak + release key handled in client; here we just
    //     expose a server-callable helper so future commands can call it). ---

    public static boolean tryManualRelease(Player chokeholder) {
        if (!ChokeholdConfig.ALLOW_MANUAL_RELEASE.get()) return false;
        ChokeholdState gs = ChokeholdStateProvider.getOrNull(chokeholder);
        if (gs == null || !gs.isActive() || !gs.isChokeholder()) return false;
        Player partner = resolvePartner(chokeholder, gs.getPartnerId());
        endChokehold(chokeholder, gs, S2CChokeholdEndPacket.REASON_MANUAL);
        if (partner != null) {
            endChokehold(partner, ChokeholdStateProvider.getOrNull(partner), S2CChokeholdEndPacket.REASON_MANUAL);
        }
        return true;
    }
}