package com.chokehold.chokehold.command;

import com.chokehold.chokehold.entity.TestDummyEntity;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec3Argument;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * Test-only commands. Currently:
 * <pre>
 * /chokehold summon &lt;easy|normal|hard|chokeholder&gt; [pos]
 * </pre>
 * Spawns a {@link TestDummyEntity} at {@code pos} (default: source position).
 * Each variant auto-presses Space in the wheel; right-click it with the
 * Restraint Tool to chokehold it as the chokeholder.
 * <ul>
 *   <li>{@code easy} — dummy presses Space on a randomized 20-44 tick cadence.
 *       Beatable by timing the needle.</li>
 *   <li>{@code normal} — like easy, but the dummy never misses: it presses
 *       exactly when the needle is inside a valid zone and always lands the
 *       gasp. You can still win, but it won't hand you a whiff.</li>
 *   <li>{@code hard} — like normal, but the dummy always presses when the
 *       needle is in the highest-scoring zone, so it scores the maximum on
 *       every round and never misses.</li>
 *   <li>{@code chokeholder} — actively seeks and chokeholds nearby players who stand
 *       behind it. Named Garry.</li>
 * </ul>
 * Requires cheats (permission level 2). Intended for single-player testing.
 */
public final class ChokeholdCommand {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(
                Commands.literal("chokehold")
                        .then(Commands.literal("summon")
                                .requires(src -> src.hasPermission(2))
                                .then(dummyVariant("easy", TestDummyEntity.DIFFICULTY_EASY))
                                .then(dummyVariant("normal", TestDummyEntity.DIFFICULTY_NORMAL))
                                .then(dummyVariant("hard", TestDummyEntity.DIFFICULTY_IMPOSSIBLE))
                                .then(dummyVariant("chokeholder", TestDummyEntity.DIFFICULTY_CHOKEHOLDER))));
    }

    private static com.mojang.brigadier.builder.ArgumentBuilder<CommandSourceStack, ?> dummyVariant(String name, int difficulty) {
        return Commands.literal(name)
                .executes(ctx -> summon(ctx, difficulty, ctx.getSource().getPosition()))
                .then(Commands.argument("pos", Vec3Argument.vec3())
                        .executes(ctx -> summon(ctx, difficulty, Vec3Argument.getVec3(ctx, "pos"))));
    }

    private static int summon(CommandContext<CommandSourceStack> ctx, int difficulty, Vec3 pos) throws CommandSyntaxException {
        CommandSourceStack src = ctx.getSource();
        ServerLevel level = src.getLevel();
        BlockPos spawnPos = new BlockPos((int) Math.floor(pos.x),
                                         (int) Math.floor(pos.y),
                                         (int) Math.floor(pos.z));

        String customName = switch (difficulty) {
            case TestDummyEntity.DIFFICULTY_NORMAL -> "bob";
            case TestDummyEntity.DIFFICULTY_IMPOSSIBLE -> "john";
            case TestDummyEntity.DIFFICULTY_CHOKEHOLDER -> "garry";
            default -> "joe"; // DIFFICULTY_EASY
        };

        TestDummyEntity dummy = new TestDummyEntity(com.chokehold.chokehold.entity.ModEntities.TEST_DUMMY.get(), level, customName);
        dummy.setAutoPress(true); // all three variants are auto-press dummies
        dummy.setDifficulty(difficulty);

        // Set custom name (also in GameProfile for sync consistency)
        dummy.setCustomName(net.minecraft.network.chat.Component.literal(customName));
        dummy.setCustomNameVisible(true);

        System.out.println("[ChokeholdMod DEBUG] Spawned dummy: name=" + customName + " UUID=" + dummy.getUUID());

        dummy.setPos(spawnPos.getX() + 0.5, spawnPos.getY(), spawnPos.getZ() + 0.5);
        dummy.setPose(net.minecraft.world.entity.Pose.STANDING);
        if (!level.addFreshEntity(dummy)) {
            src.sendFailure(net.minecraft.network.chat.Component.literal(
                    net.minecraft.ChatFormatting.RED + "Failed to spawn dummy (entity rejected by world)."));
            return 0;
        }

        String kind = switch (difficulty) {
            case TestDummyEntity.DIFFICULTY_NORMAL -> "normal";
            case TestDummyEntity.DIFFICULTY_IMPOSSIBLE -> "hard";
            case TestDummyEntity.DIFFICULTY_CHOKEHOLDER -> "chokeholder";
            default -> "easy";
        };
        final String msg = net.minecraft.ChatFormatting.GREEN +
                "Spawned " + kind + " chokehold dummy at " + spawnPos.toShortString() + ".";
        src.sendSuccess(() -> net.minecraft.network.chat.Component.literal(msg), true);
        return 1;
    }

    private ChokeholdCommand() {}
}
