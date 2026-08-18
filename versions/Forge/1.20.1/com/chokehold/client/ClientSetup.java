package com.chokehold.chokehold.client;

import com.chokehold.chokehold.ChokeholdMod;
import com.chokehold.chokehold.entity.ModEntities;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Client-only setup. Subscribed to the MOD event bus (auto, via the annotation).
 *
 * <ul>
 *   <li>Registers {@link TestDummyRenderer} against the custom
 *       {@code ModEntities.TEST_DUMMY} entity type so spawned dummies are
 *       rendered as Steve-shaped players.</li>
 * </ul>
 */
@Mod.EventBusSubscriber(modid = ChokeholdMod.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientSetup {

    @SubscribeEvent
    public static void onRegisterRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(ModEntities.TEST_DUMMY.get(), TestDummyRenderer::new);
    }
}