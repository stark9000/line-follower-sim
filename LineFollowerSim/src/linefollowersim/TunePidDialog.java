package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tune PID: keeps the whole robot as it is and lets the designer search the PID tab only (base speed, Kp, Ki,
 * Kd, derivative filter, corner slowdown), starting from the current values and from a fresh start.
 */
final class TunePidDialog extends JDialog {

    private final Model base;
    private final Consumer<Map<String, String>> apply;
    private final JSlider rel = new JSlider(0, 100, 50);
    private final JComboBox<String> effort = new JComboBox<>(new String[]{"Quick", "Normal", "Thorough"});
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JTextArea text = new JTextArea(9, 46);
    private final JButton go = UI.button("Tune", "suggest", "Simulate many PID settings on this track");
    private final JButton applyBtn = UI.button("Apply", "saved", "Put the tuned values into the PID tab");
    private Designer designer;
    private Designer.Candidate best;

    TunePidDialog(Window owner, Model m, Consumer<Map<String, String>> apply) {
        super(owner, "Tune PID for this track", ModalityType.APPLICATION_MODAL);
        this.base = m;
        this.apply = apply;
        JPanel p = new JPanel(new BorderLayout(6, 6));
        p.setBorder(new EmptyBorder(10, 12, 10, 12));
        JPanel top = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 3, 3, 3);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.gridx = 0;
        c.gridy = 0;
        top.add(new JLabel("Fast"), c);
        c.gridx = 1;
        c.weightx = 1;
        rel.setToolTipText("Left: fastest lap. Right: never lose the line.");
        top.add(rel, c);
        c.gridx = 2;
        c.weightx = 0;
        top.add(new JLabel("Reliable"), c);
        c.gridy = 1;
        c.gridx = 0;
        top.add(new JLabel("Effort"), c);
        c.gridx = 1;
        effort.setSelectedIndex(1);
        top.add(effort, c);
        p.add(top, BorderLayout.NORTH);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setText("The robot (motors, wheels, sensors, size) stays as it is. The tuner runs the robot on this track many "
                + "times with small changes to the PID tab - base speed, Kp, Ki, Kd, derivative filter and corner slowdown - "
                + "on slightly different robots each time (sensor mismatch, start offset, motor mismatch, tired battery), "
                + "starting from your values and from a fresh start, and keeps what drives best.\n\nPress Tune.");
        JPanel mid = new JPanel(new BorderLayout(4, 4));
        mid.add(new JScrollPane(text), BorderLayout.CENTER);
        bar.setStringPainted(true);
        bar.setString(" ");
        mid.add(bar, BorderLayout.SOUTH);
        p.add(mid, BorderLayout.CENTER);
        JButton close = UI.button("Close", null, "Close without changing anything");
        JPanel btns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btns.add(go);
        btns.add(applyBtn);
        btns.add(close);
        p.add(btns, BorderLayout.SOUTH);
        applyBtn.setEnabled(false);
        go.addActionListener(e -> start());
        applyBtn.addActionListener(e -> {
            if (best != null) {
                Map<String, String> all = Designer.toSettings(best.robot), pid = new java.util.LinkedHashMap<>();
                for (Map.Entry<String, String> en : all.entrySet()) {
                    if (en.getKey().startsWith("PID/")) {
                        pid.put(en.getKey(), en.getValue());
                    }
                }
                apply.accept(pid);
                dispose();
            }
        });
        close.addActionListener(e -> {
            if (designer != null) {
                designer.cancel();
            }
            dispose();
        });
        setContentPane(p);
        pack();
        setMinimumSize(new Dimension(560, 380));
        setLocationRelativeTo(owner);
    }

    private void start() {
        Designer.Options o = new Designer.Options();
        o.pidOnly = true;
        o.reliability = rel.getValue() / 100.0;
        o.effort = effort.getSelectedIndex();
        designer = new Designer(base, TrackAnalysis.analyze(base), o);
        go.setEnabled(false);
        applyBtn.setEnabled(false);
        new SwingWorker<List<Designer.Candidate>, Object[]>() {
            @Override
            protected List<Designer.Candidate> doInBackground() {
                return designer.run(this::publish);
            }

            @Override
            protected void process(List<Object[]> chunks) {
                Object[] last = chunks.get(chunks.size() - 1);
                bar.setValue((int) Math.round((Double) last[0] * 1000));
                bar.setString((String) last[1]);
            }

            @Override
            protected void done() {
                go.setEnabled(true);
                List<Designer.Candidate> r;
                try {
                    r = get();
                } catch (Exception ex) {
                    text.setText("Tuning failed: " + ex);
                    return;
                }
                if (r.isEmpty()) {
                    text.setText("No result (stopped?).");
                    return;
                }
                best = r.get(0);
                Model b = best.robot;
                StringBuilder sb = new StringBuilder();
                sb.append(String.format("               now      tuned%n"));
                sb.append(row("Base speed", base.baseSpeed, b.baseSpeed, "%.0f mm/s"));
                sb.append(row("Kp", base.kp, b.kp, "%.2f"));
                sb.append(row("Ki", base.ki, b.ki, "%.3f"));
                sb.append(row("Kd", base.kd, b.kd, "%.3f"));
                sb.append(row("D filter", base.dFilterMs, b.dFilterMs, "%.0f ms"));
                sb.append(row("Corner slow", base.cornerSlow * 100, b.cornerSlow * 100, "%.0f %%"));
                sb.append(String.format("%nBest (%s): %.0f mm/s along the line, line lost %.1f %% of the time, average error %.1f mm.",
                        best.hardware, best.avgSpeed, best.lostShare * 100, best.avgErr));
                sb.append("\n\nApply changes only the PID tab. Test on the real robot slower first.");
                text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
                text.setText(sb.toString());
                text.setCaretPosition(0);
                applyBtn.setEnabled(true);
            }
        }.execute();
    }

    private static String row(String name, double now, double tuned, String fmt) {
        return String.format("%-12s %10s %10s%n", name, String.format(fmt, now), String.format(fmt, tuned));
    }
}
