package linefollowersim;

import java.awt.*;
import java.awt.geom.*;
import java.awt.print.PageFormat;
import java.awt.print.Pageable;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 1:1 outputs for building the real thing: the robot drawing (DXF / SVG with cut, hole, mark, reference
 * and text layers), the track drawing (line outline and centre line), a parts list, and real-size
 * printing of the track on tiled pages.
 */
final class Exports {

    private Exports() {
    }

    private static String f(String fmt, Object... a) {
        return String.format(Locale.ROOT, fmt, a);
    }

    // ------------------------------------------------------------------ robot drawing
    /** Hole patterns for the parts. Typical values only: measure your own parts. */
    static final class Mounts {

        static final String[] TYPES = {"TT gear motor (typical)", "N20 metal bracket (typical)", "Custom"};
        int type = 0;
        double holeDia = 3.2, holeSpacing = 17.5, holeInset = 30, holeOffset = 0;   // per motor: two holes along the robot
        double casterSpacing = 20, casterDia = 2.5;                                 // ball caster: two holes across
        double boardHoleDia = 3.2, boardHoleInset = 3.5;                            // sensor board: one hole at each end
        boolean servoCutout = true;                                                 // car: SG90-size servo opening at the front axle

        void preset(int t) {
            type = t;
            if (t == 0) {
                holeDia = 3.2;
                holeSpacing = 17.5;
                holeInset = 30;
                holeOffset = 0;
            } else if (t == 1) {
                holeDia = 2.2;
                holeSpacing = 18;
                holeInset = 10;
                holeOffset = 0;
            }
        }
    }

    /** Robot frame (x forward, y right) to drawing coordinates (front up). */
    private static double[] rb(double rx, double ry) {
        return new double[]{ry, rx};
    }

    static Drawing robot(Model m, Mounts mt) {
        Drawing d = new Drawing();
        d.layer("CUT", 1);
        d.layer("HOLES", 5);
        d.layer("MARK", 3);
        d.layer("REF", 8);
        d.layer("TEXT", 7);
        d.shape("CUT", m.chassisOutline(), 0.1, p -> rb(p[0], p[1]));
        d.shape("CUT", m.sensorBoard(), 0.1, p -> rb(p[0], p[1]));
        boolean car = m.steerMode != 0;
        // motor mounts: every driven wheel has a motor
        for (double[] w : m.wheelPositions()) {
            boolean driven = !(car && m.steerMode == 1 && w[0] > 0);       // rear-wheel-drive car: front wheels free
            boolean steered = car && w[0] > 0;
            double side = Math.signum(w[1]), hy = w[1] - side * mt.holeInset;
            if (driven) {
                for (double sgn : new double[]{-1, 1}) {
                    double[] c = rb(w[0] + mt.holeOffset + sgn * mt.holeSpacing / 2, hy);
                    d.circle("HOLES", c[0], c[1], mt.holeDia / 2);
                }
            }
            double[] c = rb(w[0] - m.wheelDia / 2, w[1] - m.tireWidth / 2);   // the wheel, for reference
            d.rect("REF", c[0], c[1], m.tireWidth, m.wheelDia);
            if (steered) {
                double[] t = rb(w[0] - m.wheelDia / 2 - 4, w[1] - side * 3);
                d.text("TEXT", t[0], t[1], 3, "steered");
            }
        }
        if (m.wheelCount == 2) {                                            // ball caster
            double cx = m.casterX();
            for (double sgn : new double[]{-1, 1}) {
                double[] c = rb(cx, sgn * mt.casterSpacing / 2);
                d.circle("HOLES", c[0], c[1], mt.casterDia / 2);
            }
            double[] c = rb(cx, 0);
            d.circle("MARK", c[0], c[1], 1);
            d.text("TEXT", c[0] + 2, c[1] - 6, 3, "caster");
        }
        if (car && m.steerActuator == 0 && mt.servoCutout) {               // SG90-size servo opening and screw holes
            double ax = m.axleSpacing / 2;
            double[] c = rb(ax - 6.25, -11.5);
            d.rect("CUT", c[0], c[1], 23, 12.5);
            for (double sgn : new double[]{-1, 1}) {
                double[] h = rb(ax, sgn * 13.9);
                d.circle("HOLES", h[0], h[1], 1.0);
            }
            double[] t = rb(ax - 10, -11.5);
            d.text("TEXT", t[0], t[1], 3, "servo (SG90 size)");
        }
        // sensor board: a mounting hole at each end (also drilled through the chassis)
        java.util.List<double[]> joint = m.customLayout || m.useParts ? m.boardHoles() : java.util.Collections.emptyList();
        if (!joint.isEmpty()) {                                            // the editor's joint: holes where board and plate overlap
            for (double[] hp : joint) {
                double[] c = rb(hp[0], hp[1]);
                d.circle("HOLES", c[0], c[1], m.boardHoleDia / 2);
            }
        } else {
            double half = m.halfSpan();
            double edge = half + m.spotRadius + m.boardMargin - mt.boardHoleInset;
            for (int k = 0; k < 2; k++) {
                int si = k == 0 ? 0 : m.nSensors - 1;
                double lat = m.lat(si) + (k == 0 ? -1 : 1) * (m.spotRadius + m.boardMargin - mt.boardHoleInset);
                double[] c = rb(m.forward + m.dxOf(si), m.customLayout ? lat : (k == 0 ? -edge : edge));
                d.circle("HOLES", c[0], c[1], mt.boardHoleDia / 2);
            }
        }
        if (m.useParts) {                                                  // where the parts sit (reference only)
            for (Model.Part pt : m.parts) {
                if (pt.w > 0 && pt.l > 0) {
                    double[] c0 = rb(pt.x - pt.l / 2, pt.y - pt.w / 2), c1 = rb(pt.x + pt.l / 2, pt.y + pt.w / 2);
                    d.line("REF", c0[0], c0[1], c1[0], c0[1]);
                    d.line("REF", c1[0], c0[1], c1[0], c1[1]);
                    d.line("REF", c1[0], c1[1], c0[0], c1[1]);
                    d.line("REF", c0[0], c1[1], c0[0], c0[1]);
                    double[] tc = rb(pt.x, pt.y);
                    d.text("TEXT", tc[0], tc[1], 2.5, pt.name);
                }
            }
        }
        // marks: sensors, LEDs, axle line, centre of mass, front arrow
        for (double[] s : m.sensorPositions()) {
            double[] c = rb(s[0], s[1]);
            d.circle("MARK", c[0], c[1], 1.0);
            d.line("MARK", c[0] - 1.5, c[1], c[0] + 1.5, c[1]);
            d.line("MARK", c[0], c[1] - 1.5, c[0], c[1] + 1.5);
        }
        if (m.whiteLeds) {
            double[] lx = new double[Model.MAXL], ly = new double[Model.MAXL];
            int n = m.ledLayout(lx, ly);
            for (int i = 0; i < n; i++) {
                double[] c = rb(lx[i], ly[i]);
                d.circle("MARK", c[0], c[1], 1.5);
            }
        }
        double hw = m.wheelBase / 2 + m.tireWidth;
        double[] a0 = rb(0, -hw), a1 = rb(0, hw);
        d.line("MARK", a0[0], a0[1], a1[0], a1[1]);
        double[] cg = m.centreOfMass(), cc = rb(cg[0], cg[1]);
        d.circle("MARK", cc[0], cc[1], 3);
        d.line("MARK", cc[0] - 4, cc[1], cc[0] + 4, cc[1]);
        d.line("MARK", cc[0], cc[1] - 4, cc[0], cc[1] + 4);
        double front = m.chassisOutline().getBounds2D().getMaxX();
        front = Math.max(front, m.sensorBoard().getBounds2D().getMaxX());
        double[] t0 = rb(front + 6, 0), t1 = rb(front + 16, 0);
        d.line("MARK", t0[0], t0[1], t1[0], t1[1]);
        d.line("MARK", t1[0], t1[1], t1[0] - 3, t1[1] - 4);
        d.line("MARK", t1[0], t1[1], t1[0] + 3, t1[1] - 4);
        d.text("TEXT", t1[0] + 4, t1[1] - 3, 4, "FRONT");
        // text
        double[] b = d.bounds();
        double ty = b[1] - 8;
        String[] lines = {
            f("LineFollowerSim robot, 1:1 in mm. %s.", m.wheelCount == 2 ? "2 wheels + caster"
                    : car ? (m.steerMode == 1 ? "car, front steering, rear drive" : "car, front steering, all-wheel drive") : "4 wheels, skid steer"),
            f("Wheel base %.1f mm, wheels %.0f x %.0f mm%s, axle to sensors %.1f mm (from %s).", m.wheelBase, m.wheelDia, m.tireWidth,
                    m.wheelCount == 4 ? f(", axle spacing %.1f mm", m.axleSpacing) : "", m.forward,
                    m.wheelCount == 4 ? "half-way between the axles" : "the axle"),
            f("%d sensors, pitch %.2f mm, height %.1f mm%s.", m.nSensors, m.spacing, m.sensorHeight,
                    m.arrayShape == 1 ? f(", V %.0f deg", m.arrayAngle) : m.arrayShape == 2 ? f(", arc R %.0f mm", m.arrayRadius) : ""),
            "Layers: CUT outlines, HOLES drill/cut, MARK engrave, REF wheels (do not cut), TEXT.",
            "Hole positions are typical values (" + Mounts.TYPES[mt.type] + ") - check them against your parts before cutting."};
        for (String s : lines) {
            d.text("TEXT", b[0], ty, 3, s);
            ty -= 5;
        }
        return d;
    }

    // ------------------------------------------------------------------ track drawing
    private static List<List<double[]>> dashes(List<double[]> pts, double dash, double gap) {
        List<List<double[]>> out = new ArrayList<>();
        if (dash <= 0 || gap <= 0) {
            out.add(pts);
            return out;
        }
        boolean on = true;
        double left = dash;
        List<double[]> cur = new ArrayList<>();
        cur.add(pts.get(0));
        for (int i = 1; i < pts.size(); i++) {
            double[] a = pts.get(i - 1), b = pts.get(i);
            double len = Math.hypot(b[0] - a[0], b[1] - a[1]), pos = 0;
            while (len - pos > left) {
                pos += left;
                double[] p = {a[0] + (b[0] - a[0]) * pos / len, a[1] + (b[1] - a[1]) * pos / len};
                if (on) {
                    cur.add(p);
                    out.add(cur);
                } else {
                    cur = new ArrayList<>();
                    cur.add(p);
                }
                on = !on;
                left = on ? dash : gap;
            }
            left -= len - pos;
            if (on) {
                cur.add(b);
            }
        }
        if (on && cur.size() > 1) {
            out.add(cur);
        }
        return out;
    }

    static Drawing track(Model m, double tol) {
        Drawing d = new Drawing();
        d.layer("ARENA", 8);
        d.layer("LINE_OUTLINE", 7);
        d.layer("CENTRELINE", 1);
        d.layer("START", 3);
        d.layer("PATCHES", 6);
        d.layer("TEXT", 8);
        double H = m.arenaH;
        java.util.function.UnaryOperator<double[]> flip = p -> new double[]{p[0], H - p[1]};
        d.rect("ARENA", 0, 0, m.arenaW, m.arenaH);
        Area ink = new Area();
        for (TrackShape s : m.shapes) {                                   // same order as the painting: erasers cut earlier ink
            if (s.erase) {
                ink.subtract(s.area());
            } else {
                ink.add(s.area());
            }
        }
        ink.intersect(new Area(new Rectangle2D.Double(0, 0, m.arenaW, m.arenaH)));
        d.shape("LINE_OUTLINE", ink, tol, flip);
        for (TrackShape s : m.shapes) {
            if (s.erase) {
                continue;
            }
            List<List<double[]>> parts = new ArrayList<>();
            double[] c = new double[6];
            List<double[]> cur = null;
            double[] first = null;
            for (PathIterator it = s.geometry().getPathIterator(null, tol); !it.isDone(); it.next()) {
                int t = it.currentSegment(c);
                if (t == PathIterator.SEG_MOVETO) {
                    cur = new ArrayList<>();
                    parts.add(cur);
                    first = new double[]{c[0], c[1]};
                    cur.add(first);
                } else if (t == PathIterator.SEG_LINETO && cur != null) {
                    cur.add(new double[]{c[0], c[1]});
                } else if (t == PathIterator.SEG_CLOSE && cur != null && first != null) {
                    cur.add(first.clone());
                }
            }
            for (List<double[]> p : parts) {
                if (p.size() < 2) {
                    continue;
                }
                for (List<double[]> seg : dashes(p, s.dash, s.gap)) {
                    List<double[]> fl = new ArrayList<>();
                    for (double[] q : seg) {
                        fl.add(flip.apply(q));
                    }
                    d.poly("CENTRELINE", fl, false);
                }
            }
        }
        // start position and direction
        double[] s0 = flip.apply(new double[]{m.sx, m.sy});
        double ang = -m.sth;
        d.circle("START", s0[0], s0[1], 6);
        d.line("START", s0[0], s0[1], s0[0] + 30 * Math.cos(ang), s0[1] + 30 * Math.sin(ang));
        d.text("START", s0[0] + 8, s0[1] + 8, 6, "START");
        for (Model.Patch p : m.patches) {
            Shape r = AffineTransform.getRotateInstance(p.ang, p.cx, p.cy)
                    .createTransformedShape(new Rectangle2D.Double(p.cx - p.hl, p.cy - p.hw, 2 * p.hl, 2 * p.hw));
            d.shape("PATCHES", r, tol, flip);
        }
        d.text("TEXT", 0, -12, 6, f("LineFollowerSim track, 1:1 in mm, arena %.0f x %.0f mm, line %.0f mm.", m.arenaW, m.arenaH,
                m.checkLineWidth()));
        d.text("TEXT", 0, -21, 4, "LINE_OUTLINE: edges of the tape / paint (cut vinyl, engrave). CENTRELINE: the path (plotter, CNC pen).");
        return d;
    }

    // ------------------------------------------------------------------ parts list
    static String partsList(Model m) {
        boolean car = m.steerMode != 0;
        int motors = m.wheelCount == 4 && m.steerMode != 1 ? 4 : 2;
        String motorName = f("%.0f rpm %.0f V gear motor", m.motorRpm, m.motorVolt);
        for (HardwarePresets.P p : HardwarePresets.MOTORS) {
            if (p.v[0] == m.motorRpm && p.v[1] == m.motorVolt) {
                motorName = p.name;
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("LineFollowerSim - parts list for the simulated robot\n");
        sb.append("====================================================\n\n");
        sb.append(f("%-28s %s%n", "Layout", m.wheelCount == 2 ? "2 driven wheels + ball caster" : car
                ? (m.steerMode == 1 ? "car: front steering, rear-wheel drive" : "car: front steering, all-wheel drive") : "4 wheels, skid steer"));
        sb.append(f("%-28s %d x %s (stall torque about %.2f kg.cm)%n", "Drive motors", motors, motorName, m.stallTorque));
        sb.append(f("%-28s %d x %.0f mm diameter, %.0f mm wide tyres%n", "Wheels", m.wheelCount, m.wheelDia, m.tireWidth));
        if (m.wheelCount == 2) {
            sb.append(f("%-28s 1 x ball caster, about %.0f mm %s the axle%n", "Caster", Math.abs(m.casterX()), m.casterX() < 0 ? "behind" : "ahead of"));
        }
        if (car) {
            sb.append(f("%-28s %s, %.0f deg lock each way, %.0f deg/s%n", "Steering", m.steerActuator == 0 ? "servo (SG90 / MG90S size)"
                    : "DC motor with end stops and a centring spring", m.maxSteerDeg, m.servoRate));
        }
        String driver = motors == 4 && !car ? "2 x TB6612FNG (or 1 x L298N with each side's motors in parallel)"
                : car ? (m.steerActuator == 1 ? "1 x TB6612FNG (A: drive, B: steering motor)" : "1 x TB6612FNG (both channels: drive)")
                : "1 x TB6612FNG (or L298N / DRV8833)";
        sb.append(f("%-28s %s%n", "Motor driver", driver));
        sb.append(f("%-28s %s%n", "Microcontroller", f("%.0f MHz board (MCU tab)", m.clockMHz)));
        sb.append(f("%-28s %d x IR reflective sensor (TCRT5000 / QTR style), %s, pitch %.1f mm, %.1f mm above the floor%s%n",
                "Line sensors", m.nSensors * m.nRows, m.analogMode ? "analog" : "digital", m.pitchEff(), m.sensorHeight,
                m.customLayout ? " (custom layout: see the drawing)" : ""));
        if (m.whiteLeds) {
            double[] lx = new double[Model.MAXL], ly = new double[Model.MAXL];
            sb.append(f("%-28s %d x %s LED (%s)%n", "LEDs", m.ledLayout(lx, ly), Model.LED_COLORS[m.ledColor].toLowerCase(),
                    m.whiteMode == 1 ? "floor light" : "indicator"));
        }
        if (m.ambientShield > 0) {
            sb.append(f("%-28s skirt / hood around the sensors (blocks about %.0f %% of room light)%n", "Light shield", m.ambientShield * 100));
        }
        sb.append(f("%-28s %.1f V (%s)%s%n", "Battery", m.battVolt, nearestBattery(m.battVolt),
                m.battCapacity > 0 ? f(", %.0f mAh", m.battCapacity) : ""));
        if (m.encType > 0) {
            String[] en = {"", "N20 magnetic encoder", "TT optical disc encoder", "Pololu magnetic encoder", "encoder"};
            sb.append(f("%-28s 2 x %s, %.0f counts per wheel turn%s%n", "Wheel encoders", en[m.encType], m.encCountsPerRev(),
                    m.speedLoop ? " (speed loop on)" : ""));
        }
        if (m.useParts && !m.parts.isEmpty()) {
            double[] pc = m.partsCentre();
            sb.append(f("%-28s %.0f g in all (plate %.0f g), centre of mass %.0f mm %s the axle, %.0f mm high%n", "Weight (Robot editor)",
                    m.partsMassG(), m.plateMassG(), Math.abs(pc[0]), pc[0] >= 0 ? "ahead of" : "behind", pc[2]));
        }
        Rectangle2D cb = m.chassisOutline().getBounds2D(), bb = m.sensorBoard().getBounds2D();
        sb.append(f("%-28s %s, %.0f x %.0f mm (File > Export robot drawing for the outline)%n", "Chassis plate",
                new String[]{"rectangle", "round", "T (body + arm)", "custom outline"}[m.chassisShape], cb.getHeight(), cb.getWidth()));
        sb.append(bb.isEmpty() ? f("%-28s on the plate (one-piece PCB)%n", "Sensor board")
                : f("%-28s %.0f x %.0f mm%n", "Sensor board", bb.getHeight(), bb.getWidth()));
        sb.append(f("%-28s about %.0f g in total%n", "Target mass", m.massG));
        sb.append("\nKey dimensions\n--------------\n");
        sb.append(f("Wheel base (left-right wheel centres)   %.1f mm%n", m.wheelBase));
        if (m.wheelCount == 4) {
            sb.append(f("Axle spacing (front-rear axle)          %.1f mm%n", m.axleSpacing));
        }
        sb.append(f("Axle to sensor row                      %.1f mm%n", m.forward));
        sb.append(f("Simulated top speed                     %.0f mm/s%n", m.maxSpeed));
        sb.append(f("PID                                     Kp %.2f  Ki %.3f  Kd %.3f, base speed %.0f mm/s%n", m.kp, m.ki, m.kd, m.baseSpeed));
        sb.append("\nPlus: wires, screws / standoffs, a power switch and a fuse. Check every part's data sheet - the presets are typical values.\n");
        return sb.toString();
    }

    private static String nearestBattery(double v) {
        String best = "";
        double d = 1e9;
        for (HardwarePresets.P p : HardwarePresets.BATTERIES) {
            if (Math.abs(p.v[0] - v) < d) {
                d = Math.abs(p.v[0] - v);
                best = p.name;
            }
        }
        return best;
    }

    // ------------------------------------------------------------------ real-size printing
    /** The track at 1:1 on as many pages as needed, with overlap, alignment marks and a scale check. */
    static final class TrackPrint implements Pageable, Printable {

        static final double PT = 72 / 25.4, OVERLAP = 10;
        final Model m;
        final PageFormat pf;
        final int cols, rows;
        final double tileW, tileH;

        TrackPrint(Model m, PageFormat pf) {
            this.m = m;
            this.pf = pf;
            tileW = pf.getImageableWidth() / PT - OVERLAP;
            tileH = pf.getImageableHeight() / PT - OVERLAP - 8;            // 8 mm for the page label
            cols = (int) Math.ceil(m.arenaW / tileW);
            rows = (int) Math.ceil(m.arenaH / tileH);
        }

        @Override
        public int getNumberOfPages() {
            return cols * rows;
        }

        @Override
        public PageFormat getPageFormat(int i) {
            return pf;
        }

        @Override
        public Printable getPrintable(int i) {
            return this;
        }

        @Override
        public int print(Graphics g0, PageFormat p, int page) throws PrinterException {
            if (page >= cols * rows) {
                return NO_SUCH_PAGE;
            }
            int r = page / cols, c = page % cols;
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.translate(p.getImageableX(), p.getImageableY());
            g.scale(PT, PT);                                               // from here on: millimetres
            double x0 = c * tileW, y0 = r * tileH;
            g.setColor(Color.DARK_GRAY);
            g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 3));
            g.drawString(f("LineFollowerSim track 1:1 - page %d of %d (row %d, column %d) - glue so the marks meet; "
                    + "check: the bar is 50 mm", page + 1, cols * rows, r + 1, c + 1), 0, 4);
            g.setStroke(new BasicStroke(0.4f));
            g.draw(new Line2D.Double(0, 6, 50, 6));                         // 50 mm scale check
            g.draw(new Line2D.Double(0, 5, 0, 7));
            g.draw(new Line2D.Double(50, 5, 50, 7));
            g.translate(0, 8);
            Shape clip = new Rectangle2D.Double(0, 0, tileW + OVERLAP, tileH + OVERLAP);
            g.clip(clip);
            g.translate(-x0, -y0);
            g.setColor(Color.WHITE);
            g.fill(new Rectangle2D.Double(x0, y0, tileW + OVERLAP, tileH + OVERLAP));
            for (TrackShape s : m.shapes) {
                s.paint(g);
            }
            g.setColor(new Color(150, 150, 150));
            g.setStroke(new BasicStroke(0.2f));
            g.draw(new Rectangle2D.Double(0, 0, m.arenaW, m.arenaH));
            g.setColor(new Color(220, 0, 120));                            // alignment crosses on the overlap lines
            for (double ax : new double[]{x0 + tileW, x0}) {
                for (double ay : new double[]{y0 + tileH, y0}) {
                    g.draw(new Line2D.Double(ax - 4, ay, ax + 4, ay));
                    g.draw(new Line2D.Double(ax, ay - 4, ax, ay + 4));
                }
            }
            g.setStroke(new BasicStroke(0.15f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 4f, new float[]{2f, 2f}, 0f));
            g.draw(new Line2D.Double(x0 + tileW, y0, x0 + tileW, y0 + tileH + OVERLAP));   // where the next page starts
            g.draw(new Line2D.Double(x0, y0 + tileH, x0 + tileW + OVERLAP, y0 + tileH));
            g.dispose();
            return PAGE_EXISTS;
        }
    }
}
