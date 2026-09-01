package com.chokehold.chokehold;

import com.chokehold.chokehold.command.ChokeholdCommand;
import com.chokehold.chokehold.config.ChokeholdConfig;
import com.chokehold.chokehold.entity.ModEntities;
import com.chokehold.chokehold.item.ModItems;
import com.chokehold.chokehold.network.ModNetworking;
import com.mojang.logging.LogUtils;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.entity.EntityAttributeCreationEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

/**
 * Main entry point for the Chokehold Mod.
 *
 * Adds a player-vs-player "chokehold / restrain" mechanic built around a
 * timing-based duel minigame. See README.md for the full design overview.
 */
@Mod(ChokeholdMod.MODID)
public class ChokeholdMod {
    public static final String MODID = "chokehold";
    private static final Logger LOGGER = LogUtils.getLogger();

    public ChokeholdMod() {
        IEventBus modEventBus = FMLJavaModLoadingContext.get().getModEventBus();

        // Register items on the MOD event bus.
        ModItems.ITEMS.register(modEventBus);

        // Register entity types (TEST_DUMMY) on the MOD event bus.
        ModEntities.ENTITY_TYPES.register(modEventBus);

        // Register the EntityAttributeCreationEvent listener on the MOD bus so
        // the custom TestDummyEntity type gets the vanilla Player attribute
        // set (max health, movement speed, attack damage, etc.).
        modEventBus.addListener(this::onEntityAttributeCreation);

        // Capability attachers live in CapabilityAttachers (subscribed to the FORGE bus).

        // Register networking packets via SimpleChannel (NeoForge-compatible API).
        ModNetworking.register();

        // Register config
        ModLoadingContext.get().registerConfig(ModConfig.Type.COMMON, ChokeholdConfig.SPEC, "chokehold-common.toml");

        // Register commands on the FORGE bus.
        MinecraftForge.EVENT_BUS.addListener(this::onRegisterCommands);

        LOGGER.info("[ChokeholdMod] loaded (env={})", FMLEnvironment.dist);
    }

    /**
     * Provides vanilla Player attributes for {@link TestDummyEntity}.
     * Without this, the dummy has no max-health / movement-speed / attack
     * attributes and logs a warning on spawn.
     *
     * <p>Registered on the MOD bus via {@link #onEntityAttributeCreation}
     * {@code modEventBus.addListener(this::onEntityAttributeCreation)} above —
     * the explicit listener registration (no {@code @SubscribeEvent} on this
     * method) is what wires it up.
     */
    public void onEntityAttributeCreation(EntityAttributeCreationEvent event) {
        if (ModEntities.TEST_DUMMY.get() != null) {
            AttributeSupplier.Builder attrs = Player.createAttributes();
            event.put(ModEntities.TEST_DUMMY.get(), attrs.build());
        }
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        ChokeholdCommand.register(event.getDispatcher());
    }
}