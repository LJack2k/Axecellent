package nl.ljack2k.axecellent.chainsaw;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.ModConfigSpec;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import nl.ljack2k.axecellent.Axecellent;

import java.util.List;
import java.util.Locale;

/**
 * {@code /axecellent config} - read and change the chainsaw settings in-game,
 * instead of editing the TOML and reloading.
 * <p>
 * The whole command tree is <em>derived</em> from {@link Config#OPTIONS}: the option names
 * come from each value's config path, and the accepted range, the default and the
 * help text come from its {@code ValueSpec}. Adding an option to {@code Config} is therefore
 * all it takes - nothing is listed, bounded or described twice, so with three near-identical
 * per-mode sections there is nothing here to drift out of sync.
 * <p>
 * Option names are the full dotted paths, so the per-mode ones read as
 * {@code /axecellent config held.maxLogs 8} and tab-complete by section.
 * <p>
 * Changes are written straight to the config file, so they survive a restart.
 * Requires permission level 2 (op), because this changes how the world behaves for
 * everyone on the server.
 */
@EventBusSubscriber(modid = Axecellent.MODID)
public final class ConfigCommand {
    private ConfigCommand() {
    }

    /** Every option the command exposes, in config-file order. */
    private static final List<ModConfigSpec.ConfigValue<?>> OPTIONS = Config.OPTIONS;

    @SubscribeEvent
    public static void onRegisterCommands(RegisterCommandsEvent event) {
        // Registered as a child of the same "axecellent" root the dev harness uses.
        // Brigadier merges literals of the same name, so both sets of subcommands
        // coexist when the harness is active, and only this one exists in a normal
        // install.
        event.getDispatcher().register(Commands.literal(Axecellent.MODID).then(buildConfigNode()));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildConfigNode() {
        LiteralArgumentBuilder<CommandSourceStack> config = Commands.literal("config")
                .requires(source -> source.hasPermission(2))
                .executes(context -> list(context.getSource()));

        for (ModConfigSpec.ConfigValue<?> option : OPTIONS) {
            config.then(buildOptionNode(option));
        }
        config.then(Commands.literal("reset").executes(context -> resetAll(context.getSource())));
        return config;
    }

    private static LiteralArgumentBuilder<CommandSourceStack> buildOptionNode(ModConfigSpec.ConfigValue<?> option) {
        LiteralArgumentBuilder<CommandSourceStack> node = Commands.literal(pathOf(option))
                .executes(context -> describe(context.getSource(), option));

        if (option instanceof ModConfigSpec.IntValue intOption) {
            // Bounds come from the spec, so the command rejects out-of-range values
            // with Brigadier's own error before anything is written.
            ModConfigSpec.Range<Integer> range = intOption.getSpec().getRange();
            IntegerArgumentType type = range == null
                    ? IntegerArgumentType.integer()
                    : IntegerArgumentType.integer(range.getMin(), range.getMax());
            node.then(Commands.argument("value", type).executes(context ->
                    set(context.getSource(), intOption, IntegerArgumentType.getInteger(context, "value"))));
        } else if (option instanceof ModConfigSpec.DoubleValue doubleOption) {
            ModConfigSpec.Range<Double> range = doubleOption.getSpec().getRange();
            DoubleArgumentType type = range == null
                    ? DoubleArgumentType.doubleArg()
                    : DoubleArgumentType.doubleArg(range.getMin(), range.getMax());
            node.then(Commands.argument("value", type).executes(context ->
                    set(context.getSource(), doubleOption, DoubleArgumentType.getDouble(context, "value"))));
        } else if (option instanceof ModConfigSpec.BooleanValue boolOption) {
            node.then(Commands.argument("value", BoolArgumentType.bool()).executes(context ->
                    set(context.getSource(), boolOption, BoolArgumentType.getBool(context, "value"))));
        } else if (option instanceof ModConfigSpec.EnumValue<?> enumOption) {
            addEnumChoices(node, enumOption);
        }
        return node;
    }

    /**
     * One literal per enum constant, rather than a free-text argument: the accepted
     * values then tab-complete and a typo is rejected by Brigadier instead of silently
     * falling back to a default.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void addEnumChoices(LiteralArgumentBuilder<CommandSourceStack> node,
                                      ModConfigSpec.EnumValue<?> option) {
        ModConfigSpec.EnumValue raw = option;
        for (Object constant : ((Enum<?>) option.getDefault()).getDeclaringClass().getEnumConstants()) {
            Enum<?> value = (Enum<?>) constant;
            node.then(Commands.literal(value.name().toLowerCase(Locale.ROOT))
                    .executes(context -> set(context.getSource(), raw, value)));
        }
    }

    private static int list(CommandSourceStack source) {
        source.sendSuccess(() -> Component.literal("Axecellent config").withStyle(ChatFormatting.GOLD), false);
        for (ModConfigSpec.ConfigValue<?> option : OPTIONS) {
            Object current = option.get();
            Object dflt = option.getDefault();
            // Flag anything that differs from the default - the single most useful
            // thing to see at a glance when a test is behaving oddly.
            boolean changed = !String.valueOf(current).equals(String.valueOf(dflt));
            Component line = Component.literal("  " + pathOf(option) + " = ")
                    .withStyle(ChatFormatting.GRAY)
                    .append(Component.literal(String.valueOf(current))
                            .withStyle(changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE))
                    .append(changed
                            ? Component.literal("  (default " + dflt + ")").withStyle(ChatFormatting.DARK_GRAY)
                            : Component.empty());
            source.sendSuccess(() -> line, false);
        }
        return OPTIONS.size();
    }

    private static int describe(CommandSourceStack source, ModConfigSpec.ConfigValue<?> option) {
        ModConfigSpec.ValueSpec spec = option.getSpec();
        source.sendSuccess(() -> Component.literal(pathOf(option) + " = " + option.get())
                .withStyle(ChatFormatting.GOLD), false);

        ModConfigSpec.Range<?> range = spec.getRange();
        String detail = "  default " + option.getDefault()
                + (range == null ? "" : ", range " + range.getMin() + " to " + range.getMax());
        source.sendSuccess(() -> Component.literal(detail).withStyle(ChatFormatting.DARK_GRAY), false);

        // The comment from Config is the in-game documentation - no second copy of
        // the explanations lives in this class. NeoForge appends its own "Default:"
        // and "Range:" lines to that comment, which the line above already covers,
        // so those are skipped rather than printed twice.
        String comment = spec.getComment();
        if (comment != null && !comment.isBlank()) {
            for (String rawLine : comment.split("\n")) {
                String commentLine = rawLine.trim();
                if (commentLine.isBlank()
                        || commentLine.startsWith("Default:")
                        || commentLine.startsWith("Range:")) {
                    continue;
                }
                source.sendSuccess(() -> Component.literal("  " + commentLine)
                        .withStyle(ChatFormatting.GRAY), false);
            }
        }
        return 1;
    }

    private static <T> int set(CommandSourceStack source, ModConfigSpec.ConfigValue<T> option, T value) {
        if (!Config.SPEC.isLoaded()) {
            source.sendFailure(Component.literal(
                    "Axecellent config is not loaded yet - load a world first."));
            return 0;
        }
        T previous = option.get();
        if (previous.equals(value)) {
            source.sendSuccess(() -> Component.literal(pathOf(option) + " is already " + value)
                    .withStyle(ChatFormatting.GRAY), false);
            return 0;
        }
        option.set(value);
        Config.SPEC.save();
        // Broadcast to other ops: this changes the world's behaviour for everyone,
        // so it should not be a silent change by one admin.
        source.sendSuccess(() -> Component.literal(pathOf(option) + ": " + previous + " -> ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(String.valueOf(value)).withStyle(ChatFormatting.YELLOW)), true);
        Axecellent.LOGGER.info("{} changed {} from {} to {}",
                source.getTextName(), pathOf(option), previous, value);
        return 1;
    }

    private static int resetAll(CommandSourceStack source) {
        if (!Config.SPEC.isLoaded()) {
            source.sendFailure(Component.literal(
                    "Axecellent config is not loaded yet - load a world first."));
            return 0;
        }
        int changed = 0;
        for (ModConfigSpec.ConfigValue<?> option : OPTIONS) {
            if (!option.get().equals(option.getDefault())) {
                setRaw(option);
                changed++;
            }
        }
        if (changed > 0) {
            Config.SPEC.save();
        }
        int total = changed;
        source.sendSuccess(() -> Component.literal("Axecellent config reset to defaults (" + total + " changed)")
                .withStyle(ChatFormatting.GOLD), true);
        return total;
    }

    /** Separate method purely to capture the wildcard as a type variable. */
    private static <T> void setRaw(ModConfigSpec.ConfigValue<T> option) {
        option.set(option.getDefault());
    }

    private static String pathOf(ModConfigSpec.ConfigValue<?> option) {
        return String.join(".", option.getPath());
    }
}
