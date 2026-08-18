package com.chokehold.chokehold.config;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * Configuration values for the chokehold mod.
 * All defaults are sensible for a typical survival PvP server.
 */
public final class ChokeholdConfig {
    public static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue AIR_MAX;
    public static final ForgeConfigSpec.IntValue AIR_LOSS_PER_ROUND;
    public static final ForgeConfigSpec.IntValue AIR_GAIN_PER_GASP;
    public static final ForgeConfigSpec.IntValue PASSED_PENALTY_AIR;
    public static final ForgeConfigSpec.IntValue STREAK_TO_ESCAPE;
    public static final ForgeConfigSpec.IntValue CHOKEHOLD_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue PARTNER_COOLDOWN_SECONDS;
    public static final ForgeConfigSpec.IntValue GASP_WINDOW_TICKS;
    public static final ForgeConfigSpec.IntValue FAINT_DURATION_SECONDS;
    public static final ForgeConfigSpec.IntValue WAKE_DEBUFF_SECONDS;
    public static final ForgeConfigSpec.IntValue WHEEL_ROTATION_TICKS_PER_REV;
    public static final ForgeConfigSpec.IntValue ROUND_TIMEOUT_TICKS;
    public static final ForgeConfigSpec.DoubleValue MAX_CHOKEHOLD_RANGE;
    public static final ForgeConfigSpec.BooleanValue FAINTED_INVULNERABLE;
    public static final ForgeConfigSpec.BooleanValue PASSIVE_AIR_DRAIN;
    public static final ForgeConfigSpec.DoubleValue PASSIVE_AIR_DRAIN_PER_SEC;
    public static final ForgeConfigSpec.BooleanValue SVC_ENABLED;
    public static final ForgeConfigSpec.BooleanValue ALLOW_MANUAL_RELEASE;

    // 4 zone point values (chokeholder side benefits from low, restrained side from high)
    public static final ForgeConfigSpec.IntValue ZONE1_POINTS;
    public static final ForgeConfigSpec.IntValue ZONE2_POINTS;
    public static final ForgeConfigSpec.IntValue ZONE3_POINTS;
    public static final ForgeConfigSpec.IntValue ZONE4_POINTS;
    // Arc sizes (degrees) for the 4 zones, must sum to 360
    public static final ForgeConfigSpec.IntValue ZONE1_ARC;
    public static final ForgeConfigSpec.IntValue ZONE2_ARC;
    public static final ForgeConfigSpec.IntValue ZONE3_ARC;
    public static final ForgeConfigSpec.IntValue ZONE4_ARC;
    // Per-zone shrink (degrees). Each zone's hit window is
    // (arc - 2*shrink) wide centered in the arc. The remaining
    // (shrink * 2 * 4) degrees become the "miss" gap. 0 = no misses
    // possible (legacy).
    public static final ForgeConfigSpec.IntValue ZONE_SHRINK_DEGREES;

    static {
        BUILDER.comment("Chokehold / Restrain Duel Mod — common configuration");

        AIR_MAX = BUILDER.comment("Maximum air value").defineInRange("airMax", 100, 10, 1000);
        AIR_LOSS_PER_ROUND = BUILDER.comment("Air lost when chokeholder wins a round").defineInRange("airLossPerRound", 10, 1, 100);
        AIR_GAIN_PER_GASP = BUILDER.comment("Air gained on a successful gasp").defineInRange("airGainPerGasp", 15, 1, 100);
        PASSED_PENALTY_AIR = BUILDER.comment("Air change when a player lets the needle complete a full revolution without pressing (the 'passed' penalty — milder than a miss). Deducted from the restrained player if they passed; added to the restrained player if the chokeholder passed").defineInRange("passedPenaltyAir", 20, 1, 100);
        STREAK_TO_ESCAPE = BUILDER.comment("Number of consecutive restrained-player round wins needed to escape").defineInRange("streakToEscape", 5, 1, 50);
        CHOKEHOLD_COOLDOWN_SECONDS = BUILDER.comment("Minimum time between chokehold START attempts (anti-spam). Prevents immediately re-chokeholding after a chokehold ends").defineInRange("cooldownSeconds", 1, 0, 600);
        PARTNER_COOLDOWN_SECONDS = BUILDER.comment("Per-partner cooldown after a chokehold ends (how long before you can chokehold the same person again). Only applies to the chokeholder side").defineInRange("partnerCooldownSeconds", 10, 0, 600);
        GASP_WINDOW_TICKS = BUILDER.comment("Length of the gasp QTE window in ticks (default 20 = 1s)").defineInRange("gaspWindowTicks", 20, 1, 100);
        FAINT_DURATION_SECONDS = BUILDER.comment("Faint duration in seconds").defineInRange("faintDurationSeconds", 30, 1, 600);
        WAKE_DEBUFF_SECONDS = BUILDER.comment("Slowness/weakness duration after waking from faint").defineInRange("wakeDebuffSeconds", 3, 0, 60);
        WHEEL_ROTATION_TICKS_PER_REV = BUILDER.comment("Ticks per full wheel revolution on round 1 (server-authoritative). Every round after that is 50% faster (round n = 1.5^(n-1)x this speed), capped at 200% (round 3 and beyond spin at 2x)").defineInRange("wheelRotationTicks", 50, 5, 600);
        ROUND_TIMEOUT_TICKS = BUILDER.comment("Maximum ticks before an unfinished round is auto-resolved as a draw").defineInRange("roundTimeoutTicks", 80, 20, 600);
        MAX_CHOKEHOLD_RANGE = BUILDER.comment("Max distance between chokeholder and target to initiate chokehold (blocks)").defineInRange("maxChokeholdRange", 1.0, 1.0, 64.0);
        FAINTED_INVULNERABLE = BUILDER.comment("If true, fainted players take no damage").define("faintedInvulnerable", false);
        PASSIVE_AIR_DRAIN = BUILDER.comment("If true, restrained player's air slowly drains passively").define("passiveAirDrain", true);
        PASSIVE_AIR_DRAIN_PER_SEC = BUILDER.comment("Passive drain rate (air per second)").defineInRange("passiveAirDrainPerSec", 1.0, 0.0, 50.0);
        SVC_ENABLED = BUILDER.comment("If true, attempt to integrate with Simple Voice Chat when present").define("svcEnabled", true);
        ALLOW_MANUAL_RELEASE = BUILDER.comment("If true, chokeholder can manually release by sneaking").define("allowManualRelease", true);

        BUILDER.comment("Wheel zone values and arc sizes. The arcs need NOT sum to 360:"
                + " any leftover degrees after zone4 become a plain miss band, so a small total"
                + " arc makes the hit area a small slice of the ring (like a precision gauge).");
        ZONE1_POINTS = BUILDER.defineInRange("zone1Points", 1, 1, 100);
        ZONE2_POINTS = BUILDER.defineInRange("zone2Points", 2, 1, 100);
        ZONE3_POINTS = BUILDER.defineInRange("zone3Points", 3, 1, 100);
        ZONE4_POINTS = BUILDER.defineInRange("zone4Points", 5, 1, 100);
        // Total hit area = (sum of arcs) - 2*shrink*4 ≈ 80° of the 360° ring.
        // Roughly a quarter of the ring is hittable; the rest is a miss.
        ZONE1_ARC = BUILDER.defineInRange("zone1Arc", 20, 1, 360);
        ZONE2_ARC = BUILDER.defineInRange("zone2Arc", 30, 1, 360);
        ZONE3_ARC = BUILDER.defineInRange("zone3Arc", 40, 1, 360);
        ZONE4_ARC = BUILDER.defineInRange("zone4Arc", 30, 1, 360);
        ZONE_SHRINK_DEGREES = BUILDER
                .comment("Per-zone shrink (degrees, split half/half on each side). The gap "
                        + "between zones is the 'miss' band; 0 disables misses entirely.")
                .defineInRange("zoneShrinkDegrees", 5, 0, 60);

        SPEC = BUILDER.build();
    }

    private ChokeholdConfig() {}
}