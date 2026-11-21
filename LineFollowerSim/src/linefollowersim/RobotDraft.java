package linefollowersim;

import java.awt.BasicStroke;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The Robot editor's working copy: chassis outline, sensors, LEDs, IR pair geometry, board and parts, all in
 * robot millimetres (x forward from the axle, y to the right). It only changes the robot when applied, and it
 * is saved as it is (finished or not) in the workspace and in an autosave file.
 */
final class RobotDraft {

    static final class Pt {

        double x, y;
        String pin;                     // sensors: MCU pin (null = automatic)
        double h;                       // sensors: height above (+) / below (-) the set sensor height, mm
        int sensor = -1;                // LEDs: the sensor that lights it (indicator mode), -1 = nearest

        Pt(double x, double y) {
            this.x = x;
            this.y = y;
        }

        Pt copy() {
            Pt p = new Pt(x, y);
            p.pin = pin;
            p.sensor = sensor;
            p.h = h;
            return p;
        }
    }

    final List<Pt> chassis = new ArrayList<>();
    final List<Pt> sensors = new ArrayList<>();
    final List<Pt> leds = new ArrayList<>();
    final List<Model.Part> parts = new ArrayList<>();
    boolean customLeds, useParts, boardJoined = true, pairAlternate;
    int pairOrient;
    double pairGap, boardMargin = 3, spotRadius = 2, casterX, plateThick = 3, plateDensity = 1.19, holeDia = 3.2;
    double stemWidth = 0, stemOverlap = 10;
    boolean boardOnPlate;                            // one-piece PCB: the sensors sit on the plate itself (no separate board)
    boolean customBoard;                             // the board outline is edited corner by corner (else it follows the sensors)
    final List<Pt> boardPts = new ArrayList<>();      // that outline, robot mm
    // wheels and drive: 0 = 2 + caster, 1 = 4 skid steer (all driven), 2 = car, rear drive, 3 = car, all-wheel drive
    int wheelLayout, steerBy;
    double wheelBase = 100, wheelDia = 65, tireWidth = 10, axleSpacing = 80, steerLock = 30;
    double sensorHeight = 10;                        // the sensors' height above the floor (each sensor can add its own h)          // a neck from the array back to the plate (T / anchor boards), 0 = none

    // ------------------------------------------------------------------ from / to the robot
    /** Take everything from the robot as it is now. */
    static RobotDraft from(Model m) {
        RobotDraft d = new RobotDraft();
        java.awt.geom.PathIterator it = m.chassisOutline().getPathIterator(null, 0.4);
        double[] c = new double[6];
        while (!it.isDone()) {
            int t = it.currentSegment(c);
            if (t == java.awt.geom.PathIterator.SEG_MOVETO && !d.chassis.isEmpty()) {
                break;                                                     // the first closed outline only
            }
            if (t != java.awt.geom.PathIterator.SEG_CLOSE) {
                d.chassis.add(new Pt(c[0], c[1]));
            }
            it.next();
        }
        d.mergeClose(d.chassis, 0.3);
        d.sensorsFrom(m);
        d.ledsFrom(m);
        d.customLeds = m.customLeds;
        d.pairOrient = m.pairOrient;
        d.pairAlternate = m.pairAlternate;
        d.pairGap = m.pairGap;
        d.boardJoined = m.boardJoined;
        d.boardMargin = m.boardMargin;
        d.spotRadius = m.spotRadius;
        d.holeDia = m.boardHoleDia;
        d.stemWidth = m.boardStem;
        d.customBoard = m.customBoard && m.cuBoard.size() >= 3;
        for (double[] q : m.cuBoard) {
            d.boardPts.add(new Pt(m.forward + q[0], q[1]));
        }
        d.sensorHeight = m.sensorHeight;
        d.wheelLayout = m.wheelCount == 2 ? 0 : 1 + m.steerMode;
        d.steerBy = m.steerActuator;
        d.wheelBase = m.wheelBase;
        d.wheelDia = m.wheelDia;
        d.tireWidth = m.tireWidth;
        d.axleSpacing = m.axleSpacing;
        d.steerLock = m.maxSteerDeg;
        d.boardOnPlate = m.boardOnPlate;
        d.casterX = m.casterX();
        d.useParts = m.useParts;
        d.plateThick = m.plateThick;
        d.plateDensity = m.plateDensity;
        if (m.parts.isEmpty()) {
            Model t = new Model();
            t.copySettingsFrom(m);
            t.defaultParts();
            for (Model.Part p : t.parts) {
                d.parts.add(p.copy());
            }
        } else {
            for (Model.Part p : m.parts) {
                d.parts.add(p.copy());
            }
        }
        return d;
    }

    void sensorsFrom(Model m) {
        sensors.clear();
        for (int i = 0; i < m.nSensors; i++) {
            Pt p = new Pt(m.forward + m.dxOf(i), m.lat(i));
            p.pin = m.customLayout ? m.cuPin[i] : null;
            p.h = m.customLayout ? m.cuHeight[i] : 0;
            sensors.add(p);
        }
    }

    void ledsFrom(Model m) {
        leds.clear();
        double[] lx = new double[Model.MAXL], ly = new double[Model.MAXL];
        boolean was = m.customLeds;
        int n = m.ledLayout(lx, ly);
        for (int j = 0; j < n; j++) {
            Pt p = new Pt(lx[j], ly[j]);
            p.sensor = was ? m.cuLedSensor[j] : -1;
            leds.add(p);
        }
    }

    /** The sensor nearest to the array centre sets the reference ("Axle to sensors"). */
    double reference() {
        double best = Double.MAX_VALUE, x = 0;
        for (Pt p : sensors) {
            if (Math.abs(p.y) < best - 1e-9) {
                best = Math.abs(p.y);
                x = p.x;
            }
        }
        return x;
    }

    /** Problems that stop Apply (empty = fine). */
    String applyProblem() {
        if (chassis.size() < 3) {
            return "The chassis needs at least 3 corners.";
        }
        if (sensors.isEmpty()) {
            return "Place at least one sensor.";
        }
        if (sensors.size() > Model.MAXS) {
            return "At most " + Model.MAXS + " sensors.";
        }
        if (leds.size() > Model.MAXL) {
            return "At most " + Model.MAXL + " LEDs.";
        }
        if (reference() < 5) {
            return "The sensors must be ahead of the axle.";
        }
        return null;
    }

    /** Put the draft into the robot (the caller then mirrors the numbers into the controls). */
    void applyTo(Model m) {
        List<Pt> s = new ArrayList<>(sensors);
        s.sort((a, b) -> Double.compare(a.y, b.y));                         // left to right, as the controller counts them
        m.nSensors = s.size();
        m.forward = reference();
        for (int i = 0; i < s.size(); i++) {
            m.cuDx[i] = s.get(i).x - m.forward;
            m.cuLat[i] = s.get(i).y;
            m.cuPin[i] = s.get(i).pin;
            m.cuHeight[i] = s.get(i).h;
        }
        m.customLayout = true;
        m.customOutline.clear();
        for (Pt p : chassis) {
            m.customOutline.add(new double[]{p.x, p.y});
        }
        m.customLeds = customLeds;
        m.cuLedCount = Math.min(leds.size(), Model.MAXL);
        for (int j = 0; j < m.cuLedCount; j++) {
            Pt p = leds.get(j);
            m.cuLedDx[j] = p.x - m.forward;
            m.cuLedLat[j] = p.y;
            m.cuLedSensor[j] = p.sensor >= 0 ? sortedIndex(p.sensor, s) : nearest(s, p);
        }
        m.pairOrient = pairOrient;
        m.pairAlternate = pairAlternate;
        m.pairGap = pairGap;
        m.boardJoined = boardJoined;
        m.boardMargin = boardMargin;
        m.boardHoleDia = holeDia;
        m.boardStem = stemWidth;
        m.customBoard = customBoard && boardPts.size() >= 3;
        m.cuBoard.clear();
        for (Pt p : boardPts) {
            m.cuBoard.add(new double[]{p.x - m.forward, p.y});
        }
        m.sensorHeight = sensorHeight;
        m.wheelCount = wheelLayout == 0 ? 2 : 4;
        m.steerMode = wheelLayout <= 1 ? 0 : wheelLayout - 1;
        m.steerActuator = steerBy;
        m.wheelBase = wheelBase;
        m.wheelDia = wheelDia;
        m.tireWidth = tireWidth;
        m.axleSpacing = axleSpacing;
        m.maxSteerDeg = steerLock;
        m.boardOnPlate = boardOnPlate;
        m.casterPos = casterX;
        m.useParts = useParts;
        m.plateThick = plateThick;
        m.plateDensity = plateDensity;
        m.parts.clear();
        for (Model.Part p : parts) {
            m.parts.add(p.copy());
        }
    }

    private int sortedIndex(int unsorted, List<Pt> sorted) {
        return unsorted >= 0 && unsorted < sensors.size() ? sorted.indexOf(sensors.get(unsorted)) : 0;
    }

    private static int nearest(List<Pt> s, Pt p) {
        int bi = 0;
        double bd = Double.MAX_VALUE;
        for (int i = 0; i < s.size(); i++) {
            double d = Math.hypot(s.get(i).x - p.x, s.get(i).y - p.y);
            if (d < bd) {
                bd = d;
                bi = i;
            }
        }
        return bi;
    }

    /** Wheel centres {x, y, steered 0/1}: 2 wheels on the axle, or 4 with the axles either side of x = 0. */
    List<double[]> wheels() {
        List<double[]> l = new ArrayList<>();
        double hw = wheelBase / 2;
        if (wheelLayout == 0) {
            l.add(new double[]{0, -hw, 0});
            l.add(new double[]{0, hw, 0});
        } else {
            for (double ax : new double[]{axleSpacing / 2, -axleSpacing / 2}) {
                double st = wheelLayout >= 2 && ax > 0 ? 1 : 0;
                l.add(new double[]{ax, -hw, st});
                l.add(new double[]{ax, hw, st});
            }
        }
        return l;
    }

    // ------------------------------------------------------------------ geometry
    Path2D.Double chassisPath() {
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < chassis.size(); i++) {
            if (i == 0) {
                p.moveTo(chassis.get(i).x, chassis.get(i).y);
            } else {
                p.lineTo(chassis.get(i).x, chassis.get(i).y);
            }
        }
        p.closePath();
        return p;
    }

    /** The board: a strip through the sensors (left to right) as wide as the spots plus the margin. */
    Area board() {
        if (boardOnPlate) {
            return new Area();
        }
        if (customBoard && boardPts.size() >= 3) {
            return new Area(path(boardPts));
        }
        return autoBoard();
    }

    static Path2D.Double path(List<Pt> pts) {
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < pts.size(); i++) {
            if (i == 0) {
                p.moveTo(pts.get(i).x, pts.get(i).y);
            } else {
                p.lineTo(pts.get(i).x, pts.get(i).y);
            }
        }
        p.closePath();
        return p;
    }

    /** The automatic board: a strip through the sensors, pads for the LEDs, the stem. */
    Area autoBoard() {
        List<Pt> s = new ArrayList<>(sensors);
        s.sort((a, b) -> Double.compare(a.y, b.y));
        Path2D.Double p = new Path2D.Double();
        for (int i = 0; i < s.size(); i++) {
            if (i == 0) {
                p.moveTo(s.get(i).x, s.get(i).y);
                if (s.size() == 1) {
                    p.lineTo(s.get(i).x, s.get(i).y + 0.01);
                }
            } else {
                p.lineTo(s.get(i).x, s.get(i).y);
            }
        }
        double w = Math.max(2 * boardMargin + 2 * spotRadius, 6);
        Area a = new Area(new BasicStroke((float) w, BasicStroke.CAP_SQUARE, BasicStroke.JOIN_MITER, 4f).createStrokedShape(p));
        for (Pt l : leds) {                                                // the board also carries the LEDs
            a.add(new Area(new Rectangle2D.Double(l.x - 2.5, l.y - 2.5, 5, 5)));
        }
        if (stemWidth > 0 && !s.isEmpty()) {                               // the neck back to the plate
            double ref = reference(), from = chassis.size() >= 3 ? frontEdgeAt(chassis, 0) - stemOverlap : ref - 40;
            if (!Double.isNaN(from) && from < ref) {
                a.add(new Area(new Rectangle2D.Double(from, -stemWidth / 2, ref - from, stemWidth)));
            }
        }
        return a;
    }

    /** Mount holes where the board overlaps the plate. */
    List<double[]> holes() {
        List<double[]> l = new ArrayList<>();
        if (!boardJoined || chassis.size() < 3 || sensors.isEmpty()) {
            return l;
        }
        Area ov = board();
        ov.intersect(new Area(chassisPath()));
        Rectangle2D b = ov.getBounds2D();
        if (b.isEmpty() || b.getWidth() < holeDia + 2 || b.getHeight() < holeDia + 2) {
            return l;
        }
        for (double fy : new double[]{-0.3, 0.3}) {
            double y = b.getCenterY() + fy * b.getHeight(), x = b.getCenterX();
            for (double dx = 0; dx < b.getWidth() / 2; dx += 0.5) {
                if (ov.contains(x - dx, y)) {
                    l.add(new double[]{x - dx, y});
                    break;
                }
                if (ov.contains(x + dx, y)) {
                    l.add(new double[]{x + dx, y});
                    break;
                }
            }
        }
        return l;
    }

    /** Area, centroid x, centroid y of the plate. */
    double[] plateGeometry() {
        double a = 0, cx = 0, cy = 0;
        int n = chassis.size();
        for (int i = 0; i < n; i++) {
            Pt p = chassis.get(i), q = chassis.get((i + 1) % n);
            double cr = p.x * q.y - q.x * p.y;
            a += cr;
            cx += (p.x + q.x) * cr;
            cy += (p.y + q.y) * cr;
        }
        a /= 2;
        return Math.abs(a) < 1e-9 ? new double[]{0, 0, 0} : new double[]{Math.abs(a), cx / (6 * a), cy / (6 * a)};
    }

    double plateMass() {
        return plateGeometry()[0] * plateThick / 1000.0 * plateDensity;
    }

    /** {total mass g, CoM x, CoM y, CoM height} from the plate and the parts. */
    double[] massCentre(double wheelDia) {
        double[] g = plateGeometry();
        double pm = plateMass(), sm = pm, sx = pm * g[1], sy = pm * g[2], sz = pm * (wheelDia / 2 + plateThick / 2);
        for (Model.Part p : parts) {
            sm += p.massG;
            sx += p.massG * p.x;
            sy += p.massG * p.y;
            sz += p.massG * p.h;
        }
        return sm > 0 ? new double[]{sm, sx / sm, sy / sm, sz / sm} : new double[]{0, 0, 0, 0};
    }

    // ------------------------------------------------------------------ edits
    /** Remove corners closer than tol to the previous one (and a last corner equal to the first). */
    int mergeClose(List<Pt> pts, double tol) {
        int removed = 0;
        for (int i = pts.size() - 1; i >= 0 && pts.size() > 3; i--) {
            Pt p = pts.get(i), q = pts.get((i + 1) % pts.size());
            if (Math.hypot(p.x - q.x, p.y - q.y) < tol) {
                pts.remove(i);
                removed++;
            }
        }
        for (int i = pts.size() - 1; i >= 0 && pts.size() > 3; i--) {      // straight-through corners
            Pt a = pts.get((i - 1 + pts.size()) % pts.size()), p = pts.get(i), b = pts.get((i + 1) % pts.size());
            double cr = (p.x - a.x) * (b.y - a.y) - (p.y - a.y) * (b.x - a.x), len = Math.hypot(b.x - a.x, b.y - a.y);
            double dot = (p.x - a.x) * (b.x - a.x) + (p.y - a.y) * (b.y - a.y);
            if (len > 1e-9 && Math.abs(cr) / len < tol * 0.05 && dot > 0 && dot < len * len) {
                pts.remove(i);
                removed++;
            }
        }
        return removed;
    }

    /** Rebuild the outline from one half (keep = -1 left half, +1 right half), mirrored about the centre line. */
    void makeSymmetric(int keep) {
        if (chassis.size() < 3) {
            return;
        }
        Area half = new Area(chassisPath());
        half.intersect(new Area(new Rectangle2D.Double(-1e5, keep < 0 ? -1e5 : 0, 2e5, 1e5)));
        Area mir = half.createTransformedArea(java.awt.geom.AffineTransform.getScaleInstance(1, -1));
        half.add(mir);
        List<Pt> l = outlineOf(half);
        if (l.size() >= 3) {
            chassis.clear();
            chassis.addAll(l);
            mergeClose(chassis, 0.05);
        }
    }

    /** The biggest closed outline of an area, as corner points. */
    static List<Pt> outlineOf(Area a) {
        List<List<Pt>> loops = new ArrayList<>();
        List<Pt> cur = null;
        double[] c = new double[6];
        for (java.awt.geom.PathIterator it = a.getPathIterator(null, 0.3); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == java.awt.geom.PathIterator.SEG_MOVETO) {
                cur = new ArrayList<>();
                loops.add(cur);
            }
            if (t != java.awt.geom.PathIterator.SEG_CLOSE && cur != null) {
                cur.add(new Pt(c[0], c[1]));
            }
        }
        List<Pt> best = new ArrayList<>();
        double ba = 0;
        for (List<Pt> lp : loops) {
            double ar = 0;
            for (int i = 0; i < lp.size(); i++) {
                Pt p = lp.get(i), q = lp.get((i + 1) % lp.size());
                ar += p.x * q.y - q.x * p.y;
            }
            if (Math.abs(ar) > ba) {
                ba = Math.abs(ar);
                best = lp;
            }
        }
        return best;
    }

    /** Start shapes for the chassis (robot mm, front = +x). */
    static List<Pt> startShape(int kind, double front, double rear, double width) {
        List<Pt> l = new ArrayList<>();
        double hw = width / 2;
        switch (kind) {
            case 1: {                                                      // rounded rectangle
                double r = Math.min(12, Math.min(hw, (front + rear) / 2) * 0.5);
                double[][] cs = {{front - r, hw - r, 0}, {-rear + r, hw - r, 90}, {-rear + r, -hw + r, 180}, {front - r, -hw + r, 270}};
                for (double[] k : cs) {
                    for (int a = 0; a <= 90; a += 15) {
                        double an = Math.toRadians(k[2] + a);
                        l.add(new Pt(k[0] + r * Math.cos(an), k[1] + r * Math.sin(an)));
                    }
                }
                break;
            }
            case 2: {                                                      // circle (centre between front and rear)
                double cx = (front - rear) / 2, rr = (front + rear) / 2;
                for (int a = 0; a < 360; a += 10) {
                    l.add(new Pt(cx + rr * Math.cos(Math.toRadians(a)), rr * Math.sin(Math.toRadians(a))));
                }
                break;
            }
            case 3: {                                                      // T: wide body at the axle, a neck to the front
                double neck = Math.max(10, width * 0.25), bodyF = Math.min(front, rear + 10);
                l.add(new Pt(front, -neck / 2));
                l.add(new Pt(front, neck / 2));
                l.add(new Pt(bodyF * 0.5, neck / 2));
                l.add(new Pt(bodyF * 0.5, hw));
                l.add(new Pt(-rear, hw));
                l.add(new Pt(-rear, -hw));
                l.add(new Pt(bodyF * 0.5, -hw));
                l.add(new Pt(bodyF * 0.5, -neck / 2));
                break;
            }
            case 4: {                                                      // arrow: pointed front
                l.add(new Pt(front, 0));
                l.add(new Pt(front * 0.45, hw));
                l.add(new Pt(-rear, hw));
                l.add(new Pt(-rear, -hw));
                l.add(new Pt(front * 0.45, -hw));
                break;
            }
            default:                                                       // rectangle
                l.add(new Pt(front, -hw));
                l.add(new Pt(front, hw));
                l.add(new Pt(-rear, hw));
                l.add(new Pt(-rear, -hw));
        }
        return l;
    }

    /**
     * Sensors evenly placed: kind 0 straight line, 1 V (angle: outer ones further forward), 2 arc (radius,
     * outer ones trail back), 3 along the front edge of the chassis (inset). Width = outer to outer sensor.
     */
    static List<Pt> placeSensors(int kind, int n, double width, double refX, double angleDeg, double radius, List<Pt> chassis, double inset) {
        return placeSensors(kind, n, width, refX, angleDeg, radius, chassis, inset, 180, 30);
    }

    /**
     * kind 4: arc by angle - the front of a circle of this radius, arcDeg wide in all (over 180 the ends curl back, like an
     * anchor); kind 5: swept wings - a straight middle part, then each wing at angleDeg back, tip to tip = width.
     * Both are spaced evenly along the board, not across.
     */
    static List<Pt> placeSensors(int kind, int n, double width, double refX, double angleDeg, double radius, List<Pt> chassis, double inset,
            double arcDeg, double centre) {
        if (kind == 4 || kind == 5) {
            List<double[]> path = new ArrayList<>();
            if (kind == 4) {
                double r = Math.max(radius, 1), half = Math.toRadians(Math.max(1, Math.min(340, arcDeg)) / 2);
                for (int k = 0; k <= 200; k++) {
                    double a = -half + 2 * half * k / 200;
                    path.add(new double[]{refX - r + r * Math.cos(a), r * Math.sin(a)});
                }
            } else {
                double c = Math.max(0, Math.min(centre, width)), a = Math.toRadians(Math.max(-80, Math.min(80, angleDeg)));
                double wing = Math.max(0, (width - c) / 2) / Math.max(Math.cos(a), 0.05);
                double tipX = refX - wing * Math.sin(a);
                path.add(new double[]{tipX, -width / 2});
                path.add(new double[]{refX, -c / 2});
                path.add(new double[]{refX, c / 2});
                path.add(new double[]{tipX, width / 2});
            }
            double total = 0;
            for (int k = 1; k < path.size(); k++) {
                total += Math.hypot(path.get(k)[0] - path.get(k - 1)[0], path.get(k)[1] - path.get(k - 1)[1]);
            }
            List<Pt> l = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                double target = n == 1 ? total / 2 : total * i / (n - 1), run = 0;
                for (int k = 1; k < path.size(); k++) {
                    double[] p0 = path.get(k - 1), p1 = path.get(k);
                    double seg = Math.hypot(p1[0] - p0[0], p1[1] - p0[1]);
                    if (run + seg >= target - 1e-9 || k == path.size() - 1) {
                        double f = seg > 0 ? Math.max(0, Math.min(1, (target - run) / seg)) : 0;
                        l.add(new Pt(p0[0] + (p1[0] - p0[0]) * f, p0[1] + (p1[1] - p0[1]) * f));
                        break;
                    }
                    run += seg;
                }
            }
            return l;
        }
        List<Pt> l = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double y = n == 1 ? 0 : -width / 2 + width * i / (n - 1), x = refX;
            if (kind == 1) {
                x = refX + Math.abs(y) * Math.tan(Math.toRadians(Math.max(-60, Math.min(60, angleDeg))));
            } else if (kind == 2) {
                double r = Math.max(radius, width / 2 + 1);
                x = refX - (r - Math.sqrt(r * r - y * y));
            } else if (kind == 3 && chassis.size() >= 3) {
                x = frontEdgeAt(chassis, y) - inset;
            }
            l.add(new Pt(x, y));
        }
        if (kind == 2 && n > 2) {                                          // an arc: even spacing along the curve, not across
            double r = Math.max(radius, width / 2 + 1), half = Math.asin(Math.min(1, width / 2 / r));
            l.clear();
            for (int i = 0; i < n; i++) {
                double a = -half + 2 * half * i / (n - 1);
                l.add(new Pt(refX - r + r * Math.cos(a), r * Math.sin(a)));
            }
        }
        return l;
    }

    /** The most forward point of the outline at lateral position y (Double.NaN if the outline does not reach it). */
    static double frontEdgeAt(List<Pt> pts, double y) {
        double best = Double.NaN;
        for (int i = 0; i < pts.size(); i++) {
            Pt a = pts.get(i), b = pts.get((i + 1) % pts.size());
            if ((a.y - y) * (b.y - y) <= 0 && Math.abs(a.y - b.y) > 1e-9) {
                double x = a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y);
                best = Double.isNaN(best) ? x : Math.max(best, x);
            } else if (Math.abs(a.y - y) < 1e-9) {
                best = Double.isNaN(best) ? a.x : Math.max(best, a.x);
            }
        }
        return best;
    }

    /** LEDs placed automatically: 0 between neighbouring sensors, 1 in front of each, 2 behind each, 3 both sides of each. */
    void placeLeds(int kind, double offset) {
        leds.clear();
        List<Pt> s = new ArrayList<>(sensors);
        s.sort((a, b) -> Double.compare(a.y, b.y));
        for (int i = 0; i < s.size(); i++) {
            Pt p = s.get(i);
            int idx = sensors.indexOf(p);
            if (kind == 0) {
                if (i + 1 < s.size()) {
                    Pt q = s.get(i + 1);
                    Pt l = new Pt((p.x + q.x) / 2, (p.y + q.y) / 2);
                    l.sensor = idx;
                    leds.add(l);
                }
            } else if (kind == 3) {
                for (int sg : new int[]{-1, 1}) {
                    Pt l = new Pt(p.x, p.y + sg * offset);
                    l.sensor = idx;
                    leds.add(l);
                }
            } else {
                Pt l = new Pt(p.x + (kind == 1 ? offset : -offset), p.y);
                l.sensor = idx;
                leds.add(l);
            }
        }
        customLeds = true;
    }

    // ------------------------------------------------------------------ text form (workspace, autosave, undo)
    List<String> lines() {
        List<String> o = new ArrayList<>();
        o.add(String.format(Locale.ROOT, "draft %d %d %d %d %d %s %s %s %s %s %s %s", customLeds ? 1 : 0, useParts ? 1 : 0,
                boardJoined ? 1 : 0, pairAlternate ? 1 : 0, pairOrient, num(pairGap), num(boardMargin), num(spotRadius),
                num(casterX), num(plateThick), num(plateDensity), num(holeDia)) + " " + num(stemWidth) + " " + (boardOnPlate ? 1 : 0));
        o.add("dwheel " + wheelLayout + " " + steerBy + " " + num(wheelBase) + " " + num(wheelDia) + " " + num(tireWidth) + " "
                + num(axleSpacing) + " " + num(steerLock) + " " + num(sensorHeight));
        for (Pt p : chassis) {
            o.add("dc " + num(p.x) + " " + num(p.y));
        }
        o.add("dboard " + (customBoard ? 1 : 0));
        for (Pt p : boardPts) {
            o.add("db " + num(p.x) + " " + num(p.y));
        }
        for (Pt p : sensors) {
            o.add("ds " + num(p.x) + " " + num(p.y) + " " + (p.pin == null || p.pin.isEmpty() ? "-" : p.pin.replace(' ', '_')) + " " + num(p.h));
        }
        for (Pt p : leds) {
            o.add("dl " + num(p.x) + " " + num(p.y) + " " + p.sensor);
        }
        for (Model.Part p : parts) {
            o.add("dp " + p.kind + " " + num(p.x) + " " + num(p.y) + " " + num(p.w) + " " + num(p.l) + " " + num(p.h) + " "
                    + num(p.massG) + " " + p.name.replace(' ', '_'));
        }
        return o;
    }

    private static String num(double v) {
        return v == Math.rint(v) && Math.abs(v) < 1e9 ? Long.toString((long) v) : String.format(Locale.ROOT, "%.4f", v);
    }

    static RobotDraft parse(List<String> lines) {
        RobotDraft d = new RobotDraft();
        boolean any = false;
        for (String ln : lines) {
            String[] t = ln.trim().split("\\s+");
            switch (t[0]) {
                case "draft":
                    any = true;
                    d.customLeds = t[1].equals("1");
                    d.useParts = t[2].equals("1");
                    d.boardJoined = t[3].equals("1");
                    d.pairAlternate = t[4].equals("1");
                    d.pairOrient = Integer.parseInt(t[5]);
                    d.pairGap = Double.parseDouble(t[6]);
                    d.boardMargin = Double.parseDouble(t[7]);
                    d.spotRadius = Double.parseDouble(t[8]);
                    d.casterX = Double.parseDouble(t[9]);
                    d.plateThick = Double.parseDouble(t[10]);
                    d.plateDensity = Double.parseDouble(t[11]);
                    d.holeDia = Double.parseDouble(t[12]);
                    d.stemWidth = t.length > 13 ? Double.parseDouble(t[13]) : 0;
                    d.boardOnPlate = t.length > 14 && t[14].equals("1");
                    break;
                case "dwheel":
                    d.wheelLayout = Integer.parseInt(t[1]);
                    d.steerBy = Integer.parseInt(t[2]);
                    d.wheelBase = Double.parseDouble(t[3]);
                    d.wheelDia = Double.parseDouble(t[4]);
                    d.tireWidth = Double.parseDouble(t[5]);
                    d.axleSpacing = Double.parseDouble(t[6]);
                    d.steerLock = Double.parseDouble(t[7]);
                    d.sensorHeight = t.length > 8 ? Double.parseDouble(t[8]) : 10;
                    break;
                case "dboard":
                    d.customBoard = t[1].equals("1");
                    break;
                case "db":
                    d.boardPts.add(new Pt(Double.parseDouble(t[1]), Double.parseDouble(t[2])));
                    break;
                case "dc":
                    d.chassis.add(new Pt(Double.parseDouble(t[1]), Double.parseDouble(t[2])));
                    break;
                case "ds": {
                    Pt p = new Pt(Double.parseDouble(t[1]), Double.parseDouble(t[2]));
                    p.pin = t[3].equals("-") ? null : t[3];
                    p.h = t.length > 4 ? Double.parseDouble(t[4]) : 0;
                    d.sensors.add(p);
                    break;
                }
                case "dl": {
                    Pt p = new Pt(Double.parseDouble(t[1]), Double.parseDouble(t[2]));
                    p.sensor = Integer.parseInt(t[3]);
                    d.leds.add(p);
                    break;
                }
                case "dp":
                    d.parts.add(new Model.Part(t.length > 8 ? t[8].replace('_', ' ') : "Part", Integer.parseInt(t[1]),
                            Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4]), Double.parseDouble(t[5]),
                            Double.parseDouble(t[6]), Double.parseDouble(t[7])));
                    break;
                default:
            }
        }
        return any ? d : null;
    }

    RobotDraft copy() {
        return parse(lines());
    }
}
