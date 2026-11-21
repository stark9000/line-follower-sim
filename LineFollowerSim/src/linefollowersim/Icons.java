package linefollowersim;

import javax.imageio.ImageIO;
import javax.swing.Icon;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * PNG icons from /linefollowersim/icons. Each icon follows the theme: in the dark theme the dark-grey
 * parts are lightened so they stay visible. On high-DPI screens the @2x picture is drawn.
 */
final class Icons {

    private static final Map<String, BufferedImage[]> CACHE = new HashMap<>();    // name -> {1x, 2x} for the current theme

    private Icons() {
    }

    static void themeChanged() {
        CACHE.clear();
    }

    private static BufferedImage read(String file) {
        try (InputStream in = Icons.class.getResourceAsStream("icons/" + file)) {
            return in == null ? null : ImageIO.read(in);
        } catch (IOException ex) {
            return null;
        }
    }

    /** Lighten near-black / grey pixels (the dark theme); coloured pixels stay as they are. */
    private static BufferedImage forDark(BufferedImage src) {
        if (src == null) {
            return null;
        }
        BufferedImage out = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < src.getHeight(); y++) {
            for (int x = 0; x < src.getWidth(); x++) {
                int p = src.getRGB(x, y), a = p >>> 24, r = (p >> 16) & 255, g = (p >> 8) & 255, b = p & 255;
                int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
                if (a > 0 && mx < 150 && mx - mn < 40) {                       // dark and nearly grey
                    int l = 235 - mx / 3;
                    r = Math.min(255, l + (r - mn) / 2);
                    g = Math.min(255, l + (g - mn) / 2);
                    b = Math.min(255, l + (b - mn) / 2);
                }
                out.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
            }
        }
        return out;
    }

    private static BufferedImage[] images(String name) {
        BufferedImage[] im = CACHE.get(name);
        if (im == null) {
            BufferedImage a = read(name + ".png"), b = read(name + "@2x.png");
            im = Theme.isDark() ? new BufferedImage[]{forDark(a), forDark(b)} : new BufferedImage[]{a, b};
            CACHE.put(name, im);
        }
        return im;
    }

    /** A 16 x 16 icon that always paints in the current theme (null if the PNG is missing). */
    static Icon get(String name) {
        if (images(name)[0] == null) {
            return null;
        }
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g0, int x, int y) {
                BufferedImage[] im = images(name);
                Graphics2D g = (Graphics2D) g0.create();
                double scale = g.getTransform().getScaleX();
                if (c != null && !c.isEnabled()) {
                    g.setComposite(AlphaComposite.SrcOver.derive(0.35f));
                }
                if (im[1] != null && scale > 1.2) {                                // HiDPI: draw the sharp 32 px version
                    g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g.drawImage(im[1], x, y, 16, 16, null);
                } else if (im[0] != null) {
                    g.drawImage(im[0], x, y, null);
                }
                g.dispose();
            }

            @Override
            public int getIconWidth() {
                return 16;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    /** The application icon in several sizes (for the title bar, task bar and Alt-Tab). */
    static List<Image> appIcons() {
        List<Image> l = new ArrayList<>();
        for (int s : new int[]{16, 24, 32, 48, 64, 128, 256}) {
            BufferedImage im = read("app_" + s + ".png");
            if (im != null) {
                l.add(im);
            }
        }
        return l;
    }
}
