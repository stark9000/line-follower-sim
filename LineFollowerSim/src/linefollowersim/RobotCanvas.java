package linefollowersim;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The Robot editor's drawing area. Front of the robot points up, its right is to the right; units are mm.
 * Wheel = zoom at the cursor, middle-drag / Space-drag = pan. Tools: select / move (drag a box to pick
 * several, Shift adds), corner (click an edge to add a chassis corner), sensor, LED, part (drag a box),
 * measure and pan. Handles around a selection scale it (Shift = keep proportions).
 */
final class RobotCanvas extends JComponent {

    static final int SELECT = 0, CORNER = 1, SENSOR = 2, LED = 3, PART = 4, MEASURE = 5, PAN = 6, TRACE = 7, MOVEPIC = 8, SCALEPIC = 9;
    static final String[] TOOL_NAMES = {"Select / move", "Add corner", "Add sensor", "Add LED", "Add part", "Measure", "Pan",
        "Trace outline", "Move picture / drawing", "Set scale"};
    boolean moveDrawing;                              // Move tool: the drawing (else the picture)
    private double[] scaleFirst;                      // Set scale: the first click (plane mm)
    static final int L_CHASSIS = 0, L_SENSOR = 1, L_LED = 2, L_PART = 3, L_CASTER = 4, L_BOARD = 5;
    static final String[] LAYERS = {"Chassis", "Sensors", "LEDs", "Parts", "Caster", "Board"};
    boolean[] blockedWheels = new boolean[0];         // wheels something on the robot runs into (drawn red)

    private final RobotEditor ed;
    int tool = SELECT;
    double zoom = 3, offX = 400, offY = 300;          // screen position of the axle centre, px per mm
    double grid = 5;
    boolean snapGrid = true, snapPoints = true, symmetric = true, showGrid = true, dotGrid;
    int activeLayer = -1;                             // -1 = all layers pickable
    boolean isolate;                                  // dim the layers other than the active one
    final Set<Long> sel = new LinkedHashSet<>();

    private Point lastMouse, pressAt;
    private Point2D.Double pressMm, lastMm, hoverMm;
    private boolean spaceDown, panning, dragging, boxSelecting, changedInDrag;
    private Rectangle box;
    private int handle = -1;                          // 0..7 = scale handle being dragged
    private Rectangle2D.Double handleStartBox;
    private List<double[]> dragStart;                 // positions at the start of a move / scale
    private final List<long[]> partners = new ArrayList<>();   // {key, partnerKey} for mirrored editing
    double[] measure;                                 // x1, y1, x2, y2 (mm) of the last measurement

    RobotCanvas(RobotEditor ed) {
        this.ed = ed;
        setFocusable(true);
        setOpaque(true);
        MouseAdapter ma = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                press(e);
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                drag(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                release(e);
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                hoverMm = toMm(e.getPoint());
                if (tool == SCALEPIC && scaleFirst != null && measure != null) {     // rubber band for Set scale
                    measure[2] = hoverMm.x;
                    measure[3] = hoverMm.y;
                }
                ed.cursorAt(hoverMm.x, hoverMm.y);
                setCursor(Cursor.getPredefinedCursor(tool == PAN || spaceDown ? Cursor.MOVE_CURSOR
                        : handleAt(e.getPoint()) >= 0 ? Cursor.CROSSHAIR_CURSOR : Cursor.DEFAULT_CURSOR));
                repaint();
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoomAt(e.getPoint(), Math.pow(1.15, -e.getPreciseWheelRotation()));
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                key(e, true);
            }

            @Override
            public void keyReleased(KeyEvent e) {
                key(e, false);
            }
        });
    }

    // ------------------------------------------------------------------ view
    Point2D.Double toMm(Point p) {
        return new Point2D.Double((offY - p.y) / zoom, (p.x - offX) / zoom);
    }

    Point2D.Double toPx(double x, double y) {
        return new Point2D.Double(offX + y * zoom, offY - x * zoom);
    }

    void zoomAt(Point p, double f) {
        double nz = Math.max(0.3, Math.min(60, zoom * f));
        Point2D.Double mm = toMm(p);
        zoom = nz;
        offX = p.x - mm.y * zoom;
        offY = p.y + mm.x * zoom;
        ed.viewChanged();
        repaint();
    }

    /** Fit the whole robot (or the active layer when isolated) into the view. */
    void fit() {
        Rectangle2D b = bounds(isolate ? activeLayer : -1);
        if (b == null || getWidth() < 10) {
            return;
        }
        double mx = Math.max(b.getWidth(), 20), my = Math.max(b.getHeight(), 20);
        zoom = Math.max(0.3, Math.min(60, Math.min((getWidth() - 60) / my, (getHeight() - 60) / mx)));
        offX = getWidth() / 2.0 - b.getCenterY() * zoom;
        offY = getHeight() / 2.0 + b.getCenterX() * zoom;
        ed.viewChanged();
        repaint();
    }

    private Rectangle2D bounds(int layer) {
        RobotDraft d = ed.draft;
        Rectangle2D r = null;
        List<double[]> pts = new ArrayList<>();
        if (layer < 0 || layer == L_CHASSIS) {
            for (RobotDraft.Pt p : d.chassis) {
                pts.add(new double[]{p.x, p.y});
            }
        }
        if (layer < 0 || layer == L_SENSOR) {
            for (RobotDraft.Pt p : d.sensors) {
                pts.add(new double[]{p.x, p.y});
            }
        }
        if (layer < 0 || layer == L_LED) {
            for (RobotDraft.Pt p : d.leds) {
                pts.add(new double[]{p.x, p.y});
            }
        }
        if (layer < 0 || layer == L_BOARD || layer == L_SENSOR) {
            java.awt.geom.Rectangle2D bb = d.board().getBounds2D();
            if (!bb.isEmpty()) {
                pts.add(new double[]{bb.getMinX(), bb.getMinY()});
                pts.add(new double[]{bb.getMaxX(), bb.getMaxY()});
            }
        }
        if (layer < 0 || layer == L_PART) {
            for (Model.Part p : d.parts) {
                pts.add(new double[]{p.x - p.l / 2, p.y - p.w / 2});
                pts.add(new double[]{p.x + p.l / 2, p.y + p.w / 2});
            }
        }
        if (layer < 0) {
            for (double[] wp : ed.draft.wheels()) {
                double hw = ed.draft.tireWidth, r0 = ed.draft.wheelDia / 2;
                pts.add(new double[]{wp[0] + r0, wp[1] + hw});
                pts.add(new double[]{wp[0] - r0, wp[1] - hw});
            }
        }
        for (double[] q : pts) {
            if (r == null) {
                r = new Rectangle2D.Double(q[0], q[1], 0, 0);
            } else {
                r.add(q[0], q[1]);
            }
        }
        return r;
    }

    // ------------------------------------------------------------------ items
    static long key(int layer, int i) {
        return layer * 100000L + i;
    }

    static int layerOf(long k) {
        return (int) (k / 100000L);
    }

    static int indexOf(long k) {
        return (int) (k % 100000L);
    }

    /** Position of an item (parts: centre; caster: x, 0). */
    double[] pos(long k) {
        RobotDraft d = ed.draft;
        int i = indexOf(k);
        switch (layerOf(k)) {
            case L_CHASSIS:
                return i < d.chassis.size() ? new double[]{d.chassis.get(i).x, d.chassis.get(i).y} : null;
            case L_SENSOR:
                return i < d.sensors.size() ? new double[]{d.sensors.get(i).x, d.sensors.get(i).y} : null;
            case L_LED:
                return i < d.leds.size() ? new double[]{d.leds.get(i).x, d.leds.get(i).y} : null;
            case L_PART:
                return i < d.parts.size() ? new double[]{d.parts.get(i).x, d.parts.get(i).y} : null;
            case L_BOARD:
                return i < d.boardPts.size() ? new double[]{d.boardPts.get(i).x, d.boardPts.get(i).y} : null;
            default:
                return new double[]{d.casterX, 0};
        }
    }

    void setPos(long k, double x, double y) {
        RobotDraft d = ed.draft;
        int i = indexOf(k);
        switch (layerOf(k)) {
            case L_CHASSIS:
                d.chassis.get(i).x = x;
                d.chassis.get(i).y = y;
                break;
            case L_SENSOR:
                d.sensors.get(i).x = x;
                d.sensors.get(i).y = y;
                break;
            case L_LED:
                d.leds.get(i).x = x;
                d.leds.get(i).y = y;
                break;
            case L_PART:
                d.parts.get(i).x = x;
                d.parts.get(i).y = y;
                break;
            case L_BOARD:
                d.boardPts.get(i).x = x;
                d.boardPts.get(i).y = y;
                break;
            default:
                d.casterX = x;
        }
    }

    private int count(int layer) {
        RobotDraft d = ed.draft;
        return layer == L_CHASSIS ? d.chassis.size() : layer == L_SENSOR ? d.sensors.size() : layer == L_LED ? d.leds.size()
                : layer == L_PART ? d.parts.size() : layer == L_BOARD ? (d.customBoard ? d.boardPts.size() : 0)
                : d.wheelLayout == 0 ? 1 : 0;
    }

    private boolean pickable(int layer) {
        return activeLayer < 0 || activeLayer == layer;
    }

    /** The item under a screen point (top-most first), or -1. */
    long hit(Point p) {
        double tol = 7;
        int[] order = {L_SENSOR, L_LED, L_CHASSIS, L_BOARD, L_CASTER, L_PART};
        for (int layer : order) {
            if (!pickable(layer)) {
                continue;
            }
            for (int i = count(layer) - 1; i >= 0; i--) {
                long k = key(layer, i);
                double[] q = pos(k);
                Point2D.Double s = toPx(q[0], q[1]);
                if (layer == L_PART) {
                    Model.Part pt = ed.draft.parts.get(i);
                    Point2D.Double a = toPx(pt.x + pt.l / 2, pt.y - pt.w / 2), b = toPx(pt.x - pt.l / 2, pt.y + pt.w / 2);
                    if (new Rectangle2D.Double(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(b.x - a.x), Math.abs(b.y - a.y))
                            .contains(p) || s.distance(p) < tol) {
                        return k;
                    }
                } else if (s.distance(p) < tol + (layer == L_SENSOR ? ed.draft.spotRadius * zoom * 0.5 : 0)) {
                    return k;
                }
            }
        }
        return -1;
    }

    /** Nothing directly under the point: an edge of the plate or board (its two corners), the inside of the board
     * (the whole array: sensors, LEDs, board corners) or of the plate (all its corners). Empty if none. */
    List<Long> hitGroup(Point p) {
        List<Long> out = new ArrayList<>();
        RobotDraft d = ed.draft;
        double tol = 5;
        int[] polys = {L_CHASSIS, L_BOARD};
        for (int layer : polys) {                                          // edges first
            if (!pickable(layer)) {
                continue;
            }
            int n = count(layer);
            for (int i = 0; i < n && n >= 2; i++) {
                double[] a = pos(key(layer, i)), b = pos(key(layer, (i + 1) % n));
                Point2D.Double sa = toPx(a[0], a[1]), sb = toPx(b[0], b[1]);
                if (Line2D.ptSegDist(sa.x, sa.y, sb.x, sb.y, p.x, p.y) < tol) {
                    out.add(key(layer, i));
                    out.add(key(layer, (i + 1) % n));
                    return out;
                }
            }
        }
        Point2D.Double mm = toMm(p);
        if ((pickable(L_SENSOR) || pickable(L_BOARD)) && !d.sensors.isEmpty()) {
            java.awt.geom.Area b = d.board();
            if (b.isEmpty()) {                                             // sensors on the plate: the area around them
                for (RobotDraft.Pt q : d.sensors) {
                    if (Math.hypot(q.x - mm.x, q.y - mm.y) < d.spotRadius + d.boardMargin) {
                        b = new java.awt.geom.Area(new Rectangle2D.Double(mm.x - 1, mm.y - 1, 2, 2));
                        break;
                    }
                }
            }
            if (b.contains(mm.x, mm.y)) {                                  // the whole array
                for (int i = 0; i < d.sensors.size(); i++) {
                    out.add(key(L_SENSOR, i));
                }
                for (int i = 0; i < d.leds.size(); i++) {
                    out.add(key(L_LED, i));
                }
                for (int i = 0; i < count(L_BOARD); i++) {
                    out.add(key(L_BOARD, i));
                }
                return out;
            }
        }
        if (pickable(L_CHASSIS) && d.chassis.size() >= 3 && d.chassisPath().contains(mm.x, mm.y)) {
            for (int i = 0; i < d.chassis.size(); i++) {
                out.add(key(L_CHASSIS, i));
            }
        }
        return out;
    }

    /** The mirror partner of an item (same layer, at x, -y), or -1. */
    long partnerOf(long k) {
        double[] q = pos(k);
        if (q == null || Math.abs(q[1]) < 0.05) {
            return -1;
        }
        int layer = layerOf(k);
        long best = -1;
        double bd = Math.max(1.0, 6 / zoom);
        for (int i = 0; i < count(layer); i++) {
            long o = key(layer, i);
            if (o == k) {
                continue;
            }
            double[] r = pos(o);
            double d = Math.hypot(r[0] - q[0], r[1] + q[1]);
            if (d < bd) {
                bd = d;
                best = o;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ snapping
    Point2D.Double snap(Point2D.Double mm, Set<Long> exclude, boolean free) {
        if (free) {
            return mm;
        }
        double tol = 7 / zoom;
        if (snapPoints) {
            double bd = tol;
            Point2D.Double best = null;
            for (int layer = 0; layer <= L_LED; layer++) {
                for (int i = 0; i < count(layer); i++) {
                    long k = key(layer, i);
                    if (exclude != null && exclude.contains(k)) {
                        continue;
                    }
                    double[] q = pos(k);
                    double d = Math.hypot(q[0] - mm.x, q[1] - mm.y);
                    if (d < bd) {
                        bd = d;
                        best = new Point2D.Double(q[0], q[1]);
                    }
                }
            }
            for (double[] q : ed.trace.snapPoints()) {                    // corners and circle centres of the drawing
                double d = Math.hypot(q[0] - mm.x, q[1] - mm.y);
                if (d < bd) {
                    bd = d;
                    best = new Point2D.Double(q[0], q[1]);
                }
            }
            if (best != null) {
                return best;
            }
        }
        double x = mm.x, y = mm.y;
        if (snapGrid && grid > 0) {
            x = Math.round(x / grid) * grid;
            y = Math.round(y / grid) * grid;
        }
        if (snapPoints && Math.abs(mm.y) < tol) {
            y = 0;                                                         // the centre line
        }
        return new Point2D.Double(x, y);
    }

    // ------------------------------------------------------------------ mouse
    private void press(MouseEvent e) {
        lastMouse = e.getPoint();
        pressAt = e.getPoint();
        Point2D.Double mm = toMm(e.getPoint());
        pressMm = mm;
        lastMm = mm;
        changedInDrag = false;
        if (SwingUtilities.isMiddleMouseButton(e) || spaceDown || tool == PAN) {
            panning = true;
            return;
        }
        if (SwingUtilities.isRightMouseButton(e)) {                        // right-click: back to Select / clear selection
            if (tool != SELECT) {
                ed.setTool(SELECT);
            } else {
                sel.clear();
                ed.selectionChanged();
            }
            repaint();
            return;
        }
        boolean free = e.isAltDown();
        switch (tool) {
            case TRACE:
                ed.traceAt(mm.x, mm.y, e.isShiftDown() ? 1 : e.isAltDown() ? 2 : 0);
                return;
            case MOVEPIC:
                dragging = true;
                return;
            case SCALEPIC: {
                Point2D.Double s = snap(mm, null, true);
                if (scaleFirst == null) {
                    scaleFirst = new double[]{s.y, s.x};
                    measure = new double[]{s.x, s.y, s.x, s.y};
                    ed.toast("Now click the second point of the known distance");
                } else {
                    double[] a = scaleFirst;
                    scaleFirst = null;
                    measure = null;
                    ed.scaleClicked(a[0], a[1], s.y, s.x);
                }
                repaint();
                return;
            }
            case MEASURE: {
                Point2D.Double s = snap(mm, null, free);
                measure = new double[]{s.x, s.y, s.x, s.y};
                dragging = true;
                break;
            }
            case SENSOR:
            case LED: {
                if (tool == SENSOR && ed.draft.sensors.size() >= Model.MAXS) {
                    ed.toast("At most " + Model.MAXS + " sensors");
                    return;
                }
                ed.pushUndo();
                Point2D.Double s = snap(mm, null, free);
                List<RobotDraft.Pt> list = tool == SENSOR ? ed.draft.sensors : ed.draft.leds;
                list.add(new RobotDraft.Pt(s.x, s.y));
                int layer = tool == SENSOR ? L_SENSOR : L_LED;
                if (tool == LED) {
                    ed.draft.customLeds = true;
                }
                sel.clear();
                sel.add(key(layer, list.size() - 1));
                if (symmetric && Math.abs(s.y) > 0.05 && (tool == LED || list.size() < Model.MAXS)) {
                    list.add(new RobotDraft.Pt(s.x, -s.y));
                }
                ed.changed();
                startMove(e);
                break;
            }
            case PART: {
                ed.pushUndo();
                Point2D.Double s = snap(mm, null, free);
                Model.Part p = new Model.Part("Part " + (ed.draft.parts.size() + 1), 4, s.x, s.y, 1, 1,
                        ed.draft.wheelDia / 2 + ed.draft.plateThick + 5, 10);
                ed.draft.parts.add(p);
                sel.clear();
                sel.add(key(L_PART, ed.draft.parts.size() - 1));
                handle = 2;                                                // drag out its size
                handleStartBox = new Rectangle2D.Double(s.x, s.y, 0, 0);
                dragStart = null;
                ed.draft.useParts = true;
                ed.changed();
                dragging = true;
                break;
            }
            case CORNER: {
                ed.pushUndo();
                long ins = insertCorner(mm, free);
                if (ins >= 0) {
                    sel.clear();
                    sel.add(ins);
                    ed.changed();
                    startMove(e);
                }
                break;
            }
            default: {
                int h = handleAt(e.getPoint());
                if (h >= 0) {
                    ed.pushUndo();
                    handle = h;
                    handleStartBox = selBox();
                    dragStart = new ArrayList<>();
                    for (long k : sel) {
                        double[] q = pos(k);
                        double[] partSize = layerOf(k) == L_PART ? new double[]{ed.draft.parts.get(indexOf(k)).l,
                            ed.draft.parts.get(indexOf(k)).w} : new double[]{0, 0};
                        dragStart.add(new double[]{k, q[0], q[1], partSize[0], partSize[1]});
                    }
                    dragging = true;
                    return;
                }
                long k = hit(e.getPoint());
                if (k >= 0 && e.getClickCount() == 2 && layerOf(k) == L_PART) {
                    ed.editPart(indexOf(k));
                    return;
                }
                if (k < 0 && e.getClickCount() == 2 && (pickable(L_CHASSIS) || pickable(L_BOARD))) {    // double-click an edge: new corner
                    ed.pushUndo();
                    long ins = insertCorner(mm, free);
                    if (ins >= 0) {
                        sel.clear();
                        sel.add(ins);
                        ed.changed();
                    }
                    return;
                }
                List<Long> grp = k < 0 && !e.isControlDown() ? hitGroup(e.getPoint()) : new ArrayList<>();
                if (k >= 0) {
                    if (e.isShiftDown() || e.isControlDown()) {
                        if (!sel.remove(k)) {
                            sel.add(k);
                        }
                    } else if (!sel.contains(k)) {
                        sel.clear();
                        sel.add(k);
                    }
                    ed.selectionChanged();
                    ed.pushUndo();
                    startMove(e);
                } else if (!grp.isEmpty()) {                               // an edge, the whole array or the whole plate
                    if (!e.isShiftDown() && !sel.containsAll(grp)) {
                        sel.clear();
                    }
                    sel.addAll(grp);
                    ed.selectionChanged();
                    ed.pushUndo();
                    startMove(e);
                } else {
                    if (!e.isShiftDown() && !e.isControlDown()) {
                        sel.clear();
                        ed.selectionChanged();
                    }
                    boxSelecting = true;
                    box = new Rectangle(e.getPoint());
                }
            }
        }
        repaint();
    }

    private void startMove(MouseEvent e) {
        dragging = true;
        dragStart = new ArrayList<>();
        partners.clear();
        for (long k : sel) {
            double[] q = pos(k);
            dragStart.add(new double[]{k, q[0], q[1]});
            if (symmetric) {
                long p = partnerOf(k);
                if (p >= 0 && !sel.contains(p)) {
                    partners.add(new long[]{k, p});
                }
            }
        }
    }

    private void drag(MouseEvent e) {
        Point2D.Double mm = toMm(e.getPoint());
        boolean free = e.isAltDown();
        if (panning) {
            offX += e.getX() - lastMouse.x;
            offY += e.getY() - lastMouse.y;
            lastMouse = e.getPoint();
            ed.viewChanged();
            repaint();
            return;
        }
        if (tool == MOVEPIC && dragging) {                                 // slide the picture or the drawing
            double dx = (e.getX() - lastMouse.x) / zoom, dy = -(e.getY() - lastMouse.y) / zoom;
            if (moveDrawing) {
                ed.trace.dwgX += dx;
                ed.trace.dwgY += dy;
            } else {
                ed.trace.picX += dx;
                ed.trace.picY += dy;
            }
            lastMouse = e.getPoint();
            ed.traceMoved();
            repaint();
            return;
        }
        if (tool == SCALEPIC && scaleFirst != null) {
            return;
        }
        lastMouse = e.getPoint();
        if (boxSelecting) {
            box = new Rectangle(Math.min(pressAt.x, e.getX()), Math.min(pressAt.y, e.getY()), Math.abs(e.getX() - pressAt.x),
                    Math.abs(e.getY() - pressAt.y));
            repaint();
            return;
        }
        if (!dragging) {
            return;
        }
        if (tool == MEASURE && measure != null) {
            Point2D.Double s = snap(mm, null, free);
            if (e.isShiftDown()) {                                         // horizontal / vertical / 45 degrees
                double dx = s.x - measure[0], dy = s.y - measure[1], a = Math.round(Math.atan2(dy, dx) / (Math.PI / 4)) * (Math.PI / 4);
                double len = Math.hypot(dx, dy);
                s = new Point2D.Double(measure[0] + len * Math.cos(a), measure[1] + len * Math.sin(a));
            }
            measure[2] = s.x;
            measure[3] = s.y;
            ed.measured(measure);
            repaint();
            return;
        }
        if (handle >= 0) {
            scaleDrag(mm, e.isShiftDown(), free);
            changedInDrag = true;
            ed.changed();
            repaint();
            return;
        }
        if (dragStart == null || dragStart.isEmpty()) {
            return;
        }
        // move: the first selected item follows the (snapped) cursor, the rest keep their offsets
        double[] first = dragStart.get(0);
        double gx = first[1] + (mm.x - pressMm.x), gy = first[2] + (mm.y - pressMm.y);
        Set<Long> ex = new LinkedHashSet<>(sel);
        for (long[] pp : partners) {
            ex.add(pp[1]);
        }
        Point2D.Double s = snap(new Point2D.Double(gx, gy), ex, free);
        if (e.isShiftDown()) {                                             // only along x or y
            if (Math.abs(s.x - first[1]) > Math.abs(s.y - first[2])) {
                s.y = first[2];
            } else {
                s.x = first[1];
            }
        }
        double dx = s.x - first[1], dy = s.y - first[2];
        moveSelection(dx, dy);
        changedInDrag = true;
        ed.changed();
        repaint();
    }

    private void moveSelection(double dx, double dy) {
        for (double[] st : dragStart) {
            long k = (long) st[0];
            double nx = st[1] + dx, ny = st[2] + dy;
            if (layerOf(k) == L_CASTER) {
                ny = 0;
            }
            setPos(k, nx, ny);
            for (long[] pp : partners) {
                if (pp[0] == k) {
                    setPos(pp[1], nx, -ny);
                }
            }
        }
    }

    private void release(MouseEvent e) {
        if (boxSelecting && box != null) {
            for (int layer = 0; layer <= L_BOARD; layer++) {
                if (!pickable(layer)) {
                    continue;
                }
                for (int i = 0; i < count(layer); i++) {
                    long k = key(layer, i);
                    double[] q = pos(k);
                    if (box.contains(toPx(q[0], q[1]))) {
                        sel.add(k);
                    }
                }
            }
            ed.selectionChanged();
        }
        if (tool == PART && handle >= 0 && !sel.isEmpty()) {               // a part dragged out too small: give it a size
            Model.Part p = ed.draft.parts.get(indexOf(sel.iterator().next()));
            if (p.w < 3 || p.l < 3) {
                p.w = Math.max(p.w, 20);
                p.l = Math.max(p.l, 20);
            }
            ed.editPart(ed.draft.parts.indexOf(p));
        }
        if (changedInDrag) {
            ed.committed();
        }
        boxSelecting = false;
        box = null;
        dragging = panning = false;
        handle = -1;
        dragStart = null;
        partners.clear();
        repaint();
    }

    /** Add a corner on the nearest edge of the outline (or at the end when there is no outline yet). */
    /** Add a corner to the plate or the (editable) board outline, on the edge nearest the click. Returns its key or -1. */
    private long insertCorner(Point2D.Double mm, boolean free) {
        RobotDraft dr = ed.draft;
        Point2D.Double s = snap(mm, null, free);
        double dc = edgeDist(dr.chassis, mm), db = dr.customBoard ? edgeDist(dr.boardPts, mm) : Double.MAX_VALUE;
        boolean onBoard = db < dc && pickable(L_BOARD) || !pickable(L_CHASSIS) && dr.customBoard;
        List<RobotDraft.Pt> c = onBoard ? dr.boardPts : dr.chassis;
        int layer = onBoard ? L_BOARD : L_CHASSIS;
        if (c.size() < 3) {
            c.add(new RobotDraft.Pt(s.x, s.y));
            return key(layer, c.size() - 1);
        }
        int best = -1;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < c.size(); i++) {
            RobotDraft.Pt a = c.get(i), b = c.get((i + 1) % c.size());
            double d = Line2D.ptSegDist(a.x, a.y, b.x, b.y, mm.x, mm.y);
            if (d < bd) {
                bd = d;
                best = i;
            }
        }
        if (bd > 25 / zoom) {
            ed.toast("Click on (or near) an edge of the plate or of an editable board to add a corner");
            return -1;
        }
        c.add(best + 1, new RobotDraft.Pt(s.x, s.y));
        if (symmetric && Math.abs(s.y) > 0.05) {                          // the same corner on the other side
            int j = -1;
            double jd = Double.MAX_VALUE;
            for (int i = 0; i < c.size(); i++) {
                RobotDraft.Pt a = c.get(i), b = c.get((i + 1) % c.size());
                double d = Line2D.ptSegDist(a.x, a.y, b.x, b.y, s.x, -s.y);
                if (d < jd && i != best && i != best + 1) {
                    jd = d;
                    j = i;
                }
            }
            if (j >= 0 && jd < 25 / zoom + Math.abs(s.y) * 0.05) {
                c.add(j + 1, new RobotDraft.Pt(s.x, -s.y));
                if (j + 1 <= best + 1) {
                    best++;
                }
            }
        }
        return key(layer, best + 1);
    }

    private static double edgeDist(List<RobotDraft.Pt> c, Point2D.Double mm) {
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < c.size() && c.size() >= 2; i++) {
            RobotDraft.Pt a = c.get(i), b = c.get((i + 1) % c.size());
            bd = Math.min(bd, Line2D.ptSegDist(a.x, a.y, b.x, b.y, mm.x, mm.y));
        }
        return bd;
    }

    // ------------------------------------------------------------------ selection box and scale handles
    Rectangle2D.Double selBox() {
        Rectangle2D.Double r = null;
        for (long k : sel) {
            double[] q = pos(k);
            if (q == null) {
                continue;
            }
            double hl = 0, hw = 0;
            if (layerOf(k) == L_PART) {
                Model.Part p = ed.draft.parts.get(indexOf(k));
                hl = p.l / 2;
                hw = p.w / 2;
            }
            if (r == null) {
                r = new Rectangle2D.Double(q[0] - hl, q[1] - hw, 2 * hl, 2 * hw);
            } else {
                r.add(q[0] - hl, q[1] - hw);
                r.add(q[0] + hl, q[1] + hw);
            }
        }
        return r;
    }

    /** Handle positions in mm: 0 top-left, 1 top, 2 top-right, 3 right, 4 bottom-right, 5 bottom, 6 bottom-left, 7 left (screen). */
    private double[][] handles() {
        Rectangle2D.Double b = selBox();
        if (b == null || sel.size() < 2 && !(sel.size() == 1 && layerOf(sel.iterator().next()) == L_PART)) {
            return null;
        }
        if (b.getWidth() < 0.5 && b.getHeight() < 0.5) {
            return null;
        }
        double x0 = b.getMinX(), x1 = b.getMaxX(), y0 = b.getMinY(), y1 = b.getMaxY(), xm = b.getCenterX(), ym = b.getCenterY();
        return new double[][]{{x1, y0}, {x1, ym}, {x1, y1}, {xm, y1}, {x0, y1}, {x0, ym}, {x0, y0}, {xm, y0}};
    }

    private int handleAt(Point p) {
        if (tool != SELECT) {
            return -1;
        }
        double[][] h = handles();
        if (h == null) {
            return -1;
        }
        for (int i = 0; i < h.length; i++) {
            if (toPx(h[i][0], h[i][1]).distance(p) < 6) {
                return i;
            }
        }
        return -1;
    }

    private void scaleDrag(Point2D.Double mm, boolean uniform, boolean free) {
        Rectangle2D.Double b = handleStartBox;
        Point2D.Double s = snap(mm, sel, free);
        if (tool == PART) {                                                // a new part: drag out its rectangle
            Model.Part p = ed.draft.parts.get(indexOf(sel.iterator().next()));
            p.x = (b.x + s.x) / 2;
            p.y = (b.y + s.y) / 2;
            p.l = Math.abs(s.x - b.x);
            p.w = Math.abs(s.y - b.y);
            return;
        }
        double[][] hs = {{b.getMaxX(), b.getMinY()}, {b.getMaxX(), b.getCenterY()}, {b.getMaxX(), b.getMaxY()}, {b.getCenterX(), b.getMaxY()},
            {b.getMinX(), b.getMaxY()}, {b.getMinX(), b.getCenterY()}, {b.getMinX(), b.getMinY()}, {b.getCenterX(), b.getMinY()}};
        double[] h = hs[handle], o = hs[(handle + 4) % 8];                // pivot: the opposite handle
        boolean sx = handle != 3 && handle != 7, sy = handle != 1 && handle != 5;
        double fx = sx && Math.abs(h[0] - o[0]) > 1e-6 ? (s.x - o[0]) / (h[0] - o[0]) : 1;
        double fy = sy && Math.abs(h[1] - o[1]) > 1e-6 ? (s.y - o[1]) / (h[1] - o[1]) : 1;
        if (uniform) {
            double f = sx && sy ? Math.max(Math.abs(fx), Math.abs(fy)) * Math.signum(fx == 0 ? 1 : fx) : sx ? fx : fy;
            fx = fy = f;
        }
        applyScale(o[0], o[1], fx, fy);
    }

    private void applyScale(double px, double py, double fx, double fy) {
        for (double[] st : dragStart) {
            long k = (long) st[0];
            setPos(k, px + (st[1] - px) * fx, layerOf(k) == L_CASTER ? 0 : py + (st[2] - py) * fy);
            if (layerOf(k) == L_PART) {
                Model.Part p = ed.draft.parts.get(indexOf(k));
                p.l = Math.abs(st[3] * fx);
                p.w = Math.abs(st[4] * fy);
            }
        }
        ed.selectionMoved();
    }

    // ------------------------------------------------------------------ keys
    private void key(KeyEvent e, boolean down) {
        if (e.getKeyCode() == KeyEvent.VK_SPACE) {
            spaceDown = down;
            return;
        }
        if (!down) {
            return;
        }
        double step = e.isShiftDown() ? 10 * Math.max(grid, 0.1) : e.isAltDown() ? 0.1 : Math.max(grid, 0.1);
        double dx = 0, dy = 0;
        switch (e.getKeyCode()) {
            case KeyEvent.VK_UP:
                dx = step;
                break;
            case KeyEvent.VK_DOWN:
                dx = -step;
                break;
            case KeyEvent.VK_LEFT:
                dy = -step;
                break;
            case KeyEvent.VK_RIGHT:
                dy = step;
                break;
            case KeyEvent.VK_DELETE:
            case KeyEvent.VK_BACK_SPACE:
                ed.deleteSelection();
                e.consume();                                               // not also the Builder's Delete
                return;
            case KeyEvent.VK_ESCAPE:
                e.consume();
                scaleFirst = null;
                if (tool == SCALEPIC) {
                    measure = null;
                }
                if (tool != SELECT) {
                    ed.setTool(SELECT);
                } else {
                    sel.clear();
                    ed.selectionChanged();
                }
                repaint();
                return;
            case KeyEvent.VK_PLUS:
            case KeyEvent.VK_EQUALS:
            case KeyEvent.VK_ADD:
                zoomAt(new Point(getWidth() / 2, getHeight() / 2), 1.25);
                return;
            case KeyEvent.VK_MINUS:
            case KeyEvent.VK_SUBTRACT:
                zoomAt(new Point(getWidth() / 2, getHeight() / 2), 0.8);
                return;
            case KeyEvent.VK_0:
            case KeyEvent.VK_F:
                if (!e.isControlDown()) {
                    fit();
                }
                return;
            case KeyEvent.VK_A:
                if (e.isControlDown()) {
                    selectAll();
                    e.consume();
                }
                return;
            default:
                return;
        }
        e.consume();
        if (!sel.isEmpty()) {
            ed.pushUndo();
            startMove(null);
            moveSelection(dx, dy);
            dragStart = null;
            partners.clear();
            ed.changed();
            ed.committed();
            repaint();
        }
    }

    void selectAll() {
        sel.clear();
        for (int layer = 0; layer <= L_BOARD; layer++) {
            if (layer != L_CASTER && pickable(layer)) {
                for (int i = 0; i < count(layer); i++) {
                    sel.add(key(layer, i));
                }
            }
        }
        ed.selectionChanged();
        repaint();
    }

    /** Select every item of one layer. */
    void selectLayer(int layer) {
        sel.clear();
        for (int i = 0; i < count(layer); i++) {
            sel.add(key(layer, i));
        }
        ed.selectionChanged();
        repaint();
    }

    // ------------------------------------------------------------------ painting
    private float alphaFor(int layer) {
        return isolate && activeLayer >= 0 && activeLayer != layer ? 0.18f : 1f;
    }

    private Composite comp(Graphics2D g, int layer) {
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive(alphaFor(layer)));
        return old;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        boolean dark = Theme.isDark();
        g.setColor(dark ? new Color(0x26282C) : new Color(0xF4F5F7));
        g.fillRect(0, 0, getWidth(), getHeight());
        if (showGrid) {
            paintGrid(g, dark);
        }
        ed.trace.paint(g, offX, offY, zoom, dark);                         // the picture / drawing to trace over
        RobotDraft d = ed.draft;
        Model m = ed.m;
        AffineTransform w = new AffineTransform(0, -zoom, zoom, 0, offX, offY);   // (x, y) mm -> (offX + y z, offY - x z)
        Graphics2D gw = (Graphics2D) g.create();
        gw.transform(w);
        float u = (float) (1 / zoom);
        // axle and centre lines
        gw.setStroke(new BasicStroke(u, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{6 * u, 4 * u}, 0));
        gw.setColor(dark ? new Color(0x6A707A) : new Color(0x9AA0A8));
        gw.draw(new Line2D.Double(0, -1e4, 0, 1e4));
        gw.draw(new Line2D.Double(-1e4, 0, 1e4, 0));
        // chassis plate
        Composite oc = comp(gw, L_CHASSIS);
        if (d.chassis.size() >= 3) {
            gw.setColor(dark ? new Color(0x5B6B7E) : new Color(0xC9D3DE));
            gw.fill(d.chassisPath());
            gw.setColor(dark ? new Color(0xB8C4D2) : new Color(0x51606F));
            gw.setStroke(new BasicStroke(1.4f * u));
            gw.draw(d.chassisPath());
        } else {
            gw.setColor(new Color(0x51606F));
            gw.setStroke(new BasicStroke(1.4f * u));
            for (int i = 0; i + 1 < d.chassis.size(); i++) {
                gw.draw(new Line2D.Double(d.chassis.get(i).x, d.chassis.get(i).y, d.chassis.get(i + 1).x, d.chassis.get(i + 1).y));
            }
        }
        gw.setComposite(oc);
        // wheels (from the Hardware tab)
        paintWheels(gw, m, u, dark);
        // parts
        oc = comp(gw, L_PART);
        Font pf = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
        for (int i = 0; i < d.parts.size(); i++) {
            Model.Part p = d.parts.get(i);
            if (p.w <= 0 || p.l <= 0) {
                continue;
            }
            Rectangle2D.Double r = new Rectangle2D.Double(p.x - p.l / 2, p.y - p.w / 2, p.l, p.w);
            Color c = partColor(p.kind);
            gw.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), d.useParts ? 120 : 45));
            gw.fill(r);
            gw.setColor(c.darker());
            gw.setStroke(new BasicStroke(u));
            gw.draw(r);
        }
        gw.setComposite(oc);
        // sensor board and mount holes
        oc = comp(gw, L_SENSOR);
        if (!d.sensors.isEmpty()) {
            Area b = d.board();
            gw.setColor(new Color(0x1F7A4A).darker());
            gw.setComposite(AlphaComposite.SrcOver.derive(0.85f * alphaFor(L_SENSOR)));
            gw.fill(b);
            gw.setComposite(AlphaComposite.SrcOver.derive(alphaFor(L_SENSOR)));
            gw.setColor(new Color(0x7DD3A0));
            gw.setStroke(new BasicStroke(u));
            gw.draw(b);
            for (double[] h : d.holes()) {
                gw.setColor(dark ? new Color(0x26282C) : Color.WHITE);
                gw.fill(new Ellipse2D.Double(h[0] - d.holeDia / 2, h[1] - d.holeDia / 2, d.holeDia, d.holeDia));
                gw.setColor(new Color(0xE0E0E0));
                gw.draw(new Ellipse2D.Double(h[0] - d.holeDia / 2, h[1] - d.holeDia / 2, d.holeDia, d.holeDia));
            }
        }
        // sensors: the spot, and the emitter / receiver when they are drawn apart
        if (m.sensBodyW > 0 && m.sensBodyL > 0) {                         // the sensor parts themselves
            for (RobotDraft.Pt p : d.sensors) {
                Rectangle2D.Double body = new Rectangle2D.Double(p.x - m.sensBodyL / 2, p.y - m.sensBodyW / 2, m.sensBodyL, m.sensBodyW);
                gw.setColor(new Color(0x1F2937));
                gw.fill(body);
                gw.setColor(new Color(0x9CA3AF));
                gw.setStroke(new BasicStroke(0.6f * u));
                gw.draw(body);
            }
        }
        for (int i = 0; i < d.sensors.size(); i++) {
            RobotDraft.Pt p = d.sensors.get(i);
            double r = Math.max(d.spotRadius, 0.8);
            gw.setColor(new Color(0xFFF2B0));
            gw.fill(new Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r));
            gw.setColor(new Color(0x7A5B00));
            gw.setStroke(new BasicStroke(0.8f * u));
            gw.draw(new Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r));
            if (d.pairGap > 0) {
                double half = d.pairGap / 2 * (d.pairAlternate && sortedRank(i) % 2 == 1 ? -1 : 1);
                double ex = d.pairOrient == 1 ? p.x - half : p.x, ey = d.pairOrient == 1 ? p.y : p.y - half;
                double rx = d.pairOrient == 1 ? p.x + half : p.x, ry = d.pairOrient == 1 ? p.y : p.y + half;
                double s = Math.min(2.2, Math.max(1.0, d.pairGap * 0.35));
                gw.setColor(new Color(0x3B82F6));
                gw.fill(new Ellipse2D.Double(ex - s, ey - s, 2 * s, 2 * s));
                gw.setColor(new Color(0x111111));
                gw.fill(new Rectangle2D.Double(rx - s, ry - s, 2 * s, 2 * s));
            }
        }
        gw.setComposite(oc);
        // LEDs
        oc = comp(gw, L_LED);
        Color lc = Model.LED_RGB[Math.max(0, Math.min(Model.LED_RGB.length - 1, m.ledColor))];
        for (RobotDraft.Pt p : d.leds) {
            double r = 1.6;
            gw.setColor(d.customLeds ? lc : new Color(lc.getRed(), lc.getGreen(), lc.getBlue(), 110));
            gw.fill(new Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r));
            gw.setColor(new Color(0x444444));
            gw.setStroke(new BasicStroke(0.6f * u));
            gw.draw(new Ellipse2D.Double(p.x - r, p.y - r, 2 * r, 2 * r));
        }
        gw.setComposite(oc);
        // caster (2 wheels only)
        oc = comp(gw, L_CASTER);
        if (d.wheelLayout == 0) {
        gw.setColor(new Color(0x6B7280));
        gw.fill(new Ellipse2D.Double(d.casterX - 6, -6, 12, 12));
        gw.setColor(new Color(0x222222));
        gw.setStroke(new BasicStroke(u));
        gw.draw(new Ellipse2D.Double(d.casterX - 6, -6, 12, 12));
        }
        gw.setComposite(oc);
        // centre of mass
        double[] mc = d.useParts ? d.massCentre(d.wheelDia) : null;
        if (mc != null && mc[0] > 0) {
            double r = 4;
            gw.setColor(Color.WHITE);
            gw.fill(new Ellipse2D.Double(mc[1] - r, mc[2] - r, 2 * r, 2 * r));
            gw.setColor(Color.BLACK);
            gw.fill(new Arc2D.Double(mc[1] - r, mc[2] - r, 2 * r, 2 * r, 0, 90, Arc2D.PIE));
            gw.fill(new Arc2D.Double(mc[1] - r, mc[2] - r, 2 * r, 2 * r, 180, 90, Arc2D.PIE));
            gw.setStroke(new BasicStroke(u));
            gw.draw(new Ellipse2D.Double(mc[1] - r, mc[2] - r, 2 * r, 2 * r));
        }
        gw.dispose();
        // labels in screen space (so they stay readable at any zoom)
        g.setFont(pf);
        for (Model.Part p : d.parts) {
            if (p.w > 0 && p.l > 0 && alphaFor(L_PART) > 0.5) {
                Point2D.Double s = toPx(p.x, p.y);
                String t = p.name + (p.massG > 0 ? " " + Math.round(p.massG) + " g" : "");
                int tw = g.getFontMetrics().stringWidth(t);
                if (tw < p.w * zoom + 30) {
                    g.setColor(dark ? new Color(0xE8EAED) : new Color(0x1F2937));
                    g.drawString(t, (int) (s.x - tw / 2.0), (int) s.y + 4);
                }
            }
        }
        if (zoom > 2.2 && alphaFor(L_SENSOR) > 0.5) {                     // sensor numbers (left to right, as the controller counts)
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 10));
            for (int i = 0; i < d.sensors.size(); i++) {
                RobotDraft.Pt p = d.sensors.get(i);
                Point2D.Double s = toPx(p.x, p.y);
                String t = Integer.toString(sortedRank(i) + 1) + (p.pin != null ? " " + p.pin : "");
                g.setColor(dark ? new Color(0xFDE68A) : new Color(0x7A5B00));
                g.drawString(t, (int) s.x - g.getFontMetrics().stringWidth(t) / 2, (int) (s.y - Math.max(d.spotRadius, 0.8) * zoom - 3));
            }
        }
        paintSelection(g);
        paintMeasure(g, dark);
        paintRulers(g, dark);
        if (box != null) {
            g.setColor(new Color(0x3B82F6));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{4, 3}, 0));
            g.draw(box);
            g.setColor(new Color(59, 130, 246, 30));
            g.fill(box);
        }
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        g.setColor(dark ? new Color(0x9AA0A8) : new Color(0x6B7280));
        g.drawString("front", (int) offX - 13, 30);
        g.dispose();
    }

    /** Index of sensor i when the sensors are counted left to right. */
    int sortedRank(int i) {
        List<RobotDraft.Pt> s = ed.draft.sensors;
        int r = 0;
        for (int j = 0; j < s.size(); j++) {
            if (s.get(j).y < s.get(i).y || s.get(j).y == s.get(i).y && j < i) {
                r++;
            }
        }
        return r;
    }

    static Color partColor(int kind) {
        switch (kind) {
            case 0:
                return new Color(0xF59E0B);
            case 1:
                return new Color(0x3B82F6);
            case 2:
                return new Color(0xEF4444);
            case 3:
                return new Color(0x9CA3AF);
            default:
                return new Color(0xA855F7);
        }
    }

    private void paintWheels(Graphics2D gw, Model m, float u, boolean dark) {
        RobotDraft d = ed.draft;
        double r = d.wheelDia / 2, tw = Math.max(d.tireWidth, 3);
        boolean driven = true;
        int wi = 0;
        for (double[] wp : d.wheels()) {
            boolean hit = wi < blockedWheels.length && blockedWheels[wi];
            wi++;
            Shape w = new Rectangle2D.Double(wp[0] - r, wp[1] - tw / 2, 2 * r, tw);
            if (wp[2] > 0) {                                               // a steered wheel: drawn turned to half lock
                w = AffineTransform.getRotateInstance(Math.toRadians(d.steerLock / 2), wp[0], wp[1]).createTransformedShape(w);
            }
            driven = wp[2] == 0 || d.wheelLayout == 3;
            gw.setColor(dark ? new Color(0x111214) : new Color(0x2B2F36));
            gw.fill(w);
            gw.setColor(hit ? new Color(0xEF4444) : driven ? new Color(0xF59E0B) : new Color(0x6B7280));   // orange = driven, red = blocked
            gw.setStroke(new BasicStroke(u * (hit ? 2.5f : driven ? 1.5f : 1f)));
            gw.draw(w);
        }
    }

    private void paintGrid(Graphics2D g, boolean dark) {
        double step = grid;
        while (step * zoom < 6) {
            step *= 2;
        }
        double major = step * (step == grid ? (grid >= 5 ? 2 : 10 / Math.max(grid, 0.1) >= 2 ? Math.round(10 / grid) : 5) : 5);
        Point2D.Double a = toMm(new Point(0, getHeight())), b = toMm(new Point(getWidth(), 0));
        Color minor = dark ? new Color(0x30333A) : new Color(0xE3E6EA), maj = dark ? new Color(0x3A3E46) : new Color(0xCDD2D9);
        for (double x = Math.floor(a.x / step) * step; x <= b.x; x += step) {
            int py = (int) Math.round(toPx(x, 0).y);
            boolean isMajor = Math.abs(x / major - Math.rint(x / major)) < 1e-6;
            if (dotGrid) {
                if (!isMajor) {
                    continue;
                }
            }
            g.setColor(isMajor ? maj : minor);
            if (!dotGrid) {
                g.drawLine(0, py, getWidth(), py);
            }
        }
        for (double y = Math.floor(a.y / step) * step; y <= b.y; y += step) {
            int px = (int) Math.round(toPx(0, y).x);
            boolean isMajor = Math.abs(y / major - Math.rint(y / major)) < 1e-6;
            g.setColor(isMajor ? maj : minor);
            if (!dotGrid) {
                g.drawLine(px, 0, px, getHeight());
            }
        }
        if (dotGrid) {
            g.setColor(maj);
            for (double x = Math.floor(a.x / step) * step; x <= b.x; x += step) {
                for (double y = Math.floor(a.y / step) * step; y <= b.y; y += step) {
                    Point2D.Double s = toPx(x, y);
                    g.fillRect((int) s.x, (int) s.y, 1, 1);
                }
            }
        }
    }

    private void paintSelection(Graphics2D g) {
        g.setStroke(new BasicStroke(1.5f));
        for (long k : sel) {
            double[] q = pos(k);
            if (q == null) {
                continue;
            }
            Point2D.Double s = toPx(q[0], q[1]);
            g.setColor(new Color(0x3B82F6));
            g.drawRect((int) s.x - 5, (int) s.y - 5, 10, 10);
        }
        if (tool == SELECT || tool == CORNER) {                            // chassis corners as small squares
            g.setStroke(new BasicStroke(1f));
            if (pickable(L_BOARD) && ed.draft.customBoard) {
                for (RobotDraft.Pt p : ed.draft.boardPts) {
                    Point2D.Double s = toPx(p.x, p.y);
                    g.setColor(new Color(0xBBF7D0));
                    g.fillRect((int) s.x - 3, (int) s.y - 3, 6, 6);
                    g.setColor(new Color(0x14532D));
                    g.drawRect((int) s.x - 3, (int) s.y - 3, 6, 6);
                }
            }
            if (pickable(L_CHASSIS)) {
                for (RobotDraft.Pt p : ed.draft.chassis) {
                    Point2D.Double s = toPx(p.x, p.y);
                    g.setColor(Color.WHITE);
                    g.fillRect((int) s.x - 3, (int) s.y - 3, 6, 6);
                    g.setColor(new Color(0x334155));
                    g.drawRect((int) s.x - 3, (int) s.y - 3, 6, 6);
                }
            }
        }
        double[][] h = handles();
        Rectangle2D.Double b = selBox();
        if (h != null && b != null && tool == SELECT) {
            Point2D.Double a = toPx(b.getMaxX(), b.getMinY()), c = toPx(b.getMinX(), b.getMaxY());
            g.setColor(new Color(0x3B82F6));
            g.setStroke(new BasicStroke(1f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10, new float[]{5, 3}, 0));
            g.draw(new Rectangle2D.Double(Math.min(a.x, c.x), Math.min(a.y, c.y), Math.abs(c.x - a.x), Math.abs(c.y - a.y)));
            g.setStroke(new BasicStroke(1f));
            for (double[] q : h) {
                Point2D.Double s = toPx(q[0], q[1]);
                g.setColor(Color.WHITE);
                g.fillRect((int) s.x - 4, (int) s.y - 4, 8, 8);
                g.setColor(new Color(0x3B82F6));
                g.drawRect((int) s.x - 4, (int) s.y - 4, 8, 8);
            }
            String t = String.format(Locale.ROOT, "%.1f x %.1f mm", b.getHeight(), b.getWidth());   // width (y) x length (x)
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
            g.drawString(t, (int) Math.min(a.x, c.x), (int) Math.min(a.y, c.y) - 6);
        }
    }

    private void paintMeasure(Graphics2D g, boolean dark) {
        if (measure == null) {
            return;
        }
        Point2D.Double a = toPx(measure[0], measure[1]), b = toPx(measure[2], measure[3]);
        g.setColor(new Color(0xDB2777));
        g.setStroke(new BasicStroke(1.5f));
        g.draw(new Line2D.Double(a, b));
        for (Point2D.Double p : new Point2D.Double[]{a, b}) {
            g.draw(new Line2D.Double(p.x - 5, p.y, p.x + 5, p.y));
            g.draw(new Line2D.Double(p.x, p.y - 5, p.x, p.y + 5));
        }
        double dx = measure[2] - measure[0], dy = measure[3] - measure[1];
        String t = String.format(Locale.ROOT, "%.2f mm  (across %.2f, along %.2f, %.1f deg)", Math.hypot(dx, dy), dy, dx,
                Math.toDegrees(Math.atan2(dx, dy)));
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        int tw = g.getFontMetrics().stringWidth(t);
        int tx = (int) ((a.x + b.x) / 2 - tw / 2.0), ty = (int) ((a.y + b.y) / 2) - 8;
        g.setColor(dark ? new Color(30, 31, 34, 220) : new Color(255, 255, 255, 220));
        g.fillRoundRect(tx - 4, ty - 13, tw + 8, 17, 6, 6);
        g.setColor(new Color(0xDB2777));
        g.drawString(t, tx, ty);
    }

    /** mm rulers along the top and the left edge. */
    private void paintRulers(Graphics2D g, boolean dark) {
        int rs = 16;
        g.setColor(dark ? new Color(0x1E1F22) : new Color(0xFFFFFF));
        g.fillRect(0, 0, getWidth(), rs);
        g.fillRect(0, 0, rs, getHeight());
        g.setColor(dark ? new Color(0x80858F) : new Color(0x6B7280));
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 9));
        double step = 1;
        while (step * zoom < 7) {
            step *= step == 1 ? 5 : 2;
        }
        double lab = step * 10;
        while (lab * zoom < 50) {
            lab *= 2;
        }
        Point2D.Double a = toMm(new Point(0, getHeight())), b = toMm(new Point(getWidth(), 0));
        for (double y = Math.floor(a.y / step) * step; y <= b.y; y += step) {
            int px = (int) Math.round(toPx(0, y).x);
            boolean l = Math.abs(y / lab - Math.rint(y / lab)) < 1e-6;
            g.drawLine(px, rs, px, rs - (l ? 9 : 4));
            if (l) {
                g.drawString(fmt(y), px + 2, 9);
            }
        }
        for (double x = Math.floor(a.x / step) * step; x <= b.x; x += step) {
            int py = (int) Math.round(toPx(x, 0).y);
            boolean l = Math.abs(x / lab - Math.rint(x / lab)) < 1e-6;
            g.drawLine(rs, py, rs - (l ? 9 : 4), py);
            if (l) {
                Graphics2D gr = (Graphics2D) g.create();
                gr.rotate(-Math.PI / 2, 9, py - 2);
                gr.drawString(fmt(x), 9, py - 2);
                gr.dispose();
            }
        }
        if (hoverMm != null) {                                             // cursor marks on the rulers
            Point2D.Double s = toPx(hoverMm.x, hoverMm.y);
            g.setColor(new Color(0xDB2777));
            g.drawLine((int) s.x, 0, (int) s.x, rs);
            g.drawLine(0, (int) s.y, rs, (int) s.y);
        }
        g.setColor(dark ? new Color(0x1E1F22) : Color.WHITE);
        g.fillRect(0, 0, rs, rs);
    }

    private static String fmt(double v) {
        return Math.abs(v - Math.rint(v)) < 1e-6 ? Long.toString(Math.round(v)) : String.format(Locale.ROOT, "%.1f", v);
    }
}
