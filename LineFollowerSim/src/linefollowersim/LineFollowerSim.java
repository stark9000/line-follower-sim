package linefollowersim;

import javax.swing.*;
import java.awt.*;

/**
 * Line Follower Robot Simulator (Java 8, Swing / Java2D, no libraries).
 *
 * Classes:
 *   LineFollowerSim  main window + game loop
 *   Model            physics, sensors, PID controller, MCU timing (no Swing)
 *   TrackBuilder     preset tracks
 *   McuProfile       clock / cycle-cost presets (Arduino, ESP, ...)
 *   TrackPanel       track view, robot, sensor read-out HUD, mouse tools
 *   GraphPanel       error / PID / wheel-speed graphs
 *   ControlPanel     the control column (tabs, master reset)
 *   Theme, Icons     light / dark theme (Metal based) and the PNG icons
 *   Workspace        .lfsim files: every setting + the track
 *   Param, SpinParam, ComboParam, CheckParam   controls with a reset button
 *   Resettable, ResetGroup                      reset plumbing
 *   UI               small Swing helpers
 */
public class LineFollowerSim extends JFrame {

    private final Model model = new Model();
    private final TrackPanel trackPanel = new TrackPanel(model);
    private final GraphPanel graphPanel = new GraphPanel(model);
    private final ControlPanel controls = new ControlPanel(model, trackPanel);
    private long lastNanos = System.nanoTime();
    private final JTabbedPane top = new JTabbedPane();
    private RobotEditor robotEditor;
    private CodeTab codeTab;

    public LineFollowerSim() {
        super("Line Follower Robot Simulator");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                exit();
            }
        });
        setIconImages(Icons.appIcons());
        JPanel left = new JPanel(new BorderLayout(0, 4));
        left.add(trackPanel, BorderLayout.CENTER);
        left.add(graphPanel, BorderLayout.SOUTH);
        JPanel sim = new JPanel(new BorderLayout());
        sim.add(left, BorderLayout.CENTER);
        sim.add(controls, BorderLayout.EAST);
        robotEditor = new RobotEditor(model, controls);
        top.addTab("Simulator", Icons.get("play"), sim, "Run the robot on the track");
        top.addTab("Robot editor", Icons.get("tool_robot"), robotEditor, "Draw the chassis, place sensors, LEDs and parts");
        codeTab = new CodeTab(model, controls);
        top.addTab("Code", Icons.get("tab_code"), codeTab, "Write the robot's program (Arduino-style C) and test it here");
        java.util.function.Supplier<java.util.List<String>> edLines = controls.draftLines;
        java.util.function.Consumer<java.util.List<String>> edRestore = controls.draftRestore;
        controls.draftLines = () -> {                        // the workspace keeps the code with the editor's draft
            java.util.List<String> l = new java.util.ArrayList<>();
            java.util.List<String> e = edLines.get();
            if (e != null) {
                l.addAll(e);
            }
            l.addAll(codeTab.lines());
            return l;
        };
        controls.draftRestore = lines -> {
            java.util.List<String> e = new java.util.ArrayList<>(), c = new java.util.ArrayList<>();
            for (String t : lines) {
                (CodeTab.isCodeLine(t) ? c : e).add(t);
            }
            codeTab.restore(c);
            if (!e.isEmpty()) {
                edRestore.accept(e);
            }
        };
        add(top, BorderLayout.CENTER);
        controls.showRobotEditor = () -> top.setSelectedComponent(robotEditor);
        controls.editorShown = () -> top.getSelectedComponent() == robotEditor;
        controls.editorUndo = robotEditor::undo;
        controls.editorRedo = robotEditor::redo;
        controls.editorDelete = robotEditor::deleteSelection;
        top.addChangeListener(e -> {
            if (top.getSelectedComponent() == robotEditor) {
                robotEditor.canvas.requestFocusInWindow();
            } else if (top.getSelectedComponent() == codeTab) {
                codeTab.refresh(true);
            }
        });
        controls.setThemeToggle(this::toggleTheme);
        controls.setTitleSink(this::setTitle);
        setJMenuBar(controls.buildMenuBar(this::exit));
        controls.startClean();
        JRootPane rp = getRootPane();                        // Esc works wherever the focus is (back to Select / deselect)
        rp.getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW).put(KeyStroke.getKeyStroke("ESCAPE"), "lfsEsc");
        rp.getActionMap().put("lfsEsc", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (top.getSelectedComponent() == robotEditor) {
                    robotEditor.escapeKey();
                } else {
                    trackPanel.escapeKey();
                }
            }
        });
        pack();
        fitToScreen();
        setLocationRelativeTo(null);
        restoreBounds();
        new javax.swing.Timer(16, e -> tick()).start();
    }

    /** Use most of the screen (the layout is meant to fit 1366 x 768 and larger). */
    private void fitToScreen() {
        Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        int w = Math.min(Math.max(getWidth(), 1240), scr.width - 20);
        int h = Math.min(Math.max(getHeight(), 760), scr.height - 10);
        setSize(Math.min(w, 1600), Math.min(h, 1000));
        setMinimumSize(new Dimension(Math.min(1000, scr.width), Math.min(600, scr.height)));
    }

    /** Put the window where it was last time (if that is still on a screen). */
    private void restoreBounds() {
        String w = UserPresets.setting("window", "");
        if (w.equals("max")) {
            setExtendedState(MAXIMIZED_BOTH);
            return;
        }
        String[] p = w.split(",");
        if (p.length != 4) {
            return;
        }
        try {
            Rectangle r = new Rectangle(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            Rectangle scr = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
            if (scr.intersects(r) && r.width >= getMinimumSize().width && r.height >= getMinimumSize().height) {
                setBounds(r.intersection(scr));
            }
        } catch (NumberFormatException ex) {
            // ignore a damaged entry
        }
    }

    /** Ask about unsaved work before closing. */
    private void exit() {
        if (controls.confirmDiscard("closing")) {
            Rectangle b = getBounds();
            UserPresets.setSetting("window", (getExtendedState() & MAXIMIZED_BOTH) != 0 ? "max"
                    : b.x + "," + b.y + "," + b.width + "," + b.height);
            dispose();
            System.exit(0);
        }
    }

    private void toggleTheme() {
        boolean dark = !Theme.isDark();
        Theme.apply(dark, Window.getWindows());
        controls.themeChanged();
        robotEditor.themeChanged();
        codeTab.themeChanged();
        UserPresets.setSetting("theme", dark ? "dark" : "light");
    }

    private void tick() {
        long now = System.nanoTime();
        double dt = Math.min((now - lastNanos) / 1e9, 0.05);
        lastNanos = now;
        if (model.running && !codeTab.holding()) {               // stopped at a breakpoint: time stands still
            model.advance(dt * model.speedMul);
        }
        if (top.getSelectedIndex() == 0) {                   // the simulator view only needs painting while it is shown
            controls.refreshLive();
            trackPanel.repaint();
            graphPanel.repaint();
        } else if (top.getSelectedComponent() == codeTab) {
            codeTab.refresh(false);
        }
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            String th = System.getProperty("linefollowersim.theme", UserPresets.setting("theme", "dark"));
            Theme.apply("dark".equals(th));
            new LineFollowerSim().setVisible(true);
        });
    }
}
