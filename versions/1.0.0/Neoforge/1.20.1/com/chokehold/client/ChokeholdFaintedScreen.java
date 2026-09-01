package com.chokehold.chokehold.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.network.chat.Component;

import java.awt.Color;

/**
 * Modal "K.O." screen shown to the fainted player for the whole faint duration.
 *
 * <p>Like the QTE screen it's a real {@link Screen}, so vanilla routes EVERY
 * keyboard + mouse event to it — the player can neither move, jump, nor look,
 * the "incapacitated" half of the hardcore-revival feel. It's non-pausing
 * ({@link #isPauseScreen()} returns {@code false}) so the world — and the
 * server-side faint countdown — keeps ticking behind it, and it can't be
 * dismissed with ESC ({@link #shouldCloseOnEsc()} returns {@code false}).
 *
 * <p>The fainted player is dropped to half a heart and made vulnerable (the
 * server clamps health to 1 HP in {@code ChokeholdEventHandlers}), so this screen
 * is also the player's last image before someone finishes them off.
 *
 * <p>Lifecycle: opened by {@link ClientChokeholdCache#onFaintStart} (replacing the
 * QTE screen, since a faint always ends the chokehold), closed by
 * {@link ClientChokeholdCache#onFaintEnd}, and repaired by the
 * {@link ClientInputHandler#onClientTick} safety net. The countdown shown here
 * is decremented once per client tick by that same safety net.
 */
public class ChokeholdFaintedScreen extends Screen {

    public ChokeholdFaintedScreen() {
        super(Component.literal("K.O."));
    }

    @Override
    public boolean isPauseScreen() {
        // World + faint countdown keep running behind the modal.
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // Being K.O.'d is not something you can ESC out of.
        return false;
    }

    /**
     * Auto-close when the faint ends (server sends {@code S2CFaintEndPacket} →
     * {@link ClientChokeholdCache#onFaintEnd}) or the world disappears.
     */
    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ClientChokeholdCache.fainted) {
            mc.setScreen(null);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Dark red veil so the screen reads unmistakably as "down and out" while
        // still letting the world (and your killers) stay visible behind it.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator t = Tesselator.getInstance();
        BufferBuilder b = t.getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addQuad(b, 0, 0, this.width, this.height, new Color(60, 0, 0, 160).getRGB());
        BufferUploader.drawWithShader(b.end());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        int cx = this.width / 2;
        int cy = this.height / 2;
        int seconds = Math.max(0, ClientChokeholdCache.faintTicksRemaining / 20);

        g.drawCenteredString(Minecraft.getInstance().font, "YOU ARE K.O.", cx, cy - 40, 0xFFFF4040);
        g.drawCenteredString(Minecraft.getInstance().font, seconds + "s left until recovery", cx, cy, 0xFFFFFFFF);
        g.drawCenteredString(Minecraft.getInstance().font, "You are helpless - anyone can finish you off.", cx, cy + 24, 0xFFBBBBBB);
    }

    private static void addQuad(BufferBuilder b, float x1, float y1, float x2, float y2, int color) {
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float bl = (color & 0xFF) / 255f;
        b.vertex(x1, y1, 0).color(r, g, bl, a).endVertex();
        b.vertex(x1, y2, 0).color(r, g, bl, a).endVertex();
        b.vertex(x2, y2, 0).color(r, g, bl, a).endVertex();
        b.vertex(x2, y1, 0).color(r, g, bl, a).endVertex();
    }
}
