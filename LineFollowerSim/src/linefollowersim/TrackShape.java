package linefollowersim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Shape;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.geom.RoundRectangle2D;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * One element of a track, in real millimetres. Every element is defined by
 * control points (so it can be edited by dragging them), except PATH, which
 * holds fixed geometry (used for a few preset tracks): it can be moved and
 * deleted but its points cannot be edited.
 *
 *   LINE     2 points: start, end
 *   POLYLINE n points joined by straight segments (freehand strokes are polylines)
 *   CURVE    n points joined by a smooth spline
 *   ARC      3 points: start, a point on the arc, end
 *   RECT     2 points: opposite corners (optional corner radius)
 *   ELLIPSE  2 points: opposite corners of the bounding box
 */
public class TrackShape {

    public enum Type {
        LINE, POLYLINE, CURVE, ARC, RECT, ELLIPSE, PATH
    }

    public Type type;
    public final List<Point2D.Double> pts = new ArrayList<>();
    public Path2D.Double path;                        // PATH only
    public double width = 20;                         // line width, mm
    public double dash = 0, gap = 0;                  // dash pattern, mm (0 = solid)
    public double radius = 0;                         // RECT corner radius, mm
    public boolean closed, filled;
    public boolean erase;                             // paints white: removes line underneath
    public boolean miter, butt;                       // sharp joins / flat ends (used by presets)

    public TrackShape(Type type) {
        this.type = type;
    }

    // ------------------------------------------------------------------ copying
    public TrackShape copy() {
        TrackShape s = new TrackShape(type);
        s.assign(this);
        return s;
    }

    /** Make this shape identical to o (deep copy). */
    public void assign(TrackShape o) {
        type = o.type;
        pts.clear();
        for (Point2D.Double p : o.pts) {
            pts.add(new Point2D.Double(p.x, p.y));
        }
        path = o.path == null ? null : new Path2D.Double(o.path);
        width = o.width;
        dash = o.dash;
        gap = o.gap;
        radius = o.radius;
        closed = o.closed;
        filled = o.filled;
        erase = o.erase;
        miter = o.miter;
        butt = o.butt;
    }

    public boolean editable() {
        return type != Type.PATH;
    }

    // ------------------------------------------------------------------ geometry
    private boolean dashed() {
        return dash > 0 && gap > 0;
    }

    public Stroke stroke() {
        int cap = (butt || dashed()) ? BasicStroke.CAP_BUTT : BasicStroke.CAP_ROUND;
        int join = miter ? BasicStroke.JOIN_MITER : BasicStroke.JOIN_ROUND;
        float w = (float) Math.max(width, 0.1);
        if (dashed()) {
            return new BasicStroke(w, cap, join, 10f, new float[]{(float) dash, (float) gap}, 0f);
        }
        return new BasicStroke(w, cap, join, 10f);
    }

    private Rectangle2D corners() {
        Point2D.Double a = pts.get(0), b = pts.get(1);
        return new Rectangle2D.Double(Math.min(a.x, b.x), Math.min(a.y, b.y), Math.abs(a.x - b.x), Math.abs(a.y - b.y));
    }

    /** The centre line (or outline) of the shape. */
    public Shape geometry() {
        switch (type) {
            case LINE:
                return new Line2D.Double(pts.get(0), pts.get(1));
            case RECT: {
                Rectangle2D r = corners();
                double rr = Math.min(radius, Math.min(r.getWidth(), r.getHeight()) / 2);
                return rr > 0.01
                        ? new RoundRectangle2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight(), 2 * rr, 2 * rr)
                        : r;
            }
            case ELLIPSE: {
                Rectangle2D r = corners();
                return new Ellipse2D.Double(r.getX(), r.getY(), r.getWidth(), r.getHeight());
            }
            case POLYLINE: {
                Path2D.Double p = new Path2D.Double();
                for (int i = 0; i < pts.size(); i++) {
                    Point2D.Double q = pts.get(i);
                    if (i == 0) {
                        p.moveTo(q.x, q.y);
                    } else {
                        p.lineTo(q.x, q.y);
                    }
                }
                if (closed && pts.size() >= 3) {
                    p.closePath();
                }
                return p;
            }
            case CURVE: {
                if (pts.size() < 2) {
                    Path2D.Double p = new Path2D.Double();
                    if (!pts.isEmpty()) {
                        p.moveTo(pts.get(0).x, pts.get(0).y);
                    }
                    return p;
                }
                double[][] a = new double[pts.size()][];
                for (int i = 0; i < a.length; i++) {
                    a[i] = new double[]{pts.get(i).x, pts.get(i).y};
                }
                return TrackBuilder.spline(a, closed && pts.size() >= 3);
            }
            case ARC:
                return arcPath();
            default:
                return path;
        }
    }

    /** Circular arc from p0 to p2 passing through p1 (sampled every 2 degrees). */
    private Shape arcPath() {
        if (pts.size() < 3) {
            Path2D.Double p = new Path2D.Double();
            if (!pts.isEmpty()) {
                p.moveTo(pts.get(0).x, pts.get(0).y);
                p.lineTo(pts.get(pts.size() - 1).x, pts.get(pts.size() - 1).y);
            }
            return p;
        }
        Point2D.Double a = pts.get(0), b = pts.get(1), c = pts.get(2);
        double d = 2 * (a.x * (b.y - c.y) + b.x * (c.y - a.y) + c.x * (a.y - b.y));
        if (Math.abs(d) < 1e-9) {                                   // the three points are in a line
            return new Line2D.Double(a, c);
        }
        double a2 = a.x * a.x + a.y * a.y, b2 = b.x * b.x + b.y * b.y, c2 = c.x * c.x + c.y * c.y;
        double ux = (a2 * (b.y - c.y) + b2 * (c.y - a.y) + c2 * (a.y - b.y)) / d;
        double uy = (a2 * (c.x - b.x) + b2 * (a.x - c.x) + c2 * (b.x - a.x)) / d;
        double r = Math.hypot(a.x - ux, a.y - uy);
        double a0 = Math.atan2(a.y - uy, a.x - ux);
        double d1 = norm(Math.atan2(b.y - uy, b.x - ux) - a0);
        double d2 = norm(Math.atan2(c.y - uy, c.x - ux) - a0);
        double extent = d1 < d2 ? d2 : d2 - 2 * Math.PI;           // sweep direction that passes through b
        int n = Math.max(2, (int) Math.ceil(Math.abs(extent) / Math.toRadians(2)));
        Path2D.Double p = new Path2D.Double();
        p.moveTo(a.x, a.y);
        for (int i = 1; i < n; i++) {
            double ang = a0 + extent * i / n;
            p.lineTo(ux + r * Math.cos(ang), uy + r * Math.sin(ang));
        }
        p.lineTo(c.x, c.y);
        return p;
    }

    private static double norm(double a) {
        a %= 2 * Math.PI;
        return a < 0 ? a + 2 * Math.PI : a;
    }

    /** True if the inside of the shape is painted too. */
    private boolean areaFilled() {
        if (!filled) {
            return false;
        }
        return type == Type.RECT || type == Type.ELLIPSE
                || ((type == Type.POLYLINE || type == Type.CURVE) && closed && pts.size() >= 3);
    }

    /** The ink of this shape as an area: the stroked line, plus the inside when filled. */
    public java.awt.geom.Area area() {
        Shape geo = geometry();
        java.awt.geom.Area a = new java.awt.geom.Area(stroke().createStrokedShape(geo));
        if (areaFilled()) {
            a.add(new java.awt.geom.Area(geo));
        }
        return a;
    }

    // ------------------------------------------------------------------ drawing / picking
    public void paint(Graphics2D g) {
        Shape geo = geometry();
        g.setColor(erase ? Color.WHITE : Color.BLACK);
        if (areaFilled()) {
            g.fill(geo);
        }
        g.setStroke(stroke());
        g.draw(geo);
    }

    /** Is the point (mm) on this shape? tol is the extra pick distance in mm. */
    public boolean hit(Point2D p, double tol) {
        Shape geo = geometry();
        if (areaFilled() && geo.contains(p)) {
            return true;
        }
        double w = Math.max(width, 2 * tol);
        return new BasicStroke((float) w, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                .createStrokedShape(geo).contains(p);
    }

    public Rectangle2D bounds() {
        return geometry().getBounds2D();
    }

    /** Scale, rotate or mirror the shape. A rectangle or ellipse turned by an odd angle becomes a free path. */
    public void transform(AffineTransform at) {
        boolean square = Math.abs(at.getShearX()) < 1e-9 && Math.abs(at.getShearY()) < 1e-9
                || Math.abs(at.getScaleX()) < 1e-9 && Math.abs(at.getScaleY()) < 1e-9;   // 0 / 90 / 180 / 270 degrees
        if ((type == Type.RECT || type == Type.ELLIPSE) && !square) {
            path = new Path2D.Double(geometry());
            type = Type.PATH;
            pts.clear();
            closed = true;
        }
        double k = Math.sqrt(Math.abs(at.getDeterminant()));
        radius *= k;
        for (Point2D.Double p : pts) {
            at.transform(p, p);
        }
        if (path != null) {
            path.transform(at);
        }
    }

    public void translate(double dx, double dy) {
        for (Point2D.Double p : pts) {
            p.x += dx;
            p.y += dy;
        }
        if (path != null) {
            path.transform(AffineTransform.getTranslateInstance(dx, dy));
        }
    }

    /** A point that moves with the shape, used for grid snapping while dragging. */
    public Point2D.Double reference() {
        if (!pts.isEmpty()) {
            return new Point2D.Double(pts.get(0).x, pts.get(0).y);
        }
        Rectangle2D b = bounds();
        return new Point2D.Double(b.getX(), b.getY());
    }

    // ------------------------------------------------------------------ text format
    private static String n(double v) {
        return Double.toString(v);
    }

    public String serialize() {
        StringBuilder sb = new StringBuilder("shape ").append(type);
        sb.append(" w=").append(n(width)).append(" dash=").append(n(dash)).append(" gap=").append(n(gap));
        sb.append(" r=").append(n(radius));
        sb.append(" closed=").append(closed ? 1 : 0).append(" filled=").append(filled ? 1 : 0);
        sb.append(" erase=").append(erase ? 1 : 0).append(" miter=").append(miter ? 1 : 0);
        sb.append(" butt=").append(butt ? 1 : 0);
        if (type == Type.PATH) {
            sb.append(" d=");
            double[] c = new double[6];
            for (PathIterator it = path.getPathIterator(null); !it.isDone(); it.next()) {
                switch (it.currentSegment(c)) {
                    case PathIterator.SEG_MOVETO:
                        sb.append('M').append(n(c[0])).append(',').append(n(c[1]));
                        break;
                    case PathIterator.SEG_LINETO:
                        sb.append('L').append(n(c[0])).append(',').append(n(c[1]));
                        break;
                    case PathIterator.SEG_QUADTO:
                        sb.append('Q').append(n(c[0])).append(',').append(n(c[1])).append(',')
                                .append(n(c[2])).append(',').append(n(c[3]));
                        break;
                    case PathIterator.SEG_CUBICTO:
                        sb.append('C').append(n(c[0])).append(',').append(n(c[1])).append(',')
                                .append(n(c[2])).append(',').append(n(c[3])).append(',')
                                .append(n(c[4])).append(',').append(n(c[5]));
                        break;
                    default:
                        sb.append('Z');
                }
            }
        } else {
            sb.append(" pts=");
            for (int i = 0; i < pts.size(); i++) {
                if (i > 0) {
                    sb.append(';');
                }
                sb.append(n(pts.get(i).x)).append(',').append(n(pts.get(i).y));
            }
        }
        return sb.toString();
    }

    public static TrackShape parse(String line) throws IOException {
        String[] tok = line.trim().split("\\s+");
        if (tok.length < 2 || !tok[0].equals("shape")) {
            throw new IOException("Not a shape line: " + line);
        }
        TrackShape s;
        try {
            s = new TrackShape(Type.valueOf(tok[1]));
        } catch (IllegalArgumentException ex) {
            throw new IOException("Unknown shape type: " + tok[1]);
        }
        try {
            for (int i = 2; i < tok.length; i++) {
                String[] kv = tok[i].split("=", 2);
                if (kv.length < 2) {
                    continue;
                }
                String v = kv[1];
                switch (kv[0]) {
                    case "w":
                        s.width = Double.parseDouble(v);
                        break;
                    case "dash":
                        s.dash = Double.parseDouble(v);
                        break;
                    case "gap":
                        s.gap = Double.parseDouble(v);
                        break;
                    case "r":
                        s.radius = Double.parseDouble(v);
                        break;
                    case "closed":
                        s.closed = v.equals("1");
                        break;
                    case "filled":
                        s.filled = v.equals("1");
                        break;
                    case "erase":
                        s.erase = v.equals("1");
                        break;
                    case "miter":
                        s.miter = v.equals("1");
                        break;
                    case "butt":
                        s.butt = v.equals("1");
                        break;
                    case "pts":
                        for (String pt : v.split(";")) {
                            String[] xy = pt.split(",");
                            s.pts.add(new Point2D.Double(Double.parseDouble(xy[0]), Double.parseDouble(xy[1])));
                        }
                        break;
                    case "d":
                        s.path = parsePath(v);
                        break;
                    default:
                        break;                       // ignore keys from newer versions
                }
            }
        } catch (RuntimeException ex) {
            throw new IOException("Damaged shape line: " + line);
        }
        int need = s.type == Type.ARC ? 3 : (s.type == Type.LINE || s.type == Type.RECT || s.type == Type.ELLIPSE) ? 2 : 0;
        boolean ok = s.type == Type.PATH ? s.path != null
                : (need > 0 ? s.pts.size() == need : s.pts.size() >= 2);
        if (!ok) {
            throw new IOException("Wrong number of points in: " + line);
        }
        return s;
    }

    private static Path2D.Double parsePath(String d) {
        Path2D.Double p = new Path2D.Double();
        for (String seg : d.split("(?=[MLQCZ])")) {
            if (seg.isEmpty()) {
                continue;
            }
            char cmd = seg.charAt(0);
            String[] f = seg.substring(1).isEmpty() ? new String[0] : seg.substring(1).split(",");
            double[] v = new double[f.length];
            for (int i = 0; i < f.length; i++) {
                v[i] = Double.parseDouble(f[i]);
            }
            switch (cmd) {
                case 'M':
                    p.moveTo(v[0], v[1]);
                    break;
                case 'L':
                    p.lineTo(v[0], v[1]);
                    break;
                case 'Q':
                    p.quadTo(v[0], v[1], v[2], v[3]);
                    break;
                case 'C':
                    p.curveTo(v[0], v[1], v[2], v[3], v[4], v[5]);
                    break;
                default:
                    p.closePath();
            }
        }
        return p;
    }
}
