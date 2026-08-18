package nl.ljack2k.axecellent;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import nl.ljack2k.axecellent.chainsaw.Config;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point for Axecellent - break the base of a tree with a tool carrying the
 * {@code #axecellent:chainsaw} item tag and the whole tree comes down.
 * <p>
 * There is nothing to register: no items, no blocks, no enchantment. The mod is a
 * single break-event handler ({@link nl.ljack2k.axecellent.chainsaw.ChainsawHandler},
 * found by annotation scan) plus three tags and a server config. All of the
 * behaviour is server-side, so a client without the mod installed still sees the
 * correct result when playing on a server that has it.
 */
@Mod(Axecellent.MODID)
public final class Axecellent {
    public static final String MODID = "axecellent";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    public Axecellent(IEventBus modBus, ModContainer container, Dist dist) {
        // SERVER type: the chainsaw is a world rule, so the file lives per-world and in
        // multiplayer the server's copy is the one that decides.
        container.registerConfig(ModConfig.Type.SERVER, Config.SPEC);

        // Nothing else. In particular there is deliberately NO reference to the dev
        // harness here: it self-registers by annotation, and its package is stripped
        // from the published jar. A guarded call from this constructor would still
        // leave an invokestatic to a missing class in the shipped bytecode, which
        // would crash with NoClassDefFoundError if anyone ever set the dev system
        // property on a real install.
    }
}
