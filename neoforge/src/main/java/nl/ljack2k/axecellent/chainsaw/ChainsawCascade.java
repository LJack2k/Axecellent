package nl.ljack2k.axecellent.chainsaw;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import nl.ljack2k.axecellent.Axecellent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Takes a {@link Chainsaw.Plan} apart a bite at a time, so a tree visibly comes down from
 * its far branches inwards instead of vanishing. What drives the bites depends on the mode,
 * and the difference is the whole point:
 * <ul>
 *   <li>{@link CutMode#PROGRESSIVE} - a few logs every tick, on its own, until the tree is
 *       down. One break, whole tree.</li>
 *   <li>{@link CutMode#HELD} - one bite per chop the player actually completes, and
 *       <strong>nothing on a timer</strong>. Their own chopping is the clock, so the rhythm
 *       is their tool's and letting go stops the cut with nothing left running.</li>
 * </ul>
 * That distinction was arrived at the hard way. Earlier versions advanced HELD on a timer
 * gated by "is the player still holding", inferred first from left-click packets (which only
 * arrive every ~15 ticks, so the cut had to coast between them and felt automatic) and then
 * from per-tick mining events (smooth, but still a timer, so it still felt like it was
 * running itself). Driving it from completed chops removes the clock entirely.
 * <p>
 * The block the player hit is deliberately not part of the plan. The break event is cancelled
 * to keep it standing while the rest of the tree falls, and when the cut finishes that block
 * is handed back to vanilla by re-running {@code gameMode.destroyBlock} on it - so it keeps
 * vanilla's drops, durability, enchantment handling, stats and advancements rather than an
 * approximation. Keeping it alive is also what gives a HELD cut something to keep chopping.
 * <p>
 * Everything here runs on the server thread; there is no background thread and no state that
 * outlives the server.
 */
@EventBusSubscriber(modid = Axecellent.MODID)
public final class ChainsawCascade {
    private ChainsawCascade() {
    }

    private static final List<Cut> ACTIVE_CUTS = new ArrayList<>();

    /**
     * The one block currently being handed back to vanilla, or null. While it is set,
     * {@link ChainsawHandler} lets that break event through untouched rather than treating it
     * as a fresh cut - otherwise replaying the origin would start another cut on the stump.
     */
    private static BlockPos ORIGIN_REPLAY;

    /** One tree coming down. */
    private static final class Cut {
        private final ServerLevel level;
        private final ServerPlayer player;
        private final ItemStack tool;
        private final BlockPos origin;
        private final List<Chainsaw.Step> steps;
        private final boolean held;
        /** Per-log durability, only used by HELD - the other modes pay up front. */
        private final int perLog;
        /** Per-leaf durability. Zero unless chargeForLeaves is on, exactly as planning does. */
        private final int perLeaf;
        /** Logs not yet taken, so a HELD cut can be carried on from any of them. */
        private final Set<BlockPos> remaining = new HashSet<>();
        private int next;
        /** Game time of the last bite, which is what the HELD give-up timeout measures. */
        private long lastProgress;

        private Cut(ServerLevel level, ServerPlayer player, ItemStack tool, BlockPos origin,
                    List<Chainsaw.Step> steps, boolean held, int perLog, int perLeaf) {
            this.level = level;
            this.player = player;
            this.tool = tool;
            this.origin = origin;
            this.steps = steps;
            this.held = held;
            this.perLog = perLog;
            this.perLeaf = perLeaf;
            this.lastProgress = level.getGameTime();
            for (Chainsaw.Step step : steps) {
                remaining.add(step.log());
            }
        }

        private boolean done() {
            return next >= steps.size();
        }
    }

    /** Is there room for another animated cut, or should the caller fall back to instant? */
    public static boolean hasRoom() {
        return ACTIVE_CUTS.size() < Config.MAX_CONCURRENT_CUTS.get();
    }

    /**
     * True while the given block is being handed back to vanilla. Checked by
     * {@link ChainsawHandler} so the replay is not mistaken for a new cut.
     */
    public static boolean isReplayingOrigin(BlockPos pos) {
        return ORIGIN_REPLAY != null && ORIGIN_REPLAY.equals(pos);
    }

    /**
     * Which player, if any, has a cut running that covers this block - null if none.
     * <p>
     * Load-bearing for both animated modes, because both keep the origin block alive: a player
     * holding the attack button re-breaks it every couple of ticks and fires a fresh break
     * event each time. Without this every one of those would plan and charge for the same tree
     * again. In HELD those repeats are also the pacing - see {@link #chop}.
     * <p>
     * It returns the <em>owner</em> rather than a boolean because on a shared server the answer
     * "someone, but not you" needs different handling: swallowing another player's break makes
     * the log simply refuse to break for them, with no explanation.
     */
    public static ServerPlayer cutOwner(ServerLevel level, BlockPos pos) {
        for (Cut cut : ACTIVE_CUTS) {
            if (cut.level == level && (cut.origin.equals(pos) || cut.remaining.contains(pos))) {
                return cut.player;
            }
        }
        return null;
    }

    /**
     * Queue a tree.
     *
     * @param held     true for {@link CutMode#HELD}: bites come from {@link #chop}, not ticks
     * @param perLog   durability charged per log as it falls; 0 when the caller already charged
     *                 the whole cut up front
     * @param perLeaf  the same for leaves - normally 0, since chargeForLeaves is off by
     *                 default. Kept separate from perLog because charging the log rate for
     *                 every leaf costs several times a whole tree's worth
     */
    public static void submit(ServerLevel level, ServerPlayer player, ItemStack tool, BlockPos origin,
                              Chainsaw.Plan plan, boolean held, int perLog, int perLeaf) {
        ACTIVE_CUTS.add(new Cut(level, player, tool, origin.immutable(), plan.steps(), held,
                perLog, perLeaf));
    }

    /**
     * A chop the player finished on a tree that has a cut running: take one bite.
     * <p>
     * This is all of HELD's pacing. One completed chop takes down
     * {@link Config#HELD_LOGS_PER_CHOP} logs, furthest first, and between chops nothing
     * happens at all.
     */
    public static void chop(ServerLevel level, ServerPlayer player, BlockPos pos) {
        Iterator<Cut> cuts = ACTIVE_CUTS.iterator();
        while (cuts.hasNext()) {
            Cut cut = cuts.next();
            if (!cut.held || cut.level != level || cut.player != player) {
                continue;
            }
            if (!cut.origin.equals(pos) && !cut.remaining.contains(pos)) {
                continue;
            }
            // The tool that started the cut has to still be the one in hand, or the cost would
            // land on an item that is no longer doing the work.
            if (cut.player.getMainHandItem() != cut.tool) {
                Axecellent.LOGGER.debug("[cascade] dropping cut at {}: tool changed", cut.origin);
                cuts.remove();
                return;
            }
            if (cut.done()) {
                // Every planned log is gone and only the block under the axe is left standing.
                // This chop is the one that takes it - it deliberately does NOT happen on the
                // same chop as the previous log, or the last two would go at once.
                finish(cut);
                cuts.remove();
                return;
            }
            if (outOfDurability(cut)) {
                cuts.remove();
                return;
            }
            advance(cut, Config.HELD_LOGS_PER_CHOP.get());
            return;
        }
    }

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        if (ACTIVE_CUTS.isEmpty()) {
            return;
        }
        int perTick = Config.LOGS_PER_TICK.get();
        Iterator<Cut> cuts = ACTIVE_CUTS.iterator();
        while (cuts.hasNext()) {
            if (!tick(cuts.next(), perTick)) {
                cuts.remove();
            }
        }
    }

    /**
     * @return false when this cut is finished or has been abandoned
     */
    private static boolean tick(Cut cut, int logs) {
        // If the player left, logged out, or changed dimension, stop where we are. The half-cut
        // tree stays standing, which is better than dropping items for an absent player or
        // guessing at their tool.
        if (cut.player.isRemoved() || cut.player.level() != cut.level) {
            Axecellent.LOGGER.debug("[cascade] dropping cut at {}: player gone", cut.origin);
            return false;
        }

        if (cut.held) {
            // HELD never advances on a tick - that is the point. All this does is forget a tree
            // the player has wandered off from, so a part-cut trunk does not stay claimed.
            long idle = cut.level.getGameTime() - cut.lastProgress;
            boolean keep = idle <= Config.HELD_RESUME_WINDOW.get() * 20L;
            if (!keep) {
                Axecellent.LOGGER.debug("[cascade] dropping cut at {}: no chop for {} ticks",
                        cut.origin, idle);
            }
            return keep;
        }

        advance(cut, logs);
        if (cut.done()) {
            // PROGRESSIVE is one continuous motion, so the origin goes with the last log.
            // HELD deliberately waits for another chop instead - see chop().
            finish(cut);
            return false;
        }
        return true;
    }

    /**
     * Take up to {@code logs} steps out of one cut, each log bringing its own leaves.
     * <p>
     * Deliberately does not finish the cut when it empties: whether the origin block goes in
     * the same motion is the caller's decision, and it differs between the modes.
     */
    private static void advance(Cut cut, int logs) {
        boolean dropAtOrigin = Config.DROPS_AT_BREAK_POSITION.get();
        for (int i = 0; i < logs && !cut.done(); i++) {
            Chainsaw.Step step = cut.steps.get(cut.next++);
            cut.remaining.remove(step.log());
            charge(cut, breakAt(cut, step.log(), dropAtOrigin), cut.perLog);
            for (BlockPos leaf : step.leaves()) {
                charge(cut, breakAt(cut, leaf, dropAtOrigin), cut.perLeaf);
            }
            cut.lastProgress = cut.level.getGameTime();
        }
    }

    /**
     * HELD charges as it goes, so it has to stop before the tool breaks rather than relying on
     * a budget worked out up front.
     */
    private static boolean outOfDurability(Cut cut) {
        if (cut.perLog <= 0 || !cut.tool.isDamageableItem()) {
            return false;
        }
        int left = cut.tool.getMaxDamage() - cut.tool.getDamageValue();
        int reserved = 1 + (Config.STOP_BEFORE_TOOL_BREAKS.get() ? 1 : 0);
        return left - cut.perLog < reserved;
    }

    private static void charge(Cut cut, boolean removed, int cost) {
        if (removed && cost > 0) {
            cut.tool.hurtAndBreak(cost, cut.level, cut.player, item -> {});
        }
    }

    /**
     * Hand the origin block back to vanilla. Re-running the player's own break is what keeps its
     * drops, durability, stats and enchantment handling identical to a normal chop - nothing
     * here reimplements any of that.
     */
    private static void finish(Cut cut) {
        ORIGIN_REPLAY = cut.origin;
        try {
            cut.player.gameMode.destroyBlock(cut.origin);
        } catch (RuntimeException e) {
            // Never let one odd block stop the server ticking, and never leave the replay marker
            // set - a stuck marker would disable cutting at that spot.
            Axecellent.LOGGER.error("Failed to finish the cut at {}", cut.origin, e);
        } finally {
            ORIGIN_REPLAY = null;
        }
    }

    /**
     * Finish every outstanding cut immediately when the server stops, so a shutdown mid-fall
     * does not leave half-cut trees behind in the save.
     */
    public static void completeAllNow() {
        if (ACTIVE_CUTS.isEmpty()) {
            return;
        }
        Axecellent.LOGGER.info("Finishing {} in-progress cut(s) before shutdown.", ACTIVE_CUTS.size());
        for (Cut cut : ACTIVE_CUTS) {
            if (!cut.player.isRemoved() && cut.player.level() == cut.level) {
                advance(cut, Integer.MAX_VALUE);
                finish(cut);
            }
        }
        ACTIVE_CUTS.clear();
    }

    @SubscribeEvent
    public static void onServerStopping(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        completeAllNow();
    }

    /** Server-wide count of trees part-way down. Used by the dev harness. */
    public static int activeCount() {
        return ACTIVE_CUTS.size();
    }

    /**
     * Defensive reset. In single-player one JVM hosts many server runs, so stale cuts or a stuck
     * replay marker from a previous world must not carry over - a marker left set would silently
     * disable cutting at that position.
     */
    @SubscribeEvent
    public static void onServerStarting(net.neoforged.neoforge.event.server.ServerStartingEvent event) {
        ACTIVE_CUTS.clear();
        ORIGIN_REPLAY = null;
    }

    /** One block, dropping either at the player's break position or where it stood. */
    private static boolean breakAt(Cut cut, BlockPos pos, boolean dropAtOrigin) {
        return Chainsaw.breakOne(cut.level, cut.player, pos, dropAtOrigin ? cut.origin : pos, cut.tool);
    }
}
