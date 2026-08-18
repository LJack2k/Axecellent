package nl.ljack2k.axecellent.chainsaw;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.CommonHooks;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Works out what a cut should remove, and removes individual blocks on request.
 * <p>
 * It does not decide <em>when</em> blocks come out. {@link #plan} produces an ordered
 * list of steps and the caller spends it: {@link ChainsawHandler} walks the whole list at
 * once for {@link CutMode#INSTANT}, or hands it to {@link ChainsawCascade}, which releases
 * it a few logs per tick for {@link CutMode#PROGRESSIVE}, or one bite per completed chop
 * for {@link CutMode#HELD}. Keeping the decision apart from the timing is what lets all
 * three modes share exactly one copy of the rules.
 * <p>
 * Shape of the planning work:
 * <ol>
 *   <li>flood-fill connected logs from the origin, recording each one's <em>depth</em> -
 *       how many logs away through the tree it is - capped by {@link Config#MAX_LOGS};</li>
 *   <li>optionally require attached leaves, so a log building is not a "tree";</li>
 *   <li>work out how many blocks the tool can pay for and drop the rest;</li>
 *   <li>flood-fill the canopy outward, remembering which log each leaf hangs from;</li>
 *   <li>order the logs deepest-first, each carrying its own leaves.</li>
 * </ol>
 * Deepest-first means branch tips go before the trunk, and the last thing standing is
 * the log the player hit. Depth is measured through the log network rather than as a
 * straight line, so a branch curling back towards the player still falls early.
 */
public final class Chainsaw {
    private Chainsaw() {
    }

    /** Offsets covering all 26 neighbours of a block. Built once. */
    private static final BlockPos[] NEIGHBOURS = buildNeighbours();

    /**
     * Re-entrancy guard. With {@link Config#RESPECT_BLOCK_PROTECTION} on we fire a real
     * break event per block so claim mods can veto it - and that event would otherwise
     * come straight back into our own handler and recurse. Thread-local rather than a
     * plain boolean because the integrated server and a dedicated server can both tick
     * while another thread is elsewhere in the game.
     */
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> false);

    /** One log and the leaves hanging from it: the unit a cut removes at a time. */
    public record Step(BlockPos log, List<BlockPos> leaves) {
    }

    /**
     * Everything a cut will remove, already ordered, plus the durability it costs.
     * <p>
     * Excludes the block the player hit: vanilla owns that one. INSTANT lets it break there
     * and then, while the animated modes keep it standing and hand it back at the end.
     */
    public record Plan(List<Step> steps, int durability, int logCount, int leafCount) {
        public static final Plan NOTHING = new Plan(List.of(), 0, 0, 0);

        public boolean isEmpty() {
            return steps.isEmpty();
        }

        /**
         * The same cut worked from the other end: nearest log first, far end last.
         * <p>
         * Used when the player crouches in {@link CutMode#HELD}, so the log giving way is the
         * one under their crosshair and the cut eats away from them rather than towards them.
         */
        public Plan reversed() {
            List<Step> flipped = new ArrayList<>(steps);
            Collections.reverse(flipped);
            return new Plan(flipped, durability, logCount, leafCount);
        }
    }

    /** What one cut actually removed. Reported by the dev harness. */
    public record Result(int logs, int leaves, int durability) {
    }

    /** True while a cut is removing a block on this thread - see {@link #ACTIVE}. */
    public static boolean isActive() {
        return ACTIVE.get();
    }

    // --- planning ------------------------------------------------------------

    /**
     * Decide what the tree at {@code origin} loses.
     *
     * @param origin the log the player broke. Never part of the returned steps.
     */
    public static Plan plan(ServerLevel level, ServerPlayer player, BlockPos origin, ItemStack tool) {
        Map<BlockPos, Integer> depths = collectLogs(level, origin, Config.MAX_LOGS.get());
        if (Config.REQUIRE_LEAVES.get() && !hasAttachedLeaves(level, depths.keySet())) {
            return Plan.NOTHING;
        }

        // The origin anchors the search and counts towards maxLogs, but vanilla is the
        // one that removes it, so it is not ours to schedule.
        List<BlockPos> logs = new ArrayList<>(depths.keySet());
        logs.remove(origin);
        if (logs.isEmpty()) {
            return Plan.NOTHING;
        }

        // Deepest first, so the cut travels inwards and finishes where the player hit.
        logs.sort(Comparator.<BlockPos>comparingInt(pos -> depths.getOrDefault(pos, 0)).reversed());

        boolean creative = player.isCreative();
        int perLog = creative ? 0 : Config.DURABILITY_PER_LOG.get();
        int perLeaf = creative || !Config.DURABILITY_FOR_LEAVES.get() ? 0 : Config.DURABILITY_PER_LOG.get();
        int budget = durabilityBudget(tool, creative);

        if (perLog > 0) {
            int affordable = budget / perLog;
            if (affordable <= 0) {
                return Plan.NOTHING;
            }
            if (logs.size() > affordable) {
                logs = new ArrayList<>(logs.subList(0, affordable));
            }
        }
        int spent = logs.size() * perLog;

        // Canopy is collected only for the logs actually being taken, so nothing is
        // left floating over a trunk the tool could not afford to finish.
        Map<BlockPos, List<BlockPos>> canopy = new HashMap<>();
        int leafCount = 0;
        if (Config.CLEAR_LEAVES.get()) {
            int leafBudget = perLeaf > 0 ? (budget - spent) / perLeaf : Integer.MAX_VALUE;
            canopy = collectLeaves(level, logs, origin, Config.MAX_LEAVES.get(), leafBudget);
            for (List<BlockPos> leaves : canopy.values()) {
                leafCount += leaves.size();
            }
            spent += leafCount * perLeaf;
        }

        List<Step> steps = new ArrayList<>(logs.size());
        for (BlockPos log : logs) {
            steps.add(new Step(log, canopy.getOrDefault(log, List.of())));
        }
        // Leaves that only ever touched the origin log have no step to ride on, so they
        // join the final step rather than being abandoned above a stump.
        List<BlockPos> orphaned = canopy.get(origin);
        if (orphaned != null && !orphaned.isEmpty()) {
            Step last = steps.get(steps.size() - 1);
            List<BlockPos> merged = new ArrayList<>(last.leaves());
            merged.addAll(orphaned);
            steps.set(steps.size() - 1, new Step(last.log(), merged));
        }

        return new Plan(steps, spent, logs.size(), leafCount);
    }

    /**
     * Remove everything in a plan right now - this is {@link CutMode#INSTANT}. The
     * progressive mode spends the same plan over several ticks instead.
     *
     * @param dropAt where drops land, per {@link Config#DROPS_AT_BREAK_POSITION}
     */
    public static Result execute(ServerLevel level, ServerPlayer player, Plan plan,
                                 BlockPos dropAt, ItemStack tool, boolean chargeDurability) {
        int logs = 0;
        int leaves = 0;
        for (Step step : plan.steps()) {
            if (breakOne(level, player, step.log(), dropAt, tool)) {
                logs++;
            }
            for (BlockPos leaf : step.leaves()) {
                if (breakOne(level, player, leaf, dropAt, tool)) {
                    leaves++;
                }
            }
        }
        if (chargeDurability && plan.durability() > 0) {
            tool.hurtAndBreak(plan.durability(), level, player, item -> {});
        }
        return new Result(logs, leaves, plan.durability());
    }

    /**
     * Remove one block the way a player break would: protection first, then drops, then
     * removal with the usual particles and sound.
     *
     * @return false if the block was vetoed, already changed, or out of a loaded chunk
     */
    public static boolean breakOne(ServerLevel level, ServerPlayer player, BlockPos pos,
                                   BlockPos dropAt, ItemStack tool) {
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) {
            return false;
        }

        ACTIVE.set(true);
        try {
            if (Config.RESPECT_BLOCK_PROTECTION.get()
                    && CommonHooks.fireBlockBreak(level, player.gameMode.getGameModeForPlayer(), player, pos, state)
                            .isCanceled()) {
                return false;
            }

            BlockEntity blockEntity = state.hasBlockEntity() ? level.getBlockEntity(pos) : null;
            if (!player.isCreative()) {
                if (Config.DROPS_AT_BREAK_POSITION.get()) {
                    // getDrops + popResource rather than dropResources: only this pair
                    // lets the items appear somewhere other than the block's own
                    // position, and popResource still honours the doTileDrops gamerule.
                    for (ItemStack drop : Block.getDrops(state, level, pos, blockEntity, player, tool)) {
                        Block.popResource(level, dropAt, drop);
                    }
                } else {
                    Block.dropResources(state, level, pos, blockEntity, player, tool);
                }
            }
            // false: drops are handled above (or deliberately absent in creative).
            return level.destroyBlock(pos, false, player);
        } finally {
            ACTIVE.set(false);
        }
    }

    // --- internals -----------------------------------------------------------

    /**
     * How much durability a cut may spend. One point is always reserved for the origin
     * block, which vanilla charges separately; {@link Config#STOP_BEFORE_TOOL_BREAKS}
     * reserves one more so the tool survives the tree.
     */
    private static int durabilityBudget(ItemStack tool, boolean creative) {
        if (creative || !tool.isDamageableItem()) {
            return Integer.MAX_VALUE;
        }
        int reserved = 1 + (Config.STOP_BEFORE_TOOL_BREAKS.get() ? 1 : 0);
        return Math.max(0, tool.getMaxDamage() - tool.getDamageValue() - reserved);
    }

    /**
     * Connected logs and how far each is from the origin through the log network, origin
     * included at depth 0. Capped at {@code max}.
     */
    private static Map<BlockPos, Integer> collectLogs(ServerLevel level, BlockPos origin, int max) {
        Map<BlockPos, Integer> depths = new HashMap<>();
        Deque<BlockPos> queue = new ArrayDeque<>();

        depths.put(origin.immutable(), 0);
        queue.add(origin.immutable());
        while (!queue.isEmpty() && depths.size() < max) {
            BlockPos current = queue.poll();
            int next = depths.get(current) + 1;
            for (BlockPos offset : NEIGHBOURS) {
                BlockPos neighbour = current.offset(offset).immutable();
                if (depths.containsKey(neighbour) || depths.size() >= max) {
                    continue;
                }
                if (level.isLoaded(neighbour) && level.getBlockState(neighbour).is(ModTags.Blocks.CHAINSAW_LOGS)) {
                    depths.put(neighbour, next);
                    queue.add(neighbour);
                }
            }
        }
        return depths;
    }

    /**
     * Canopy connected to the given logs, grouped by the log each leaf hangs from.
     * Spreads leaf-to-leaf so a full crown is cleared, not just the ring touching the
     * trunk; a leaf reached through other leaves belongs to whichever log started that
     * chain.
     *
     * @param max        {@link Config#MAX_LEAVES}, 0 meaning no limit
     * @param affordable how many the tool can pay for (only relevant when leaves cost
     *                   durability)
     */
    private static Map<BlockPos, List<BlockPos>> collectLeaves(ServerLevel level, List<BlockPos> logs,
                                                               BlockPos origin, int max, int affordable) {
        int limit = Math.min(max <= 0 ? Integer.MAX_VALUE : max, Math.max(0, affordable));
        Map<BlockPos, List<BlockPos>> byLog = new HashMap<>();
        if (limit == 0) {
            return byLog;
        }

        Set<BlockPos> seen = new HashSet<>(logs);
        Deque<BlockPos> queue = new ArrayDeque<>();
        Map<BlockPos, BlockPos> owner = new HashMap<>();

        // Seed from every log (and the origin), so ownership starts at the branch the
        // leaf is actually attached to.
        for (BlockPos log : logs) {
            queue.add(log);
            owner.put(log, log);
        }
        BlockPos originKey = origin.immutable();
        if (seen.add(originKey)) {
            queue.add(originKey);
        }
        owner.put(originKey, originKey);

        int found = 0;
        while (!queue.isEmpty() && found < limit) {
            BlockPos current = queue.poll();
            BlockPos currentOwner = owner.get(current);
            for (BlockPos offset : NEIGHBOURS) {
                BlockPos neighbour = current.offset(offset).immutable();
                if (!seen.add(neighbour)) {
                    continue;
                }
                if (!level.isLoaded(neighbour) || !isCuttableLeaf(level.getBlockState(neighbour))) {
                    continue;
                }
                owner.put(neighbour, currentOwner);
                byLog.computeIfAbsent(currentOwner, key -> new ArrayList<>()).add(neighbour);
                queue.add(neighbour);
                if (++found >= limit) {
                    break;
                }
            }
        }
        return byLog;
    }

    /**
     * Leaves that grew there, not leaves someone placed. Player-placed leaves are
     * {@code persistent}, and skipping those is what makes an uncapped leaf sweep safe
     * around builds. A leaf block without the property (some modded ones) counts as
     * natural.
     */
    private static boolean isCuttableLeaf(BlockState state) {
        if (!state.is(ModTags.Blocks.CHAINSAW_LEAVES)) {
            return false;
        }
        return !state.hasProperty(LeavesBlock.PERSISTENT) || !state.getValue(LeavesBlock.PERSISTENT);
    }

    /** Does this log group have natural leaves on it, i.e. is it a tree? */
    private static boolean hasAttachedLeaves(ServerLevel level, Set<BlockPos> logs) {
        for (BlockPos log : logs) {
            for (BlockPos offset : NEIGHBOURS) {
                BlockPos pos = log.offset(offset);
                if (level.isLoaded(pos) && isCuttableLeaf(level.getBlockState(pos))) {
                    return true;
                }
            }
        }
        return false;
    }

    private static BlockPos[] buildNeighbours() {
        List<BlockPos> offsets = new ArrayList<>(26);
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                for (int z = -1; z <= 1; z++) {
                    if (x != 0 || y != 0 || z != 0) {
                        offsets.add(new BlockPos(x, y, z));
                    }
                }
            }
        }
        return offsets.toArray(new BlockPos[0]);
    }
}
