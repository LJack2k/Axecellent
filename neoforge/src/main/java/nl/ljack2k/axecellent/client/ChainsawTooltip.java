package nl.ljack2k.axecellent.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import nl.ljack2k.axecellent.Axecellent;
import nl.ljack2k.axecellent.chainsaw.ModTags;

/**
 * Adds a tooltip line to any tool that cuts trees, naming its mode when the tool is pinned
 * to one.
 * <p>
 * This is not decoration. Axecellent replaces an <em>enchantment</em>-based tree
 * chopper, and an enchantment advertises itself - a tooltip line and a glint. A tag
 * is invisible, and a pack is expected to grant the chainsaw to one specific tool
 * (typically with {@code "replace": true}, so ordinary axes no longer do it), which
 * leaves a player with no way at all to tell which tool is the special one. This
 * line is what restores that.
 * <p>
 * The mode is read from the tool's tags, never from the config: tags are synced to the
 * client, and a config value it may not have received yet would be worse than saying
 * nothing. A tool in the plain tag therefore just says "Chainsaw" - its mode is the
 * server's to decide and can change under it.
 * <p>
 * The wording is a translation key rather than literal text, so a pack can reword it with a
 * resource pack overriding {@code assets/axecellent/lang/en_us.json}, or drop the line by
 * translating the key to an empty string. Keep it that way - hardcoding the text would take
 * that away, and {@code examples/resourcepack/} depends on it.
 * <p>
 * Client-side only, and the only client-side behaviour in the mod. The chainsaw itself is
 * entirely server-side, so a client without Axecellent loses this line but still
 * fells trees normally. Item tags are synced to clients during login, so the tag
 * lookup is valid here in multiplayer as well as single-player.
 */
@EventBusSubscriber(modid = Axecellent.MODID, value = Dist.CLIENT)
public final class ChainsawTooltip {
    private ChainsawTooltip() {
    }

    @SubscribeEvent
    public static void onItemTooltip(ItemTooltipEvent event) {
        ItemStack stack = event.getItemStack();
        String key = null;
        if (stack.is(ModTags.Items.CHAINSAW_HELD)) {
            key = "axecellent.tooltip.chainsaw.held";
        } else if (stack.is(ModTags.Items.CHAINSAW_PROGRESSIVE)) {
            key = "axecellent.tooltip.chainsaw.progressive";
        } else if (stack.is(ModTags.Items.CHAINSAW_INSTANT)) {
            key = "axecellent.tooltip.chainsaw.instant";
        } else if (stack.is(ModTags.Items.CHAINSAW)) {
            key = "axecellent.tooltip.chainsaw";
        }
        if (key == null) {
            return;
        }

        MutableComponent line = Component.translatable(key);
        if (line.getString().isEmpty()) {
            // A resource pack translated this key to "" - that is a pack deliberately turning
            // the line off, so add nothing. Without this check it would add a blank line
            // instead, which looks like a bug rather than a choice.
            return;
        }
        // Appended after the vanilla lines, styled like an enchantment/ability line
        // rather than a warning, so it reads as part of the item.
        event.getToolTip().add(line.withStyle(ChatFormatting.GRAY));
    }
}
