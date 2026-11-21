package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.IntConsumer;

/** A labelled drop-down with a reset button. */
public class ComboParam implements Saveable {

    final JPanel panel = new JPanel(new BorderLayout(6, 0));
    final JComboBox<String> combo;
    private final int def;
    private final IntConsumer cb;
    private boolean adjusting;
    private final String name;

    ComboParam(String label, String[] items, int def, IntConsumer cb) {
        this.def = def;
        this.cb = cb;
        this.name = label;
        combo = new JComboBox<>(items);
        combo.setSelectedIndex(def);
        combo.addActionListener(e -> {
            if (!adjusting) {
                cb.accept(combo.getSelectedIndex());
            }
        });
        panel.add(UI.label(label), BorderLayout.WEST);
        panel.add(combo, BorderLayout.CENTER);
        panel.add(UI.resetButton(this::resetDefault), BorderLayout.EAST);
        panel.setBorder(new EmptyBorder(3, 4, 3, 4));
        UI.widePopup(combo);
    }

    int index() {
        return combo.getSelectedIndex();
    }

    /** Change the selection without running the callback (used to mirror state changed elsewhere). */
    void setQuiet(int i) {
        adjusting = true;
        combo.setSelectedIndex(i);
        adjusting = false;
    }

    @Override
    public String label() {
        return name;
    }

    @Override
    public String value() {
        return Integer.toString(index());
    }

    /** Select an entry and run the action. */
    void select(int i) {
        adjusting = true;
        combo.setSelectedIndex(Math.max(0, Math.min(combo.getItemCount() - 1, i)));
        adjusting = false;
        cb.accept(index());
    }

    @Override
    public void restore(String v) {
        select(Integer.parseInt(v.trim()));
    }

    /** Always re-applies the default (even if it is already selected, e.g. to reload a hand-drawn track). */
    @Override
    public void resetDefault() {
        adjusting = true;
        combo.setSelectedIndex(def);
        adjusting = false;
        cb.accept(def);
    }
}
