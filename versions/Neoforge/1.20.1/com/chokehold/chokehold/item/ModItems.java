package com.chokehold.chokehold.item;

import com.chokehold.chokehold.ChokeholdMod;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * Item registry for the chokehold mod. Currently empty — the chokehold mechanic
 * uses sneak + empty hand + right-click (see {@link
 * ChokeholdEventHandlers#onRightClickEntity}) rather than a dedicated item.
 *
 * <p>Kept as a placeholder in case future items are added.
 */
public final class ModItems {
    public static final DeferredRegister<net.minecraft.world.item.Item> ITEMS =
            DeferredRegister.create(ForgeRegistries.ITEMS, ChokeholdMod.MODID);

    private ModItems() {}
}