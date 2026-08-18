package nl.ljack2k.axecellent.client;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.ItemTooltipEvent;
import nl.ljack2k.axecellent.Axecellent;
import nl.ljack2k.axecellent.chainsaw.ModTags;

/**
 * Adds a tooltip line to any item in {@link ModTags.Items#CHAINSAW}.
 * <p>
 * This is not decoration. Axecellent replaces an <em>enchantment</em>-based tree
 * chopper, and an enchantment advertises itself - a tooltip line and a glint. A tag
 * is invisible, and a pack is expected to grant the chainsaw to one specific tool
 * (typically with {@code "replace": true}, so ordinary axes no longer do it), which
 * leaves a player with no way at all to tell which tool is the special one. This
 * line is what restores that.
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
        if (!event.getItemStack().is(ModTags.Items.CHAINSAW)) {
            return;
        }
        // Appended after the vanilla lines, styled like an enchantment/ability line
        // rather than a warning, so it reads as part of the item.
        event.getToolTip().add(Component.translatable("axecellent.tooltip.chainsaw")
                .withStyle(ChatFormatting.GRAY));
    }
}
