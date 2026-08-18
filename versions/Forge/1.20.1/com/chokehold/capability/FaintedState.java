package com.chokehold.chokehold.capability;

import net.minecraft.nbt.CompoundTag;

/**
 * Tracks the per-player faint state. Persists independently of ChokeholdState — a player
 * can be fainted without an active chokehold (e.g. faint triggers chokehold-end, but faint
 * itself remains for its full duration).
 *
 * <p>While {@link #isFainted()} is true:
 * <ul>
 *   <li>The player is forced into {@code Pose.SLEEPING} by
 *       {@code ChokeholdEventHandlers.onPlayerTick} (and
 *       {@code TestDummyEntity.tick} for dummies).</li>
 *   <li>Horizontal velocity is zeroed, so they can't drift.</li>
 *   <li>Damage is cancelled (when {@code faintedInvulnerable = true}).</li>
 *   <li>Attacks, item use, item toss, and chat are all cancelled.</li>
 *   <li>Outgoing Simple Voice Chat packets are dropped
 *       ({@code ChokeholdVoicechatPlugin.onMicrophone}).</li>
 * </ul>
 *
 * <p>On wake, the server applies {@code WAKE_DEBUFF_SECONDS} of Slowness II and
 * Weakness II before clearing this state.
 */
public final class FaintedState {
    private boolean isFainted;
    /** Ticks remaining until wake. Decremented once per server tick by {@link #tickFaint()}. */
    private int faintTicksRemaining;

    public FaintedState() {
        clear();
    }

    /** Reset to "not fainted" with zero remaining ticks. Safe to call repeatedly. */
    public void clear() {
        isFainted = false;
        faintTicksRemaining = 0;
    }

    public boolean isFainted() { return isFainted; }
    public void setFainted(boolean f) { isFainted = f; }

    public int getFaintTicksRemaining() { return faintTicksRemaining; }

    /**
     * Set the remaining duration. Clamped to {@code >= 0} so a misconfigured
     * negative value never produces an underflow in {@link #tickFaint()}.
     */
    public void setFaintTicksRemaining(int t) { faintTicksRemaining = Math.max(0, t); }

    /**
     * Decrement the remaining counter by one tick. When the counter reaches
     * zero, the faint state is automatically cleared. Called once per server
     * tick from {@code ChokeholdEventHandlers.tickPlayer} (and the dummy tick).
     */
    public void tickFaint() { if (faintTicksRemaining > 0) faintTicksRemaining--; if (faintTicksRemaining == 0) isFainted = false; }

    public void writeToNbt(CompoundTag tag) {
        tag.putBoolean("isFainted", isFainted);
        tag.putInt("remaining", faintTicksRemaining);
    }

    public void readFromNbt(CompoundTag tag) {
        isFainted = tag.getBoolean("isFainted");
        faintTicksRemaining = tag.getInt("remaining");
    }
}