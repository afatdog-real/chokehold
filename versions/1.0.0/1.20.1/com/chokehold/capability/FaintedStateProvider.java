package com.chokehold.chokehold.capability;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.common.capabilities.Capability;
import net.minecraftforge.common.capabilities.CapabilityManager;
import net.minecraftforge.common.capabilities.CapabilityToken;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.common.util.INBTSerializable;
import net.minecraftforge.common.util.LazyOptional;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.chokehold.chokehold.ChokeholdMod;

/**
 * Provider + capability registration for {@link FaintedState}.
 * Attachment is handled centrally in {@link CapabilityAttachers}.
 */
public final class FaintedStateProvider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
    public static final ResourceLocation KEY =
            new ResourceLocation(ChokeholdMod.MODID, "fainted_state");

    public static final Capability<FaintedState> CAP =
            CapabilityManager.get(new CapabilityToken<>() {});

    private final FaintedState state = new FaintedState();
    private final LazyOptional<FaintedState> optional = LazyOptional.of(() -> state);

    public static LazyOptional<FaintedState> get(Player player) {
        if (player == null || player.level() == null) return LazyOptional.empty();
        return player.getCapability(CAP);
    }

    @Nullable
    public static FaintedState getOrNull(Player player) {
        return get(player).orElse(null);
    }

    @Nonnull
    @Override
    public <T> LazyOptional<T> getCapability(@Nonnull Capability<T> cap, @Nullable Direction side) {
        return cap == CAP ? optional.cast() : LazyOptional.empty();
    }

    @Override
    public CompoundTag serializeNBT() {
        CompoundTag tag = new CompoundTag();
        state.writeToNbt(tag);
        return tag;
    }

    @Override
    public void deserializeNBT(CompoundTag nbt) {
        state.readFromNbt(nbt);
    }
}