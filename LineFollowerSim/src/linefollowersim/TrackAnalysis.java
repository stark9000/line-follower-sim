package linefollowersim;

import java.awt.Shape;
import java.awt.geom.FlatteningPathIterator;
import java.awt.geom.Line2D;
import java.awt.geom.PathIterator;
import java.util.ArrayList;
import java.util.List;

/**
 * Measures a track for the robot designer: line width, length, the tightest bend, sharp corners,
 * crossings / junctions and gaps. Works on the centre lines of the drawn shapes (no Swing).
 */
final class TrackAnalysis {

    double lineWidth, length, minRadius = Double.POSITIVE_INFINITY, sharpestDeg;
    int sharpCorners, junctions, gaps;
    boolean closed;
    double arenaW, arenaH;

    private TrackAnalysis() {
    }

    /** Points of a shape's centre line, resampled about every 'step' mm (one list per sub-path). */
    private static List<List<double[]>> resample(Shape s, double step) {
        List<List<double[]>> out = new ArrayList<>();
        List<double[]> cur = null;
        double[] c = new double[6], last = null, first = null;
        double carry = 0;
        for (PathIterator it = new FlatteningPathIterator(s.getPathIterator(null), 0.25); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == PathIterator.SEG_MOVETO) {
                cur = new ArrayList<>();
                out.add(cur);
                last = new double[]{c[0], c[1]};
                first = last;
                cur.add(last);
                carry = 0;
            } else if ((t == PathIterator.SEG_LINETO || t == PathIterator.SEG_CLOSE) && cur != null) {
                double[] to = t == PathIterator.SEG_CLOSE ? first : new double[]{c[0], c[1]};
                double dx = to[0] - last[0], dy = to[1] - last[1], len = Math.hypot(dx, dy), pos = step - carry;
                while (pos <= len) {
                    cur.add(new double[]{last[0] + dx * pos / len, last[1] + dy * pos / len});
                    pos += step;
                }
                carry = len - (pos - step);
                last = to;
                if (t == PathIterator.SEG_CLOSE && cur.size() > 2) {
                    cur.add(new double[]{first[0], first[1]});
                }
            }
        }
        return out;
    }

    static TrackAnalysis analyze(Model m) {
        TrackAnalysis a = new TrackAnalysis();
        a.lineWidth = m.checkLineWidth();
        a.arenaW = m.arenaW;
        a.arenaH = m.arenaH;
        List<List<double[]>> lines = new ArrayList<>();
        List<Integer> owner = new ArrayList<>();
        List<double[]> ends = new ArrayList<>();
        int si = 0;
        for (TrackShape sh : m.shapes) {
            si++;
            if (sh.erase) {
                continue;
            }
            boolean shClosed = sh.closed || sh.type == TrackShape.Type.RECT || sh.type == TrackShape.Type.ELLIPSE;
            a.closed |= shClosed;
            if (sh.dash > 0 && sh.gap > 0) {
                a.gaps++;
            }
            for (List<double[]> l : resample(sh.geometry(), 2.0)) {
                if (l.size() < 2) {
                    continue;
                }
                lines.add(l);
                owner.add(si);
                double len = 0;
                for (int i = 1; i < l.size(); i++) {
                    len += Math.hypot(l.get(i)[0] - l.get(i - 1)[0], l.get(i)[1] - l.get(i - 1)[1]);
                }
                a.length += len;
                double[] p0 = l.get(0), p1 = l.get(l.size() - 1);
                if (Math.hypot(p0[0] - p1[0], p0[1] - p1[1]) < 1) {
                    a.closed = true;
                } else {
                    ends.add(p0);
                    ends.add(p1);
                }
            }
        }
        // bends: turning angle over a short window; a sharp turn within about a line width is a "corner"
        double win = Math.max(a.lineWidth, 10);
        int k = Math.max(2, (int) Math.round(win / 2.0));
        for (List<double[]> l : lines) {
            int n = l.size();
            for (int i = k; i + k < n; i++) {
                double[] p = l.get(i - k), q = l.get(i), r = l.get(i + k);
                double a1 = Math.atan2(q[1] - p[1], q[0] - p[0]), a2 = Math.atan2(r[1] - q[1], r[0] - q[0]);
                double turn = Math.abs(Math.atan2(Math.sin(a2 - a1), Math.cos(a2 - a1)));
                if (Math.toDegrees(turn) > 50) {                            // a corner, not a bend
                    a.sharpestDeg = Math.max(a.sharpestDeg, Math.toDegrees(turn));
                    continue;
                }
                double ax = q[0] - p[0], ay = q[1] - p[1], bx = r[0] - q[0], by = r[1] - q[1], cx = r[0] - p[0], cy = r[1] - p[1];
                double cross = Math.abs(ax * by - ay * bx);
                if (cross > 1e-6) {
                    double rad = Math.hypot(ax, ay) * Math.hypot(bx, by) * Math.hypot(cx, cy) / (2 * cross);
                    if (rad > win * 0.75) {
                        a.minRadius = Math.min(a.minRadius, rad);
                    }
                }
            }
            // count separate corners: runs of large turning
            boolean in = false;
            for (int i = k; i + k < n; i++) {
                double[] p = l.get(i - k), q = l.get(i), r = l.get(i + k);
                double a1 = Math.atan2(q[1] - p[1], q[0] - p[0]), a2 = Math.atan2(r[1] - q[1], r[0] - q[0]);
                boolean sharp = Math.toDegrees(Math.abs(Math.atan2(Math.sin(a2 - a1), Math.cos(a2 - a1)))) > 50;
                if (sharp && !in) {
                    a.sharpCorners++;
                }
                in = sharp;
            }
        }
        // crossings / junctions between different shapes (merged within 15 mm)
        List<double[]> hits = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            for (int j = i + 1; j < lines.size(); j++) {
                if (owner.get(i).equals(owner.get(j))) {
                    continue;
                }
                List<double[]> u = lines.get(i), v = lines.get(j);
                if (!bounds(u, a.lineWidth).intersects(bounds(v, a.lineWidth))) {
                    continue;
                }
                double[] ue0 = u.get(0), ue1 = u.get(u.size() - 1), ve0 = v.get(0), ve1 = v.get(v.size() - 1);
                for (int x = 1; x < u.size(); x += 1) {
                    for (int y = 1; y < v.size(); y += 1) {
                        double[] a0 = u.get(x - 1), a1 = u.get(x), b0 = v.get(y - 1), b1 = v.get(y);
                        boolean touch = Line2D.linesIntersect(a0[0], a0[1], a1[0], a1[1], b0[0], b0[1], b1[0], b1[1])
                                || Math.hypot(a1[0] - b1[0], a1[1] - b1[1]) < a.lineWidth / 2;
                        boolean joined = (near(a1, ue0, a.lineWidth) || near(a1, ue1, a.lineWidth))
                                && (near(b1, ve0, a.lineWidth) || near(b1, ve1, a.lineWidth));
                        if (touch && !joined) {                            // two lines simply joined end to end are no junction
                            boolean known = false;
                            for (double[] h : hits) {
                                if (Math.hypot(h[0] - a1[0], h[1] - a1[1]) < 15 + a.lineWidth) {
                                    known = true;
                                    break;
                                }
                            }
                            if (!known) {
                                hits.add(a1.clone());
                            }
                        }
                    }
                }
            }
        }
        a.junctions = hits.size();
        // gaps: line ends close to another line end (a dashed or broken line)
        for (int i = 0; i < ends.size(); i++) {
            for (int j = i + 1; j < ends.size(); j++) {
                if (j == i + 1 && i % 2 == 0) {
                    continue;                                              // the two ends of the same line
                }
                double d = Math.hypot(ends.get(i)[0] - ends.get(j)[0], ends.get(i)[1] - ends.get(j)[1]);
                if (d > 2 && d < 80) {
                    a.gaps++;
                }
            }
        }
        return a;
    }

    private static boolean near(double[] p, double[] q, double d) {
        return Math.hypot(p[0] - q[0], p[1] - q[1]) < d;
    }

    private static java.awt.geom.Rectangle2D bounds(List<double[]> l, double pad) {
        java.awt.geom.Rectangle2D.Double r = new java.awt.geom.Rectangle2D.Double(l.get(0)[0], l.get(0)[1], 0, 0);
        for (double[] p : l) {
            r.add(p[0], p[1]);
        }
        r.setRect(r.x - pad, r.y - pad, r.width + 2 * pad, r.height + 2 * pad);
        return r;
    }

    /** The bend radius the robot must manage (sharp corners count as about a line width). */
    double designRadius() {
        double r = Double.isInfinite(minRadius) ? 1000 : minRadius;
        return sharpCorners > 0 ? Math.min(r, Math.max(lineWidth, 15)) : r;
    }

    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Line width %.0f mm, centre line %.2f m, %s.%n", lineWidth, length / 1000,
                closed ? "a closed loop" : "an open track"));
        sb.append(Double.isInfinite(minRadius) ? "No curved bends." : String.format("Tightest bend: radius %.0f mm.", minRadius));
        sb.append(sharpCorners > 0 ? String.format(" %d sharp corner%s (up to %.0f degrees).", sharpCorners,
                sharpCorners > 1 ? "s" : "", sharpestDeg) : " No sharp corners.").append('\n');
        sb.append(junctions > 0 ? junctions + " crossing / junction point" + (junctions > 1 ? "s" : "") + ". " : "No crossings. ");
        sb.append(gaps > 0 ? gaps + " gap" + (gaps > 1 ? "s" : "") + " in the line." : "No gaps.").append('\n');
        sb.append(String.format("Arena %.0f x %.0f mm.", arenaW, arenaH));
        return sb.toString();
    }
}
