package com.chokehold.chokehold.voice;

import com.chokehold.chokehold.capability.FaintedStateProvider;
import com.chokehold.chokehold.capability.ChokeholdStateProvider;
import com.chokehold.chokehold.config.ChokeholdConfig;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.VoicechatServerApi;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.fml.ModList;

/**
 * Simple Voice Chat integration. The class only references SVC types when SVC is
 * actually loaded — the {@code META-INF/services} entry combined with the runtime
 * check via {@link ModList#get()} keeps the mod from crashing if SVC isn't present.
 *
 * If SVC is not installed, the {@code VoicechatPlugin} service registration still
 * exists but never resolves to anything the SVC side looks up, so it is a no-op.
 *
 * SVC 2.4.x routes event subscription through {@link #registerEvents(EventRegistration)},
 * not via the server API's event bus.
 */
public final class ChokeholdVoicechatPlugin implements VoicechatPlugin {

    @Override
    public void initialize(VoicechatApi api) {
        // No-op: SVC 2.4.x doesn't expose an event bus on the server API. Event
        // listeners are registered below in registerEvents().
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophone);
    }

    @Override
    public String getPluginId() {
        return "chokehold";
    }

    private void onMicrophone(MicrophonePacketEvent event) {
        if (!ChokeholdConfig.SVC_ENABLED.get()) return;
        var conn = event.getSenderConnection();
        if (conn == null || conn.getPlayer() == null) return;
        if (!(conn.getPlayer() instanceof ServerPlayer player)) return;

        boolean mute = false;
        var fainted = FaintedStateProvider.getOrNull(player);
        if (fainted != null && fainted.isFainted()) mute = true;
        var gs = ChokeholdStateProvider.getOrNull(player);
        if (gs != null && gs.isRestrained()) mute = true;
        if (mute && event.isCancellable()) event.cancel();
    }

    /** Convenience guard for places that want to know if SVC is present. */
    public static boolean svcLoaded() {
        return ModList.get().isLoaded("voicechat");
    }
}