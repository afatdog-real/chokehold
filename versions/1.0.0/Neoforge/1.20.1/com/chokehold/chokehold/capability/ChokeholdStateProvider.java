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
 * Provider + capability registration for {@link ChokeholdState}.
 *
 * We expose a static {@link #CAP} and convenience helpers so other code can fetch
 * the state without dealing with the LazyOptional boilerplate. Attachment is handled
 * centrally in {@link CapabilityAttachers}.
 */
public final class ChokeholdStateProvider implements ICapabilityProvider, INBTSerializable<CompoundTag> {
    public static final ResourceLocation KEY =
            new ResourceLocation(ChokeholdMod.MODID, "chokehold_state");

    public static final Capability<ChokeholdState> CAP =
            CapabilityManager.get(new CapabilityToken<>() {});

    private final ChokeholdState state = new ChokeholdState();
    private final LazyOptional<ChokeholdState> optional = LazyOptional.of(() -> state);

    public static LazyOptional<ChokeholdState> get(Player player) {
        if (player == null || player.level() == null) return LazyOptional.empty();
        return player.getCapability(CAP);
    }

    @Nullable
    public static ChokeholdState getOrNull(Player player) {
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