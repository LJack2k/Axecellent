package nl.ljack2k.axecellent.chainsaw;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.level.BlockEvent;
import nl.ljack2k.axecellent.Axecellent;

/**
 * The one hook the mod needs: watch player block breaks, and when a tool carrying
 * {@code #axecellent:chainsaw} breaks a log, cut the tree.
 * <p>
 * What happens to the break event depends on the mode, and the difference matters:
 * <ul>
 *   <li>{@link CutMode#INSTANT} - <b>not</b> cancelled. Vanilla breaks the block the player
 *       aimed at, with its drops, durability, stats and enchantments, and the rest of the
 *       tree goes with it in the same tick.</li>
 *   <li>{@link CutMode#PROGRESSIVE} and {@link CutMode#HELD} - <b>cancelled</b>, so the block
 *       the player hit stays standing while the rest of the tree comes apart around it.
 *       {@link ChainsawCascade} hands that block back to vanilla at the end, so it still gets
 *       exactly vanilla's treatment, just later. In HELD that surviving block is also the
 *       thing the player keeps chopping, which is what paces the cut.</li>
 * </ul>
 * Nothing here reimplements a player break.
 * <p>
 * A repeat break on a tree that already has a cut running is not a new cut - it is either
 * noise to be swallowed (PROGRESSIVE) or the next bite (HELD). Getting that wrong means
 * planning and charging for the same tree several times a second.
 */
@EventBusSubscriber(modid = Axecellent.MODID)
public final class ChainsawHandler {
    private ChainsawHandler() {
    }

    @SubscribeEvent
    public static void onBlockBreak(BlockEvent.BreakEvent event) {
        // Cutting is server-authoritative: the client never runs it, so a client without
        // this mod still sees the right result.
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        if (!(event.getPlayer() instanceof ServerPlayer player)) {
            return;
        }
        // Our own per-block break events land here too - ignore them.
        if (Chainsaw.isActive()) {
            return;
        }
        // So does the origin block being handed back to vanilla at the end of a cascade.
        // Letting that through untouched is what stops it starting a cut on the stump.
        if (ChainsawCascade.isReplayingOrigin(event.getPos())) {
            return;
        }
        if (!event.getState().is(ModTags.Blocks.CHAINSAW_LOGS)) {
            return;
        }
        if (player.isCreative() && !Config.ENABLED_IN_CREATIVE.get()) {
            return;
        }
        CutMode mode = Config.MODE.get();
        boolean sneaking = player.isShiftKeyDown();
        // In HELD, crouching redirects the cut instead of switching it off: it starts at the
        // block under the crosshair and eats away from the player. Crouch plus one chop is
        // then the "just this log" escape hatch that sneakToDisable otherwise provides.
        boolean sneakRedirects = mode == CutMode.HELD
                && sneaking
                && Config.HELD_SNEAK_STARTS_AT_YOU.get();
        if (Config.SNEAK_TO_DISABLE.get() && sneaking && !sneakRedirects) {
            return;
        }

        ItemStack tool = player.getMainHandItem();
        if (!tool.is(ModTags.Items.CHAINSAW)) {
            return;
        }

        // Both animated modes keep the origin alive, so a player holding the attack button
        // re-breaks it every couple of ticks. Each of those is a fresh break event, and
        // without this the same tree would be planned, queued and charged for again and
        // again. Cancel so the block stays put, then leave the running cut alone.
        if (mode != CutMode.INSTANT) {
            ServerPlayer owner = ChainsawCascade.cutOwner(level, event.getPos());
            if (owner != null && owner != player) {
                // Somebody else is cutting this tree. Leave their cut alone and let this break
                // happen normally: cancelling it would make the log refuse to break for a
                // second player, for as long as the first one keeps at it. One chainsaw cut per
                // tree at a time, and everyone else chops the ordinary way.
                return;
            }
            if (owner == player) {
                event.setCanceled(true);
                // In HELD this repeat break IS the pacing. The player finished a chop on the
                // tree, so that buys exactly one bite - nothing runs on a timer underneath, so
                // stopping chopping stops the cut with no window to get wrong.
                if (mode == CutMode.HELD) {
                    ChainsawCascade.chop(level, player, event.getPos());
                }
                return;
            }
        }

        Chainsaw.Plan plan = Chainsaw.plan(level, player, event.getPos(), tool);
        if (plan.isEmpty()) {
            // Nothing to cut beyond the block itself - leave the break completely alone
            // rather than cancelling it and handing back an identical result.
            return;
        }

        boolean animated = mode != CutMode.INSTANT && ChainsawCascade.hasRoom();
        if (animated) {
            boolean held = mode == CutMode.HELD;
            // The player's block is always kept standing. In HELD that is what they keep
            // chopping, and it is the only thing that makes the mode work: let vanilla take it
            // straight away and the crosshair ends up on air, so no further chops land and the
            // cut never advances - indistinguishable from plain vanilla chopping.
            event.setCanceled(true);
            if (sneakRedirects) {
                // Crouched only reverses which end goes first: logs peel away from the player
                // instead of falling in from the far side. Their own block still goes last.
                plan = plan.reversed();
            }

            int perLog = 0;
            int perLeaf = 0;
            if (held) {
                // HELD pays as it goes, so releasing early only costs what was cut. Logs
                // and leaves are priced separately for the same reason planning does it:
                // leaves are uncapped, so charging the log rate for each would cost several
                // times the whole tree.
                perLog = player.isCreative() ? 0 : Config.DURABILITY_PER_LOG.get();
                perLeaf = player.isCreative() || !Config.DURABILITY_FOR_LEAVES.get()
                        ? 0
                        : Config.DURABILITY_PER_LOG.get();
            } else if (plan.durability() > 0) {
                // The others charge up front, so switching tools mid-fall cannot dodge the
                // cost or damage the wrong item.
                tool.hurtAndBreak(plan.durability(), level, player, item -> {});
            }
            ChainsawCascade.submit(level, player, tool, event.getPos(), plan, held, perLog, perLeaf);
            if (held) {
                // This break was itself a completed chop, so it earns the first bite. Without
                // this the opening chop only sets the cut up and appears to do nothing.
                ChainsawCascade.chop(level, player, event.getPos());
            }
        } else {
            Chainsaw.execute(level, player, plan, event.getPos(), tool, true);
        }

        Axecellent.LOGGER.debug("Cut {} log(s) and {} leaf/leaves for {} at {} ({})",
                plan.logCount(), plan.leafCount(), player.getGameProfile().getName(), event.getPos(),
                animated ? mode : CutMode.INSTANT);
    }
}
