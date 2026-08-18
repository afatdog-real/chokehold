package com.chokehold.chokehold.client;

import com.chokehold.chokehold.ChokeholdMod;
import com.chokehold.chokehold.capability.FaintedState;
import com.chokehold.chokehold.capability.FaintedStateProvider;
import com.chokehold.chokehold.event.ChokeholdEventHandlers;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.InputEvent;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only wiring that no longer needs to fight the input pipeline.
 *
 * <p>The duel's input capture (Space presses, movement lock, camera lock) is
 * now handled entirely by the modal {@link ChokeholdQTEScreen}: while it is open,
 * vanilla routes every keyboard + mouse event to it, so the local player can
 * neither move, jump, nor look — exactly like having the inventory open. That
 * removes the old per-tick zeroing + camera snap-back, which jittered because
 * the snap-back fought the mouse input every frame.
 *
 * <p>What's left here:
 * <ul>
 *   <li>{@link #onClientTick} — a screen-lifecycle safety net for BOTH modal
 *       screens (the QTE and the {@link ChokeholdFaintedScreen}). If either ever
 *       fails to open (or lingers after its state ends), this fixes it so the
 *       duel stays modal and the K.O. countdown keeps ticking.</li>
 *   <li>{@link #onClientLogout} — clears the static cache on disconnect so a
 *       stale chokehold/faint state never leaks into the next world join.</li>
 *   <li>{@link #onRenderLevel} — the periodic "muted-mic" particle above the
 *       restrained player's head.</li>
 *   <li>{@link #onKey} / {@link #onInteractionKey} — the pre-chokehold Space
 *       capture: a Space pressed in the gap between sneaking + right-clicking
 *       (the empty-handed chokehold initiation) and the QTE screen opening would
 *       otherwise jump instead of pressing the wheel (see
 *       {@link ClientChokeholdCache#onJumpKeyPressed}).</li>
 *   <li>{@link #onClientTick} also keeps the fainted hitbox in sync so a
 *       fainted body is hittable (see {@link ChokeholdEventHandlers#applyFaintedHitbox}).</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ChokeholdMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE, value = Dist.CLIENT)
public final class ClientInputHandler {

    /**
     * Per-tick safety net for the modal screens' lifecycles.
     *
     * <p>The QTE screen opens in {@code ClientChokeholdCache.onChokeholdStart} and
     * closes in {@code onChokeholdEnd} / its own {@code tick()}; the K.O. screen
     * opens in {@code onFaintStart} and closes in {@code onFaintEnd}. This just
     * repairs any screen-state race so the duel is always modal (and the K.O.
     * countdown keeps ticking) and never stuck open.
     */
    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;

        // Expire the pre-chokehold Space-capture window if the chokehold never
        // started (e.g. the target was too far / already busy) so a later jump
        // isn't swallowed.
        if (ClientChokeholdCache.chokeholdArmed && ClientChokeholdCache.clientTick() > ClientChokeholdCache.armedUntilTick) {
            ClientChokeholdCache.chokeholdArmed = false;
            ClientChokeholdCache.pendingSpacePress = false;
        }

        // Keep the fainted hitbox in sync: vanilla collapses the SLEEPING pose
        // to a 0.2x0.2 box, which no longer covers the body the client renders
        // lying down. This runs for every fainted player/dummy the client can
        // see (the capability mirror is set by S2CFaintStartPacket), so the
        // local victim AND onlookers all get the correct box.
        for (Entity e : mc.level.entitiesForRendering()) {
            if (e instanceof Player pp) {
                FaintedState fs = FaintedStateProvider.getOrNull(pp);
                if (fs != null && fs.isFainted()) {
                    ChokeholdEventHandlers.applyFaintedHitbox(pp);
                }
            }
        }

        // Fainted: show the K.O. screen (highest priority — it replaces the QTE)
        // and drive the countdown. Never hijack a screen that's already up (e.g.
        // the vanilla death screen when a bystander finishes off the K.O.'d player).
        if (ClientChokeholdCache.fainted) {
            if (ClientChokeholdCache.faintTicksRemaining > 0) ClientChokeholdCache.faintTicksRemaining--;
            if (mc.screen == null) mc.setScreen(new ChokeholdFaintedScreen());
            return;
        }
        if (mc.screen instanceof ChokeholdFaintedScreen) {
            // Faint cleared but the screen somehow lingered — close it.
            mc.setScreen(null);
            return;
        }

        if (ClientChokeholdCache.inChokehold && mc.screen == null) {
            // In a chokehold but no QTE screen (e.g. it was dismissed by another
            // screen interaction) — reopen it to restore the modal lock.
            mc.setScreen(new ChokeholdQTEScreen());
        } else if (!ClientChokeholdCache.inChokehold && mc.screen instanceof ChokeholdQTEScreen) {
            // Chokehold over but the screen somehow lingered — close it.
            mc.setScreen(null);
        }
    }

    /**
     * Fallback Space capture for the window where no QTE screen is open yet:
     * between sneaking + right-clicking (the empty-handed chokehold initiation)
     * and the {@link ChokeholdQTEScreen} opening. While the screen is up it owns
     * the jump key, so vanilla routes Space to it and this handler never fires;
     * until then vanilla would route an immediate Space to a normal jump — which
     * sends nothing and reads as "the action was cancelled". This catches that
     * press and funnels it through {@link ClientChokeholdCache#onJumpKeyPressed()}:
     * for a live chokehold it presses via the same debounced
     * {@link ClientChokeholdCache#pressSpace()} the screen uses; for an imminent
     * one (armed by the initiation) it queues the press for {@code onChokeholdStart}
     * to replay. In both cases it clears the vanilla jump-key state so the press
     * can't ALSO launch a jump.
     */
    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (event.getKey() != mc.options.keyJump.getKey().getValue()) return;
        if (ClientChokeholdCache.onJumpKeyPressed()) {
            // The chokehold swallowed this Space — clear the vanilla jump-key
            // state so it can't ALSO launch a jump. When no QTE screen is open
            // yet (the pre-chokehold window this fallback exists for), vanilla
            // would otherwise route the press to a normal jump.
            KeyMapping.set(mc.options.keyJump.getKey(), false);
        }
    }

    /**
     * Arm the pre-chokehold Space-capture window when the player right-clicks
     * while sneaking with an empty main hand — the same action that initiates a
     * chokehold server-side (see {@link ChokeholdEventHandlers#onRightClickEntity}).
     * {@code InteractionKeyMappingTriggered} fires on every use-key press
     * (right-click / F) before the interaction is dispatched, so this catches
     * the "starting a chokehold" moment {@link ClientChokeholdCache#armForChokehold()}
     * needs. See {@link ClientChokeholdCache#onJumpKeyPressed} for why this window
     * exists.
     */
    @SubscribeEvent
    public static void onInteractionKey(InputEvent.InteractionKeyMappingTriggered event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;
        if (!mc.player.isCrouching()) return;
        if (!mc.player.getMainHandItem().isEmpty()) return;
        ClientChokeholdCache.armForChokehold();
    }

    /**
     * Clear the client cache on disconnect so a stale {@code inChokehold} or
     * {@code fainted} never survives into the next world join (ESC is blocked
     * during the QTE / K.O., but a force-quit can leave the static cache set).
     * Also force-closes both modal screens so neither can ride along to the
     * title screen.
     */
    @SubscribeEvent
    public static void onClientLogout(ClientPlayerNetworkEvent.LoggingOut event) {
        ClientChokeholdCache.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ChokeholdQTEScreen || mc.screen instanceof ChokeholdFaintedScreen) {
            mc.setScreen(null);
        }
    }

    /**
     * Visual cue: a periodic angry-villager particle above the restrained
     * player's head so nearby players understand why they've gone silent.
     * Renders only when the local client is involved in the same chokehold.
     */
    @SubscribeEvent
    public static void onRenderLevel(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || mc.player == null) return;
        if (!ClientChokeholdCache.inChokehold) return;
        if (ClientChokeholdCache.partnerId == null) return;

        for (Player p : mc.level.players()) {
            if (!p.getUUID().equals(ClientChokeholdCache.partnerId)) continue;
            if (mc.level.random.nextInt(10) == 0) {
                mc.level.addParticle(
                        ParticleTypes.ANGRY_VILLAGER,
                        p.getX(), p.getY() + p.getBbHeight() + 0.3, p.getZ(),
                        0.0, 0.05, 0.0);
            }
        }
    }
}
