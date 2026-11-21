package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Kept runs: a table of their numbers, what changed in the settings between them, and their error
 * curves on one plot. Their trails are drawn on the track in their colours.
 */
final class RunsPanel extends JPanel {

    private final Model m;
    private final TrackPanel track;
    private final Supplier<Map<String, String>> settings;
    private final RunTable model = new RunTable();
    private final JTable table = new JTable(model);
    private final JTextArea details = new JTextArea(7, 26);
    private final PlotPanel plot = new PlotPanel(300, 150);
    private int lastSize = -1, lastFirst;

    private static final String[] COLS = {"", "Run", "Time s", "Best lap", "avg |e|", "max |e|", "Off %"};
    Runnable exportCsv = () -> { }, importLog = () -> { };

    /** Settings that do not change how the robot drives (left out of the comparison). */
    private static boolean ignored(String key) {
        return key.startsWith("Builder/") || key.equals("Track/Zoom") || key.equals("Track/View") || key.startsWith("Top/")
                || key.equals("Track/Show trail") || key.equals("Track/Show picture") || key.equals("Track/Opacity")
                || key.equals("Sensors/Readout");
    }

    private final class RunTable extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return m.runs.size();
        }

        @Override
        public int getColumnCount() {
            return COLS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLS[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Boolean.class : c == 1 ? Model.Run.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 0;
        }

        @Override
        public Object getValueAt(int r, int c) {
            Model.Run run = m.runs.get(r);
            switch (c) {
                case 0:
                    return run.visible;
                case 1:
                    return run;
                case 2:
                    return String.format("%.1f", run.time);
                case 3: {
                    double b = Double.NaN;
                    for (double l : run.laps) {
                        b = Double.isNaN(b) ? l : Math.min(b, l);
                    }
                    return Double.isNaN(b) ? "-" : String.format("%.2f", b);
                }
                case 4:
                    return String.format("%.1f", run.avgErr);
                case 5:
                    return String.format("%.1f", run.maxErr);
                default:
                    return run.imported ? "-" : String.format("%.0f", run.offLine * 100);
            }
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            if (c == 0) {
                m.runs.get(r).visible = (Boolean) v;
                refreshPlot();
            }
        }
    }

    RunsPanel(Model m, TrackPanel track, Supplier<Map<String, String>> settings) {
        this.m = m;
        this.track = track;
        this.settings = settings;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        JButton keep = UI.button("Keep this run", "saved", "Keep the trail and numbers of the run so far (Ctrl+K)");
        JButton clear = UI.button("Clear trail", "clear", "Wipe the drawn trail; the robot keeps going (Ctrl+L)");
        JButton remove = UI.button("Remove", "delete", "Remove the selected run");
        JButton removeAll = UI.button("Remove all", "clear", "Remove every kept run");
        keep.addActionListener(e -> keepRun());
        clear.addActionListener(e -> m.clearTrail());
        remove.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0 && r < m.runs.size()) {
                m.runs.remove(r);
                refresh();
            }
        });
        removeAll.addActionListener(e -> {
            m.runs.clear();
            refresh();
        });
        add(UI.fix(row(keep, clear)));
        add(UI.fix(row(remove, removeAll)));
        JButton csv = UI.button("Export CSV...", "save", "Save the last 2 minutes of telemetry (every 10 ms) as a CSV file");
        JButton imp = UI.button("Import log...", "open", "Show a CSV log from a real robot (or an exported run) as a kept run");
        csv.addActionListener(e -> exportCsv.run());
        imp.addActionListener(e -> importLog.run());
        add(UI.fix(row(csv, imp)));

        table.setRowHeight(20);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.getTableHeader().setReorderingAllowed(false);
        int[] w = {22, 70, 46, 54, 50, 50, 40};
        for (int i = 0; i < w.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(w[i]);
        }
        table.getColumnModel().getColumn(0).setMaxWidth(26);
        table.setDefaultRenderer(Model.Run.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                super.getTableCellRendererComponent(t, "", sel, foc, r, c);
                Model.Run run = (Model.Run) v;
                setText(run.name);
                setIcon(swatch(run.color));
                return this;
            }
        });
        table.getSelectionModel().addListSelectionListener(e -> showDetails());
        JScrollPane sp = new JScrollPane(table);
        sp.setPreferredSize(new Dimension(300, 132));
        sp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 132));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(sp);

        details.setEditable(false);
        details.setLineWrap(true);
        details.setWrapStyleWord(true);
        details.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        details.setText("Run the robot, press Keep this run, change something (Kp, speed, sensor height ...), "
                + "press Restart (F6) and keep the next run. Then pick a run here to see what changed and how it did.");
        JScrollPane dp = new JScrollPane(details);
        dp.setPreferredSize(new Dimension(300, 120));
        dp.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        dp.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(Box.createVerticalStrut(4));
        add(dp);
        add(Box.createVerticalStrut(4));
        plot.setup("error of the kept runs, last 10 s (mm)", "time (s)", "", false, false);
        plot.setEmptyText("Keep a run to see its error here");
        plot.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(plot);
        add(UI.hint("<b>avg |e|</b> and <b>max |e|</b>: how far (mm) the line was from the array centre on average and at "
                + "worst while it was seen; <b>Off %</b>: share of the time the array centre was more than one line width off the line. <b>Best lap</b>: fastest lap through the start / finish line (open track: the finish time). <b>Import log</b> reads a CSV with a time column (t_s or t_ms) and error_mm and / or x_mm, y_mm - for example the serial log of the exported sketch (#define LOG 1). Kept trails "
                + "are drawn on the track in the run's colour (untick to hide). The difference list compares a run "
                + "with the first one in the table. Up to 8 runs; they are saved in the workspace and dropped when another track is loaded."));
    }

    private static Icon swatch(Color c) {
        return new Icon() {
            @Override
            public void paintIcon(Component comp, Graphics g, int x, int y) {
                g.setColor(c);
                g.fillRoundRect(x, y + 2, 12, 12, 4, 4);
            }

            @Override
            public int getIconWidth() {
                return 14;
            }

            @Override
            public int getIconHeight() {
                return 16;
            }
        };
    }

    private static JPanel row(JButton... b) {
        JPanel r = new JPanel(new GridLayout(1, b.length, 6, 0));
        r.setBorder(new EmptyBorder(3, 4, 3, 4));
        for (JButton x : b) {
            r.add(x);
        }
        return r;
    }

    /** Keep the current run (button, Ctrl+K). */
    void keepRun() {
        if (m.t < 0.2) {
            track.toast("Let the robot run first, then keep the run");
            return;
        }
        Model.Run r = m.keepRun(settings.get());
        refresh();
        table.getSelectionModel().setSelectionInterval(m.runs.size() - 1, m.runs.size() - 1);
        track.toast("Kept " + r.name + String.format(": avg error %.1f mm - press F6 to try new settings", r.avgErr));
    }

    /** Called every frame: notice when the runs were cleared or changed elsewhere. */
    void tick() {
        int first = m.runs.isEmpty() ? 0 : System.identityHashCode(m.runs.get(0));
        if (m.runs.size() != lastSize || first != lastFirst) {
            refresh();
        }
    }

    void refresh() {
        lastSize = m.runs.size();
        lastFirst = m.runs.isEmpty() ? 0 : System.identityHashCode(m.runs.get(0));
        int sel = table.getSelectedRow();
        model.fireTableDataChanged();
        if (sel >= 0 && sel < m.runs.size()) {
            table.getSelectionModel().setSelectionInterval(sel, sel);
        }
        refreshPlot();
        showDetails();
    }

    private void refreshPlot() {
        plot.clear();
        for (Model.Run r : m.runs) {
            if (!r.visible || r.err.length < 2) {
                continue;
            }
            double[] t = new double[r.errT.length];
            double t0 = r.errT[0];
            for (int i = 0; i < t.length; i++) {
                t[i] = r.errT[i] - t0;
            }
            plot.add(t, r.err, r.color, r.name, 1.3f, false);
        }
        track.repaint();
    }

    private void showDetails() {
        int i = table.getSelectedRow();
        if (i < 0 || i >= m.runs.size()) {
            return;
        }
        Model.Run r = m.runs.get(i), ref = m.runs.get(0);
        StringBuilder sb = new StringBuilder();
        sb.append(r.name).append(r.imported ? String.format(" (imported log): %.1f s, %.2f m, avg |e| %.1f mm, max |e| %.1f mm",
                r.time, r.distance / 1000, r.avgErr, r.maxErr)
                : String.format(": %.1f s, %.2f m, avg |e| %.1f mm, max |e| %.1f mm, off the line %.0f %%, "
                + "line lost %.2f s", r.time, r.distance / 1000, r.avgErr, r.maxErr, r.offLine * 100, r.lost));
        if (r.laps.length > 0) {
            StringBuilder l = new StringBuilder();
            for (double v : r.laps) {
                l.append(l.length() > 0 ? ", " : "").append(String.format("%.2f", v));
            }
            sb.append("\nLaps (s): ").append(l);
        }
        if (r != ref && !r.imported && !ref.imported) {
            sb.append("\n\nCompared with ").append(ref.name).append(":");
            sb.append(String.format("\n  avg |e| %s, max |e| %s, off the line %s", pct(r.avgErr, ref.avgErr),
                    pct(r.maxErr, ref.maxErr), pct(r.offLine, ref.offLine)));
            List<String> diff = new ArrayList<>();
            for (Map.Entry<String, String> e : r.settings.entrySet()) {
                String a = ref.settings.get(e.getKey());
                if (!ignored(e.getKey()) && a != null && !a.equals(e.getValue())) {
                    diff.add("  " + e.getKey() + ": " + nice(a) + " -> " + nice(e.getValue()));
                }
            }
            sb.append(diff.isEmpty() ? "\n  same settings (differences come from noise or the start)" : "\n" + String.join("\n", diff));
        }
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    private static String pct(double v, double ref) {
        if (Math.abs(ref) < 1e-9) {
            return Math.abs(v) < 1e-9 ? "same" : "up from 0";
        }
        double p = (v - ref) / ref * 100;
        return Math.abs(p) < 0.5 ? "same" : String.format("%+.0f %%", p);
    }

    private static String nice(String v) {
        try {
            double d = Double.parseDouble(v);
            return d == Math.rint(d) ? String.valueOf((long) d) : String.format("%.3g", d);
        } catch (NumberFormatException ex) {
            return v;
        }
    }
}
