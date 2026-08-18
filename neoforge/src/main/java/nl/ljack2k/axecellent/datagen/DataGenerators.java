package nl.ljack2k.axecellent.datagen;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.DataGenerator;
import net.minecraft.data.PackOutput;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.common.data.ExistingFileHelper;
import net.neoforged.neoforge.data.event.GatherDataEvent;
import nl.ljack2k.axecellent.Axecellent;

import java.util.concurrent.CompletableFuture;

/**
 * Data generation entry point - run with:  gradlew :neoforge:runData
 * <p>
 * The mod adds no items or blocks, so the only generated data is the three tags
 * that drive the chainsaw. No model, recipe or loot providers exist here on purpose.
 * <p>
 * Output goes to neoforge/src/generated/resources, a committed resource root
 * (release.yml only runs `gradlew build`, so CI never regenerates). After changing
 * a default tag: run runData, then commit the generated diff.
 * <p>
 * Discovered by annotation rather than wired from the @Mod constructor, because
 * the datagen/ package is stripped from the published jar - a method reference
 * from the entry point would crash a normal install.
 */
@EventBusSubscriber(modid = Axecellent.MODID)
public final class DataGenerators {
    private DataGenerators() {
    }

    @SubscribeEvent
    public static void onGatherData(GatherDataEvent event) {
        DataGenerator generator = event.getGenerator();
        PackOutput output = generator.getPackOutput();
        ExistingFileHelper existing = event.getExistingFileHelper();
        CompletableFuture<HolderLookup.Provider> lookup = event.getLookupProvider();

        // Item tags need the block tag provider's contents, because ItemTagsProvider
        // can copy block tags into item tags - so the block provider is built first
        // and handed over even though nothing is copied today.
        ModBlockTagsProvider blockTags = new ModBlockTagsProvider(output, lookup, existing);
        generator.addProvider(event.includeServer(), blockTags);
        generator.addProvider(event.includeServer(),
                new ModItemTagsProvider(output, lookup, blockTags.contentsGetter(), existing));
    }
}
