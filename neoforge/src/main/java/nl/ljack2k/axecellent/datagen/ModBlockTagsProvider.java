package nl.ljack2k.axecellent.datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.BlockTags;
import net.neoforged.neoforge.common.data.BlockTagsProvider;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import nl.ljack2k.axecellent.Axecellent;
import nl.ljack2k.axecellent.chainsaw.ModTags;

import java.util.concurrent.CompletableFuture;

/**
 * Generates what counts as trunk and what counts as canopy.
 * <p>
 * Both default to the vanilla tags ({@code #minecraft:logs},
 * {@code #minecraft:leaves}), which nearly every mod that adds trees already
 * contributes to. They exist as separate Axecellent tags anyway so a pack can add
 * a tree-like block that is not in the vanilla tags - or remove one - without
 * touching the vanilla tags and affecting unrelated recipes and mechanics.
 * <p>
 * Note {@code #minecraft:logs} also covers crimson and warped stems, so nether
 * "trees" are felled too. That is intended; drop them from
 * {@code axecellent:chainsaw_logs} in a datapack if not wanted.
 */
public final class ModBlockTagsProvider extends BlockTagsProvider {
    public ModBlockTagsProvider(PackOutput output,
                                CompletableFuture<HolderLookup.Provider> lookup,
                                ExistingFileHelper existing) {
        super(output, lookup, Axecellent.MODID, existing);
    }

    @Override
    protected void addTags(HolderLookup.Provider provider) {
        tag(ModTags.Blocks.CHAINSAW_LOGS)
                .addTag(BlockTags.LOGS);

        tag(ModTags.Blocks.CHAINSAW_LEAVES)
                .addTag(BlockTags.LEAVES);
    }
}
