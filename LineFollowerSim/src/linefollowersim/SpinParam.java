package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.DoubleConsumer;

/** An editable number field with up/down arrows (spinner) and a reset button. */
public class SpinParam implements Saveable {

    final JPanel panel = new JPanel(new BorderLayout(6, 0));
    final JSpinner spinner;
    private final double def;
    private final DoubleConsumer cb;
    private final String name;

    SpinParam(String name, String unit, double min, double max, double step, double init, String format,
            DoubleConsumer cb) {
        this(name, unit, min, max, step, init, format, cb, true);
    }

    /** withReset = false: no reset button (for values that depend on a loaded file). */
    SpinParam(String name, String unit, double min, double max, double step, double init, String format,
            DoubleConsumer cb, boolean withReset) {
        this.def = init;
        this.cb = cb;
        this.name = name;
        spinner = new JSpinner(new SpinnerNumberModel(init, min, max, step));
        spinner.setEditor(new JSpinner.NumberEditor(spinner, format));
        spinner.setPreferredSize(new Dimension(86, 22));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(spinner);
        JLabel u = new JLabel(unit);                                   // fixed unit column: the fields line up
        u.setPreferredSize(new Dimension(Math.max(u.getPreferredSize().width, 40), u.getPreferredSize().height));
        right.add(u);
        if (withReset) {
            right.add(UI.resetButton(this::resetDefault));
        }
        panel.add(new JLabel(name), BorderLayout.WEST);
        panel.add(right, BorderLayout.EAST);
        panel.setBorder(new EmptyBorder(2, 4, 2, 4));
        spinner.addChangeListener(e -> cb.accept(get()));
        cb.accept(init);
    }

    double get() {
        return ((Number) spinner.getValue()).doubleValue();
    }

    void set(double v) {
        SpinnerNumberModel sm = (SpinnerNumberModel) spinner.getModel();
        double lo = ((Number) sm.getMinimum()).doubleValue(), hi = ((Number) sm.getMaximum()).doubleValue();
        spinner.setValue(Math.max(lo, Math.min(hi, v)));                // the model itself does not check its limits
        cb.accept(get());
    }

    @Override
    public String label() {
        return name;
    }

    @Override
    public String value() {
        return Double.toString(get());
    }

    @Override
    public void restore(String v) {
        set(Double.parseDouble(v));
    }

    @Override
    public void resetDefault() {
        set(def);
    }
}
