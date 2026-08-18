package nl.ljack2k.axecellent.chainsaw;

/**
 * How a tree comes apart once the chainsaw decides to cut it.
 */
public enum CutMode {
    /**
     * The signature behaviour, and the default. One break brings the whole tree down a few
     * logs per tick, starting from the log furthest away through the tree's own branch
     * structure and working back to the block the player hit, which goes last. Each log takes
     * its attached leaves with it, so the canopy dissolves along with the branch holding it up.
     */
    PROGRESSIVE,

    /**
     * The player's own chopping drives the cut. Every chop they finish takes down
     * {@link Config#HELD_LOGS_PER_CHOP} more logs, and between chops nothing happens at all -
     * there is no timer underneath, so stopping chopping stops the tree coming apart.
     * <p>
     * Crouching reverses which end goes first ({@link Config#HELD_SNEAK_STARTS_AT_YOU}): logs
     * peel away from the player instead of falling in from the far side, so a single chop takes
     * only the log in front of them. Either way the block being chopped is kept standing - it
     * is what they keep hitting, and without it there would be nothing left to chop - and it
     * goes last.
     * <p>
     * Durability is charged per block as it falls, so stopping early only costs what was
     * actually cut. A part-cut tree is forgotten after {@link Config#HELD_RESUME_WINDOW}
     * seconds with no chop.
     */
    HELD,

    /**
     * The whole tree disappears in the same tick as the break. Cheaper, and what a server that
     * dislikes visual flourish (or a pack automating tree farms) will want.
     */
    INSTANT
}
