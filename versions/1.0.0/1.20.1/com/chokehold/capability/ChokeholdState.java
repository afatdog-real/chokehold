package com.chokehold.chokehold.capability;

import com.chokehold.chokehold.config.ChokeholdConfig;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player chokehold state. Persists across ticks on the server and is the source of truth
 * for the duel loop. Clients do not store their own copy; they receive state from packets.
 *
 * <h2>State machine</h2>
 * A player is either <i>idle</i> ({@code partnerId == null}, {@link #getPhase()} ==
 * {@link RoundPhase#IDLE}) or <i>in a chokehold</i> with a partner. While grappling,
 * the phase cycles:
 *
 * <pre>
 *   IDLE ── startChokehold() ──▶ WHEEL
 *                              │
 *              ┌─── win: open GASP_QTE ───┐
 *              │                          │
 *              ▼                          ▼
 *   GASP_QTE ──── fail/timed out ──▶ WHEEL
 *   GASP_QTE ──── streak hit ──────▶ IDLE  (escape)
 *   WHEEL    ──── air reaches 0 ──▶ IDLE  (faint triggers chokehold-end)
 * </pre>
 *
 * <p>The phase is a server-only concept; the client mirrors it through
 * {@code S2CWheelSyncPacket} / {@code S2CGaspQTEPacket} so the HUD can switch
 * between the wheel and the "GASP! Press SPACE" prompt.
 *
 * <h2>Capability plumbing</h2>
 * Stored as a per-Player capability; see
 * {@link ChokeholdStateProvider} for registration and
 * {@link com.chokehold.chokehold.capability.CapabilityAttachers} for attachment.
 */
public final class ChokeholdState {
    /** UUID of the partner player, or null if not grappling. */
    private UUID partnerId;

    /** True if this player is the chokeholder; false if they're the restrained player. */
    private boolean isChokeholder;

    /** Current air (0..AIR_MAX). Tracked only on the restrained player. */
    private int air;

    /** Streak counter — incremented each time the restrained player wins a round. */
    private int consecutiveWinStreak;

    /** Current phase of the duel. */
    private RoundPhase phase = RoundPhase.IDLE;

    /** Game-time at which the active round began. Server-side ticks. */
    private long roundStartTick;

    /** Game-time at which the current round will auto-resolve if no presses arrive. */
    private long roundTimeoutTick;

    /**
     * Server game-time at which this player pressed Space during the current
     * WHEEL round, or -1 if they haven't pressed yet. A round resolves only once
     * BOTH players have recorded a press (or the round times out), so this is
     * how the "wait for both presses" QTE model tracks each side. Reset to -1
     * at the start of every round. Never serialized — a logout ends the chokehold.
     */
    private long pressTick = -1;

    /** Game-time at which a gasp window opened, if any. */
    private long gaspOpenTick;

    /** Game-time at which a gasp window will close. */
    private long gaspCloseTick;

    /** Server game-time when the current chokehold started (for anti-spam cooldown from START). */
    private long chokeholdStartTick;

    /** Cooldown ticks remaining since chokehold START before another can start. */
    private int chokeholdCooldownTicks;

    /** Per-partner cooldown end times (ticks since server start). Map<partnerUUID, endTick>. */
    private final Map<UUID, Long> partnerCooldownEndTicks = new HashMap<>();

    /**
     * 1-based count of wheel rounds completed in this chokehold. Drives the needle
     * acceleration — the wheel rotates {@code 1.5^(roundNumber-1)}× faster than
     * the base {@code WHEEL_ROTATION_TICKS_PER_REV} on round 1. Incremented on
     * both sides whenever a new wheel round begins (including both-miss re-rolls).
     * Never serialized: a new chokehold always starts fresh at round 1.
     */
    private int roundNumber = 1;

    /**
     * True once the "passed" penalty has fired for the current wheel round — i.e.
     * the needle completed a full revolution while this side had not pressed.
     * Gates the penalty so it can only apply once per round, no matter how many
     * extra revolutions the needle spins. Reset whenever a new round begins.
     */
    private boolean passedApplied;

    public ChokeholdState() {
        clear();
    }

    public void clear() {
        partnerId = null;
        isChokeholder = false;
        air = 0;
        consecutiveWinStreak = 0;
        phase = RoundPhase.IDLE;
        roundStartTick = 0;
        roundTimeoutTick = 0;
        pressTick = -1;
        gaspOpenTick = 0;
        gaspCloseTick = 0;
        chokeholdStartTick = 0;
        chokeholdCooldownTicks = 0;
        roundNumber = 1;
        passedApplied = false;
        // Note: partnerCooldownEndTicks is NOT cleared here — per-partner cooldowns
        // persist across chokeholds and only expire with time.
    }

    public UUID getPartnerId() { return partnerId; }
    public void setPartnerId(UUID partnerId) { this.partnerId = partnerId; }

    public boolean isChokeholder() { return isChokeholder; }
    public void setChokeholder(boolean chokeholder) { isChokeholder = chokeholder; }

    public boolean isActive() { return partnerId != null; }

    public boolean isRestrained() { return partnerId != null && !isChokeholder; }

    public int getAir() { return air; }
    public void setAir(int air) { this.air = air; }

    public int getConsecutiveWinStreak() { return consecutiveWinStreak; }
    public void resetStreak() { this.consecutiveWinStreak = 0; }
    public void incrementStreak() { this.consecutiveWinStreak++; }

    public RoundPhase getPhase() { return phase; }
    public void setPhase(RoundPhase phase) { this.phase = phase; }

    public long getRoundStartTick() { return roundStartTick; }
    public void setRoundStartTick(long t) { this.roundStartTick = t; }

    public long getRoundTimeoutTick() { return roundTimeoutTick; }
    public void setRoundTimeoutTick(long t) { this.roundTimeoutTick = t; }

    public long getPressTick() { return pressTick; }
    public void setPressTick(long t) { this.pressTick = t; }

    public long getGaspOpenTick() { return gaspOpenTick; }
    public void setGaspOpenTick(long t) { this.gaspOpenTick = t; }

    public long getGaspCloseTick() { return gaspCloseTick; }
    public void setGaspCloseTick(long t) { this.gaspCloseTick = t; }

    public int getChokeholdCooldownTicks() { return chokeholdCooldownTicks; }
    public void setChokeholdCooldownTicks(int t) { this.chokeholdCooldownTicks = t; }

    /**
     * Computes the remaining anti-spam cooldown based on time since chokehold START.
     * Returns ticks remaining until another chokehold can be initiated.
     */
    public int getRemainingAntiSpamCooldown(long currentTime) {
        if (chokeholdStartTick <= 0) return 0;
        long elapsed = currentTime - chokeholdStartTick;
        int cooldownTicks = ChokeholdConfig.CHOKEHOLD_COOLDOWN_SECONDS.get() * 20;
        int remaining = (int) Math.max(0, cooldownTicks - elapsed);
        return remaining;
    }

    public long getChokeholdStartTick() { return chokeholdStartTick; }
    public void setChokeholdStartTick(long t) { this.chokeholdStartTick = t; }

    /**
     * Check if a cooldown is active against a specific partner.
     * @param partnerId The UUID of the partner to check
     * @param currentTime Current server game time
     * @return Ticks remaining, or 0 if no cooldown against this partner
     */
    public int getPartnerCooldownRemaining(UUID partnerId, long currentTime) {
        if (partnerId == null) return 0;
        Long endTick = partnerCooldownEndTicks.get(partnerId);
        if (endTick == null) {
            System.out.println("[ChokeholdMod DEBUG] getPartnerCooldownRemaining: partnerId=" + partnerId + " NOT FOUND in map. Map keys: " + partnerCooldownEndTicks.keySet());
            return 0;
        }
        int remaining = (int) Math.max(0, endTick - currentTime);
        System.out.println("[ChokeholdMod DEBUG] getPartnerCooldownRemaining: partnerId=" + partnerId + " endTick=" + endTick + " currentTime=" + currentTime + " remaining=" + remaining);
        return remaining;
    }

    /**
     * Set a cooldown against a specific partner after a chokehold ends.
     * @param partnerId The UUID of the partner
     * @param currentTime Current server game time
     */
    public void setPartnerCooldown(UUID partnerId, long currentTime) {
        if (partnerId == null) return;
        int cooldownTicks = ChokeholdConfig.PARTNER_COOLDOWN_SECONDS.get() * 20;
        long endTick = currentTime + cooldownTicks;
        partnerCooldownEndTicks.put(partnerId, endTick);
        System.out.println("[ChokeholdMod DEBUG] setPartnerCooldown: partnerId=" + partnerId + " endTick=" + endTick + " currentTime=" + currentTime + " cooldownTicks=" + cooldownTicks);
    }

    /**
     * Clear cooldown for a specific partner (e.g., on logout or manual clear).
     */
    public void clearPartnerCooldown(UUID partnerId) {
        if (partnerId != null) {
            partnerCooldownEndTicks.remove(partnerId);
        }
    }

    public void tickCooldown() {
        // Kept for backward compatibility, but anti-spam now uses chokeholdStartTick
        if (chokeholdCooldownTicks > 0) {
            System.out.println("[ChokeholdMod DEBUG] tickCooldown: before=" + chokeholdCooldownTicks);
            chokeholdCooldownTicks--;
            System.out.println("[ChokeholdMod DEBUG] tickCooldown: after=" + chokeholdCooldownTicks);
        }
    }

    public int getRoundNumber() { return roundNumber; }
    public void setRoundNumber(int roundNumber) { this.roundNumber = roundNumber; }

    public boolean isPassedApplied() { return passedApplied; }
    public void setPassedApplied(boolean passedApplied) { this.passedApplied = passedApplied; }

    /** Serialize to NBT so it survives player logout/rejoin. */
    public void writeToNbt(CompoundTag tag) {
        if (partnerId == null) {
            tag.putBoolean("active", false);
            // Still write partner cooldowns even when not active
            if (!partnerCooldownEndTicks.isEmpty()) {
                ListTag cooldownList = new ListTag();
                long currentTime = 0; // We don't have server time here, store end ticks directly
                for (Map.Entry<UUID, Long> entry : partnerCooldownEndTicks.entrySet()) {
                    CompoundTag ct = new CompoundTag();
                    ct.putUUID("partner", entry.getKey());
                    ct.putLong("endTick", entry.getValue());
                    cooldownList.add(ct);
                }
                tag.put("partnerCooldowns", cooldownList);
            }
            return;
        }
        tag.putBoolean("active", true);
        tag.putUUID("partnerId", partnerId);
        tag.putBoolean("isChokeholder", isChokeholder);
        tag.putInt("air", air);
        tag.putInt("streak", consecutiveWinStreak);
        tag.putInt("phase", phase.ordinal());
        tag.putLong("roundStart", roundStartTick);
        tag.putLong("roundTimeout", roundTimeoutTick);
        tag.putLong("gaspOpen", gaspOpenTick);
        tag.putLong("gaspClose", gaspCloseTick);
        tag.putLong("chokeholdStart", chokeholdStartTick);
        tag.putInt("cooldown", chokeholdCooldownTicks);

        // Serialize partner cooldowns
        if (!partnerCooldownEndTicks.isEmpty()) {
            ListTag cooldownList = new ListTag();
            for (Map.Entry<UUID, Long> entry : partnerCooldownEndTicks.entrySet()) {
                CompoundTag ct = new CompoundTag();
                ct.putUUID("partner", entry.getKey());
                ct.putLong("endTick", entry.getValue());
                cooldownList.add(ct);
            }
            tag.put("partnerCooldowns", cooldownList);
        }
    }

    public void readFromNbt(CompoundTag tag) {
        clear();
        if (!tag.getBoolean("active")) {
            // Still load partner cooldowns if present
            if (tag.contains("partnerCooldowns")) {
                ListTag cooldownList = tag.getList("partnerCooldowns", 10); // 10 = TAG_COMPOUND
                for (int i = 0; i < cooldownList.size(); i++) {
                    CompoundTag ct = cooldownList.getCompound(i);
                    UUID pid = ct.getUUID("partner");
                    long endTick = ct.getLong("endTick");
                    partnerCooldownEndTicks.put(pid, endTick);
                }
            }
            return;
        }
        partnerId = tag.getUUID("partnerId");
        isChokeholder = tag.getBoolean("isChokeholder");
        air = tag.getInt("air");
        consecutiveWinStreak = tag.getInt("streak");
        phase = RoundPhase.values()[Math.max(0, Math.min(RoundPhase.values().length - 1, tag.getInt("phase")))];
        roundStartTick = tag.getLong("roundStart");
        roundTimeoutTick = tag.getLong("roundTimeout");
        gaspOpenTick = tag.getLong("gaspOpen");
        gaspCloseTick = tag.getLong("gaspClose");
        chokeholdStartTick = tag.getLong("chokeholdStart");
        chokeholdCooldownTicks = tag.getInt("cooldown");

        // Load partner cooldowns
        if (tag.contains("partnerCooldowns")) {
            ListTag cooldownList = tag.getList("partnerCooldowns", 10);
            for (int i = 0; i < cooldownList.size(); i++) {
                CompoundTag ct = cooldownList.getCompound(i);
                UUID pid = ct.getUUID("partner");
                long endTick = ct.getLong("endTick");
                partnerCooldownEndTicks.put(pid, endTick);
            }
        }
    }

    /**
     * The phase of the duel this player is in. Mirrored on the client by the
     * most recent {@code S2CWheelSyncPacket} / {@code S2CGaspQTEPacket}.
     *
     * <ul>
     *   <li>{@link #IDLE} — no active chokehold.</li>
     *   <li>{@link #WHEEL} — wheel minigame round in progress; both players can
     *       press Space.</li>
     *   <li>{@link #GASP_QTE} — restrained player has a short window to press
     *       Space to gain air and increment the escape streak.</li>
     *   <li>{@link #RESOLVED} — round result has been computed; reserved for
     *       future use (e.g. an explicit "round over" state between WHEEL
     *       rounds). Currently transitions straight back to WHEEL.</li>
     * </ul>
     */
    public enum RoundPhase {
        IDLE,
        WHEEL,
        GASP_QTE,
        RESOLVED
    }
}