package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.DoubleConsumer;

/** A labelled slider bound to a callback, with its own reset-to-default button. */
public class Param implements Saveable {

    final double min, scale, def;
    final int dec;
    final String unit;
    final JSlider slider = new JSlider();
    final JLabel valueLabel = new JLabel();
    final JPanel panel = new JPanel(new BorderLayout());
    private final DoubleConsumer cb;
    private final String name;

    Param(String name, String unit, double min, double max, double init, int dec, DoubleConsumer cb) {
        this.min = min;
        this.name = name;
        this.unit = unit;
        this.dec = dec;
        this.def = init;
        this.cb = cb;
        this.scale = Math.pow(10, dec);
        slider.setMinimum(0);
        slider.setMaximum((int) Math.round((max - min) * scale));
        slider.setValue((int) Math.round((init - min) * scale));

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        right.add(valueLabel);
        right.add(UI.resetButton(this::resetDefault));
        JPanel top = new JPanel(new BorderLayout());
        top.add(new JLabel(name), BorderLayout.WEST);
        top.add(right, BorderLayout.EAST);
        panel.add(top, BorderLayout.NORTH);
        panel.add(slider, BorderLayout.CENTER);
        panel.setBorder(new EmptyBorder(1, 4, 1, 4));
        slider.addChangeListener(e -> update());
        update();
    }

    double get() {
        return min + slider.getValue() / scale;
    }

    void set(double v) {
        slider.setValue((int) Math.round((v - min) * scale));
        update();
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

    private void update() {
        double v = get();
        valueLabel.setText(String.format("%." + dec + "f %s", v, unit));
        cb.accept(v);
    }
}
