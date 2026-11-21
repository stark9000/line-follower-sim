package linefollowersim;

import java.awt.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;

/** Draws the preset tracks and works out where the robot starts on them. */
public final class TrackBuilder {

    public static final String[] PRESETS = {
        "Straight line (start offset)", "Sine wave", "Oval circuit", "Chicane (S-curves)",
        "Sharp 90\u00B0 corners", "Race circuit", "Tightening spiral", "Dashed line (gaps)",
        "Blank (draw your own)", "Crossroads (+ junction)", "T-junction (stem into a crossbar)",
        "Thin-line oval (10 mm)", "Wide-line oval (40 mm)", "Y-junction (fork)"
    };

    /** Preset tracks are designed on an 800 x 480 canvas and scaled to fit the arena. */
    private static final double DW = 800, DH = 480;

    private TrackBuilder() {
    }

    /** Catmull-Rom spline through the given points. */
    static Path2D.Double spline(double[][] p, boolean closed) {
        Path2D.Double path = new Path2D.Double();
        int n = p.length;
        path.moveTo(p[0][0], p[0][1]);
        int segs = closed ? n : n - 1;
        for (int i = 0; i < segs; i++) {
            double[] p0 = p[(i - 1 + n) % n], p1 = p[i], p2 = p[(i + 1) % n], p3 = p[(i + 2) % n];
            if (!closed) {
                if (i == 0) {
                    p0 = p1;
                }
                if (i == n - 2) {
                    p3 = p2;
                }
            }
            path.curveTo(p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6,
                    p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6, p2[0], p2[1]);
        }
        if (closed) {
            path.closePath();
        }
        return path;
    }

    /** Start position/heading taken from the first segment of a path. */
    static double[] startPose(Shape s) {
        PathIterator it = new FlatteningPathIterator(s.getPathIterator(null), 0.5);
        double[] c = new double[6];
        double[] p0 = null, p1 = null;
        while (!it.isDone()) {
            int type = it.currentSegment(c);
            if (type == PathIterator.SEG_MOVETO || type == PathIterator.SEG_LINETO) {
                if (p0 == null) {
                    p0 = new double[]{c[0], c[1]};
                } else if (Math.hypot(c[0] - p0[0], c[1] - p0[1]) > 3) {
                    p1 = new double[]{c[0], c[1]};
                    break;
                }
            }
            it.next();
        }
        return new double[]{p0[0], p0[1], Math.atan2(p1[1] - p0[1], p1[0] - p0[0])};
    }

    /** Points of a shape, flattened. */
    private static java.util.List<double[]> points(Shape s) {
        java.util.List<double[]> l = new ArrayList<>();
        double[] c = new double[6];
        for (PathIterator it = new FlatteningPathIterator(s.getPathIterator(null), 0.5); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == PathIterator.SEG_MOVETO || t == PathIterator.SEG_LINETO) {
                l.add(new double[]{c[0], c[1]});
            }
        }
        return l;
    }

    /** Start at the far end of an open path, heading back along it. */
    static double[] endPose(Shape s) {
        java.util.List<double[]> p = points(s);
        double[] e = p.get(p.size() - 1);
        for (int i = p.size() - 2; i >= 0; i--) {
            double[] q = p.get(i);
            if (Math.hypot(q[0] - e[0], q[1] - e[1]) > 3) {
                return new double[]{e[0], e[1], Math.atan2(q[1] - e[1], q[0] - e[0])};
            }
        }
        return new double[]{e[0], e[1], Math.PI};
    }

    /** True if the path runs clockwise on screen (y points down). */
    static boolean isClockwise(Shape s) {
        java.util.List<double[]> p = points(s);
        double a = 0;
        for (int i = 0; i < p.size(); i++) {
            double[] u = p.get(i), v = p.get((i + 1) % p.size());
            a += u[0] * v[1] - v[0] * u[1];
        }
        return a > 0;
    }

    /** A preset as a list of shapes plus the robot's start pose {x, y, heading} (mm). */
    public static final class Result {

        public final List<TrackShape> shapes = new ArrayList<>();
        public double[] pose;
        public double[] poseReversed;                   // start for running the other way round (open tracks: from the far end)
        public Boolean clockwise;                       // closed tracks: direction of the normal start (null = not a loop)
        public double[] finish, finishReversed;         // open tracks: a finish gate at the far end {x, y, heading}
    }

    private static TrackShape preset(TrackShape.Type t, double lineWidth) {
        TrackShape s = new TrackShape(t);
        s.width = lineWidth;
        s.butt = true;                                   // flat line ends, as the presets always had
        return s;
    }

    private static TrackShape pathShape(AffineTransform tf, Shape design, double lineWidth, boolean miter) {
        TrackShape s = preset(TrackShape.Type.PATH, lineWidth);
        s.path = new Path2D.Double(tf.createTransformedShape(design));
        s.miter = miter;
        return s;
    }

    /**
     * Build preset idx for an arenaW x arenaH mm arena. The presets are designed on an
     * 800 x 480 canvas; they are scaled to fit and centred, while the line keeps its
     * real width (lineWidth, mm).
     */
    public static Result build(int idx, double lineWidth, double arenaW, double arenaH) {
        Result r = new Result();
        double k = Math.min(arenaW / DW, arenaH / DH);
        double ox = (arenaW - DW * k) / 2, oy = (arenaH - DH * k) / 2;
        AffineTransform tf = new AffineTransform();
        tf.translate(ox, oy);
        tf.scale(k, k);
        double sx = 100, sy = 240, sth = 0, dyWorld = 0;
        double[] rev = null;                                 // reversed start in design units (null = turn round on the spot)
        boolean closed = false;
        Shape poseShape = null, revShape = null;
        switch (idx) {
            case 0: {   // straight, robot starts 28 mm off the line
                TrackShape l = preset(TrackShape.Type.LINE, lineWidth);
                l.pts.add(new Point2D.Double(ox + 40 * k, oy + 240 * k));
                l.pts.add(new Point2D.Double(ox + 760 * k, oy + 240 * k));
                r.shapes.add(l);
                sx = 70;
                dyWorld = 28;
                rev = new double[]{730, 240, Math.PI};
                break;
            }
            case 1: {   // sine wave
                Path2D.Double p = new Path2D.Double();
                for (int px = 40; px <= 760; px += 4) {
                    double yy = 240 - 70 * Math.sin((px - 40) * 2 * Math.PI / 480.0);
                    if (px == 40) {
                        p.moveTo(px, yy);
                    } else {
                        p.lineTo(px, yy);
                    }
                }
                r.shapes.add(pathShape(tf, p, lineWidth, false));
                poseShape = p;
                break;
            }
            case 2: {   // oval = rounded rectangle
                TrackShape rc = preset(TrackShape.Type.RECT, lineWidth);
                rc.pts.add(new Point2D.Double(ox + 110 * k, oy + 90 * k));
                rc.pts.add(new Point2D.Double(ox + 690 * k, oy + 390 * k));
                rc.radius = 110 * k;
                r.shapes.add(rc);
                poseShape = new RoundRectangle2D.Double(110, 90, 580, 300, 220, 220);
                break;
            }
            case 3: {   // chicane
                Path2D.Double p = new Path2D.Double();
                p.moveTo(30, 380);
                p.lineTo(110, 380);
                p.curveTo(230, 380, 230, 160, 350, 160);
                p.lineTo(450, 160);
                p.curveTo(570, 160, 570, 380, 690, 380);
                p.lineTo(770, 380);
                r.shapes.add(pathShape(tf, p, lineWidth, false));
                poseShape = p;
                break;
            }
            case 4: {   // sharp 90 degree corners
                double[][] pts = {{40, 400}, {240, 400}, {240, 120}, {440, 120}, {440, 400}, {640, 400}, {640, 120}, {770, 120}};
                TrackShape pl = preset(TrackShape.Type.POLYLINE, lineWidth);
                pl.miter = true;
                Path2D.Double p = new Path2D.Double();
                for (int i = 0; i < pts.length; i++) {
                    pl.pts.add(new Point2D.Double(ox + pts[i][0] * k, oy + pts[i][1] * k));
                    if (i == 0) {
                        p.moveTo(pts[i][0], pts[i][1]);
                    } else {
                        p.lineTo(pts[i][0], pts[i][1]);
                    }
                }
                r.shapes.add(pl);
                poseShape = p;
                break;
            }
            case 5: {   // race circuit = closed smooth curve through 11 points
                double[][] pts = {{150, 100}, {400, 60}, {650, 100}, {735, 210}, {650, 290}, {545, 250},
                {470, 300}, {455, 385}, {300, 425}, {120, 370}, {70, 240}};
                TrackShape c = preset(TrackShape.Type.CURVE, lineWidth);
                c.closed = true;
                for (double[] q : pts) {
                    c.pts.add(new Point2D.Double(ox + q[0] * k, oy + q[1] * k));
                }
                r.shapes.add(c);
                poseShape = spline(pts, true);
                break;
            }
            case 6: {   // tightening spiral
                Path2D.Double p = new Path2D.Double();
                double turns = 3.0, r0 = 205, r1 = 55;
                for (double a = 0; a <= turns * 2 * Math.PI; a += 0.03) {
                    double rr = r0 - (r0 - r1) * a / (turns * 2 * Math.PI);
                    double px = 400 + rr * Math.cos(a), py = 240 + rr * Math.sin(a);
                    if (a == 0) {
                        p.moveTo(px, py);
                    } else {
                        p.lineTo(px, py);
                    }
                }
                r.shapes.add(pathShape(tf, p, lineWidth, false));
                poseShape = p;
                break;
            }
            case 7: {   // dashed line with gaps
                double[][] seg = {{40, 200}, {240, 420}, {460, 620}, {660, 780}};
                for (double[] q : seg) {
                    TrackShape l = preset(TrackShape.Type.LINE, lineWidth);
                    l.pts.add(new Point2D.Double(ox + q[0] * k, oy + 240 * k));
                    l.pts.add(new Point2D.Double(ox + q[1] * k, oy + 240 * k));
                    r.shapes.add(l);
                }
                sx = 70;
                dyWorld = 6;
                rev = new double[]{730, 240, Math.PI};
                break;
            }
            case 9: {   // crossroads: a long line crossed by another; the robot starts on the horizontal line
                for (double[] q : new double[][]{{40, 240, 760, 240}, {400, 30, 400, 450}}) {
                    TrackShape l = preset(TrackShape.Type.LINE, lineWidth);
                    l.pts.add(new Point2D.Double(ox + q[0] * k, oy + q[1] * k));
                    l.pts.add(new Point2D.Double(ox + q[2] * k, oy + q[3] * k));
                    r.shapes.add(l);
                }
                sx = 70;
                rev = new double[]{730, 240, Math.PI};
                break;
            }
            case 10: {  // T-junction: the robot drives up the stem and meets the crossbar
                for (double[] q : new double[][]{{40, 200, 760, 200}, {400, 200, 400, 450}}) {
                    TrackShape l = preset(TrackShape.Type.LINE, lineWidth);
                    l.pts.add(new Point2D.Double(ox + q[0] * k, oy + q[1] * k));
                    l.pts.add(new Point2D.Double(ox + q[2] * k, oy + q[3] * k));
                    r.shapes.add(l);
                }
                sx = 400;
                sy = 420;
                sth = -Math.PI / 2;
                break;
            }
            case 11:
            case 12: {  // the oval again with a thin (10 mm) or wide (40 mm) line, whatever the line-width setting
                TrackShape rc = preset(TrackShape.Type.RECT, idx == 11 ? 10 : 40);
                rc.pts.add(new Point2D.Double(ox + 110 * k, oy + 90 * k));
                rc.pts.add(new Point2D.Double(ox + 690 * k, oy + 390 * k));
                rc.radius = 110 * k;
                r.shapes.add(rc);
                poseShape = new RoundRectangle2D.Double(110, 90, 580, 300, 220, 220);
                break;
            }
            case 13: {  // Y-junction: a straight stem that splits into two branches that curve apart
                Path2D.Double stem = new Path2D.Double();
                stem.moveTo(40, 240);
                stem.lineTo(330, 240);
                Path2D.Double up = new Path2D.Double();
                up.moveTo(330, 240);
                up.curveTo(430, 240, 470, 110, 600, 110);
                up.lineTo(770, 110);
                Path2D.Double down = new Path2D.Double();
                down.moveTo(330, 240);
                down.curveTo(430, 240, 470, 370, 600, 370);
                down.lineTo(770, 370);
                for (Path2D.Double q : new Path2D.Double[]{stem, up, down}) {
                    r.shapes.add(pathShape(tf, q, lineWidth, false));
                }
                poseShape = stem;
                revShape = up;                              // reversed: come down the upper branch into the fork
                break;
            }
            default:    // blank
                break;
        }
        closed = idx == 2 || idx == 5 || idx == 11 || idx == 12;
        if (poseShape != null) {
            double[] sp = startPose(poseShape);
            sx = sp[0];
            sy = sp[1];
            sth = sp[2];
            if (closed) {
                r.clockwise = isClockwise(poseShape);
            } else if (idx != 10) {
                rev = endPose(revShape != null ? revShape : poseShape);
            }
        }
        r.pose = new double[]{ox + k * sx, oy + k * sy + dyWorld, sth};
        r.poseReversed = rev == null ? new double[]{r.pose[0], r.pose[1], sth + Math.PI}
                : new double[]{ox + k * rev[0], oy + k * rev[1] + dyWorld, rev[2]};
        if (rev != null && idx != 10) {                     // open track: finish 40 mm before each end, facing out
            double[] a = r.poseReversed, b = r.pose;
            r.finish = new double[]{a[0] + Math.cos(a[2]) * 40, a[1] - dyWorld + Math.sin(a[2]) * 40, a[2] + Math.PI};
            r.finishReversed = new double[]{b[0] + Math.cos(b[2]) * 40, b[1] - dyWorld + Math.sin(b[2]) * 40, b[2] + Math.PI};
        }
        return r;
    }
}
