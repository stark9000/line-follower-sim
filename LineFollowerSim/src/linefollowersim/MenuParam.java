package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.IntConsumer;

/**
 * A drop-down that fills in other controls: choosing an entry runs the action once (with the
 * entry's index, starting at 0) and the box keeps showing what was chosen. Choosing the same entry
 * again applies it again. Not resettable - it holds no value of its own.
 */
public class MenuParam {

    final JPanel panel = new JPanel(new BorderLayout(6, 0));
    final JComboBox<String> combo;
    private boolean adjusting;

    /** items[0] is the prompt text, items[1..] are the choices. */
    MenuParam(String label, String[] items, IntConsumer cb) {
        combo = new JComboBox<>(items);
        combo.addActionListener(e -> {
            int i = combo.getSelectedIndex();
            if (adjusting || i <= 0) {
                return;
            }
            cb.accept(i - 1);
        });
        panel.add(UI.label(label), BorderLayout.WEST);
        panel.add(combo, BorderLayout.CENTER);
        panel.setBorder(new EmptyBorder(3, 4, 3, 4));
        UI.widePopup(combo);
    }

    /** Replace the entries (items[0] stays the prompt). */
    void setItems(String[] items) {
        adjusting = true;
        combo.setModel(new DefaultComboBoxModel<>(items));
        combo.setSelectedIndex(0);
        adjusting = false;
    }
}
