package linefollowersim;

import java.util.ArrayList;
import java.util.List;

/**
 * Automatic tests that run the simulation in private copies of the model (the model you are
 * watching is never touched): step response, 90 degree corner, look-ahead x speed sweep.
 */
final class Experiments {

    private Experiments() {
    }

    /** A private copy of all settings. */
    static Model snapshot(Model base) {
        Model m = new Model();
        m.copySettingsFrom(base);
        return m;
    }

    private static TrackShape line(double x0, double y0, double x1, double y1, double width) {
        TrackShape s = new TrackShape(TrackShape.Type.LINE);
        s.width = width;
        s.pts.add(new java.awt.geom.Point2D.Double(x0, y0));
        s.pts.add(new java.awt.geom.Point2D.Double(x1, y1));
        return s;
    }

    private static Model onTrack(Model snap, double w, double h, List<TrackShape> shapes, double sx, double sy) {
        Model m = new Model();
        m.copySettingsFrom(snap);
        m.setTrack(w, h, shapes, new double[]{sx, sy, 0});
        return m;
    }

    // ------------------------------------------------------------------ step response
    static final class StepResult {

        double offset, rise = Double.NaN, overshootPct, settle = Double.NaN, wobble, steadyAmp, duration;
        int crossings;
        boolean lost, settled;
        String label = "";
        double[] time = new double[0], err = new double[0];
    }

    /**
     * The robot starts beside a straight line (a sideways step). We follow the real distance between the
     * sensor array centre and the line, and measure how it comes back.
     */
    static StepResult stepResponse(Model snap) {
        double v = Math.max(snap.baseSpeed, 20);
        double dur = Model.clamp(1200 / v, 1.0, 4.0);
        double w = Model.clamp(300 + v * dur + 200, 700, 1600), y0 = 200;
        double half = (snap.nSensors - 1) / 2.0 * snap.spacing;
        StepResult r = new StepResult();
        r.offset = Model.clamp(0.35 * half, 6, 25);
        r.duration = dur;
        List<TrackShape> sh = new ArrayList<>();
        sh.add(line(60, y0, w - 40, y0, snap.lineWidth));
        Model m = onTrack(snap, w, 400, sh, 120, y0 + r.offset);
        double dt = 0.005;
        int n = (int) (dur / dt);
        List<Double> ts = new ArrayList<>(), es = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            m.advance(dt);
            ts.add((k + 1) * dt);
            es.add(m.y + Math.sin(m.th) * m.forward - y0);
            if (m.outOfArena) {
                break;
            }
        }
        int cnt = es.size();
        r.time = new double[cnt];
        r.err = new double[cnt];
        for (int i = 0; i < cnt; i++) {
            r.time[i] = ts.get(i);
            r.err[i] = es.get(i);
        }
        double e0 = r.offset;
        // steady-state wobble: a digital array cannot hold the line better than about half its pitch
        int from = (int) (cnt * 0.75);
        double amp = 0, sq = 0;
        for (int i = from; i < cnt; i++) {
            amp = Math.max(amp, Math.abs(r.err[i]));
            sq += r.err[i] * r.err[i];
        }
        r.wobble = cnt > from ? Math.sqrt(sq / (cnt - from)) : 0;
        r.steadyAmp = amp;
        double band = Math.max(Math.max(0.1 * e0, 1.15 * amp), 1.0), minE = 0;
        int lastBad = -1, sign = 0;
        for (int i = 0; i < cnt; i++) {
            double e = r.err[i];
            if (Double.isNaN(r.rise) && Math.abs(e) <= band) {
                r.rise = r.time[i];
            }
            minE = Math.min(minE, e);                                       // the start side is positive
            if (Math.abs(e) > Math.max(1.5 * band, 2.0)) {
                lastBad = i;
            }
            if (Math.abs(e) > band) {                                       // only real swings count as crossings
                int sg = e > 0 ? 1 : -1;
                if (sign != 0 && sg != sign) {
                    r.crossings++;
                }
                sign = sg;
            }
        }
        r.overshootPct = Math.max(0, -minE - amp) / e0 * 100.0;            // beyond the steady-state wobble
        r.settled = cnt > 0 && lastBad < cnt * 0.9;
        r.settle = r.settled ? (lastBad < 0 ? 0 : r.time[lastBad]) : Double.NaN;
        boolean sustained = amp > Math.max(0.5 * e0, 0.75 * snap.spacing);
        r.lost = m.outOfArena || m.lineLostTime > 0.25;
        if (r.lost) {
            r.label = "Lost the line";
        } else if (sustained || !r.settled || r.overshootPct > 25 || r.crossings >= 3) {
            r.label = "Nervous (underdamped)";
        } else if (r.overshootPct > 12) {
            r.label = "Lively (slightly underdamped)";
        } else if (r.overshootPct < 3 && (Double.isNaN(r.rise) || r.rise >= 0.5)) {
            r.label = "Sluggish (overdamped)";
        } else {
            r.label = "Graceful (well damped)";
        }
        return r;
    }

    // ------------------------------------------------------------------ 90 degree corner
    static final class CornerResult {

        double overshoot, cut, devAfter;
        boolean lost;
        String verdict = "";
        double[] px = new double[0], py = new double[0];                   // path of the sensor array centre
    }

    static final double CORNER_X = 900, CORNER_Y = 300;

    /** A straight run into a sharp 90 degree right-hand corner. */
    static CornerResult corner(Model snap) {
        double v = Math.max(snap.baseSpeed, 20);
        TrackShape pl = new TrackShape(TrackShape.Type.POLYLINE);
        pl.width = snap.lineWidth;
        pl.miter = true;
        pl.butt = true;
        pl.pts.add(new java.awt.geom.Point2D.Double(100, CORNER_Y));
        pl.pts.add(new java.awt.geom.Point2D.Double(CORNER_X, CORNER_Y));
        pl.pts.add(new java.awt.geom.Point2D.Double(CORNER_X, 950));
        List<TrackShape> sh = new ArrayList<>();
        sh.add(pl);
        Model m = onTrack(snap, 1400, 1000, sh, 140, CORNER_Y);
        double tMax = (800 + 650) / v + 2.5, dt = 0.005;
        List<double[]> pts = new ArrayList<>();
        for (double t = 0; t < tMax; t += dt) {
            m.advance(dt);
            double xa = m.x + Math.cos(m.th) * m.forward, ya = m.y + Math.sin(m.th) * m.forward;
            pts.add(new double[]{xa, ya});
            if (m.outOfArena || ya > CORNER_Y + 330) {
                break;
            }
        }
        CornerResult r = new CornerResult();
        r.px = new double[pts.size()];
        r.py = new double[pts.size()];
        boolean cutSet = false;
        for (int i = 0; i < pts.size(); i++) {
            double xa = pts.get(i)[0], ya = pts.get(i)[1];
            r.px[i] = xa;
            r.py[i] = ya;
            if (ya < CORNER_Y + 60) {
                r.overshoot = Math.max(r.overshoot, xa - CORNER_X);
            }
            if (!cutSet && ya >= CORNER_Y + 40) {
                r.cut = Math.max(0, CORNER_X - xa);
                cutSet = true;
            }
            if (ya >= CORNER_Y + 150 && ya <= CORNER_Y + 320) {
                r.devAfter = Math.max(r.devAfter, Math.abs(xa - CORNER_X));
            }
        }
        r.lost = m.outOfArena || !cutSet || m.lineLostTime > 0.25;
        double tol = 0.5 * snap.lineWidth;
        if (r.lost) {
            r.verdict = "Lost the line at the corner";
        } else if (r.overshoot > tol && r.overshoot >= r.cut) {
            r.verdict = "Overshoots the corner: the sensors are too close to the axle, or the robot reacts too late";
        } else if (r.cut > tol) {
            r.verdict = "Turns early and cuts the corner: the sensors are too far ahead of the axle";
        } else {
            r.verdict = "Takes the corner cleanly";
        }
        return r;
    }

    /** One number for how bad a corner was: millimetres of overshoot or cut, 999 if the line was lost. */
    static double cornerScore(CornerResult r) {
        return r.lost ? 999 : Math.max(r.overshoot, r.cut);
    }

    // ------------------------------------------------------------------ look-ahead x speed
    static final class SweepResult {

        double[] speeds, aheads;
        double[][] score;                                                  // corner overshoot / cut in mm (999 = lost)
        boolean[][] stable;                                                // straight-line step response is not nervous / lost
        int[] best;                                                        // best stable look-ahead index per speed, -1 = none
        double[] rule;                                                     // the 10 cm per m/s rule, mm
    }

    /**
     * Run the corner test and the straight-line step response for every speed x look-ahead (axle to
     * sensors) combination. The best look-ahead is the one with the smallest corner error among the
     * combinations whose straight-line behaviour is stable; ties go to the one nearest the 10 cm per m/s rule.
     */
    static SweepResult sweep(Model snap, double[] speeds, double[] aheads) {
        SweepResult s = new SweepResult();
        s.speeds = speeds;
        s.aheads = aheads;
        s.score = new double[speeds.length][aheads.length];
        s.stable = new boolean[speeds.length][aheads.length];
        s.best = new int[speeds.length];
        s.rule = new double[speeds.length];
        for (int i = 0; i < speeds.length; i++) {
            s.rule[i] = speeds[i] * 0.1;
            int b = -1;
            for (int j = 0; j < aheads.length; j++) {
                Model c = snapshot(snap);
                c.baseSpeed = speeds[i];
                c.forward = aheads[j];
                s.score[i][j] = cornerScore(corner(snapshot(c)));
                StepResult st = stepResponse(snapshot(c));
                s.stable[i][j] = !st.lost && !st.label.startsWith("Nervous");
                if (!s.stable[i][j]) {
                    continue;
                }
                if (b < 0) {
                    b = j;
                    continue;
                }
                double d = Math.abs(aheads[j] - s.rule[i]), db = Math.abs(aheads[b] - s.rule[i]);
                if (s.score[i][j] < s.score[i][b] - 1.0 || (Math.abs(s.score[i][j] - s.score[i][b]) <= 1.0 && d < db)) {
                    b = j;
                }
            }
            s.best[i] = b;
        }
        return s;
    }
}
