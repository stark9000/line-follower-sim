package linefollowersim;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import java.awt.*;
import java.awt.geom.Rectangle2D;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;

/**
 * The Robot editor tab: the chassis plate, the sensor board (sensors, IR pairs, LEDs) and the parts with
 * their weights, drawn and edited like in a drawing program. The robot only changes when the draft is
 * applied; the draft itself is kept in the workspace and in an autosave file, finished or not.
 */
final class RobotEditor extends JPanel {

    final Model m;
    private final ControlPanel cp;
    RobotDraft draft;
    final RobotCanvas canvas;
    final TraceLayer trace = new TraceLayer();
    private final JSlider picAlpha = new JSlider(5, 100, 60);
    private final JSpinner picRot = num(0, -360, 360, 0.5), traceTol = num(22, 1, 100, 1), traceClose = num(3, 0, 20, 1),
            traceDetail = num(1.0, 0.05, 20, 0.1);
    private final JCheckBox picShow = new JCheckBox("Show", true), picFlip = new JCheckBox("Flip"), dwgShow = new JCheckBox("Show", true);
    private final JCheckBox onPlate = new JCheckBox("Sensors sit on the plate (one-piece PCB, no separate board)");
    private final JComboBox<String> boardMode = new JComboBox<>(new String[]{"Follows the sensors", "Editable corners"});
    private final JLabel traceInfo = new JLabel(" ");
    private final Deque<List<String>> undo = new ArrayDeque<>(), redo = new ArrayDeque<>();
    private final JToggleButton[] toolBtn = new JToggleButton[RobotCanvas.TOOL_NAMES.length];
    private final JLabel status = new JLabel(" "), cursor = new JLabel(" "), state = new JLabel(" "), zoomLbl = new JLabel("100 %");
    private final JLabel selInfo = new JLabel(" "), chassisInfo = new JLabel(" "), sensorInfo = new JLabel(" "), ledInfo = new JLabel(" ");
    private final JLabel massInfo = new JLabel(" ");
    private final JTextArea checks = new JTextArea(6, 24);
    private final javax.swing.Timer autosave;
    private boolean syncing;
    private String appliedText;                                    // the draft as it was last applied / taken from the robot
    private final PartTable partModel = new PartTable();
    private final JTable partTable = new JTable(partModel);
    private final JButton recoverBtn = UI.button("Recover the autosaved draft", "open", "Bring back the draft that was autosaved last time");

    // fields of the side panel
    private final JComboBox<String> gridCombo = new JComboBox<>(new String[]{"0.5", "1", "2", "2.5", "5", "10"});
    private final JCheckBox showGrid = new JCheckBox("Grid", true), dots = new JCheckBox("Dots"), snapGrid = new JCheckBox("Snap to grid", true);
    private final JCheckBox snapPts = new JCheckBox("Snap to points", true), sym = new JCheckBox("Mirror edits (both sides alike)", true);
    private final JComboBox<String> layerCombo = new JComboBox<>(new String[]{"All layers", "Chassis", "Sensors", "LEDs", "Parts", "Caster", "Board outline"});
    private final JCheckBox isolate = new JCheckBox("Isolate (dim the rest)");
    private final JSpinner selX = num(0, -2000, 2000, 0.5), selY = num(0, -2000, 2000, 0.5), scW = num(100, 1, 1000, 5), scH = num(100, 1, 1000, 5);
    private final JCheckBox scLock = new JCheckBox("Keep proportions", true);
    private final JSpinner rotDeg = num(90, -360, 360, 5);
    private final JComboBox<String> startCombo = new JComboBox<>(new String[]{"Rectangle", "Rounded rectangle", "Circle", "T shape", "Arrow"});
    private final JSpinner stW = num(90, 10, 1000, 1), stF = num(60, 0, 1000, 1), stR = num(40, 0, 1000, 1), mergeTol = num(0.5, 0.05, 20, 0.1);
    private final JSpinner sCount = num(8, 1, Model.MAXS, 1), sWidth = num(70, 0, 1000, 1), sPitch = num(10, 0.5, 100, 0.5);
    private final JSpinner sHeight = num(10, 0.5, 40, 0.5);
    private final JSpinner sRef = num(60, 0, 1000, 1), sAngle = num(25, -60, 60, 1), sRadius = num(120, 5, 5000, 5), sInset = num(4, 0, 100, 0.5);
    private final JComboBox<String> sKind = new JComboBox<>(new String[]{"Straight line", "V / arrow", "Arc (by width)",
        "Along the chassis front", "Arc by angle (anchor)", "Swept wings"});
    private final JSpinner sArc = num(200, 10, 340, 5), sCentre = num(40, 0, 1000, 1), stem = num(0, 0, 200, 1);
    private final JRadioButton byWidth = new JRadioButton("By width", true), byPitch = new JRadioButton("By pitch");
    private final JComboBox<String> pairOrient = new JComboBox<>(new String[]{"Side by side (across)", "One behind the other"});
    private final JSpinner pairGap = num(0, 0, 20, 0.5), margin = num(3, 0.5, 30, 0.5), holeDia = num(3.2, 1, 8, 0.1), overlap = num(8, 0, 100, 1);
    private final JCheckBox pairAlt = new JCheckBox("Every other pair turned round"), joined = new JCheckBox("Screwed to the chassis (holes)", true);
    private final JCheckBox customLeds = new JCheckBox("Use these LEDs (not the Hardware tab placement)");
    private final JComboBox<String> ledKind = new JComboBox<>(new String[]{"Between the sensors", "In front of each sensor", "Behind each sensor",
        "Both sides of each sensor"});
    private final JSpinner ledOff = num(5, 0, 50, 0.5), plateT = num(3, 0.5, 20, 0.5), casterSp = num(-30, -500, 500, 1);
    private final JComboBox<String> material = new JComboBox<>(new String[]{"Acrylic 1.19 g/cm³", "PCB / FR4 1.85 g/cm³",
        "Aluminium 2.70 g/cm³", "Plywood 0.60 g/cm³", "PLA print 1.24 g/cm³ (100 %)", "PLA print 0.50 g/cm³ (25 % infill)"});
    private static final double[] DENSITY = {1.19, 1.85, 2.70, 0.60, 1.24, 0.50};
    private final JCheckBox useParts = new JCheckBox("Weight and centre of mass from the parts");
    private final JCheckBox autoLed = new JCheckBox("LEDs follow", true);

    RobotEditor(Model m, ControlPanel cp) {
        super(new BorderLayout());
        this.m = m;
        this.cp = cp;
        draft = RobotDraft.from(m);
        appliedText = String.join("\n", draft.lines());
        canvas = new RobotCanvas(this);
        add(toolbar(), BorderLayout.NORTH);
        add(tools(), BorderLayout.WEST);
        add(canvas, BorderLayout.CENTER);
        JScrollPane side = UI.scroll(sidePanel());
        side.setPreferredSize(new Dimension(352, 400));
        add(side, BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout(10, 0));
        bottom.setBorder(new EmptyBorder(2, 8, 2, 8));
        bottom.add(cursor, BorderLayout.WEST);
        bottom.add(status, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        autosave = new javax.swing.Timer(1500, e -> writeAutosave());
        autosave.setRepeats(false);
        setTool(RobotCanvas.SELECT);
        syncFields();
        cp.robotLayoutChanged = () -> takeFromRobot(false);              // only after opening a workspace
        cp.draftLines = () -> {
            List<String> l = new ArrayList<>(draft.lines());
            l.addAll(trace.lines());
            return l;
        };
        cp.draftRestore = lines -> {
            trace.from(lines);
            syncTrace();
            RobotDraft d = RobotDraft.parse(lines);
            if (d != null) {
                draft = d;
                undo.clear();
                redo.clear();
                canvas.sel.clear();
                syncFields();
                changed();
            }
        };
        File af = autosaveFile();
        recoverBtn.setVisible(af.isFile());
        if (af.isFile()) {
            recoverBtn.setToolTipText("Autosaved " + new java.util.Date(af.lastModified()));
        }
        SwingUtilities.invokeLater(canvas::fit);
    }

    // ------------------------------------------------------------------ layout
    private JComponent toolbar() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 3));
        JButton nw = UI.button("New", "new", "Start a new robot from scratch (Undo brings this one back)");
        nw.addActionListener(e -> newRobot());
        JButton rs = UI.button("Reset", "reset_all", "Reset the editor only (the simulator's Reset all does not touch it)");
        JPopupMenu rm = new JPopupMenu();
        JMenuItem r1 = new JMenuItem("View and snapping back to the defaults");
        JMenuItem r2 = new JMenuItem("Draft back to the default robot (undo brings it back)");
        JMenuItem r3 = new JMenuItem("Everything in the editor (view, draft, picture and drawing)");
        r1.addActionListener(e -> resetView());
        r2.addActionListener(e -> resetDraftToDefault());
        r3.addActionListener(e -> {
            if (JOptionPane.showConfirmDialog(this, "Reset the whole editor: view, draft, picture and drawing?\nThe simulated robot "
                    + "stays as it is; Undo brings the draft back.", "Reset the editor", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                resetView();
                trace.img = null;
                trace.dwg = null;
                trace.dwgPath = null;
                syncTrace();
                resetDraftToDefault();
            }
        });
        rm.add(r1);
        rm.add(r2);
        rm.add(r3);
        rs.addActionListener(e -> rm.show(rs, 0, rs.getHeight()));
        JButton und = UI.button("Undo", "undo", "Undo (Ctrl+Z)"), red = UI.button("Redo", "redo", "Redo (Ctrl+Y)");
        und.addActionListener(e -> undo());
        red.addActionListener(e -> redo());
        JButton zin = UI.button(null, "zoom_in", "Zoom in (+, mouse wheel)"), zout = UI.button(null, "zoom_out", "Zoom out (-)");
        JButton fit = UI.button("Fit", "fit", "Show the whole robot (F)");
        zin.addActionListener(e -> canvas.zoomAt(new Point(canvas.getWidth() / 2, canvas.getHeight() / 2), 1.25));
        zout.addActionListener(e -> canvas.zoomAt(new Point(canvas.getWidth() / 2, canvas.getHeight() / 2), 0.8));
        fit.addActionListener(e -> canvas.fit());
        JButton apply = UI.button("Apply to robot", "saved", "Use this draft for the simulated robot (Hardware, Motors tabs follow)");
        JButton revert = UI.button("Take from robot", "reset_robot", "Throw the draft away and start again from the robot as it is now");
        apply.addActionListener(e -> applyDraft());
        revert.addActionListener(e -> takeFromRobot(true));
        recoverBtn.addActionListener(e -> recoverAutosave());
        zoomLbl.setPreferredSize(new Dimension(52, 20));
        for (JComponent c : new JComponent[]{nw, rs, und, red, new JSeparator(SwingConstants.VERTICAL), zout, zoomLbl, zin, fit,
            new JSeparator(SwingConstants.VERTICAL), apply, revert, recoverBtn, state}) {
            if (c instanceof JSeparator) {
                c.setPreferredSize(new Dimension(6, 22));
            }
            p.add(c);
        }
        return p;
    }

    private JComponent tools() {
        JPanel p = new JPanel(new GridLayout(0, 1, 0, 3));
        p.setBorder(new EmptyBorder(6, 4, 6, 4));
        String[] icons = {"tool_select", "tool_polyline", "tab_sensors", "bulb", "tab_hardware", "measure", "tool_pan", "tool_freehand",
            "image"};
        String[] tips = {"Click to pick, drag to move (Shift: straight, Alt: no snap), drag a box to pick several; "
            + "handles scale the selection; double-click the chassis edge to add a corner; Delete removes",
            "Click on an edge of the chassis to add a corner there (mirrored on the other side when Mirror edits is on)",
            "Click to place a sensor (mirrored too)", "Click to place an LED", "Drag a box to add a part (battery, board ...)",
            "Drag to measure a distance (Shift: 45 degree steps)", "Drag to move the view (also: middle button or Space)",
            "Click inside the board on the picture: its outline becomes the chassis (or click inside a closed shape of the drawing)",
            "Drag to move the picture (or the drawing - see Trace from a picture or drawing)"};
        ButtonGroup bg = new ButtonGroup();
        for (int i = 0; i < icons.length; i++) {
            int t = i;
            JToggleButton b = new JToggleButton(Icons.get(icons[i]));
            b.setToolTipText("<html><b>" + RobotCanvas.TOOL_NAMES[i] + "</b><br>" + tips[i] + "</html>");
            b.setFocusable(false);
            b.setMargin(new Insets(4, 4, 4, 4));
            b.addActionListener(e -> setTool(t));
            bg.add(b);
            toolBtn[i] = b;
            p.add(b);
        }
        JPanel wrap = new JPanel(new BorderLayout());
        wrap.add(p, BorderLayout.NORTH);
        return wrap;
    }

    private JPanel sideCol;

    private JPanel sidePanel() {
        JPanel col = new JPanel();
        sideCol = col;
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBorder(new EmptyBorder(4, 4, 4, 4));

        JPanel view = UI.group("View and snapping");
        gridCombo.setEditable(true);
        gridCombo.setSelectedItem("5");
        gridCombo.addActionListener(e -> {
            try {
                canvas.grid = Math.max(0.05, Double.parseDouble(String.valueOf(gridCombo.getSelectedItem()).trim().replace(',', '.')));
            } catch (NumberFormatException ex) {
                gridCombo.setSelectedItem(fmt(canvas.grid));
            }
            canvas.repaint();
        });
        view.add(fx(row("Grid size (mm)", gridCombo)));
        view.add(fx(flow(showGrid, dots)));
        view.add(fx(flow(snapGrid, snapPts)));
        view.add(fx(flow(sym)));
        view.add(fx(row("Pick from", layerCombo)));
        view.add(fx(flow(isolate)));
        showGrid.addActionListener(e -> {
            canvas.showGrid = showGrid.isSelected();
            canvas.repaint();
        });
        dots.addActionListener(e -> {
            canvas.dotGrid = dots.isSelected();
            canvas.repaint();
        });
        snapGrid.addActionListener(e -> canvas.snapGrid = snapGrid.isSelected());
        snapPts.addActionListener(e -> canvas.snapPoints = snapPts.isSelected());
        sym.addActionListener(e -> canvas.symmetric = sym.isSelected());
        layerCombo.addActionListener(e -> {
            canvas.activeLayer = layerCombo.getSelectedIndex() - 1;
            canvas.sel.clear();
            selectionChanged();
            canvas.repaint();
        });
        isolate.addActionListener(e -> {
            canvas.isolate = isolate.isSelected();
            canvas.repaint();
        });
        view.add(hint("Wheel: zoom at the cursor. Middle button or Space + drag: pan. Arrow keys nudge the selection by "
                + "one grid step (Shift x10, Alt 0.1 mm). Alt while dragging: no snapping. Right-click or Esc: back to "
                + "Select / clear the selection. Ctrl+A selects everything you can pick."));
        col.add(view);
        col.add(traceGroup());

        JPanel selP = UI.group("Selection");
        selP.add(fx(selInfo));
        JButton setPos = UI.button("Move to", null, "Put the centre of the selection here (x ahead of the axle, y to the right)");
        setPos.addActionListener(e -> transformSel(b -> new double[]{1, 1, num(selX) - b.getCenterX(), num(selY) - b.getCenterY()}));
        en(setPos, () -> canvas.sel.size() >= 1);
        en(selX, () -> canvas.sel.size() >= 1);
        en(selY, () -> canvas.sel.size() >= 1);
        selP.add(fx(flow(new JLabel("x"), selX, new JLabel("y"), selY, setPos)));
        JButton doScale = UI.button("Scale", null, "Scale the selection about its centre (width = across, length = along)");
        doScale.addActionListener(e -> {
            double w = num(scW) / 100, h = scLock.isSelected() ? w : num(scH) / 100;
            transformSel(b -> new double[]{h, w, 0, 0});
        });
        en(doScale, () -> canvas.sel.size() >= 1);
        en(scW, () -> canvas.sel.size() >= 1);
        en(scH, () -> canvas.sel.size() >= 1 && !scLock.isSelected());
        selP.add(fx(flow(new JLabel("W %"), scW, new JLabel("L %"), scH, doScale)));
        selP.add(fx(flow(scLock)));
        JButton doRot = UI.button("Rotate", null, "Turn the selection about its centre (+ = clockwise seen from above)");
        doRot.addActionListener(e -> rotateSel(num(rotDeg)));
        JButton mirLR = UI.button("Mirror ↔", null, "Mirror the selection left / right"), mirFB = UI.button("Mirror ↕", null,
                "Mirror the selection front / back");
        mirLR.addActionListener(e -> transformSel(b -> new double[]{1, -1, 0, 0}));
        mirFB.addActionListener(e -> transformSel(b -> new double[]{-1, 1, 0, 0}));
        for (JComponent c : new JComponent[]{doRot, rotDeg, mirLR, mirFB}) {
            en(c, () -> canvas.sel.size() >= 1);
        }
        selP.add(fx(flow(rotDeg, new JLabel("deg"), doRot)));
        selP.add(fx(flow(mirLR, mirFB)));
        JButton aL = small("Left", "Line the selection up on its leftmost item"), aC = small("Centre", "Centre across"),
                aR = small("Right", "Line up on the rightmost"), aF = small("Front", "Line up on the front-most"),
                aM = small("Middle", "Centre along"), aB = small("Back", "Line up on the rearmost");
        aL.addActionListener(e -> align(1, -1));
        aC.addActionListener(e -> align(1, 0));
        aR.addActionListener(e -> align(1, 1));
        aF.addActionListener(e -> align(0, 1));
        aM.addActionListener(e -> align(0, 0));
        aB.addActionListener(e -> align(0, -1));
        for (JComponent c : new JComponent[]{aL, aC, aR, aF, aM, aB}) {
            en(c, () -> canvas.sel.size() >= 2);
        }
        selP.add(fx(flow(new JLabel("Align"), aL, aC, aR)));
        selP.add(fx(flow(new JLabel("        "), aF, aM, aB)));
        JButton dAcross = small("Space evenly across", "Equal gaps left to right between the first and the last selected");
        JButton dAlong = small("along", "Equal gaps front to back");
        dAcross.addActionListener(e -> distribute(1));
        dAlong.addActionListener(e -> distribute(0));
        en(dAcross, () -> canvas.sel.size() >= 3);
        en(dAlong, () -> canvas.sel.size() >= 3);
        selP.add(fx(flow(dAcross, dAlong)));
        JButton del = UI.button("Delete", "delete", "Remove the selection (Delete key)");
        JButton all = UI.button("Select all", null, "Ctrl+A");
        del.addActionListener(e -> deleteSelection());
        all.addActionListener(e -> canvas.selectAll());
        en(del, () -> canvas.sel.size() >= 1);
        selP.add(fx(flow(all, del)));
        col.add(selP);

        JPanel ch = UI.group("Chassis plate");
        ch.add(fx(chassisInfo));
        ch.add(fx(row("Start from", startCombo)));
        ch.add(fx(flow(new JLabel("width"), stW, new JLabel("front"), stF)));
        ch.add(fx(flow(new JLabel("rear"), stR, startBtn())));
        JButton symL = small("Copy left half", "Rebuild the right side as a mirror of the left"),
                symR = small("Copy right half", "Rebuild the left side as a mirror of the right");
        symL.addActionListener(e -> {
            pushUndo();
            draft.makeSymmetric(-1);
            changed();
            committed();
        });
        symR.addActionListener(e -> {
            pushUndo();
            draft.makeSymmetric(1);
            changed();
            committed();
        });
        ch.add(fx(flow(new JLabel("Symmetric:"), symL, symR)));
        JButton merge = small("Merge close corners", "Remove corners closer than this to the next one, and corners on a straight edge");
        merge.addActionListener(e -> {
            pushUndo();
            int n = draft.mergeClose(draft.chassis, num(mergeTol));
            canvas.sel.clear();
            changed();
            committed();
            toast(n + " corner(s) removed");
        });
        ch.add(fx(flow(merge, mergeTol, new JLabel("mm"))));
        JButton imp = UI.button("Import outline (DXF / SVG)...", "open", "Read a plate outline drawn in a CAD program");
        imp.addActionListener(e -> importOutline());
        en(symL, () -> draft.chassis.size() >= 3);
        en(symR, () -> draft.chassis.size() >= 3);
        en(merge, () -> draft.chassis.size() > 3);
        JButton resC = UI.button("Reset", "reset_small", "Back to the shape set in the Hardware tab");
        resC.addActionListener(e -> {
            pushUndo();
            RobotDraft f = RobotDraft.from(robotWithShape());
            draft.chassis.clear();
            draft.chassis.addAll(f.chassis);
            changed();
            committed();
        });
        ch.add(fx(flow(imp)));
        ch.add(fx(flow(resC, new JLabel("caster at"), casterSp, new JLabel("mm"))));
        en(casterSp, () -> draft.wheelLayout == 0);
        casterSp.addChangeListener(e -> {
            if (!syncing) {
                pushUndo();
                draft.casterX = num(casterSp);
                changed();
            }
        });
        col.add(ch);
        col.add(wheelGroup());

        JPanel se = UI.group("Sensors and board");
        se.add(fx(sensorInfo));
        se.add(fx(row("Arrange", sKind)));
        ButtonGroup bw = new ButtonGroup();
        bw.add(byWidth);
        bw.add(byPitch);
        se.add(fx(flow(new JLabel("count"), sCount, new JLabel("ahead of axle"), sRef)));
        se.add(fx(flow(new JLabel("height above floor"), sHeight, new JLabel("mm"))));
        sHeight.setToolTipText("<html>The sensors' height above the floor (the Hardware tab's sensor height).<br>"
                + "Each sensor can sit higher or lower: <b>Heights / pins...</b></html>");
        sHeight.addChangeListener(e -> {
            if (!syncing) {
                pushUndo();
                draft.sensorHeight = num(sHeight);
                changed();
            }
        });
        se.add(fx(flow(byWidth, sWidth, byPitch, sPitch)));
        se.add(fx(flow(new JLabel("V angle"), sAngle, new JLabel("arc radius"), sRadius)));
        se.add(fx(flow(new JLabel("arc angle"), sArc, new JLabel("wings: middle"), sCentre)));
        java.util.function.IntSupplier kind = sKind::getSelectedIndex;
        en(sAngle, () -> kind.getAsInt() == 1 || kind.getAsInt() == 5);
        en(sRadius, () -> kind.getAsInt() == 2 || kind.getAsInt() == 4);
        en(sArc, () -> kind.getAsInt() == 4);
        en(sCentre, () -> kind.getAsInt() == 5);
        en(sInset, () -> kind.getAsInt() == 3);
        en(sRef, () -> kind.getAsInt() != 3);
        en(sWidth, () -> kind.getAsInt() != 4 && byWidth.isSelected());
        en(sPitch, () -> kind.getAsInt() != 4 && byPitch.isSelected());
        en(byWidth, () -> kind.getAsInt() != 4);
        en(byPitch, () -> kind.getAsInt() != 4);
        sKind.addActionListener(e -> updateEnables());
        byWidth.addActionListener(e -> updateEnables());
        byPitch.addActionListener(e -> updateEnables());
        se.add(fx(flow(new JLabel("inset from the front edge"), sInset)));
        JButton fitW = small("Fit the width to the chassis", "Make the row as wide as the plate is at that distance (minus the board margin)");
        fitW.addActionListener(e -> fitWidth());
        se.add(fx(flow(fitW, autoLed)));
        JButton place = UI.button("Place sensors evenly", "tab_sensors", "Replace the sensors with an even row in this shape");
        place.addActionListener(e -> placeSensors());
        JButton resS = UI.button("Reset", "reset_small", "Back to the array set in the Hardware tab");
        resS.addActionListener(e -> {
            pushUndo();
            Model t = robotWithShape();
            draft.sensorsFrom(t);
            changed();
            committed();
        });
        JButton pins = UI.button("Heights / pins...", "tab_mcu", "Each sensor's height above the floor and its MCU pin (Arduino export)");
        pins.addActionListener(e -> editPins());
        se.add(fx(flow(place, resS, pins)));
        se.add(fx(row("IR pair", pairOrient)));
        se.add(fx(flow(new JLabel("emitter - receiver"), pairGap, new JLabel("mm"))));
        se.add(fx(flow(pairAlt)));
        se.add(fx(flow(new JLabel("board margin"), margin, new JLabel("mm"))));
        se.add(fx(flow(joined)));
        se.add(fx(flow(onPlate)));
        se.add(fx(row("Board outline", boardMode)));
        boardMode.addActionListener(e -> {
            if (syncing) {
                return;
            }
            pushUndo();
            if (boardMode.getSelectedIndex() == 1) {                   // start from the automatic outline
                if (!draft.customBoard || draft.boardPts.size() < 3) {
                    draft.boardPts.clear();
                    draft.boardPts.addAll(RobotDraft.outlineOf(draft.autoBoard()));
                    draft.mergeClose(draft.boardPts, 0.3);
                }
                draft.customBoard = true;
                toast("The board outline is editable: drag its corners (green), drag an edge, add corners with the corner tool");
            } else {
                draft.customBoard = false;
            }
            changed();
            committed();
        });
        onPlate.addActionListener(e -> pairCb2());
        JButton snapB = UI.button("Join to the chassis front", "tool_robot", "Move the whole array so the board overlaps the plate's front edge");
        snapB.addActionListener(e -> joinBoard());
        se.add(fx(flow(new JLabel("holes"), holeDia, new JLabel("overlap"), overlap)));
        se.add(fx(flow(new JLabel("stem to the chassis (0: none)"), stem, new JLabel("mm wide"))));
        se.add(fx(flow(snapB)));
        en(pairAlt, () -> draft.pairGap > 0);
        en(pairOrient, () -> draft.pairGap > 0);
        en(joined, () -> !draft.boardOnPlate);
        en(holeDia, () -> !draft.boardOnPlate && draft.boardJoined);
        en(overlap, () -> !draft.boardOnPlate && draft.boardJoined);
        en(snapB, () -> !draft.boardOnPlate && draft.chassis.size() >= 3 && !draft.sensors.isEmpty());
        en(stem, () -> !draft.boardOnPlate && !draft.customBoard);
        en(margin, () -> !draft.boardOnPlate && !draft.customBoard);
        en(boardMode, () -> !draft.boardOnPlate);
        en(pins, () -> !draft.sensors.isEmpty());
        Runnable pairCb = () -> {
            if (!syncing) {
                pushUndo();
                draft.pairOrient = pairOrient.getSelectedIndex();
                draft.pairGap = num(pairGap);
                draft.pairAlternate = pairAlt.isSelected();
                draft.boardMargin = num(margin);
                draft.boardJoined = joined.isSelected();
                draft.holeDia = num(holeDia);
                draft.stemWidth = num(stem);
                draft.stemOverlap = num(overlap);
                changed();
            }
        };
        pairOrient.addActionListener(e -> pairCb.run());
        pairAlt.addActionListener(e -> pairCb.run());
        joined.addActionListener(e -> pairCb.run());
        pairGap.addChangeListener(e -> pairCb.run());
        margin.addChangeListener(e -> pairCb.run());
        holeDia.addChangeListener(e -> pairCb.run());
        stem.addChangeListener(e -> pairCb.run());
        overlap.addChangeListener(e -> pairCb.run());
        se.add(hint("Sensors are counted <b>left to right</b> (the numbers on the drawing) - that is how the controller weights "
                + "them, wherever they sit. <b>Arc by angle</b>: over 180 degrees the ends curl back (anchor boards). "
                + "<b>Swept wings</b>: a straight middle part, then each wing swept back by the V angle. <b>Stem</b>: a "
                + "neck from the array back to the plate, for T / anchor boards held far ahead. <b>IR pair</b>: with a gap set, each sensor is drawn as emitter (blue) and receiver "
                + "(black) and the crosstalk is worked out from the real distances between one sensor's emitter and the "
                + "others' receivers (Sensors tab: crosstalk). Turning every other pair round puts emitters next to emitters."));
        col.add(se);

        JPanel le = UI.group("LEDs");
        le.add(fx(ledInfo));
        le.add(fx(flow(customLeds)));
        le.add(fx(row("Place", ledKind)));
        JButton placeL = UI.button("Place LEDs", "bulb", "Put LEDs at even spots next to the sensors");
        placeL.addActionListener(e -> {
            pushUndo();
            draft.placeLeds(ledKind.getSelectedIndex(), num(ledOff));
            syncFields();
            changed();
            committed();
        });
        JButton resL = UI.button("Reset", "reset_small", "Back to the LED placement of the Hardware tab");
        resL.addActionListener(e -> {
            pushUndo();
            Model t = robotWithShape();
            t.customLeds = false;
            draft.ledsFrom(t);
            draft.customLeds = false;
            syncFields();
            changed();
            committed();
        });
        le.add(fx(flow(new JLabel("offset"), ledOff, new JLabel("mm"), placeL, resL)));
        en(placeL, () -> !draft.sensors.isEmpty());
        en(ledKind, () -> !draft.sensors.isEmpty());
        en(ledOff, () -> !draft.sensors.isEmpty() && ledKind.getSelectedIndex() != 0);
        ledKind.addActionListener(e -> updateEnables());
        customLeds.addActionListener(e -> {
            if (!syncing) {
                pushUndo();
                draft.customLeds = customLeds.isSelected();
                changed();
            }
        });
        le.add(hint("The LEDs only shine when the Hardware tab's <b>White LEDs</b> are on (colour, power and floor light / "
                + "indicator are set there). Indicator mode: each LED lights with the sensor it was placed for."));
        col.add(le);

        JPanel pa = UI.group("Parts and weight");
        pa.add(fx(flow(useParts)));
        partTable.setRowHeight(20);
        partTable.setFillsViewportHeight(true);
        partTable.getColumnModel().getColumn(0).setPreferredWidth(150);
        partTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        partTable.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                int r = partTable.getSelectedRow();
                if (r >= 0) {
                    canvas.sel.clear();
                    canvas.sel.add(RobotCanvas.key(RobotCanvas.L_PART, r));
                    selectionChanged();
                    canvas.repaint();
                    if (e.getClickCount() == 2) {
                        editPart(r);
                    }
                }
            }
        });
        JScrollPane ps = new JScrollPane(partTable);
        ps.setPreferredSize(new Dimension(280, 120));
        ps.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        ps.setAlignmentX(Component.LEFT_ALIGNMENT);
        pa.add(ps);
        JButton addP = small("Add...", "Add a part"), editP = small("Edit...", "Change the selected part"), delP = small("Remove", "Remove the selected part");
        JButton defP = small("Typical parts", "Battery, MCU, driver, motors, wheels, board, caster with usual weights");
        addP.addActionListener(e -> {
            pushUndo();
            draft.parts.add(new Model.Part("Part " + (draft.parts.size() + 1), 4, draft.plateGeometry()[1], 0, 20, 20,
                    draft.wheelDia / 2 + draft.plateThick + 5, 10));
            draft.useParts = true;
            changed();
            editPart(draft.parts.size() - 1);
        });
        editP.addActionListener(e -> {
            if (partTable.getSelectedRow() >= 0) {
                editPart(partTable.getSelectedRow());
            }
        });
        delP.addActionListener(e -> {
            int r = partTable.getSelectedRow();
            if (r >= 0) {
                pushUndo();
                draft.parts.remove(r);
                canvas.sel.clear();
                changed();
                committed();
            }
        });
        defP.addActionListener(e -> {
            pushUndo();
            Model t = robotWithShape();
            t.customOutline.clear();
            for (RobotDraft.Pt p : draft.chassis) {
                t.customOutline.add(new double[]{p.x, p.y});
            }
            t.chassisShape = 3;
            t.defaultParts();
            draft.parts.clear();
            for (Model.Part p : t.parts) {
                draft.parts.add(p.copy());
            }
            draft.useParts = true;
            syncFields();
            changed();
            committed();
        });
        pa.add(fx(flow(addP, editP, delP, defP)));
        en(editP, () -> partTable.getSelectedRow() >= 0);
        en(delP, () -> partTable.getSelectedRow() >= 0);
        partTable.getSelectionModel().addListSelectionListener(e -> updateEnables());
        pa.add(fx(row("Plate", material)));
        pa.add(fx(flow(new JLabel("thickness"), plateT, new JLabel("mm"))));
        pa.add(fx(massInfo));
        useParts.addActionListener(e -> {
            if (!syncing) {
                pushUndo();
                draft.useParts = useParts.isSelected();
                changed();
            }
        });
        material.addActionListener(e -> {
            if (!syncing) {
                pushUndo();
                draft.plateDensity = DENSITY[material.getSelectedIndex()];
                changed();
            }
        });
        plateT.addChangeListener(e -> {
            if (!syncing) {
                pushUndo();
                draft.plateThick = num(plateT);
                changed();
            }
        });
        pa.add(hint("With <b>weight from the parts</b> on, Apply sets the robot's mass, the weight on the wheels (lever rule "
                + "with the caster) and the height of the centre of mass (tipping in fast bends). <b>h</b> = height of the part's "
                + "centre above the floor. Double-click a part to change it; drag its handles to resize."));
        col.add(pa);

        JPanel ck = UI.group("Checks");
        checks.setEditable(false);
        checks.setLineWrap(true);
        checks.setWrapStyleWord(true);
        checks.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        checks.setOpaque(false);
        ck.add(fx(checks));
        col.add(ck);
        return col;
    }

    // ------------------------------------------------------------------ wheels and drive
    private final JComboBox<String> wLayout = new JComboBox<>(new String[]{"2 wheels + caster", "4 wheels, skid steer (all driven)",
        "4 wheels, car: rear drive", "4 wheels, car: all-wheel drive"});
    private final JComboBox<String> wSteerBy = new JComboBox<>(new String[]{"Servo", "DC motor (end stops)"});
    private final JSpinner wBase = num(100, 20, 300, 1), wDia = num(65, 10, 150, 1), wTire = num(10, 2, 60, 1), wAxle = num(80, 20, 400, 1),
            wLock = num(30, 5, 60, 1);

    private JPanel wheelGroup() {
        JPanel wg = UI.group("Wheels and drive");
        wg.add(fx(row("Layout", wLayout)));
        wg.add(fx(flow(new JLabel("track"), wBase, new JLabel("wheel"), wDia, new JLabel("tyre"), wTire)));
        wg.add(fx(flow(new JLabel("axle spacing (4)"), wAxle, new JLabel("steering lock"), wLock)));
        wg.add(fx(row("Steered by (car)", wSteerBy)));
        en(wAxle, () -> wLayout.getSelectedIndex() >= 1);
        en(wLock, () -> wLayout.getSelectedIndex() >= 2);
        en(wSteerBy, () -> wLayout.getSelectedIndex() >= 2);
        Runnable cb = () -> {
            if (!syncing) {
                pushUndo();
                draft.wheelLayout = wLayout.getSelectedIndex();
                draft.steerBy = wSteerBy.getSelectedIndex();
                draft.wheelBase = num(wBase);
                draft.wheelDia = num(wDia);
                draft.tireWidth = num(wTire);
                draft.axleSpacing = num(wAxle);
                draft.steerLock = num(wLock);
                changed();
            }
        };
        wLayout.addActionListener(e -> cb.run());
        wSteerBy.addActionListener(e -> cb.run());
        for (JSpinner s : new JSpinner[]{wBase, wDia, wTire, wAxle, wLock}) {
            s.addChangeListener(e -> cb.run());
        }
        wg.add(hint("<b>Track</b> = left to right wheel centres, <b>axle spacing</b> = front to rear axle (4 wheels; the "
                + "axles sit either side of the reference line x = 0). Orange-edged wheels are driven; a car's front wheels "
                + "are drawn at half lock. Skid steer turns by driving the sides at different speeds; a car steers its front "
                + "wheels (servo or a DC motor to the end stops). Apply sets the Hardware tab's wheels too."));
        return wg;
    }

    private void syncWheels() {
        boolean was = syncing;
        syncing = true;
        wLayout.setSelectedIndex(Math.max(0, Math.min(3, draft.wheelLayout)));
        wSteerBy.setSelectedIndex(Math.max(0, Math.min(1, draft.steerBy)));
        setNum(wBase, draft.wheelBase);
        setNum(wDia, draft.wheelDia);
        setNum(wTire, draft.tireWidth);
        setNum(wAxle, draft.axleSpacing);
        setNum(wLock, draft.steerLock);
        syncing = was;
    }

    /** Start a new robot from scratch (empty, or a simple start), keeping the wheels and the picture. */
    void newRobot() {
        Object[] opts = {"Empty - I draw everything", "A simple start (plate, 8 sensors, parts)", "Cancel"};
        int c = JOptionPane.showOptionDialog(this, "<html>Start a new robot in the editor?<br>The robot in the simulator stays as it is "
                + "until you press Apply.<br>Undo brings the current draft back.</html>", "New robot", JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, opts, opts[0]);
        if (c != 0 && c != 1) {
            return;
        }
        pushUndo();
        RobotDraft n = new RobotDraft();
        n.wheelLayout = draft.wheelLayout;
        n.steerBy = draft.steerBy;
        n.wheelBase = draft.wheelBase;
        n.wheelDia = draft.wheelDia;
        n.tireWidth = draft.tireWidth;
        n.axleSpacing = draft.axleSpacing;
        n.steerLock = draft.steerLock;
        n.spotRadius = draft.spotRadius;
        n.casterX = -Math.max(30, n.wheelDia / 2 + 10);
        if (c == 1) {
            n.chassis.addAll(RobotDraft.startShape(1, 70, Math.max(40, n.wheelDia / 2 + 20), Math.max(40, n.wheelBase - 2 * n.tireWidth)));
            n.sensors.addAll(RobotDraft.placeSensors(0, 8, 70, 80, 0, 0, n.chassis, 0));
            Model t = new Model();
            t.copySettingsFrom(m);
            t.customOutline.clear();
            for (RobotDraft.Pt p : n.chassis) {
                t.customOutline.add(new double[]{p.x, p.y});
            }
            t.chassisShape = 3;
            t.casterPos = n.casterX;
            t.wheelCount = n.wheelLayout == 0 ? 2 : 4;
            t.defaultParts();
            for (Model.Part p : t.parts) {
                n.parts.add(p.copy());
            }
        }
        draft = n;
        canvas.sel.clear();
        syncFields();
        changed();
        committed();
        if (c == 1) {
            canvas.fit();
        }
        toast(c == 0 ? "Empty robot: draw the plate (Start from, Add corner or Trace outline), then place the sensors"
                : "New robot started - change anything, then Apply to robot");
    }

    private void pairCb2() {
        if (!syncing) {
            pushUndo();
            draft.boardOnPlate = onPlate.isSelected();
            changed();
        }
    }

    // ------------------------------------------------------------------ tracing
    private JPanel traceGroup() {
        JPanel tr = UI.group("Trace from a picture or drawing");
        JButton loadP = small("Load picture...", "A photo or scan of the board or plate (PNG, JPG, GIF, BMP), taken straight down");
        JButton remP = small("Remove", "Remove the picture");
        loadP.addActionListener(e -> loadPicture());
        remP.addActionListener(e -> {
            trace.img = null;
            syncTrace();
            canvas.repaint();
        });
        tr.add(fx(flow(loadP, remP, picShow, picFlip)));
        picAlpha.setPreferredSize(new Dimension(120, 22));
        tr.add(fx(flow(new JLabel("opacity"), picAlpha, new JLabel("turn"), picRot)));
        JButton scaleB = small("Set scale", "Click two points a known distance apart on the picture (a ruler, the wheel centres), then type the distance");
        JButton moveP = small("Move picture", "Drag the picture into place (the axle on the dashed line across, the middle on the line up)");
        scaleB.addActionListener(e -> {
            if (trace.img == null) {
                toast("Load a picture first");
                return;
            }
            setTool(RobotCanvas.SCALEPIC);
        });
        moveP.addActionListener(e -> {
            canvas.moveDrawing = false;
            setTool(RobotCanvas.MOVEPIC);
        });
        tr.add(fx(flow(scaleB, moveP)));
        tr.add(fx(flow(new JLabel("auto-trace: colour"), traceTol, new JLabel("%"))));
        tr.add(fx(flow(new JLabel("close gaps"), traceClose, new JLabel("px  detail"), traceDetail, new JLabel("mm"))));
        JButton traceB = UI.button("Trace outline (click the board)", "tool_freehand", "Then click inside the board on the picture");
        traceB.addActionListener(e -> setTool(RobotCanvas.TRACE));
        tr.add(fx(flow(traceB)));
        JButton loadD = small("Load drawing...", "A DXF or SVG of the plate or PCB: shown behind, its corners and hole centres become snap points");
        JButton remD = small("Remove", "Remove the drawing");
        loadD.addActionListener(e -> loadDrawing());
        remD.addActionListener(e -> {
            trace.dwg = null;
            trace.dwgPath = null;
            syncTrace();
            canvas.repaint();
        });
        tr.add(fx(flow(loadD, remD, dwgShow)));
        JButton rotD = small("Turn 90°", "Turn the drawing a quarter turn clockwise"), mirD = small("Mirror", "Mirror the drawing left / right");
        JButton cenD = small("Centre", "Put the middle of the drawing on the axle centre");
        JButton moveD = small("Move", "Drag the drawing into place");
        rotD.addActionListener(e -> {
            trace.dwgRot90 = (trace.dwgRot90 + 1) % 4;
            canvas.repaint();
        });
        mirD.addActionListener(e -> {
            trace.dwgMirror = !trace.dwgMirror;
            canvas.repaint();
        });
        cenD.addActionListener(e -> {
            trace.centreDrawing();
            canvas.repaint();
        });
        moveD.addActionListener(e -> {
            canvas.moveDrawing = true;
            setTool(RobotCanvas.MOVEPIC);
        });
        tr.add(fx(flow(rotD, mirD, cenD, moveD)));
        for (JComponent c : new JComponent[]{remP, picShow, picFlip, picAlpha, picRot, scaleB, moveP, traceTol, traceClose, traceDetail}) {
            en(c, () -> trace.img != null);
        }
        en(traceB, () -> trace.img != null || trace.dwg != null);
        for (JComponent c : new JComponent[]{remD, dwgShow, rotD, mirD, cenD, moveD}) {
            en(c, () -> trace.dwg != null);
        }
        tr.add(fx(traceInfo));
        picShow.addActionListener(e -> {
            trace.picVisible = picShow.isSelected();
            canvas.repaint();
        });
        picFlip.addActionListener(e -> {
            trace.picFlip = picFlip.isSelected();
            canvas.repaint();
        });
        dwgShow.addActionListener(e -> {
            trace.dwgVisible = dwgShow.isSelected();
            canvas.repaint();
        });
        picAlpha.addChangeListener(e -> {
            trace.picAlpha = picAlpha.getValue() / 100.0;
            canvas.repaint();
        });
        picRot.addChangeListener(e -> {
            if (!syncing) {
                trace.picRot = num(picRot);
                canvas.repaint();
            }
        });
        tr.add(hint("Take the photo <b>straight down</b> (from an angle it is stretched, and scaling cannot fix that) with a "
                + "ruler beside the board. Load it, <b>Set scale</b> (two clicks + the distance), move and turn it so the axle "
                + "lies on the dashed line across. <b>Trace outline</b>: click inside the board - the area of a similar colour "
                + "becomes the chassis (more <b>colour</b> % takes in more shades; <b>close gaps</b> bridges printing and holes; "
                + "<b>detail</b>: smaller keeps more corners). <b>Shift+click</b> adds another area (a part lying on the "
                + "board), <b>Alt+click</b> cuts one away. Parts that look like the floor cannot be told apart: a photo of the "
                + "bare PCB traces best, or fix the corners by hand. Or trace by hand with the corner and sensor tools - with Mirror "
                + "edits, trace one half. A <b>drawing</b> (DXF / SVG from a PCB or CAD program) is shown in cyan: its corners and "
                + "hole centres are snap points, and Trace outline inside one of its closed shapes takes that shape. The picture is "
                + "kept in the workspace; the drawing is linked."));
        syncTrace();
        return tr;
    }

    private void syncTrace() {
        syncing = true;
        picShow.setSelected(trace.picVisible);
        picFlip.setSelected(trace.picFlip);
        picAlpha.setValue((int) Math.round(trace.picAlpha * 100));
        setNum(picRot, trace.picRot);
        dwgShow.setSelected(trace.dwgVisible);
        syncing = false;
        traceMoved();
    }

    void traceMoved() {
        String p = trace.img == null ? "No picture" : String.format(Locale.ROOT, "Picture %d x %d px, %.3f mm per px (%.0f x %.0f mm)",
                trace.img.getWidth(), trace.img.getHeight(), trace.mmPerPx, trace.img.getWidth() * trace.mmPerPx, trace.img.getHeight() * trace.mmPerPx);
        String d = trace.dwg == null ? "no drawing" : String.format(Locale.ROOT, "drawing %s (%d outlines, %d circles)", trace.dwg.name,
                trace.dwg.closed.size(), trace.dwg.circles.size());
        traceInfo.setText("<html><body style='width:225px'>" + p + "; " + d + ".</body></html>");
        updateEnables();
    }

    private void loadPicture() {
        JFileChooser fc = new JFileChooser(UserPresets.setting("lastDir", ""));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("Picture (PNG, JPG, GIF, BMP)", "png", "jpg", "jpeg", "gif", "bmp"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            java.awt.image.BufferedImage im = javax.imageio.ImageIO.read(fc.getSelectedFile());
            if (im == null) {
                throw new IOException("not a picture this program can read");
            }
            Rectangle2D b = draft.chassis.size() >= 3 ? draft.chassisPath().getBounds2D() : new Rectangle2D.Double(-50, -60, 150, 120);
            trace.setPicture(im, Math.max(b.getWidth(), b.getHeight()) * 1.6);
            syncTrace();
            canvas.fit();
            canvas.repaint();
            toast("Picture loaded - now Set scale (two clicks a known distance apart)");
        } catch (IOException ex) {
            JOptionPane.showMessageDialog(this, "Could not read the picture: " + ex.getMessage(), "Load picture", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void loadDrawing() {
        JFileChooser fc = new JFileChooser(UserPresets.setting("lastDir", ""));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("DXF or SVG drawing", "dxf", "svg"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            trace.dwg = OutlineImport.underlay(fc.getSelectedFile());
            trace.dwgPath = fc.getSelectedFile().getAbsolutePath();
            trace.dwgRot90 = 0;
            trace.dwgMirror = false;
            trace.dwgVisible = true;
            trace.dwgX = trace.dwgY = 0;
            double[] b = trace.dwgBounds();
            if (b != null && !(b[0] <= 0 && b[2] >= 0 && b[1] <= 0 && b[3] >= 0)) {
                trace.centreDrawing();                                    // not drawn around the axle: centre it
            }
            syncTrace();
            canvas.fit();
            canvas.repaint();
            toast("Drawing loaded: its corners and hole centres are snap points");
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Could not read the drawing: " + ex.getMessage(), "Load drawing", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** Trace tool click (robot mm): a closed shape of the drawing around it, else the picture's region of that colour. */
    void traceAt(double x, double y, int mode) {
        List<RobotDraft.Pt> l = mode == 0 ? trace.pickClosed(x, y) : null;
        String from = "the drawing";
        if (l == null && trace.img != null && trace.picVisible) {
            setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
            try {
                l = trace.autoTrace(x, y, num(traceTol), (int) num(traceClose), num(traceDetail), mode);
            } finally {
                setCursor(Cursor.getDefaultCursor());
            }
            from = "the picture";
        }
        if (l == null) {
            toast(trace.img == null && trace.dwg == null ? "Load a picture or a drawing first"
                    : "Nothing found there (or that looks like the background) - click inside the board, or change the colour %");
            return;
        }
        pushUndo();
        draft.chassis.clear();
        draft.chassis.addAll(l);
        draft.mergeClose(draft.chassis, 0.05);
        canvas.sel.clear();
        syncFields();
        changed();
        committed();
        toast(String.format(Locale.ROOT, "Chassis traced from %s: %d corners, %.0f cm\u00B2 (Undo if it is wrong)", from,
                draft.chassis.size(), draft.plateGeometry()[0] / 100));
    }

    /** Set scale: the two clicked plane points and the distance the user types. */
    void scaleClicked(double x1, double y1, double x2, double y2) {
        double d = Math.hypot(x2 - x1, y2 - y1);
        setTool(RobotCanvas.SELECT);
        if (d < 1e-6 || trace.img == null) {
            return;
        }
        String s = JOptionPane.showInputDialog(this, String.format(Locale.ROOT, "The two points are %.1f mm apart on the picture now.%n"
                + "How far apart are they really (mm)?", d), String.format(Locale.ROOT, "%.1f", d));
        if (s == null) {
            return;
        }
        try {
            double real = Double.parseDouble(s.trim().replace(',', '.'));
            if (real <= 0) {
                throw new NumberFormatException();
            }
            trace.scalePictureAbout(x1, y1, real / d);
            traceMoved();
            canvas.repaint();
            toast(String.format(Locale.ROOT, "Picture scaled x%.3f: now %.3f mm per pixel", real / d, trace.mmPerPx));
        } catch (NumberFormatException ex) {
            toast("Type a distance in mm");
        }
    }

    private JButton startBtn() {
        JButton b = small("Start", "Replace the outline with this shape (centred on the axle line)");
        b.addActionListener(e -> {
            pushUndo();
            draft.chassis.clear();
            draft.chassis.addAll(RobotDraft.startShape(startCombo.getSelectedIndex(), num(stF), num(stR), num(stW)));
            canvas.sel.clear();
            changed();
            committed();
        });
        return b;
    }

    // ------------------------------------------------------------------ small helpers
    private final List<Object[]> enables = new ArrayList<>();

    /** Grey out a control while it does nothing (no picture, nothing selected, a car-only setting ...). */
    private <T extends JComponent> T en(T c, java.util.function.BooleanSupplier when) {
        enables.add(new Object[]{c, when});
        return c;
    }

    void updateEnables() {
        for (Object[] e : enables) {
            boolean on;
            try {
                on = ((java.util.function.BooleanSupplier) e[1]).getAsBoolean();
            } catch (RuntimeException ex) {
                on = true;
            }
            ControlPanel.setEnabledDeep((Component) e[0], on);
        }
    }
    private static JSpinner num(double v, double lo, double hi, double step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(v, lo, hi, step));
        s.setEditor(new JSpinner.NumberEditor(s, "0.##"));
        s.setPreferredSize(new Dimension(62, 22));
        return s;
    }

    private static double num(JSpinner s) {
        return ((Number) s.getValue()).doubleValue();
    }

    private static void setNum(JSpinner s, double v) {
        SpinnerNumberModel sm = (SpinnerNumberModel) s.getModel();
        s.setValue(Math.max(((Number) sm.getMinimum()).doubleValue(), Math.min(((Number) sm.getMaximum()).doubleValue(), v)));
    }

    /** A panel whose maximum height follows its preferred height (BoxLayout then never squeezes it). */
    static final class Dyn extends JPanel {

        Dyn(LayoutManager lm) {
            super(lm);
            setAlignmentX(Component.LEFT_ALIGNMENT);
        }

        @Override
        public Dimension getMaximumSize() {
            return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
        }
    }

    /** Left-aligned, full width, never squeezed. */
    private static JComponent fx(JComponent c) {
        if (c instanceof Dyn) {
            return c;
        }
        Dyn d = new Dyn(new BorderLayout());
        d.add(c, BorderLayout.CENTER);
        return d;
    }

    private static JPanel flow(JComponent... c) {
        JPanel p = new Dyn(new FlowLayout(FlowLayout.LEFT, 4, 1));
        for (JComponent x : c) {
            p.add(x);
        }
        return p;
    }

    private static JPanel row(String label, JComponent c) {
        JPanel p = new Dyn(new BorderLayout(6, 0));
        p.setBorder(new EmptyBorder(2, 4, 2, 4));
        p.add(new JLabel(label), BorderLayout.WEST);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    /** Help text sized for the editor's side panel. */
    private static JLabel hint(String text) {
        JLabel l = new UI.Hint("<html><body style='width:215px'><small>" + text + "</small></body></html>");
        l.setBorder(new EmptyBorder(4, 4, 2, 4));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        return l;
    }

    private static JButton small(String text, String tip) {
        JButton b = UI.button(text, null, tip);
        b.setMargin(new Insets(1, 4, 1, 4));
        return b;
    }

    private static String fmt(double v) {
        return v == Math.rint(v) ? Long.toString((long) v) : String.format(Locale.ROOT, "%.2f", v);
    }

    /** A copy of the robot with the Hardware tab's own shape, array and LED placement (for the Reset buttons). */
    private Model robotWithShape() {
        Model t = new Model();
        t.copySettingsFrom(m);
        t.customLayout = false;
        t.customLeds = false;
        if (t.chassisShape == 3) {
            t.chassisShape = 0;
        }
        t.applyHardware();
        return t;
    }

    // ------------------------------------------------------------------ canvas callbacks
    void setTool(int t) {
        canvas.tool = t;
        if (t < toolBtn.length && toolBtn[t] != null) {
            toolBtn[t].setSelected(true);
        } else {
            for (JToggleButton b : toolBtn) {                          // a mode without its own button (Set scale)
                if (b != null) {
                    b.setSelected(false);
                }
            }
        }
        String[] hint = {"Select / move", "Click an edge to add a corner", "Click to place a sensor", "Click to place an LED",
            "Drag a box to add a part", "Drag to measure", "Drag to pan", "Click inside the board on the picture (or inside a shape of "
            + "the drawing)", "Drag to move the " + (canvas.moveDrawing ? "drawing" : "picture"), "Click the first point of a known distance"};
        status.setText(hint[t] + "  -  right-click or Esc: back to Select");
        canvas.repaint();
    }

    void toast(String s) {
        status.setText(s);
    }

    void cursorAt(double x, double y) {
        cursor.setText(String.format(Locale.ROOT, "x %.1f  y %.1f mm", x, y));
    }

    void viewChanged() {
        zoomLbl.setText(Math.round(canvas.zoom / 3.78 * 100) + " %");     // 100 % = real size on a 96 dpi screen
    }

    void measured(double[] ms) {
        double dx = ms[2] - ms[0], dy = ms[3] - ms[1];
        status.setText(String.format(Locale.ROOT, "Measured %.2f mm (across %.2f, along %.2f)", Math.hypot(dx, dy), dy, dx));
    }

    void pushUndo() {
        List<String> now = draft.lines();
        if (undo.isEmpty() || !undo.peek().equals(now)) {
            undo.push(now);
            while (undo.size() > 200) {
                undo.removeLast();
            }
            redo.clear();
        }
    }

    void undo() {
        if (undo.isEmpty()) {
            toast("Nothing to undo");
            return;
        }
        List<String> now = draft.lines();
        List<String> prev = undo.pop();
        if (prev.equals(now) && !undo.isEmpty()) {
            prev = undo.pop();
        }
        redo.push(now);
        draft = RobotDraft.parse(prev);
        canvas.sel.clear();
        syncFields();
        changed();
        committed();
    }

    void redo() {
        if (redo.isEmpty()) {
            toast("Nothing to redo");
            return;
        }
        undo.push(draft.lines());
        draft = RobotDraft.parse(redo.pop());
        canvas.sel.clear();
        syncFields();
        changed();
        committed();
    }

    /** Something in the draft changed: redraw, recheck. */
    void changed() {
        partModel.fireTableDataChanged();
        updateInfo();
        canvas.repaint();
        autosave.restart();
    }

    /** An edit finished (mouse released, button pressed). */
    void committed() {
        autosave.restart();
        updateInfo();
    }

    void selectionChanged() {
        updateSelInfo();
        updateEnables();
    }

    void selectionMoved() {
        updateSelInfo();
    }

    private void updateSelInfo() {
        Rectangle2D.Double b = canvas.selBox();
        int n = canvas.sel.size();
        if (n == 0 || b == null) {
            selInfo.setText("Nothing selected - click, or drag a box");
            return;
        }
        int[] per = new int[6];
        for (long k : canvas.sel) {
            per[RobotCanvas.layerOf(k)]++;
        }
        StringBuilder sb = new StringBuilder();
        String[] nm = {"plate corner", "sensor", "LED", "part", "caster", "board corner"};
        for (int i = 0; i < 6; i++) {
            if (per[i] > 0) {
                sb.append(sb.length() > 0 ? ", " : "").append(per[i]).append(' ').append(nm[i]).append(per[i] > 1 && i != 4 ? "s" : "");
            }
        }
        selInfo.setText(String.format(Locale.ROOT, "<html><body style='width:225px'>%s<br>centre %.1f, %.1f mm; %.1f wide x %.1f long</html>", sb, b.getCenterX(),
                b.getCenterY(), b.getHeight(), b.getWidth()));
        syncing = true;
        setNum(selX, b.getCenterX());
        setNum(selY, b.getCenterY());
        syncing = false;
    }

    /** Mirror the draft's numbers into the side panel. */
    private void syncFields() {
        syncing = true;
        pairOrient.setSelectedIndex(Math.max(0, Math.min(1, draft.pairOrient)));
        setNum(pairGap, draft.pairGap);
        pairAlt.setSelected(draft.pairAlternate);
        setNum(margin, draft.boardMargin);
        joined.setSelected(draft.boardJoined);
        setNum(holeDia, draft.holeDia);
        setNum(stem, draft.stemWidth);
        setNum(sHeight, draft.sensorHeight);
        syncWheels();
        onPlate.setSelected(draft.boardOnPlate);
        boardMode.setSelectedIndex(draft.customBoard ? 1 : 0);
        customLeds.setSelected(draft.customLeds);
        useParts.setSelected(draft.useParts);
        setNum(plateT, draft.plateThick);
        setNum(casterSp, draft.casterX);
        int mi = 0;
        for (int i = 0; i < DENSITY.length; i++) {
            if (Math.abs(DENSITY[i] - draft.plateDensity) < Math.abs(DENSITY[mi] - draft.plateDensity)) {
                mi = i;
            }
        }
        material.setSelectedIndex(mi);
        if (!draft.sensors.isEmpty()) {
            setNum(sCount, draft.sensors.size());
            setNum(sRef, draft.reference());
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
            for (RobotDraft.Pt p : draft.sensors) {
                lo = Math.min(lo, p.y);
                hi = Math.max(hi, p.y);
            }
            setNum(sWidth, hi - lo);
            if (draft.sensors.size() > 1) {
                setNum(sPitch, (hi - lo) / (draft.sensors.size() - 1));
            }
        }
        Rectangle2D cb = draft.chassis.size() >= 3 ? draft.chassisPath().getBounds2D() : null;
        if (cb != null) {
            setNum(stW, Math.round(cb.getHeight()));
            setNum(stF, Math.round(Math.max(0, cb.getMaxX())));
            setNum(stR, Math.round(Math.max(0, -cb.getMinX())));
        }
        syncing = false;
        updateInfo();
    }

    private boolean isDraftDirty() {
        return !String.join("\n", draft.lines()).equals(appliedText);
    }

    private void updateInfo() {
        double[] g = draft.plateGeometry();
        Rectangle2D cb = draft.chassis.size() >= 3 ? draft.chassisPath().getBounds2D() : new Rectangle2D.Double();
        chassisInfo.setText(String.format(Locale.ROOT, "<html><body style='width:225px'>%d corners, %.0f x %.0f mm (wide x long), %.0f cm²</html>",
                draft.chassis.size(), cb.getHeight(), cb.getWidth(), g[0] / 100));
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (RobotDraft.Pt p : draft.sensors) {
            lo = Math.min(lo, p.y);
            hi = Math.max(hi, p.y);
        }
        int n = draft.sensors.size();
        sensorInfo.setText(n == 0 ? "No sensors yet" : String.format(Locale.ROOT, "<html><body style='width:225px'>%d sensors, %.1f mm wide%s, reference %.1f mm ahead "
                + "of the axle</html>", n, hi - lo, n > 1 ? String.format(Locale.ROOT, " (average pitch %.2f)", (hi - lo) / (n - 1)) : "",
                draft.reference()));
        ledInfo.setText("<html><body style='width:225px'>" + draft.leds.size() + " LEDs" + (draft.customLeds ? " (used)"
                : " (the Hardware tab placement, shown)") + (m.whiteLeds ? "" : " - the LEDs are off in the Hardware tab") + "</body></html>");
        double[] mc = draft.massCentre(draft.wheelDia);
        double share = wheelShare(mc[1]);
        blockers = wheelBlockers();
        boolean[] bw = new boolean[blockers.size()];
        for (int i = 0; i < bw.length; i++) {
            bw[i] = blockers.get(i) != null;
        }
        canvas.blockedWheels = bw;
        massInfo.setText(String.format(Locale.ROOT, "<html><body style='width:225px'>Plate %.0f g, total %.0f g%s<br>Centre of mass %.1f mm %s the axle, %.1f mm %s, "
                + "%.0f mm high<br>About %.0f %% of the weight on the wheels</html>", draft.plateMass(), mc[0],
                draft.useParts ? "" : " (not used: Motors tab mass)", Math.abs(mc[1]), mc[1] >= 0 ? "ahead of" : "behind",
                Math.abs(mc[2]), mc[2] >= 0 ? "right" : "left", mc[3], share * 100));
        boolean dirty = isDraftDirty();
        state.setText(dirty ? "<html><b>Draft not applied yet</b></html>" : "Draft = robot");
        state.setForeground(dirty ? Color.decode(Theme.warnHex()) : Theme.textDim());
        checks.setText(runChecks(mc, share));
        updateEnables();
        if (sideCol != null) {                                         // the info texts changed length: lay the panel out again
            sideCol.invalidate();
            for (Component c : sideCol.getComponents()) {
                c.invalidate();
            }
            sideCol.revalidate();
        }
    }

    private List<String> blockers = new ArrayList<>();

    /** For each wheel: what on the robot it runs into (null = free). Steered wheels are checked across their lock. */
    List<String> wheelBlockers() {
        List<String> out = new ArrayList<>();
        List<double[]> ws = draft.wheels();
        double r = draft.wheelDia / 2, tw = Math.max(draft.tireWidth, 1), gap = 0.5;
        java.awt.geom.Area plate = draft.chassis.size() >= 3 ? new java.awt.geom.Area(draft.chassisPath()) : new java.awt.geom.Area();
        java.awt.geom.Area board = draft.board();
        for (double[] w : ws) {
            java.awt.geom.Area sweep = new java.awt.geom.Area();
            Rectangle2D.Double rect = new Rectangle2D.Double(w[0] - r - gap, w[1] - tw / 2 - gap, 2 * r + 2 * gap, tw + 2 * gap);
            double lock = w[2] > 0 ? draft.steerLock : 0;
            for (double a = -lock; a <= lock + 1e-9; a += Math.max(lock / 6, 1)) {
                sweep.add(new java.awt.geom.Area(java.awt.geom.AffineTransform.getRotateInstance(Math.toRadians(a), w[0], w[1])
                        .createTransformedShape(rect)));
            }
            String hit = null;
            if (overlaps(sweep, plate)) {
                hit = "the plate";
            } else if (overlaps(sweep, board)) {
                hit = "the sensor board";
            } else {
                for (Model.Part p : draft.parts) {
                    if (p.w > 0 && p.l > 0 && overlaps(sweep, new java.awt.geom.Area(new Rectangle2D.Double(p.x - p.l / 2, p.y - p.w / 2, p.l, p.w)))) {
                        hit = p.name;
                        break;
                    }
                }
            }
            out.add(hit);
        }
        return out;
    }

    private static boolean overlaps(java.awt.geom.Area a, java.awt.geom.Area b) {
        if (b.isEmpty()) {
            return false;
        }
        java.awt.geom.Area x = new java.awt.geom.Area(a);
        x.intersect(b);
        return !x.isEmpty() && x.getBounds2D().getWidth() * x.getBounds2D().getHeight() > 0.05;
    }

    private double wheelShare(double xg) {
        if (draft.wheelLayout != 0) {
            return 1;
        }
        double xc = draft.casterX;
        return Math.abs(xc) < 1 ? 1 : Math.max(0, (xg - xc) / (0 - xc));
    }

    private String runChecks(double[] mc, double share) {
        List<String> l = new ArrayList<>();
        String p = draft.applyProblem();
        if (p != null) {
            l.add("FIX: " + p);
        }
        List<double[]> wl = draft.wheels();
        for (int i = 0; i < blockers.size() && i < wl.size(); i++) {
            if (blockers.get(i) != null) {
                String which = (wl.size() == 4 ? (wl.get(i)[0] > 0 ? "front " : "rear ") : "") + (wl.get(i)[1] < 0 ? "left" : "right");
                l.add("FIX: the " + which + " wheel runs into " + blockers.get(i) + (wl.get(i)[2] > 0 ? " when it steers" : "")
                        + " (drawn red): cut the plate back, move the part or the wheel.");
            }
        }
        List<RobotDraft.Pt> s = new ArrayList<>(draft.sensors);
        s.sort((a, b) -> Double.compare(a.y, b.y));
        double minGap = Double.MAX_VALUE, maxGap = 0;
        for (int i = 1; i < s.size(); i++) {
            double gap = Math.hypot(s.get(i).x - s.get(i - 1).x, s.get(i).y - s.get(i - 1).y);
            minGap = Math.min(minGap, gap);
            maxGap = Math.max(maxGap, s.get(i).y - s.get(i - 1).y);
        }
        if (s.size() > 1 && m.sensBodyW > 0) {                          // the parts themselves must fit side by side
            for (int i = 1; i < s.size(); i++) {
                RobotDraft.Pt a = s.get(i - 1), b = s.get(i);
                boolean clash = Math.abs(a.y - b.y) < m.sensBodyW - 0.05 && Math.abs(a.x - b.x) < m.sensBodyL - 0.05;
                if (clash) {
                    l.add(String.format(Locale.ROOT, "FIX: sensors %d and %d overlap: the parts are %.1f x %.1f mm (Hardware > Sensor part); "
                            + "the pitch must be at least %.1f mm across.", i, i + 1, m.sensBodyW, m.sensBodyL, m.sensBodyW));
                    break;
                }
            }
        }
        if (s.size() > 1 && minGap < 2 * draft.spotRadius) {
            l.add(String.format(Locale.ROOT, "CHECK: two sensors are %.1f mm apart - their spots (%.1f mm) overlap.", minGap, 2 * draft.spotRadius));
        }
        if (s.size() > 1 && maxGap > m.lineWidth) {
            l.add(String.format(Locale.ROOT, "CHECK: a gap of %.1f mm across is wider than the line (%.0f mm): the line can hide "
                    + "between two sensors.", maxGap, m.lineWidth));
        }
        if (draft.boardOnPlate && draft.chassis.size() >= 3) {
            java.awt.geom.Path2D.Double plate = draft.chassisPath();
            for (RobotDraft.Pt q : draft.sensors) {
                if (!plate.contains(q.x, q.y)) {
                    l.add("FIX: a sensor is off the plate (the sensors sit on the plate: draw it out to them).");
                    break;
                }
            }
        }
        if (draft.chassis.size() >= 3 && !s.isEmpty() && draft.boardJoined && !draft.boardOnPlate && draft.holes().isEmpty()) {
            l.add("CHECK: the board does not overlap the plate enough for the mount holes - use Join to the chassis front.");
        }
        for (RobotDraft.Pt q : draft.sensors) {
            if (q.x < 0) {
                l.add("FIX: a sensor is behind the axle.");
                break;
            }
        }
        if (share > 1.001) {
            l.add("FIX: the centre of mass is beyond the axle away from the caster - the robot tips onto its nose / tail.");
        } else if (share < 0.45 && draft.useParts) {
            l.add(String.format(Locale.ROOT, "CHECK: only %.0f %% of the weight is on the driven wheels: little grip (move the battery "
                    + "towards the axle).", share * 100));
        }
        if (draft.useParts && mc[3] > 0) {
            double aTip = 9810 * (draft.wheelBase / 2) / Math.max(mc[3], 5);
            l.add(String.format(Locale.ROOT, "OK: tips over sideways above %.1f g sideways (%.0f mm/s in a 100 mm bend).", aTip / 9810,
                    Math.sqrt(aTip * 100)));
        }
        if (draft.pairGap > 0 && s.size() > 1 && draft.pairOrient == 0 && draft.pairGap > 0.8 * (s.get(1).y - s.get(0).y)) {
            l.add("CHECK: the emitter-receiver gap is nearly the pitch: neighbouring pairs will see each other.");
        }
        if (draft.leds.size() > 0 && !m.whiteLeds) {
            l.add("NOTE: the LEDs are off in the Hardware tab (White LEDs).");
        }
        if (l.isEmpty() || l.size() == 1 && l.get(0).startsWith("OK")) {
            l.add(0, "OK: no problems found.");
        }
        return String.join("\n", l);
    }

    // ------------------------------------------------------------------ actions
    interface BoxFn {

        double[] apply(Rectangle2D.Double b);                      // {scale x (along), scale y (across), move x, move y}
    }

    private void transformSel(BoxFn f) {
        Rectangle2D.Double b = canvas.selBox();
        if (b == null) {
            toast("Select something first (Ctrl+A: everything)");
            return;
        }
        pushUndo();
        double[] t = f.apply(b);
        double cx = b.getCenterX(), cy = b.getCenterY();
        for (long k : canvas.sel) {
            double[] q = canvas.pos(k);
            double nx = cx + (q[0] - cx) * t[0] + t[2], ny = cy + (q[1] - cy) * t[1] + t[3];
            canvas.setPos(k, nx, RobotCanvas.layerOf(k) == RobotCanvas.L_CASTER ? 0 : ny);
            if (RobotCanvas.layerOf(k) == RobotCanvas.L_PART) {
                Model.Part p = draft.parts.get(RobotCanvas.indexOf(k));
                p.l *= Math.abs(t[0]);
                p.w *= Math.abs(t[1]);
            }
        }
        changed();
        committed();
        updateSelInfo();
    }

    private void rotateSel(double deg) {
        Rectangle2D.Double b = canvas.selBox();
        if (b == null) {
            toast("Select something first (Ctrl+A: everything)");
            return;
        }
        pushUndo();
        double a = Math.toRadians(deg), c = Math.cos(a), s = Math.sin(a), cx = b.getCenterX(), cy = b.getCenterY();
        for (long k : canvas.sel) {
            double[] q = canvas.pos(k);
            double dx = q[0] - cx, dy = q[1] - cy;
            canvas.setPos(k, cx + dx * c - dy * s, RobotCanvas.layerOf(k) == RobotCanvas.L_CASTER ? 0 : cy + dx * s + dy * c);
            if (RobotCanvas.layerOf(k) == RobotCanvas.L_PART && Math.abs(Math.abs(deg) % 180 - 90) < 1e-6) {
                Model.Part p = draft.parts.get(RobotCanvas.indexOf(k));
                double t = p.l;
                p.l = p.w;
                p.w = t;
            }
        }
        changed();
        committed();
        updateSelInfo();
    }

    /** across = 1: line up y (-1 left, 0 centre, 1 right); across = 0: line up x (1 front, 0 middle, -1 back). */
    private void align(int across, int where) {
        Rectangle2D.Double b = canvas.selBox();
        if (b == null || canvas.sel.size() < 2) {
            toast("Select two or more items to line them up");
            return;
        }
        pushUndo();
        for (long k : canvas.sel) {
            double[] q = canvas.pos(k);
            if (across == 1) {
                canvas.setPos(k, q[0], where < 0 ? b.getMinY() : where > 0 ? b.getMaxY() : b.getCenterY());
            } else {
                canvas.setPos(k, where > 0 ? b.getMaxX() : where < 0 ? b.getMinX() : b.getCenterX(), q[1]);
            }
        }
        changed();
        committed();
        updateSelInfo();
    }

    /** Equal gaps between the selected items: across (by y) or along (by x); the first and last stay put. */
    private void distribute(int across) {
        List<Long> ks = new ArrayList<>(canvas.sel);
        if (ks.size() < 3) {
            toast("Select three or more items to space them evenly");
            return;
        }
        pushUndo();
        ks.sort((a, b) -> Double.compare(canvas.pos(a)[across], canvas.pos(b)[across]));
        double[] f = canvas.pos(ks.get(0)), l = canvas.pos(ks.get(ks.size() - 1));
        for (int i = 1; i < ks.size() - 1; i++) {
            double t = i / (double) (ks.size() - 1);
            double[] q = canvas.pos(ks.get(i));
            if (across == 1) {
                canvas.setPos(ks.get(i), f[0] + (l[0] - f[0]) * t, f[1] + (l[1] - f[1]) * t);   // on the line from first to last
            } else {
                canvas.setPos(ks.get(i), f[0] + (l[0] - f[0]) * t, q[1]);
            }
        }
        changed();
        committed();
    }

    void deleteSelection() {
        if (canvas.sel.isEmpty()) {
            return;
        }
        pushUndo();
        List<Long> ks = new ArrayList<>(canvas.sel);
        ks.sort((a, b) -> Long.compare(b, a));                         // highest index first
        int corners = draft.chassis.size(), bcorners = draft.boardPts.size();
        for (long k : ks) {
            int i = RobotCanvas.indexOf(k);
            switch (RobotCanvas.layerOf(k)) {
                case RobotCanvas.L_CHASSIS:
                    if (corners > 3) {
                        draft.chassis.remove(i);
                        corners--;
                    }
                    break;
                case RobotCanvas.L_SENSOR:
                    draft.sensors.remove(i);
                    for (RobotDraft.Pt led : draft.leds) {
                        led.sensor = led.sensor == i ? -1 : led.sensor > i ? led.sensor - 1 : led.sensor;
                    }
                    break;
                case RobotCanvas.L_LED:
                    draft.leds.remove(i);
                    break;
                case RobotCanvas.L_PART:
                    draft.parts.remove(i);
                    break;
                case RobotCanvas.L_BOARD:
                    if (bcorners > 3) {
                        draft.boardPts.remove(i);
                        bcorners--;
                    }
                    break;
                default:
            }
        }
        canvas.sel.clear();
        selectionChanged();
        changed();
        committed();
    }

    private void placeSensors() {
        int n = (int) num(sCount);
        double width = byPitch.isSelected() ? num(sPitch) * (n - 1) : num(sWidth);
        if (sKind.getSelectedIndex() == 3 && draft.chassis.size() < 3) {
            toast("Draw the chassis first (or pick another arrangement)");
            return;
        }
        List<RobotDraft.Pt> l = RobotDraft.placeSensors(sKind.getSelectedIndex(), n, width, num(sRef), num(sAngle), num(sRadius),
                draft.chassis, num(sInset), num(sArc), num(sCentre));
        for (RobotDraft.Pt p : l) {
            if (Double.isNaN(p.x)) {
                toast("The chassis front does not reach that wide: make the width smaller");
                return;
            }
        }
        pushUndo();
        for (int i = 0; i < l.size() && i < draft.sensors.size(); i++) {
            l.get(i).pin = draft.sensors.get(i).pin;                      // keep the pins in order
        }
        draft.sensors.clear();
        draft.sensors.addAll(l);
        for (RobotDraft.Pt led : draft.leds) {
            led.sensor = -1;
        }
        if (autoLed.isSelected() && draft.customLeds) {                // the LEDs move with the sensors, placed the same way
            draft.placeLeds(ledKind.getSelectedIndex(), num(ledOff));
        }
        canvas.sel.clear();
        syncFields();
        changed();
        committed();
        toast(n + " sensors placed" + (draft.customLeds && autoLed.isSelected() ? ", LEDs placed with them" : ""));
    }

    /** The row width that fits the plate: its width at the row (or its widest front part) minus the board edge. */
    private void fitWidth() {
        if (draft.chassis.size() < 3) {
            toast("Draw the chassis first");
            return;
        }
        double x = sKind.getSelectedIndex() == 3 ? RobotDraft.frontEdgeAt(draft.chassis, 0) - num(sInset) : num(sRef);
        double lo = Double.NaN, hi = Double.NaN;
        java.awt.geom.Area a = new java.awt.geom.Area(draft.chassisPath());
        Rectangle2D b = a.getBounds2D();
        for (double y = b.getMinY(); y <= b.getMaxY(); y += 0.25) {    // where a line across at x is inside the plate
            if (a.contains(Math.min(x, b.getMaxX() - 0.01), y)) {
                lo = Double.isNaN(lo) ? y : lo;
                hi = y;
            }
        }
        if (Double.isNaN(lo)) {
            lo = b.getMinY();
            hi = b.getMaxY();
        }
        double w = Math.max(0, hi - lo - 2 * (draft.spotRadius + draft.boardMargin));
        setNum(sWidth, Math.round(w * 2) / 2.0);
        byWidth.setSelected(true);
        toast(String.format(Locale.ROOT, "Width %.1f mm (the plate is %.1f mm wide there) - press Place sensors evenly", w, hi - lo));
    }

    /** Slide the sensors (and the LEDs) along so the board overlaps the plate's front edge by the overlap. */
    private void joinBoard() {
        if (draft.chassis.size() < 3 || draft.sensors.isEmpty()) {
            toast("Needs a chassis and sensors");
            return;
        }
        double front = RobotDraft.frontEdgeAt(draft.chassis, 0);
        if (Double.isNaN(front)) {
            toast("The chassis does not cross the centre line");
            return;
        }
        Rectangle2D b = draft.board().getBounds2D();
        double shift = front - num(overlap) - b.getMinX();
        pushUndo();
        for (RobotDraft.Pt p : draft.sensors) {
            p.x += shift;
        }
        for (RobotDraft.Pt p : draft.leds) {
            p.x += shift;
        }
        draft.boardJoined = true;
        syncFields();
        changed();
        committed();
        toast(String.format(Locale.ROOT, "Array moved %.1f mm; the board overlaps the plate by %.0f mm", shift, num(overlap)));
    }

    private void importOutline() {
        JFileChooser fc = new JFileChooser(UserPresets.setting("lastDir", ""));
        fc.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("DXF or SVG drawing", "dxf", "svg"));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        try {
            List<double[]> pts = OutlineImport.read(fc.getSelectedFile());
            Rectangle2D.Double b = null;
            for (double[] q : pts) {
                if (b == null) {
                    b = new Rectangle2D.Double(q[0], q[1], 0, 0);
                } else {
                    b.add(q[0], q[1]);
                }
            }
            double sx = 0, sy = 0;
            if (b != null && !b.contains(0, 0)) {                         // not drawn around the axle: centre it there
                sx = -b.getCenterX();
                sy = -b.getCenterY();
            }
            pushUndo();
            draft.chassis.clear();
            for (double[] q : pts) {
                draft.chassis.add(new RobotDraft.Pt(q[0] + sx, q[1] + sy));
            }
            int merged = draft.mergeClose(draft.chassis, 0.05);
            canvas.sel.clear();
            syncFields();
            changed();
            committed();
            canvas.fit();
            toast(String.format(Locale.ROOT, "Imported %d corners%s%s", draft.chassis.size(), sx != 0 || sy != 0 ? " (centred on the axle - move it "
                    + "with Select all + Move to)" : "", merged > 0 ? ", " + merged + " duplicates removed" : ""));
        } catch (IOException | RuntimeException ex) {
            JOptionPane.showMessageDialog(this, "Could not read the outline: " + ex.getMessage(), "Import outline", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void editPins() {
        List<RobotDraft.Pt> s = new ArrayList<>(draft.sensors);
        s.sort((a, b) -> Double.compare(a.y, b.y));
        String[] cols = {"#", "across mm", "ahead mm", "height mm", "pin"};
        Object[][] data = new Object[s.size()][5];
        for (int i = 0; i < s.size(); i++) {
            data[i] = new Object[]{i + 1, String.format(Locale.ROOT, "%.1f", s.get(i).y), String.format(Locale.ROOT, "%.1f", s.get(i).x),
                String.format(Locale.ROOT, "%.1f", draft.sensorHeight + s.get(i).h), s.get(i).pin == null ? "" : s.get(i).pin};
        }
        JTable t = new JTable(new javax.swing.table.DefaultTableModel(data, cols) {
            @Override
            public boolean isCellEditable(int r, int c) {
                return c >= 3;
            }
        });
        t.setRowHeight(20);
        JScrollPane sp = new JScrollPane(t);
        sp.setPreferredSize(new Dimension(420, Math.min(420, 30 + 20 * s.size())));
        JPanel p = new JPanel(new BorderLayout(4, 4));
        p.add(sp, BorderLayout.CENTER);
        p.add(new JLabel("<html><body style='width:380px'>Left to right. <b>Height</b>: each sensor's height above the floor (a curved or bent board "
                + "puts the tips higher). <b>Pin</b>: empty = A0, A1, ... in order (Arduino export).</html>"), BorderLayout.SOUTH);
        if (JOptionPane.showConfirmDialog(this, p, "Sensors: heights and pins", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE)
                == JOptionPane.OK_OPTION) {
            if (t.isEditing()) {
                t.getCellEditor().stopCellEditing();
            }
            pushUndo();
            for (int i = 0; i < s.size(); i++) {
                String v = String.valueOf(t.getValueAt(i, 4)).trim();
                s.get(i).pin = v.isEmpty() ? null : v.replace(' ', '_');
                try {
                    double h = Double.parseDouble(String.valueOf(t.getValueAt(i, 3)).trim().replace(',', '.'));
                    s.get(i).h = Math.max(0.3, Math.min(60, h)) - draft.sensorHeight;
                } catch (NumberFormatException ex) {
                    // keep the old height
                }
            }
            changed();
            committed();
        }
    }

    void editPart(int i) {
        if (i < 0 || i >= draft.parts.size()) {
            return;
        }
        Model.Part p = draft.parts.get(i);
        JTextField name = new JTextField(p.name, 16);
        JComboBox<String> kind = new JComboBox<>(new String[]{"Battery", "MCU board", "Motor driver", "Motor", "Other"});
        kind.setSelectedIndex(Math.max(0, Math.min(4, p.kind)));
        JSpinner mass = num(p.massG, 0, 5000, 1), x = num(p.x, -1000, 1000, 1), y = num(p.y, -1000, 1000, 1), w = num(p.w, 0, 1000, 1),
                l = num(p.l, 0, 1000, 1), h = num(p.h, 0, 500, 1);
        JPanel f = new JPanel(new GridLayout(0, 2, 6, 4));
        Object[][] rows = {{"Name", name}, {"Kind", kind}, {"Mass (g)", mass}, {"Centre ahead of the axle (mm)", x},
            {"Centre to the right (mm)", y}, {"Width, across (mm)", w}, {"Length, along (mm)", l}, {"Height of its centre above the floor (mm)", h}};
        for (Object[] r : rows) {
            f.add(new JLabel((String) r[0]));
            f.add((JComponent) r[1]);
        }
        if (JOptionPane.showConfirmDialog(this, f, "Part", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION) {
            pushUndo();
            p.name = name.getText().trim().isEmpty() ? "Part" : name.getText().trim();
            p.kind = kind.getSelectedIndex();
            p.massG = num(mass);
            p.x = num(x);
            p.y = num(y);
            p.w = num(w);
            p.l = num(l);
            p.h = num(h);
            changed();
            committed();
        }
    }

    // ------------------------------------------------------------------ apply / revert / autosave
    void applyDraft() {
        String p = draft.applyProblem();
        if (p != null) {
            JOptionPane.showMessageDialog(this, p, "Apply to robot", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (draft.sensors.size() > 16 && m.nRows > 1) {
            JOptionPane.showMessageDialog(this, "More than 16 sensors work with one row only: set Sensor rows to 1 in the Hardware tab.",
                    "Apply to robot", JOptionPane.WARNING_MESSAGE);
            return;
        }
        draft.applyTo(m);
        double[] mc = draft.massCentre(draft.wheelDia);
        cp.robotFromEditor(draft.useParts ? mc[0] : -1, draft.useParts ? Math.max(5, Math.min(100, wheelShare(mc[1]) * 100)) : -1);
        appliedText = String.join("\n", draft.lines());
        draft = RobotDraft.from(m);                                    // as the robot now has it (sorted, rounded)
        appliedText = String.join("\n", draft.lines());
        canvas.sel.clear();
        syncFields();
        changed();
        toast("Applied: the simulated robot now uses this layout (save the workspace to keep it)");
    }

    void takeFromRobot(boolean ask) {
        if (ask && isDraftDirty() && JOptionPane.showConfirmDialog(this, "Throw away the changes in the draft?", "Take from robot",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) {
            return;
        }
        pushUndo();
        draft = RobotDraft.from(m);
        appliedText = String.join("\n", draft.lines());
        canvas.sel.clear();
        syncFields();
        changed();
    }

    static File autosaveFile() {
        return new File(new File(System.getProperty("user.home"), ".linefollowersim"), "robot-draft.txt");
    }

    private void writeAutosave() {
        if (!isDraftDirty()) {
            return;
        }
        try {
            File f = autosaveFile();
            f.getParentFile().mkdirs();
            Files.write(f.toPath(), String.join("\n", draft.lines()).getBytes(StandardCharsets.UTF_8));
        } catch (IOException ex) {
            // autosave is best effort
        }
    }

    private void recoverAutosave() {
        try {
            List<String> l = Files.readAllLines(autosaveFile().toPath(), StandardCharsets.UTF_8);
            RobotDraft d = RobotDraft.parse(l);
            if (d == null) {
                throw new IOException("empty");
            }
            pushUndo();
            draft = d;
            canvas.sel.clear();
            syncFields();
            changed();
            canvas.fit();
            recoverBtn.setVisible(false);
            toast("Autosaved draft recovered (not applied yet - Undo goes back)");
        } catch (IOException | RuntimeException ex) {
            toast("The autosave file could not be read");
            recoverBtn.setVisible(false);
        }
    }

    /** Grid, snapping, mirror edits, layers and the zoom back to how the editor starts. */
    void resetView() {
        gridCombo.setSelectedItem("5");
        canvas.grid = 5;
        showGrid.setSelected(true);
        dots.setSelected(false);
        snapGrid.setSelected(true);
        snapPts.setSelected(true);
        sym.setSelected(true);
        isolate.setSelected(false);
        canvas.showGrid = true;
        canvas.dotGrid = false;
        canvas.snapGrid = canvas.snapPoints = canvas.symmetric = true;
        canvas.isolate = false;
        layerCombo.setSelectedIndex(0);
        setNum(traceTol, 22);
        setNum(traceClose, 3);
        setNum(traceDetail, 1.0);
        setTool(RobotCanvas.SELECT);
        canvas.measure = null;
        canvas.fit();
        toast("Editor view and snapping reset");
    }

    /** The draft becomes the default robot (as Reset all leaves the simulator); the simulator is not changed. */
    void resetDraftToDefault() {
        pushUndo();
        Model d = new Model();
        d.applyHardware();
        draft = RobotDraft.from(d);
        canvas.sel.clear();
        syncFields();
        changed();
        committed();
        canvas.fit();
        toast("Draft reset to the default robot - Apply to use it, Undo to go back");
    }

    void escapeKey() {
        if (canvas.tool != RobotCanvas.SELECT) {
            setTool(RobotCanvas.SELECT);
        } else {
            canvas.sel.clear();
            selectionChanged();
            canvas.repaint();
        }
    }

    void themeChanged() {
        canvas.repaint();
        updateInfo();
    }

    // ------------------------------------------------------------------ parts table
    private final class PartTable extends AbstractTableModel {

        private final String[] cols = {"Part", "g", "h mm"};

        @Override
        public int getRowCount() {
            return draft == null ? 0 : draft.parts.size();
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
            Model.Part p = draft.parts.get(r);
            return c == 0 ? p.name : c == 1 ? fmt(Math.round(p.massG * 10) / 10.0) : fmt(Math.round(p.h));
        }
    }
}
