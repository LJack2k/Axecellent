package nl.ljack2k.axecellent.datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.ItemTagsProvider;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import nl.ljack2k.axecellent.Axecellent;
import nl.ljack2k.axecellent.chainsaw.ModTags;

import java.util.concurrent.CompletableFuture;

/**
 * Generates {@code #axecellent:chainsaw} - the tools that cut a whole tree in one
 * break - <strong>empty on purpose</strong>.
 * <p>
 * The chainsaw is not granted to axes, nor to any default list of tools. A pack
 * decides which item gets it, normally from a KubeJS server script:
 * <pre>
 * ServerEvents.tags('item', event -&gt; {
 *     event.add('axecellent:chainsaw', 'yourmod:stone_hatchet')
 * })
 * </pre>
 * So a fresh install does nothing at all until something is added to this tag. That
 * is intended: the mod is the mechanism, the pack decides which tool gets it.
 * <p>
 * The file is still generated rather than omitted, so the tag exists as a known,
 * empty tag in tooling ({@code /kubejs list-tag}, JEI's {@code #} search) instead of
 * looking like a typo.
 */
public final class ModItemTagsProvider extends ItemTagsProvider {
    public ModItemTagsProvider(PackOutput output,
                               CompletableFuture<HolderLookup.Provider> lookup,
                               CompletableFuture<TagLookup<Block>> blockTags,
                               ExistingFileHelper existing) {
        super(output, lookup, blockTags, Axecellent.MODID, existing);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        // Deliberately no .add(...) / .addTag(...) - see the class comment.
        tag(ModTags.Items.CHAINSAW);
    }
}
