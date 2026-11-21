package linefollowersim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Track builder: creates, selects, moves, reshapes and deletes the shapes of the
 * track, with snapping and undo/redo. It has no Swing components; TrackPanel
 * feeds it mouse and key events in world (mm) coordinates and asks it to paint
 * its overlay (grid, shape being drawn, selection handles).
 */
public class TrackEditor {

    static final int SELECT = 0, FREEHAND = 1, LINE = 2, POLYLINE = 3, CURVE = 4, ARC = 5, RECT = 6,
            ELLIPSE = 7, ERASE = 8, ROBOT = 9, PAN = 10, GATE = 11, MEASURE = 12;
    static final String[] TOOL_NAMES = {"Select", "Freehand", "Line", "Polyline", "Curve", "Arc", "Rectangle",
        "Ellipse", "Erase", "Robot", "Pan", "Finish line", "Measure"};
    Point2D.Double measureA, measureB;
    private Point2D.Double gateStart;

    final Model m;
    int tool = SELECT;

    // style for new shapes; also shows / edits the selected shape
    double width = 20, dash = 0, gap = 0, radius = 0;
    boolean closed, filled, erase;

    boolean snapGrid = true, snapPoints = true, showGrid = false;
    double grid = 10;
    int gridStyle = 0;                                    // 0 lines, 1 dots
    double dotSize = 2;                                   // screen pixels
    int majorEvery = 10;                                  // a stronger line / dot every n cells (0 = none)
    int gridOpacity = 25;                                 // %

    TrackShape selected;
    int styleVersion, selVersion;                         // bumped so the UI knows to refresh
    int changeCount;                                      // bumped on every change to the shapes (unsaved-work check)

    private double scale = 1;                             // px per mm of the current view
    private TrackShape drawing;                           // shape being created
    private Point2D.Double hoverPt;                       // cursor position after snapping
    private List<TrackShape> pendingSnap;                 // track as it was before the current gesture
    private final Deque<List<TrackShape>> undoStack = new ArrayDeque<>(), redoStack = new ArrayDeque<>();
    private int dragHandle = -1;
    private boolean moving, dragged;
    private Point2D.Double dragStart;
    private TrackShape moveOrig;
    private TrackShape lastStyleEdit;
    private Point2D.Double robotAnchor;

    TrackEditor(Model m) {
        this.m = m;
        m.addTrackListener(this::trackReplaced);
    }

    void setScale(double s) {
        scale = Math.max(s, 1e-6);
    }

    /** The whole track was replaced (preset loaded, file opened): forget selection and history. */
    private void trackReplaced() {
        drawing = null;
        pendingSnap = null;
        dragHandle = -1;
        moving = false;
        selected = null;
        lastStyleEdit = null;
        undoStack.clear();
        redoStack.clear();
        selVersion++;
        styleVersion++;
    }

    // ------------------------------------------------------------------ history
    private List<TrackShape> snapshot() {
        List<TrackShape> l = new ArrayList<>();
        for (TrackShape s : m.shapes) {
            l.add(s.copy());
        }
        return l;
    }

    private void pushUndo(List<TrackShape> snap) {
        undoStack.push(snap);
        while (undoStack.size() > 100) {
            undoStack.removeLast();
        }
        redoStack.clear();
    }

    private void commitPending() {
        changeCount++;
        if (pendingSnap != null) {
            pushUndo(pendingSnap);
            pendingSnap = null;
        }
    }

    boolean canUndo() {
        return !undoStack.isEmpty();
    }

    boolean canRedo() {
        return !redoStack.isEmpty();
    }

    void undo() {
        cancelDrawing();
        if (undoStack.isEmpty()) {
            return;
        }
        redoStack.push(snapshot());
        m.setShapes(undoStack.pop());
        afterChange();
        select(null);
    }

    void redo() {
        cancelDrawing();
        if (redoStack.isEmpty()) {
            return;
        }
        undoStack.push(snapshot());
        m.setShapes(redoStack.pop());
        afterChange();
        select(null);
    }

    private void afterChange() {
        changeCount++;
        m.markEdited();
        m.renderTrack();
    }

    // ------------------------------------------------------------------ commands
    void setTool(int t) {
        if (t == tool) {
            return;
        }
        finish();                                          // keep a polyline / curve that is half drawn
        cancelDrawing();
        tool = t;
        hoverPt = null;
    }

    /** Scale (f), rotate (deg) or mirror (fx, fy = -1) the selected shape about its centre. */
    boolean transformSelected(double sx, double sy, double deg) {
        if (selected == null) {
            return false;
        }
        pushUndo(snapshot());
        Rectangle2D b = selected.bounds();
        java.awt.geom.AffineTransform at = new java.awt.geom.AffineTransform();
        at.translate(b.getCenterX(), b.getCenterY());
        at.rotate(Math.toRadians(deg));
        at.scale(sx, sy);
        at.translate(-b.getCenterX(), -b.getCenterY());
        selected.transform(at);
        afterChange();
        return true;
    }

    void deleteSelected() {
        if (selected == null) {
            return;
        }
        pushUndo(snapshot());
        m.shapes.remove(selected);
        select(null);
        afterChange();
    }

    void duplicateSelected() {
        if (selected == null) {
            return;
        }
        pushUndo(snapshot());
        TrackShape c = selected.copy();
        c.translate(30, 30);
        m.shapes.add(c);
        select(c);
        afterChange();
    }

    void clearAll() {
        cancelDrawing();
        pushUndo(snapshot());
        select(null);
        m.clearTrack();
    }

    /** Enter / double-click / right-click: finish a polyline, curve or arc that is being drawn. */
    void finish() {
        if (drawing == null) {
            return;
        }
        boolean multi = drawing.type == TrackShape.Type.POLYLINE && (tool == POLYLINE)
                || drawing.type == TrackShape.Type.CURVE || drawing.type == TrackShape.Type.ARC;
        if (!multi) {
            return;
        }
        if (drawing.type == TrackShape.Type.ARC) {
            cancelDrawing();                               // an arc needs all three clicks
            return;
        }
        if (drawing.pts.size() >= 2) {
            commit(drawing);
        } else {
            cancelDrawing();
        }
    }

    /** Escape: cancel what is being drawn, else go back to the Select tool, else deselect. */
    void escape() {
        if (drawing != null) {
            cancelDrawing();
        } else if (tool != SELECT) {
            setTool(SELECT);
        } else {
            select(null);
        }
    }

    private void cancelDrawing() {
        drawing = null;
        pendingSnap = null;
    }

    private void commit(TrackShape s) {
        m.shapes.add(s);
        commitPending();
        drawing = null;
        afterChange();
    }

    // ------------------------------------------------------------------ selection and style
    void select(TrackShape s) {
        if (selected == s) {
            return;
        }
        selected = s;
        lastStyleEdit = null;
        selVersion++;
        if (s != null) {
            width = s.width;
            dash = s.dash;
            gap = s.gap;
            radius = s.radius;
            closed = s.closed;
            filled = s.filled;
            erase = s.erase;
            styleVersion++;
        }
    }

    /** Copy the current style (width, dash ...) into the selected shape. */
    void applyToSelection() {
        TrackShape s = selected;
        if (s == null) {
            return;
        }
        if (lastStyleEdit != s) {                          // one undo step for a run of style changes
            pushUndo(snapshot());
            lastStyleEdit = s;
        }
        s.width = width;
        s.dash = dash;
        s.gap = gap;
        s.erase = erase;
        if (s.type == TrackShape.Type.RECT) {
            s.radius = radius;
        }
        if (s.type == TrackShape.Type.POLYLINE || s.type == TrackShape.Type.CURVE) {
            s.closed = closed;
        }
        if (s.type == TrackShape.Type.RECT || s.type == TrackShape.Type.ELLIPSE
                || s.type == TrackShape.Type.POLYLINE || s.type == TrackShape.Type.CURVE) {
            s.filled = filled;
        }
        afterChange();
    }

    private TrackShape newShape(TrackShape.Type t, boolean eraser) {
        TrackShape s = new TrackShape(t);
        s.width = eraser ? width * 2 : width;
        s.dash = dash;
        s.gap = gap;
        s.erase = eraser || erase;
        if (t == TrackShape.Type.RECT) {
            s.radius = radius;
        }
        if (t == TrackShape.Type.POLYLINE || t == TrackShape.Type.CURVE) {
            s.closed = closed;
        }
        if (t == TrackShape.Type.RECT || t == TrackShape.Type.ELLIPSE || t == TrackShape.Type.POLYLINE
                || t == TrackShape.Type.CURVE) {
            s.filled = filled;
        }
        return s;
    }

    // ------------------------------------------------------------------ snapping
    private Point2D.Double snap(Point2D w, boolean alt, TrackShape exclude) {
        if (!alt) {
            if (snapPoints) {
                double best = 10 / scale;
                Point2D.Double bp = null;
                List<TrackShape> all = new ArrayList<>(m.shapes);
                if (drawing != null) {
                    all.add(drawing);
                }
                for (TrackShape s : all) {
                    if (s == exclude || !s.editable()) {
                        continue;
                    }
                    for (Point2D.Double p : s.pts) {
                        double d = p.distance(w);
                        if (d < best) {
                            best = d;
                            bp = p;
                        }
                    }
                }
                if (bp != null) {
                    return new Point2D.Double(bp.x, bp.y);
                }
            }
            if (snapGrid && grid > 0) {
                return new Point2D.Double(Math.round(w.getX() / grid) * grid, Math.round(w.getY() / grid) * grid);
            }
        }
        return new Point2D.Double(w.getX(), w.getY());
    }

    private boolean snapsTool() {
        return tool == GATE || tool == MEASURE || tool == LINE || tool == POLYLINE || tool == CURVE || tool == ARC || tool == RECT || tool == ELLIPSE;
    }

    // ------------------------------------------------------------------ mouse events (world coordinates)
    void press(Point2D w, boolean left, int clicks, boolean shift, boolean alt) {
        boolean right = !left;
        if (right && drawing == null) {                    // right-click with nothing half drawn: back to Select
            if (tool == SELECT) {
                select(null);
            } else {
                setTool(SELECT);
            }
            return;
        }
        if (right && tool != POLYLINE && tool != CURVE && tool != ARC) {
            cancelDrawing();
            return;
        }
        switch (tool) {
            case MEASURE:
                measureA = snap(w, alt, null);
                measureB = measureA;
                break;
            case GATE:
                gateStart = snap(w, alt, null);
                break;
            case ROBOT:
                robotAnchor = new Point2D.Double(w.getX(), w.getY());
                m.placeRobot(w.getX(), w.getY(), m.sth);
                break;
            case SELECT:
                if (left) {
                    selectPress(w);
                }
                break;
            case FREEHAND:
            case ERASE:
                beginFreehand(w, tool == ERASE);
                break;
            case LINE:
                if (left) {
                    beginDrag(TrackShape.Type.LINE, w, alt);
                }
                break;
            case RECT:
                if (left) {
                    beginDrag(TrackShape.Type.RECT, w, alt);
                }
                break;
            case ELLIPSE:
                if (left) {
                    beginDrag(TrackShape.Type.ELLIPSE, w, alt);
                }
                break;
            case POLYLINE:
            case CURVE:
            case ARC:
                multiPress(w, right, clicks, alt);
                break;
            default:
                break;
        }
    }

    void drag(Point2D w, boolean shift, boolean alt) {
        if (snapsTool()) {
            hoverPt = snap(w, alt, null);
        }
        switch (tool) {
            case MEASURE:
                if (measureA != null) {
                    measureB = shift ? constrain(measureA, w, true) : snap(w, alt, null);
                }
                break;
            case GATE:
                if (gateStart != null) {
                    Point2D.Double e = shift ? constrain(gateStart, w, true) : snap(w, alt, null);
                    if (gateStart.distance(e) > 5) {
                        m.setGate(gateStart.x, gateStart.y, e.x, e.y);
                    }
                }
                break;
            case ROBOT:
                if (robotAnchor != null) {
                    double ang = m.sth;
                    if (robotAnchor.distance(w) * scale > 8) {
                        ang = Math.atan2(w.getY() - robotAnchor.y, w.getX() - robotAnchor.x);
                    }
                    m.placeRobot(robotAnchor.x, robotAnchor.y, ang);
                }
                break;
            case SELECT:
                selectDrag(w, alt);
                break;
            case FREEHAND:
            case ERASE:
                if (drawing != null) {
                    Point2D.Double last = drawing.pts.get(drawing.pts.size() - 1);
                    if (last.distance(w) > Math.max(1.0, 2.5 / scale)) {
                        drawing.pts.add(new Point2D.Double(w.getX(), w.getY()));
                    }
                }
                break;
            case LINE:
            case RECT:
            case ELLIPSE:
                if (drawing != null && drawing.pts.size() == 2) {
                    Point2D.Double p0 = drawing.pts.get(0);
                    Point2D.Double q = shift ? constrain(p0, w, tool == LINE) : snap(w, alt, null);
                    drawing.pts.get(1).setLocation(q);
                }
                break;
            default:
                break;
        }
    }

    void release(Point2D w) {
        switch (tool) {
            case GATE:
                if (gateStart != null) {
                    changeCount++;
                }
                gateStart = null;
                break;
            case ROBOT:
                robotAnchor = null;
                break;
            case SELECT:
                selectRelease();
                break;
            case FREEHAND:
            case ERASE:
                finishFreehand();
                break;
            case LINE:
            case RECT:
            case ELLIPSE:
                if (drawing != null && drawing.pts.size() == 2) {
                    if (drawing.pts.get(0).distance(drawing.pts.get(1)) < Math.max(1.0, 2.0 / scale)) {
                        cancelDrawing();
                    } else {
                        commit(drawing);
                    }
                }
                break;
            default:
                break;
        }
    }

    /** Mouse moved with no button down. */
    void hover(Point2D w, boolean alt) {
        hoverPt = snapsTool() ? snap(w, alt, null) : null;
    }

    private static Point2D.Double constrain(Point2D.Double p0, Point2D w, boolean line) {
        double dx = w.getX() - p0.x, dy = w.getY() - p0.y;
        if (line) {                                        // lines: multiples of 15 degrees
            double step = Math.PI / 12, a = Math.round(Math.atan2(dy, dx) / step) * step, d = Math.hypot(dx, dy);
            return new Point2D.Double(p0.x + d * Math.cos(a), p0.y + d * Math.sin(a));
        }
        double s = Math.max(Math.abs(dx), Math.abs(dy));   // rectangle / ellipse: square / circle
        return new Point2D.Double(p0.x + (dx >= 0 ? s : -s), p0.y + (dy >= 0 ? s : -s));
    }

    // ---- select / move / reshape
    private TrackShape pick(Point2D w) {
        for (int i = m.shapes.size() - 1; i >= 0; i--) {
            if (m.shapes.get(i).hit(w, 6 / scale)) {
                return m.shapes.get(i);
            }
        }
        return null;
    }

    private int handleAt(TrackShape s, Point2D w) {
        if (!s.editable()) {
            return -1;
        }
        for (int i = 0; i < s.pts.size(); i++) {
            if (s.pts.get(i).distance(w) < 8 / scale) {
                return i;
            }
        }
        return -1;
    }

    private void selectPress(Point2D w) {
        dragHandle = -1;
        moving = false;
        dragged = false;
        if (selected != null) {
            int h = handleAt(selected, w);
            if (h >= 0) {
                pendingSnap = snapshot();
                dragHandle = h;
                return;
            }
        }
        TrackShape hit = pick(w);
        select(hit);
        if (hit != null) {
            pendingSnap = snapshot();
            moving = true;
            dragStart = new Point2D.Double(w.getX(), w.getY());
            moveOrig = hit.copy();
        }
    }

    private void selectDrag(Point2D w, boolean alt) {
        if (selected == null) {
            return;
        }
        if (dragHandle >= 0) {
            selected.pts.get(dragHandle).setLocation(snap(w, alt, selected));
            dragged = true;
            m.markEdited();
            m.renderTrack();
        } else if (moving) {
            Point2D.Double ref = moveOrig.reference();
            Point2D.Double target = snap(new Point2D.Double(ref.x + w.getX() - dragStart.x,
                    ref.y + w.getY() - dragStart.y), alt, selected);
            selected.assign(moveOrig);
            selected.translate(target.x - ref.x, target.y - ref.y);
            dragged = true;
            m.markEdited();
            m.renderTrack();
        }
    }

    private void selectRelease() {
        if (dragged) {
            commitPending();
        } else {
            pendingSnap = null;
        }
        dragHandle = -1;
        moving = false;
        dragged = false;
        moveOrig = null;
    }

    // ---- freehand
    private void beginFreehand(Point2D w, boolean eraser) {
        lastStyleEdit = null;
        pendingSnap = snapshot();
        drawing = newShape(TrackShape.Type.POLYLINE, eraser);
        drawing.closed = false;
        drawing.filled = false;
        drawing.pts.add(new Point2D.Double(w.getX(), w.getY()));
    }

    private void finishFreehand() {
        if (drawing == null) {
            return;
        }
        List<Point2D.Double> simple = simplify(drawing.pts, Math.min(1.5, 2.0 / scale));
        if (simple.size() < 2) {                           // a click: make a dot
            Point2D.Double p = simple.get(0);
            simple.add(new Point2D.Double(p.x + 0.01, p.y));
        }
        drawing.pts.clear();
        drawing.pts.addAll(simple);
        commit(drawing);
    }

    /** Douglas-Peucker: drop points that lie within tol of the straight line between their neighbours. */
    static List<Point2D.Double> simplify(List<Point2D.Double> pts, double tol) {
        int n = pts.size();
        List<Point2D.Double> out = new ArrayList<>();
        if (n < 3) {
            out.addAll(pts);
            return out;
        }
        boolean[] keep = new boolean[n];
        keep[0] = true;
        keep[n - 1] = true;
        Deque<int[]> stack = new ArrayDeque<>();
        stack.push(new int[]{0, n - 1});
        while (!stack.isEmpty()) {
            int[] r = stack.pop();
            double best = -1;
            int bi = -1;
            for (int i = r[0] + 1; i < r[1]; i++) {
                double d = Line2D.ptSegDist(pts.get(r[0]).x, pts.get(r[0]).y, pts.get(r[1]).x, pts.get(r[1]).y,
                        pts.get(i).x, pts.get(i).y);
                if (d > best) {
                    best = d;
                    bi = i;
                }
            }
            if (bi >= 0 && best > tol) {
                keep[bi] = true;
                stack.push(new int[]{r[0], bi});
                stack.push(new int[]{bi, r[1]});
            }
        }
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(pts.get(i));
            }
        }
        return out;
    }

    // ---- click-and-drag shapes (line, rectangle, ellipse)
    private void beginDrag(TrackShape.Type t, Point2D w, boolean alt) {
        lastStyleEdit = null;
        pendingSnap = snapshot();
        drawing = newShape(t, false);
        Point2D.Double p = snap(w, alt, null);
        drawing.pts.add(p);
        drawing.pts.add(new Point2D.Double(p.x, p.y));
    }

    // ---- click-by-click shapes (polyline, curve, arc)
    private void multiPress(Point2D w, boolean right, int clicks, boolean alt) {
        if (right || clicks >= 2) {
            finish();
            return;
        }
        Point2D.Double p = snap(w, alt, null);
        if (drawing == null) {
            lastStyleEdit = null;
            pendingSnap = snapshot();
            drawing = newShape(tool == POLYLINE ? TrackShape.Type.POLYLINE
                    : tool == CURVE ? TrackShape.Type.CURVE : TrackShape.Type.ARC, false);
            drawing.pts.add(p);
            return;
        }
        if (drawing.type != TrackShape.Type.ARC && drawing.pts.size() >= 3
                && p.distance(drawing.pts.get(0)) < 8 / scale) {   // clicked the first point: close the shape
            drawing.closed = true;
            commit(drawing);
            return;
        }
        drawing.pts.add(p);
        if (drawing.type == TrackShape.Type.ARC && drawing.pts.size() == 3) {
            Point2D.Double mid = drawing.pts.remove(2);            // clicks were: start, end, point on arc
            drawing.pts.add(1, mid);
            commit(drawing);
        }
    }

    // ------------------------------------------------------------------ painting (world coordinates)
    /** Optional grid over the arena. u = size of one screen pixel in mm. */
    void paintGrid(Graphics2D g, double u) {
        if (!showGrid || grid <= 0) {
            return;
        }
        double step = grid;
        int major = majorEvery;
        while (step * scale < (gridStyle == 1 ? 8 : 5)) {    // too dense at this zoom: show every 2nd, 4th ... line
            step *= 2;
            major = major > 1 ? Math.max(1, major / 2) : major;
        }
        Rectangle b = g.getClipBounds();
        double x0 = b == null ? 0 : Math.max(0, b.getMinX()), x1 = b == null ? m.arenaW : Math.min(m.arenaW, b.getMaxX());
        double y0 = b == null ? 0 : Math.max(0, b.getMinY()), y1 = b == null ? m.arenaH : Math.min(m.arenaH, b.getMaxY());
        int a = (int) Math.round(255 * Math.max(5, Math.min(100, gridOpacity)) / 100.0);
        Color minor = new Color(0, 90, 200, a), strong = new Color(0, 70, 180, Math.min(255, a * 2 + 30));
        if (gridStyle == 1) {                                 // dots at the crossings
            double r = Math.max(0.5, dotSize) * u;
            Color dot = new Color(0, 80, 190, Math.min(255, a * 3 + 40));     // dots are small: draw them stronger than lines
            Color strongDot = new Color(0, 60, 170, Math.min(255, a * 4 + 110));
            for (double x = Math.ceil(x0 / step) * step; x <= x1; x += step) {
                boolean mx = major > 0 && Math.round(x / step) % major == 0;
                for (double y = Math.ceil(y0 / step) * step; y <= y1; y += step) {
                    boolean big = mx && Math.round(y / step) % major == 0;
                    double rr = big ? r * 1.8 : r;
                    g.setColor(big ? strongDot : dot);
                    g.fill(new java.awt.geom.Ellipse2D.Double(x - rr, y - rr, 2 * rr, 2 * rr));
                }
            }
            return;
        }
        for (double x = Math.ceil(x0 / step) * step; x <= x1; x += step) {
            boolean big = major > 0 && Math.round(x / step) % major == 0;
            g.setColor(big ? strong : minor);
            g.setStroke(new BasicStroke((float) (big ? 1.4 * u : u)));
            g.draw(new Line2D.Double(x, y0, x, y1));
        }
        for (double y = Math.ceil(y0 / step) * step; y <= y1; y += step) {
            boolean big = major > 0 && Math.round(y / step) % major == 0;
            g.setColor(big ? strong : minor);
            g.setStroke(new BasicStroke((float) (big ? 1.4 * u : u)));
            g.draw(new Line2D.Double(x0, y, x1, y));
        }
    }

    /** The shape being drawn, including the rubber band to the cursor. */
    void paintDrawing(Graphics2D g, double u) {
        if (drawing == null) {
            return;
        }
        TrackShape t = drawing.copy();
        if (hoverPt != null) {
            Point2D.Double h = new Point2D.Double(hoverPt.x, hoverPt.y);
            if (t.type == TrackShape.Type.POLYLINE && tool == POLYLINE || t.type == TrackShape.Type.CURVE) {
                t.pts.add(h);
            } else if (t.type == TrackShape.Type.ARC) {
                if (t.pts.size() == 1) {
                    t.type = TrackShape.Type.LINE;
                    t.pts.add(h);
                } else if (t.pts.size() == 2) {
                    Point2D.Double end = t.pts.get(1);
                    t.pts.set(1, h);                      // the cursor is the point on the arc
                    t.pts.add(end);
                }
            }
        }
        t.paint(g);
        g.setStroke(new BasicStroke((float) u));
        for (Point2D.Double p : drawing.pts) {
            g.setColor(Color.WHITE);
            g.fill(new Rectangle2D.Double(p.x - 3.5 * u, p.y - 3.5 * u, 7 * u, 7 * u));
            g.setColor(new Color(0, 120, 255));
            g.draw(new Rectangle2D.Double(p.x - 3.5 * u, p.y - 3.5 * u, 7 * u, 7 * u));
        }
    }

    /** Selection outline, handles and the snap marker. */
    void paintOverlay(Graphics2D g, double u) {
        if (measureA != null && measureB != null && measureA.distance(measureB) > 0.01) {      // the last measurement
            g.setColor(new Color(0xDB2777));
            g.setStroke(new BasicStroke((float) (1.5 * u)));
            g.draw(new Line2D.Double(measureA, measureB));
            for (Point2D.Double q : new Point2D.Double[]{measureA, measureB}) {
                g.draw(new Line2D.Double(q.x - 5 * u, q.y, q.x + 5 * u, q.y));
                g.draw(new Line2D.Double(q.x, q.y - 5 * u, q.x, q.y + 5 * u));
            }
            double dx = measureB.x - measureA.x, dy = measureB.y - measureA.y;
            String t = String.format(java.util.Locale.ROOT, "%.1f mm (dx %.1f, dy %.1f, %.1f deg)", Math.hypot(dx, dy), dx, -dy,
                    Math.toDegrees(Math.atan2(-dy, dx)));
            g.setFont(new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 1).deriveFont((float) (12 * u)));
            double tx = (measureA.x + measureB.x) / 2, ty = (measureA.y + measureB.y) / 2 - 6 * u;
            Rectangle2D tb = g.getFontMetrics().getStringBounds(t, g);
            g.setColor(new Color(255, 255, 255, 220));
            g.fill(new Rectangle2D.Double(tx - tb.getWidth() / 2 - 3 * u, ty - tb.getHeight() + 2 * u, tb.getWidth() + 6 * u, tb.getHeight() + 2 * u));
            g.setColor(new Color(0xDB2777));
            g.drawString(t, (float) (tx - tb.getWidth() / 2), (float) ty);
        }
        if (tool == SELECT && selected != null) {
            g.setColor(new Color(0, 150, 255));
            g.setStroke(new BasicStroke((float) (1.5 * u), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
                    new float[]{(float) (5 * u), (float) (4 * u)}, 0f));
            g.draw(selected.geometry());
            if (selected.editable()) {
                g.setStroke(new BasicStroke((float) (1.5 * u)));
                for (Point2D.Double p : selected.pts) {
                    Rectangle2D h = new Rectangle2D.Double(p.x - 4 * u, p.y - 4 * u, 8 * u, 8 * u);
                    g.setColor(Color.WHITE);
                    g.fill(h);
                    g.setColor(new Color(0, 120, 255));
                    g.draw(h);
                }
            } else {
                g.setColor(new Color(0, 150, 255, 120));
                Rectangle2D b = selected.bounds();
                g.draw(new Rectangle2D.Double(b.getX() - 6 * u, b.getY() - 6 * u, b.getWidth() + 12 * u, b.getHeight() + 12 * u));
            }
        }
        if (hoverPt != null && snapsTool()) {
            g.setColor(new Color(0, 120, 255));
            g.setStroke(new BasicStroke((float) (1.2 * u)));
            g.draw(new Line2D.Double(hoverPt.x - 6 * u, hoverPt.y, hoverPt.x + 6 * u, hoverPt.y));
            g.draw(new Line2D.Double(hoverPt.x, hoverPt.y - 6 * u, hoverPt.x, hoverPt.y + 6 * u));
        }
    }
}
