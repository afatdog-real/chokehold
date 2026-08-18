package com.chokehold.chokehold.network;

import com.chokehold.chokehold.config.ChokeholdConfig;

/**
 * Pure helpers for the wheel logic. Kept server-side; clients recompute the same
 * angles from the same config so the visual stays in sync without per-tick packets.
 *
 * <p>Because both sides share {@code ChokeholdConfig} (config is server-authoritative
 * but the file is shipped to clients), the same {@link #pointValueAt} and
 * {@link #needleAngleAt} functions applied to the same {@code roundStartTick}
 * and {@code roundNumber} (the latter synced via {@code S2CWheelSyncPacket})
 * produce the same result on both sides. The server uses these when resolving a
 * round (to compute the zone value from a player's press tick); the client uses
 * these every frame to draw the rotating needle on the HUD.
 */
public final class PacketHelper {

    /**
     * Ticks the needle sits motionless at the top (0°) when a fresh wheel round
     * begins. This is the visible "reset": when a round ends and the next one
     * starts, the arrow snaps back to 12 o'clock and visibly rests there for
     * this many ticks before sweeping again — instead of the reset being a
     * single frame too fast to perceive. Subtracted from the elapsed time on
     * BOTH the server (scoring + pass detection) and the client (rendering)
     * via {@link #needleAngleAt} / {@link #revolutionsCompleted}, so what the
     * player sees is exactly what gets scored.
     */
    public static final int ROUND_START_GRACE_TICKS = 6;

    /**
     * Hard cap on {@link #roundSpeedMultiplier} — the needle never spins faster
     * than 400% of the round-1 speed.
     */
    public static final double MAX_SPEED_MULTIPLIER = 4.0;

    // --- Zone layout --------------------------------------------------------
    // The four hit-zone edges (e1..e4) and the miss-band width, computed once
    // from ChokeholdConfig and reused by every zone helper. Created fresh each
    // call so a live-config change takes effect immediately.

    private record ZoneEdges(int e1, int e2, int e3, int e4) {
        int missWidth() { return 360 - e4; }
    }

    private static ZoneEdges zoneEdges() {
        int arc1 = ChokeholdConfig.ZONE1_ARC.get();
        int arc2 = ChokeholdConfig.ZONE2_ARC.get();
        int arc3 = ChokeholdConfig.ZONE3_ARC.get();
        int arc4 = ChokeholdConfig.ZONE4_ARC.get();
        int shrink = Math.max(0, ChokeholdConfig.ZONE_SHRINK_DEGREES.get());
        // Edge case: with shrink >= half of arc, a zone has no hit window at
        // all. Clamp shrink to half the smallest arc so every zone stays hittable.
        int minArc = Math.min(Math.min(arc1, arc2), Math.min(arc3, arc4));
        shrink = Math.min(shrink, minArc / 2);
        int e1 = arc1 - shrink;
        int s2 = e1 + 2 * shrink;
        int e2 = s2 + (arc2 - 2 * shrink);
        int s3 = e2 + 2 * shrink;
        int e3 = s3 + (arc3 - 2 * shrink);
        int s4 = e3 + 2 * shrink;
        int e4 = s4 + (arc4 - 2 * shrink);
        return new ZoneEdges(e1, e2, e3, e4);
    }

    /**
     * Rotational offset applied to the whole Z1→Z4 block. Randomised each
     * round from a deterministic hash of {@code roundStartTick + roundNumber}
     * so the server and every client rotate the hit area to the same fresh
     * position without a per-round packet.
     *
     * <p>The offset is constrained to {@code [1, missWidth]} where
     * {@code missWidth = 360 - e4} — the zone block {@code [offset, offset+e4)}
     * must never wrap past 360° and include 0°, because the miss band always
     * needs to cover the needle's reset position at 12 o'clock. Pressing at
     * the top then always lands in the miss band, forcing the player to wait
     * for the needle to sweep into the hit area.
     */
    public static int zoneOffsetDegrees(long roundStartTick, int roundNumber) {
        int missWidth = zoneEdges().missWidth();
        if (missWidth <= 0) return 0;
        long h = roundStartTick * 0x9E3779B97F4A7C15L
                ^ roundNumber * 0xBF58476D1CE4E5B9L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        return (int) ((h & 0xFFFFFFFFL) % missWidth) + 1;
    }

    /**
     * Calculate the point value of the zone that the needle is currently pointing at,
     * for the given round's rotated layout. Needle angle is in degrees, 0 = up
     * (12 o'clock), increasing clockwise.
     *
     * <p>Returns the configured zone value (1..N) when the needle falls inside
     * one of the four hit windows, or {@code 0} when it lands in a miss band —
     * either the {@code zoneShrinkDegrees}-wide gap between zones, or the plain
     * tail after the last zone when the arcs don't sum to 360 (the default).
     */
    public static int pointValueAt(int needleAngle, long roundStartTick, int roundNumber) {
        return switch (zoneIndexAt(needleAngle, roundStartTick, roundNumber)) {
            case 1 -> ChokeholdConfig.ZONE1_POINTS.get();
            case 2 -> ChokeholdConfig.ZONE2_POINTS.get();
            case 3 -> ChokeholdConfig.ZONE3_POINTS.get();
            case 4 -> ChokeholdConfig.ZONE4_POINTS.get();
            default -> 0;
        };
    }

    /**
     * Which hit-zone index (1-4) the needle is pointing at (after accounting for
     * the round's rotated layout), or {@code 0} for a miss (gap band between
     * zones, or the plain tail past the last zone). Single source of truth for
     * the zone windows — the server resolves with it and the client labels
     * "Z1..Z4 / MISS" with it, so they can't drift apart.
     */
    public static int zoneIndexAt(int needleAngle, long roundStartTick, int roundNumber) {
        return zoneIndexAtRotated(needleAngle, zoneOffsetDegrees(roundStartTick, roundNumber));
    }

    /**
     * Fixed-layout zone lookup against an explicit offset (degrees). Used by
     * the client's result banner, which must label a recorded press angle with
     * the layout of the round that press came from (captured as the
     * result-round's offset) rather than the current round's.
     */
    public static int zoneIndexAt(int needleAngle, int offset) {
        return zoneIndexAtRotated(needleAngle, offset);
    }

    /**
     * Zone lookup against an explicit rotation. Rotates the needle back by
     * {@code offset} so the zone block behaves as if it started at
     * {@code offset}° — preserving the 1→2→3→4 order while the block moves
     * around the ring each round.
     */
    private static int zoneIndexAtRotated(int needleAngle, int offset) {
        needleAngle = ((needleAngle - offset) % 360 + 360) % 360;
        ZoneEdges ze = zoneEdges();
        if (needleAngle < ze.e1()) return 1;
        if (needleAngle < ze.e2()) return 2;
        if (needleAngle < ze.e3()) return 3;
        if (needleAngle < ze.e4()) return 4;
        return 0;
    }

    /**
     * Angular distance (0..360) from a needle angle to the nearest scoring
     * region, for the given round's rotated layout. The scoring region is the
     * contiguous sweep of the rotated block — {@link #zoneIndexAt} is nonzero
     * there and {@code 0} past the block's tail (the miss band). Used by the
     * Normal/Impossible test dummies to pick the least-bad press tick when the
     * accelerated wheel is too fast for any discrete tick to land inside the
     * region.
     */
    public static int distanceToHitZone(int needleAngle, long roundStartTick, int roundNumber) {
        needleAngle = ((needleAngle - zoneOffsetDegrees(roundStartTick, roundNumber)) % 360 + 360) % 360;
        int e4 = zoneEdges().e4();
        return needleAngle < e4 ? 0 : needleAngle - e4;
    }

    // --- Needle angle -------------------------------------------------------

    /**
     * Compute the needle angle for a given tick relative to a round start, with
     * per-round acceleration: round {@code n} rotates {@code 1.5^(n-1)}× faster
     * than the base {@code WHEEL_ROTATION_TICKS_PER_REV}, capped at
     * {@link #MAX_SPEED_MULTIPLIER} (200%). Returns degrees in [0, 360).
     *
     * <p>The needle holds at 0° for the first {@link #ROUND_START_GRACE_TICKS}
     * ticks of every round (the visible reset at the top), then sweeps — so an
     * elapsed value below the grace always reads as angle 0.
     *
     * <p>Both the server (scoring presses in {@code resolveRound}) and the
     * client (drawing the needle every frame) call this with the same
     * {@code roundStartTick} and {@code roundNumber}, so the rendered needle and
     * the scored angle stay identical. The {@code % 360} wraps each revolution;
     * {@code 360 * elapsed * speed} stays far below 2^53 for any sane duel.
     */
    public static int needleAngleAt(long currentTick, long roundStartTick, int roundNumber) {
        long ticksPerRev = Math.max(1, ChokeholdConfig.WHEEL_ROTATION_TICKS_PER_REV.get());
        long elapsed = currentTick - roundStartTick - ROUND_START_GRACE_TICKS;
        if (elapsed < 0) elapsed = 0;
        double speed = roundSpeedMultiplier(roundNumber);
        int deg = (int) ((elapsed * 360.0 * speed) / ticksPerRev);
        return ((deg % 360) + 360) % 360;
    }

    /**
     * The needle's speed multiplier for that round — +75% per round
     * with base round 1 = 100%, round 2 = 175%, round 3 = 306%,
     * then clamped to {@link #MAX_SPEED_MULTIPLIER} (400%) from round 4 onward.
     */
    public static double roundSpeedMultiplier(int roundNumber) {
        return Math.min(MAX_SPEED_MULTIPLIER, 1.0 + 0.75 * (Math.max(1, roundNumber) - 1));
    }

    /**
     * Full needle revolutions completed since the round started (0 before the
     * first revolution finishes). Used by the "passed" penalty: the first time
     * this is ≥ 1 while a side hasn't pressed, that side passed the wheel. A
     * revolution finishes when the accumulated angle sweeps past 360°, which at
     * acceleration {@code speed} takes {@code ticksPerRev / speed} ticks.
     */
    public static int revolutionsCompleted(long elapsed, int roundNumber) {
        long ticksPerRev = Math.max(1, ChokeholdConfig.WHEEL_ROTATION_TICKS_PER_REV.get());
        double speed = roundSpeedMultiplier(roundNumber);
        // The grace ticks are spent motionless at the top — they don't count
        // toward a completed revolution, so the "passed" boundary lines up with
        // what the player sees (the needle finishing a real full turn).
        long e = Math.max(0, elapsed - ROUND_START_GRACE_TICKS);
        return (int) ((e * speed) / ticksPerRev);
    }
}
