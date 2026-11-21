package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import java.awt.*;

/** Small Swing helpers shared by the control classes. */
final class UI {

    /** Text of the per-control reset button. Change it if your font lacks the arrow glyph (e.g. "R"). */
    static final String RESET_LABEL = "\u21BA";

    private UI() {
    }

    static <T extends JComponent> T fix(T c) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, c.getPreferredSize().height));
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        return c;
    }

    static JPanel group(String title) {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.setBorder(new TitledBorder(title));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        return p;
    }

    /** Help text under a group: smaller and dimmer than the controls, follows the theme. */
    static final class Hint extends JLabel {

        Hint(String html) {
            super(html);
        }

        @Override
        public void updateUI() {
            super.updateUI();
            setForeground(Theme.textDim());
        }
    }

    static JLabel hint(String text) {
        JLabel l = new Hint("<html><body style='width:245px'><small>" + text + "</small></body></html>");
        l.setBorder(new EmptyBorder(4, 4, 2, 4));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    /** Let the open list of a drop-down be as wide as its longest entry, and show the chosen text as a tooltip. */
    static void widePopup(JComboBox<?> cb) {
        cb.addPopupMenuListener(new javax.swing.event.PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e) {
                Object child = cb.getAccessibleContext().getAccessibleChild(0);
                if (!(child instanceof javax.swing.plaf.basic.ComboPopup)) {
                    return;
                }
                JList<?> list = ((javax.swing.plaf.basic.ComboPopup) child).getList();
                int w = list.getPreferredSize().width + 24;
                if (w > cb.getWidth() && child instanceof JComponent) {
                    JComponent pop = (JComponent) child;
                    JScrollPane sp = (JScrollPane) SwingUtilities.getAncestorOfClass(JScrollPane.class, list);
                    if (sp != null) {
                        Dimension d = sp.getPreferredSize();
                        sp.setPreferredSize(new Dimension(w, d.height));
                        sp.setMaximumSize(new Dimension(w, d.height));
                    }
                    pop.setPreferredSize(null);
                }
            }

            @Override
            public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e) {
            }

            @Override
            public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e) {
            }
        });
        cb.addActionListener(e -> cb.setToolTipText(String.valueOf(cb.getSelectedItem())));
        cb.setToolTipText(String.valueOf(cb.getSelectedItem()));
    }

    /** A row label; short labels get a common width so the fields next to them line up. */
    static JLabel label(String text) {
        JLabel l = new JLabel(text);
        Dimension d = l.getPreferredSize();
        l.setPreferredSize(new Dimension(Math.max(d.width, 92), d.height));
        return l;
    }

    /** A button with an icon (may be null) and text; tip may be null. */
    static JButton button(String text, String icon, String tip) {
        JButton b = new JButton(text, icon == null ? null : Icons.get(icon));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setIconTextGap(5);
        b.setMargin(new Insets(2, 6, 2, 6));
        return b;
    }

    static JToggleButton toggle(String text, String icon, String tip) {
        JToggleButton b = new JToggleButton(text, icon == null ? null : Icons.get(icon));
        b.setToolTipText(tip);
        b.setFocusable(false);
        b.setIconTextGap(5);
        b.setMargin(new Insets(2, 6, 2, 6));
        return b;
    }

    static JButton resetButton(Runnable action) {
        Icon ic = Icons.get("reset_small");
        JButton b = ic != null ? new JButton(ic) : new JButton(RESET_LABEL);
        b.setBorder(new EmptyBorder(1, 2, 1, 2));
        b.setContentAreaFilled(false);
        b.setRolloverEnabled(true);
        b.setToolTipText("Reset to default");
        b.setFocusable(false);
        b.setPreferredSize(new Dimension(22, 20));
        b.addActionListener(e -> action.run());
        return b;
    }

    /** A full-width button that resets a whole group of controls. */
    static JPanel resetRow(String text, Resettable group) {
        JButton b = button(text, "reset_small", "Put every control on this tab back to its default");
        b.addActionListener(e -> group.resetDefault());
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(new EmptyBorder(6, 4, 3, 4));
        p.add(b, BorderLayout.CENTER);
        return fix(p);
    }

    /** Panel that follows the scroll pane's width (no horizontal scrolling). */
    static class ScrollPanel extends JPanel implements Scrollable {

        ScrollPanel(LayoutManager lm) {
            super(lm);
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle r, int o, int d) {
            return 14;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle r, int o, int d) {
            return 80;
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            return true;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return false;
        }
    }

    static JScrollPane scroll(JComponent c) {
        ScrollPanel wrap = new ScrollPanel(new BorderLayout());
        wrap.add(c, BorderLayout.NORTH);
        JScrollPane sp = new JScrollPane(wrap, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        sp.setBorder(null);
        return sp;
    }
}
