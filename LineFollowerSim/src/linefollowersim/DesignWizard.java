package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * "Design a robot for this track": a five-step wizard around Designer.
 * 1 track, 2 conditions and goal, 3 what to keep and limits, 4 run, 5 report and apply.
 */
final class DesignWizard extends JDialog {

    private final Model live;
    private final Consumer<Map<String, String>> apply;
    private final CardLayout cards = new CardLayout();
    private final JPanel deck = new JPanel(cards);
    private final JButton back = UI.button("Back", null, null), next = UI.button("Next", null, null), close = UI.button("Close", null, null);
    private int step;
    private static final String[] TITLES = {"1  Your track", "2  Conditions and goal", "3  What to keep, and limits", "4  Designing",
        "5  Result"};
    private final JLabel title = new JLabel();

    private Model base;                                    // private copy of the robot and the track
    private TrackAnalysis analysis;
    private final Designer.Options opt = new Designer.Options();
    private Designer designer;
    private List<Designer.Candidate> results = new ArrayList<>();

    // step widgets
    private final JLabel trackText = new JLabel(), condText = new JLabel();
    private final JSlider relSlider = new JSlider(0, 100, 50);
    private final JSpinner gripSp = new JSpinner(new SpinnerNumberModel(0.7, 0.05, 1.5, 0.05));
    private final JComboBox<String> effort = new JComboBox<>(new String[]{"Quick (a few seconds)", "Normal (about 10-30 s)",
        "Thorough (a minute or more, also tries car steering)"});
    private final JCheckBox[] locks = {new JCheckBox("Motors (I already have them)"), new JCheckBox("Wheels"), new JCheckBox("Battery"),
        new JCheckBox("Wheel layout (2 wheels / 4 wheels / car steering)"), new JCheckBox("Sensor array (count, pitch, height, shape)"),
        new JCheckBox("Size (axle-to-sensor distance, wheel base)"), new JCheckBox("PID and speed (only judge the hardware)")};
    private final JSpinner maxW = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 5)), maxL = new JSpinner(new SpinnerNumberModel(0, 0, 1000, 5));
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JLabel runText = new JLabel(" "), bestText = new JLabel(" ");
    private final JTextArea report = new JTextArea(12, 50);
    private final ResultTable tableModel = new ResultTable();
    private final JTable table = new JTable(tableModel);
    private final JButton applyBtn = UI.button("Apply selected design", "play", "Put this robot into every tab (you can undo by "
            + "opening your saved workspace)");

    private final class ResultTable extends AbstractTableModel {

        private final String[] cols = {"#", "Robot", "Speed mm/s", "Lost %", "avg |e|"};

        @Override
        public int getRowCount() {
            return Math.min(results.size(), 10);
        }

        @Override
        public int getColumnCount() {
            return cols.length;
        }

        @Override
        public String getColumnName(int c) {
            return cols[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            Designer.Candidate d = results.get(r);
            switch (c) {
                case 0:
                    return r + 1;
                case 1:
                    return d.hardware;
                case 2:
                    return Math.round(d.avgSpeed);
                case 3:
                    return String.format("%.1f", d.lostShare * 100);
                default:
                    return String.format("%.1f", d.avgErr);
            }
        }
    }

    DesignWizard(Window owner, Model live, Consumer<Map<String, String>> apply) {
        super(owner, "Design a robot for this track", ModalityType.APPLICATION_MODAL);
        this.live = live;
        this.apply = apply;
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setBorder(new EmptyBorder(8, 10, 4, 10));
        deck.add(page(trackText, "The designer measures the track you have now: draw or load it first. It then picks "
                + "a robot by the usual rules and tunes it by running it on this exact track, several times with small "
                + "changes (sensor noise, start offset, motor mismatch, a tired battery) so the result is robust."), "0");
        deck.add(conditionsPage(), "1");
        deck.add(locksPage(), "2");
        deck.add(runPage(), "3");
        deck.add(reportPage(), "4");
        JPanel nav = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 6));
        nav.add(back);
        nav.add(next);
        nav.add(close);
        back.addActionListener(e -> go(step - 1));
        next.addActionListener(e -> go(step + 1));
        close.addActionListener(e -> {
            if (designer != null) {
                designer.cancel();
            }
            dispose();
        });
        JPanel root = new JPanel(new BorderLayout());
        root.add(title, BorderLayout.NORTH);
        root.add(deck, BorderLayout.CENTER);
        root.add(nav, BorderLayout.SOUTH);
        setContentPane(root);
        prepare();
        go(0);
        setSize(860, 600);
        setLocationRelativeTo(owner);
    }

    private static JPanel page(JComponent main, String hint) {
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(new EmptyBorder(6, 12, 6, 12));
        main.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(main, BorderLayout.NORTH);
        JLabel h = new UI.Hint("<html><body style='width:600px'>" + hint + "</body></html>");
        p.add(h, BorderLayout.SOUTH);
        return p;
    }

    /** Copy the robot and the track (the designer must not touch what you are working on). */
    private void prepare() {
        base = Experiments.snapshot(live);
        List<TrackShape> copy = new ArrayList<>();
        for (TrackShape s : live.shapes) {
            copy.add(s.copy());
        }
        base.setTrack(live.arenaW, live.arenaH, copy, new double[]{live.sx, live.sy, live.sth});
        base.patches.addAll(live.patches);
        base.lineWidth = live.checkLineWidth();
        analysis = TrackAnalysis.analyze(base);
        trackText.setText("<html><body style='width:600px'><b>What the designer sees</b><br><br>"
                + analysis.summary().replace("\n", "<br>") + (analysis.length < 200
                ? "<br><br><font color='#d04040'><b>The track is (almost) empty - draw or pick a track first.</b></font>" : "")
                + "</body></html>");
        double contrast = (base.floorRefl - base.lineRefl);
        condText.setText(String.format("<html><body style='width:600px'><b>From the World tab</b><br>Floor %.0f %%, line %.0f %% "
                + "reflectance (contrast %.0f %%). Ambient light %.0f %%%s. Lamp flicker %.0f %%. Grip: %s.<br>Drive model: %s.</body></html>",
                base.floorRefl, base.lineRefl, contrast, base.ambient * 100, base.ambientShield > 0
                ? String.format(" (shield blocks %.0f %%)", base.ambientShield * 100) : "", base.flickerPct,
                base.tractionMu > 0 ? String.format("%.2f (set)", base.tractionMu) : "unlimited in the simulator - plan with the value below",
                base.driveModel == 1 ? "Physical (mass, torque)" : "Simple (motor lag) - choose Physical in the Motors tab for more realistic results"));
        gripSp.setEnabled(base.tractionMu <= 0);
        if (base.tractionMu > 0) {
            gripSp.setValue(base.tractionMu);
        }
        effort.setSelectedIndex(1);
    }

    private JPanel conditionsPage() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        p.add(condText);
        p.add(Box.createVerticalStrut(12));
        JPanel g = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        g.add(new JLabel("Tyre grip to plan with (mu):"));
        g.add(gripSp);
        g.add(new UI.Hint("<html>rubber on paper 0.6-0.9, hard plastic 0.3, dusty board 0.4</html>"));
        g.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(g);
        p.add(Box.createVerticalStrut(12));
        JLabel gl = new JLabel("<html><b>Goal</b></html>");
        gl.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(gl);
        java.util.Hashtable<Integer, JLabel> labels = new java.util.Hashtable<>();
        labels.put(0, new JLabel("Fastest lap"));
        labels.put(50, new JLabel("Balanced"));
        labels.put(100, new JLabel("Never lose the line"));
        relSlider.setLabelTable(labels);
        relSlider.setPaintLabels(true);
        relSlider.setMajorTickSpacing(25);
        relSlider.setPaintTicks(true);
        relSlider.setAlignmentX(Component.LEFT_ALIGNMENT);
        relSlider.setMaximumSize(new Dimension(520, 60));
        p.add(relSlider);
        p.add(Box.createVerticalStrut(12));
        JPanel e = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        e.add(new JLabel("Search:"));
        e.add(effort);
        e.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(e);
        return page(p, "Towards <b>Fastest lap</b> the designer accepts a robot that is quick but sometimes wobbles; towards "
                + "<b>Never lose the line</b> it judges every design by its worst run and slows down where needed.");
    }

    private JPanel locksPage() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        JLabel l = new JLabel("<html><b>Keep these as they are now</b> (everything else may change):</html>");
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(l);
        for (JCheckBox c : locks) {
            c.setAlignmentX(Component.LEFT_ALIGNMENT);
            p.add(c);
        }
        p.add(Box.createVerticalStrut(12));
        JPanel lim = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        lim.add(new JLabel("Largest width"));
        lim.add(maxW);
        lim.add(new JLabel("mm     largest length (axle to front)"));
        lim.add(maxL);
        lim.add(new JLabel("mm   (0 = no limit)"));
        lim.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(lim);
        return page(p, "Lock what you already own or cannot change. Motors, wheels and batteries are picked from the presets "
                + "in the Hardware tab (rough typical values - check the parts you buy). With <b>PID and speed</b> locked the "
                + "designer only compares hardware with your current tuning.");
    }

    private JPanel runPage() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        bar.setAlignmentX(Component.LEFT_ALIGNMENT);
        bar.setStringPainted(true);
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24));
        runText.setAlignmentX(Component.LEFT_ALIGNMENT);
        bestText.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(bar);
        p.add(Box.createVerticalStrut(8));
        p.add(runText);
        p.add(Box.createVerticalStrut(8));
        p.add(bestText);
        JButton stop = UI.button("Stop and show the best so far", "pause", null);
        stop.addActionListener(e -> {
            if (designer != null) {
                designer.cancel();
            }
        });
        stop.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(Box.createVerticalStrut(12));
        p.add(stop);
        return page(p, "Every design is driven on your track several times. Your robot in the main window is not touched.");
    }

    private JPanel reportPage() {
        JPanel p = new JPanel(new BorderLayout(0, 6));
        report.setEditable(false);
        report.setLineWrap(true);
        report.setWrapStyleWord(true);
        report.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        JScrollPane rs = new JScrollPane(report);
        rs.setPreferredSize(new Dimension(800, 220));
        table.setRowHeight(20);
        table.getColumnModel().getColumn(0).setMaxWidth(30);
        table.getColumnModel().getColumn(1).setPreferredWidth(560);
        table.setDefaultRenderer(Object.class, new javax.swing.table.DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean foc, int r, int c) {
                Component comp = super.getTableCellRendererComponent(t, v, sel, foc, r, c);
                setToolTipText(c == 1 && v != null ? v.toString() : null);      // the full robot description
                return comp;
            }
        });
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane ts = new JScrollPane(table);
        ts.setPreferredSize(new Dimension(800, 150));
        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.add(rs, BorderLayout.CENTER);
        top.add(ts, BorderLayout.SOUTH);
        p.add(top, BorderLayout.CENTER);
        applyBtn.addActionListener(e -> {
            int r = Math.max(0, table.getSelectedRow());
            if (r < results.size()) {
                apply.accept(Designer.toSettings(results.get(r).robot));
                dispose();
            }
        });
        JPanel b = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        b.add(applyBtn);
        p.add(b, BorderLayout.SOUTH);
        p.setBorder(new EmptyBorder(6, 12, 6, 12));
        return p;
    }

    private void go(int s) {
        if (s == 3 && step == 2) {
            startDesign();
        }
        step = Math.max(0, Math.min(4, s));
        cards.show(deck, Integer.toString(step));
        title.setText(TITLES[step]);
        back.setEnabled(step > 0 && step < 3);
        next.setEnabled(step < 2 || (step == 2 && analysis.length >= 200));
        next.setText(step == 2 ? "Design it!" : "Next");
        next.setVisible(step < 3);
        back.setVisible(step < 3);
    }

    private void startDesign() {
        opt.reliability = relSlider.getValue() / 100.0;
        opt.grip = ((Number) gripSp.getValue()).doubleValue();
        opt.effort = effort.getSelectedIndex();
        opt.lockMotor = locks[0].isSelected();
        opt.lockWheels = locks[1].isSelected();
        opt.lockBattery = locks[2].isSelected();
        opt.lockLayout = locks[3].isSelected();
        opt.lockArray = locks[4].isSelected();
        opt.lockSize = locks[5].isSelected();
        opt.lockPid = locks[6].isSelected();
        opt.maxWidth = ((Number) maxW.getValue()).doubleValue();
        opt.maxLength = ((Number) maxL.getValue()).doubleValue();
        designer = new Designer(base, analysis, opt);
        new SwingWorker<List<Designer.Candidate>, Object[]>() {
            @Override
            protected List<Designer.Candidate> doInBackground() {
                return designer.run(this::publish);
            }

            @Override
            protected void process(List<Object[]> chunks) {
                Object[] last = chunks.get(chunks.size() - 1);
                bar.setValue((int) Math.round((Double) last[0] * 1000));
                runText.setText((String) last[1]);
                Designer.Candidate b = (Designer.Candidate) last[2];
                if (b != null) {
                    bestText.setText(String.format("<html>Best so far: <b>%.0f mm/s</b> along the line, lost %.1f %% - %s</html>",
                            b.avgSpeed, b.lostShare * 100, b.hardware));
                }
            }

            @Override
            protected void done() {
                try {
                    results = get();
                } catch (Exception ex) {
                    results = new ArrayList<>();
                    report.setText("The design run failed: " + ex);
                }
                tableModel.fireTableDataChanged();
                if (!results.isEmpty()) {
                    table.getSelectionModel().setSelectionInterval(0, 0);
                    StringBuilder sb = new StringBuilder(designer.cancelled() ? "Stopped early - best so far.\n\n" : "");
                    for (String r : results.get(0).reasons) {
                        sb.append("- ").append(r).append('\n');
                    }
                    sb.append("\nThe table lists the alternatives; pick one and press Apply. Apply sets the Hardware, Motors and PID "
                            + "tabs (save your workspace first if you want to come back). Then build it, measure the real robot "
                            + "(Motors > From real measurements) and fine-tune.");
                    report.setText(sb.toString());
                    report.setCaretPosition(0);
                }
                applyBtn.setEnabled(!results.isEmpty());
                go(4);
            }
        }.execute();
    }
}
