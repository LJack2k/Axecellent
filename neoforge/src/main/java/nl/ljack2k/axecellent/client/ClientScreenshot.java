package nl.ljack2k.axecellent.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.neoforged.fml.loading.FMLPaths;

/**
 * Client-only: grab the current frame to rundir/screenshots/axshot.png (fixed
 * name so it is overwritten and easy to read back). Driven by the dev harness's
 * /axecellent shot command so an agent can capture what the joined client
 * actually renders. Dev-only - stripped from the published jar.
 */
public final class ClientScreenshot {
    private ClientScreenshot() {
    }

    public static void take() {
        Minecraft mc = Minecraft.getInstance();
        Screenshot.grab(
                FMLPaths.GAMEDIR.get().toFile(),
                "axshot.png",
                mc.getMainRenderTarget(),
                component -> {});
    }
}
