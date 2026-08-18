package com.chokehold.chokehold.client;

import com.chokehold.chokehold.network.ModNetworking;
import com.chokehold.chokehold.network.ModNetworking.C2SSpacePressPacket;
import com.chokehold.chokehold.network.PacketHelper;
import com.chokehold.chokehold.network.ModNetworking.S2CFaintStartPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CGaspQTEPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CChokeholdEndPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CChokeholdStartPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CRoundResultPacket;
import com.chokehold.chokehold.network.ModNetworking.S2CWheelSyncPacket;

import java.util.UUID;

/**
 * Caches the most recent S2C payload for the local player. The HUD reads this
 * every frame to draw the wheel and air bar.
 *
 * We deliberately keep this in plain static fields (no capability) because the
 * HUD does not need to participate in the gameplay loop and does not need
 * server sync.
 */
public final class ClientChokeholdCache {
    public static boolean inChokehold;
    public static boolean isChokeholder;
    public static UUID partnerId;
    public static long roundStartTick;
    public static long roundTimeoutTick;
    public static int air;
    /** 1-based round counter; drives the wheel's 50%-per-round speed-up. */
    public static int roundNumber;

    public static int lastChokeholderAngle;
    public static int lastRestrainedAngle;
    public static int lastChokeholderValue;
    public static int lastRestrainedValue;
    public static String lastWinner = "";
    public static int lastStreak;
    public static long lastResultDisplayUntil;
    /** True once at least one round has resolved — gates the hit-marker dots. */
    public static boolean hasResult;

    /**
     * Rotational offset of the Z1→Z4 hit block for the round that the last
     * result came from. Captured in {@link #onRoundResult} — because
     * {@code S2CRoundResultPacket} always precedes the next round's
     * {@code S2CWheelSyncPacket} on the same channel, {@link #roundStartTick}
     * and {@link #roundNumber} still describe the just-ended round there, so
     * the banner can label which zone each side actually hit.
     */
    public static int lastResultZoneOffset;

    public static boolean gaspOpen;
    public static long gaspOpenTick;
    public static long gaspCloseTick;

    public static boolean fainted;
    public static int faintTicksRemaining;

    /**
     * Client-side Space-press debounce, used by the modal
     * {@link ChokeholdQTEScreen} to ensure a single physical press is never sent
     * as two C2S packets.
     */
    public static long spaceCooldownTick;

    // --- Pre-chokehold Space-capture window ----------------------------------
    // Between the player sneaking + right-clicking (the empty-handed chokehold
    // initiation) and the S2C start packet landing, the QTE screen isn't open
    // yet, so a Space press would be routed to a vanilla jump and lost. This
    // window queues that press and replays it in onChokeholdStart so the very
    // first Space of a duel always counts.

    /** True for a short window after the player initiates a chokehold (sneak +
     *  empty hand + right-click), while the server is still authorising the
     *  chokehold (before {@link #inChokehold} flips). */
    public static boolean chokeholdArmed;
    /** Client tick at which the armed window expires. */
    public static long armedUntilTick;
    /** A jump-key press captured while armed but before the chokehold went live. */
    public static boolean pendingSpacePress;
    /** Client tick of the pending press, sent verbatim once the round is live. */
    public static long pendingSpacePressTick;

    /**
     * Arm the pre-chokehold Space-capture window. Called from
     * {@code ClientInputHandler.onInteractionKey} when the player sneaks +
     * right-clicks with an empty hand — the same action that initiates a chokehold
     * server-side (see {@link ChokeholdEventHandlers#onRightClickEntity}).
     * The window is short (a chokehold authorises within a tick or two); it
     * exists only so an immediate Space press isn't routed to a vanilla jump
     * and dropped.
     */
    public static void armForChokehold() {
        chokeholdArmed = true;
        armedUntilTick = clientTick() + 20;
    }

    /**
     * Handle a jump-key press on the client. Returns {@code true} if the press
     * was consumed by the chokehold (sent immediately for a live chokehold, or
     * queued for an imminent one) — the caller must then un-press the vanilla
     * jump key so it can't also launch a jump. Returns {@code false} for a
     * normal jump outside any chokehold.
     */
    public static boolean onJumpKeyPressed() {
        long now = clientTick();
        if (inChokehold || gaspOpen) {
            pressSpace();
            return true;
        }
        if (chokeholdArmed) {
            pendingSpacePress = true;
            pendingSpacePressTick = now;
            return true;
        }
        return false;
    }

    /**
     * Handle {@code S2CChokeholdStartPacket}: turn on the HUD and remember which
     * role this client is playing (chokeholder vs restrained) and who the partner
     * is (used by the "muted-mic" particle cue in
     * {@code ClientInputHandler.onRenderLevel}).
     */
    public static void onChokeholdStart(S2CChokeholdStartPacket p) {
        inChokehold = true;
        isChokeholder = p.isChokeholder();
        partnerId = p.partnerId();
        // A fresh chokehold starts at round 1 (base wheel speed); the wheel sync
        // packet that immediately follows carries the authoritative value.
        roundNumber = 1;
        spaceCooldownTick = 0;
        hasResult = false;
        // Replay a jump-key press that landed in the pre-chokehold window (before
        // this S2C packet was processed / the QTE screen opened). Without this
        // the very first Space of a duel jumps instead of pressing, and the
        // player has to press a second time for the round to register.
        if (pendingSpacePress) {
            pendingSpacePress = false;
            ModNetworking.sendToServer(new C2SSpacePressPacket(pendingSpacePressTick));
        }
        chokeholdArmed = false;
        openQTEScreen();
    }

    /**
     * Open the modal QTE screen that captures all input for the whole chokehold.
     * Guarded so we never hijack an unrelated screen the user is interacting
     * with (chokeholds only start via a world right-click, but belt-and-braces).
     */
    private static void openQTEScreen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof ChokeholdQTEScreen) return;
        if (mc.screen != null) return;
        mc.setScreen(new ChokeholdQTEScreen());
    }

    /**
     * Force-close the QTE screen. The screen's own {@code tick()} also closes
     * itself once {@link #inChokehold} clears; doing it directly here removes the
     * one-tick lag and guarantees a clean handoff when the chokehold ends.
     */
    private static void closeQTEScreen() {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof ChokeholdQTEScreen) mc.setScreen(null);
    }

    /**
     * Handle {@code S2CWheelSyncPacket}: store the server's round-start tick,
     * timeout tick and round number so the HUD can compute the needle angle
     * locally each frame from
     * {@code PacketHelper.needleAngleAt(now, roundStartTick, roundNumber)} —
     * no per-tick packet needed. Any in-flight GASP QTE is cancelled (a new
     * wheel round always supersedes a prior gasp).
     */
    public static void onWheelSync(S2CWheelSyncPacket p) {
        roundStartTick = p.roundStartTick();
        roundTimeoutTick = p.roundTimeoutTick();
        air = p.air();
        roundNumber = p.roundNumber();
        gaspOpen = false;
    }

    /**
     * Handle {@code S2CRoundResultPacket}: cache the last round's angles and
     * the winner so the {@code drawResultBanner} helper can render the
     * "Chokeholder wins round" / "Restrained player wins round (streak N)" text.
     * The banner shows for 30 ticks (~1.5s).
     */
    public static void onRoundResult(S2CRoundResultPacket p) {
        lastChokeholderAngle = p.chokeholderAngle();
        lastRestrainedAngle = p.restrainedAngle();
        lastChokeholderValue = p.chokeholderValue();
        lastRestrainedValue = p.restrainedValue();
        lastWinner = p.winner();
        air = p.airAfter();
        lastStreak = p.streak();
        hasResult = true;
        // The result belongs to the round that just ended, which is still the
        // current roundStartTick/roundNumber (the next round's wheel sync is
        // queued after this packet on the same channel). Capture its layout so
        // the banner labels the zones the recorded angles landed in.
        lastResultZoneOffset = PacketHelper.zoneOffsetDegrees(roundStartTick, roundNumber);
        // Display the result banner + hit markers for ~1.5s. The tick time is read from the client world.
        lastResultDisplayUntil = clientTick() + 30;
    }

    /**
     * Handle {@code S2CGaspQTEPacket}: arm the GASP prompt. The HUD's
     * {@code drawGaspPrompt} helper will show "GASP! Press SPACE (N ticks)"
     * for as long as {@code clientTick()} is in {@code [openTick, closeTick]}.
     */
    public static void onGasp(S2CGaspQTEPacket p) {
        gaspOpen = true;
        gaspOpenTick = p.openTick();
        gaspCloseTick = p.closeTick();
        // Reset space debounce so a gasp press isn't blocked by a recent wheel-round press.
        spaceCooldownTick = 0;
    }

    /**
     * Handle {@code S2CFaintStartPacket}: flip on the faint state and replace
     * the QTE screen with the modal {@link ChokeholdFaintedScreen}. A faint always
     * ends the chokehold, so the player is no longer pressing the wheel — they're
     * incapacitated on the ground until the duration elapses or they're killed.
     */
    public static void onFaintStart(S2CFaintStartPacket p) {
        fainted = true;
        faintTicksRemaining = p.durationTicks();
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen == null || mc.screen instanceof ChokeholdQTEScreen) {
            mc.setScreen(new ChokeholdFaintedScreen());
        }
    }

    /**
     * Handle {@code S2CFaintEndPacket}: clear the faint state and close the K.O.
     * screen so the player wakes to the normal game.
     */
    public static void onFaintEnd() {
        fainted = false;
        faintTicksRemaining = 0;
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.screen instanceof ChokeholdFaintedScreen) mc.setScreen(null);
    }

    /**
     * Handle {@code S2CChokeholdEndPacket}: clear the per-chokehold state. The
     * faint state is intentionally not cleared here — a fainted player can
     * end the chokehold (faint triggers chokehold-end) but remains fainted for
     * the full configured duration.
     */
    public static void onChokeholdEnd(S2CChokeholdEndPacket p) {
        inChokehold = false;
        isChokeholder = false;
        partnerId = null;
        air = 0;
        gaspOpen = false;
        hasResult = false;
        closeQTEScreen();
    }

    public static long clientTick() {
        if (net.minecraft.client.Minecraft.getInstance().level == null) return 0;
        return net.minecraft.client.Minecraft.getInstance().level.getGameTime();
    }

    /**
     * Client-side entity lookup by UUID. 1.20.1's {@code ClientLevel} exposes no
     * public UUID getter, so iterate the rendering/ticking entities — this finds
     * real players AND {@code TestDummyEntity} copies (which are {@link Player}s
     * but not {@code AbstractClientPlayer}s, so they aren't in {@code players()}).
     * Returns {@code null} when the entity isn't present locally.
     */
    public static net.minecraft.world.entity.Entity findEntityByUuid(UUID id) {
        net.minecraft.client.Minecraft mc = net.minecraft.client.Minecraft.getInstance();
        if (mc.level == null || id == null) return null;
        for (net.minecraft.world.entity.Entity e : mc.level.entitiesForRendering()) {
            if (e.getUUID().equals(id)) return e;
        }
        return null;
    }

    /**
     * Debounced Space handler shared by the modal {@link ChokeholdQTEScreen} and
     * the {@link ClientInputHandler} key fallback. Sends a press only when a
     * wheel round or gasp window is open, and only the restrained side may
     * press during a gasp. The debounce guarantees a single physical press is
     * never sent as two C2S packets even when both the screen's keyPressed and
     * the raw {@code InputEvent.Key} observe the same press.
     */
    public static void pressSpace() {
        if (!inChokehold && !gaspOpen) return;
        if (gaspOpen && isChokeholder) return;
        long now = clientTick();
        if (now < spaceCooldownTick) return;
        spaceCooldownTick = now + 10;
        ModNetworking.sendToServer(new C2SSpacePressPacket(now));
    }

    public static void clear() {
        inChokehold = false;
        isChokeholder = false;
        partnerId = null;
        air = 0;
        roundNumber = 1;
        gaspOpen = false;
        hasResult = false;
        fainted = false;
        faintTicksRemaining = 0;
        spaceCooldownTick = 0;
        chokeholdArmed = false;
        pendingSpacePress = false;
    }
}