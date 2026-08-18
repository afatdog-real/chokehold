package com.chokehold.chokehold.entity;

import com.chokehold.chokehold.ChokeholdMod;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registration for chokehold entities.
 *
 * <p>{@link #TEST_DUMMY} is the EntityType used for test dummies. Why a custom
 * type instead of {@link EntityType#PLAYER}?
 *
 * <ul>
 *   <li>{@code EntityType.PLAYER}'s factory is
 *       {@link EntityType.Builder#createNothing(MobCategory) createNothing}, so
 *       the client-side spawn handler
 *       ({@code ClientPacketListener.handleAddEntity}) calls
 *       {@code EntityType.PLAYER.create(level)} and gets {@code null} back,
 *       causing it to silently drop the spawn packet. The local-player case is
 *       special-cased through {@code ClientboundAddPlayerPacket}; a non-local
 *       {@code EntityType.PLAYER} can never be brought into a {@code ClientLevel}
 *       via the generic spawn packet.</li>
 *   <li>Registering a custom type with a real factory makes the generic
 *       {@code ClientboundAddEntityPacket} path work, so the chunk tracker
 *       correctly broadcasts the dummy to the local client.</li>
 *   <li>Our custom type still extends {@code Player}, so
 *       {@code PlayerInteractEvent.EntityInteract}, capability attachment, and
 *       all chokehold logic that operates on the {@code Player} base surface
 *       work without modification.</li>
 * </ul>
 *
 * <p>Client-side rendering is handled by {@code TestDummyRenderer}, registered
 * via {@code EntityRenderersEvent.RegisterRenderers} on the MOD bus. It binds
 * {@code PlayerModel<TestDummyEntity>} and uses the default Steve-shaped skin
 * via {@code DefaultPlayerSkin.getDefaultSkin(UUID)}.
 *
 * <p>{@link #ACTIVE_DUMMIES} holds every {@link TestDummyEntity} currently
 * loaded on the server. The chokehold server-tick handler iterates this set so
 * the dummy's per-tick logic runs even though dummies aren't in the player
 * list.
 */
public final class ModEntities {

    public static final DeferredRegister<EntityType<?>> ENTITY_TYPES =
            DeferredRegister.create(ForgeRegistries.ENTITY_TYPES, ChokeholdMod.MODID);

    /**
     * Custom {@link EntityType} for {@link TestDummyEntity}. Why a custom type:
     * see class javadoc. The factory {@code TestDummyEntity::new} matches the
     * {@code EntityType.EntityFactory<TestDummyEntity>} contract
     * {@code (EntityType<TestDummyEntity>, Level) -> TestDummyEntity}, so the
     * client-side spawn handler can construct a fresh dummy instance via
     * {@code EntityType.create(level)}.
     *
     * <p>Sized 0.6×1.8 to match a vanilla player's bounding box; tracking
     * range 32 (matches {@code EntityType.PLAYER}); update interval 2
     * (matches {@code EntityType.PLAYER}).
     */
    public static final RegistryObject<EntityType<TestDummyEntity>> TEST_DUMMY =
            ENTITY_TYPES.register("test_dummy", () ->
                    EntityType.Builder.<TestDummyEntity>of(TestDummyEntity::new, MobCategory.MISC)
                            .sized(0.6F, 1.8F)
                            .clientTrackingRange(32)
                            .updateInterval(2)
                            .build(ChokeholdMod.MODID + ":test_dummy"));

    /** Set of currently loaded {@link TestDummyEntity} instances. Server-only. */
    public static final Set<TestDummyEntity> ACTIVE_DUMMIES = ConcurrentHashMap.newKeySet();

    private ModEntities() {}
}