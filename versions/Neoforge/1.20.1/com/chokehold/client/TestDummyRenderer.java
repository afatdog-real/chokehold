package com.chokehold.chokehold.client;

import com.chokehold.chokehold.entity.TestDummyEntity;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * Client-side renderer for {@link TestDummyEntity}.
 *
 * <p>Why a custom renderer instead of binding the vanilla {@code PlayerRenderer}?
 * Because {@code PlayerRenderer} is generic over {@code AbstractClientPlayer},
 * which is a client-only abstract class. A {@code TestDummyEntity} (which extends
 * the shared {@code Player} base class) cannot satisfy that bound — binding the
 * vanilla renderer would compile but {@code ClassCastException} on render.
 *
 * <p>This renderer uses {@link PlayerModel}, which <i>is</i> generic over
 * {@link net.minecraft.world.entity.LivingEntity}, so
 * {@code PlayerModel<TestDummyEntity>} is valid. The texture comes from
 * {@link DefaultPlayerSkin#getDefaultSkin(java.util.UUID)} — one of the 6
 * standard offline-mode default skins, picked by hashing
 * {@link TestDummyEntity#STEVE_UUID}.
 *
 * <p>The shadow size (0.5) matches the vanilla {@code PlayerRenderer}.
 */
public class TestDummyRenderer extends LivingEntityRenderer<TestDummyEntity, PlayerModel<TestDummyEntity>> {

    public TestDummyRenderer(EntityRendererProvider.Context ctx) {
        super(ctx,
              new PlayerModel<>(ctx.bakeLayer(ModelLayers.PLAYER), /* slim */ false),
              0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(TestDummyEntity entity) {
        return DefaultPlayerSkin.getDefaultSkin(TestDummyEntity.STEVE_UUID);
    }
}