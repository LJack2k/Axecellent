package nl.ljack2k.axecellent.chainsaw;

import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.ArrayList;
import java.util.List;

/**
 * Server config for the chainsaw. Registered as {@code ModConfig.Type.SERVER} because
 * cutting happens server-side and is a world rule: in multiplayer the server's
 * copy decides, and the file lives per-world rather than per-installation.
 * <p>
 * The tags in {@link ModTags} decide <em>which tools and blocks</em> participate;
 * this decides <em>how much</em> they do. Anything a pack is likely to want to
 * tune is here rather than hardcoded.
 * <p>
 * <b>A setting lives in a mode's section only if it is specific to that mode.</b>
 * {@code [progressive]} and {@code [held]} hold the settings that make those modes work the way
 * a pack wants them to - the pace of each, and what crouching does in HELD. Everything else
 * means exactly the same thing whichever way the tree falls, so it is global: one
 * {@code maxLogs}, one durability price, one answer to whether claims are respected. There is
 * no {@code [instant]} section because instant has nothing of its own to tune; not having a
 * pace is what makes it instant.
 * <p>
 * Duplicating the shared settings once per mode was tried and rejected: it triples the file for
 * settings whose meaning never changes, and turns one obvious knob into three that can silently
 * disagree.
 */
public final class Config {
    private Config() {
    }

    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    /**
     * Every option, in the order it was defined. Collected as a side effect of defining them so
     * {@link ConfigCommand} has nothing to keep in sync.
     */
    private static final List<ModConfigSpec.ConfigValue<?>> DEFINED = new ArrayList<>();

    private static <T extends ModConfigSpec.ConfigValue<?>> T track(T value) {
        DEFINED.add(value);
        return value;
    }

    // --- chainsaw -------------------------------------------------------------

    public static final ModConfigSpec.EnumValue<CutMode> MODE = track(BUILDER
            .comment("How the tree comes apart, for tools in #axecellent:chainsaw.",
                    "A tool in #axecellent:chainsaw_progressive, _held or _instant uses that mode",
                    "instead and ignores this setting, so a pack can make the mode a property of",
                    "the tool - a wooden axe you have to work with, a netherite axe that does it",
                    "for you - rather than one server-wide choice.",
                    "PROGRESSIVE - one break takes the whole tree down a few logs per tick, starting",
                    "              from the log furthest away through the tree's own branches and",
                    "              working back to the one you hit, which goes last. Each log takes",
                    "              its leaves with it.",
                    "HELD        - your own chopping drives it: every chop you finish takes one more",
                    "              log, and between chops nothing happens at all. Stop chopping and",
                    "              the tree stops coming apart. Crouch to work outward from your own",
                    "              block instead of inward from the far end. Charges durability per",
                    "              block as it falls, so stopping early only costs what you cut.",
                    "INSTANT     - the whole tree vanishes in the same tick as the break.",
                    "PROGRESSIVE and HELD have their own sections below for the settings that only",
                    "apply to them. Everything else here applies to all three.")
            .translation("axecellent.configuration.mode")
            .defineEnum("chainsaw.mode", CutMode.PROGRESSIVE));

    public static final ModConfigSpec.IntValue MAX_CONCURRENT_CUTS = track(BUILDER
            .comment("How many trees may be part-way down at once, server-wide - both PROGRESSIVE",
                    "and HELD. Past this limit further trees are cut instantly rather than queued,",
                    "so a busy server degrades in animation quality instead of in memory.",
                    "32 is sized for a populated server: each pending cut is a short list of block",
                    "positions, so the cost of a high limit is negligible next to being the thing",
                    "that silently turns the mod off during a busy evening.")
            .translation("axecellent.configuration.max_concurrent_cuts")
            .defineInRange("chainsaw.maxConcurrentCuts", 32, 1, 256));

    public static final ModConfigSpec.IntValue MAX_LOGS = track(BUILDER
            .comment("Maximum logs removed by one break, the origin block included.",
                    "This is the main safety limit: a break can never remove more trunk than this.")
            .translation("axecellent.configuration.max_logs")
            .defineInRange("chainsaw.maxLogs", 64, 1, 4096));

    public static final ModConfigSpec.BooleanValue CLEAR_LEAVES = track(BUILDER
            .comment("Also clear the leaves connected to the logs it cuts.")
            .translation("axecellent.configuration.clear_leaves")
            .define("chainsaw.clearLeaves", true));

    public static final ModConfigSpec.IntValue MAX_LEAVES = track(BUILDER
            .comment("Maximum leaves cleared by one break. 0 means no limit.",
                    "Uncapped is safe in practice because only NON-persistent leaves are ever",
                    "touched - leaves a player placed by hand are persistent and always survive.")
            .translation("axecellent.configuration.max_leaves")
            .defineInRange("chainsaw.maxLeaves", 0, 0, 65536));

    public static final ModConfigSpec.BooleanValue REQUIRE_LEAVES = track(BUILDER
            .comment("Only cut a log group that has non-persistent leaves attached, i.e. only",
                    "things that are actually trees. Turn this on to protect log-built",
                    "structures: with it off (the default), any connected group of logs falls.")
            .translation("axecellent.configuration.require_leaves")
            .define("chainsaw.requireLeaves", false));

    public static final ModConfigSpec.BooleanValue DROPS_AT_BREAK_POSITION = track(BUILDER
            .comment("Where the drops appear.",
                    "true  - everything drops at the block you broke, at your feet.",
                    "false - each block drops where it stood, vanilla-style.")
            .translation("axecellent.configuration.drops_at_break_position")
            .define("chainsaw.dropsAtBreakPosition", true));

    public static final ModConfigSpec.BooleanValue SNEAK_TO_DISABLE = track(BUILDER
            .comment("Hold sneak to break a single log without cutting the whole tree.",
                    "In HELD mode this is overridden by held.sneakStartsAtYou, which repurposes",
                    "crouch to reverse the cut direction instead of disabling it.")
            .translation("axecellent.configuration.sneak_to_disable")
            .define("chainsaw.sneakToDisable", true));

    public static final ModConfigSpec.BooleanValue ENABLED_IN_CREATIVE = track(BUILDER
            .comment("Use the chainsaw in creative mode too. Off by default: in creative every break",
                    "is instant, which makes accidental mass removal very easy.")
            .translation("axecellent.configuration.enabled_in_creative")
            .define("chainsaw.enabledInCreative", false));

    public static final ModConfigSpec.BooleanValue RESPECT_BLOCK_PROTECTION = track(BUILDER
            .comment("Fire a block-break event for every extra block, so claim/protection mods",
                    "can veto them individually. Leave on unless it causes a conflict; turning",
                    "it off makes the chainsaw ignore land claims.")
            .translation("axecellent.configuration.respect_block_protection")
            .define("chainsaw.respectBlockProtection", true));

    // --- progressive: only what makes PROGRESSIVE itself ----------------------

    public static final ModConfigSpec.IntValue LOGS_PER_TICK = track(BUILDER
            .comment("How many logs fall per tick. Their leaves ride along for free, so this alone",
                    "sets the pace - 2 takes a 64-log tree down in about 1.6s.",
                    "Higher is faster and less visible; 20 or more is effectively instant.")
            .translation("axecellent.configuration.progressive.logs_per_tick")
            .defineInRange("progressive.logsPerTick", 2, 1, 64));

    // --- held: only what makes HELD itself ------------------------------------

    public static final ModConfigSpec.IntValue HELD_LOGS_PER_CHOP = track(BUILDER
            .comment("How many logs one completed chop takes down.",
                    "The pace is your own chopping: finish a chop on the tree and that many logs",
                    "give way (furthest first, or nearest first while crouching). Stop chopping and",
                    "nothing moves - there is no timer running underneath. 1 means one chop, one",
                    "log, which is the whole point of the mode; raise it if that feels too slow on",
                    "big trees.")
            .translation("axecellent.configuration.held.logs_per_chop")
            .defineInRange("held.logsPerChop", 1, 1, 64));

    public static final ModConfigSpec.IntValue HELD_RESUME_WINDOW = track(BUILDER
            .comment("How many seconds a part-cut tree waits for your next chop before it gives up",
                    "and the rest becomes ordinary blocks again.")
            .translation("axecellent.configuration.held.resume_window")
            .defineInRange("held.resumeWindow", 10, 1, 300));

    public static final ModConfigSpec.BooleanValue HELD_SNEAK_STARTS_AT_YOU = track(BUILDER
            .comment("What crouching does.",
                    "true  - crouch and the cut starts at the log you are hitting and works away",
                    "        from you, one log per chop, instead of starting at the far end. So a",
                    "        single chop takes just the log in front of you, and carrying on eats",
                    "        into the tree from there. Precise control when you want it, and the",
                    "        dramatic far-end-first fell when you don't.",
                    "false - crouching falls back to chainsaw.sneakToDisable, i.e. it turns the",
                    "        chainsaw off and you break one log the vanilla way.",
                    "NOTE: with this on, crouching no longer disables the chainsaw in HELD mode.",
                    "Crouch plus a single chop is the equivalent escape hatch.")
            .translation("axecellent.configuration.held.sneak_starts_at_you")
            .define("held.sneakStartsAtYou", true));

    // --- durability -----------------------------------------------------------

    public static final ModConfigSpec.IntValue DURABILITY_PER_LOG = track(BUILDER
            .comment("Durability charged per extra log cut. The origin block is already charged",
                    "by vanilla, so this applies to the rest. 0 makes cutting free.")
            .translation("axecellent.configuration.durability_per_log")
            .defineInRange("durability.perLog", 1, 0, 64));

    public static final ModConfigSpec.BooleanValue DURABILITY_FOR_LEAVES = track(BUILDER
            .comment("Charge durability for cleared leaves as well. Off by default - leaves are",
                    "uncapped, so charging for them can consume a whole tool on one big tree.")
            .translation("axecellent.configuration.durability_for_leaves")
            .define("durability.chargeForLeaves", false));

    public static final ModConfigSpec.BooleanValue STOP_BEFORE_TOOL_BREAKS = track(BUILDER
            .comment("Stop cutting while the tool still has one durability point left, instead of",
                    "breaking it mid-tree. The remaining logs simply stay standing.")
            .translation("axecellent.configuration.stop_before_tool_breaks")
            .define("durability.stopBeforeBreak", true));

    public static final ModConfigSpec SPEC = BUILDER.build();

    /** Every option the config exposes, in file order. */
    public static final List<ModConfigSpec.ConfigValue<?>> OPTIONS = List.copyOf(DEFINED);
}
