package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import javax.imageio.ImageIO;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The right-hand control column. Every control (slider, spinner, drop-down,
 * check box) has its own reset button; each tab has a "reset this tab" button
 * and the top has one master button that resets everything.
 */
public class ControlPanel extends JPanel {

    private final Model m;
    private final TrackPanel trackPanel;
    private final ResetGroup master = new ResetGroup(null);

    private JButton runBtn;
    private Runnable themeToggle = () -> { };
    private JLabel dirLabel;
    private RunsPanel runsPanel;
    private String lastDir2 = "";
    private ComboParam presetCombo;
    private CheckParam gridCk, trailCk;
    private File workspaceFile;
    private int savedChanges;
    private String savedSig = "";
    private java.util.function.Consumer<String> titleSink = t -> { };
    private static final String TITLE = "Line Follower Robot Simulator";
    private Param thresholdParam;
    private SpinParam clockSpin, adcSpin, digSpin, mathSpin;
    private ComboParam arrayCombo, shapeCombo, wheelsCombo;
    private SpinParam axleSpSp, skidSp, sideFricSp, steerSp, servoSp, steerDelaySp;
    private ComboParam actCombo;
    private SpinParam arrayAngleSp, arrayRadiusSp, bodyWSp, bodyFSp, bodyRSp, armSp, roundSp, casterPosSp, boardSp;
    private JLabel shapeInfo;
    private String lastShape = "";
    private SpinParam tireSp, gridSp, massSp, torqueSp, weightSp, frictionSp, casterSp, backlashSp;
    private ComboParam driveCombo, decayCombo, driverCombo;
    private JLabel encInfo, batInfo;
    private SpinParam dropSp;
    /** Motor driver presets: volts lost at running current, decay between PWM pulses (0 brake, 1 coast). */
    static final String[] DRIVER_NAMES = {"Ideal (no loss)", "TB6612FNG", "DRV8833", "L298N"};
    static final double[] DRIVER_DROP = {0, 0.4, 0.25, 2.0};
    static final int[] DRIVER_DECAY = {0, 0, 1, 1};
    private JLabel driveInfo;
    private String lastDrive = "";
    private SpinParam wheelBaseSp, wheelDiaSp, axleSp, countSp, pitchSp, heightSp, angleSp;
    private SpinParam rpmSp, ratedSp, battSp, loadSp, overrideSp;
    private SpinParam arenaWSp, arenaHSp;
    private double arenaW, arenaH;
    private boolean arenaBulk;
    private Param gainLP, gainRP, lagLP, lagRP, deadLP, deadRP, delayParam;
    private ComboParam viewCombo;
    private SpinParam zoomSpin;
    private JToggleButton followBtn;
    private boolean viewSync;
    // track builder
    private JToggleButton[] toolButtons;
    private SpinParam widthSp, dashSp, gapSp, radiusSp;
    private CheckParam closedCk, filledCk, eraseCk;
    private JLabel selLabel;
    private JButton undoBtn, redoBtn, deleteBtn, dupBtn;
    private boolean styleSync;
    private int lastStyleVersion = -1, lastSelVersion = -1;
    private String lastSelText = "";
    private File lastDir;
    // analysis, realism and picture controls
    private AnalysisTab analysis;
    private JTabbedPane tabsPane;
    private int analysisIndex = -1;
    private Param crosstalkP, floorP, lineP, noiseP, mismatchP, drainP, sagP, tempP, riseP, kpP, kiP, kdP;
    private CheckParam barriersCk, battLedCk;
    private SpinParam jitterSp, rowsSp, rowSpSp, bgWSp, bgXSp, bgYSp;
    private ComboParam calCombo;
    private Param fadeP, patchMuP;
    private boolean bgSync;
    private final Random rng = new Random();
    private JLabel mcuInfo, hwInfo;
    private String lastInfo = "", lastHw = "";
    private int driveTick;

    ControlPanel(Model model, TrackPanel trackPanel) {
        this.m = model;
        this.trackPanel = trackPanel;
        this.arenaW = model.arenaW;
        this.arenaH = model.arenaH;
        setLayout(new BoxLayout(this, BoxLayout.Y_AXIS));
        setBorder(new EmptyBorder(4, 4, 4, 6));
        buildTop();
        buildTabs();
        setPreferredSize(new Dimension(462, 690));
    }

    /** Called every frame so live read-outs (loop time) stay current. */
    private int depTick;

    void refreshLive() {
        if (++depTick % 8 == 0) {
            updateDeps();
        }
        if (runningShown() != m.running) {
            setRunning(m.running);                                         // pause() in the code, or a run error
        }
        double c = m.computeSec() * 1000.0, p = m.loopPeriodSec() * 1000.0;
        String s = m.loopMode == 1
                ? String.format("<html><body style='width:250px'><small>Compute %.3f ms, fixed period %.2f ms = %.0f Hz%s<br>physics step %.0f us%s</small></html>",
                        c, p, 1000.0 / p, c > 1000.0 / Math.max(m.fixedHz, 0.1) ? " <b>(overrun: compute is longer)</b>" : "",
                        m.stepSec() * 1e6, m.sensorTask ? ", sensor task " + String.format("%.0f", m.sensorTaskHz) + " Hz" : "")
                : String.format("<html><body style='width:250px'><small>Compute %.3f ms + delay %.1f ms = loop %.3f ms (%.0f Hz)<br>physics step %.0f us%s</small></html>",
                        c, m.loopDelayMs, p, 1000.0 / p, m.stepSec() * 1e6,
                        m.sensorTask ? ", sensor task " + String.format("%.0f", m.sensorTaskHz) + " Hz" : "");
        if (mcuInfo != null && !s.equals(lastInfo)) {
            mcuInfo.setText(s);
            lastInfo = s;
        }
        syncView();
        syncBuilder();
        if (++titleTick % 15 == 0) {                        // a few times a second is plenty
            updateTitle();
            String d = m.loopDirection();
            String txt = d == null ? (m.edited ? "<html><small>Your own track: set the heading with the Robot tool.</small></html>"
                    : "<html><small>Open track: Reversed starts from the other end.</small></html>")
                    : "<html><small>This loop is driven <b>" + d + "</b>.</small></html>";
            if (dirLabel != null && !txt.equals(lastDir2)) {
                lastDir2 = txt;
                dirLabel.setText(txt);
            }
        }
        Model.Latency lat = m.latency();
        double v = Math.max(m.baseSpeed, 1);
        String h = String.format("<html><body style='width:250px'><small>Wheel circumference %.0f mm, top speed %.0f mm/s (%.2f km/h)<br>"
                + "Sensor array width %.0f mm, spot radius %.1f mm<br>"
                + "Delay sensor to wheels %.1f ms: at %.0f mm/s the robot moves %.1f mm before it reacts</small></html>",
                m.wheelCircumference(), m.maxSpeed, m.maxSpeed * 0.0036, m.arrayWidth(), m.spotRadius,
                lat.total, v, v * lat.total / 1000.0);
        if (shapeInfo != null && driveTick % 10 == 5) {
            double[] cg = m.centreOfMass(), geo = m.chassisGeometry();
            String si = String.format("<html><body style='width:250px'><small>Plate %.0f cm&sup2;, centre of mass %.0f mm %s "
                    + "the axle, caster at %.0f mm: about <b>%.0f %%</b> of the weight on the wheels.</small></html>",
                    geo[0] / 100, Math.abs(cg[0]), cg[0] >= 0 ? "ahead of" : "behind", m.casterX(), m.suggestedWheelWeight())
                    + (m.wheelWeightShare() > 1.0 ? "<html><body style='width:250px'><small><font color='" + Theme.badHex() + "'><b>It would "
                    + "tip over the axle</b> (the centre of mass is on the far side from the caster): move the battery towards "
                    + "the caster, or put the caster at the other end.</font></small></html>" : "");
            si = si.replace("</small></html><html><body style='width:250px'><small>", " ");
            if (!si.equals(lastShape)) {
                lastShape = si;
                shapeInfo.setText(si);
            }
        }
        if (driveInfo != null && ++driveTick % 10 == 0) {
            String d = m.driveModel == 1
                    ? String.format("<html><body style='width:250px'><small>Reaches 63 %% speed in %.0f ms, top speed %.0f mm/s, "
                            + "push %.1f N per motor, starts moving at PWM %.0f / 255. Turning inertia %.0f kg.mm&sup2;.</small></html>",
                            m.mechTimeConstant() * 1000, m.physicalTopSpeed(), m.stallForceN() * m.battVolt / Math.max(m.motorVolt, 0.1),
                            m.minDutyToMove() * 255, m.yawInertia())
                    : "<html><body style='width:250px'><small>Choose Physical to use the values above.</small></html>";
            if (!d.equals(lastDrive)) {
                lastDrive = d;
                driveInfo.setText(d);
            }
        }
        if (encInfo != null && driveTick % 10 == 3) {
            double cpr = m.encCountsPerRev();
            encInfo.setText(cpr <= 0 ? "<html><body style='width:250px'><small>No encoder.</small></html>"
                    : String.format("<html><body style='width:250px'><small>%.0f counts per wheel turn, %.3f mm per count; at %.0f mm/s "
                    + "%.1f counts per loop. Measured now: left %.0f, right %.0f mm/s.</small></html>", cpr, m.mmPerCount(), m.baseSpeed,
                    m.baseSpeed / Math.max(m.mmPerCount(), 1e-9) * m.loopPeriodSec(), m.encSpeedL, m.encSpeedR));
            double rt = m.runtimeMin();
            batInfo.setText(String.format("<html><body style='width:250px'><small>Now %.2f A, %.1f V; used %.1f mAh in %.0f s%s</small></html>",
                    m.currentNow, m.battNow(), m.usedMah, m.t, Double.isNaN(rt) ? " (set a capacity for the running time)"
                    : String.format(": a full battery lasts about %.0f min at this rate", rt)));
        }
        if (hwInfo != null && !h.equals(lastHw)) {
            hwInfo.setText(h);
            lastHw = h;
        }
        if (runsPanel != null) {
            runsPanel.tick();
        }
        if (analysis != null) {
            analysis.tick(tabsPane != null && tabsPane.getSelectedIndex() == analysisIndex);
        }
    }

    /** Mirror view changes made with the mouse / keys (wheel, drag, F ...) in the controls. */
    private void syncView() {
        if (viewCombo != null && viewCombo.index() != trackPanel.viewMode) {
            viewCombo.setQuiet(trackPanel.viewMode);
        }
        if (followBtn != null && followBtn.isSelected() != (trackPanel.viewMode == 1)) {
            followBtn.setSelected(trackPanel.viewMode == 1);
        }
        if (zoomSpin != null) {
            double z = trackPanel.zoomPct();
            boolean editing = ((JSpinner.DefaultEditor) zoomSpin.spinner.getEditor()).getTextField().hasFocus();
            if (!editing && Math.abs(z - zoomSpin.get()) > 0.5) {
                viewSync = true;
                zoomSpin.set(z);
                viewSync = false;
            }
        }
    }

    private void setPid(double kp, double ki, double kd) {
        kpP.set(kp);
        kiP.set(ki);
        kdP.set(kd);
    }

    private void setRunning(boolean r) {
        m.running = r;
        runBtn.setText(r ? "Pause" : "Run");
        runBtn.setIcon(Icons.get(r ? "pause" : "play"));
    }

    boolean runningShown() {
        return runBtn.getText().equals("Pause");
    }

    /** The Run button after the code paused the simulation. */
    void syncRunButton() {
        setRunning(m.running);
    }

    /** Start / pause the simulation (also F5). */
    void toggleRunning() {
        if (debugContinue.getAsBoolean()) {
            return;                                                        // stopped in the code: F5 = Continue
        }
        setRunning(!m.running);
    }

    /** Put the robot back at its start (also F6). */
    void resetRobot() {
        if (debugHold.getAsBoolean()) {                                    // stopped inside the code: leave it first
            debugAbort.run();
            SwingUtilities.invokeLater(this::resetRobot);
            return;
        }
        m.reset();
    }

    /** What the theme button does (set by the main window). */
    void setThemeToggle(Runnable r) {
        themeToggle = r;
    }

    /** Re-render theme dependent text (the design report). */
    void themeChanged() {
        if (analysis != null) {
            analysis.themeChanged();
        }
    }

    /** Master reset: every control back to its default, track reloaded, robot reset. */
    private void resetEverything() {
        if (m.edited) {
            int ans = JOptionPane.showConfirmDialog(this,
                    "This also reloads the track and discards your hand-drawn changes.\nContinue?",
                    "Reset all settings", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ans != JOptionPane.OK_OPTION) {
                return;
            }
        }
        master.resetDefault();
        m.customLayout = m.customLeds = m.useParts = m.boardOnPlate = false;   // the simulated robot back to the default;
        m.parts.clear();                                                       // the Robot editor keeps its draft (it has its own Reset)
        m.boardStem = m.pairGap = 0;
        m.cuLedCount = 0;
        m.reset();
    }

    // ------------------------------------------------------------------ top section
    private void buildTop() {
        runBtn = UI.button("Pause", "pause", "Start or pause the simulation (F5)");
        runBtn.addActionListener(e -> toggleRunning());
        JButton resetRobot = UI.button("Restart", "reset_robot", "Put the robot back at its start position (F6)");
        resetRobot.addActionListener(e -> resetRobot());
        JButton all = UI.button("Reset all", "reset_all", "Every slider, spinner, drop-down and check box back to its default, "
                + "plus the track and the robot");
        all.addActionListener(e -> resetEverything());
        JButton theme = UI.button(null, "theme", "Switch between the light and the dark theme");
        theme.addActionListener(e -> themeToggle.run());
        ledBtn = new JToggleButton(Icons.get("bulb"));
        ledBtn.setToolTipText("LEDs near the sensors on / off (F7) - works while the robot runs; F8 / Shift+F8 brighter / dimmer");
        ledBtn.setFocusable(false);
        ledBtn.setMargin(new Insets(2, 4, 2, 4));
        ledBtn.addActionListener(e -> toggleLeds());
        JPanel eastBtns = new JPanel(new GridLayout(1, 2, 4, 0));
        eastBtns.add(ledBtn);
        eastBtns.add(theme);
        JPanel runRow = new JPanel(new BorderLayout(6, 0));
        JPanel three = new JPanel(new GridLayout(1, 3, 6, 0));
        three.add(runBtn);
        three.add(resetRobot);
        three.add(all);
        runRow.add(three, BorderLayout.CENTER);
        runRow.add(eastBtns, BorderLayout.EAST);
        runRow.setBorder(new EmptyBorder(0, 4, 0, 4));
        add(UI.fix(runRow));
        add(Box.createVerticalStrut(4));

        master.addAction(() -> setRunning(true));
        master.addAction(() -> m.resetMismatch());
        master.addAction(() -> m.clearPatches());

        ComboParam preset = presetCombo = master.add(new ComboParam("Track", TrackBuilder.PRESETS, 0, i -> m.loadPreset(i)));
        add(UI.fix(preset.panel));
        add(UI.fix(master.add(new Param("Simulation speed", "x", 0.1, 8, m.speedMul, 1, v -> m.speedMul = v)).panel));

        JButton zOut = UI.button(null, "zoom_out", "Zoom out (mouse wheel also zooms around the pointer; key -)");
        JButton zIn = UI.button(null, "zoom_in", "Zoom in (key +)");
        JButton fit = UI.button("Fit", "fit", "Show the whole arena (key 0)");
        followBtn = UI.toggle("Follow", "follow", "Keep the robot in the centre of the view (key F)");
        zOut.addActionListener(e -> trackPanel.zoomBy(0.8, null));
        zIn.addActionListener(e -> trackPanel.zoomBy(1.25, null));
        fit.addActionListener(e -> trackPanel.setViewMode(0));
        followBtn.addActionListener(e -> trackPanel.setViewMode(followBtn.isSelected() ? 1 : 2));
        JPanel viewRow = new JPanel(new GridLayout(1, 4, 4, 0));
        viewRow.setBorder(new EmptyBorder(2, 4, 4, 4));
        viewRow.add(zOut);
        viewRow.add(zIn);
        viewRow.add(fit);
        viewRow.add(followBtn);
        add(UI.fix(viewRow));
    }

    private JPanel pidTab() {
        JPanel p = column();
        ResetGroup g = new ResetGroup(master, "PID");
        JPanel pid = UI.group("PID controller");
        pid.add(UI.fix(g.add(new Param("Base speed", "mm/s", 20, 1000, m.baseSpeed, 0, v -> m.baseSpeed = v)).panel));
        kpP = g.add(new Param("Kp", "1/s", 0, 40, m.kp, 2, v -> m.kp = v));
        pid.add(UI.fix(kpP.panel));
        kiP = g.add(new Param("Ki", "1/s\u00B2", 0, 5, m.ki, 3, v -> m.ki = v));
        pid.add(UI.fix(kiP.panel));
        kdP = g.add(new Param("Kd", "", 0, 5, m.kd, 3, v -> m.kd = v));
        pid.add(UI.fix(kdP.panel));
        pid.add(UI.fix(g.add(new Param("Derivative filter", "ms", 0, 100, m.dFilterMs, 0, v -> m.dFilterMs = v)).panel));
        pid.add(UI.fix(g.add(new Param("Corner slowdown", "%", 0, 100, m.cornerSlow * 100, 0, v -> m.cornerSlow = v / 100)).panel));
        pid.add(UI.fix(g.add(new CheckParam("No D kick when the line is lost / found", m.noDKick, b -> m.noDKick = b)).panel));
        JButton pOnly = UI.button("P", null, "Proportional only: Kp 5, no integral, no derivative");
        JButton pd = UI.button("PD", null, "Proportional + derivative: Kp 5, Kd 0.5 (the default)");
        JButton pid3 = UI.button("PID", null, "Proportional + integral + derivative: Kp 5, Ki 0.3, Kd 0.5");
        pOnly.addActionListener(e -> setPid(5, 0, 0));
        pd.addActionListener(e -> setPid(5, 0, 0.5));
        pid3.addActionListener(e -> setPid(5, 0.3, 0.5));
        pid.add(UI.fix(buttonRow(pOnly, pd, pid3)));
        JButton tune = UI.button("Tune PID for this track...", "suggest",
                "Keep the robot, simulate many PID settings on this track and pick the best (Ctrl+T)");
        tune.addActionListener(e -> openTunePid());
        pid.add(UI.fix(buttonRow(tune)));
        JPanel lost = UI.group("Line lost (recovery)");
        lost.add(UI.fix(g.add(new ComboParam("When lost", new String[]{"Smart (edge or gap)", "Hold the last error",
            "Steer hard to the last side", "Straight on", "Spin towards the last side", "Stop"}, 0, i -> m.lostMode = i)).panel));
        lost.add(UI.fix(dep(g.add(new Param("Edge zone (smart)", "%", 10, 100, m.edgeZonePct, 0, v -> m.edgeZonePct = v)).panel, () -> m.lostMode == 0)));
        lost.add(UI.fix(dep(g.add(new SpinParam("Search speed (spin)", "mm/s", 10, 1000, 10, m.searchSpeed, "0", v -> m.searchSpeed = v)).panel, () -> m.lostMode == 4)));
        lost.add(UI.fix(g.add(new SpinParam("Give up after", "s", 0, 30, 0.1, m.lostTimeout, "0.0", v -> m.lostTimeout = v)).panel));
        lost.add(UI.fix(g.add(new SpinParam("Found again after", "loops", 1, 20, 1, m.confirmCount, "0",
                v -> m.confirmCount = (int) Math.round(v))).panel));
        lost.add(UI.hint("What the robot does when no sensor sees the line. <b>Smart</b>: if the line was last seen in the "
                + "outer <b>edge zone</b> of the array it left sideways, so steer hard that way; if it vanished near the "
                + "centre it is a gap, so drive straight on. <b>Spin</b> turns on the spot at the <b>search speed</b> (a car "
                + "crawls at full lock). <b>Give up after</b>: stop when the line has been gone this long (0 = never). "
                + "<b>Found again after</b>: how many loops in a row the line must be seen before the PID follows it "
                + "again (filters out a single noisy reading). The exported sketch does the same."));
        pid.add(UI.hint("<b>Kp</b> steers in proportion to how far the line is from the centre. <b>Kd</b> damps the swing "
                + "(acts on how fast the error changes). <b>Ki</b> removes a steady offset, e.g. from mismatched motors. "
                + "<b>Corner slowdown</b> lowers the speed when the error is large. Watch the graphs under the track."));
        p.add(UI.fix(pid));
        p.add(UI.fix(lost));
        p.add(UI.resetRow("Reset PID tab", g));
        return p;
    }

    // ------------------------------------------------------------------ tabs
    private void buildTabs() {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.LEFT);     // one fixed column of tabs: no shuffling rows
        tabs.setFocusable(false);
        tabs.addTab("PID", Icons.get("tab_pid"), UI.scroll(pidTab()), "Controller gains and speed");
        tabs.addTab("Hardware", Icons.get("tab_hardware"), UI.scroll(hardwareTab()), "Chassis, sensor array and drive");
        tabs.addTab("Sensors", Icons.get("tab_sensors"), UI.scroll(sensorTab()), "Readout, calibration, filters, white LEDs");
        tabs.addTab("Motors", Icons.get("tab_motors"), UI.scroll(motorTab()), "Left / right motor differences");
        tabs.addTab("MCU", Icons.get("tab_mcu"), UI.scroll(mcuTab()), "Microcontroller speed and loop timing");
        tabs.addTab("World", Icons.get("tab_environment"), UI.scroll(environmentTab()),
                "Light, floor, battery, temperature, grip and disturbances");
        tabs.addTab("Track", Icons.get("tab_track"), UI.scroll(trackTab()), "Arena size, view, background picture");
        tabs.addTab("Builder", Icons.get("tab_builder"), UI.scroll(builderTab()), "Draw and edit the track");
        analysis = new AnalysisTab(m, this::applySuggestion);
        runsPanel = new RunsPanel(m, trackPanel, () -> {
            java.util.Map<String, String> s = new java.util.LinkedHashMap<>();
            for (java.util.Map.Entry<String, Saveable> e : master.saveables().entrySet()) {
                s.put(e.getKey(), e.getValue().value());
            }
            return s;
        });
        runsPanel.exportCsv = this::exportTelemetry;
        runsPanel.importLog = this::importLog;

        tabs.addTab("Analysis", Icons.get("tab_analysis"), UI.scroll(analysis), "Design check and automatic tests");
        analysisIndex = tabs.getTabCount() - 1;
        JPanel dz = UI.group("Design a robot for this track");
        JButton dzBtn = UI.button("Design a robot...", "suggest", "Measure the track, then pick and tune a whole robot for it (Ctrl+G)");
        dzBtn.addActionListener(e -> openDesigner());
        dz.add(UI.fix(buttonRow(dzBtn)));
        dz.add(UI.hint("Picks motors, wheels, battery, wheel layout, sensor array and PID for the track you have drawn, by "
                + "simulating many robots on it. A slider chooses between speed and reliability; lock what you already own."));
        analysis.add(UI.fix(dz), 0);
        JPanel runsBox = UI.group("Compare runs (kept trails)");
        runsBox.add(runsPanel);
        tabs.addTab("Runs", Icons.get("saved"), UI.scroll(runsBox), "Keep runs and compare their trails, numbers and settings");
        tabsPane = tabs;
        tabs.setAlignmentX(Component.LEFT_ALIGNMENT);
        add(tabs);
        master.addAction(() -> {                             // after everything else: calibration matches the defaults again
            m.calibrate();
            thresholdParam.set(m.threshold);
        });
    }

    private static JPanel column() {
        JPanel p = new JPanel();
        p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
        return p;
    }

    private JPanel sensorTab() {
        ResetGroup g = new ResetGroup(master, "Sensors");
        JPanel p = column();
        p.add(UI.fix(g.add(new Param("IR LED power", "%", 0, 100, m.irPower * 100, 0, v -> m.irPower = v / 100)).panel));
        thresholdParam = g.add(new Param("Threshold (ADC 0-1023)", "", 0, 1023, m.threshold, 0, v -> m.threshold = v));
        p.add(UI.fix(thresholdParam.panel));
        noiseP = g.add(new Param("Noise", "%", 0, 10, m.noise * 100, 1, v -> m.noise = v / 100));
        p.add(UI.fix(noiseP.panel));
        mismatchP = g.add(new Param("Sensor mismatch", "%", 0, 50, m.mismatch * 100, 0, v -> m.mismatch = v / 100));
        p.add(UI.fix(mismatchP.panel));
        p.add(UI.fix(g.add(new SpinParam("Noise seed", "", 1, 99999, 1, m.noiseSeed, "0", v -> m.noiseSeed = (int) Math.round(v))).panel));
        p.add(UI.fix(g.add(new CheckParam("New noise every run (not repeatable)", m.varyNoise, b -> m.varyNoise = b)).panel));

        p.add(UI.fix(g.add(new ComboParam("Output", new String[]{"Digital (threshold)", "Analog (weighted)"}, 0,
                i -> m.analogMode = i == 1)).panel));
        p.add(UI.fix(g.add(new ComboParam("Readout", new String[]{"Auto (follow Output)", "Analog (ADC values)",
            "Digital (0/1 bits)", "Both"}, 0, i -> m.readout = i)).panel));

        JButton cal = UI.button("Calibrate here", "calibrate", "Measure white and black at the robot's position and set the "
                + "threshold half-way (like the calibration step at start-up)");
        cal.addActionListener(e -> {
            m.calibrate();
            thresholdParam.set(m.threshold);
        });
        JButton shuffle = UI.button("Shuffle mismatch", "shuffle", "A new random set of sensor-to-sensor differences");
        shuffle.addActionListener(e -> m.randomizeMismatch());
        JPanel btns = new JPanel(new GridLayout(1, 2, 6, 0));
        btns.setBorder(new EmptyBorder(3, 4, 3, 4));
        btns.add(cal);
        btns.add(shuffle);
        p.add(UI.fix(btns));
        calCombo = g.add(new ComboParam("Calibration", new String[]{"One white/black pair", "Each sensor (sweep)"}, 0, i -> {
            if (i == 1) {
                m.calibrateSweep();
            }
            m.perSensorCal = i == 1;
        }));
        p.add(UI.fix(calCombo.panel));
        JButton sweepBtn = UI.button("Sweep sensors over the line", "sweep",
                "Moves every sensor across a line and remembers each one's darkest and brightest reading");
        sweepBtn.addActionListener(e -> {
            m.calibrateSweep();
            m.perSensorCal = true;
            calCombo.setQuiet(1);
        });
        p.add(UI.fix(buttonRow(sweepBtn)));

        JPanel optics = UI.group("Optical realism");
        crosstalkP = g.add(new Param("Crosstalk", "%", 0, 40, m.crosstalkPct, 0, v -> m.crosstalkPct = v));
        barriersCk = g.add(new CheckParam("Light barriers between sensors", m.barriers, b -> m.barriers = b));
        optics.add(UI.fix(crosstalkP.panel));
        optics.add(UI.fix(barriersCk.panel));
        optics.add(UI.hint("Crosstalk is light from the neighbouring LEDs (percent at 10 mm pitch, stronger when the sensors are "
                + "closer); barriers cut it to a tenth. Sensor height already changes the signal: higher means weaker and "
                + "blurrier (Hardware tab)."));
        p.add(UI.fix(optics));


        JPanel proc = UI.group("Signal processing and control");
        proc.add(UI.fix(g.add(new ComboParam("Filter", new String[]{"None", "Moving average", "Low-pass"}, 0,
                i -> m.filterMode = i)).panel));
        proc.add(UI.fix(dep(g.add(new SpinParam("Average length", "samples", 2, 16, 1, m.filterN, "0",
                v -> m.filterN = (int) Math.round(v))).panel, () -> m.filterMode == 1)));
        proc.add(UI.fix(dep(g.add(new Param("Low-pass time constant", "ms", 1, 200, m.filterTauMs, 0, v -> m.filterTauMs = v)).panel, () -> m.filterMode == 2)));
        proc.add(UI.fix(g.add(new ComboParam("Error formula", new String[]{"Normalised (mm)", "Raw weighted sum"}, 0,
                i -> m.errorMode = i)).panel));
        proc.add(UI.fix(g.add(new Param("Predictive term", "ms", 0, 200, m.predictMs, 0, v -> m.predictMs = v)).panel));
        proc.add(UI.fix(g.add(new ComboParam("Junction", new String[]{"Ignore", "Prefer left", "Prefer right"}, 0,
                i -> m.junctionRule = i)).panel));
        proc.add(UI.fix(dep(g.add(new Param("Row extrapolation", "mm", 0, 200, m.predictAheadMm, 0, v -> m.predictAheadMm = v)).panel, () -> m.nRows > 1)));
        proc.add(UI.hint("<b>Filters</b> remove noise but add delay (counted in the latency budget). <b>Raw weighted sum</b> "
                + "is the unnormalised formula: its size depends on how many sensors see the line, so it reads thin lines "
                + "low and wide lines high. <b>Predictive</b> lets P act on error + rate x time. <b>Junction</b>: when most "
                + "sensors see black (a crossing, T or sharp corner) commit to a 90 degree turn to that side; when two "
                + "separate lines are under the array (a Y fork) follow the one on that side. <b>Row extrapolation</b> (needs 2+ rows, "
                + "Hardware tab) looks further ahead along the line's direction."));
        p.add(UI.fix(proc));

        JPanel mnt = UI.group("Mounting, comparator and ADC");
        mnt.add(UI.fix(g.add(new SpinParam("Array off centre", "mm", -20, 20, 0.5, m.arrayOffset, "0.0", v -> m.arrayOffset = v)).panel));
        mnt.add(UI.fix(g.add(new SpinParam("Array turned", "deg", -20, 20, 0.5, m.arrayYaw, "0.0", v -> m.arrayYaw = v)).panel));
        mnt.add(UI.fix(dep(g.add(new SpinParam("Hysteresis (digital)", "%", 0, 50, 1, m.hystPct, "0", v -> m.hystPct = v)).panel, () -> !m.analogMode)));
        mnt.add(UI.fix(g.add(new ComboParam("ADC", new String[]{"Ideal", "8-bit", "10-bit (AVR, RP2040)",
            "12-bit (ESP32, STM32)"}, 0, i -> m.adcBits = new int[]{0, 8, 10, 12}[i])).panel));
        mnt.add(UI.fix(g.add(new CheckParam("ESP32-style ADC (loses both ends)", false, b -> m.esp32Adc = b)).panel));
        mnt.add(UI.hint("<b>Off centre / turned</b>: the board is screwed on a little sideways or at an angle - the robot "
                + "then follows a line beside its centre and corners differently each way (the calibration does not fix "
                + "it). <b>Hysteresis</b>: a comparator (LM393 module, or code) that needs the reading to pass the threshold "
                + "by this share of the white-black span before it flips - no flicker at the edge of the line, but the "
                + "edge moves a little. <b>ADC</b> steps round every reading; the ESP32's ADC also loses the ends of its range."));
        p.add(UI.fix(mnt));

        p.add(UI.hint("<b>Output</b> is what the robot's code uses: digital (sensor is 0/1 against the threshold) or "
                + "analog (weighted ADC values). <b>Readout</b> only changes what the HUD on the track shows: ADC bars "
                + "with 0-1023 numbers, 0/1 boxes with a bit string, or both. Auto follows Output. "
                + "Sensor count, pitch, height and the axle-to-sensor distance are in the Hardware tab."));
        p.add(UI.resetRow("Reset sensors tab", g));
        return p;
    }

    /** Calibrate again (white/black pair, and the per-sensor sweep if it is in use) and show the new threshold. */
    private void recalibrate() {
        if (thresholdParam == null) {
            return;
        }
        m.calibrate();
        thresholdParam.set(m.threshold);
        if (m.perSensorCal) {
            m.calibrateSweep();
        }
    }

    /** The sensor geometry changed: a per-sensor calibration from the old geometry no longer fits, so sweep again. */
    private void resweep() {
        if (m.perSensorCal) {
            m.calibrateSweep();
        }
    }

    private JPanel hardwareTab() {
        ResetGroup g = new ResetGroup(master, "Hardware");
        JPanel p = column();

        JPanel quick = UI.group("Quick presets (typical sizes)");
        quick.add(UI.fix(new MenuParam("Robot", HardwarePresets.menu("Choose...", HardwarePresets.ROBOTS),
                i -> applyRobot(HardwarePresets.ROBOTS[i].v)).panel));
        quick.add(UI.fix(new MenuParam("Wheel", HardwarePresets.menu("Choose...", HardwarePresets.WHEELS),
                i -> wheelDiaSp.set(HardwarePresets.WHEELS[i].v[0])).panel));
        quick.add(UI.fix(new MenuParam("Motor", HardwarePresets.menu("Choose...", HardwarePresets.MOTORS), i -> {
            rpmSp.set(HardwarePresets.MOTORS[i].v[0]);
            ratedSp.set(HardwarePresets.MOTORS[i].v[1]);
            lagLP.set(HardwarePresets.MOTORS[i].v[2]);
            lagRP.set(HardwarePresets.MOTORS[i].v[2]);
            torqueSp.set(HardwarePresets.MOTORS[i].v[3]);
        }).panel));
        quick.add(UI.fix(new MenuParam("Sensor array", HardwarePresets.menu("Choose...", HardwarePresets.ARRAYS), i -> {
            countSp.set(HardwarePresets.ARRAYS[i].v[0]);
            pitchSp.set(HardwarePresets.ARRAYS[i].v[1]);
        }).panel));
        quick.add(UI.fix(new MenuParam("Battery", HardwarePresets.menu("Choose...", HardwarePresets.BATTERIES),
                i -> battSp.set(HardwarePresets.BATTERIES[i].v[0])).panel));
        quick.add(UI.hint("Picking a preset only fills in the fields below. Every value stays editable, so start "
                + "from the closest size and type your real measurements. The numbers are typical examples, "
                + "not specific products - check your datasheet."));
        p.add(UI.fix(quick));

        JPanel mine = UI.group("My robots (saved)");
        mine.add(UI.fix(savedBlock(UserPresets.ROBOT, "My robots", this::currentRobot, this::applyRobot)));
        mine.add(UI.hint("Saves the hardware, motor and MCU settings of the current robot under a name, "
                + "so you can bring your real robot back with one click."));
        p.add(UI.fix(mine));

        JPanel chassis = UI.group("Chassis");
        wheelBaseSp = g.add(new SpinParam("Wheel base", "mm", 20, 300, 1, m.wheelBase, "0.#", v -> m.wheelBase = v));
        wheelDiaSp = g.add(new SpinParam("Wheel diameter", "mm", 10, 150, 1, m.wheelDia, "0.#", v -> {
            m.wheelDia = v;
            m.applyHardware();
        }));
        tireSp = g.add(new SpinParam("Tire width", "mm", 2, 60, 1, m.tireWidth, "0.#", v -> m.tireWidth = v));
        axleSp = g.add(new SpinParam("Axle to sensors", "mm", 0, 300, 1, m.forward, "0.#", v -> m.forward = v));
        wheelsCombo = g.add(new ComboParam("Wheels", new String[]{"2 + caster", "4, skid steer", "4, steer + rear drive",
            "4, steer + all drive"}, 0, i -> {
                m.wheelCount = i == 0 ? 2 : 4;
                m.steerMode = i <= 1 ? 0 : i - 1;
            }));
        steerSp = g.add(new SpinParam("Steering lock (car)", "deg", 5, 60, 1, m.maxSteerDeg, "0", v -> m.maxSteerDeg = v));
        servoSp = g.add(new SpinParam("Steering speed (car)", "deg/s", 30, 2000, 10, m.servoRate, "0", v -> m.servoRate = v));
        actCombo = g.add(new ComboParam("Steered by (car)", new String[]{"Servo (any angle)", "DC motor (end stops)"}, 0,
                i -> m.steerActuator = i));
        steerDelaySp = g.add(new SpinParam("Steering delay (car)", "ms", 0, 200, 1, m.steerDelayMs, "0", v -> m.steerDelayMs = v));
        axleSpSp = g.add(new SpinParam("Axle spacing (4WD)", "mm", 20, 400, 1, m.axleSpacing, "0", v -> m.axleSpacing = v));
        skidSp = g.add(new SpinParam("Skid factor (4WD)", "", 0, 3, 0.05, m.skidFactor, "0.00", v -> m.skidFactor = v));
        dep(skidSp.panel, () -> m.wheelCount == 4 && m.steerMode == 0 && m.driveModel == 0);
        chassis.add(UI.fix(wheelsCombo.panel));
        chassis.add(UI.fix(dep(axleSpSp.panel, () -> m.wheelCount == 4)));
        chassis.add(UI.fix(skidSp.panel));
        chassis.add(UI.fix(dep(steerSp.panel, () -> m.steerMode != 0)));
        chassis.add(UI.fix(dep(servoSp.panel, () -> m.steerMode != 0)));
        chassis.add(UI.fix(dep(actCombo.panel, () -> m.steerMode != 0)));
        chassis.add(UI.fix(dep(steerDelaySp.panel, () -> m.steerMode != 0)));
        chassis.add(UI.fix(wheelBaseSp.panel));
        chassis.add(UI.fix(wheelDiaSp.panel));
        chassis.add(UI.fix(tireSp.panel));
        chassis.add(UI.fix(axleSp.panel));
        p.add(UI.fix(chassis));

        JPanel shape = UI.group("Chassis shape");
        shapeCombo = g.add(new ComboParam("Shape", new String[]{"Rectangle", "Round", "T (body + arm)", "Custom outline"}, 0,
                i -> m.chassisShape = i));
        shape.add(UI.fix(shapeCombo.panel));
        bodyWSp = g.add(new SpinParam("Body width", "mm", 0, 400, 1, m.bodyWidth, "0", v -> m.bodyWidth = v));
        bodyFSp = g.add(new SpinParam("Body front", "mm", 0, 400, 1, m.bodyFront, "0", v -> m.bodyFront = v));
        bodyRSp = g.add(new SpinParam("Body rear", "mm", 0, 300, 1, m.bodyRear, "0", v -> m.bodyRear = v));
        armSp = g.add(new SpinParam("Arm width (T)", "mm", 6, 200, 1, m.armWidth, "0", v -> m.armWidth = v));
        roundSp = g.add(new SpinParam("Diameter (round)", "mm", 0, 500, 1, m.roundDia, "0", v -> m.roundDia = v));
        casterPosSp = g.add(new SpinParam("Caster position", "mm", -300, 300, 1, m.casterPos, "0", v -> m.casterPos = v));
        boardSp = g.add(new SpinParam("Board margin", "mm", 1, 30, 0.5, m.boardMargin, "0.0", v -> m.boardMargin = v));
        for (SpinParam sp : new SpinParam[]{bodyWSp, bodyFSp, bodyRSp, armSp, roundSp, casterPosSp, boardSp}) {
            shape.add(UI.fix(sp.panel));
        }
        JButton editShape = UI.button("Open the Robot editor", "tool_polyline",
                "Draw the chassis, place the sensors, LEDs and parts freely (full-window editor)");
        editShape.addActionListener(e -> showRobotEditor.run());
        JButton useWeight = UI.button("Use for weight on wheels", "calibrate",
                "Set Motors > Weight on wheels from where the shape puts the centre of mass and the caster");
        useWeight.addActionListener(e -> weightSp.set(Math.round(m.suggestedWheelWeight())));
        shape.add(UI.fix(buttonRow(editShape)));
        shapeInfo = new JLabel(" ");
        shapeInfo.setBorder(new EmptyBorder(4, 6, 2, 4));
        shapeInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        shape.add(shapeInfo);
        shape.add(UI.fix(buttonRow(useWeight)));
        shape.add(UI.hint("The plate the parts sit on, seen from above. 0 = worked out from the wheels and sensors. "
                + "<b>Body front / rear</b>: from the axle. <b>Caster position</b>: + ahead of the axle, - behind, 0 = at "
                + "the back. The shape sets how hard the robot is to turn (inertia, Physical drive model) and where the "
                + "weight sits; it is also what File > Export robot drawing cuts."));
        p.add(UI.fix(shape));

        JPanel array = UI.group("Sensor array");
        countSp = g.add(new SpinParam("Sensor count", "", 1, Model.MAXS, 1, m.nSensors, "0", v -> {
            int n = (int) Math.round(v);
            if (n != m.nSensors) {
                dropCustomLayout();
            }
            m.nSensors = n;
            resweep();
        }));
        pitchSp = g.add(new SpinParam("Sensor pitch", "mm", 2, 30, 0.5, m.spacing, "0.0", v -> {
            if (Math.abs(v - m.spacing) > 1e-9) {
                dropCustomLayout();
            }
            m.spacing = v;
            resweep();
        }));
        heightSp = g.add(new SpinParam("Sensor height", "mm", 1, 40, 0.5, m.sensorHeight, "0.0", v -> {
            m.sensorHeight = v;
            m.applyHardware();
            resweep();
        }));
        angleSp = g.add(new SpinParam("Beam half-angle", "deg", 5, 60, 1, m.beamAngle, "0.#", v -> {
            m.beamAngle = v;
            m.applyHardware();
            resweep();
        }));
        // IR sensor parts: {beam half-angle deg, emitter-receiver gap mm, body across, body along, best height mm, height to set}
        final double[][] st = {{0, 0, 0, 0, 0, 0}, {20, 5.5, 10.2, 5.8, 2.5, 3}, {20, 3.5, 6.1, 4.4, 1.3, 2}, {25, 2.0, 3.6, 2.9, 1.0, 1.5},
            {25, 2.4, 4.0, 3.0, 1.0, 1.5}, {22, 2.0, 4.0, 2.0, 1.5, 2}, {20, 4.0, 7.0, 3.5, 5, 5}, {15, 6.0, 11.0, 5.5, 8, 8}};
        ComboParam typeCombo = g.add(new ComboParam("Sensor part", new String[]{"As set below", "TCRT5000 (box 10x6)",
            "QRD1114 (box 6x4.5)", "QRE1113 (SMD 3.6x2.9)", "ITR20001 (SMD 4x3)", "KTIR0711S (SMD 4x2)", "3 mm IR LED + receiver",
            "5 mm IR LED + receiver"}, 0, i -> {
                m.sensorType = i;
                if (i == 0) {
                    m.sensBodyW = m.sensBodyL = 0;
                    m.heightRef = 10;
                    m.applyHardware();
                    recalibrate();
                    return;
                }
                double[] v = st[i];
                angleSp.set(v[0]);
                m.pairGap = v[1];
                m.sensBodyW = v[2];
                m.sensBodyL = v[3];
                m.heightRef = v[4];
                heightSp.set(v[5]);
                m.applyHardware();
                recalibrate();
            }));
        array.add(UI.fix(typeCombo.panel));
        array.add(UI.fix(countSp.panel));
        array.add(UI.fix(pitchSp.panel));
        array.add(UI.fix(heightSp.panel));
        array.add(UI.fix(angleSp.panel));
        rowsSp = g.add(new SpinParam("Sensor rows", "", 1, 3, 1, m.nRows, "0", v -> {
            m.nRows = (int) Math.round(v);
            resweep();
        }));
        rowSpSp = g.add(new SpinParam("Row spacing", "mm", 5, 100, 1, m.rowSpacing, "0.#", v -> {
            m.rowSpacing = v;
            resweep();
        }));
        array.add(UI.fix(rowsSp.panel));
        array.add(UI.fix(dep(rowSpSp.panel, () -> m.nRows > 1)));
        arrayCombo = g.add(new ComboParam("Array shape", new String[]{"Straight", "V / arrow", "Arc", "Custom (Robot editor)"}, 0, i -> {
            if (i == 3) {
                if (!m.customLayout) {
                    m.layoutFromShape();                                   // start from where the sensors are now
                    m.customLayout = true;
                }
            } else {
                m.customLayout = false;
                m.arrayShape = i;
            }
            resweep();
        }));
        array.add(UI.fix(arrayCombo.panel));
        arrayAngleSp = g.add(new SpinParam("V angle", "deg", -45, 45, 1, m.arrayAngle, "0", v -> {
            m.arrayAngle = v;
            resweep();
        }));
        arrayRadiusSp = g.add(new SpinParam("Arc radius", "mm", 20, 1000, 5, m.arrayRadius, "0", v -> {
            m.arrayRadius = v;
            resweep();
        }));
        array.add(UI.fix(dep(arrayAngleSp.panel, () -> m.arrayShape == 1 && !m.customLayout)));
        array.add(UI.fix(dep(arrayRadiusSp.panel, () -> m.arrayShape == 2 && !m.customLayout)));
        array.add(UI.hint("<b>V / arrow</b>: + angle puts the outer sensors further forward, so the robot sees a bend "
                + "coming earlier without moving the whole board out; - makes an arrow with the centre leading. "
                + "A large angle (20 degrees and more) makes the robot turn in early; at a sharp corner the swept-forward wing "
                + "can then run past the new branch while the centre loses the old one, and the usual 'line lost near the "
                + "centre - drive straight' rule sends it straight on: check with Analysis > 90 degree corner. "
                + "<b>Arc</b>: sensors on a curve bulging forward. <b>Sensor pitch</b> is always measured sideways."));
        p.add(UI.fix(array));

        JPanel drive = UI.group("Drive (top speed)");
        rpmSp = g.add(new SpinParam("Motor RPM", "rpm", 10, 3000, 5, m.motorRpm, "0", v -> {
            m.motorRpm = v;
            m.applyHardware();
        }));
        ratedSp = g.add(new SpinParam("Motor rated V", "V", 1, 24, 0.5, m.motorVolt, "0.0", v -> {
            m.motorVolt = v;
            m.applyHardware();
        }));
        battSp = g.add(new SpinParam("Battery voltage", "V", 1, 24, 0.1, m.battVolt, "0.0", v -> {
            m.battVolt = v;
            m.applyHardware();
        }));
        loadSp = g.add(new SpinParam("Load factor", "%", 10, 100, 1, m.loadPct, "0", v -> {
            m.loadPct = v;
            m.applyHardware();
        }));
        overrideSp = g.add(new SpinParam("Measured top speed", "mm/s", 0, 3000, 10, m.topSpeedOverride, "0", v -> {
            m.topSpeedOverride = v;
            m.applyHardware();
        }));
        drive.add(UI.fix(rpmSp.panel));
        drive.add(UI.fix(ratedSp.panel));
        drive.add(UI.fix(battSp.panel));
        drive.add(UI.fix(loadSp.panel));
        drive.add(UI.fix(overrideSp.panel));
        p.add(UI.fix(drive));

        JPanel leds = UI.group("LEDs near the sensors (white or coloured)");
        ledCk = g.add(new CheckParam("Fit LEDs near the sensors", m.whiteLeds, b -> {
            m.whiteLeds = b;
            ledRecal();
        }));
        leds.add(UI.fix(ledCk.panel));
        java.util.function.BooleanSupplier ledOn = () -> m.whiteLeds;
        leds.add(UI.fix(dep(g.add(new ComboParam("Colour", Model.LED_COLORS, 0, i -> {
            m.ledColor = i;
            ledRecal();
        })).panel, ledOn)));
        leds.add(UI.fix(dep(g.add(new ComboParam("Used as", new String[]{"Indicator (lit on the line)",
            "Floor light (always on)"}, 0, i -> {
                m.whiteMode = i;
                ledRecal();
            })).panel, ledOn)));
        leds.add(UI.fix(dep(g.add(new ComboParam("Placement", new String[]{"Behind each sensor", "Between the sensors",
            "Row in front", "Row behind"}, 0, i -> {
                m.ledPlace = i;
                ledRecal();
            })).panel, () -> m.whiteLeds && !m.customLeds)));
        leds.add(UI.fix(dep(g.add(new SpinParam("Distance from row", "mm", 0, 40, 0.5, m.ledOffset, "0.0", v -> {
            m.ledOffset = v;
            ledRecal();
        })).panel, () -> m.whiteLeds && !m.customLeds && m.ledPlace != 1)));
        ledPowerP = g.add(new Param("Brightness", "%", 0, 100, m.whitePower * 100, 0, v -> m.whitePower = v / 100));
        leds.add(UI.fix(dep(ledPowerP.panel, ledOn)));
        leds.add(UI.fix(dep(g.add(new Param("Sensor sees the LED light", "%", 0, 100, m.whiteSeen * 100, 0,
                v -> m.whiteSeen = v / 100)).panel, ledOn)));
        leds.add(UI.fix(g.add(new CheckParam("Recalibrate when the LEDs change", m.ledRecal, b -> m.ledRecal = b)).panel));
        leds.add(UI.hint("Many boards have an LED next to each IR sensor. As an <b>indicator</b> it lights while its "
                + "sensor sees the line; without light barriers (Sensors tab) some of its light leaks into the sensors "
                + "and pushes the readings up, which can make an edge sensor flicker. As a <b>floor light</b> it is always "
                + "on and lights the spots the sensors look at: the line still reads darker than the floor, and the "
                + "ambient light becomes a smaller share of the signal - but too much light saturates the white "
                + "readings at 1023. Light falls off with distance, so <b>placement</b> and <b>distance</b> matter. "
                + "An IR sensor with a daylight filter responds little to visible light: red the most, blue hardly at "
                + "all (<b>Sensor sees the LED light</b> is the figure for white). Changes recalibrate, unless <b>Recalibrate when the LEDs "
                + "change</b> is off: then you see what a real robot does when its LEDs change after the calibration (the HUD's "
                + "cal line shows whether the threshold still fits). Switch them while running: the bulb button at the top, "
                + "F7 (on / off), F8 / Shift+F8 (brighter / dimmer)."));
        p.add(UI.fix(leds));

        JPanel shield = UI.group("Light shield");
        shield.add(UI.fix(g.add(new Param("Ambient light blocked", "%", 0, 95, m.ambientShield * 100, 0, v -> {
            m.ambientShield = v / 100;
            recalibrate();
        })).panel));
        shield.add(UI.hint("A skirt or cover around the sensor board (black foam, card, a 3D-printed hood) that keeps "
                + "room light and sunlight off the floor under the sensors. This is the most effective cure for "
                + "changing ambient light; 70-90 % is typical for a skirt that almost touches the floor."));
        p.add(UI.fix(shield));

        hwInfo = new JLabel(" ");
        hwInfo.setBorder(new EmptyBorder(4, 6, 2, 4));
        hwInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(hwInfo);
        p.add(UI.hint("Enter real measurements; the numbers above are calculated from them. "
                + "<b>Wheels</b>: two driven wheels and a caster, or four wheels with two motors "
                + "per side (skid steer: wire each side's motors in parallel). A 4-wheel robot has to scrub its tyres "
                + "sideways to turn, so it turns less for the same wheel speeds - more for long <b>axle spacing</b> "
                + "(front to rear axle). <b>Skid factor</b> sets that for the Simple model. "
                + "<b>Steer</b> layouts work like a car: a servo turns the front wheels (<b>steering lock</b> each way, "
                + "<b>steering speed</b>: an SG90 does about 300-600 deg/s) and the rear wheels (or all four) drive. "
                + "<b>Steered by</b>: a servo can hold any angle; a DC motor (toy-car steering) only goes full left, "
                + "full right or springs back to the centre. <b>Steering delay</b>: before it starts to move (a servo "
                + "only gets a new position every 20 ms, so 10-20 ms is typical). Measure both: Motors > From real "
                + "measurements. A car "
                + "cannot turn on the spot: its tightest turn is axle spacing / tan(lock), so it must slow down for sharp "
                + "corners. For all 4-wheel layouts <b>axle spacing</b> is front to rear axle. With 4 wheels, <b>axle to "
                + "sensors</b> is measured from half-way between the axles. <b>Wheel base</b>: centre of one wheel to "
                + "centre of the other (the track width). <b>Tire width</b> only changes how the robot is drawn. <b>Axle to sensors</b>: wheel axle "
                + "centreline to the sensor row. <b>Sensor pitch</b>: centre-to-centre between neighbouring sensors. "
                + "<b>Sensor height</b> and <b>beam half-angle</b> give the size of the spot each sensor sees on the floor "
                + "(height x tan(angle)). <b>Top speed</b> = wheel circumference x RPM / 60, scaled by "
                + "battery / rated voltage and the load factor (a loaded motor runs well below its no-load RPM, "
                + "roughly 60-80 %). If you have timed the real robot over a known distance, put that speed in "
                + "<b>Measured top speed</b> (0 = calculate from the motor)."));
        p.add(UI.resetRow("Reset hardware tab", g));
        return p;
    }

    /** The current robot as saved presets store it: 11 hardware values, top-speed override, 6 motor, 5 MCU, 2 rows. */
    private double[] currentRobot() {
        return new double[]{m.wheelBase, m.wheelDia, m.forward, m.nSensors, m.spacing, m.sensorHeight, m.beamAngle,
            m.motorRpm, m.motorVolt, m.battVolt, m.loadPct, m.topSpeedOverride,
            m.gainL, m.gainR, m.lagL, m.lagR, m.deadL, m.deadR,
            m.clockMHz, m.adcCycles, m.digCycles, m.mathCycles, m.loopDelayMs, m.nRows, m.rowSpacing, m.tireWidth,
            m.driveModel, m.massG, m.stallTorque, m.driveWeightPct, m.gearFrictionPct, m.casterCrr, m.decayMode, m.backlashMs,
            m.arrayShape, m.arrayAngle, m.arrayRadius, m.chassisShape, m.bodyWidth, m.bodyFront, m.bodyRear, m.armWidth,
            m.roundDia, m.casterPos, m.boardMargin, m.wheelCount, m.axleSpacing, m.skidFactor, m.sideFriction,
            m.steerMode, m.maxSteerDeg, m.servoRate, m.steerActuator, m.steerDelayMs, m.driverType, m.driverDrop};
    }

    /**
     * Fill the robot fields from a preset. Built-in presets hold the first 11 values (hardware);
     * saved robots also carry the top-speed override, motor and MCU settings.
     */
    private void applyRobot(double[] v) {
        wheelBaseSp.set(v[0]);
        wheelDiaSp.set(v[1]);
        axleSp.set(v[2]);
        countSp.set(v[3]);
        pitchSp.set(v[4]);
        heightSp.set(v[5]);
        angleSp.set(v[6]);
        rpmSp.set(v[7]);
        ratedSp.set(v[8]);
        battSp.set(v[9]);
        loadSp.set(v[10]);
        overrideSp.set(v.length > 11 ? v[11] : 0);
        if (v.length == 13 || v.length == 15) {             // built-in robots also set the motor response time
            lagLP.set(v[12]);
            lagRP.set(v[12]);
        }
        if (v.length == 15) {                                // ... and the wheel layout
            wheelsCombo.select(v[13] == 4 ? 1 : 0);
            axleSpSp.set(v[14]);
        }
        if (v.length >= 23) {
            gainLP.set(v[12]);
            gainRP.set(v[13]);
            lagLP.set(v[14]);
            lagRP.set(v[15]);
            deadLP.set(v[16]);
            deadRP.set(v[17]);
            clockSpin.set(v[18]);
            adcSpin.set(v[19]);
            digSpin.set(v[20]);
            mathSpin.set(v[21]);
            delayParam.set(v[22]);
        }
        if (v.length >= 25) {
            rowsSp.set(v[23]);
            rowSpSp.set(v[24]);
        }
        if (v.length >= 26) {
            tireSp.set(v[25]);
        }
        if (v.length >= 34) {
            driveCombo.select((int) v[26]);
            massSp.set(v[27]);
            torqueSp.set(v[28]);
            weightSp.set(v[29]);
            frictionSp.set(v[30]);
            casterSp.set(v[31]);
            decayCombo.select((int) v[32]);
            backlashSp.set(v[33]);
        }
        if (v.length >= 45) {
            arrayCombo.select((int) v[34]);
            arrayAngleSp.set(v[35]);
            arrayRadiusSp.set(v[36]);
            shapeCombo.select((int) v[37] == 3 && m.customOutline.size() < 3 ? 0 : (int) v[37]);
            bodyWSp.set(v[38]);
            bodyFSp.set(v[39]);
            bodyRSp.set(v[40]);
            armSp.set(v[41]);
            roundSp.set(v[42]);
            casterPosSp.set(v[43]);
            boardSp.set(v[44]);
        }
        if (v.length >= 49) {
            wheelsCombo.select(v[45] == 4 ? (v.length >= 52 && v[49] > 0 ? 1 + (int) v[49] : 1) : 0);
            axleSpSp.set(v[46]);
            skidSp.set(v[47]);
            sideFricSp.set(v[48]);
        }
        if (v.length >= 52) {
            steerSp.set(v[50]);
            servoSp.set(v[51]);
        }
        if (v.length >= 54) {
            actCombo.select((int) v[52]);
            steerDelaySp.set(v[53]);
        }
        if (v.length >= 56) {
            driverCombo.setQuiet((int) v[54]);
            m.driverType = driverCombo.index();
            dropSp.set(v[55]);
        }
    }

    // ------------------------------------------------------------------ saved presets
    private static String[] savedItems(String cat) {
        List<String> names = UserPresets.names(cat);
        String[] r = new String[names.size() + 1];
        r[0] = names.isEmpty() ? "None saved yet" : "Choose...";
        for (int i = 0; i < names.size(); i++) {
            r[i + 1] = names.get(i);
        }
        return r;
    }

    /** A menu of saved presets plus "Save current as..." and "Delete..." buttons. */
    private JPanel savedBlock(String cat, String label, Supplier<double[]> current, Consumer<double[]> apply) {
        JPanel box = column();
        MenuParam menu = new MenuParam(label, savedItems(cat), i -> {
            List<String> names = UserPresets.names(cat);
            if (i < names.size()) {
                double[] v = UserPresets.get(cat, names.get(i));
                if (v != null) {
                    apply.accept(v);
                }
            }
        });
        JButton save = UI.button("Save current as...", "save", "Stored in " + UserPresets.file().getPath());
        save.addActionListener(e -> {
            String name = JOptionPane.showInputDialog(this, "Name for this preset:", "Save preset",
                    JOptionPane.QUESTION_MESSAGE);
            if (name == null || name.trim().isEmpty()) {
                return;
            }
            name = name.trim();
            if (UserPresets.names(cat).contains(name) && JOptionPane.showConfirmDialog(this,
                    "\"" + name + "\" already exists. Replace it?", "Save preset",
                    JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
                return;
            }
            try {
                UserPresets.put(cat, name, current.get());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not save the preset:\n" + ex.getMessage(), "Save preset",
                        JOptionPane.ERROR_MESSAGE);
            }
            menu.setItems(savedItems(cat));
        });
        JButton del = UI.button("Delete...", "delete", "Remove one of the saved presets");
        del.addActionListener(e -> {
            List<String> names = UserPresets.names(cat);
            if (names.isEmpty()) {
                JOptionPane.showMessageDialog(this, "There are no saved presets to delete.", "Delete preset",
                        JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            Object pick = JOptionPane.showInputDialog(this, "Delete which saved preset?", "Delete preset",
                    JOptionPane.QUESTION_MESSAGE, null, names.toArray(), names.get(0));
            if (pick == null) {
                return;
            }
            try {
                UserPresets.delete(cat, pick.toString());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Could not delete the preset:\n" + ex.getMessage(),
                        "Delete preset", JOptionPane.ERROR_MESSAGE);
            }
            menu.setItems(savedItems(cat));
        });
        JPanel btns = new JPanel(new GridLayout(1, 2, 6, 0));
        btns.setBorder(new EmptyBorder(3, 4, 3, 4));
        btns.add(save);
        btns.add(del);
        box.add(UI.fix(menu.panel));
        box.add(UI.fix(btns));
        return box;
    }

    /** Turn measurements of the real robot into settings. */
    private JPanel measureGroup() {
        JPanel box = UI.group("From real measurements");
        SpinParam dist = new SpinParam("Run distance", "mm", 100, 20000, 100, 1000, "0", v -> { }, false);
        SpinParam time = new SpinParam("Run time", "s", 0.1, 100, 0.05, 2.0, "0.00", v -> { }, false);
        JButton a1 = UI.button("Use as top speed", "play", "Drive straight at full PWM; time it over a measured distance "
                + "(start timing once it is up to speed)");
        a1.addActionListener(e -> {
            overrideSp.set(dist.get() / time.get());
            trackPanel.toast(String.format("Measured top speed %.0f mm/s", dist.get() / time.get()));
        });
        SpinParam rise = new SpinParam("Time to 63 % speed", "ms", 5, 2000, 5, 60, "0", v -> { }, false);
        JButton a2 = UI.button("Use as response", "step", "From standstill to full PWM: the time until the robot reaches "
                + "63 % of its top speed (from a video, or encoder counts)");
        a2.addActionListener(e -> {
            double tau = rise.get();
            lagLP.set(tau);
            lagRP.set(tau);
            // physical model: tau = m * v0 / (2 * Fs) gives the stall force that matches, then the torque
            double v0 = m.noLoadSpeedRated() * m.battVolt / Math.max(m.motorVolt, 0.1) / 1000.0;
            double fs = m.effMassKg() * v0 / (2 * tau / 1000.0) / (m.battVolt / Math.max(m.motorVolt, 0.1));
            torqueSp.set(fs * (m.wheelDia / 2) / 98.0665);
            trackPanel.toast(String.format("Motor lag %.0f ms, stall torque %.2f kg.cm", tau, torqueSp.get()));
        });
        SpinParam pwm = new SpinParam("Lowest PWM that moves", "0-255", 0, 255, 1, 40, "0", v -> { }, false);
        JButton a3 = UI.button("Use as dead band", "calibrate", "Raise the PWM slowly from 0: the value where the "
                + "robot starts to roll");
        a3.addActionListener(e -> {
            double duty = pwm.get() / 255.0;
            deadLP.set(duty * 100);
            deadRP.set(duty * 100);
            double vr = m.battVolt / Math.max(m.motorVolt, 0.1);
            frictionSp.set(Math.max(0, Math.min(50, duty * vr * 100 - 1)));
            trackPanel.toast(String.format("Dead zone %.0f %%, gearbox friction %.0f %%", duty * 100, frictionSp.get()));
        });
        SpinParam lockT = new SpinParam("Straight to full lock", "ms", 10, 2000, 5, 120, "0", v -> { }, false);
        SpinParam lockD = new SpinParam("Steering start delay", "ms", 0, 300, 1, 15, "0", v -> { }, false);
        JButton a5 = UI.button("Use for the steering", "tool_robot", "Car steering: film the front wheels and time a "
                + "full-lock command from straight (60 fps video: count frames x 16.7 ms)");
        a5.addActionListener(e -> {
            servoSp.set(m.maxSteerDeg / Math.max(lockT.get(), 1) * 1000);
            steerDelaySp.set(lockD.get());
            trackPanel.toast(String.format("Steering speed %.0f deg/s, delay %.0f ms", servoSp.get(), lockD.get()));
        });
        SpinParam rpmL = new SpinParam("Left wheel RPM", "rpm", 1, 5000, 1, 180, "0", v -> { }, false);
        SpinParam rpmR = new SpinParam("Right wheel RPM", "rpm", 1, 5000, 1, 180, "0", v -> { }, false);
        JButton a4 = UI.button("Use as motor match", "shuffle", "Both wheels off the ground, same PWM: count each "
                + "wheel's turns over 10 s (x6 = RPM)");
        a4.addActionListener(e -> {
            double avg = (rpmL.get() + rpmR.get()) / 2;
            gainLP.set(100 * rpmL.get() / avg);
            gainRP.set(100 * rpmR.get() / avg);
            trackPanel.toast(String.format("Speed factors L %.1f %%, R %.1f %%", gainLP.get(), gainRP.get()));
        });
        for (JComponent c : new JComponent[]{dist.panel, time.panel, buttonRow(a1), rise.panel, buttonRow(a2), pwm.panel,
            buttonRow(a3), rpmL.panel, rpmR.panel, buttonRow(a4), lockT.panel, lockD.panel, buttonRow(a5)}) {
            box.add(UI.fix(c));
        }
        box.add(UI.hint("The simulator is only as good as these numbers (the steering ones only for car steering), and the presets are guesses. Measure "
                + "them on your robot (a phone video at 60 fps and a ruler are enough), type them in and press Use. "
                + "Then compare a real run with the simulated one before trusting the PID values."));
        return box;
    }

    private JPanel motorTab() {
        ResetGroup g = new ResetGroup(master, "Motors");
        JPanel p = column();
        p.add(UI.fix(g.add(new CheckParam("Allow reverse wheel speed", m.allowReverse, b -> m.allowReverse = b)).panel));

        JPanel left = UI.group("Left motor");
        Param gl = gainLP = g.add(new Param("Speed factor", "%", 50, 150, m.gainL, 1, v -> m.gainL = v));
        left.add(UI.fix(gl.panel));
        lagLP = g.add(new Param("Motor lag", "ms", 0, 300, m.lagL, 0, v -> m.lagL = v));
        deadLP = g.add(new Param("Dead zone", "%", 0, 50, m.deadL, 0, v -> m.deadL = v));
        left.add(UI.fix(dep(lagLP.panel, () -> m.driveModel == 0)));
        left.add(UI.fix(deadLP.panel));
        p.add(UI.fix(left));

        JPanel right = UI.group("Right motor");
        Param gr = gainRP = g.add(new Param("Speed factor", "%", 50, 150, m.gainR, 1, v -> m.gainR = v));
        right.add(UI.fix(gr.panel));
        lagRP = g.add(new Param("Motor lag", "ms", 0, 300, m.lagR, 0, v -> m.lagR = v));
        deadRP = g.add(new Param("Dead zone", "%", 0, 50, m.deadR, 0, v -> m.deadR = v));
        right.add(UI.fix(dep(lagRP.panel, () -> m.driveModel == 0)));
        right.add(UI.fix(deadRP.panel));
        p.add(UI.fix(right));

        JButton rand = UI.button("Random TT-motor mismatch (\u00B112%)", "shuffle",
                "Give the two motors random speed factors, like two cheap gear motors from the same bag");
        rand.addActionListener(e -> {
            Random r = new Random();
            gl.set(Math.round((88 + r.nextDouble() * 24) * 10) / 10.0);
            gr.set(Math.round((88 + r.nextDouble() * 24) * 10) / 10.0);
        });
        JPanel rr = new JPanel(new BorderLayout());
        rr.setBorder(new EmptyBorder(3, 4, 3, 4));
        rr.add(rand, BorderLayout.CENTER);
        p.add(UI.fix(rr));
        p.add(UI.hint("<b>Allow reverse</b> lets the robot pivot in sharp corners. <b>Speed factor</b>: real wheel speed for the same PWM command (100 % = ideal). Cheap TT "
                + "motors are rarely within 10 % of each other, so the robot drifts to the slower side and the PID "
                + "has to correct it. <b>Motor lag</b>: how slowly the wheel reaches the commanded speed. "
                + "<b>Dead zone</b>: commands below this share of max speed do not turn the motor at all."));
        JPanel phys = UI.group("Drive model");
        driveCombo = g.add(new ComboParam("Model", new String[]{"Simple (motor lag)", "Physical (mass, torque)"}, 0,
                i -> m.driveModel = i));
        phys.add(UI.fix(driveCombo.panel));
        dropSp = g.add(new SpinParam("Driver drop", "V", 0, 4, 0.05, 0, "0.00", v -> m.driverDrop = v));
        driverCombo = g.add(new ComboParam("Motor driver", DRIVER_NAMES, 0, i -> {
            m.driverType = i;
            dropSp.set(DRIVER_DROP[i]);
            decayCombo.select(DRIVER_DECAY[i]);
        }));
        phys.add(UI.fix(driverCombo.panel));
        phys.add(UI.fix(dropSp.panel));
        massSp = g.add(new SpinParam("Robot mass", "g", 20, 5000, 10, m.massG, "0", v -> m.massG = v));
        torqueSp = g.add(new SpinParam("Stall torque", "kg.cm", 0.02, 20, 0.05, m.stallTorque, "0.00", v -> m.stallTorque = v));
        weightSp = g.add(new SpinParam("Weight on wheels", "%", 20, 100, 5, m.driveWeightPct, "0", v -> m.driveWeightPct = v));
        frictionSp = g.add(new SpinParam("Gearbox friction", "%", 0, 50, 1, m.gearFrictionPct, "0", v -> m.gearFrictionPct = v));
        casterSp = g.add(new SpinParam("Caster drag", "Crr", 0, 0.5, 0.01, m.casterCrr, "0.00", v -> m.casterCrr = v));
        decayCombo = g.add(new ComboParam("Between pulses", new String[]{"Brake (slow decay)", "Coast (fast decay)"}, 0,
                i -> m.decayMode = i));
        backlashSp = g.add(new SpinParam("Gear backlash", "ms", 0, 50, 1, m.backlashMs, "0", v -> m.backlashMs = v));
        sideFricSp = g.add(new SpinParam("Side scrub (4WD)", "mu", 0, 1.5, 0.05, m.sideFriction, "0.00", v -> m.sideFriction = v));
        for (JComponent c : new JComponent[]{massSp.panel, torqueSp.panel, weightSp.panel, frictionSp.panel, casterSp.panel,
            decayCombo.panel, backlashSp.panel}) {
            phys.add(UI.fix(dep(c, () -> m.driveModel == 1)));
        }
        phys.add(UI.fix(dep(sideFricSp.panel, () -> m.driveModel == 1 && m.wheelCount == 4 && m.steerMode == 0)));
        driveInfo = new JLabel(" ");
        driveInfo.setBorder(new EmptyBorder(4, 6, 2, 4));
        driveInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        phys.add(driveInfo);
        phys.add(UI.hint("<b>Simple</b> makes each wheel follow its command with the motor lag above. <b>Physical</b> "
                + "works it out the way a real robot does: the motors' torque (falling linearly from <b>stall torque</b> "
                + "to zero at the no-load RPM) pushes the robot's <b>mass</b> and turns its inertia, so a heavier robot "
                + "speeds up, slows down and turns in later. <b>Gearbox friction</b> gives the dead band (low PWM does "
                + "nothing). <b>Weight on wheels</b>: the rest sits on the caster, which drags (<b>Crr</b>: 0.02 ball on "
                + "paper, 0.1 worn ball, 0.3 a skid) and leaves less grip for the wheels. <b>Motor driver</b> sets the <b>driver drop</b> (volts lost "
                + "in the driver at running current: L298N about 2 V, TB6612 0.4 V, DRV8833 0.25 V - so a 6 V motor on "
                + "a 7.4 V pack through an L298N gets about 5.4 V) and how it behaves between pulses; it lowers the top "
                + "speed in both models and is the default driver of the Arduino export. <b>Between pulses</b>: a TB6612 "
                + "or DRV8833 usually <b>brakes</b>; an L298N driven with EN = PWM <b>coasts</b>, so slowing down "
                + "(the inner wheel in a bend) is much slower. <b>Backlash</b>: play in the gears when a wheel reverses. <b>Side scrub</b> (4 wheels): how hard the tyres grip "
                + "sideways while the robot turns on the spot - it costs torque on every bend. "
                + "Stall torque is on the motor's data sheet (at the gearbox shaft). Motor lag, load factor and the dead "
                + "zone sliders are not used in this model, except the dead zone of the driver."));
        p.add(UI.fix(phys));

        JPanel enc = UI.group("Wheel encoders and speed loop");
        SpinParam pprSp = g.add(new SpinParam("Pulses per motor turn", "", 1, 2000, 1, m.encPpr, "0", v -> m.encPpr = v));
        SpinParam gearSp = g.add(new SpinParam("Gear ratio", ":1", 1, 1000, 1, m.encGear, "0.#", v -> m.encGear = v));
        CheckParam quadCk = g.add(new CheckParam("Count every edge of both channels (x4)", m.encQuad, b -> m.encQuad = b));
        SpinParam cprSp = g.add(new SpinParam("Custom counts per wheel turn", "", 1, 100000, 1, m.encCustomCpr, "0", v -> m.encCustomCpr = v));
        enc.add(UI.fix(g.add(new ComboParam("Encoder", new String[]{"None", "N20 magnetic",
            "TT optical disc", "Pololu magnetic", "Custom"}, 0, i -> {
                m.encType = i;
                if (i == 1) {
                    pprSp.set(7);
                    quadCk.set(true);
                } else if (i == 2) {
                    pprSp.set(20);
                    gearSp.set(1);
                    quadCk.set(false);
                } else if (i == 3) {
                    pprSp.set(3);
                    quadCk.set(true);
                }
            })).panel));
        enc.add(UI.fix(dep(pprSp.panel, () -> m.encType >= 1 && m.encType <= 3)));
        enc.add(UI.fix(dep(gearSp.panel, () -> m.encType == 1 || m.encType == 3)));
        enc.add(UI.fix(dep(quadCk.panel, () -> m.encType >= 1 && m.encType <= 3)));
        enc.add(UI.fix(dep(cprSp.panel, () -> m.encType == 4)));
        enc.add(UI.fix(dep(g.add(new CheckParam("Hold the wheel speeds with a PI loop", m.speedLoop, b -> m.speedLoop = b)).panel, () -> m.encType > 0)));
        enc.add(UI.fix(dep(g.add(new SpinParam("Speed loop P", "", 0, 5, 0.05, m.speedKp, "0.00", v -> m.speedKp = v)).panel, () -> m.encType > 0 && m.speedLoop)));
        enc.add(UI.fix(dep(g.add(new SpinParam("Speed loop I", "1/s", 0, 100, 0.5, m.speedKi, "0.0", v -> m.speedKi = v)).panel, () -> m.encType > 0 && m.speedLoop)));
        encInfo = new JLabel(" ");
        encInfo.setBorder(new EmptyBorder(4, 6, 2, 4));
        encInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        enc.add(encInfo);
        enc.add(UI.hint("<b>N20 magnetic</b>: 7 pulses per motor turn on the motor shaft; <b>TT optical disc</b>: 20 slots "
                + "on the wheel shaft; <b>Pololu magnetic</b>: 12 counts per motor turn (3 pulses x4). "
                + "Encoders count how far each wheel turned. A magnetic encoder on the motor shaft counts "
                + "pulses x gear ratio (x4 with both channels) per wheel turn - thousands; an optical disc on the wheel "
                + "only 20 or 40, so its speed reading is coarse at short loop times. The <b>speed loop</b> measures each "
                + "wheel every loop and corrects the PWM, so mismatched motors, battery sag and the driver drop no longer "
                + "change the speed - at the price of the measuring noise. Gear ratio: N20 and Pololu motors print it "
                + "(e.g. 30:1); the motor RPM on the Hardware tab is at the wheel."));
        p.add(UI.fix(enc));

        JPanel bat = UI.group("Battery capacity and current");
        bat.add(UI.fix(g.add(new SpinParam("Capacity (0: off)", "mAh", 0, 20000, 50, m.battCapacity, "0", v -> m.battCapacity = v)).panel));
        bat.add(UI.fix(dep(g.add(new SpinParam("Internal resistance", "ohm", 0, 3, 0.01, m.battResistance, "0.00", v -> m.battResistance = v)).panel, () -> m.battCapacity > 0)));
        bat.add(UI.fix(g.add(new SpinParam("Motor stall current", "A", 0.05, 20, 0.05, m.stallCurrent, "0.00", v -> m.stallCurrent = v)).panel));
        bat.add(UI.fix(g.add(new SpinParam("MCU and rest", "A", 0, 2, 0.01, m.logicCurrent, "0.00", v -> m.logicCurrent = v)).panel));
        batInfo = new JLabel(" ");
        batInfo.setBorder(new EmptyBorder(4, 6, 2, 4));
        batInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        bat.add(batInfo);
        bat.add(UI.hint("The current is worked out from the motors (PWM, speed and stall current: most when starting and "
                + "in sharp bends), the IR and white LEDs and the electronics. With a capacity set, the battery voltage "
                + "falls with the charge used and with the current (internal resistance) - this replaces guessing the "
                + "drain and sag on the World tab. Stall current: motor data sheet (N20 6 V about 0.4-1.6 A, TT 1.5 A)."));
        p.add(UI.fix(bat));

        p.add(UI.fix(measureGroup()));
        p.add(UI.resetRow("Reset motors tab", g));
        return p;
    }

    private JPanel mcuTab() {
        ResetGroup g = new ResetGroup(master, "MCU");
        JPanel p = column();
        p.add(UI.fix(g.add(new ComboParam("MCU", McuProfile.names(), 0, this::applyProfile)).panel));
        clockSpin = g.add(new SpinParam("Clock", "MHz", 0.1, 1000, 1, m.clockMHz, "0.##", v -> m.clockMHz = v));
        adcSpin = g.add(new SpinParam("analogRead cost", "cycles", 0, 100000, 100, m.adcCycles, "0", v -> m.adcCycles = v));
        digSpin = g.add(new SpinParam("digitalRead cost", "cycles", 0, 10000, 10, m.digCycles, "0", v -> m.digCycles = v));
        mathSpin = g.add(new SpinParam("PID math per loop", "cycles", 0, 200000, 100, m.mathCycles, "0", v -> m.mathCycles = v));
        p.add(UI.fix(clockSpin.panel));
        p.add(UI.fix(adcSpin.panel));
        p.add(UI.fix(digSpin.panel));
        p.add(UI.fix(mathSpin.panel));
        delayParam = g.add(new Param("Loop delay (delay() / timer)", "ms", 0, 50, m.loopDelayMs, 1, v -> m.loopDelayMs = v));
        p.add(UI.fix(dep(delayParam.panel, () -> m.loopMode == 0)));

        JPanel loop = UI.group("Loop timing");
        loop.add(UI.fix(g.add(new ComboParam("Loop mode", new String[]{"Compute + delay()", "Fixed period (wait)"}, 0,
                i -> m.loopMode = i)).panel));
        loop.add(UI.fix(dep(g.add(new SpinParam("Fixed rate", "Hz", 1, 10000, 10, m.fixedHz, "0", v -> m.fixedHz = v)).panel, () -> m.loopMode == 1)));
        jitterSp = g.add(new SpinParam("Jitter (random)", "us", 0, 5000, 10, m.jitterUs, "0", v -> m.jitterUs = v));
        loop.add(UI.fix(jitterSp.panel));
        loop.add(UI.fix(g.add(new CheckParam("Sensors read by a separate task", m.sensorTask, b -> m.sensorTask = b)).panel));
        loop.add(UI.fix(dep(g.add(new SpinParam("Sensor task rate", "Hz", 10, 50000, 50, m.sensorTaskHz, "0",
                v -> m.sensorTaskHz = v)).panel, () -> m.sensorTask)));
        final double[] steps = {0, 0.5, 0.2, 0.1, 0.05, 0.02};
        loop.add(UI.fix(g.add(new ComboParam("Physics step", new String[]{"Automatic", "0.5 ms", "0.2 ms", "0.1 ms", "0.05 ms",
            "0.02 ms"}, 0, i -> m.physicsStepMs = steps[i])).panel));
        loop.add(UI.hint("<b>Fixed period</b> waits until the period is over, like a micros() timer at 50 Hz (the loop "
                + "delay is then ignored; if the work takes longer the loop simply runs slower). <b>Jitter</b> varies "
                + "each period at random. <b>Separate task</b>: the ADC is read by another core at its own rate "
                + "(ESP32 style), so the control loop no longer pays for it, but each reading is on average half a task "
                + "period old. <b>Physics step</b>: automatic picks half the shortest loop period (at most 0.5 ms) so fast "
                + "boards are not capped at 2 kHz."));
        p.add(UI.fix(loop));
        mcuInfo = new JLabel(" ");
        mcuInfo.setBorder(new EmptyBorder(4, 6, 2, 4));
        mcuInfo.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(mcuInfo);
        p.add(UI.hint("The control loop reads every sensor, does the PID maths, then writes the motor PWM. Its time = "
                + "(sensors x read cost + maths cost) / clock. Pick a board to fill in the clock and typical costs, "
                + "or type your own clock in the field. The cycle counts are rough estimates (e.g. Arduino "
                + "analogRead is about 104 us at 16 MHz and software float maths is slow); a slow clock gives a "
                + "slow loop, stale sensor data and late motor commands. Digital Output mode skips the ADC, so its loop is "
                + "much faster."));
        p.add(UI.resetRow("Reset MCU tab", g));
        return p;
    }

    private void applyProfile(int i) {
        McuProfile pr = McuProfile.ALL[i];
        clockSpin.set(pr.mhz);
        adcSpin.set(pr.adcCycles);
        digSpin.set(pr.digCycles);
        mathSpin.set(pr.mathCycles);
    }

    private JPanel environmentTab() {
        ResetGroup g = new ResetGroup(master, "World");
        JPanel p = column();

        JPanel light = UI.group("Light");
        light.add(UI.fix(g.add(new Param("Ambient light", "%", 0, 100, m.ambient * 100, 0, v -> m.ambient = v / 100)).panel));
        light.add(UI.fix(g.add(new ComboParam("Pattern", new String[]{"Uniform", "Gradient (dark to bright)",
            "Sunny patch (centre)"}, 0, i -> m.ambientMode = i)).panel));
        light.add(UI.fix(g.add(new Param("Lamp flicker", "%", 0, 100, m.flickerPct, 0, v -> m.flickerPct = v)).panel));
        light.add(UI.fix(g.add(new ComboParam("Mains", new String[]{"50 Hz (100 Hz flicker)", "60 Hz (120 Hz flicker)"}, 0,
                i -> m.flickerHz = i == 0 ? 100 : 120)).panel));
        light.add(UI.hint("Ambient light adds to every sensor reading. As it rises the white surface saturates at 1023 "
                + "and the contrast shrinks until the threshold no longer works, like a robot in direct sunlight. A fixed "
                + "threshold also fails when the light changes along the track. <b>Lamp flicker</b>: room lamps "
                + "(fluorescent tubes, cheap LED bulbs) pulse at twice the mains frequency; read at a similar loop rate this "
                + "shows up as a slow wobble in the readings. 20-40 % is common for cheap LED bulbs, ~0 for daylight."));
        p.add(UI.fix(light));

        JPanel surf = UI.group("Floor and line");
        floorP = g.add(new Param("Floor reflectance", "%", 10, 100, m.floorRefl, 0, v -> m.floorRefl = v));
        lineP = g.add(new Param("Line reflectance", "%", 0, 60, m.lineRefl, 0, v -> m.lineRefl = v));
        surf.add(UI.fix(floorP.panel));
        surf.add(UI.fix(lineP.panel));
        surf.add(UI.hint("100 % / 0 % is pure white paper and pure black line. A grey floor or a glossy or worn line "
                + "lowers the contrast; calibrate again after changing them."));
        p.add(UI.fix(surf));

        JPanel power = UI.group("Battery and temperature");
        drainP = g.add(new Param("Battery drain", "%/min", 0, 20, m.battDrainPctPerMin, 1, v -> m.battDrainPctPerMin = v));
        sagP = g.add(new Param("Voltage sag under load", "%", 0, 40, m.battSagPct, 0, v -> m.battSagPct = v));
        battLedCk = g.add(new CheckParam("IR LEDs run from the battery", m.sensorsOnBattery, b -> m.sensorsOnBattery = b));
        tempP = g.add(new Param("Temperature", "\u00B0C", 0, 70, m.tempC, 0, v -> m.tempC = v));
        riseP = g.add(new Param("Warm-up", "\u00B0C/min", 0, 5, m.tempRisePerMin, 1, v -> m.tempRisePerMin = v));
        power.add(UI.fix(drainP.panel));
        power.add(UI.fix(sagP.panel));
        power.add(UI.fix(battLedCk.panel));
        power.add(UI.fix(tempP.panel));
        power.add(UI.fix(riseP.panel));
        power.add(UI.hint("A falling battery slows the wheels (speed follows voltage) and, if the sensor LEDs run from "
                + "it, dims them. The LED output also falls 0.3 % for every degree above 25. Watch the white and black "
                + "readings drift away from the threshold you calibrated."));
        p.add(UI.fix(power));

        JPanel grip = UI.group("Wheel grip");
        grip.add(UI.fix(g.add(new Param("Traction (mu)", "", 0, 1, m.tractionMu, 2, v -> m.tractionMu = v)).panel));
        grip.add(UI.hint("0 = unlimited. With a value the robot cannot turn tighter than its grip allows and understeers "
                + "wide. Normal rubber on paper (0.6 and up) only matters for very fast robots; try 0.05 to see it."));
        p.add(UI.fix(grip));

        JPanel dist = UI.group("Disturbances");
        fadeP = g.add(new Param("Faded patch: contrast lost", "%", 10, 100, 80, 0, v -> {
        }));
        patchMuP = g.add(new Param("Slippery patch: grip", "mu", 0.01, 0.5, 0.05, 2, v -> {
        }));
        dist.add(UI.fix(fadeP.panel));
        dist.add(UI.fix(patchMuP.panel));
        JButton bump = UI.button("Bump", "bump", "Knock the robot sideways and turn it a little");
        JButton fade = UI.button("Faded ahead", "fade", "Put a faded stretch of line just ahead of the robot");
        JButton slip = UI.button("Slippery ahead", "slip", "Put a slippery patch just ahead of the robot");
        JButton stallL = UI.button("Stall left", "stall", "Block the left wheel for 0.5 s");
        JButton stallR = UI.button("Stall right", "stall", "Block the right wheel for 0.5 s");
        JButton clearP = UI.button("Clear patches", "clear", "Remove all faded and slippery patches");
        bump.addActionListener(e -> m.bump((rng.nextBoolean() ? 1 : -1) * (15 + rng.nextDouble() * 12),
                (rng.nextBoolean() ? 1 : -1) * (0.08 + rng.nextDouble() * 0.1)));
        fade.addActionListener(e -> m.addPatch(false, fadeP.get() / 100.0, 200, 80, 100));
        slip.addActionListener(e -> m.addPatch(true, patchMuP.get(), 250, 160, 100));
        stallL.addActionListener(e -> m.stallWheel(true, 0.5));
        stallR.addActionListener(e -> m.stallWheel(false, 0.5));
        clearP.addActionListener(e -> m.clearPatches());
        for (JButton b : new JButton[]{bump, fade, slip, stallL, stallR, clearP}) {
            b.setFocusable(false);
        }
        dist.add(UI.fix(buttonRow(bump, clearP)));
        dist.add(UI.fix(buttonRow(fade, slip)));
        dist.add(UI.fix(buttonRow(stallL, stallR)));
        dist.add(UI.hint("Use these while the robot runs. <b>Bump</b> knocks it sideways and turns it a little. A "
                + "<b>faded patch</b> (orange) lightens the line inside it. A <b>slippery patch</b> (blue) limits how "
                + "hard the robot can turn while it is on it; it only hurts in a bend. A <b>stall</b> blocks one wheel "
                + "for half a second."));
        dist.add(UI.fix(g.add(new SpinParam("LEDs switch off at", "s", 0, 600, 0.5, 0, "0.0", v -> m.ledOffAt = v)).panel));
        dist.add(UI.fix(g.add(new SpinParam("and back on at", "s", 0, 600, 0.5, 0, "0.0", v -> m.ledOnAt = v)).panel));
        dist.add(UI.hint("Timed LED switch (0 = never), the same in every run: e.g. a floor light failing at 4 s. Needs the "
                + "Hardware tab's LEDs fitted."));
        p.add(UI.fix(dist));

        JPanel rw = UI.group("Real-world effects");
        rw.add(UI.fix(g.add(new SpinParam("Board lifts under acceleration", "mm/g", -20, 20, 0.5, 0, "0.0", v -> m.bounceMmPerG = v)).panel));
        rw.add(UI.fix(g.add(new SpinParam("Shiny tape (glare)", "%", 0, 80, 1, 0, "0", v -> m.glossPct = v)).panel));
        rw.add(UI.fix(g.add(new SpinParam("Sensor response time", "ms", 0, 20, 0.1, 0, "0.0", v -> m.respMs = v)).panel));
        rw.add(UI.fix(g.add(new SpinParam("Motors warm up", "%/min", 0, 30, 0.5, 0, "0.0", v -> m.motorWarmPct = v)).panel));
        rw.add(UI.fix(dep(g.add(new SpinParam("Dust on the tyres", "%/min", 0, 30, 0.5, 0, "0.0", v -> m.gripLossPctPerMin = v)).panel,
                () -> m.tractionMu > 0)));
        rw.add(UI.hint("<b>Board lifts</b>: the chassis pitches when it speeds up or brakes, so the sensors rise or dip "
                + "(a long board on a 2-wheel robot: 1-4 mm per g; - if it dips) - the signal changes with every start and "
                + "stop. <b>Shiny tape</b>: PVC tape reflects IR like a mirror close to the floor - the black reads lighter, "
                + "with sparkle (matte paint or tape: 0). <b>Response time</b>: a phototransistor with a 10-47 k load resistor "
                + "needs 0.1-1 ms; the reading lags the floor. <b>Motors warm up</b>: brushed motors lose speed as they heat "
                + "(cools again with a 5 min time constant). <b>Dust</b>: grip falls over a long session (needs a traction "
                + "value). Typical starting values are given; measure your own robot."));
        p.add(UI.fix(rw));

        JPanel real = UI.group("Realism presets");
        JButton ideal = UI.button("Ideal", null, "Perfect sensors and power (the defaults)");
        JButton typical = UI.button("Typical", null, "A normal hobby robot on a normal day");
        JButton harsh = UI.button("Harsh", null, "Worn track, warm room, tired battery, no light barriers");
        ideal.addActionListener(e -> applyRealism(0));
        typical.addActionListener(e -> applyRealism(1));
        harsh.addActionListener(e -> applyRealism(2));
        for (JButton b : new JButton[]{ideal, typical, harsh}) {
            b.setFocusable(false);
        }
        real.add(UI.fix(buttonRow(ideal, typical, harsh)));
        real.add(UI.hint("Sets crosstalk, noise, floor and line reflectance, battery drain and sag, temperature and "
                + "loop jitter in one go, then recalibrates. They are examples to start from, not measurements of any "
                + "particular robot."));
        p.add(UI.fix(real));

        JPanel fail = UI.group("Failures");
        String[] stuck = new String[Model.MAXS + 1];
        stuck[0] = "None";
        for (int i = 1; i <= Model.MAXS; i++) {
            stuck[i] = "Sensor " + i;
        }
        fail.add(UI.fix(g.add(new ComboParam("Stuck sensor", stuck, 0, i -> m.stuckSensor = i - 1)).panel));
        fail.add(UI.fix(dep(g.add(new ComboParam("It reads", new String[]{"Line (black) all the time", "Floor (white) all the time"}, 0,
                i -> m.stuckOnLine = i == 0)).panel, () -> m.stuckSensor >= 0)));
        fail.add(UI.fix(g.add(new SpinParam("MCU resets below", "V", 0, 24, 0.1, m.brownoutV, "0.0", v -> m.brownoutV = v)).panel));
        fail.add(UI.fix(g.add(new CheckParam("Stop when it would tip over in a bend", m.tipCheck, b -> m.tipCheck = b)).panel));
        fail.add(UI.hint("<b>Stuck sensor</b>: a broken or dirty sensor (counted from the left) - see how the controller "
                + "copes. <b>MCU resets below</b>: a brown-out - when the battery sags under this voltage (starting, stalls) "
                + "the board restarts: motors off for 0.3 s and the PID starts from zero (0 = off; set the battery on the "
                + "Motors tab). <b>Tip over</b>: uses the height of the centre of mass (Robot editor parts) and the track "
                + "width; a motor stall is under Disturbances."));
        p.add(UI.fix(fail));

        p.add(UI.resetRow("Reset World tab", g));
        return p;
    }

    /** Example conditions: 0 ideal (the defaults), 1 typical, 2 harsh. Recalibrates afterwards, as a real robot would at start-up. */
    private void applyRealism(int level) {
        double[][] v = {
            //  crosstalk barriers floor line noise mismatch drain sag battLED temp rise jitter
            {0, 1, 100, 0, 1, 10, 0, 0, 0, 25, 0, 0},
            {6, 1, 85, 8, 1.5, 10, 1, 6, 1, 30, 0, 100},
            {15, 0, 70, 15, 3, 20, 3, 15, 1, 40, 1, 300}};
        double[] r = v[level];
        crosstalkP.set(r[0]);
        barriersCk.set(r[1] > 0);
        floorP.set(r[2]);
        lineP.set(r[3]);
        noiseP.set(r[4]);
        mismatchP.set(r[5]);
        drainP.set(r[6]);
        sagP.set(r[7]);
        battLedCk.set(r[8] > 0);
        tempP.set(r[9]);
        riseP.set(r[10]);
        jitterSp.set(r[11]);
        m.calibrate();
        thresholdParam.set(m.threshold);
        if (m.perSensorCal) {
            m.calibrateSweep();
        }
    }

    private void applySuggestion(DesignCheck.Suggestion g) {
        pitchSp.set(g.pitch);
        countSp.set(g.count);
        axleSp.set(g.axle);
        heightSp.set(g.height);
    }


    private static final String[] TOOL_ICONS = {"tool_select", "tool_freehand", "tool_line", "tool_polyline", "tool_curve",
        "tool_arc", "tool_rect", "tool_ellipse", "tool_erase", "tool_robot", "tool_pan", "tool_finish", "measure"};

    private static final String[] TOOL_TIPS = {
        "Click a shape to select it. Drag it to move it, drag a square handle to reshape it.",
        "Hold the button and draw freely.",
        "Drag from the start to the end. Hold Shift for 15 degree steps.",
        "Click each corner. Double-click, Enter or right-click finishes; click the first point to close it.",
        "Click points: the line becomes a smooth curve through them. Finish like a polyline.",
        "Click the start, the end, then a point on the arc.",
        "Drag from corner to corner. Hold Shift for a square.",
        "Drag the bounding box. Hold Shift for a circle.",
        "Draw to paint white over the track and remove it.",
        "Click to place the robot; drag to set its heading.",
        "Drag to move the view.",
        "Drag a line across the track: laps count when the front of the robot crosses it going forward "
                + "(either way round when you set it by hand). Track tab: Reset finish line.",
        "Drag to measure a distance on the track (Shift: 45 degree steps). The last measurement stays until the next."
    };

    private JPanel builderTab() {
        ResetGroup g = new ResetGroup(master, "Builder");
        TrackEditor ed = trackPanel.editor;
        JPanel p = column();

        JPanel canvas = UI.group("Canvas");
        JButton newCanvas = UI.button("New canvas...", "new", "Start from an empty arena of the size you choose (Ctrl+N)");
        JButton resize = UI.button("Resize...", "fit", "Change the arena size; the shapes keep their real positions");
        newCanvas.addActionListener(e -> canvasDialog(true));
        resize.addActionListener(e -> canvasDialog(false));
        canvas.add(UI.fix(buttonRow(newCanvas, resize)));
        canvas.add(UI.fix(new MenuParam("Size", HardwarePresets.menu("Choose...", HardwarePresets.ARENAS),
                i -> applyArena(HardwarePresets.ARENAS[i].v[0], HardwarePresets.ARENAS[i].v[1])).panel));
        canvas.add(UI.hint("<b>New canvas</b> clears everything for a clean start. <b>Size</b> / <b>Resize</b> change the "
                + "board size; a preset track is re-fitted, a track you drew keeps its real size. Exact width and height "
                + "are also in the Track tab."));
        p.add(UI.fix(canvas));

        JPanel tools = UI.group("Tools");
        JPanel pal = new JPanel(new GridLayout(0, 3, 4, 4));
        pal.setBorder(new EmptyBorder(2, 4, 2, 4));
        ButtonGroup bg = new ButtonGroup();
        toolButtons = new JToggleButton[TrackEditor.TOOL_NAMES.length];
        for (int i = 0; i < toolButtons.length; i++) {
            final int idx = i;
            JToggleButton b = UI.toggle(TrackEditor.TOOL_NAMES[i], TOOL_ICONS[i],
                    "<html><b>" + TrackEditor.TOOL_NAMES[i] + "</b><br>" + TOOL_TIPS[i] + "</html>");
            b.setMargin(new Insets(2, 3, 2, 3));
            b.setHorizontalAlignment(SwingConstants.LEFT);
            b.addActionListener(e -> trackPanel.setTool(idx));
            bg.add(b);
            pal.add(b);
            toolButtons[i] = b;
        }
        toolButtons[ed.tool].setSelected(true);
        g.addAction(() -> {
            trackPanel.setTool(TrackEditor.SELECT);
            toolButtons[TrackEditor.SELECT].setSelected(true);
        });
        tools.add(UI.fix(pal));
        selLabel = new JLabel(" ");
        selLabel.setBorder(new EmptyBorder(4, 6, 2, 4));
        selLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        tools.add(selLabel);
        tools.add(UI.hint("Hover over a tool for how to use it. Right-click, Enter or a double-click finishes "
                + "polylines and curves. <b>Esc or right-click</b> (with nothing half drawn) goes back to Select; "
                + "Esc again deselects. Hold Space and drag, or use the middle button, to pan."));
        p.add(UI.fix(tools));

        JPanel props = UI.group("Shape style");
        widthSp = g.add(new SpinParam("Width", "mm", 1, 300, 1, ed.width, "0.#", v -> style(() -> ed.width = v)));
        dashSp = g.add(new SpinParam("Dash length", "mm", 0, 500, 5, ed.dash, "0.#", v -> style(() -> ed.dash = v)));
        gapSp = g.add(new SpinParam("Dash gap", "mm", 0, 500, 5, ed.gap, "0.#", v -> style(() -> ed.gap = v)));
        radiusSp = g.add(new SpinParam("Corner radius", "mm", 0, 1000, 5, ed.radius, "0.#", v -> style(() -> ed.radius = v)));
        closedCk = g.add(new CheckParam("Closed (polyline / curve)", ed.closed, b -> style(() -> ed.closed = b)));
        filledCk = g.add(new CheckParam("Filled (shapes with an inside)", ed.filled, b -> style(() -> ed.filled = b)));
        eraseCk = g.add(new CheckParam("Erase (paint white)", ed.erase, b -> style(() -> ed.erase = b)));
        props.add(UI.fix(widthSp.panel));
        props.add(UI.fix(dashSp.panel));
        props.add(UI.fix(gapSp.panel));
        props.add(UI.fix(radiusSp.panel));
        props.add(UI.fix(closedCk.panel));
        props.add(UI.fix(filledCk.panel));
        props.add(UI.fix(eraseCk.panel));
        props.add(UI.hint("These values are used for the next shape you draw. With a shape selected they change "
                + "that shape instead. A dash needs both a length and a gap; 0 means a solid line. The corner "
                + "radius is for rectangles."));
        p.add(UI.fix(props));

        JPanel snap = UI.group("Snapping");
        snap.add(UI.fix(g.add(new CheckParam("Snap to grid", ed.snapGrid, b -> ed.snapGrid = b)).panel));
        gridSp = g.add(new SpinParam("Grid size", "mm", 1, 500, 5, ed.grid, "0.#", v -> ed.grid = v));
        snap.add(UI.fix(g.add(new CheckParam("Snap to ends of other shapes", ed.snapPoints, b -> ed.snapPoints = b)).panel));
        snap.add(UI.hint("Hold Alt while clicking to place a point without snapping (if your system lets "
                + "Alt-click through). Freehand is never snapped."));
        p.add(UI.fix(snap));

        JPanel gridBox = UI.group("Grid display");
        gridCk = g.add(new CheckParam("Show grid", ed.showGrid, b -> ed.showGrid = b));
        gridBox.add(UI.fix(gridCk.panel));
        gridBox.add(UI.fix(g.add(new ComboParam("Style", new String[]{"Lines", "Dots"}, ed.gridStyle,
                i -> ed.gridStyle = i)).panel));
        gridBox.add(UI.fix(gridSp.panel));
        gridBox.add(UI.fix(new MenuParam("Quick size", new String[]{"Choose...", "5 mm", "10 mm", "20 mm", "25 mm", "50 mm",
            "100 mm"}, i -> gridSp.set(new double[]{5, 10, 20, 25, 50, 100}[i])).panel));
        gridBox.add(UI.fix(g.add(new SpinParam("Dot size", "px", 0.5, 6, 0.5, ed.dotSize, "0.0", v -> ed.dotSize = v)).panel));
        gridBox.add(UI.fix(g.add(new SpinParam("Strong line every", "cells", 0, 50, 1, ed.majorEvery, "0",
                v -> ed.majorEvery = (int) Math.round(v))).panel));
        gridBox.add(UI.fix(g.add(new Param("Grid opacity", "%", 5, 100, ed.gridOpacity, 0,
                v -> ed.gridOpacity = (int) Math.round(v))).panel));
        gridBox.add(UI.hint("The grid is only a drawing aid (the sensors never see it). <b>Grid size</b> is also what "
                + "<b>Snap to grid</b> uses. When zoomed out it thins itself out so it never gets too dense; "
                + "every <b>strong</b> line / dot marks n cells (0 = none). It is easiest to read with the grid shown "
                + "in the Builder and hidden while the robot runs."));
        p.add(UI.fix(gridBox));

        JPanel edit = UI.group("Edit");
        undoBtn = UI.button("Undo", "undo", "Undo (Ctrl+Z)");
        redoBtn = UI.button("Redo", "redo", "Redo (Ctrl+Y)");
        deleteBtn = UI.button("Delete", "delete", "Delete the selected shape (Delete)");
        dupBtn = UI.button("Duplicate", "duplicate", "Copy the selected shape (Ctrl+D)");
        JButton clearAll = UI.button("Clear all", "clear", "Remove every shape (can be undone)");
        undoBtn.addActionListener(e -> ed.undo());
        redoBtn.addActionListener(e -> ed.redo());
        deleteBtn.addActionListener(e -> ed.deleteSelected());
        dupBtn.addActionListener(e -> ed.duplicateSelected());
        clearAll.addActionListener(e -> ed.clearAll());
        for (JButton b : new JButton[]{undoBtn, redoBtn, deleteBtn, dupBtn, clearAll}) {
            b.setFocusable(false);
        }
        edit.add(UI.fix(buttonRow(undoBtn, redoBtn)));
        edit.add(UI.fix(buttonRow(deleteBtn, dupBtn)));
        edit.add(UI.fix(buttonRow(clearAll)));
        SpinParam scPct = new SpinParam("Scale", "%", 1, 1000, 5, 110, "0", v -> { }, false);
        SpinParam rotD = new SpinParam("Rotate", "deg", -360, 360, 5, 90, "0", v -> { }, false);
        JButton doSc = UI.button("Scale", null, "Make the selected shape bigger or smaller about its centre (line width stays)");
        JButton doRot = UI.button("Rotate", null, "Turn the selected shape about its centre (+ = clockwise)");
        JButton mirH = UI.button("Mirror \u2194", null, "Mirror the selected shape left / right");
        JButton mirV = UI.button("Mirror \u2195", null, "Mirror the selected shape up / down");
        Runnable none = () -> trackPanel.toast("Select a shape first (Select tool)");
        doSc.addActionListener(e -> {
            if (!ed.transformSelected(scPct.get() / 100, scPct.get() / 100, 0)) {
                none.run();
            }
        });
        doRot.addActionListener(e -> {
            if (!ed.transformSelected(1, 1, rotD.get())) {
                none.run();
            }
        });
        mirH.addActionListener(e -> {
            if (!ed.transformSelected(-1, 1, 0)) {
                none.run();
            }
        });
        mirV.addActionListener(e -> {
            if (!ed.transformSelected(1, -1, 0)) {
                none.run();
            }
        });
        edit.add(UI.fix(scPct.panel));
        edit.add(UI.fix(rotD.panel));
        edit.add(UI.fix(buttonRow(doSc, doRot)));
        edit.add(UI.fix(buttonRow(mirH, mirV)));
        edit.add(UI.hint("Keys (click the track first): Ctrl+Z undo, Ctrl+Y redo, Delete, Ctrl+D duplicate, "
                + "Esc cancel / deselect."));
        p.add(UI.fix(edit));

        JPanel file = UI.group("Track file");
        JButton save = UI.button("Save track...", "save", "Save the shapes, arena, start position and patches (.lftrack)");
        JButton open = UI.button("Open track...", "open", "Open a .lftrack file");
        JButton png = UI.button("Export PNG...", "image", "Save the track as the sensors see it");
        save.addActionListener(e -> saveTrack());
        open.addActionListener(e -> openTrack());
        png.addActionListener(e -> exportPng());
        file.add(UI.fix(buttonRow(save, open)));
        file.add(UI.fix(buttonRow(png)));
        file.add(UI.hint("A track file keeps the shapes, the arena size, the robot start position, the faded / slippery "
                + "patches and a link to the background picture. "
                + "The PNG is the track as the sensors see it."));
        p.add(UI.fix(file));

        p.add(UI.resetRow("Reset builder tab", g));
        return p;
    }

    private static JPanel buttonRow(JButton... buttons) {
        JPanel r = new JPanel(new GridLayout(1, buttons.length, 6, 0));
        r.setBorder(new EmptyBorder(3, 4, 3, 4));
        for (JButton b : buttons) {
            r.add(b);
        }
        return r;
    }

    /** A style control changed: remember it for new shapes and apply it to the selected shape. */
    private void style(Runnable setter) {
        if (styleSync) {
            return;
        }
        setter.run();
        trackPanel.editor.applyToSelection();
    }

    /** Mirror the builder state (selection, style, tool, undo availability) in the controls. */
    private void syncBuilder() {
        if (toolButtons == null || undoBtn == null) {
            return;
        }
        TrackEditor ed = trackPanel.editor;
        if (ed.styleVersion != lastStyleVersion) {
            lastStyleVersion = ed.styleVersion;
            styleSync = true;
            widthSp.set(ed.width);
            dashSp.set(ed.dash);
            gapSp.set(ed.gap);
            radiusSp.set(ed.radius);
            closedCk.setQuiet(ed.closed);
            filledCk.setQuiet(ed.filled);
            eraseCk.setQuiet(ed.erase);
            styleSync = false;
        }
        TrackShape sel = ed.selected;
        String text = sel == null ? "Nothing selected"
                : "Selected: " + sel.type.name().toLowerCase() + (sel.editable() ? "" : " (move or delete only)");
        if (!text.equals(lastSelText)) {
            lastSelText = text;
            selLabel.setText(text);
        }
        lastSelVersion = ed.selVersion;
        undoBtn.setEnabled(ed.canUndo());
        redoBtn.setEnabled(ed.canRedo());
        deleteBtn.setEnabled(sel != null);
        dupBtn.setEnabled(sel != null);
        if (!toolButtons[ed.tool].isSelected()) {
            toolButtons[ed.tool].setSelected(true);
        }
    }

    /** Show the picture's size and position in the boxes without changing it. */
    private void syncBg() {
        if (bgWSp == null) {
            return;
        }
        bgSync = true;
        bgWSp.set(m.bgW);
        bgXSp.set(m.bgX);
        bgYSp.set(m.bgY);
        bgSync = false;
    }

    private void loadBackground() {
        File f = chooseFile(false, "Load background picture", "",
                new FileNameExtensionFilter("Pictures (png, jpg, gif, bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (f == null) {
            return;
        }
        try {
            m.loadBackground(f);
            syncBg();
            trackPanel.setViewMode(0);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not load the picture:\n" + ex.getMessage(), "Load picture",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Two clicks on the picture and a real distance: rescales the picture about the first point. */
    private void measureBackground() {
        if (m.bgImage == null) {
            JOptionPane.showMessageDialog(this, "Load a picture first.", "Set scale", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JOptionPane.showMessageDialog(this, "Click two points on the picture that are a known distance apart\n"
                + "(for example the two ends of a ruler or a straight section).\nRight-click cancels.", "Set scale",
                JOptionPane.INFORMATION_MESSAGE);
        trackPanel.startScale(pts -> {
            String in = JOptionPane.showInputDialog(this, "Real distance between the two points (mm):", "Set scale",
                    JOptionPane.QUESTION_MESSAGE);
            if (in == null) {
                return;
            }
            double real;
            try {
                real = Double.parseDouble(in.trim().replace(',', '.'));
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(this, "That is not a number.", "Set scale", JOptionPane.ERROR_MESSAGE);
                return;
            }
            if (real <= 0) {
                return;
            }
            double f = real / Math.hypot(pts[2] - pts[0], pts[3] - pts[1]);
            m.bgW *= f;
            m.bgX = pts[0] + (m.bgX - pts[0]) * f;
            m.bgY = pts[1] + (m.bgY - pts[1]) * f;
            syncBg();
        });
    }

    private File chooseFile(boolean save, String title, String suggestion, FileNameExtensionFilter filter) {
        if (lastDir == null) {
            String d = UserPresets.setting("lastDir", "");
            lastDir = d.isEmpty() || !new File(d).isDirectory() ? null : new File(d);
        }
        JFileChooser fc = new JFileChooser(lastDir);
        fc.setDialogTitle(title);
        fc.setFileFilter(filter);
        if (save) {
            fc.setSelectedFile(new File(lastDir, suggestion));
        }
        int r = save ? fc.showSaveDialog(this) : fc.showOpenDialog(this);
        if (r != JFileChooser.APPROVE_OPTION) {
            return null;
        }
        File f = fc.getSelectedFile();
        lastDir = f.getParentFile();
        if (lastDir != null) {
            UserPresets.setSetting("lastDir", lastDir.getPath());
        }
        String ext = filter.getExtensions()[0];
        if (save && !f.getName().toLowerCase().endsWith("." + ext)) {
            f = new File(f.getPath() + "." + ext);
        }
        if (save && f.exists() && JOptionPane.showConfirmDialog(this, f.getName() + " already exists. Replace it?",
                title, JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return null;
        }
        return f;
    }

    void saveTrack() {
        File f = chooseFile(true, "Save track", "my-track.lftrack",
                new FileNameExtensionFilter("LineFollowerSim track (*.lftrack)", "lftrack"));
        if (f == null) {
            return;
        }
        try {
            TrackFile.save(f, m);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save the track:\n" + ex.getMessage(), "Save track",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    void openTrack() {
        if (!confirmDiscard("opening a track")) {
            return;
        }
        File f = chooseFile(false, "Open track", "",
                new FileNameExtensionFilter("LineFollowerSim track (*.lftrack)", "lftrack"));
        if (f == null) {
            return;
        }
        try {
            TrackFile.load(f, m);
            arenaBulk = true;                                // show the file's arena size without rebuilding the track
            arenaWSp.set(m.arenaW);
            arenaHSp.set(m.arenaH);
            arenaBulk = false;
            arenaW = m.arenaW;
            arenaH = m.arenaH;
            syncBg();
            trackPanel.setViewMode(0);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not open the track:\n" + ex.getMessage(), "Open track",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /** PNG with its real size stored (pixels per metre), so image programs print it at 1:1. */
    private static void writePngWithScale(java.awt.image.BufferedImage img, File f, double pxPerMetre) throws IOException {
        javax.imageio.ImageWriter w = ImageIO.getImageWritersByFormatName("png").next();
        javax.imageio.ImageWriteParam p = w.getDefaultWriteParam();
        javax.imageio.metadata.IIOMetadata meta = w.getDefaultImageMetadata(new javax.imageio.ImageTypeSpecifier(img), p);
        javax.imageio.metadata.IIOMetadataNode phys = new javax.imageio.metadata.IIOMetadataNode("pHYs");
        String ppm = Integer.toString((int) Math.round(pxPerMetre));
        phys.setAttribute("pixelsPerUnitXAxis", ppm);
        phys.setAttribute("pixelsPerUnitYAxis", ppm);
        phys.setAttribute("unitSpecifier", "meter");
        javax.imageio.metadata.IIOMetadataNode root = new javax.imageio.metadata.IIOMetadataNode("javax_imageio_png_1.0");
        root.appendChild(phys);
        meta.mergeTree("javax_imageio_png_1.0", root);
        if (f.exists() && !f.delete()) {
            throw new IOException("Cannot replace " + f.getName());
        }
        try (javax.imageio.stream.ImageOutputStream out = ImageIO.createImageOutputStream(f)) {
            w.setOutput(out);
            w.write(new javax.imageio.IIOImage(img, null, meta));
        } finally {
            w.dispose();
        }
    }

    void exportTelemetry() {
        if (m.telemetry().isEmpty()) {
            trackPanel.toast("Run the robot first: telemetry is recorded while it runs");
            return;
        }
        File f = chooseFile(true, "Export telemetry", "run.csv", new FileNameExtensionFilter("CSV file (*.csv)", "csv"));
        if (f == null) {
            return;
        }
        try {
            int n = Telemetry.writeCsv(m, f);
            trackPanel.toast(String.format("Saved %d rows (%.1f s) to %s", n, n * 0.01, f.getName()));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save: " + ex.getMessage(), "Export telemetry", JOptionPane.ERROR_MESSAGE);
        }
    }

    void importLog() {
        File f = chooseFile(false, "Import a run log", null, new FileNameExtensionFilter("CSV / text log (*.csv, *.txt, *.log)",
                "csv", "txt", "log"));
        if (f == null) {
            return;
        }
        try {
            Model.Run r = Telemetry.readLog(m, f);
            runsPanel.refresh();
            trackPanel.toast(String.format("Imported %s: %.1f s, avg error %.1f mm%s", r.name, r.time, r.avgErr,
                    r.x.length == 0 ? " (no position: error curve only)" : ""));
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "<html>Could not read " + f.getName() + ": " + ex.getMessage()
                    + "<br><br>Expected a CSV with a header line, a time column (t_s or t_ms) and error_mm and / or "
                    + "x_mm, y_mm.</html>", "Import a run log", JOptionPane.ERROR_MESSAGE);
        }
    }

    void exportPng() {
        File f = chooseFile(true, "Export track image", "my-track.png",
                new FileNameExtensionFilter("PNG image (*.png)", "png"));
        if (f == null) {
            return;
        }
        try {
            writePngWithScale(m.track, f, 1000.0 / m.res);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not export the image:\n" + ex.getMessage(), "Export track image",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    // ------------------------------------------------------------------ canvas, workspace, menus
    /** New canvas (clean start) or resize, with size presets and a custom size. */
    void canvasDialog(boolean fresh) {
        HardwarePresets.P[] ps = HardwarePresets.ARENAS;
        String[] names = new String[ps.length + 1];
        names[0] = "Custom";
        int sel = 0;
        for (int i = 0; i < ps.length; i++) {
            names[i + 1] = ps[i].name;
            if (ps[i].v[0] == m.arenaW && ps[i].v[1] == m.arenaH) {
                sel = i + 1;
            }
        }
        JComboBox<String> size = new JComboBox<>(names);
        size.setSelectedIndex(sel);
        JSpinner w = new JSpinner(new SpinnerNumberModel(m.arenaW, 200, 6000, 50));
        JSpinner h = new JSpinner(new SpinnerNumberModel(m.arenaH, 200, 6000, 50));
        boolean[] quiet = {false};
        size.addActionListener(e -> {
            int i = size.getSelectedIndex();
            if (i > 0) {
                quiet[0] = true;
                w.setValue(ps[i - 1].v[0]);
                h.setValue(ps[i - 1].v[1]);
                quiet[0] = false;
            }
        });
        javax.swing.event.ChangeListener custom = e -> {
            if (!quiet[0]) {
                size.setSelectedIndex(0);
            }
        };
        w.addChangeListener(custom);
        h.addChangeListener(custom);
        JCheckBox keepBg = new JCheckBox("Keep the background picture", m.bgImage != null);
        keepBg.setEnabled(m.bgImage != null);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        Object[][] rows = {{"Size", size}, {"Width (mm)", w}, {"Height (mm)", h}};
        for (int r = 0; r < rows.length; r++) {
            c.gridy = r;
            c.gridx = 0;
            c.weightx = 0;
            form.add(new JLabel((String) rows[r][0]), c);
            c.gridx = 1;
            c.weightx = 1;
            form.add((Component) rows[r][1], c);
        }
        if (fresh) {
            c.gridy = rows.length;
            c.gridx = 0;
            c.gridwidth = 2;
            form.add(keepBg, c);
            c.gridy++;
            form.add(UI.hint("Removes every shape, patch and the start position. Settings stay as they are."), c);
        }
        if (fresh && !confirmDiscard("starting a new canvas")) {
            return;
        }
        int ans = JOptionPane.showConfirmDialog(this, form, fresh ? "New canvas" : "Resize canvas",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (ans != JOptionPane.OK_OPTION) {
            return;
        }
        double nw = ((Number) w.getValue()).doubleValue(), nh = ((Number) h.getValue()).doubleValue();
        if (!fresh) {
            applyArena(nw, nh);
            trackPanel.setViewMode(0);
            return;
        }
        boolean keep = keepBg.isSelected() && m.bgImage != null;
        m.setTrack(nw, nh, new java.util.ArrayList<>(), new double[]{Math.min(100, nw / 8), nh / 2, 0});
        if (!keep) {
            m.clearBackground();
        }
        presetCombo.setQuiet(8);                           // "Blank (draw your own)"
        setRunning(false);                                 // nothing to follow yet: wait until the track is drawn
        trackPanel.setTool(TrackEditor.SELECT);
        trackPanel.toast("New canvas " + Math.round(nw) + " \u00D7 " + Math.round(nh) + " mm - the robot is paused (F5 runs)");
        showArena();
        syncBg();
        trackPanel.setViewMode(0);
        workspaceFile = null;
        markSaved();
    }

    /** Mirror the model's arena size in the Track tab fields without rebuilding anything. */
    private void showArena() {
        arenaBulk = true;
        arenaWSp.set(m.arenaW);
        arenaHSp.set(m.arenaH);
        arenaBulk = false;
        arenaW = m.arenaW;
        arenaH = m.arenaH;
    }

    private void markSaved() {
        savedChanges = trackPanel.editor.changeCount;
        savedSig = m.settingsSignature();
    }

    /** Unsaved changes: the track was edited, or a setting differs from the last save / open. */
    boolean isDirty() {
        return trackPanel.editor.changeCount != savedChanges || !m.settingsSignature().equals(savedSig);
    }

    /** Called once the window is built: the start-up state counts as saved. */
    void startClean() {
        markSaved();
    }

    void setTitleSink(java.util.function.Consumer<String> sink) {
        titleSink = sink;
    }

    private String lastTitle = "";
    private int titleTick;

    private void updateTitle() {
        String t = TITLE + " - " + (workspaceFile == null ? "untitled" : workspaceFile.getName()) + (isDirty() ? " *" : "");
        if (!t.equals(lastTitle)) {
            lastTitle = t;
            titleSink.accept(t);
        }
    }

    /** Ask before work would be lost. True = go ahead. */
    boolean confirmDiscard(String what) {
        if (!isDirty()) {
            return true;
        }
        Object[] opts = {"Save", "Don't save", "Cancel"};
        int a = JOptionPane.showOptionDialog(this, "The workspace has unsaved changes.\nSave them before " + what + "?",
                "Unsaved changes", JOptionPane.YES_NO_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
        if (a == 0) {
            return saveWorkspace(false);
        }
        return a == 1;
    }

    /** Save the workspace (as = always ask for a file name). True if it was saved. */
    boolean saveWorkspace(boolean as) {
        File f = workspaceFile;
        if (as || f == null) {
            f = chooseFile(true, "Save workspace", workspaceFile != null ? workspaceFile.getName() : "my-robot." + Workspace.EXT,
                    new FileNameExtensionFilter("LineFollowerSim workspace (*.lfsim)", Workspace.EXT));
            if (f == null) {
                return false;
            }
        }
        try {
            Workspace.save(f, master.saveables(), m, draftLines.get());
            workspaceFile = f;
            markSaved();
            addRecent(f);
            trackPanel.toast("Saved " + f.getName());
            return true;
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not save the workspace:\n" + ex.getMessage(), "Save workspace",
                    JOptionPane.ERROR_MESSAGE);
            return false;
        }
    }

    /** Open a workspace (null = ask for the file). */
    void openWorkspace(File f) {
        if (!confirmDiscard("opening another workspace")) {
            return;
        }
        if (f == null) {
            f = chooseFile(false, "Open workspace", "",
                    new FileNameExtensionFilter("LineFollowerSim workspace (*.lfsim)", Workspace.EXT));
            if (f == null) {
                return;
            }
        }
        Workspace.Content c;
        try {
            c = Workspace.read(f);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not open the workspace:\n" + ex.getMessage(), "Open workspace",
                    JOptionPane.ERROR_MESSAGE);
            removeRecent(f);
            return;
        }
        int skipped = 0;
        for (java.util.Map.Entry<String, Saveable> e : master.saveables().entrySet()) {     // in creation order
            String v = c.settings.get(e.getKey());
            if (v != null) {
                try {
                    e.getValue().restore(v);
                } catch (RuntimeException ex) {
                    skipped++;
                }
            }
        }
        m.customOutline.clear();
        if (c.chassis != null) {
            m.customOutline.addAll(c.chassis);
        }
        m.customLayout = m.customLeds = m.useParts = false;          // older files have no layout: the array shape as set
        m.cuLedCount = 0;
        m.parts.clear();
        try {
            m.layoutFrom(c.layout);
        } catch (RuntimeException ex) {
            skipped++;
        }
        robotLayoutChanged.run();
        if (!c.draft.isEmpty()) {
            try {
                draftRestore.accept(c.draft);
            } catch (RuntimeException ex) {
                skipped++;
            }
        }
        if (c.gain != null) {
            System.arraycopy(c.gain, 0, m.mmGain, 0, m.mmGain.length);
            System.arraycopy(c.off, 0, m.mmOff, 0, m.mmOff.length);
        }
        try {
            TrackFile.readTrack(c.track, m);
        } catch (IOException ex) {
            skipped++;                                     // cannot happen: the file was checked by Workspace.read
        }
        m.runs.clear();                                    // the kept runs (after the track: loading a track drops them)
        m.runs.addAll(Workspace.readRuns(c.runs));
        m.runsLoaded();
        if (c.cal != null) {                               // the calibration exactly as it was saved
            m.calWhite = c.cal[0];
            m.calBlack = c.cal[1];
            int n = Model.MAXR * Model.MAXS;
            for (int r = 0; r < Model.MAXR; r++) {
                for (int i = 0; i < Model.MAXS; i++) {
                    m.calMin[r][i] = c.cal[2 + r * Model.MAXS + i];
                    m.calMax[r][i] = c.cal[2 + n + r * Model.MAXS + i];
                }
            }
        } else {                                           // older file: calibrate afresh, keep a hand-set threshold
            double thr = m.threshold;
            m.calibrate();
            thresholdParam.set(thr);
            if (m.perSensorCal) {
                m.calibrateSweep();
            }
        }
        m.reset();
        showArena();
        syncBg();
        trackPanel.setViewMode(0);
        workspaceFile = f;
        markSaved();
        addRecent(f);
        trackPanel.toast("Opened " + f.getName());
        if (skipped > 0) {
            JOptionPane.showMessageDialog(this, skipped + " value(s) in the file could not be used and were left as they were.",
                    "Open workspace", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ---- recent workspaces (stored with the presets)
    private static List<String> recent() {
        List<String> r = new java.util.ArrayList<>();
        for (String s : UserPresets.setting("recent", "").split("\\|")) {
            if (!s.isEmpty()) {
                r.add(s);
            }
        }
        return r;
    }

    private static void storeRecent(List<String> r) {
        UserPresets.setSetting("recent", String.join("|", r.subList(0, Math.min(8, r.size()))));
    }

    private static void addRecent(File f) {
        List<String> r = recent();
        r.remove(f.getAbsolutePath());
        r.add(0, f.getAbsolutePath());
        storeRecent(r);
    }

    private static void removeRecent(File f) {
        List<String> r = recent();
        if (r.remove(f.getAbsolutePath())) {
            storeRecent(r);
        }
    }

    private static JMenuItem item(String text, String icon, KeyStroke key, Runnable action) {
        JMenuItem it = new JMenuItem(text, icon == null ? null : Icons.get(icon));
        if (key != null) {
            it.setAccelerator(key);
        }
        it.addActionListener(e -> action.run());
        return it;
    }

    private static KeyStroke ks(String s) {
        return KeyStroke.getKeyStroke(s);
    }

    /** The window's menu bar: every command in one place, with its shortcut. */
    JMenuBar buildMenuBar(Runnable exit) {
        TrackEditor ed = trackPanel.editor;
        JMenuBar bar = new JMenuBar();

        JMenu file = new JMenu("File");
        file.setMnemonic('F');
        file.add(item("New canvas...", "new", ks("ctrl N"), () -> canvasDialog(true)));
        file.add(item("Open workspace...", "open", ks("ctrl O"), () -> openWorkspace(null)));
        JMenu rec = new JMenu("Open recent");
        file.add(rec);
        file.add(item("Save workspace", "save", ks("ctrl S"), () -> saveWorkspace(false)));
        file.add(item("Save workspace as...", null, ks("ctrl shift S"), () -> saveWorkspace(true)));
        file.addSeparator();
        file.add(item("Open track only...", "open", null, this::openTrack));
        file.add(item("Save track only...", "save", null, this::saveTrack));
        file.add(item("Export track as PNG...", "image", null, this::exportPng));
        file.add(item("Export Arduino sketch...", "tab_mcu", ks("ctrl E"), this::exportArduino));
        file.add(item("Export telemetry (CSV)...", "save", null, this::exportTelemetry));
        file.add(item("Import a run log (CSV)...", "open", null, this::importLog));
        file.addSeparator();
        file.add(item("Export robot drawing (DXF / SVG)...", "tool_robot", null, this::exportRobotDrawing));
        file.add(item("Export track drawing (DXF / SVG)...", "tab_track", null, this::exportTrackDrawing));
        file.add(item("Print track at real size...", "image", ks("ctrl P"), this::printTrack));
        file.add(item("Parts list...", "saved", null, this::showPartsList));
        file.addSeparator();
        file.add(item("Exit", null, ks("alt F4"), exit));
        file.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                rec.removeAll();
                for (String path : recent()) {
                    File f = new File(path);
                    JMenuItem it = new JMenuItem(f.getName());
                    it.setToolTipText(path);
                    it.addActionListener(a -> openWorkspace(f));
                    rec.add(it);
                }
                if (rec.getItemCount() == 0) {
                    JMenuItem none = new JMenuItem("(none yet)");
                    none.setEnabled(false);
                    rec.add(none);
                } else {
                    rec.addSeparator();
                    rec.add(item("Clear list", null, null, () -> storeRecent(new java.util.ArrayList<>())));
                }
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
            }
        });
        bar.add(file);

        JMenu edit = new JMenu("Edit");
        edit.setMnemonic('E');
        JMenuItem undo = item("Undo", "undo", ks("ctrl Z"), () -> {
            if (editorShown.getAsBoolean()) {
                editorUndo.run();
            } else {
                ed.undo();
            }
        });
        JMenuItem redo = item("Redo", "redo", ks("ctrl Y"), () -> {
            if (editorShown.getAsBoolean()) {
                editorRedo.run();
            } else {
                ed.redo();
            }
        });
        JMenuItem del = item("Delete shape", "delete", ks("DELETE"), () -> {
            if (editorShown.getAsBoolean()) {
                editorDelete.run();
            } else {
                ed.deleteSelected();
            }
        });
        JMenuItem dup = item("Duplicate shape", "duplicate", ks("ctrl D"), ed::duplicateSelected);
        edit.add(undo);
        edit.add(redo);
        edit.addSeparator();
        edit.add(del);
        edit.add(dup);
        edit.add(item("Clear all shapes", "clear", null, ed::clearAll));
        edit.addSeparator();
        edit.add(item("Resize canvas...", "fit", null, () -> canvasDialog(false)));
        edit.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                undo.setEnabled(ed.canUndo());
                redo.setEnabled(ed.canRedo());
                del.setEnabled(ed.selected != null);
                dup.setEnabled(ed.selected != null);
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
                undo.setEnabled(true);                     // keep the shortcuts working while the menu is closed
                redo.setEnabled(true);
                del.setEnabled(true);
                dup.setEnabled(true);
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
                menuDeselected(e);
            }
        });
        bar.add(edit);

        JMenu view = new JMenu("View");
        view.setMnemonic('V');
        view.add(item("Fit whole arena", "fit", ks("ctrl 0"), () -> trackPanel.setViewMode(0)));
        view.add(item("Follow robot", "follow", null, () -> trackPanel.setViewMode(1)));
        view.add(item("Zoom in", "zoom_in", ks("ctrl EQUALS"), () -> trackPanel.zoomBy(1.25, null)));
        view.add(item("Zoom out", "zoom_out", ks("ctrl MINUS"), () -> trackPanel.zoomBy(0.8, null)));
        view.addSeparator();
        JCheckBoxMenuItem grid = new JCheckBoxMenuItem("Show grid");
        JCheckBoxMenuItem trail = new JCheckBoxMenuItem("Show trail");
        JCheckBoxMenuItem dark = new JCheckBoxMenuItem("Dark theme");
        grid.addActionListener(e -> gridCk.set(grid.isSelected()));
        trail.addActionListener(e -> trailCk.set(trail.isSelected()));
        dark.addActionListener(e -> themeToggle.run());
        view.add(grid);
        view.add(trail);
        view.addSeparator();
        view.add(dark);
        view.addMenuListener(new javax.swing.event.MenuListener() {
            @Override
            public void menuSelected(javax.swing.event.MenuEvent e) {
                grid.setSelected(gridCk.isSelected());
                trail.setSelected(trailCk.isSelected());
                dark.setSelected(Theme.isDark());
            }

            @Override
            public void menuDeselected(javax.swing.event.MenuEvent e) {
            }

            @Override
            public void menuCanceled(javax.swing.event.MenuEvent e) {
            }
        });
        bar.add(view);

        JMenu sim = new JMenu("Simulation");
        sim.setMnemonic('S');
        sim.add(item("Run / pause", "play", ks("F5"), this::toggleRunning));
        sim.add(item("LEDs on / off", "bulb", ks("F7"), this::toggleLeds));
        sim.add(item("LEDs brighter", null, ks("F8"), () -> ledPowerP.set(Math.min(100, ledPowerP.get() + 10))));
        sim.add(item("LEDs dimmer", null, ks("shift F8"), () -> ledPowerP.set(Math.max(0, ledPowerP.get() - 10))));
        sim.add(item("Restart robot", "reset_robot", ks("F6"), this::resetRobot));
        sim.addSeparator();
        sim.add(item("Calibrate here", "calibrate", null, () -> {
            m.calibrate();
            thresholdParam.set(m.threshold);
        }));
        sim.add(item("Clear disturbance patches", "clear", null, m::clearPatches));
        sim.addSeparator();
        sim.add(item("Keep this run", "saved", ks("ctrl K"), () -> runsPanel.keepRun()));
        sim.add(item("Clear trail", "clear", ks("ctrl L"), m::clearTrail));
        sim.addSeparator();
        sim.add(item("Design a robot for this track...", "suggest", ks("ctrl G"), this::openDesigner));
        sim.add(item("Tune PID for this track...", "suggest", ks("ctrl T"), this::openTunePid));
        sim.addSeparator();
        sim.add(item("Reset all settings...", "reset_all", null, this::resetEverything));
        bar.add(sim);

        JMenu help = new JMenu("Help");
        help.setMnemonic('H');
        help.add(item("Keyboard shortcuts", null, ks("F1"), this::showShortcuts));
        help.add(item("About", null, null, this::showAbout));
        bar.add(help);
        return bar;
    }

    /** Open the robot designer wizard. */
    /** The Hardware tab's array settings were changed: the sensors go back to the regular array. */
    void dropCustomLayout() {
        if (m.customLayout) {
            m.customLayout = false;
            arrayCombo.setQuiet(m.arrayShape);
            trackPanel.toast("Sensors back on the regular array (count / pitch changed) - Robot editor to place them freely");
        }
    }

    // ------------------------------------------------------------------ LEDs while running, controls that do not apply
    private CheckParam ledCk;
    private Param ledPowerP;
    private JToggleButton ledBtn;

    void toggleLeds() {
        ledCk.set(!m.whiteLeds);
        trackPanel.toast("LEDs " + (m.whiteLeds ? "on" : "off") + (m.ledRecal ? " (recalibrated)" : " - not recalibrated"));
    }

    private void ledRecal() {
        if (m.ledRecal) {
            recalibrate();
        }
    }

    private final List<Object[]> deps = new java.util.ArrayList<>();

    /** Grey out a control while it has no effect (checked a few times a second). */
    JComponent dep(JComponent c, java.util.function.BooleanSupplier when) {
        deps.add(new Object[]{c, when, null});
        return c;
    }

    static void setEnabledDeep(Component c, boolean on) {
        c.setEnabled(on);
        if (c instanceof Container) {
            for (Component k : ((Container) c).getComponents()) {
                setEnabledDeep(k, on);
            }
        }
    }

    /** Apply the dependencies (only where the state changed). */
    void updateDeps() {
        for (Object[] d : deps) {
            boolean on;
            try {
                on = ((java.util.function.BooleanSupplier) d[1]).getAsBoolean();
            } catch (RuntimeException ex) {
                on = true;
            }
            if (!Boolean.valueOf(on).equals(d[2])) {
                d[2] = on;
                setEnabledDeep((Component) d[0], on);
            }
        }
        if (ledBtn != null && ledBtn.isSelected() != m.whiteLeds) {
            ledBtn.setSelected(m.whiteLeds);
        }
    }

    void openTunePid() {
        boolean was = m.running;
        setRunning(false);
        new TunePidDialog(SwingUtilities.getWindowAncestor(this), m, this::applyDesign).setVisible(true);
        setRunning(was);
    }

    void openDesigner() {
        boolean was = m.running;
        setRunning(false);                                  // the wizard is modal; the robot waits
        new DesignWizard(SwingUtilities.getWindowAncestor(this), m, this::applyDesign).setVisible(true);
        setRunning(was);
    }

    /** Put a designed robot into the controls (in the order the controls were made), then recalibrate. */
    void applyDesign(java.util.Map<String, String> values) {
        int used = 0;
        for (java.util.Map.Entry<String, Saveable> e : master.saveables().entrySet()) {
            String v = values.get(e.getKey());
            if (v != null) {
                try {
                    e.getValue().restore(v);
                    used++;
                } catch (RuntimeException ex) {
                    // a value the control cannot take is skipped
                }
            }
        }
        m.applyHardware();
        m.calibrate();
        thresholdParam.set(m.threshold);
        if (m.perSensorCal) {
            m.calibrateSweep();
        }
        m.reset();
        trackPanel.toast("Design applied (" + used + " settings) - save the workspace to keep it");
    }

    private final Exports.Mounts mounts = new Exports.Mounts();

    /** Which files to write: 0 DXF, 1 SVG, 2 both. Returns the chosen base file (without extension) or null. */
    private File chooseDrawing(String title, String suggestion, int format) {
        String ext = format == 1 ? "svg" : "dxf";
        File f = chooseFile(true, title, suggestion + "." + ext, new FileNameExtensionFilter(
                format == 1 ? "SVG drawing (*.svg)" : "DXF drawing (*.dxf)", ext));
        if (f == null) {
            return null;
        }
        String p = f.getPath();
        return new File(p.substring(0, p.length() - ext.length() - 1));
    }

    private void writeDrawing(Drawing d, File base, int format, String what) {
        try {
            List<String> done = new java.util.ArrayList<>();
            if (format != 1) {
                File f = new File(base.getPath() + ".dxf");
                d.writeDxf(f);
                done.add(f.getName());
            }
            if (format != 0) {
                File f = new File(base.getPath() + ".svg");
                d.writeSvg(f);
                done.add(f.getName());
            }
            trackPanel.toast("Written: " + String.join(", ", done));
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not write the " + what + ":\n" + ex.getMessage(), "Export",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 1:1 top view of the robot with cut, hole, mark, reference and text layers. */
    void exportRobotDrawing() {
        Exports.Mounts mt = mounts;
        JComboBox<String> type = new JComboBox<>(Exports.Mounts.TYPES);
        type.setSelectedIndex(mt.type);
        JSpinner hd = new JSpinner(new SpinnerNumberModel(mt.holeDia, 0.5, 20, 0.1));
        JSpinner hs = new JSpinner(new SpinnerNumberModel(mt.holeSpacing, 0, 100, 0.5));
        JSpinner hi = new JSpinner(new SpinnerNumberModel(mt.holeInset, -50, 150, 0.5));
        JSpinner ho = new JSpinner(new SpinnerNumberModel(mt.holeOffset, -100, 100, 0.5));
        JSpinner cs = new JSpinner(new SpinnerNumberModel(mt.casterSpacing, 0, 100, 0.5));
        JSpinner cd = new JSpinner(new SpinnerNumberModel(mt.casterDia, 0.5, 20, 0.1));
        JSpinner bd = new JSpinner(new SpinnerNumberModel(mt.boardHoleDia, 0.5, 20, 0.1));
        JSpinner bi = new JSpinner(new SpinnerNumberModel(mt.boardHoleInset, 0, 30, 0.5));
        JCheckBox servo = new JCheckBox("Car: cut an SG90-size servo opening at the front axle", mt.servoCutout);
        JComboBox<String> fmt = new JComboBox<>(new String[]{"DXF", "SVG", "DXF and SVG"});
        type.addActionListener(e -> {
            mt.preset(type.getSelectedIndex());
            hd.setValue(mt.holeDia);
            hs.setValue(mt.holeSpacing);
            hi.setValue(mt.holeInset);
            ho.setValue(mt.holeOffset);
        });
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 4));
        Object[][] rows = {{"Motor mount", type}, {"Motor hole diameter (mm)", hd}, {"Hole spacing along the robot (mm)", hs},
            {"Holes in from the wheel centre (mm)", hi}, {"Holes ahead of the axle (mm)", ho}, {"Caster hole spacing (mm)", cs},
            {"Caster hole diameter (mm)", cd}, {"Sensor board hole diameter (mm)", bd}, {"Board hole in from the edge (mm)", bi},
            {"Format", fmt}};
        for (Object[] r : rows) {
            form.add(new JLabel((String) r[0]));
            form.add((Component) r[1]);
        }
        JPanel all = new JPanel(new BorderLayout(0, 6));
        all.add(form, BorderLayout.CENTER);
        JPanel south = new JPanel(new BorderLayout());
        south.add(servo, BorderLayout.NORTH);
        south.add(UI.hint("Top view, front up, 1:1 in mm. Layers: <b>CUT</b> (chassis plate, sensor board, servo opening), "
                + "<b>HOLES</b>, <b>MARK</b> (sensor and LED centres, axle line, centre of mass, front arrow - engrave), "
                + "<b>REF</b> (wheels, do not cut), <b>TEXT</b>. The motor and caster hole patterns are typical values: "
                + "measure your brackets and type them here before cutting."), BorderLayout.CENTER);
        all.add(south, BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, all, "Export robot drawing", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        mt.type = type.getSelectedIndex();
        mt.holeDia = ((Number) hd.getValue()).doubleValue();
        mt.holeSpacing = ((Number) hs.getValue()).doubleValue();
        mt.holeInset = ((Number) hi.getValue()).doubleValue();
        mt.holeOffset = ((Number) ho.getValue()).doubleValue();
        mt.casterSpacing = ((Number) cs.getValue()).doubleValue();
        mt.casterDia = ((Number) cd.getValue()).doubleValue();
        mt.boardHoleDia = ((Number) bd.getValue()).doubleValue();
        mt.boardHoleInset = ((Number) bi.getValue()).doubleValue();
        mt.servoCutout = servo.isSelected();
        File base = chooseDrawing("Export robot drawing", "robot", fmt.getSelectedIndex());
        if (base != null) {
            writeDrawing(Exports.robot(m, mt), base, fmt.getSelectedIndex(), "robot drawing");
        }
    }

    /** 1:1 track: the line's outline, its centre line, the arena, the start and the patches. */
    void exportTrackDrawing() {
        JComboBox<String> fmt = new JComboBox<>(new String[]{"DXF", "SVG", "DXF and SVG"});
        JSpinner tol = new JSpinner(new SpinnerNumberModel(0.1, 0.01, 2, 0.05));
        JPanel form = new JPanel(new GridLayout(0, 2, 6, 4));
        form.add(new JLabel("Format"));
        form.add(fmt);
        form.add(new JLabel("Curve accuracy (mm)"));
        form.add(tol);
        JPanel all = new JPanel(new BorderLayout(0, 6));
        all.add(form, BorderLayout.CENTER);
        all.add(UI.hint("1:1 in mm, y up. Layers: <b>LINE_OUTLINE</b> (the edges of the tape or paint: cut vinyl, a stencil, "
                + "or engrave the track), <b>CENTRELINE</b> (the path: plotter, CNC pen), <b>ARENA</b>, <b>START</b>, "
                + "<b>PATCHES</b>, <b>TEXT</b>. Curves become short straight pieces no further than the accuracy from the "
                + "true curve."), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, all, "Export track drawing", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        File base = chooseDrawing("Export track drawing", "track", fmt.getSelectedIndex());
        if (base != null) {
            writeDrawing(Exports.track(m, ((Number) tol.getValue()).doubleValue()), base, fmt.getSelectedIndex(), "track drawing");
        }
    }

    /** Print the track at real size on tiled pages (choose "Microsoft Print to PDF" to get a PDF). */
    void printTrack() {
        java.awt.print.PrinterJob job = java.awt.print.PrinterJob.getPrinterJob();
        java.awt.print.PageFormat pf = job.pageDialog(job.defaultPage());
        if (pf == null) {
            return;
        }
        Exports.TrackPrint tp = new Exports.TrackPrint(m, pf);
        if (JOptionPane.showConfirmDialog(this, String.format("<html>The %.0f x %.0f mm arena needs <b>%d pages</b> (%d x %d), "
                + "with 10 mm overlap.<br>Print at 100 %% / \"actual size\" (no \"fit to page\"); each page has a 50 mm bar "
                + "to check the scale.<br>To get a PDF, choose a PDF printer in the next window.</html>", m.arenaW, m.arenaH,
                tp.getNumberOfPages(), tp.cols, tp.rows), "Print track at real size", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.INFORMATION_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        job.setPageable(tp);
        job.setJobName("LineFollowerSim track");
        if (job.printDialog()) {
            try {
                job.print();
                trackPanel.toast("Sent " + tp.getNumberOfPages() + " pages to the printer");
            } catch (java.awt.print.PrinterException ex) {
                JOptionPane.showMessageDialog(this, "Printing failed:\n" + ex.getMessage(), "Print", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Show the parts list for the simulated robot, with Copy and Save. */
    void showPartsList() {
        String text = Exports.partsList(m);
        JTextArea ta = new JTextArea(text, 24, 90);
        ta.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ta.setEditable(false);
        Object[] opts = {"Save as text...", "Copy", "Close"};
        int a = JOptionPane.showOptionDialog(this, new JScrollPane(ta), "Parts list", JOptionPane.DEFAULT_OPTION,
                JOptionPane.PLAIN_MESSAGE, null, opts, opts[2]);
        if (a == 1) {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new java.awt.datatransfer.StringSelection(text), null);
            trackPanel.toast("Parts list copied");
        } else if (a == 0) {
            File f = chooseFile(true, "Save parts list", "parts-list.txt", new FileNameExtensionFilter("Text (*.txt)", "txt"));
            if (f != null) {
                try {
                    java.nio.file.Files.write(f.toPath(), text.replace("\n", System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    trackPanel.toast("Saved " + f.getName());
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "Could not save:\n" + ex.getMessage(), "Parts list", JOptionPane.ERROR_MESSAGE);
                }
            }
        }
    }

    /** Set by the Robot editor: after a workspace was opened, take the robot (used when the file has no draft). */
    Runnable robotLayoutChanged = () -> { };
    /** The Robot editor's draft for the workspace file, and the way back in. */
    java.util.function.Supplier<List<String>> draftLines = () -> null;
    java.util.function.Consumer<List<String>> draftRestore = l -> { };
    /** Shows the Robot editor tab. */
    Runnable showRobotEditor = () -> { };
    /** While the Robot editor is shown, Edit > Undo / Redo / Delete work there. */
    java.util.function.BooleanSupplier editorShown = () -> false;
    java.util.function.BooleanSupplier debugHold = () -> false;          // the Code tab's debugger has the program stopped
    java.util.function.BooleanSupplier debugContinue = () -> false;      // F5 while stopped: continue (true = done)
    Runnable debugAbort = () -> { };
    Runnable editorUndo = () -> { }, editorRedo = () -> { }, editorDelete = () -> { };

    /** The Robot editor applied its draft to the model: mirror the numbers into the controls. massG / wheelPct &lt; 0 = keep. */
    void robotFromEditor(double massG, double wheelPct) {
        heightSp.set(m.sensorHeight);
        wheelsCombo.select(m.wheelCount == 2 ? 0 : 1 + m.steerMode);
        actCombo.select(m.steerActuator);
        wheelBaseSp.set(m.wheelBase);
        wheelDiaSp.set(m.wheelDia);
        tireSp.set(m.tireWidth);
        axleSpSp.set(m.axleSpacing);
        steerSp.set(m.maxSteerDeg);
        axleSp.set(m.forward);
        countSp.set(m.nSensors);
        arrayCombo.setQuiet(3);
        shapeCombo.select(3);
        casterPosSp.set(m.casterPos);
        boardSp.set(m.boardMargin);
        if (massG > 0) {
            massSp.set(Math.round(massG));
        }
        if (wheelPct > 0) {
            weightSp.set(Math.round(wheelPct));
        }
        m.applyHardware();
        recalibrate();
        resweep();
        m.reset();
        trackPanel.repaint();
    }
    private final ArduinoExport.Options ardOpts = new ArduinoExport.Options();
    private boolean ardOptsShown;

    private String diffHtml() {
        java.util.List<String> d = ArduinoExport.differences(m);
        if (d.isEmpty()) {
            return " <b>The sketch runs exactly the simulator's controller.</b>";
        }
        StringBuilder sb = new StringBuilder(" <b>Not the same as the simulator:</b>");
        for (String s : d) {
            sb.append("<br>- ").append(s);
        }
        return sb.toString();
    }

    /** Write an Arduino sketch with the same controller and settings as the simulated robot. */
    void exportArduino() {
        ArduinoExport.Options o = ardOpts;
        if (ArduinoExport.sensorCount(o) != m.nSensors || ArduinoExport.rowCount(o) != m.nRows) {   // one pin per simulated sensor
            StringBuilder sb = new StringBuilder();
            String[] analog = {"A0", "A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "A9", "A10", "A11", "A12", "A13", "A14", "A15"};
            int k = 0;
            for (int r = 0; r < m.nRows; r++) {
                sb.append(r > 0 ? "; " : "");
                for (int i = 0; i < m.nSensors; i++, k++) {
                    sb.append(i > 0 ? ", " : "").append(k < analog.length ? analog[k] : String.valueOf(22 + 2 * (k - analog.length)));
                }
            }
            o.sensorPins = sb.toString();
        }
        if (m.customLayout && m.nRows == 1) {                       // pins set in the Robot editor win
            StringBuilder sb = new StringBuilder();
            boolean any = false;
            String[] cur = ArduinoExport.pinRows(o)[0];
            for (int i = 0; i < m.nSensors; i++) {
                String pn = m.cuPin[i] != null && !m.cuPin[i].trim().isEmpty() ? m.cuPin[i].trim() : i < cur.length ? cur[i] : "A" + i;
                any |= m.cuPin[i] != null && !m.cuPin[i].trim().isEmpty();
                sb.append(i > 0 ? ", " : "").append(pn);
            }
            if (any) {
                o.sensorPins = sb.toString();
            }
        }
        if (!ardOptsShown) {
            o.digitalPins = !m.analogMode;
            ardOptsShown = true;
        }
        JCheckBox digPins = new JCheckBox("Sensors are digital modules (digitalRead, the module sets the threshold)", o.digitalPins);
        JCheckBox logCk = new JCheckBox("Serial log (LOG): CSV lines for File > Import a run log", o.log);
        JTextField pins = new JTextField(o.sensorPins, 22);
        JComboBox<String> drv = new JComboBox<>(ArduinoExport.DRIVERS);
        drv.setSelectedIndex(m.driverType == 2 ? 2 : m.driverType == 3 ? 1 : o.driver);
        JTextField lp = new JTextField(o.lPwm, 4), l1 = new JTextField(o.lIn1, 4), l2 = new JTextField(o.lIn2, 4);
        JTextField rp = new JTextField(o.rPwm, 4), r1 = new JTextField(o.rIn1, 4), r2 = new JTextField(o.rIn2, 4);
        JTextField sb = new JTextField(o.stby, 4);
        JCheckBox low = new JCheckBox("Analog reading is LOWER on the black line (TCRT5000 modules; QTR: untick)", o.lineReadsLow);
        JCheckBox dHigh = new JCheckBox("Digital modules output HIGH on the black line", o.digitalLineHigh);
        JCheckBox spin = new JCheckBox("Calibrate at start-up: spin over the line for 3 s", o.spinCalibration);
        JCheckBox dead = new JCheckBox("Lift small PWM over the dead band (Motors tab dead zone)", o.deadBandComp);
        JTextField servoPin = new JTextField(o.servoPin, 4), servoCenter = new JTextField(Integer.toString(o.servoCenter), 4);
        JCheckBox servoRev = new JCheckBox("Servo turns the wrong way (reverse)", o.servoReversed);
        JPanel form = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(3, 4, 3, 4);
        c.anchor = GridBagConstraints.WEST;
        c.fill = GridBagConstraints.HORIZONTAL;
        int[] y = {0};
        java.util.function.BiConsumer<String, JComponent> row = (label, comp) -> {
            c.gridy = y[0]++;
            c.gridx = 0;
            c.gridwidth = 1;
            c.weightx = 0;
            form.add(new JLabel(label), c);
            c.gridx = 1;
            c.weightx = 1;
            form.add(comp, c);
        };
        row.accept("Sensor pins (left to right; rows: ;)", pins);
        row.accept("Motor driver", drv);
        JPanel lRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JPanel rRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JLabel[] pwmLbl = {new JLabel("PWM"), new JLabel("PWM")};
        lRow.add(pwmLbl[0]);
        lRow.add(lp);
        lRow.add(new JLabel("IN1"));
        lRow.add(l1);
        lRow.add(new JLabel("IN2"));
        lRow.add(l2);
        rRow.add(pwmLbl[1]);
        rRow.add(rp);
        rRow.add(new JLabel("IN1"));
        rRow.add(r1);
        rRow.add(new JLabel("IN2"));
        rRow.add(r2);
        row.accept("Left motor pins", lRow);
        row.accept("Right motor pins", rRow);
        row.accept("TB6612 STBY pin", sb);
        JTextField sp1 = new JTextField(o.sPwm, 4), si1 = new JTextField(o.sIn1, 4), si2 = new JTextField(o.sIn2, 4);
        if (m.steerMode != 0 && m.steerActuator == 1) {                // car: DC steering motor channel
            JPanel dRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            dRow.add(new JLabel("PWM"));
            dRow.add(sp1);
            dRow.add(new JLabel("IN1"));
            dRow.add(si1);
            dRow.add(new JLabel("IN2"));
            dRow.add(si2);
            row.accept("Steering motor pins", dRow);
        }
        JTextField encLf = new JTextField(o.encL, 4), encRf = new JTextField(o.encR, 4);
        if (m.encType > 0) {                                          // encoders: channel A pins (interrupt capable)
            JPanel eRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            eRow.add(new JLabel("left"));
            eRow.add(encLf);
            eRow.add(new JLabel("right"));
            eRow.add(encRf);
            eRow.add(new JLabel("(interrupt pins)"));
            row.accept("Encoder channel A", eRow);
        }
        if (m.steerMode != 0 && m.steerActuator == 0) {                // car: steering servo
            JPanel sRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            sRow.add(new JLabel("pin"));
            sRow.add(servoPin);
            sRow.add(new JLabel("straight at"));
            sRow.add(servoCenter);
            sRow.add(new JLabel("deg"));
            row.accept("Steering servo", sRow);
        }
        Runnable sync = () -> {
            int d = drv.getSelectedIndex();
            lp.setEnabled(d != 2);
            rp.setEnabled(d != 2);
            sb.setEnabled(d == 0);
        };
        drv.addActionListener(e -> sync.run());
        sync.run();
        for (JCheckBox b : m.steerMode != 0 ? new JCheckBox[]{digPins, low, dHigh, dead, servoRev, logCk}
                : new JCheckBox[]{digPins, low, dHigh, spin, dead, logCk}) {
            c.gridy = y[0]++;
            c.gridx = 0;
            c.gridwidth = 2;
            form.add(b, c);
        }
        c.gridy = y[0]++;
        form.add(UI.hint("The sketch uses the settings of the simulated robot: PID gains, speeds, corner slowdown, loop "
                + "period, junction rule and motor trims. Its numbers are in mm and mm/s like the simulator, so the gains "
                + "need no conversion; <b>TOP_SPEED</b> (now " + Math.round(m.maxSpeed) + " mm/s) must be measured on the "
                + "real robot. DRV8833: IN1 / IN2 must both be PWM pins. Sensor 1 is the leftmost; more rows: front row "
                + "first, rows separated by ; . Also copied: sensor filter, error formula, row extrapolation, per-sensor "
                + "calibration, line-lost recovery, confirm count and the no-D-kick rule."
                + diffHtml()), c);
        if (JOptionPane.showConfirmDialog(this, form, "Export Arduino sketch", JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        o.sensorPins = pins.getText().trim();
        o.driver = drv.getSelectedIndex();
        o.lPwm = lp.getText().trim();
        o.lIn1 = l1.getText().trim();
        o.lIn2 = l2.getText().trim();
        o.rPwm = rp.getText().trim();
        o.rIn1 = r1.getText().trim();
        o.rIn2 = r2.getText().trim();
        o.stby = sb.getText().trim();
        o.lineReadsLow = low.isSelected();
        o.digitalLineHigh = dHigh.isSelected();
        o.spinCalibration = spin.isSelected();
        o.deadBandComp = dead.isSelected();
        o.digitalPins = digPins.isSelected();
        o.encL = encLf.getText().trim();
        o.encR = encRf.getText().trim();
        o.log = logCk.isSelected();
        o.servoPin = servoPin.getText().trim();
        o.sPwm = sp1.getText().trim();
        o.sIn1 = si1.getText().trim();
        o.sIn2 = si2.getText().trim();
        o.servoReversed = servoRev.isSelected();
        try {
            o.servoCenter = Integer.parseInt(servoCenter.getText().trim());
        } catch (NumberFormatException ex) {
            o.servoCenter = 90;
        }
        if (o.sensorPins.isEmpty()) {
            JOptionPane.showMessageDialog(this, "List at least one sensor pin.", "Export Arduino sketch", JOptionPane.ERROR_MESSAGE);
            return;
        }
        File f = chooseFile(true, "Save Arduino sketch", "line_follower.ino", new FileNameExtensionFilter("Arduino sketch (*.ino)", "ino"));
        if (f == null) {
            return;
        }
        String name = f.getName().substring(0, f.getName().length() - 4).replaceAll("[^A-Za-z0-9_]", "_");
        File dir = f.getParentFile();
        if (dir == null || !dir.getName().equals(name)) {              // the Arduino IDE wants the sketch in a folder of its own name
            dir = new File(dir, name);
        }
        File out = new File(dir, name + ".ino");
        try {
            if (!dir.isDirectory() && !dir.mkdirs()) {
                throw new IOException("Cannot create the folder " + dir);
            }
            java.nio.file.Files.write(out.toPath(), ArduinoExport.generate(m, o, name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            trackPanel.toast("Sketch written: " + out.getName());
            JOptionPane.showMessageDialog(this, "<html>Written to<br><b>" + out.getPath() + "</b><br><br>Before the first run: measure "
                    + "the top speed and set TOP_SPEED, check the motor directions<br>(swap IN1 / IN2 if a wheel turns "
                    + "backwards) and start slower than in the simulator.</html>", "Export Arduino sketch",
                    JOptionPane.INFORMATION_MESSAGE);
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not write the sketch:\n" + ex.getMessage(), "Export Arduino sketch",
                    JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showShortcuts() {
        String[][] k = {
            {"F5", "Run / pause"}, {"F6", "Restart robot"}, {"Ctrl+N", "New canvas"}, {"Ctrl+O / Ctrl+S", "Open / save workspace"},
            {"Ctrl+Shift+S", "Save workspace as"}, {"Ctrl+P", "Print the track at real size"}, {"Ctrl+G", "Design a robot for this track"}, {"Ctrl+E", "Export Arduino sketch"}, {"Ctrl+K / Ctrl+L", "Keep this run / clear trail"}, {"Ctrl+Z / Ctrl+Y", "Undo / redo (track)"}, {"Ctrl+D", "Duplicate shape"},
            {"Delete", "Delete selected shape"}, {"Esc / right-click", "Cancel drawing, back to Select, deselect"},
            {"Enter, double-click, right-click", "Finish polyline / curve"}, {"Mouse wheel", "Zoom around the pointer"},
            {"Space + drag, middle drag", "Pan"}, {"+ - 0 F, arrows", "Zoom, fit, follow, pan (track focused)"},
            {"Shift while drawing", "15 degree lines, squares, circles"}, {"Alt while clicking", "Place without snapping"},
            {"F7 / F8 / Shift+F8", "LEDs on / off, brighter, dimmer"},
            {"Code tab: Ctrl+Enter", "Apply the code"}, {"Code tab: F9", "Breakpoint on / off"},
            {"Code tab: F5 (stopped)", "Continue"}, {"Code tab: F10 / F11 / Shift+F11", "Step over / into / out"},
            {"Code tab: Ctrl+/", "Comment / uncomment the lines"}};
        StringBuilder sb = new StringBuilder("<html><table cellpadding=2>");
        for (String[] r : k) {
            sb.append("<tr><td><b>").append(r[0]).append("</b></td><td>").append(r[1]).append("</td></tr>");
        }
        JOptionPane.showMessageDialog(this, sb.append("</table></html>").toString(), "Keyboard shortcuts",
                JOptionPane.PLAIN_MESSAGE);
    }

    private void showAbout() {
        JOptionPane.showMessageDialog(this, "<html><b>Line Follower Robot Simulator</b><br>Java 8 / Swing, no external libraries."
                + "<br><br>Design the sensor array, tune the PID and test the robot on real-size tracks.<br>"
                + "Workspaces (.lfsim) keep every setting and the track; track files (.lftrack) keep only the track.</html>",
                "About", JOptionPane.INFORMATION_MESSAGE, Icons.appIcons().isEmpty() ? null
                : new ImageIcon(Icons.appIcons().get(Math.min(5, Icons.appIcons().size() - 1))));
    }

    private JPanel trackTab() {
        ResetGroup g = new ResetGroup(master, "Track");
        JPanel p = column();

        JPanel arena = UI.group("Arena (real size)");
        arena.add(UI.fix(new MenuParam("Common sizes", HardwarePresets.menu("Choose...", HardwarePresets.ARENAS),
                i -> applyArena(HardwarePresets.ARENAS[i].v[0], HardwarePresets.ARENAS[i].v[1])).panel));
        arenaWSp = g.add(new SpinParam("Width", "mm", 200, 6000, 50, m.arenaW, "0", v -> {
            arenaW = v;
            if (!arenaBulk) {
                m.setArena(arenaW, arenaH);
            }
        }));
        arenaHSp = g.add(new SpinParam("Height", "mm", 200, 6000, 50, m.arenaH, "0", v -> {
            arenaH = v;
            if (!arenaBulk) {
                m.setArena(arenaW, arenaH);
            }
        }));
        arena.add(UI.fix(arenaWSp.panel));
        arena.add(UI.fix(arenaHSp.panel));
        arena.add(UI.fix(savedBlock(UserPresets.ARENA, "My arenas", () -> new double[]{arenaW, arenaH},
                v -> applyArena(v[0], v[1]))));
        arena.add(UI.hint("The arena is the real size of your board in mm. Preset tracks are scaled to fit it "
                + "(the line keeps its real width), and a track you drew yourself is kept."));
        p.add(UI.fix(arena));

        JPanel view = UI.group("View");
        viewSync = true;                                   // the spinner's start-up callback must not change the view
        zoomSpin = g.add(new SpinParam("Zoom", "%", 2, 2000, 10, 100, "0", v -> {
            if (!viewSync) {
                trackPanel.setZoomPct(v);
            }
        }));
        viewSync = false;
        viewCombo = g.add(new ComboParam("View", new String[]{"Fit whole arena", "Follow robot", "Free (zoom and pan)"},
                0, i -> trackPanel.setViewMode(i)));
        view.add(UI.fix(viewCombo.panel));
        view.add(UI.fix(zoomSpin.panel));
        view.add(UI.hint("Mouse wheel zooms around the pointer. Pan by holding Space and dragging, with the middle "
                + "button, or with the Pan tool in the Builder tab. Click the track once, then keys + - zoom, 0 fits, F follows the robot "
                + "and the arrow keys pan. 100 % = 1 pixel per mm."));
        p.add(UI.fix(view));

        JPanel line = UI.group("Preset track");
        SpinParam lineSp = g.add(new SpinParam("Line width", "mm", 2, 200, 1, m.lineWidth, "0.#", v -> {
            m.lineWidth = v;
            if (!m.edited) {
                m.rerender();
            }
        }));
        line.add(UI.fix(lineSp.panel));
        double[] widths = {10, 15, 19, 20, 25, 30, 40, 50};
        line.add(UI.fix(new MenuParam("Quick width", new String[]{"Choose...", "10 mm (thin)", "15 mm",
            "19 mm (electrical tape)", "20 mm", "25 mm", "30 mm", "40 mm", "50 mm (wide tape)"},
                i -> lineSp.set(widths[i])).panel));
        line.add(UI.fix(g.add(new ComboParam("Start direction", new String[]{"As drawn", "Reversed"}, 0, i -> {
            m.startDir = i;
            if (!m.edited) {
                m.loadPreset(m.presetIdx);
            }
        })).panel));
        dirLabel = new JLabel(" ");
        dirLabel.setBorder(new EmptyBorder(2, 6, 2, 4));
        dirLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        line.add(dirLabel);
        JButton turn = UI.button("Turn robot round", "reset_robot", "Start facing the other way at the same spot (any track)");
        turn.addActionListener(e -> m.turnRound());
        JButton gateReset = UI.button("Reset finish line", "tool_finish", "Back to the automatic start / finish line");
        gateReset.addActionListener(e -> {
            m.clearGate();
            trackPanel.toast("Finish line: automatic");
        });
        line.add(UI.fix(buttonRow(turn, gateReset)));
        line.add(UI.hint("<b>Line width</b>: type any width, or pick a common tape. <b>Start direction</b>: "
                + "<b>Reversed</b> drives a loop the other way round and starts an open track from its far end. On a "
                + "track you drew, use the Robot tool or <b>Turn robot round</b>. <b>Laps</b>: the chequered line is the "
                + "start / finish (automatic: across the line at the start, or the far end of an open preset). Draw "
                + "your own with the Builder's <b>Finish line</b> tool."));
        p.add(UI.fix(line));
        trailCk = g.add(new CheckParam("Show trail", true, b -> trackPanel.showTrail = b));
        p.add(UI.fix(trailCk.panel));
        JButton clrTrail = UI.button("Clear trail", "clear", "Wipe the drawn trail; the robot keeps going (Ctrl+L)");
        JButton keepRun = UI.button("Keep this run", "saved", "Keep this trail and its numbers to compare (Ctrl+K, see the Runs tab)");
        clrTrail.addActionListener(e -> m.clearTrail());
        keepRun.addActionListener(e -> runsPanel.keepRun());
        p.add(UI.fix(buttonRow(clrTrail, keepRun)));
        p.add(UI.hint("Build or change the track itself in the Builder tab. The preset line width only applies to a "
                + "preset track you have not edited; shapes you draw have their own width."));
        JPanel bg = UI.group("Background picture (to trace over)");
        JButton loadBg = UI.button("Load picture...", "image", "A photo or scan of a real track to trace over");
        JButton removeBg = UI.button("Remove", "picture_off", "Remove the background picture");
        loadBg.addActionListener(e -> loadBackground());
        removeBg.addActionListener(e -> {
            m.clearBackground();
            syncBg();
        });
        bg.add(UI.fix(buttonRow(loadBg, removeBg)));
        bg.add(UI.fix(dep(g.add(new CheckParam("Show picture", m.bgVisible, b -> m.bgVisible = b)).panel, () -> m.bgImage != null)));
        bg.add(UI.fix(dep(g.add(new CheckParam("Keep the picture inside saved files", true, b -> m.bgEmbed = b)).panel, () -> m.bgImage != null)));
        bg.add(UI.fix(dep(g.add(new Param("Opacity", "%", 5, 100, m.bgAlpha * 100, 0, v -> m.bgAlpha = v / 100)).panel, () -> m.bgImage != null)));
        bgWSp = new SpinParam("Picture width", "mm", 20, 50000, 10, m.bgW, "0.#", v -> {
            if (!bgSync) {
                m.bgW = v;
            }
        }, false);
        bgXSp = new SpinParam("Left edge x", "mm", -50000, 50000, 10, m.bgX, "0.#", v -> {
            if (!bgSync) {
                m.bgX = v;
            }
        }, false);
        bgYSp = new SpinParam("Top edge y", "mm", -50000, 50000, 10, m.bgY, "0.#", v -> {
            if (!bgSync) {
                m.bgY = v;
            }
        }, false);
        bg.add(UI.fix(dep(bgWSp.panel, () -> m.bgImage != null)));
        bg.add(UI.fix(dep(bgXSp.panel, () -> m.bgImage != null)));
        bg.add(UI.fix(dep(bgYSp.panel, () -> m.bgImage != null)));
        JButton fitBg = UI.button("Fit to arena", "fit", "Scale the picture to fill the arena");
        JButton scaleBg = UI.button("Set scale...", "measure", "Click two points a known distance apart, then type the distance");
        fitBg.addActionListener(e -> {
            m.fitBackground();
            syncBg();
        });
        scaleBg.addActionListener(e -> measureBackground());
        bg.add(UI.fix(dep(buttonRow(fitBg, scaleBg), () -> m.bgImage != null)));
        bg.add(UI.hint("Load a photo or scan of a real track and trace it with the Builder tools. The picture is only a guide: "
                + "the sensors never see it, and it is not part of the track. A photo has no real size, so use <b>Set scale</b>: "
                + "click two points a known distance apart on the picture, then type that distance. The Builder's snapping "
                + "and grid work on top of the picture. Hide it or lower the opacity when you run the robot."));
        p.add(UI.fix(bg));
        master.addAction(() -> {
            m.clearBackground();
            syncBg();
        });

        p.add(UI.resetRow("Reset track tab", g));
        return p;
    }

    /** Set both arena fields and rebuild the track once. */
    private void applyArena(double w, double h) {
        arenaBulk = true;
        arenaWSp.set(w);
        arenaHSp.set(h);
        arenaBulk = false;
        m.setArena(w, h);
    }
}
