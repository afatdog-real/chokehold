package com.chokehold.chokehold.capability;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.AttachCapabilitiesEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import com.chokehold.chokehold.ChokeholdMod;

/**
 * Centralized capability attaching. Subscribes on the Forge event bus; is fired
 * for every entity the server (or client) loads.
 */
@Mod.EventBusSubscriber(modid = ChokeholdMod.MODID, bus = Mod.EventBusSubscriber.Bus.FORGE)
public final class CapabilityAttachers {

    @SubscribeEvent
    public static void onAttach(AttachCapabilitiesEvent<Entity> event) {
        if (!(event.getObject() instanceof Player player)) return;
        if (player.level() == null) return;

        if (!event.getCapabilities().containsKey(ChokeholdStateProvider.KEY)) {
            event.addCapability(ChokeholdStateProvider.KEY, new ChokeholdStateProvider());
        }
        if (!event.getCapabilities().containsKey(FaintedStateProvider.KEY)) {
            event.addCapability(FaintedStateProvider.KEY, new FaintedStateProvider());
        }
    }
}
