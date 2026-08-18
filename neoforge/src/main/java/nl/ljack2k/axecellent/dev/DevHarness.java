package nl.ljack2k.axecellent.dev;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import nl.ljack2k.axecellent.Axecellent;
import nl.ljack2k.axecellent.chainsaw.Chainsaw;
import nl.ljack2k.axecellent.chainsaw.ChainsawCascade;
import nl.ljack2k.axecellent.chainsaw.Config;
import nl.ljack2k.axecellent.chainsaw.CutMode;
import nl.ljack2k.axecellent.chainsaw.ModTags;
import org.jetbrains.annotations.Nullable;

/**
 * Dev-only test harness - enabled only when -Daxecellent.devHarness is set (the
 * runServer/clientJoin Gradle runs set it; a normal install never does, so none of
 * this ships as active behaviour).
 * <p>
 * A cut is triggered by a player break, which RCON cannot perform - so this
 * harness provides the two halves separately, which together verify the whole
 * feature without a human at the keyboard:
 * <ul>
 *   <li>{@code /axecellent tree [height]} - plant a test oak next to the player.</li>
 *   <li>{@code /axecellent cut} - run the feller on the nearest log exactly as a
 *       break would, and report logs/leaves/durability.</li>
 *   <li>{@code /axecellent shot} - screenshot the joined client.</li>
 * </ul>
 */
@EventBusSubscriber(modid = Axecellent.MODID)
public final class DevHarness {
    private DevHarness() {
    }

    /** Set by the runServer / runClientJoin Gradle runs, and by nothing else. */
    private static final String ENABLED_PROPERTY = "axecellent.devHarness";

    /** How far from the player {@code cut} looks for a log to start from. */
    private static final int SEARCH_RADIUS = 12;

    private static boolean enabled() {
        return System.getProperty(ENABLED_PROPERTY) != null;
    }

    /**
     * Wires the game-bus command listener. Discovered by FML's annotation scan, so
     * the {@code @Mod} class never names this one - which is the point: the shipped
     * jar has no dev package to scan, and no reference to a missing class either.
     */
    @SubscribeEvent
    public static void onCommonSetup(FMLCommonSetupEvent event) {
        if (!enabled()) {
            return;
        }
        // Added here rather than by annotation because RegisterCommandsEvent is a
        // game-bus event; common setup runs during mod loading, long before any
        // world starts, so the listener is always in place in time.
        NeoForge.EVENT_BUS.addListener(DevHarness::registerCommands);
        Axecellent.LOGGER.info("[Axecellent] Dev harness enabled ({}).", ENABLED_PROPERTY);
    }

    @SubscribeEvent
    public static void registerPayloads(RegisterPayloadHandlersEvent event) {
        if (!enabled()) {
            return;
        }
        // optional(): dev-only channels must never break the connection handshake
        // on a version/side skew.
        var registrar = event.registrar("1").optional();
        if (FMLEnvironment.dist == Dist.CLIENT) {
            // The client actually performs this. Referenced only on the client dist
            // so a dedicated server never classloads net.minecraft.client.*.
            registrar.playToClient(ScreenshotRequestPayload.TYPE, ScreenshotRequestPayload.STREAM_CODEC,
                    (payload, context) -> context.enqueueWork(
                            nl.ljack2k.axecellent.client.ClientScreenshot::take));
        } else {
            // The server must still register the type to be allowed to send it;
            // it never receives it.
            registrar.playToClient(ScreenshotRequestPayload.TYPE, ScreenshotRequestPayload.STREAM_CODEC,
                    (payload, context) -> {});
        }
    }

    private static void registerCommands(RegisterCommandsEvent event) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal(Axecellent.MODID)
                .then(Commands.literal("tree")
                        .executes(ctx -> tree(ctx.getSource(), 6))
                        .then(Commands.argument("height", IntegerArgumentType.integer(1, 32))
                                .executes(ctx -> tree(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "height")))))
                .then(Commands.literal("cut").executes(ctx -> cut(ctx.getSource())))
                .then(Commands.literal("break")
                        .executes(ctx -> breakLog(ctx.getSource(), false))
                        .then(Commands.literal("sneaking")
                                .executes(ctx -> breakLog(ctx.getSource(), true))))
                .then(Commands.literal("chop")
                        .executes(ctx -> simulateChops(ctx.getSource(), 1))
                        .then(Commands.argument("chops", IntegerArgumentType.integer(1, 200))
                                .executes(ctx -> simulateChops(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "chops")))))
                .then(Commands.literal("count").executes(ctx -> count(ctx.getSource())))
                .then(Commands.literal("shot").executes(ctx -> shot(ctx.getSource())));
        event.getDispatcher().register(root);
    }

    /** Plant a plain oak two blocks diagonally from the player: trunk plus a small crown. */
    private static int tree(CommandSourceStack src, int height) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[Axecellent] tree needs a player context."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos base = player.blockPosition().offset(2, 0, 2);

        for (int y = 0; y < height; y++) {
            level.setBlockAndUpdate(base.above(y), Blocks.OAK_LOG.defaultBlockState());
        }
        // Leaves are placed AFTER the logs so their distance property resolves
        // against a finished trunk and the crown does not immediately decay.
        BlockState leaves = Blocks.OAK_LEAVES.defaultBlockState();
        int crown = base.getY() + height - 1;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = -2; dy <= 1; dy++) {
                    int radius = Math.max(Math.abs(dx), Math.abs(dz));
                    // Taper: wide in the middle of the crown, 1 block at the very top.
                    if (dy >= 0 && radius > 1 || radius > 2) {
                        continue;
                    }
                    BlockPos pos = new BlockPos(base.getX() + dx, crown + dy, base.getZ() + dz);
                    if (level.getBlockState(pos).isAir()) {
                        level.setBlockAndUpdate(pos, leaves);
                    }
                }
            }
        }
        src.sendSuccess(() -> Component.literal(
                "[Axecellent] planted a " + height + "-log oak at " + base + ". Run: axecellent cut"), false);
        return 1;
    }

    /**
     * Run a cut exactly as a real break would: the feller handles the tree, then
     * the origin log is removed the way vanilla removes the block the player hit.
     * Gives the player a diamond axe first unless they are already holding a tool
     * that carries the chainsaw tag, so a specific tool can be tested deliberately.
     * <p>
     * It reports the mode the tool would use, even though it always removes the tree at once.
     */
    private static int cut(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[Axecellent] cut needs a player context "
                    + "(use: execute as <player> run axecellent cut)."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos origin = findLog(level, player.blockPosition());
        if (origin == null) {
            src.sendFailure(Component.literal("[Axecellent] no cuttable log within "
                    + SEARCH_RADIUS + " blocks - run: axecellent tree"));
            return 0;
        }

        ItemStack tool = player.getMainHandItem();
        if (!ModTags.isChainsaw(tool)) {
            tool = new ItemStack(Items.DIAMOND_AXE);
            player.setItemInHand(InteractionHand.MAIN_HAND, tool);
        }
        int durabilityBefore = tool.getDamageValue();

        CutMode mode = ModTags.modeFor(tool);
        if (mode == null) {
            mode = Config.MODE.get();
        }

        Chainsaw.Plan plan = Chainsaw.plan(level, player, origin, tool, mode);
        Chainsaw.Result result = Chainsaw.execute(level, player, plan, origin, tool, true);
        // Vanilla's half of the break: remove the block the "player hit", with drops.
        level.destroyBlock(origin, !player.isCreative(), player);

        int spent = tool.getDamageValue() - durabilityBefore;
        src.sendSuccess(() -> Component.literal(String.format(
                "[Axecellent] cut from %s as %s: %d extra log(s), %d leaf/leaves, "
                        + "%d durability (reported %d).",
                origin, plan.mode(), result.logs(), result.leaves(), spent, result.durability())), false);
        return 1;
    }

    /**
     * Break the nearest log through vanilla's own path
     * ({@code ServerPlayerGameMode#destroyBlock}), which fires the real block-break
     * event. Unlike {@code fell} this exercises the whole chain including every gate
     * in {@link nl.ljack2k.axecellent.chainsaw.ChainsawHandler} - the item tag, sneak,
     * creative mode - and it uses whatever the player is actually holding, so an
     * untagged tool can be tested.
     *
     * @param sneaking set the player's sneak flag for the duration, to test
     *                 sneak-to-disable without a human holding shift
     */
    private static int breakLog(CommandSourceStack src, boolean sneaking) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[Axecellent] break needs a player context."));
            return 0;
        }
        ServerLevel level = player.serverLevel();
        BlockPos origin = findLog(level, player.blockPosition());
        if (origin == null) {
            src.sendFailure(Component.literal("[Axecellent] no cuttable log within "
                    + SEARCH_RADIUS + " blocks - run: axecellent tree"));
            return 0;
        }

        ItemStack tool = player.getMainHandItem();
        String held = tool.isEmpty() ? "empty hand" : tool.getItem().toString();
        int damageBefore = tool.getDamageValue();
        int logsBefore = countLogs(level, origin);

        boolean wasSneaking = player.isShiftKeyDown();
        player.setShiftKeyDown(sneaking);
        try {
            player.gameMode.destroyBlock(origin);
        } finally {
            player.setShiftKeyDown(wasSneaking);
        }

        int remaining = countLogs(level, origin);
        int spent = player.getMainHandItem().getDamageValue() - damageBefore;
        src.sendSuccess(() -> Component.literal(String.format(
                "[Axecellent] break at %s with %s%s: %d -> %d log(s) nearby, %d durability spent.",
                origin, held, sneaking ? " (sneaking)" : "", logsBefore, remaining, spent)), false);
        return 1;
    }

    /**
     * Pretend the player is holding the attack button against the nearest log for a few
     * seconds - the only way to exercise {@link nl.ljack2k.axecellent.chainsaw.CutMode#HELD}
     * without a hand on a mouse.
     * <p>
     * It feeds the same hold state the real left-click events write, so the cascade cannot
     * tell the difference; only the source of the signal is synthetic.
     */
    private static int simulateChops(CommandSourceStack src, int chops) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[Axecellent] hold needs a player context."));
            return 0;
        }
        BlockPos target = findLog(player.serverLevel(), player.blockPosition());
        if (target == null) {
            src.sendFailure(Component.literal("[Axecellent] no cuttable log within "
                    + SEARCH_RADIUS + " blocks - run: axecellent tree"));
            return 0;
        }
        // Stand in for finishing that many chops on the tree. This is the same entry point the
        // real break event uses, so the code under test is the live path - only the source of
        // the chop is synthetic.
        // Stand in for finishing that many chops on the tree - the same call the real break
        // event makes, so the code path under test is the live one.
        for (int i = 0; i < chops; i++) {
            ChainsawCascade.chop(player.serverLevel(), player, target);
        }
        src.sendSuccess(() -> Component.literal(String.format(
                "[Axecellent] simulated %d chop(s) on %s.", chops, target)), false);
        return 1;
    }

    /**
     * Logs still standing near the player, plus how many trees are mid-fall.
     * <p>
     * Needed because a progressive cut finishes over several ticks: {@code break} returns
     * before the tree is down, so verifying it means counting again a moment later.
     */
    private static int count(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[Axecellent] count needs a player context."));
            return 0;
        }
        int logs = countLogs(player.serverLevel(), player.blockPosition());
        int falling = ChainsawCascade.activeCount();
        src.sendSuccess(() -> Component.literal(String.format(
                "[Axecellent] %d log(s) within %d blocks, %d tree(s) still falling.",
                logs, SEARCH_RADIUS, falling)), false);
        return logs;
    }

    /** Logs left in the area, so a break can be judged by what it actually removed. */
    private static int countLogs(ServerLevel level, BlockPos around) {
        int count = 0;
        for (BlockPos pos : BlockPos.betweenClosed(
                around.offset(-SEARCH_RADIUS, -2, -SEARCH_RADIUS),
                around.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            if (level.getBlockState(pos).is(ModTags.Blocks.CHAINSAW_LOGS)) {
                count++;
            }
        }
        return count;
    }

    /** Lowest, then nearest, cuttable log around the player. */
    @Nullable
    private static BlockPos findLog(ServerLevel level, BlockPos around) {
        BlockPos best = null;
        double bestScore = Double.MAX_VALUE;
        for (BlockPos pos : BlockPos.betweenClosed(
                around.offset(-SEARCH_RADIUS, -4, -SEARCH_RADIUS),
                around.offset(SEARCH_RADIUS, SEARCH_RADIUS, SEARCH_RADIUS))) {
            if (!level.getBlockState(pos).is(ModTags.Blocks.CHAINSAW_LOGS)) {
                continue;
            }
            // Height dominates the score so the base of a trunk always wins.
            double score = (pos.getY() - around.getY()) * 100.0 + pos.distSqr(around);
            if (score < bestScore) {
                bestScore = score;
                best = pos.immutable();
            }
        }
        return best;
    }

    private static int shot(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            src.sendFailure(Component.literal("[Axecellent] shot needs a player context "
                    + "(use: execute as <player> run axecellent shot)."));
            return 0;
        }
        PacketDistributor.sendToPlayer(player, new ScreenshotRequestPayload());
        src.sendSuccess(() -> Component.literal("[Axecellent] screenshot requested."), false);
        return 1;
    }
}
