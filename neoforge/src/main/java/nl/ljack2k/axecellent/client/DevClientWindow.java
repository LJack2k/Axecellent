package nl.ljack2k.axecellent.client;

import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import nl.ljack2k.axecellent.Axecellent;
import org.lwjgl.glfw.GLFW;

/**
 * Client-only, dev-only: move the dev client window to a fixed screen position on
 * startup.
 * <p>
 * Minecraft does not remember its window position between launches, so on a
 * multi-monitor setup every {@code runClient} lands on the primary display and has
 * to be dragged off the monitor you are actually working on. The position comes
 * from {@code -Daxecellent.devWindowX/Y}, set by the client run configs from
 * {@code dev_window_x} / {@code dev_window_y} in gradle.properties.
 * <p>
 * Stripped from the published jar (see the {@code jar} task) - this is a
 * convenience for whoever is developing the mod, not behaviour for players.
 */
@EventBusSubscriber(modid = Axecellent.MODID, value = Dist.CLIENT)
public final class DevClientWindow {
    private DevClientWindow() {
    }

    public static final String X_PROPERTY = "axecellent.devWindowX";
    public static final String Y_PROPERTY = "axecellent.devWindowY";

    /**
     * Self-registering by annotation, so no shipped class names this one. That
     * matters: this class is stripped from the published jar, and a guarded call
     * from shipped code would still leave a reference to a missing class in the
     * bytecode - enough to crash with NoClassDefFoundError if someone ever set the
     * dev window property on a real install.
     */
    @SubscribeEvent
    public static void onClientSetup(FMLClientSetupEvent event) {
        if (System.getProperty(X_PROPERTY) == null) {
            return;
        }
        // enqueueWork: GLFW window calls must happen on the render thread.
        event.enqueueWork(DevClientWindow::place);
    }

    /** Must run on the render thread - GLFW window calls are main-thread only. */
    public static void place() {
        Integer x = readCoordinate(X_PROPERTY);
        Integer y = readCoordinate(Y_PROPERTY);
        if (x == null || y == null) {
            return;
        }
        Window window = Minecraft.getInstance().getWindow();
        long handle = window.getWindow();

        // glfwSetWindowPos positions the CONTENT area, not the frame - so passing the
        // monitor's top-left puts the title bar above the screen edge, where it can't
        // be grabbed. Offset by the frame borders so the configured coordinate means
        // "top-left of the window as the user sees it".
        int[] frameLeft = new int[1];
        int[] frameTop = new int[1];
        GLFW.glfwGetWindowFrameSize(handle, frameLeft, frameTop, new int[1], new int[1]);
        GLFW.glfwSetWindowPos(handle, x + frameLeft[0], y + frameTop[0]);

        Axecellent.LOGGER.info("[Axecellent] Dev client window moved to {},{} (frame inset {},{}).",
                x, y, frameLeft[0], frameTop[0]);
    }

    private static Integer readCoordinate(String property) {
        String raw = System.getProperty(property);
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            // Negative values are normal: a monitor left of the primary one has
            // negative virtual-desktop coordinates.
            return Integer.valueOf(raw.trim());
        } catch (NumberFormatException e) {
            Axecellent.LOGGER.warn("[Axecellent] Ignoring -D{}={} - not an integer.", property, raw);
            return null;
        }
    }
}
