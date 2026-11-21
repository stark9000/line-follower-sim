package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The Analysis tab: a live design check with the latency budget, the calibration demo,
 * a design suggestion, and the automatic tests (step response, 90 degree corner, look-ahead x speed).
 * Everything slow runs on a background thread on a private copy of the model.
 */
final class AnalysisTab extends JPanel {

    private final Model m;
    private final Consumer<DesignCheck.Suggestion> applySuggestion;

    private final JLabel report = new JLabel("<html><small>Open this tab to check the design.</small></html>");
    private final PlotPanel calPlot = new PlotPanel(300, 150);
    private final JTextArea suggestArea = new JTextArea(11, 26);
    private final JButton applyBtn = UI.button("Apply to the Hardware tab", "play", "Copy the suggested pitch, count, axle distance and height");
    private final JLabel testResult = new JLabel(" ");
    private final PlotPanel testPlot = new PlotPanel(300, 170);
    private final JButton[] buttons;

    private DesignCheck.Suggestion lastSuggestion;
    private String shownSig = "", wantedSig = "";
    private long changedAt;
    private boolean reportRunning, busy;

    AnalysisTab(Model model, Consumer<DesignCheck.Suggestion> applySuggestion) {
        this.m = model;
        this.applySuggestion = applySuggestion;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));

        JPanel design = UI.group("Design check");
        report.setBorder(new EmptyBorder(2, 6, 4, 4));
        report.setAlignmentX(Component.LEFT_ALIGNMENT);
        report.setVerticalAlignment(SwingConstants.TOP);
        design.add(report);
        design.add(UI.hint("Checked for the width of the line on the current track and the base speed in the PID tab. "
                + "It refreshes a moment after any setting changes."));
        add(design);                                       // grows with the report: do not fix its height

        JPanel cal = UI.group("Calibration demo: does a straight line look straight?");
        calPlot.setup("measured position vs true position (mm)", "true", "", false, false);
        calPlot.setAlignmentX(Component.LEFT_ALIGNMENT);
        cal.add(calPlot);
        cal.add(UI.hint("Analog weighted position with sensor mismatch. <font color='#c0261d'>Red</font>: one white/black pair "
                + "for all sensors, the line looks bent and off-centre. <font color='#1b8a3a'>Green</font>: each sensor "
                + "calibrated by a sweep over the line. Grey: perfect. Set the mismatch in the Sensors tab."));
        add(UI.fix(cal));

        JPanel sug = UI.group("Suggest a design");
        JButton rules = UI.button("Rules", "suggest", "Suggest a design from the usual rules of thumb");
        JButton sim = UI.button("Simulate corners", "test", "Run the corner test at several distances and pick the best");
        rules.addActionListener(e -> suggest(false));
        sim.addActionListener(e -> suggest(true));
        suggestArea.setEditable(false);
        suggestArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        suggestArea.setLineWrap(true);
        suggestArea.setWrapStyleWord(true);
        suggestArea.setText("Press a button for a design that follows the rules for this line width and speed.");
        applyBtn.setEnabled(false);
        applyBtn.addActionListener(e -> {
            if (lastSuggestion != null) {
                AnalysisTab.this.applySuggestion.accept(lastSuggestion);
            }
        });
        sug.add(UI.fix(row(rules, sim)));
        JScrollPane sp = new JScrollPane(suggestArea, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        sp.setPreferredSize(new Dimension(280, 190));
        sp.setAlignmentX(Component.LEFT_ALIGNMENT);
        sug.add(sp);
        sug.add(UI.fix(row(applyBtn)));
        sug.add(UI.hint("<b>Rules</b> uses half the line width for the pitch and 2.5x the distance travelled during the "
                + "delay for the axle distance. <b>Simulate</b> runs the 90 degree corner test at several distances and "
                + "picks the shortest one that corners within one line width and stays stable on a straight."));
        add(UI.fix(sug));

        JPanel tests = UI.group("Tests (run on a copy: your robot is not disturbed)");
        JButton step = UI.button("Step response", "step", "Start beside a straight line and see how the robot comes back");
        JButton corner = UI.button("90\u00B0 corner", "corner", "A straight run into a sharp right-hand corner");
        JButton sweep = UI.button("Look-ahead x speed sweep", "grid", "The corner test for several speeds and axle distances");
        step.addActionListener(e -> runStep());
        corner.addActionListener(e -> runCorner());
        sweep.addActionListener(e -> runSweep());
        tests.add(UI.fix(row(step, corner)));
        tests.add(UI.fix(row(sweep)));
        testResult.setBorder(new EmptyBorder(4, 6, 2, 4));
        testResult.setAlignmentX(Component.LEFT_ALIGNMENT);
        testResult.setVerticalAlignment(SwingConstants.TOP);
        tests.add(testResult);
        testPlot.setAlignmentX(Component.LEFT_ALIGNMENT);
        testPlot.setEmptyText("Run a test to see its plot here");
        tests.add(testPlot);
        tests.add(UI.hint("<b>Step response</b>: the robot starts beside a straight line; the labels are Sluggish "
                + "(no overshoot, slow), Graceful, Lively (12-25 % overshoot) and Nervous (more, or it keeps swinging). "
                + "A digital array cannot hold the line better than about half its pitch, so that wobble is not counted as "
                + "overshoot. <b>Corner</b>: a straight run into a sharp right-hand corner; overshoot = how far the array "
                + "centre goes past the corner. <b>Sweep</b>: the corner test for several speeds and axle distances."));
        add(tests);                                        // grows with the result text
        buttons = new JButton[]{rules, sim, step, corner, sweep};
    }

    private static JPanel row(JButton... b) {
        JPanel r = new JPanel(new GridLayout(1, b.length, 6, 0));
        r.setBorder(new EmptyBorder(3, 4, 3, 4));
        for (JButton x : b) {
            x.setFocusable(false);
            r.add(x);
        }
        return r;
    }

    // ------------------------------------------------------------------ live design report
    /** Called every frame. Recomputes the report shortly after the settings stopped changing, while this tab is visible. */
    void tick(boolean visible) {
        if (!visible) {
            return;
        }
        String sig = m.settingsSignature();
        if (!sig.equals(wantedSig)) {
            wantedSig = sig;
            changedAt = System.nanoTime();
        }
        if (!wantedSig.equals(shownSig) && !reportRunning && System.nanoTime() - changedAt > 300_000_000L) {
            startReport(wantedSig);
        }
    }

    /** The report must be redrawn in the new theme's colours. */
    void themeChanged() {
        shownSig = "";
        changedAt = 0;
    }

    private Model copy() {
        Model s = Experiments.snapshot(m);
        s.lineWidth = m.checkLineWidth();                  // judge the line that is actually drawn (thin / wide presets)
        if (m.steerMode != 0) {
            s.trackRadiusHint = TrackAnalysis.analyze(m).designRadius();
        }
        s.x = m.x;
        s.y = m.y;
        s.th = m.th;
        return s;
    }

    private void startReport(String sig) {
        reportRunning = true;
        Model snap = copy();
        new SwingWorker<Object[], Void>() {
            @Override
            protected Object[] doInBackground() {
                return new Object[]{DesignCheck.html(DesignCheck.run(snap)), DesignCheck.curves(snap)};
            }

            @Override
            protected void done() {
                reportRunning = false;
                shownSig = sig;
                try {
                    Object[] r = get();
                    report.setText((String) r[0]);
                    double[][] c = (double[][]) r[1];
                    calPlot.clear();
                    calPlot.add(c[0], c[0], new Color(160, 160, 160), "perfect", 1f, true);
                    calPlot.add(c[0], c[1], new Color(192, 38, 29), "one calibration", 1.6f, false);
                    calPlot.add(c[0], c[2], new Color(27, 138, 58), "per sensor", 1.6f, false);
                    AnalysisTab.this.revalidate();
                } catch (Exception ex) {
                    report.setText("<html><small>The check could not run: " + ex + "</small></html>");
                }
            }
        }.execute();
    }

    // ------------------------------------------------------------------ background jobs
    private void run(String what, Supplier<Runnable> job) {
        if (busy) {
            return;
        }
        busy = true;
        for (JButton b : buttons) {
            b.setEnabled(false);
        }
        testResult.setText("<html><small>Running " + what + "...</small></html>");
        new SwingWorker<Runnable, Void>() {
            @Override
            protected Runnable doInBackground() {
                return job.get();
            }

            @Override
            protected void done() {
                busy = false;
                for (JButton b : buttons) {
                    b.setEnabled(true);
                }
                try {
                    get().run();
                } catch (Exception ex) {
                    testResult.setText("<html><small>The test failed: " + ex + "</small></html>");
                }
                AnalysisTab.this.revalidate();
            }
        }.execute();
    }

    private static String f(double v) {
        return String.format("%.1f", v);
    }

    private void suggest(boolean simulate) {
        Model snap = copy();
        if (!simulate) {
            show(DesignCheck.suggest(snap, false));
            return;
        }
        run("the simulated suggestion", () -> {
            DesignCheck.Suggestion g = DesignCheck.suggest(snap, true);
            return () -> {
                testResult.setText(" ");
                show(g);
            };
        });
    }

    private void show(DesignCheck.Suggestion g) {
        lastSuggestion = g;
        suggestArea.setText(g.text);
        suggestArea.setCaretPosition(0);
        applyBtn.setEnabled(true);
    }

    private void runStep() {
        Model snap = copy();
        run("the step response", () -> {
            Experiments.StepResult r = Experiments.stepResponse(Experiments.snapshot(snap));
            return () -> {
                testResult.setText("<html><body style='width:232px'><b>" + r.label + "</b><br><small>start offset " + f(r.offset)
                        + " mm &middot; back to within the wobble in " + (Double.isNaN(r.rise) ? "never" : String.format("%.2f s", r.rise))
                        + " &middot; overshoot " + f(r.overshootPct) + " % &middot; settled by "
                        + (Double.isNaN(r.settle) ? "never" : String.format("%.2f s", r.settle))
                        + " &middot; steady wobble &plusmn;" + f(r.steadyAmp) + " mm &middot; " + r.crossings
                        + " swings through the line</small></body></html>");
                testPlot.setup("distance of the array from the line (mm)", "time (s)", "", false, false);
                testPlot.clear();
                double[] zero = {0, 0};
                double tEnd = r.time.length > 0 ? r.time[r.time.length - 1] : 1;
                testPlot.add(new double[]{0, tEnd}, zero, new Color(150, 150, 150), "", 1f, true);
                testPlot.add(new double[]{0, tEnd}, new double[]{r.steadyAmp, r.steadyAmp}, new Color(200, 160, 60), "steady wobble", 1f, true);
                testPlot.add(new double[]{0, tEnd}, new double[]{-r.steadyAmp, -r.steadyAmp}, new Color(200, 160, 60), "", 1f, true);
                testPlot.add(r.time, r.err, new Color(30, 100, 200), "array error", 1.6f, false);
            };
        });
    }

    private void runCorner() {
        Model snap = copy();
        run("the corner test", () -> {
            Experiments.CornerResult r = Experiments.corner(Experiments.snapshot(snap));
            return () -> {
                testResult.setText("<html><body style='width:232px'><b>" + r.verdict + "</b><br><small>overshoot " + f(r.overshoot)
                        + " mm &middot; early turn (cut) " + f(r.cut) + " mm &middot; wander after the corner " + f(r.devAfter)
                        + " mm &middot; judged against half the line width (" + f(snap.lineWidth / 2) + " mm)</small></body></html>");
                testPlot.setup("path of the sensor array centre (mm)", "x", "y", true, true);
                testPlot.clear();
                double cx = Experiments.CORNER_X, cy = Experiments.CORNER_Y;
                testPlot.add(new double[]{100, cx, cx}, new double[]{cy, cy, cy + 350}, new Color(190, 190, 190), "line", 7f, false);
                testPlot.add(r.px, r.py, new Color(30, 100, 200), "array centre", 1.6f, false);
            };
        });
    }

    private void runSweep() {
        Model snap = copy();
        run("the look-ahead x speed sweep (a few seconds)", () -> {
            double v = Math.max(snap.baseSpeed, 40);
            double[] speeds = new double[5];
            double[] mult = {0.5, 0.75, 1, 1.5, 2};
            for (int i = 0; i < 5; i++) {
                speeds[i] = Math.max(20, Math.round(v * mult[i] / 10.0) * 10.0);
            }
            double[] aheads = {10, 20, 30, 45, 60, 90, 130, 180};
            Experiments.SweepResult s = Experiments.sweep(snap, speeds, aheads);
            return () -> {
                testResult.setText(sweepHtml(s, snap.lineWidth));
                testPlot.clear();
            };
        });
    }

    private static String sweepHtml(Experiments.SweepResult s, double lineWidth) {
        StringBuilder sb = new StringBuilder("<html><body style='width:232px'><b>Corner overshoot (mm) by speed and axle-to-sensor distance</b><br>");
        sb.append("<small><table cellspacing=1 cellpadding=2><tr><td><b>mm/s</b></td>");
        for (double a : s.aheads) {
            sb.append("<td align=center><b>").append((int) a).append("</b></td>");
        }
        sb.append("</tr>");
        for (int i = 0; i < s.speeds.length; i++) {
            sb.append("<tr><td><b>").append((int) s.speeds[i]).append("</b></td>");
            for (int j = 0; j < s.aheads.length; j++) {
                double sc = s.score[i][j];
                String bg = sc >= 999 ? "#e8b0b0" : sc <= 0.5 * lineWidth ? "#bfe6c4" : sc <= lineWidth ? "#f3e9b0" : "#f1c9b8";
                String txt = sc >= 999 ? "lost" : String.valueOf(Math.round(sc));
                if (!s.stable[i][j]) {
                    txt += "!";
                }
                if (j == s.best[i]) {
                    txt = "<b>[" + txt + "]</b>";
                }
                sb.append("<td align=center bgcolor=").append(bg).append("><font color=#202124>").append(txt).append("</font></td>");
            }
            sb.append("</tr>");
        }
        sb.append("</table>Green: within half a line width, yellow: within one, orange: more, red: lost. ")
                .append("<b>[x]</b> = best stable distance; <b>!</b> = nervous on a straight.<br>");
        for (int i = 0; i < s.speeds.length; i++) {
            sb.append((int) s.speeds[i]).append(" mm/s: best ").append(s.best[i] < 0 ? "none stable" : (int) s.aheads[s.best[i]] + " mm")
                    .append(", the 10 cm per m/s rule says ").append((int) Math.round(s.rule[i])).append(" mm<br>");
        }
        return sb.append("</small></body></html>").toString();
    }
}
