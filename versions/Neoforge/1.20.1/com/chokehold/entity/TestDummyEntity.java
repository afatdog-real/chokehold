package com.chokehold.chokehold.entity;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

import java.util.UUID;

import com.chokehold.chokehold.capability.FaintedState;
import com.chokehold.chokehold.capability.FaintedStateProvider;
import com.chokehold.chokehold.capability.ChokeholdState;
import com.chokehold.chokehold.capability.ChokeholdStateProvider;
import com.chokehold.chokehold.config.ChokeholdConfig;
import com.chokehold.chokehold.event.ChokeholdEventHandlers;
import com.chokehold.chokehold.network.PacketHelper;

/**
 * Test-only "player" entity used to exercise the chokehold mod in single-player.
 *
 * <p>This entity extends {@link Player} so:
 * <ul>
 *   <li>{@code PlayerInteractEvent.EntityInteract} recognizes it as a target.</li>
 *   <li>The {@code ChokeholdState} and {@code FaintedState} capabilities, which are
 *       attached to anything that {@code instanceof Player}, work without modification.</li>
 *   <li>All chokehold logic that operates on the {@link Player} base surface
 *       (UUID, level, distanceTo, setPose, getDeltaMovement, etc.) just works.</li>
 * </ul>
 *
 * <p>The entity is registered against a custom {@link EntityType}
 * ({@link ModEntities#TEST_DUMMY}) rather than {@link EntityType#PLAYER}, for
 * a hard architectural reason: {@code EntityType.PLAYER}'s factory is
 * {@link EntityType.Builder#createNothing} — it returns {@code null} when the
 * client-side {@code ClientPacketListener.handleAddEntity} calls
 * {@code entityType.create(level)}, causing the spawn packet to be silently
 * dropped. The local-player case is special-cased through
 * {@code ClientboundAddPlayerPacket}; a non-local {@code EntityType.PLAYER}
 * can never be brought into a {@code ClientLevel} via the generic spawn packet.
 * A custom type with a real factory avoids that, so the chunk tracker's
 * broadcast is honored.
 *
 * <p>Client-side rendering is supplied by {@code TestDummyRenderer}, registered
 * via {@code EntityRenderersEvent.RegisterRenderers}. It binds
 * {@code PlayerModel<TestDummyEntity>} and selects the default Steve-shaped
 * skin via {@code DefaultPlayerSkin.getDefaultSkin(STEVE_UUID)} (one of the 6
 * standard offline-mode default textures, picked by hashing
 * {@link #STEVE_UUID}, not the dummy's runtime UUID — the runtime UUID is
 * randomized per dummy to avoid the spawn-collision dedup in
 * {@code PersistentEntitySectionManager}).
 *
 * <p>Test variants, driven by {@link #autoPress} and {@link #difficulty}:
 * <ul>
 *   <li><b>Easy</b> ({@link #DIFFICULTY_EASY}, the default target): the dummy
 *       presses Space on a randomized tick (20-44 ticks) for the WHEEL phase and
 *       within the GASP window when it's the restrained side. It does not
 *       initiate — right-click it with the Restraint Tool to chokehold it as the
 *       chokeholder. A human can beat it by timing the needle.</li>
 *   <li><b>Normal</b> ({@link #DIFFICULTY_NORMAL}): the dummy never misses — it
 *       waits for the needle to enter any valid hit zone and presses exactly
 *       there, and it always lands the gasp when it's the restrained side.</li>
 *   <li><b>Impossible</b> ({@link #DIFFICULTY_IMPOSSIBLE}): like Normal, but it
 *       presses only when the needle is inside the highest-scoring zone, so it
 *       scores the maximum on every round and never misses.</li>
 * </ul>
 */
public class TestDummyEntity extends Player {

    /** Steve's vanilla skin UUID. Same as the offline-mode "Steve" placeholder. */
    public static final UUID STEVE_UUID = UUID.fromString("8667ba71-b85a-4004-af54-28bf2b9855b0");

    /**
     * The {@link EntityType} to use for this dummy. Held as a field so we
     * can override {@link #getType()} — see the constructor for why.
     */
    private final EntityType<TestDummyEntity> typeOverride;

    /** Difficulty levels for the auto-press logic, from easiest to hardest. */
    public static final int DIFFICULTY_EASY = 0;
    public static final int DIFFICULTY_NORMAL = 1;
    public static final int DIFFICULTY_IMPOSSIBLE = 2;
    /** Chokeholder variant: actively seeks and chokeholds nearby players. */
    public static final int DIFFICULTY_CHOKEHOLDER = 3;

    /**
     * Toggleable behavior. Set from {@code ChokeholdCommand} at spawn time.
     */
    private boolean autoPress;

    /**
     * Auto-press difficulty: how well the dummy aims the needle. Easy presses
     * blind on a random cadence; Normal aims for any valid zone; Impossible aims
     * for the highest-scoring zone. Set from {@code ChokeholdCommand} at spawn time.
     */
    private int difficulty;

    /** Tick when the chokeholder dummy last checked for nearby players to chokehold. */
    private long lastChokeholdCheckTick = 0L;

    /** Server tick at which the next auto-press may fire. */
    private long nextAutoPressTick;

    /**
     * The {@code roundStartTick} the current {@link #nextAutoPressTick} was
     * scheduled against. When it no longer matches the live round's start, the
     * schedule is stale (it belongs to an earlier, slower round) and is re-rolled
     * against the current round so the accelerating needle can't outrun it.
     */
    private long scheduledForRoundStart = -1;

    /**
     * Deferred-register factory. The {@link EntityType} parameter must be
     * exactly {@code EntityType<TestDummyEntity>} so the client-side spawn
     * handler can construct a fresh dummy via
     * {@code EntityType.create(level)}; the resulting instance must be of the
     * same concrete type registered in {@link ModEntities#TEST_DUMMY}. See
     * {@code ModEntities} javadoc for why a custom type (rather than
     * {@code EntityType.PLAYER}) is required.
     *
     * <p>Implementation note — the {@link Player} base constructor
     * {@code Player(Level, BlockPos, float, GameProfile)} hardcodes
     * {@link EntityType#PLAYER} as the entity's type (it ignores any
     * subclass-supplied type and calls
     * {@code LivingEntity.<init>(EntityType.PLAYER, Level)}). That means
     * {@code this.getType()} would otherwise return {@code EntityType.PLAYER}
     * for the dummy, which would route the spawn packet through the player
     * spawn path and prevent the client from rendering it. We work around
     * this by overriding {@link #getType()} below to return the custom
     * {@code EntityType<TestDummyEntity>} passed in here. Everything else
     * (the public {@code getType()} method, the chunk tracker, the spawn
     * packet serialization) goes through the override, so the spawn packet
     * carries the correct custom type.
     *
     * <p>The {@code GameProfile} we pass to {@code super(...)} uses a
     * freshly-generated random UUID instead of {@link #STEVE_UUID} so that
     * each dummy has a unique network UUID. The {@code Player} constructor
     * calls {@code setUUID(GameProfile.getId())} internally, so passing a
     * random-UUID profile gives us a unique runtime UUID — avoiding the
     * {@code "UUID of added entity already exists"} collision when multiple
     * dummies are spawned. The renderer uses {@link #STEVE_UUID} for the
     * texture lookup, not the entity's runtime UUID, so the texture stays
     * "Steve" regardless.
     */
    public TestDummyEntity(EntityType<TestDummyEntity> type, Level level) {
        this(type, level, "Steve");
    }

    /**
     * Constructor with custom name for test dummy variants.
     */
    public TestDummyEntity(EntityType<TestDummyEntity> type, Level level, String customName) {
        super(level,
                new BlockPos(0, level.getMinBuildHeight(), 0),
                0.0F,
                new GameProfile(UUID.randomUUID(), customName));
        this.typeOverride = type;
        this.autoPress = false;
        this.difficulty = DIFFICULTY_EASY;
        this.setNoGravity(false); // enable gravity so dummy falls when block below breaks
        this.nextAutoPressTick = 0L;
    }

    /**
     * Override {@link Entity#getType()} to return the custom entity type
     * registered in {@link ModEntities#TEST_DUMMY}. Without this override,
     * the parent {@link Player} constructor would have set {@code this.type}
     * to {@link EntityType#PLAYER}, which would route the chunk tracker's
     * spawn packet through the player spawn path that the client silently
     * drops for non-local entities. See the constructor for details.
     */
    @Override
    public EntityType<TestDummyEntity> getType() {
        return typeOverride;
    }

    /**
     * Nameplate above the entity. A {@link Player} normally renders its
     * {@code GameProfile} name (hardcoded to "Steve" for every dummy), so we
     * override to show the per-variant custom name instead.
     */
    @Override
    public net.minecraft.network.chat.Component getDisplayName() {
        net.minecraft.network.chat.Component cn = getCustomName();
        return cn != null ? cn : super.getDisplayName();
    }

    /**
     * Scoreboard / player-list name. Mirrors {@link #getDisplayName()} so the
     * dummy shows up with its custom name wherever a display name is resolved.
     */
    @Override
    public String getScoreboardName() {
        net.minecraft.network.chat.Component cn = getCustomName();
        return cn != null ? cn.getString() : super.getScoreboardName();
    }

    /**
     * Set whether this dummy auto-presses Space during chokeholds.
     */
    public void setAutoPress(boolean autoPress) {
        this.autoPress = autoPress;
    }

    /**
     * Set the auto-press difficulty ({@link #DIFFICULTY_EASY},
     * {@link #DIFFICULTY_NORMAL}, or {@link #DIFFICULTY_IMPOSSIBLE}).
     */
    public void setDifficulty(int difficulty) {
        this.difficulty = difficulty;
    }

    /**
     * Dummies are not in creative mode — they're test NPCs that simulate a
     * real player. Required override since {@link Player#isCreative()} is
     * abstract in 1.20.1.
     */
    @Override
    public boolean isCreative() {
        return false;
    }

    /**
     * Dummies are not spectator either — they should appear "alive" to the
     * chokehold logic. Required override since {@link Player#isSpectator()} is
     * abstract in 1.20.1.
     */
    @Override
    public boolean isSpectator() {
        return false;
    }

    @Override
    public void onAddedToWorld() {
        super.onAddedToWorld();
        if (!level().isClientSide()) {
            ModEntities.ACTIVE_DUMMIES.add(this);
        }
    }

    @Override
    public void remove(net.minecraft.world.entity.Entity.RemovalReason reason) {
        if (!level().isClientSide()) {
            ModEntities.ACTIVE_DUMMIES.remove(this);
        }
        super.remove(reason);
    }

    /**
     * Server-side per-tick: drive auto-press when in a chokehold and the
     * configured behavior is enabled. Lives here (not on the entity's
     * own {@code aiStep}) because {@code Player.aiStep} already does a
     * lot, and chokehold scheduling is a deliberate test affordance.
     */
    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide()) return;

        ChokeholdState gs = ChokeholdStateProvider.getOrNull(this);
        if (gs == null) return;

        // Lock motion if fainted or in an active chokehold (both roles),
        // matching onPlayerTick for real players. A real player is pinned by
        // the modal QTE screen, so the dummy must be pinned too.
        FaintedState fs = FaintedStateProvider.getOrNull(this);
        boolean isFainted = (fs != null && fs.isFainted());
        boolean isGrappling = (gs != null && gs.isActive());
        if (isFainted || isGrappling) {
            if (isFainted) {
                // Fainted: maintain sleeping pose & hitbox, but let vanilla physics
                // (super.tick() called above) handle gravity so the dummy falls
                // if the block below breaks. No position correction - that snapped
                // the dummy to the new "floor" instead of falling.
                setPose(Pose.SLEEPING);
                ChokeholdEventHandlers.applyFaintedHitbox(this);
                // Zero horizontal only to prevent drifting; keep vertical for gravity.
                setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
            } else {
                // Grappling: fully pinned (both horizontal + vertical).
                setDeltaMovement(0.0, 0.0, 0.0);
            }
        }

        // Chokeholder variant: actively seek and chokehold nearby players
        if (difficulty == DIFFICULTY_CHOKEHOLDER && !gs.isActive()) {
            long now = level().getGameTime();
            // Check every 20 ticks (1 second) to avoid performance overhead
            if (now - lastChokeholdCheckTick >= 20) {
                lastChokeholdCheckTick = now;
                tryChokeholdNearbyPlayer();
            }
        }

        if (!autoPress || !gs.isActive()) return;

        long now = level().getGameTime();

        switch (gs.getPhase()) {
            case WHEEL -> {
                // Only fire if the round is still open (not timed out yet) and this
                // side hasn't already locked in a press this round.
                if (now >= gs.getRoundTimeoutTick() || gs.getPressTick() >= 0) return;

                if (difficulty >= DIFFICULTY_NORMAL) {
                    // Normal/Impossible/Chokeholder aim the needle instead of pressing blind.
                    // Wait for the scheduled tick to arrive, then recompute the
                    // exact tick the needle is in the target zone and press there.
                    // Once a press is recorded the round resolves on the partner's
                    // press or the timeout, so an aimed dummy never misses.
                    if (now < nextAutoPressTick) return;
                    // Chokeholder variant: only target white zones (Z1, Z2) — lowest points,
                    // which benefit the chokeholder. Normal targets any zone; Impossible targets highest.
                    boolean chokeholdrOnlyWhite = (difficulty == DIFFICULTY_CHOKEHOLDER);
                    boolean bestZoneOnly = (difficulty >= DIFFICULTY_IMPOSSIBLE) && !chokeholdrOnlyWhite;
                    nextAutoPressTick = findIdealPressTick(gs.getRoundStartTick(), now,
                            gs.getRoundNumber(), bestZoneOnly, chokeholdrOnlyWhite);
                    if (now >= nextAutoPressTick) {
                        ChokeholdEventHandlers.handleSpacePress(this, now);
                    }
                } else {
                    // Easy: blind randomized timing — a human can beat it by timing
                    // the needle. The blind press is re-targeted per round so it
                    // always lands INSIDE the current round's first revolution:
                    // with round acceleration the revolution shrinks (50 → 33 → 22
                    // → … ticks), and an absolute "now + 20..44" schedule left over
                    // from the previous round would routinely land after the needle
                    // had already completed a full turn — turning the very next
                    // round into an instant "passed" penalty.
                    if (scheduledForRoundStart != gs.getRoundStartTick()) {
                        scheduleBlindPress(gs);
                    }
                    if (now < nextAutoPressTick) return;
                    ChokeholdEventHandlers.handleSpacePress(this, now);
                }
            }
            case GASP_QTE -> {
                // Auto-press is meaningful only when the dummy is the restrained side.
                if (!gs.isChokeholder()
                        && now >= gs.getGaspOpenTick()
                        && now <= gs.getGaspCloseTick()) {
                    if (difficulty >= DIFFICULTY_NORMAL) {
                        // Any press inside the window succeeds, so Normal/Impossible
                        // fire immediately. The successful gasp flips the phase to
                        // WHEEL, so this path can't double-count air.
                        ChokeholdEventHandlers.handleSpacePress(this, now);
                    } else if (now >= nextAutoPressTick) {
                        ChokeholdEventHandlers.handleSpacePress(this, now);
                        scheduleNextPress(now);
                    }
                }
            }
            default -> { /* IDLE / RESOLVED — no auto-press */ }
        }
    }

    private void scheduleNextPress(long now) {
        // 20..44 ticks (1..2.2s) between auto-presses — slow enough that a human
        // opponent has a full needle revolution to time their own press. With the
        // "wait for both presses" model, the dummy's press only records its tick;
        // the round still resolves on the human's press (or the round timeout).
        this.nextAutoPressTick = now + 20 + level().random.nextInt(25);
    }

    /**
     * Pick a blind press tick inside the <em>current</em> round's first
     * revolution, scaled to that round's needle speed. Unlike the old absolute
     * {@code now + 20..44} schedule, this re-anchors to {@code roundStartTick}
     * each round, so as the wheel speeds up the dummy's blind press stays inside
     * the window the needle needs to complete one turn — it can no longer "pass"
     * a round before its press even lands.
     *
     * <p>Presses in the 40%..85% span of the revolution: early enough to land
     * before the needle turns, late enough to stay beatable. Re-rolled every
     * time {@code roundStartTick} changes (tracked in
     * {@link #scheduledForRoundStart}).
     */
    private void scheduleBlindPress(ChokeholdState gs) {
        long now = level().getGameTime();
        long ticksPerRev = Math.max(1, ChokeholdConfig.WHEEL_ROTATION_TICKS_PER_REV.get());
        double speed = PacketHelper.roundSpeedMultiplier(gs.getRoundNumber());
        long revTicks = Math.max(1, (long) (ticksPerRev / speed));

        long lo = gs.getRoundStartTick() + Math.max(1, (long) (revTicks * 0.40));
        long hi = gs.getRoundStartTick() + Math.max(1, (long) (revTicks * 0.85));
        if (hi <= lo) hi = lo + 1;
        long span = hi - lo;
        this.nextAutoPressTick = lo + (span <= 0 ? 0 : (long) level().random.nextInt((int) span));
        if (this.nextAutoPressTick < now + 1) this.nextAutoPressTick = now + 1;
        this.scheduledForRoundStart = gs.getRoundStartTick();
    }

    /**
     * Find the first tick at/after {@code now} (and at least one tick into the
     * round) where the wheel needle sits inside a target zone. Used by the
     * Normal/Impossible/Chokeholder dummies to aim their press, at the current
     * round's (possibly accelerated) needle speed.
     *
     * <p>The search spans at most one base revolution from the round start — at
     * acceleration multiplier {@code m} that covers {@code m} full needle
     * revolutions, more than enough to sample the whole circle, and one base
     * revolution (default 50 ticks) always fits inside the round timeout
     * (default 80 ticks). The returned tick is stored in
     * {@link #nextAutoPressTick}; the dummy presses when the server clock
     * reaches it, which is exactly the tick {@code ChokeholdEventHandlers} samples
     * the needle angle at, so an aimed press cannot drift off the zone.
     *
     * <p>When the needle is too fast for any discrete tick to land inside a
     * zone, the search falls back to the tick whose angle comes closest to the
     * scoring region ({@code PacketHelper.distanceToHitZone}) — "never miss"
     * holds as far as the discrete tick grid allows.
     *
     * @param roundStart        server tick the current wheel round began
     * @param now               current server tick
     * @param roundNumber       current round number (drives needle acceleration)
     * @param bestZoneOnly      {@code true} → only the highest-scoring zone counts
     *                          (Impossible); {@code false} → any hit zone (Normal)
     * @param chokeholdrOnlyWhite {@code true} → only target white zones Z1/Z2
     *                          (chokeholder variant); {@code false} → standard behavior
     */
    private long findIdealPressTick(long roundStart, long now, int roundNumber,
                                    boolean bestZoneOnly, boolean chokeholdrOnlyWhite) {
        long ticksPerRev = Math.max(1, ChokeholdConfig.WHEEL_ROTATION_TICKS_PER_REV.get());
        long start = Math.max(roundStart + 1, now);
        long end = roundStart + ticksPerRev;

        long firstHitTick = -1;
        long bestPointsTick = start;
        int bestPoints = -1;
        long bestGapTick = start;
        int bestGap = Integer.MAX_VALUE;

        // For chokeholder variant: track best white zone (Z1 or Z2)
        long bestWhitePointsTick = start;
        int bestWhitePoints = -1;

        // For chokeholder variant fallback: track closest approach to white zones
        long bestWhiteGapTick = start;
        int bestWhiteGap = Integer.MAX_VALUE;

        for (long tick = start; tick <= end; tick++) {
            int angle = PacketHelper.needleAngleAt(tick, roundStart, roundNumber);
            int points = PacketHelper.pointValueAt(angle, roundStart, roundNumber);
            int zoneIdx = PacketHelper.zoneIndexAt(angle, roundStart, roundNumber);

            if (points > 0 && firstHitTick < 0) firstHitTick = tick;
            if (points > bestPoints) {
                bestPoints = points;
                bestPointsTick = tick;
            }
            // Track best white zone hit (Z1 or Z2)
            if (chokeholdrOnlyWhite && (zoneIdx == 1 || zoneIdx == 2)) {
                if (points > bestWhitePoints) {
                    bestWhitePoints = points;
                    bestWhitePointsTick = tick;
                }
            }
            int gap = PacketHelper.distanceToHitZone(angle, roundStart, roundNumber);
            if (gap < bestGap) {
                bestGap = gap;
                bestGapTick = tick;
            }
            // Track closest approach to white zones for chokeholder fallback
            if (chokeholdrOnlyWhite) {
                int whiteGap = distanceToWhiteZone(angle, roundStart, roundNumber);
                if (whiteGap < bestWhiteGap) {
                    bestWhiteGap = whiteGap;
                    bestWhiteGapTick = tick;
                }
            }
        }

        if (chokeholdrOnlyWhite) {
            // Chokeholder variant: only target white zones (Z1/Z2)
            if (bestWhitePoints > 0) {
                return bestWhitePointsTick;
            }
            // If no white zone hit, fall back to closest approach to white zone
            return bestWhiteGapTick;
        }

        if (!bestZoneOnly) {
            // Normal: the first tick inside any valid zone; if the wheel is too
            // fast for that, the closest approach to a zone.
            return firstHitTick >= 0 ? firstHitTick : bestGapTick;
        }
        // Impossible: the highest-scoring tick (the top zone while it's
        // hittable, degrading gracefully as the wheel outruns it); if even that
        // misses, the closest approach.
        return bestPoints > 0 ? bestPointsTick : bestGapTick;
    }

    /**
     * Angular distance (0..360) from a needle angle to the nearest white scoring
     * region (Z1 or Z2), for the given round's rotated layout. Used by the
     * chokeholder test dummy to find the closest approach to a white zone when the
     * wheel is too fast for any discrete tick to land inside Z1/Z2.
     */
    private static int distanceToWhiteZone(int needleAngle, long roundStartTick, int roundNumber) {
        int offset = PacketHelper.zoneOffsetDegrees(roundStartTick, roundNumber);
        needleAngle = ((needleAngle - offset) % 360 + 360) % 360;

        // White zones are Z1 (0..e1) and Z2 (s2..e2). Compute their edges from
        // the same config the server uses (mirrors PacketHelper.zoneEdges()).
        int arc1 = ChokeholdConfig.ZONE1_ARC.get();
        int arc2 = ChokeholdConfig.ZONE2_ARC.get();
        int shrink = Math.max(0, ChokeholdConfig.ZONE_SHRINK_DEGREES.get());
        int minArc = Math.min(arc1, arc2);
        shrink = Math.min(shrink, minArc / 2);

        int e1 = arc1 - shrink;
        int s2 = e1 + 2 * shrink;
        int e2 = s2 + (arc2 - 2 * shrink);

        if (needleAngle <= e1) return 0;                          // inside Z1
        if (needleAngle >= s2 && needleAngle <= e2) return 0;     // inside Z2
        if (needleAngle > e1 && needleAngle < s2) return s2 - needleAngle; // between Z1 and Z2
        // after Z2: nearer of approaching Z2 from above or wrapping to Z1 below
        return Math.min(needleAngle - e2, 360 - needleAngle + e1);
    }

    /**
     * Players take fall damage by default, but the dummy has no body and is
     * mostly stationary — suppress fall damage so test sessions don't get
     * cluttered by unrelated damage events.
     */
    @Override
    public boolean causeFallDamage(float fallDistance, float multiplier,
                                   net.minecraft.world.damagesource.DamageSource source) {
        return false;
    }

    /**
     * Chokeholder variant behavior: find a nearby player who isn't already in a
     * chokehold or fainted, and initiate a chokehold if they're behind them.
     * Mirrors the server-side logic in {@link ChokeholdEventHandlers#onRightClickEntity}.
     */
    private void tryChokeholdNearbyPlayer() {
        if (!(level() instanceof net.minecraft.server.level.ServerLevel serverLevel)) return;

        // Chokeholder dummy has a fixed 1-block reach (melee range)
        double maxRange = 1.0;

        // Find players in range
        for (net.minecraft.server.level.ServerPlayer player : serverLevel.getPlayers(
                p -> p.distanceTo(this) <= maxRange)) {

            // Skip if player is already grappling
            ChokeholdState playerGs = com.chokehold.chokehold.capability.ChokeholdStateProvider.getOrNull(player);
            if (playerGs != null && playerGs.isActive()) continue;

            // Skip if player is fainted
            com.chokehold.chokehold.capability.FaintedState playerFs =
                    com.chokehold.chokehold.capability.FaintedStateProvider.getOrNull(player);
            if (playerFs != null && playerFs.isFainted()) continue;

            // Skip if player is on chokehold cooldown
            if (playerGs != null && playerGs.getChokeholdCooldownTicks() > 0) continue;

            // Skip if this dummy is on cooldown
            ChokeholdState thisGs = ChokeholdStateProvider.getOrNull(this);
            if (thisGs != null && thisGs.getChokeholdCooldownTicks() > 0) continue;

            // Skip if dummy is on per-partner cooldown against this player
            if (thisGs != null && thisGs.getPartnerCooldownRemaining(player.getUUID(), level().getGameTime()) > 0) {
                System.out.println("[ChokeholdMod DEBUG] tryChokeholdNearbyPlayer: dummy " + this.getName().getString() + " BLOCKED from grappling " + player.getName().getString() + " by partner cooldown");
                continue;
            }

            // Check if player is behind this dummy (within ~120° cone behind)
            // toTarget = vector from dummy TO player
            // dummyLook = direction dummy is facing
            // Behind = toTarget roughly aligns with dummyLook (dot > 0.5)
            net.minecraft.world.phys.Vec3 toTarget = player.position().subtract(this.position()).normalize();
            net.minecraft.world.phys.Vec3 dummyLook = this.getViewVector(1.0F).normalize();
            double dot = toTarget.dot(dummyLook);
            if (dot < 0.5) continue; // cos(60°) = 0.5; behind cone = dot > 0.5

            // Player is in range, behind the dummy, and available — start chokehold!
            // The dummy is the chokeholder, player is the restrained
            com.chokehold.chokehold.event.ChokeholdEventHandlers.startChokehold(this, player);
            break; // Only chokehold one player at a time
        }
    }
}