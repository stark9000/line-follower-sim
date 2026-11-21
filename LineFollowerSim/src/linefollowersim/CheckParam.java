package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

/** A check box with a reset button. */
public class CheckParam implements Saveable {

    final JPanel panel = new JPanel(new BorderLayout(6, 0));
    final JCheckBox box;
    private final boolean def;
    private final Consumer<Boolean> cb;
    private boolean adjusting;
    private final String name;

    CheckParam(String label, boolean def, Consumer<Boolean> cb) {
        this.def = def;
        this.cb = cb;
        this.name = label;
        box = new JCheckBox(label, def);
        box.addActionListener(e -> {
            if (!adjusting) {
                cb.accept(box.isSelected());
            }
        });
        panel.add(box, BorderLayout.CENTER);
        panel.add(UI.resetButton(this::resetDefault), BorderLayout.EAST);
        panel.setBorder(new EmptyBorder(3, 4, 3, 4));
    }

    /** Change the box without running the callback (mirrors state changed elsewhere). */
    void setQuiet(boolean b) {
        adjusting = true;
        box.setSelected(b);
        adjusting = false;
    }

    /** Set the box and run the callback. */
    void set(boolean b) {
        setQuiet(b);
        cb.accept(b);
    }

    @Override
    public String label() {
        return name;
    }

    @Override
    public String value() {
        return box.isSelected() ? "1" : "0";
    }

    @Override
    public void restore(String v) {
        set(v.trim().equals("1") || v.trim().equalsIgnoreCase("true"));
    }

    boolean isSelected() {
        return box.isSelected();
    }

    @Override
    public void resetDefault() {
        adjusting = true;
        box.setSelected(def);
        adjusting = false;
        cb.accept(def);
    }
}
