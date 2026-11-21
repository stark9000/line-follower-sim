package linefollowersim;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableModel;
import javax.swing.text.*;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Code tab: write the robot's program in Arduino-style C and run it in the simulator.
 * Built-in: the code is not used. Hooks: the built-in follower drives, then loop() may change it.
 * Full code: only the code drives. The sliders are live constants (KP, BASE_SPEED ...).
 * File > Export Arduino sketch puts the code into the sketch.
 */
final class CodeTab extends JPanel {

    static final String[] MODES = {"Built-in controller", "Hooks: built-in + my loop()", "Full code: only my code"};

    private final Model m;
    private final ControlPanel cp;
    private CodeRunner runner;
    final TrackPanel view;
    final JTextPane ed = new JTextPane();
    private final JComboBox<String> mode = new JComboBox<>(MODES);
    private final JLabel status = new JLabel(" ");
    private final JLabel stats = new JLabel(" ");
    private final JTextArea console = new JTextArea();
    private final DefaultTableModel watchM = new DefaultTableModel(new Object[]{"variable", "value"}, 0) {
        @Override
        public boolean isCellEditable(int r, int c) {
            return false;
        }
    };
    private final PlotPanel plot = new PlotPanel(300, 160);
    private final JButton applyBtn = UI.button("Apply", "saved", "Check the code and use it (Ctrl+Enter); the run restarts");
    private final JButton runBtn = UI.button("Run", "play", "Start / pause the simulation (F5)");
    private final Gutter gutter = new Gutter();
    private final UndoManager undo = new UndoManager();
    private final javax.swing.Timer hl = new javax.swing.Timer(250, e -> highlight());
    // ---- debugger
    static final int D_RUN = 0, D_INTO = 1, D_OVER = 2, D_OUT = 3, D_PAUSE = 4;
    final java.util.TreeMap<Integer, String> breakpoints = new java.util.TreeMap<>();   // line -> condition ("" = always)
    private final java.util.Map<Integer, MiniC.Expr> bpTests = new java.util.HashMap<>();
    private int dbgMode = D_RUN, stepDepth;
    private long stepRun;
    private boolean holding, abortReq;
    private SecondaryLoop hold;
    private int stopLine = -1;
    private String stopWhy = "";
    private Object stopTag;
    private int lineCount = 1;
    private final DefaultListModel<String> stackM = new DefaultListModel<>();
    private final JList<String> stackList = new JList<>(stackM);
    private final JTabbedPane outTabs = new JTabbedPane();
    private final JButton contBtn = icon("dbg_continue", "Continue (F5): run on to the next breakpoint");
    private final JButton breakBtn = icon("pause", "Break: stop at the next line the code runs");
    private final JButton overBtn = icon("dbg_over", "Step over (F10): the next line, calls run through");
    private final JButton intoBtn = icon("dbg_into", "Step into (F11): the next line, into called functions");
    private final JButton outBtn = icon("dbg_out", "Step out (Shift+F11): run to the end of this function");
    private final MiniC.Debug dbg = new MiniC.Debug() {
        @Override
        public void at(int line) {
            debugAt(line);
        }

        @Override
        public void error(MiniC.RunError e) {
            if (SwingUtilities.isEventDispatchThread()) {
                dbgMode = D_RUN;
                stopHere(e.line, "error: " + e.getMessage().replaceFirst("^line \\d+: ", ""));
            }
        }
    };
    private String applied = "";
    private int shownConsole = -1, refreshTick;
    private Object errorTag;
    private boolean syncing;

    CodeTab(Model m, ControlPanel cp) {
        super(new BorderLayout(4, 4));
        this.m = m;
        this.cp = cp;
        view = new TrackPanel(m);
        runner = new CodeRunner(m);
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // ---- tool bar
        JButton ex = UI.button("Examples", "open", "Ready-made programs: marker counter, PID written out, bang-bang ...");
        ex.addActionListener(e -> examplesMenu(ex));
        JButton restart = UI.button("Restart", "reset_robot", "Back to the start: globals to their first values, setup() runs (F6)");
        restart.addActionListener(e -> restart());
        JButton step = UI.button("Step loop", "step", "Pause and run exactly one loop");
        step.addActionListener(e -> stepLoop());
        JButton clr = UI.button("Clear", "clear", "Clear the console and the plot");
        clr.addActionListener(e -> {
            runner.clearConsole();
            runner.plots.clear();
            refresh(true);
        });
        JButton exp = UI.button("Export sketch...", "tab_mcu", "File > Export Arduino sketch, with this code in it");
        exp.addActionListener(e -> {
            if (!applyIfChanged()) {
                return;
            }
            cp.exportArduino();
        });
        applyBtn.addActionListener(e -> apply());
        runBtn.addActionListener(e -> {
            cp.toggleRunning();
            syncRun();
        });
        mode.setToolTipText("<html>Built-in: the code is not used.<br>Hooks: the built-in follower drives, then your loop() runs.<br>"
                + "Full code: only your code drives.</html>");
        mode.addActionListener(e -> {
            if (!syncing) {
                setMode(mode.getSelectedIndex());
            }
        });
        JToolBar tb = new JToolBar();
        tb.setFloatable(false);
        tb.add(new JLabel(" Mode "));
        tb.add(mode);
        mode.setMaximumSize(mode.getPreferredSize());
        tb.addSeparator();
        for (JComponent c : new JComponent[]{applyBtn, ex, runBtn, restart, step, clr, exp}) {
            tb.add(c);
        }
        tb.addSeparator();
        tb.add(new JLabel("Debug "));
        for (JButton b : new JButton[]{contBtn, breakBtn, overBtn, intoBtn, outBtn}) {
            tb.add(b);
        }
        contBtn.addActionListener(e -> resume(D_RUN));
        breakBtn.addActionListener(e -> resume(D_PAUSE));
        overBtn.addActionListener(e -> resume(D_OVER));
        intoBtn.addActionListener(e -> resume(D_INTO));
        outBtn.addActionListener(e -> resume(D_OUT));
        bindTab("F9", "lfsBp", this::toggleBreakpointAtCaret);
        bindTab("F10", "lfsOver", () -> resume(D_OVER));
        bindTab("F11", "lfsInto", () -> resume(D_INTO));
        bindTab("shift F11", "lfsOut", () -> resume(D_OUT));
        cp.debugHold = () -> holding;
        cp.debugAbort = this::abortDebug;
        cp.debugContinue = () -> {
            if (holding) {
                resume(D_RUN);
                return true;
            }
            return false;
        };
        add(tb, BorderLayout.NORTH);

        // ---- editor
        ed.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        ed.setText(CodeExamples.ALL[0][2]);
        ed.setCaretPosition(0);
        installKeys();
        ed.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                shiftBreakpoints(e.getOffset());
                edited();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                shiftBreakpoints(e.getOffset());
                edited();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                // style changes only
            }
        });
        ed.getDocument().addUndoableEditListener(e -> {
            if (!(e.getEdit() instanceof AbstractDocument.DefaultDocumentEvent
                    && ((AbstractDocument.DefaultDocumentEvent) e.getEdit()).getType() == DocumentEvent.EventType.CHANGE)) {
                undo.addEdit(e.getEdit());
            }
        });
        hl.setRepeats(false);
        JPanel noWrap = new JPanel(new BorderLayout());               // keeps long lines on one line (scroll sideways)
        noWrap.add(ed);
        JScrollPane es = new JScrollPane(noWrap);
        es.setRowHeaderView(gutter);
        es.getVerticalScrollBar().setUnitIncrement(16);
        JPanel left = new JPanel(new BorderLayout(0, 2));
        left.add(es, BorderLayout.CENTER);
        status.setFont(status.getFont().deriveFont(Font.BOLD));
        JPanel sp = new JPanel(new BorderLayout());
        sp.add(status, BorderLayout.CENTER);
        sp.add(stats, BorderLayout.EAST);
        left.add(sp, BorderLayout.SOUTH);

        // ---- right: the track and the read-outs
        console.setEditable(false);
        console.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        JTable wt = new JTable(watchM);
        wt.setRowHeight(18);
        wt.getColumnModel().getColumn(0).setPreferredWidth(110);
        wt.getColumnModel().getColumn(1).setPreferredWidth(220);
        plot.setup("plot(\"name\", value)", "s", "", false, false);
        plot.setEmptyText("no plot(\"name\", value) yet");
        plot.setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
        JEditorPane help = new JEditorPane("text/html", CodeExamples.HELP);
        help.setEditable(false);
        help.setCaretPosition(0);
        stackList.setVisibleRowCount(3);
        stackList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                refresh(true);
            }
        });
        JPanel watchP = new JPanel(new BorderLayout(0, 2));
        JScrollPane ss = new JScrollPane(stackList);
        ss.setBorder(BorderFactory.createTitledBorder("Call stack (click a call to see its variables)"));
        ss.setPreferredSize(new Dimension(300, 74));
        watchP.add(ss, BorderLayout.NORTH);
        watchP.add(new JScrollPane(wt), BorderLayout.CENTER);
        JTabbedPane out = outTabs;
        out.addTab("Console", new JScrollPane(console));
        out.addTab("Watch", watchP);
        out.addTab("Plot", plot);
        out.addTab("Help", new JScrollPane(help));
        view.setPreferredSize(new Dimension(560, 330));
        JSplitPane right = new JSplitPane(JSplitPane.VERTICAL_SPLIT, view, out);
        right.setResizeWeight(0.6);
        right.setContinuousLayout(true);
        out.setPreferredSize(new Dimension(560, 230));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setResizeWeight(0.5);
        split.setContinuousLayout(true);
        left.setPreferredSize(new Dimension(620, 500));
        add(split, BorderLayout.CENTER);

        gutter.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                int line = ed.getDocument().getDefaultRootElement().getElementIndex(ed.viewToModel(new Point(0, e.getY()))) + 1;
                if (SwingUtilities.isRightMouseButton(e)) {
                    breakpointMenu(line, e.getX(), e.getY());
                } else {
                    toggleBreakpoint(line);
                }
            }
        });
        gutter.setToolTipText("Click: breakpoint on / off (F9). Right-click: a condition");
        compileInto(ed.getText(), false);
        applied = ed.getText();
        setMode(0);
        highlight();
        updateDebugButtons();
    }

    private static JButton icon(String ic, String tip) {
        JButton b = new JButton(Icons.get(ic));
        b.setToolTipText(tip);
        b.setFocusable(false);
        return b;
    }

    private void bindTab(String key, String name, Runnable r) {
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(key), name);
        getActionMap().put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    // ------------------------------------------------------------------ the debugger
    boolean holding() {
        return holding;
    }

    /** Called by the interpreter before every statement (on the Swing thread, inside the robot's loop). */
    private void debugAt(int line) {
        if (abortReq) {
            abortReq = false;
            throw new MiniC.Abort();
        }
        if (dbgMode == D_RUN && breakpoints.isEmpty() || !SwingUtilities.isEventDispatchThread()) {
            return;
        }
        int d = runner.c.stack.size();
        boolean stop;
        String why = "step";
        switch (dbgMode) {
            case D_INTO:
            case D_PAUSE:
                stop = true;
                break;
            case D_OVER:
                stop = d <= stepDepth || runner.c.runs != stepRun;
                break;
            case D_OUT:
                stop = d < stepDepth || runner.c.runs != stepRun;
                break;
            default:
                stop = false;
        }
        if (!stop && breakpoints.containsKey(line)) {
            MiniC.Expr t = bpTests.get(line);
            try {
                stop = t == null || runner.c.test(t);
            } catch (MiniC.RunError ex) {
                stop = true;
            }
            why = t == null ? "breakpoint" : "breakpoint, " + breakpoints.get(line) + " is true";
        }
        if (stop) {
            dbgMode = D_RUN;
            stopHere(line, why);
        }
    }

    /** Stop the whole simulation here until Continue / a step (the window keeps working meanwhile). */
    private void stopHere(int line, String why) {
        holding = true;
        stopLine = line;
        stopWhy = why;
        markStop(line);
        stackM.clear();
        for (int i = runner.c.stack.size() - 1; i >= 0; i--) {
            MiniC.Frame f = runner.c.stack.get(i);
            stackM.addElement(f.name() + "   line " + f.line);
        }
        stackList.setSelectedIndex(0);
        outTabs.setSelectedIndex(1);
        updateDebugButtons();
        refresh(true);
        hold = Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
        hold.enter();                                                 // the window keeps working; the robot's loop waits here
        hold = null;
        holding = false;
        markStop(-1);
        stackM.clear();
        updateDebugButtons();
        updateStatus();
        if (abortReq) {
            abortReq = false;
            throw new MiniC.Abort();
        }
    }

    /** Continue or step. When nothing is stopped, a step starts the run and stops at the first line. */
    private void resume(int mode) {
        if (m.codeMode == 0 || m.program != runner || !runner.compiled) {
            return;
        }
        stepDepth = runner.c.stack.size();
        stepRun = runner.c.runs;
        if (!holding && mode != D_RUN) {
            mode = D_PAUSE;                                           // not stopped yet: stop at the next line that runs
        }
        dbgMode = mode;
        if (holding) {
            hold.exit();
        } else if (!m.running) {
            cp.toggleRunning();
        }
        syncRun();
    }

    /** Leave the stopped program (Restart, Apply, new mode); the loop ends at once. */
    void abortDebug() {
        if (holding) {
            abortReq = true;
            dbgMode = D_RUN;
            hold.exit();
        }
    }

    /** Run r once the program is no longer stopped. */
    private void whenFree(Runnable r) {
        if (holding) {
            abortDebug();
            SwingUtilities.invokeLater(() -> whenFree(r));
        } else {
            r.run();
        }
    }

    private void updateDebugButtons() {
        boolean on = m.codeMode != 0;
        contBtn.setEnabled(on && holding);
        breakBtn.setEnabled(on && !holding);
        overBtn.setEnabled(on);
        intoBtn.setEnabled(on);
        outBtn.setEnabled(on && holding);
    }

    private void markStop(int line) {
        Highlighter h = ed.getHighlighter();
        if (stopTag != null) {
            h.removeHighlight(stopTag);
            stopTag = null;
        }
        gutter.stopLine = line;
        gutter.repaint();
        if (line < 1) {
            return;
        }
        Element root = ed.getDocument().getDefaultRootElement();
        if (line - 1 >= root.getElementCount()) {
            return;
        }
        Element el = root.getElement(line - 1);
        try {
            stopTag = h.addHighlight(el.getStartOffset(), el.getEndOffset(),
                    new DefaultHighlighter.DefaultHighlightPainter(new Color(250, 200, 40, Theme.isDark() ? 80 : 110)));
            ed.scrollRectToVisible(ed.modelToView(el.getStartOffset()));
        } catch (BadLocationException ex) {
            // ignore
        }
    }

    // ---- breakpoints
    private void toggleBreakpointAtCaret() {
        toggleBreakpoint(ed.getDocument().getDefaultRootElement().getElementIndex(ed.getCaretPosition()) + 1);
    }

    void toggleBreakpoint(int line) {
        if (breakpoints.remove(line) == null) {
            breakpoints.put(line, "");
        }
        bpTests.remove(line);
        gutter.repaint();
    }

    private void breakpointMenu(int line, int x, int y) {
        JPopupMenu p = new JPopupMenu();
        JMenuItem cond = new JMenuItem(breakpoints.containsKey(line) ? "Condition..." : "Breakpoint with a condition...");
        cond.addActionListener(e -> {
            String c = (String) JOptionPane.showInputDialog(this, "<html>Stop on line " + line + " only when this is true<br>"
                    + "(globals, sensors and sliders, e.g. <code>A == 2</code> or <code>countDark() >= 3</code>).<br>Empty = always.</html>",
                    "Breakpoint condition", JOptionPane.PLAIN_MESSAGE, null, null, breakpoints.getOrDefault(line, ""));
            if (c != null) {
                setBreakpoint(line, c.trim());
            }
        });
        p.add(cond);
        if (breakpoints.containsKey(line)) {
            JMenuItem rm = new JMenuItem("Remove this breakpoint");
            rm.addActionListener(e -> toggleBreakpoint(line));
            p.add(rm);
        }
        JMenuItem all = new JMenuItem("Remove all breakpoints");
        all.setEnabled(!breakpoints.isEmpty());
        all.addActionListener(e -> {
            breakpoints.clear();
            bpTests.clear();
            gutter.repaint();
        });
        p.add(all);
        p.show(gutter, x, y);
    }

    void setBreakpoint(int line, String cond) {
        breakpoints.put(line, cond);
        bpTests.remove(line);
        if (!cond.isEmpty()) {
            try {
                bpTests.put(line, runner.c.compileCondition(cond));
            } catch (MiniC.CompileError ex) {
                JOptionPane.showMessageDialog(this, "The condition has an error: " + ex.getMessage().replaceFirst("^line \\d+: ", "")
                        + "\nThe breakpoint stops every time until it is fixed.", "Breakpoint", JOptionPane.WARNING_MESSAGE);
            }
        }
        gutter.repaint();
    }

    /** After Apply: the conditions again, for the new program. */
    private void recompileConditions() {
        bpTests.clear();
        for (java.util.Map.Entry<Integer, String> e : breakpoints.entrySet()) {
            if (!e.getValue().isEmpty()) {
                try {
                    bpTests.put(e.getKey(), runner.c.compileCondition(e.getValue()));
                } catch (MiniC.CompileError ex) {
                    // stops every time
                }
            }
        }
    }

    /** Lines added or removed: the breakpoints below move with their lines. */
    private void shiftBreakpoints(int offset) {
        int n = ed.getDocument().getDefaultRootElement().getElementCount(), delta = n - lineCount;
        lineCount = n;
        if (delta == 0 || breakpoints.isEmpty()) {
            return;
        }
        int at = ed.getDocument().getDefaultRootElement().getElementIndex(offset) + 1;
        java.util.TreeMap<Integer, String> moved = new java.util.TreeMap<>();
        for (java.util.Map.Entry<Integer, String> e : breakpoints.entrySet()) {
            int l = e.getKey();
            if (delta < 0 && l > at && l <= at - delta) {
                continue;                                                 // its line was deleted
            }
            if (l > at) {
                l += delta;
            }
            moved.putIfAbsent(l, e.getValue());
        }
        breakpoints.clear();
        breakpoints.putAll(moved);
        bpTests.clear();
        recompileConditions();
    }

    // ------------------------------------------------------------------ editor keys
    private void installKeys() {
        InputMap im = ed.getInputMap(JComponent.WHEN_FOCUSED);
        ActionMap am = ed.getActionMap();
        bind(im, am, "ctrl ENTER", "lfsApply", this::apply);
        bind(im, am, "ctrl Z", "lfsUndo", () -> {
            if (undo.canUndo()) {
                undo.undo();
            }
        });
        bind(im, am, "ctrl Y", "lfsRedo", () -> {
            if (undo.canRedo()) {
                undo.redo();
            }
        });
        bind(im, am, "ctrl shift Z", "lfsRedo2", () -> {
            if (undo.canRedo()) {
                undo.redo();
            }
        });
        bind(im, am, "TAB", "lfsTab", () -> indentSelection(true));
        bind(im, am, "shift TAB", "lfsUntab", () -> indentSelection(false));
        bind(im, am, "ENTER", "lfsEnter", this::newLine);
        bind(im, am, "ctrl SLASH", "lfsComment", this::toggleComment);
        bind(im, am, "DELETE", "lfsDel", () -> {                    // keep Delete for the text (not the robot editor)
            int a = ed.getSelectionStart(), b = ed.getSelectionEnd();
            try {
                if (a != b) {
                    ed.getDocument().remove(a, b - a);
                } else if (a < ed.getDocument().getLength()) {
                    ed.getDocument().remove(a, 1);
                }
            } catch (BadLocationException ex) {
                // ignore
            }
        });
    }

    private static void bind(InputMap im, ActionMap am, String key, String name, Runnable r) {
        im.put(KeyStroke.getKeyStroke(key), name);
        am.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                r.run();
            }
        });
    }

    private String text() {
        try {
            return ed.getDocument().getText(0, ed.getDocument().getLength());
        } catch (BadLocationException ex) {
            return "";
        }
    }

    /** Enter: keep the indent, one more after '{'; a line with only '}' goes one back. */
    private void newLine() {
        String s = text();
        int p = ed.getSelectionStart();
        int ls = s.lastIndexOf('\n', p - 1) + 1;
        String line = s.substring(ls, p);
        int k = 0;
        while (k < line.length() && line.charAt(k) == ' ') {
            k++;
        }
        String ind = line.substring(0, k);
        if (line.trim().endsWith("{")) {
            ind += "    ";
        }
        ed.replaceSelection("\n" + ind);
    }

    private void indentSelection(boolean in) {
        String s = text();
        int a = ed.getSelectionStart(), b = ed.getSelectionEnd();
        if (a == b && in) {
            int ls = s.lastIndexOf('\n', a - 1) + 1;
            ed.replaceSelection("    ".substring((a - ls) % 4));
            return;
        }
        int ls = s.lastIndexOf('\n', a - 1) + 1;
        int le = b > a && b > 0 && s.charAt(b - 1) == '\n' ? b - 1 : s.indexOf('\n', b);
        if (le < 0) {
            le = s.length();
        }
        String[] lines = s.substring(ls, le).split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (in) {
                l = "    " + l;
            } else {
                int k = 0;
                while (k < 4 && k < l.length() && l.charAt(k) == ' ') {
                    k++;
                }
                l = l.substring(k);
            }
            sb.append(i > 0 ? "\n" : "").append(l);
        }
        replaceRange(ls, le, sb.toString());
        ed.select(ls, ls + sb.length());
    }

    private void toggleComment() {
        String s = text();
        int a = ed.getSelectionStart(), b = Math.max(a, ed.getSelectionEnd() - (ed.getSelectionEnd() > a ? 1 : 0));
        int ls = s.lastIndexOf('\n', a - 1) + 1;
        int le = s.indexOf('\n', b);
        if (le < 0) {
            le = s.length();
        }
        String[] lines = s.substring(ls, le).split("\n", -1);
        boolean all = true;
        for (String l : lines) {
            all &= l.trim().isEmpty() || l.trim().startsWith("//");
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (all) {
                int c = l.indexOf("//");
                if (c >= 0) {
                    l = l.substring(0, c) + l.substring(Math.min(l.length(), c + (l.startsWith("// ", c) ? 3 : 2)));
                }
            } else if (!l.trim().isEmpty()) {
                l = "// " + l;
            }
            sb.append(i > 0 ? "\n" : "").append(l);
        }
        replaceRange(ls, le, sb.toString());
        ed.select(ls, ls + sb.length());
    }

    private void replaceRange(int a, int b, String t) {
        try {
            Document d = ed.getDocument();
            d.remove(a, b - a);
            d.insertString(a, t, null);
        } catch (BadLocationException ex) {
            // ignore
        }
    }

    private void edited() {
        hl.restart();
        gutter.revalidate();
        gutter.repaint();
        updateStatus();
    }

    // ------------------------------------------------------------------ apply / mode / run
    /** Compile; on success the robot uses the new program. Returns true if it compiled. */
    private boolean compileInto(String src, boolean report) {
        CodeRunner r = new CodeRunner(m);
        boolean ok = r.compile(src);
        if (ok) {
            r.console.addAll(runner.console);
            r.c.debug = dbg;
            runner = r;
            m.program = r;
            m.codeSource = src;
            recompileConditions();
        } else {
            runner.error = r.error;                                       // show the error, keep running the old program
            runner.errorLine = r.errorLine;
            runner.compiled = runner.compiled && runner.source.equals(applied);
        }
        markError(ok ? -1 : r.errorLine);
        if (report) {
            updateStatus();
        }
        return ok;
    }

    void apply() {
        if (holding) {
            whenFree(this::apply);
            return;
        }
        String src = text();
        boolean ok = compileInto(src, true);
        if (ok) {
            applied = src;
            if (m.codeMode == 0) {
                status.setText("Compiled. Pick Hooks or Full code to use it.");
                status.setForeground(Theme.textDim());
            }
            restart();
        } else {
            jumpToError();
        }
        updateStatus();
    }

    /** Before exporting: the text in the editor is what goes out. */
    private boolean applyIfChanged() {
        if (!text().equals(applied)) {
            apply();
        }
        if (runner.error != null && !runner.error.startsWith("run")) {
            JOptionPane.showMessageDialog(this, "Fix the error first:\n" + runner.error, "Export sketch", JOptionPane.WARNING_MESSAGE);
            return false;
        }
        return true;
    }

    void setMode(int k) {
        if (holding) {
            whenFree(() -> setMode(k));
            return;
        }
        m.codeMode = k;
        syncing = true;
        mode.setSelectedIndex(k);
        syncing = false;
        if (k != 0 && !text().equals(applied)) {
            apply();
        } else {
            restart();
        }
        updateStatus();
        updateDebugButtons();
    }

    private void restart() {
        cp.resetRobot();
        refresh(true);
    }

    private void stepLoop() {
        if (holding) {                                                // finish this loop, then stop
            dbgMode = D_RUN;
            m.running = false;
            hold.exit();
            return;
        }
        if (m.running) {
            cp.toggleRunning();
        }
        long n0 = m.loopCount;
        m.running = true;
        int guard = 0;
        while (m.loopCount == n0 && guard++ < 200000 && m.running) {
            m.advance(m.stepSec());
        }
        m.running = false;
        syncRun();
        refresh(true);
    }

    private void syncRun() {
        if (cp.runningShown() != m.running) {
            cp.syncRunButton();
        }
        runBtn.setText(m.running ? "Pause" : "Run");
        runBtn.setIcon(Icons.get(m.running ? "pause" : "play"));
    }

    private void examplesMenu(Component at) {
        JPopupMenu p = new JPopupMenu();
        for (String[] e : CodeExamples.ALL) {
            JMenuItem it = new JMenuItem(e[0] + "   (" + (e[1].equals("1") ? "Hooks" : "Full code") + ")");
            it.addActionListener(a -> {
                if (!text().equals(applied) && !text().trim().isEmpty()
                        && JOptionPane.showConfirmDialog(this, "Replace the code in the editor (it has changes that are not applied)?",
                                "Examples", JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
                    return;
                }
                ed.setText(e[2]);
                ed.setCaretPosition(0);
                undo.discardAllEdits();
                apply();
                setMode(Integer.parseInt(e[1]));
            });
            p.add(it);
        }
        p.show(at, 0, at.getHeight());
    }

    // ------------------------------------------------------------------ errors and status
    private void markError(int line) {
        Highlighter h = ed.getHighlighter();
        if (errorTag != null) {
            h.removeHighlight(errorTag);
            errorTag = null;
        }
        gutter.errorLine = line;
        gutter.repaint();
        if (line < 1) {
            return;
        }
        Element root = ed.getDocument().getDefaultRootElement();
        if (line - 1 >= root.getElementCount()) {
            return;
        }
        Element el = root.getElement(line - 1);
        try {
            errorTag = h.addHighlight(el.getStartOffset(), el.getEndOffset(),
                    new DefaultHighlighter.DefaultHighlightPainter(new Color(230, 60, 60, 70)));
        } catch (BadLocationException ex) {
            // ignore
        }
    }

    private void jumpToError() {
        if (runner.errorLine < 1) {
            return;
        }
        Element root = ed.getDocument().getDefaultRootElement();
        if (runner.errorLine - 1 < root.getElementCount()) {
            ed.setCaretPosition(root.getElement(runner.errorLine - 1).getStartOffset());
            ed.requestFocusInWindow();
        }
    }

    private void updateStatus() {
        String t;
        Color c;
        if (holding) {
            MiniC.Frame f = runner.c.stack.isEmpty() ? null : runner.c.stack.get(runner.c.stack.size() - 1);
            t = "Stopped at line " + stopLine + (f != null ? " in " + f.name() : "") + " (" + stopWhy + ")  -  F5 continue, F10 / F11 step";
            c = new Color(220, 160, 20);
        } else if (runner.error != null) {
            t = runner.error.startsWith("run: ") ? "Stopped: " + runner.error.substring(5)
                    : "Error " + runner.error + (m.program == runner && runner.compiled ? "  (the robot keeps the last good code)" : "");
            c = new Color(220, 70, 60);
        } else if (!text().equals(applied)) {
            t = "Changed: Apply (Ctrl+Enter) to use it";
            c = new Color(200, 140, 30);
        } else if (m.codeMode == 0) {
            t = "Built-in controller drives (the code is not used)";
            c = Theme.textDim();
        } else {
            t = (m.codeMode == 1 ? "Hooks" : "Full code") + ": running your code";
            c = new Color(40, 160, 90);
        }
        status.setText(t);
        status.setForeground(c);
    }

    // ------------------------------------------------------------------ live read-outs (called by the main timer while shown)
    void refresh(boolean all) {
        syncRun();
        view.repaint();
        if (!all && ++refreshTick % 6 != 0) {
            return;
        }
        if (runner.consoleVersion != shownConsole) {
            shownConsole = runner.consoleVersion;
            StringBuilder sb = new StringBuilder();
            for (String s : runner.console) {
                sb.append(s).append('\n');
            }
            console.setText(sb.toString());
            console.setCaretPosition(console.getDocument().getLength());
        }
        List<String[]> w = new ArrayList<>();
        if (holding && !runner.c.stack.isEmpty()) {
            int sel = Math.max(0, stackList.getSelectedIndex());
            MiniC.Frame f = runner.c.stack.get(Math.max(0, runner.c.stack.size() - 1 - sel));
            w.add(new String[]{"\u25BC " + f.name() + " (line " + f.line + ")", ""});
            w.addAll(f.locals());
            w.add(new String[]{"\u25BC globals", ""});
        }
        w.addAll(runner.watch());
        w.add(new String[]{"\u25BC robot", ""});
        StringBuilder dk = new StringBuilder(), rw = new StringBuilder();
        for (int i = 0; i < m.nSensors; i++) {
            dk.append(i > 0 ? " " : "").append(m.codeDark(i) >= 0.5 ? '1' : '0');
            rw.append(i > 0 ? ", " : "").append(Math.round(m.codeRaw(i)));
        }
        w.add(new String[]{"isDark(0.." + (m.nSensors - 1) + ")", dk.toString()});
        w.add(new String[]{"raw[]", rw.toString()});
        double lp = m.codeLinePosition();
        w.add(new String[]{"linePosition()", Double.isNaN(lp) ? "NaN (no line)" : MiniC.fmt(lp)});
        w.add(new String[]{"leftCmd(), rightCmd()", MiniC.fmt(m.codePendL()) + ", " + MiniC.fmt(m.codePendR())});
        w.add(new String[]{"millis()", Long.toString((long) Math.floor(m.t * 1000))});
        if (watchM.getRowCount() != w.size()) {
            watchM.setRowCount(0);
            for (String[] r : w) {
                watchM.addRow(r);
            }
        } else {
            for (int i = 0; i < w.size(); i++) {
                for (int k = 0; k < 2; k++) {
                    if (!w.get(i)[k].equals(watchM.getValueAt(i, k))) {
                        watchM.setValueAt(w.get(i)[k], i, k);
                    }
                }
            }
        }
        plot.clear();
        Color[] pal = {new Color(66, 133, 244), new Color(234, 67, 53), new Color(52, 168, 83), new Color(251, 188, 5),
            new Color(171, 71, 188), new Color(0, 172, 193), new Color(255, 112, 67), new Color(158, 157, 36)};
        int k = 0;
        for (CodeRunner.Series s : runner.plots.values()) {
            double[] x = new double[s.count], y = new double[s.count];
            for (int i = 0; i < s.count; i++) {
                x[i] = s.tAt(i);
                y[i] = s.vAt(i);
            }
            plot.add(x, y, pal[k++ % pal.length], s.name + String.format(Locale.ROOT, " = %.2f", s.last), 1.4f, false);
        }
        if (m.codeMode != 0 && m.program == runner) {
            double us = runner.lastSteps * m.codeCyclesPerStep / Math.max(m.clockMHz, 0.001);
            stats.setText(String.format(Locale.ROOT, "loop %d  steps %d (max %d)  ~%.0f us on the MCU  ", runner.loops, runner.lastSteps,
                    runner.maxSteps, us));
        } else {
            stats.setText(" ");
        }
        if (runner.error != null && runner.error.startsWith("run") && gutter.errorLine != runner.errorLine) {
            markError(runner.errorLine);
            updateStatus();
        } else if (runner.error == null && text().equals(applied) && all) {
            updateStatus();
        }
    }

    // ------------------------------------------------------------------ syntax colours
    private static final Pattern TOKENS = Pattern.compile(
            "(//[^\\n]*|/\\*(?s:.*?)\\*/)|(\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])')|(^[ \\t]*#[^\\n]*)"
            + "|\\b(0[xX][0-9a-fA-F]+|\\d+\\.?\\d*(?:[eE][+-]?\\d+)?[fFuUlL]*)\\b|\\b([A-Za-z_]\\w*)\\b", Pattern.MULTILINE);
    private static final java.util.Set<String> KW = new java.util.HashSet<>(java.util.Arrays.asList("if", "else", "while", "for", "do",
            "switch", "case", "default", "break", "continue", "return", "const", "static", "volatile", "true", "false", "HIGH", "LOW"));
    private static final java.util.Set<String> TYPES = new java.util.HashSet<>(java.util.Arrays.asList("int", "long", "short", "char",
            "byte", "word", "unsigned", "signed", "float", "double", "bool", "boolean", "void", "uint8_t", "uint16_t", "uint32_t",
            "int8_t", "int16_t", "int32_t", "size_t"));

    private void highlight() {
        StyledDocument d = ed.getStyledDocument();
        String s = text();
        boolean dark = Theme.isDark();
        SimpleAttributeSet base = new SimpleAttributeSet();
        StyleConstants.setForeground(base, ed.getForeground());
        StyleConstants.setBold(base, false);
        StyleConstants.setItalic(base, false);
        d.setCharacterAttributes(0, s.length(), base, true);
        Color cCom = dark ? new Color(120, 135, 125) : new Color(110, 125, 115), cStr = dark ? new Color(214, 157, 133) : new Color(163, 21, 21);
        Color cNum = dark ? new Color(181, 206, 168) : new Color(9, 134, 88), cKw = dark ? new Color(86, 156, 214) : new Color(0, 0, 200);
        Color cTy = dark ? new Color(78, 201, 176) : new Color(38, 127, 153), cPre = dark ? new Color(197, 134, 192) : new Color(140, 40, 140);
        Color cFn = dark ? new Color(220, 220, 170) : new Color(121, 94, 38), cConst = dark ? new Color(156, 220, 254) : new Color(0, 112, 193);
        Matcher mt = TOKENS.matcher(s);
        while (mt.find()) {
            Color c = null;
            boolean bold = false, ital = false;
            if (mt.group(1) != null) {
                c = cCom;
                ital = true;
            } else if (mt.group(2) != null) {
                c = cStr;
            } else if (mt.group(3) != null) {
                c = cPre;
            } else if (mt.group(4) != null) {
                c = cNum;
            } else if (mt.group(5) != null) {
                String w = mt.group(5);
                if (KW.contains(w)) {
                    c = cKw;
                    bold = true;
                } else if (TYPES.contains(w)) {
                    c = cTy;
                } else if (runner.c.builtins.containsKey(w) || w.equals("print") || w.equals("plot") || w.equals("setup") || w.equals("loop")) {
                    c = cFn;
                } else if (runner.c.constants.containsKey(w) || runner.c.arrays.containsKey(w)) {
                    c = cConst;
                    bold = true;
                }
            }
            if (c != null) {
                SimpleAttributeSet a = new SimpleAttributeSet();
                StyleConstants.setForeground(a, c);
                StyleConstants.setBold(a, bold);
                StyleConstants.setItalic(a, ital);
                d.setCharacterAttributes(mt.start(), mt.end() - mt.start(), a, false);
            }
        }
    }

    void themeChanged() {
        highlight();
        gutter.repaint();
    }

    // ------------------------------------------------------------------ line numbers
    private final class Gutter extends JComponent {

        int errorLine = -1, stopLine = -1;

        @Override
        public Dimension getPreferredSize() {
            int n = ed.getDocument().getDefaultRootElement().getElementCount();
            FontMetrics fm = getFontMetrics(ed.getFont());
            return new Dimension(fm.stringWidth(String.valueOf(Math.max(n, 99))) + 30, ed.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setColor(Theme.isDark() ? new Color(40, 44, 50) : new Color(236, 238, 240));
            Rectangle clip = g.getClipBounds();
            g.fillRect(clip.x, clip.y, clip.width, clip.height);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(ed.getFont());
            FontMetrics fm = g.getFontMetrics();
            Element root = ed.getDocument().getDefaultRootElement();
            int w = getWidth();
            for (int i = 0; i < root.getElementCount(); i++) {
                try {
                    Rectangle r = ed.modelToView(root.getElement(i).getStartOffset());
                    if (r == null || r.y + r.height < clip.y) {
                        continue;
                    }
                    if (r.y > clip.y + clip.height) {
                        break;
                    }
                    String s = String.valueOf(i + 1);
                    g.setColor(i + 1 == errorLine ? new Color(230, 70, 60) : Theme.textDim());
                    g.drawString(s, w - 6 - fm.stringWidth(s), r.y + fm.getAscent());
                    int cy = r.y + r.height / 2;
                    g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    String bc = breakpoints.get(i + 1);
                    if (bc != null) {
                        g.setColor(bc.isEmpty() ? new Color(215, 50, 50) : new Color(235, 130, 30));
                        g.fillOval(3, cy - 5, 11, 11);
                        if (!bc.isEmpty()) {
                            g.setColor(Color.WHITE);
                            g.fillRect(6, cy - 1, 5, 2);
                        }
                    }
                    if (i + 1 == stopLine) {
                        g.setColor(new Color(250, 190, 30));
                        g.fillPolygon(new int[]{2, 12, 2}, new int[]{cy - 6, cy, cy + 6}, 3);
                        g.setColor(new Color(120, 80, 0));
                        g.drawPolygon(new int[]{2, 12, 2}, new int[]{cy - 6, cy, cy + 6}, 3);
                    }
                } catch (BadLocationException ex) {
                    break;
                }
            }
        }
    }

    // ------------------------------------------------------------------ workspace
    /** Lines for the workspace file. */
    List<String> lines() {
        List<String> l = new ArrayList<>();
        l.add("codemode " + m.codeMode);
        l.add("codetext " + Base64.getEncoder().encodeToString(text().getBytes(StandardCharsets.UTF_8)));
        if (!breakpoints.isEmpty()) {
            StringBuilder b = new StringBuilder();
            for (java.util.Map.Entry<Integer, String> e : breakpoints.entrySet()) {
                b.append(e.getKey()).append('\t').append(e.getValue()).append('\n');
            }
            l.add("codebp " + Base64.getEncoder().encodeToString(b.toString().getBytes(StandardCharsets.UTF_8)));
        }
        if (!applied.equals(text())) {
            l.add("codeapplied " + Base64.getEncoder().encodeToString(applied.getBytes(StandardCharsets.UTF_8)));
        }
        return l;
    }

    static boolean isCodeLine(String t) {
        return t.startsWith("codemode ") || t.startsWith("codetext ") || t.startsWith("codeapplied ") || t.startsWith("codebp ");
    }

    /** Back from a workspace file (only if it has code lines). */
    void restore(List<String> lines) {
        String txt = null, app = null, bps = null;
        int md = -1;
        for (String t : lines) {
            try {
                if (t.startsWith("codemode ")) {
                    md = Integer.parseInt(t.substring(9).trim());
                } else if (t.startsWith("codetext ")) {
                    txt = new String(Base64.getDecoder().decode(t.substring(9).trim()), StandardCharsets.UTF_8);
                } else if (t.startsWith("codebp ")) {
                    bps = new String(Base64.getDecoder().decode(t.substring(7).trim()), StandardCharsets.UTF_8);
                } else if (t.startsWith("codeapplied ")) {
                    app = new String(Base64.getDecoder().decode(t.substring(12).trim()), StandardCharsets.UTF_8);
                }
            } catch (IllegalArgumentException ex) {
                // a damaged line: keep the current code
            }
        }
        if (txt == null) {
            return;
        }
        compileInto(app != null ? app : txt, false);
        applied = app != null ? app : txt;
        ed.setText(txt);
        breakpoints.clear();
        if (bps != null) {
            for (String b : bps.split("\n")) {
                int tab = b.indexOf('\t');
                try {
                    breakpoints.put(Integer.parseInt(tab < 0 ? b.trim() : b.substring(0, tab)), tab < 0 ? "" : b.substring(tab + 1));
                } catch (NumberFormatException ex) {
                    // skip
                }
            }
        }
        recompileConditions();
        ed.setCaretPosition(0);
        undo.discardAllEdits();
        m.codeMode = Math.max(0, Math.min(2, md));
        syncing = true;
        mode.setSelectedIndex(m.codeMode);
        syncing = false;
        highlight();
        updateStatus();
        updateDebugButtons();
    }

    String appliedText() {
        return applied;
    }

    CodeRunner runner() {
        return runner;
    }

    static int keyMask() {
        return KeyEvent.CTRL_DOWN_MASK;
    }
}
