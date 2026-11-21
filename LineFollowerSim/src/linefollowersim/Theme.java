package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.metal.DefaultMetalTheme;
import javax.swing.plaf.metal.MetalLookAndFeel;
import java.awt.*;

/**
 * Light and dark themes built on Swing's own Metal look and feel (no external libraries, no Nimbus).
 * Custom painted panels (track, graphs, plots) ask this class for their colours.
 */
final class Theme {

    private static boolean dark;

    private Theme() {
    }

    static boolean isDark() {
        return dark;
    }

    // ---- palette used by the custom painted panels
    static Color bg() {
        return dark ? new Color(0x2B2D31) : new Color(0xEEF0F3);
    }

    static Color plotBg() {
        return dark ? new Color(0x1E1F22) : Color.WHITE;
    }

    static Color plotBorder() {
        return dark ? new Color(0x4A4D55) : new Color(0xB4B4B4);
    }

    static Color gridLine() {
        return dark ? new Color(0x35373C) : new Color(0xE6E6E6);
    }

    static Color zeroLine() {
        return dark ? new Color(0x55585F) : new Color(0xCDCDCD);
    }

    static Color text() {
        return dark ? new Color(0xDFE1E5) : new Color(0x3C3F44);
    }

    static Color textDim() {
        return dark ? new Color(0x9DA0A8) : Color.GRAY;
    }

    static Color viewBg() {
        return dark ? new Color(0x18191B) : new Color(0x4A4D52);
    }

    static Color accent() {
        return new Color(0x3B82F6);
    }

    /** Colour of OK / CHECK / FIX tags in HTML (readable on both backgrounds). */
    static String okHex() {
        return dark ? "#4ade80" : "#1b8a3a";
    }

    static String warnHex() {
        return dark ? "#fbbf24" : "#c77700";
    }

    static String badHex() {
        return dark ? "#f87171" : "#c0261d";
    }

    // ---- Metal themes
    private static final class Palette extends DefaultMetalTheme {

        private final boolean d;
        private final FontUIResource font = new FontUIResource(Font.DIALOG, Font.PLAIN, 12);
        private final FontUIResource small = new FontUIResource(Font.DIALOG, Font.PLAIN, 11);

        Palette(boolean d) {
            this.d = d;
        }

        @Override
        public String getName() {
            return d ? "LineFollowerSim dark" : "LineFollowerSim light";
        }

        private static ColorUIResource c(int rgb) {
            return new ColorUIResource(rgb);
        }

        // primary = selection / focus / accents, secondary = surfaces and borders
        @Override
        protected ColorUIResource getPrimary1() {
            return d ? c(0x5B9BFF) : c(0x2F6FD6);
        }

        @Override
        protected ColorUIResource getPrimary2() {
            return d ? c(0x3D6FBF) : c(0x8DB4F0);
        }

        @Override
        protected ColorUIResource getPrimary3() {
            return d ? c(0x2F4F80) : c(0xCFE0FA);
        }

        @Override
        protected ColorUIResource getSecondary1() {
            return d ? c(0x5A5E66) : c(0x9AA1AC);
        }

        @Override
        protected ColorUIResource getSecondary2() {
            return d ? c(0x3C3F45) : c(0xD3D7DE);
        }

        @Override
        protected ColorUIResource getSecondary3() {
            return d ? c(0x2B2D31) : c(0xEEF0F3);
        }

        @Override
        protected ColorUIResource getBlack() {
            return d ? c(0xDFE1E5) : c(0x202124);
        }

        @Override
        protected ColorUIResource getWhite() {
            return d ? c(0x1E1F22) : c(0xFFFFFF);
        }

        @Override
        public ColorUIResource getControlTextColor() {
            return getBlack();
        }

        @Override
        public ColorUIResource getMenuForeground() {
            return getBlack();
        }

        @Override
        public ColorUIResource getUserTextColor() {
            return getBlack();
        }

        @Override
        public ColorUIResource getSystemTextColor() {
            return getBlack();
        }

        @Override
        public ColorUIResource getInactiveControlTextColor() {
            return d ? c(0x7A7E87) : c(0x9AA0A6);
        }

        @Override
        public ColorUIResource getHighlightedTextColor() {
            return c(0xFFFFFF);
        }

        @Override
        public ColorUIResource getTextHighlightColor() {
            return d ? c(0x2F5FA8) : c(0x3B82F6);
        }

        @Override
        public FontUIResource getControlTextFont() {
            return font;
        }

        @Override
        public FontUIResource getSystemTextFont() {
            return font;
        }

        @Override
        public FontUIResource getUserTextFont() {
            return font;
        }

        @Override
        public FontUIResource getMenuTextFont() {
            return font;
        }

        @Override
        public FontUIResource getWindowTitleFont() {
            return font;
        }

        @Override
        public FontUIResource getSubTextFont() {
            return small;
        }
    }

    /** Store a default as a UI resource, so the next theme switch is allowed to replace it. */
    private static void put(String key, Object v) {
        if (v instanceof Color && !(v instanceof javax.swing.plaf.UIResource)) {
            v = new ColorUIResource((Color) v);
        } else if (v instanceof javax.swing.border.Border && !(v instanceof javax.swing.plaf.UIResource)) {
            v = new javax.swing.plaf.BorderUIResource((javax.swing.border.Border) v);
        } else if (v instanceof Insets && !(v instanceof javax.swing.plaf.UIResource)) {
            Insets i = (Insets) v;
            v = new javax.swing.plaf.InsetsUIResource(i.top, i.left, i.bottom, i.right);
        }
        UIManager.put(key, v);
    }

    /** Switch theme. Call on the Swing thread; pass the windows to refresh (none at start-up). */
    static void apply(boolean darkTheme, Window... windows) {
        dark = darkTheme;
        try {
            MetalLookAndFeel.setCurrentTheme(new Palette(darkTheme));
            UIManager.setLookAndFeel(new MetalLookAndFeel());
        } catch (UnsupportedLookAndFeelException ex) {
            return;
        }
        Color surface = bg(), field = plotBg(), fg = text(), border = dark ? new Color(0x4A4D55) : new Color(0xB9BFC8);
        Color button = dark ? new Color(0x3A3D43) : new Color(0xF8F9FB);
        put("Button.background", button);
        put("ToggleButton.background", button);
        put("Button.select", dark ? new Color(0x2F4F80) : new Color(0xCFE0FA));
        put("ToggleButton.select", dark ? new Color(0x2F4F80) : new Color(0xCFE0FA));
        put("Button.focus", new Color(0, 0, 0, 0));
        put("ToggleButton.focus", new Color(0, 0, 0, 0));
        put("CheckBox.focus", new Color(0, 0, 0, 0));
        put("Button.border", BorderFactory.createCompoundBorder(new LineBorder(border, 1, true), new EmptyBorder(3, 8, 3, 8)));
        put("ToggleButton.border", BorderFactory.createCompoundBorder(new LineBorder(border, 1, true), new EmptyBorder(3, 8, 3, 8)));
        put("Panel.background", surface);
        put("Viewport.background", surface);
        put("ScrollPane.background", surface);
        put("TabbedPane.background", dark ? new Color(0x34373C) : new Color(0xDDE1E7));
        put("TabbedPane.selected", surface);
        put("TabbedPane.contentAreaColor", surface);
        put("TabbedPane.tabAreaBackground", surface);
        put("TabbedPane.borderHightlightColor", border);
        put("TabbedPane.unselectedBackground", dark ? new Color(0x34373C) : new Color(0xDDE1E7));
        put("TabbedPane.focus", new Color(0, 0, 0, 0));
        put("TabbedPane.tabInsets", new Insets(5, 6, 5, 8));
        put("TabbedPane.textIconGap", 6);
        put("TitledBorder.titleColor", dark ? new Color(0x8AB4F8) : new Color(0x2F5FA8));
        put("TitledBorder.border", new LineBorder(border, 1, true));
        put("TitledBorder.font", new FontUIResource(Font.DIALOG, Font.BOLD, 12));
        put("ToolTip.background", dark ? new Color(0x3C3F45) : new Color(0xFFFBE6));
        put("ToolTip.foreground", fg);
        put("ToolTip.border", new LineBorder(border));
        put("Slider.background", surface);
        put("Slider.focus", new Color(0, 0, 0, 0));
        put("Slider.thumb", accent());
        put("CheckBox.background", surface);
        put("Label.foreground", fg);
        put("Label.disabledForeground", textDim());
        put("ComboBox.background", field);
        put("ComboBox.foreground", fg);
        put("ComboBox.buttonBackground", button);
        put("ComboBox.selectionBackground", dark ? new Color(0x2F5FA8) : new Color(0x3B82F6));
        put("ComboBox.selectionForeground", Color.WHITE);
        put("FormattedTextField.background", field);
        put("TextField.background", field);
        put("TextArea.background", field);
        put("TextArea.foreground", fg);
        put("OptionPane.background", surface);
        put("OptionPane.messageForeground", fg);
        put("ScrollBar.background", surface);
        put("ScrollBar.track", surface);
        put("ScrollBar.thumb", dark ? new Color(0x55585F) : new Color(0xC3C8D0));
        put("ScrollBar.thumbShadow", dark ? new Color(0x55585F) : new Color(0xC3C8D0));
        put("ScrollBar.thumbHighlight", dark ? new Color(0x55585F) : new Color(0xC3C8D0));
        put("ScrollBar.thumbDarkShadow", dark ? new Color(0x55585F) : new Color(0xC3C8D0));
        put("ScrollBar.width", 12);
        put("Separator.foreground", border);
        put("ToolBar.background", surface);
        put("FileChooser.listViewBackground", field);
        put("List.background", field);
        put("List.foreground", fg);
        put("Table.background", field);
        put("Table.foreground", fg);
        for (Window w : windows) {
            SwingUtilities.updateComponentTreeUI(w);
            w.repaint();
        }
        Icons.themeChanged();
    }
}
