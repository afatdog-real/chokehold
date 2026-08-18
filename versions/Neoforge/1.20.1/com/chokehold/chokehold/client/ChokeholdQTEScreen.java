package com.chokehold.chokehold.client;

import com.chokehold.chokehold.config.ChokeholdConfig;
import com.chokehold.chokehold.network.PacketHelper;

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
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Player;

import java.awt.Color;

/**
 * Modal QTE screen shown for the whole chokehold. This is the "inventory-style"
 * lock the duel wants: while a real {@link Screen} is open, vanilla routes
 * EVERY keyboard + mouse event to it, so the local player can literally neither
 * move, jump, nor look — exactly like having the inventory open. It replaces
 * the old overlay + per-tick camera snap-back, which jittered because the
 * snap-back fought the mouse input every frame.
 *
 * <p>The screen is deliberately <b>non-pausing</b> ({@link #isPauseScreen()}
 * returns {@code false}) so the world — and the server-side round timeout —
 * keeps ticking behind it. It also can't be dismissed with ESC
 * ({@link #shouldCloseOnEsc()} returns {@code false}); it closes itself when
 * the server reports the chokehold is over ({@link #tick()}), or when the local
 * player leaves the world.
 *
 * <p><b>Input:</b> the jump key (Space by default) is captured by
 * {@link #keyPressed} and forwarded to the server via
 * {@link ClientChokeholdCache#pressSpace()}. Because a screen owns the input,
 * the press is seen exactly once and never also triggers a vanilla jump. For
 * presses that land in the 1-tick window before this screen opens,
 * {@code ClientInputHandler} has a {@code InputEvent.Key} fallback that funnels
 * them through the same debounced {@code ClientChokeholdCache.pressSpace()}.
 *
 * <p><b>Rendering:</b> draws the analog-gauge wheel (dark ring, four lit
 * hit-zones, rotating needle), the air bar below it, the round-result banner,
 * and the GASP prompt. All geometry is drawn with immediate-mode Tesselator
 * quads/triangles through the GUI projection matrix, mirroring the math in
 * {@link PacketHelper#pointValueAt(int, long, int)} exactly so lit zones match
 * the server's resolver.
 */
public class ChokeholdQTEScreen extends Screen {

    public ChokeholdQTEScreen() {
        super(Component.literal("Chokehold QTE"));
    }

    @Override
    public boolean isPauseScreen() {
        // World + round timeout keep running behind the modal.
        return false;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        // The QTE can't be bypassed by hitting ESC.
        return false;
    }

    /**
     * Auto-close when the chokehold ends. The server drives the end via
     * {@code S2CChokeholdEndPacket} (see {@link ClientChokeholdCache#onChokeholdEnd});
     * this is the screen-side watch so we never stay open into the idle game.
     * Also closes if the world disappears (e.g. server kick / dimension unload).
     */
    @Override
    public void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null || !ClientChokeholdCache.inChokehold) {
            mc.setScreen(null);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Space / the jump binding is the duel's only input. A Screen's
        // keyPressed fires on the initial GLFW press only (no repeats), so a
        // held Space sends one packet — exactly one QTE press. The actual send
        // lives in ClientChokeholdCache.pressSpace() so the ClientInputHandler
        // key fallback can reuse the same debounced path for presses that land
        // in the tick before this screen opens.
        if (keyCode == this.minecraft.options.keyJump.getKey().getValue()) {
            ClientChokeholdCache.pressSpace();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // While fainted, the lying-down pose is the cue — draw nothing at all
        // (matches the old overlay's early-out so the faint reads cleanly).
        if (ClientChokeholdCache.fainted) return;

        // A faint dark veil so the QTE reads as "modal" (like a GUI is open).
        // Drawn with the Tesselator (not GuiGraphics.fill) so it's flushed to
        // the GL immediately, BEFORE the wheel — if it were buffered it would
        // flush after the immediate wheel draws and paint over them.
        drawVeil();

        int cx = this.width / 2;
        int cy = this.height / 2;
        int radius = 50;

        drawWheel(cx, cy, radius);
        drawAirBar(g, cx, cy + radius + 16);
        drawResultBanner(g, cx, cy - radius - 20);
        // Only the restrained side can answer the GASP. The chokeholder still gets
        // the packet (so their needle pins to 0° and visibly resets) but must
        // not see a "Press SPACE" prompt they can't act on.
        if (ClientChokeholdCache.gaspOpen && !ClientChokeholdCache.isChokeholder) drawGaspPrompt(g, cx, cy - radius - 40);
    }

    // --- Drawing ------------------------------------------------------------

    private void drawVeil() {
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator t = Tesselator.getInstance();
        BufferBuilder b = t.getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addQuad(b, 0, 0, this.width, this.height, new Color(0, 0, 0, 110).getRGB());
        BufferUploader.drawWithShader(b.end());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private void drawWheel(int cx, int cy, int radius) {
        // Reset GL state — a prior draw may have left a non-identity shader
        // color, a different blend mode, or culling enabled.
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator t = Tesselator.getInstance();
        BufferBuilder b = t.getBuilder();

        int arc1 = ChokeholdConfig.ZONE1_ARC.get();
        int arc2 = ChokeholdConfig.ZONE2_ARC.get();
        int arc3 = ChokeholdConfig.ZONE3_ARC.get();
        int arc4 = ChokeholdConfig.ZONE4_ARC.get();
        int shrink = Math.max(0, ChokeholdConfig.ZONE_SHRINK_DEGREES.get());

        float innerR = radius * 0.78f;
        float outerR = radius;
        drawRingBand(b, cx, cy, innerR, outerR, 0, 360, new Color(40, 40, 40, 220).getRGB());

        // Hit-window per zone mirrors PacketHelper.pointValueAt EXACTLY.
        int minArc = Math.min(Math.min(arc1, arc2), Math.min(arc3, arc4));
        shrink = Math.min(shrink, minArc / 2);
        int s1 = 0,                      e1 = arc1 - shrink;
        int s2 = e1 + 2 * shrink,        e2 = s2 + (arc2 - 2 * shrink);
        int s3 = e2 + 2 * shrink,        e3 = s3 + (arc3 - 2 * shrink);
        int s4 = e3 + 2 * shrink,        e4 = s4 + (arc4 - 2 * shrink);
        // The whole Z1→Z4 block is rotated to this round's offset (same hash
        // the server scores against), so the drawn zones track the scored zones.
        // drawRingBand tolerates edges past 360, so no wrapping needed here.
        int offset = PacketHelper.zoneOffsetDegrees(ClientChokeholdCache.roundStartTick, ClientChokeholdCache.roundNumber);
        // Zone colors (reversed): Z1 = White, Z2 = White, Z3 = Light Red, Z4 = Red
        if (e1 > s1) drawRingBand(b, cx, cy, innerR, outerR, s1 + offset, e1 + offset, new Color(240, 240, 240, 230).getRGB());   // White
        if (e2 > s2) drawRingBand(b, cx, cy, innerR, outerR, s2 + offset, e2 + offset, new Color(240, 240, 240, 230).getRGB());   // White
        if (e3 > s3) drawRingBand(b, cx, cy, innerR, outerR, s3 + offset, e3 + offset, new Color(255, 120, 120, 230).getRGB());    // Light Red
        if (e4 > s4) drawRingBand(b, cx, cy, innerR, outerR, s4 + offset, e4 + offset, new Color(220, 50, 50, 230).getRGB());       // Red

        drawCircleOutline(b, cx, cy, radius, new Color(20, 20, 20, 255).getRGB());
        drawCircleOutline(b, cx, cy, innerR, new Color(20, 20, 20, 200).getRGB());

        long now = ClientChokeholdCache.clientTick();
        // A finished round resets the needle to the top (12 o'clock) instead of
        // leaving it spinning from where the round ended. During a GASP window
        // the round is over and no new wheel round has started yet, so draw it
        // at 0°; otherwise sweep it from the round's start tick.
        int needleAngle = ClientChokeholdCache.gaspOpen
                ? 0
                : PacketHelper.needleAngleAt(now, ClientChokeholdCache.roundStartTick, ClientChokeholdCache.roundNumber);
        drawNeedle(b, cx, cy, radius - 2, needleAngle, new Color(20, 20, 20, 255).getRGB());    // outline
        drawNeedle(b, cx, cy, radius - 4, needleAngle, new Color(245, 245, 245, 255).getRGB()); // fill

        drawPivot(b, cx, cy, 4, new Color(20, 20, 20, 255).getRGB());

        // Round-result markers: pin where both players' presses landed on the ring
        // for ~1.5s after a round resolves (green = you, red = opponent). These use
        // the recorded angles, so they stay fixed while the needle keeps spinning.
        if (ClientChokeholdCache.hasResult && now <= ClientChokeholdCache.lastResultDisplayUntil) {
            float markerR = innerR + (outerR - innerR) * 0.5f;
            int myAngle = ClientChokeholdCache.isChokeholder
                    ? ClientChokeholdCache.lastChokeholderAngle : ClientChokeholdCache.lastRestrainedAngle;
            int oppAngle = ClientChokeholdCache.isChokeholder
                    ? ClientChokeholdCache.lastRestrainedAngle : ClientChokeholdCache.lastChokeholderAngle;
            drawHitMarker(b, cx, cy, markerR, myAngle, new Color(90, 220, 90, 255).getRGB());
            drawHitMarker(b, cx, cy, markerR, oppAngle, new Color(255, 70, 70, 255).getRGB());
        }

        RenderSystem.enableCull();
        RenderSystem.disableBlend();
    }

    private static void drawRingBand(BufferBuilder b, int cx, int cy, float innerR, float outerR, int fromDeg, int toDeg, int color) {
        int segs = Math.max(8, (toDeg - fromDeg) / 4);
        float radFrom = (float) Math.toRadians(fromDeg - 90);
        float radTo = (float) Math.toRadians(toDeg - 90);
        b.begin(VertexFormat.Mode.TRIANGLE_STRIP, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i <= segs; i++) {
            float t = i / (float) segs;
            float a = radFrom + (radTo - radFrom) * t;
            float cosA = Mth.cos(a);
            float sinA = Mth.sin(a);
            addVertex(b, cx + cosA * outerR, cy + sinA * outerR, color);
            addVertex(b, cx + cosA * innerR, cy + sinA * innerR, color);
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static void drawCircleOutline(BufferBuilder b, int cx, int cy, float radius, int color) {
        int segs = 64;
        b.begin(VertexFormat.Mode.DEBUG_LINES, DefaultVertexFormat.POSITION_COLOR);
        for (int i = 0; i < segs; i++) {
            float a1 = (float) Math.toRadians(i * (360.0 / segs) - 90);
            float a2 = (float) Math.toRadians((i + 1) * (360.0 / segs) - 90);
            addVertex(b, cx + Mth.cos(a1) * radius, cy + Mth.sin(a1) * radius, color);
            addVertex(b, cx + Mth.cos(a2) * radius, cy + Mth.sin(a2) * radius, color);
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static void drawNeedle(BufferBuilder b, int cx, int cy, int len, int angleDeg, int color) {
        float baseHalfWidth = 3.5f;
        float a = (float) Math.toRadians(angleDeg - 90);
        float cosA = Mth.cos(a);
        float sinA = Mth.sin(a);
        float px = -sinA;
        float py = cosA;
        float tipX = cx + cosA * len;
        float tipY = cy + sinA * len;
        float baseLx = cx + px * baseHalfWidth;
        float baseLy = cy + py * baseHalfWidth;
        float baseRx = cx - px * baseHalfWidth;
        float baseRy = cy - py * baseHalfWidth;
        b.begin(VertexFormat.Mode.TRIANGLES, DefaultVertexFormat.POSITION_COLOR);
        addVertex(b, tipX, tipY, color);
        addVertex(b, baseLx, baseLy, color);
        addVertex(b, baseRx, baseRy, color);
        BufferUploader.drawWithShader(b.end());
    }

    private static void drawPivot(BufferBuilder b, int cx, int cy, float r, int color) {
        int segs = 24;
        b.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        addVertex(b, cx, cy, color);
        for (int i = 0; i <= segs; i++) {
            float a = (float) Math.toRadians(i * (360.0 / segs) - 90);
            addVertex(b, cx + Mth.cos(a) * r, cy + Mth.sin(a) * r, color);
        }
        BufferUploader.drawWithShader(b.end());
    }

    /**
     * Small filled dot pinned on the ring at a recorded hit angle. Used to show
     * where a player's press landed after a round resolves.
     */
    private static void drawHitMarker(BufferBuilder b, int cx, int cy, float ringR, int angleDeg, int color) {
        float a = (float) Math.toRadians(angleDeg - 90);
        float x = cx + Mth.cos(a) * ringR;
        float y = cy + Mth.sin(a) * ringR;
        int segs = 10;
        b.begin(VertexFormat.Mode.TRIANGLE_FAN, DefaultVertexFormat.POSITION_COLOR);
        addVertex(b, x, y, color);
        for (int i = 0; i <= segs; i++) {
            float ra = (float) Math.toRadians(i * (360.0 / segs));
            addVertex(b, x + Mth.cos(ra) * 4, y + Mth.sin(ra) * 4, color);
        }
        BufferUploader.drawWithShader(b.end());
    }

    private static void addVertex(BufferBuilder b, float x, float y, int color) {
        float a = ((color >> 24) & 0xFF) / 255f;
        float r = ((color >> 16) & 0xFF) / 255f;
        float g = ((color >> 8) & 0xFF) / 255f;
        float bl = (color & 0xFF) / 255f;
        // No-matrix vertex overload: rely on the GL projection matrix to map
        // GUI pixel coordinates to NDC (the screen renders in the GUI pass).
        b.vertex(x, y, 0).color(r, g, bl, a).endVertex();
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

    private void drawAirBar(GuiGraphics g, int cx, int cy) {
        int w = 100, h = 8;
        int maxAir = ChokeholdConfig.AIR_MAX.get();
        int air = Math.max(0, Math.min(maxAir, ClientChokeholdCache.air));
        int filled = (int) ((air / (float) maxAir) * w);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);

        Tesselator t = Tesselator.getInstance();
        BufferBuilder b = t.getBuilder();
        b.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        addQuad(b, cx - w/2, cy, cx + w/2, cy + h, new Color(20, 20, 20, 200).getRGB());
        addQuad(b, cx - w/2, cy, cx - w/2 + filled, cy + h, new Color(80, 200, 255, 230).getRGB());
        BufferUploader.drawWithShader(b.end());

        RenderSystem.enableCull();
        RenderSystem.disableBlend();

        g.drawCenteredString(Minecraft.getInstance().font, "Air: " + air + " / " + maxAir, cx, cy - 10, 0xFFFFFFFF);
    }

    private void drawResultBanner(GuiGraphics g, int cx, int cy) {
        long now = ClientChokeholdCache.clientTick();
        if (now > ClientChokeholdCache.lastResultDisplayUntil) return;

        // Line 1: what each player hit ("You: Z3 (3)  |  Bob: Z1 (1)").
        int myAngle = ClientChokeholdCache.isChokeholder
                ? ClientChokeholdCache.lastChokeholderAngle : ClientChokeholdCache.lastRestrainedAngle;
        int oppAngle = ClientChokeholdCache.isChokeholder
                ? ClientChokeholdCache.lastRestrainedAngle : ClientChokeholdCache.lastChokeholderAngle;
        int myVal = ClientChokeholdCache.isChokeholder
                ? ClientChokeholdCache.lastChokeholderValue : ClientChokeholdCache.lastRestrainedValue;
        int oppVal = ClientChokeholdCache.isChokeholder
                ? ClientChokeholdCache.lastRestrainedValue : ClientChokeholdCache.lastChokeholderValue;
        g.drawCenteredString(Minecraft.getInstance().font,
                "You: " + zoneLabel(myVal, myAngle) + "   |   " + partnerName() + ": " + zoneLabel(oppVal, oppAngle),
                cx, cy, 0xFFFFFFFF);

        // Line 2: who won the round.
        String win;
        switch (ClientChokeholdCache.lastWinner) {
            case "chokeholder" -> win = "Chokeholder wins round";
            case "restrained" -> win = "Restrained player wins round" + (ClientChokeholdCache.lastStreak > 0 ? " (streak " + ClientChokeholdCache.lastStreak + ")" : "");
            case "restrained_miss" -> win = ClientChokeholdCache.isChokeholder
                    ? "Opponent missed the wheel!"
                    : "You missed the wheel!";
            case "chokeholdr_miss" -> win = ClientChokeholdCache.isChokeholder
                    ? "You missed — opponent freed!"
                    : "Opponent missed — you're free!";
            case "both_miss" -> win = "Both missed — round repeats!";
            case "draw" -> win = "No contest — round repeats!";
            default -> { return; }
        }
        g.drawCenteredString(Minecraft.getInstance().font, win, cx, cy + 12, 0xFFFFFFAA);
    }

    /** "MISS" for a 0-value press, else "Z{n} (pts)" for the zone the angle landed in. */
    private static String zoneLabel(int value, int angle) {
        // Label against the layout of the round the press happened in, not the
        // current round's (the wheel may already have re-rolled for the next one).
        int idx = PacketHelper.zoneIndexAt(angle, ClientChokeholdCache.lastResultZoneOffset);
        if (value <= 0 || idx == 0) return "MISS";
        return "Z" + idx + " (" + value + ")";
    }

    /** Display name of the opponent, resolved from the client world by UUID. */
    private String partnerName() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.level != null && ClientChokeholdCache.partnerId != null) {
            Player p = mc.level.getPlayerByUUID(ClientChokeholdCache.partnerId);
            if (p != null) return p.getScoreboardName();
        }
        return "Opp";
    }

    private void drawGaspPrompt(GuiGraphics g, int cx, int cy) {
        long now = ClientChokeholdCache.clientTick();
        if (now < ClientChokeholdCache.gaspOpenTick || now > ClientChokeholdCache.gaspCloseTick) return;
        long remaining = ClientChokeholdCache.gaspCloseTick - now;
        g.drawCenteredString(Minecraft.getInstance().font, "GASP! Press SPACE (" + remaining + " ticks)", cx, cy, 0xFFFF5555);
    }
}
