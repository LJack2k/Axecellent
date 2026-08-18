import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import javax.imageio.ImageIO;

/**
 * Cuts the icon set from the master logo, and copies the shipped one into the mod's
 * resources.
 *
 * Run from the repo root, no build needed (JDK 11+ single-file launch):
 *
 *   java branding/MakeIcons.java
 *
 * The master is {@code branding/logo_fullsize.jpg} - 1024x1024, the artwork as
 * delivered. Everything else in branding/ is derived from it, so edit or replace the
 * master and re-run rather than touching the PNGs by hand.
 */
public class MakeIcons {

    private static final Path MASTER = Path.of("branding", "logo_fullsize.jpg");
    private static final Path BRANDING = Path.of("branding");

    /** Copied into the jar as the mod-list logo. Big enough to look right, small enough not to dominate a 45KB jar. */
    private static final int SHIPPED = 128;

    private static final Path SHIPPED_TO = Path.of("neoforge", "src", "main", "resources", "icon.png");

    public static void main(String[] args) throws Exception {
        BufferedImage master = ImageIO.read(MASTER.toFile());
        if (master == null) {
            throw new IllegalStateException("Could not read " + MASTER.toAbsolutePath());
        }
        if (master.getWidth() != master.getHeight()) {
            throw new IllegalStateException("Master must be square, got "
                    + master.getWidth() + "x" + master.getHeight()
                    + " - crop it first, so nothing is squashed here.");
        }
        System.out.println("master " + master.getWidth() + "x" + master.getHeight());

        // 512 for a Modrinth project icon, 256 spare, 128 shipped, 64 to check legibility.
        // Nothing consumes anything smaller, and below 32 the outlines turn to mush.
        for (int size : new int[] {512, 256, 128, 64}) {
            BufferedImage scaled = shrink(master, size);
            Path to = BRANDING.resolve("icon-" + size + ".png");
            ImageIO.write(scaled, "PNG", to.toFile());
            System.out.println("  " + to + "  " + Files.size(to) / 1024 + "KB");

            if (size == SHIPPED) {
                Path icon = BRANDING.resolve("icon.png");
                ImageIO.write(scaled, "PNG", icon.toFile());
                Files.copy(icon, SHIPPED_TO, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("  " + icon + " -> " + SHIPPED_TO);
            }
        }
    }

    /**
     * Halve repeatedly, then take the last step to the target.
     * <p>
     * One straight 1024 -> 64 draw samples so sparsely that the black outlines break
     * up and the blade turns to noise. Halving averages every pixel in, which is what
     * keeps the outlines solid at the small sizes the mod list actually uses.
     */
    private static BufferedImage shrink(BufferedImage src, int target) {
        BufferedImage current = src;
        int size = src.getWidth();
        while (size / 2 > target) {
            size /= 2;
            current = draw(current, size);
        }
        return size == target ? current : draw(current, target);
    }

    private static BufferedImage draw(BufferedImage src, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }
}
