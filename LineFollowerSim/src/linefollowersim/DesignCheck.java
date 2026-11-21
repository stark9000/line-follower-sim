package linefollowersim;

import java.util.ArrayList;
import java.util.List;

/**
 * Checks a sensor / robot design against the usual rules for line followers: spacing against line
 * width, array width, sensors on the line, blind zones, contrast, the latency budget and the
 * required look-ahead (axle to sensors). Works on a private copy of the model.
 */
final class DesignCheck {

    static final int OK = 0, WARN = 1, BAD = 2;

    static final class Item {

        final int level;
        final String title, text;

        Item(int level, String title, String text) {
            this.level = level;
            this.title = title;
            this.text = text;
        }
    }

    static final class Report {

        final List<Item> items = new ArrayList<>();
        Model.Latency latency;
        boolean physical;
        double speed, lineWidth, minLook, recLook, ruleLook, centredUncal, centredCal;

        int worst() {
            int w = OK;
            for (Item i : items) {
                w = Math.max(w, i.level);
            }
            return w;
        }
    }

    private DesignCheck() {
    }

    private static String f(double v) {
        return String.format("%.1f", v);
    }

    /** Position the active readout reports for a line at 'off' mm; NaN = line not seen. */
    private static double positionAt(Model s, double off) {
        double[] raw = s.rawOnLine(off);
        double mid = (s.nSensors - 1) / 2.0, sum = 0, sumW = 0;
        for (int i = 0; i < s.nSensors; i++) {
            double d = s.darkOf(0, i, raw[i]);
            sum += d;
            sumW += d * (i - mid) * s.spacing;
        }
        if (!(sum > (s.analogMode ? 0.2 : 0.5))) {
            return Double.NaN;
        }
        return s.errorMode == 1 ? sumW : sumW / sum;
    }

    static Report run(Model m) {
        Report r = new Report();
        Model s = Experiments.snapshot(m);
        s.x = m.x;
        s.y = m.y;
        s.th = m.th;
        double L = Math.max(m.lineWidth, 1), p = m.spacing;
        int n = m.nSensors;
        double aw = (n - 1) * p, half = aw / 2, spot = 2 * m.spotRadius;
        r.lineWidth = L;
        r.speed = m.baseSpeed;

        // 1. spacing against line width
        double ratio = p / L;
        if (ratio > 1.0) {
            r.items.add(new Item(BAD, "Sensor pitch", f(p) + " mm is wider than the " + f(L) + " mm line: the line can fall "
                    + "between two sensors. Keep the pitch at or below the line width (ideal: about half of it)."));
        } else if (ratio < 0.25) {
            r.items.add(new Item(WARN, "Sensor pitch", f(p) + " mm is only " + f(ratio) + "x the line width: more sensors than "
                    + "needed, and the sensors are very close together (check their physical size)."));
        } else {
            r.items.add(new Item(OK, "Sensor pitch", f(p) + " mm = " + f(ratio) + "x the line width (ideal about 0.5x)."));
        }

        // 1b. do the sensor parts fit at this pitch? (Hardware > Sensor part)
        if (m.sensBodyW > 0 && m.nSensors > 1 && !m.customLayout && m.sensBodyW > m.spacing + 0.05) {
            r.items.add(new Item(BAD, "Sensor parts", "The parts are " + f(m.sensBodyW) + " mm wide but the pitch is " + f(m.spacing)
                    + " mm: they do not fit side by side. Use a smaller part, a wider pitch, or stagger them (Robot editor)."));
        }

        // 2. array width
        if (aw >= 2 * L + 25) {
            r.items.add(new Item(OK, "Array width", f(aw) + " mm covers the line with room to spare (needs at least 2x line width + margin = "
                    + f(2 * L + 25) + " mm)."));
        } else if (aw >= 2 * L) {
            r.items.add(new Item(WARN, "Array width", f(aw) + " mm just covers 2x the line width but leaves little margin for "
                    + "sharp turns (aim for " + f(2 * L + 25) + " mm)."));
        } else {
            r.items.add(new Item(BAD, "Array width", f(aw) + " mm is less than 2x the line width (" + f(2 * L)
                    + " mm): the line leaves the array in any sharp turn."));
        }

        // 3. how many sensors see the line while it drifts across one pitch
        int minCnt = 99, maxCnt = 0;
        for (double off = 0; off < p - 1e-9; off += 0.25) {
            double[] raw = s.rawOnLine(off);
            int c = 0;
            for (int i = 0; i < n; i++) {
                if (raw[i] < s.thresholdFor(0, i)) {
                    c++;
                }
            }
            minCnt = Math.min(minCnt, c);
            maxCnt = Math.max(maxCnt, c);
        }
        if (minCnt >= 2) {
            r.items.add(new Item(OK, "Sensors on the line", minCnt == maxCnt ? minCnt + " sensors see the line at any position."
                    : minCnt + " to " + maxCnt + " sensors see the line, so it is never seen by just one."));
        } else if (minCnt == 1) {
            r.items.add(new Item(WARN, "Sensors on the line", "Sometimes only one sensor sees the line (" + minCnt + " to " + maxCnt
                    + "): the position jumps in steps of one pitch."));
        } else {
            r.items.add(new Item(BAD, "Sensors on the line", "At some positions NO sensor sees the line: a blind spot."));
        }

        // 4. position accuracy and blind zones over the array
        double lim = Math.max(half - L / 2, p), worstErr = 0, blind = 0, run = 0, maxRun = 0;
        int samples = 0;
        for (double off = -lim; off <= lim + 1e-9; off += 0.5) {
            double pos = positionAt(s, off);
            samples++;
            if (Double.isNaN(pos)) {
                run += 0.5;
                maxRun = Math.max(maxRun, run);
                blind += 0.5;
            } else {
                run = 0;
                if (s.errorMode != 1) {
                    worstErr = Math.max(worstErr, Math.abs(pos - off));
                }
            }
        }
        if (maxRun > 0) {
            r.items.add(new Item(BAD, "Blind zone", "The line is lost over a stretch of " + f(maxRun) + " mm of sideways travel "
                    + "inside the array (" + f(100 * blind / (samples * 0.5)) + "% of the range)."));
        } else if (s.errorMode == 1) {
            r.items.add(new Item(WARN, "Position accuracy", "The raw weighted sum is not a distance: it scales with how many sensors "
                    + "see the line (a wide line or a crossing doubles it), so the gains need retuning."));
        } else {
            int lvl = worstErr <= 0.25 * L ? OK : (worstErr <= 0.5 * L ? WARN : BAD);
            r.items.add(new Item(lvl, "Position accuracy", "Worst error between the true line position and what the "
                    + (s.analogMode ? "analog" : "digital") + " readout reports: " + f(worstErr) + " mm."));
        }

        // 5. optics: spot size, crosstalk
        if (spot > L + 0.01) {
            r.items.add(new Item(WARN, "Sensor spot", "Spot diameter " + f(spot) + " mm is wider than the line: black and white "
                    + "blur together and the contrast drops. Lower the sensor or use a narrower beam."));
        } else if (spot > p + 0.01) {
            r.items.add(new Item(WARN, "Sensor spot", "Spot diameter " + f(spot) + " mm is larger than the pitch (" + f(p)
                    + " mm): neighbouring sensors look at overlapping areas."));
        } else {
            r.items.add(new Item(OK, "Sensor spot", "Spot diameter " + f(spot) + " mm fits inside the pitch and the line."));
        }
        if (m.crosstalkPct > 0 && !m.barriers) {
            r.items.add(new Item(WARN, "Crosstalk", "Neighbouring sensors light each other up (" + f(m.crosstalkPct)
                    + "% at 10 mm pitch) and there are no light barriers: the black level rises and contrast drops."));
        } else if (m.crosstalkPct > 0) {
            r.items.add(new Item(OK, "Crosstalk", "Crosstalk is cut to a tenth by the light barriers."));
        }

        // 6. contrast of the worst sensor, and how many sensors clip at white
        double minContrast = 1e9;
        int clipped = 0;
        for (int i = 0; i < n; i++) {
            double lo = 1e9, hi = -1e9;
            for (double off = -(half + L); off <= half + L + 1e-9; off += 1.0) {
                double v = s.rawOnLine(off)[i];
                lo = Math.min(lo, v);
                hi = Math.max(hi, v);
            }
            minContrast = Math.min(minContrast, (hi - lo) / 1023.0);
            if (hi >= 1022.5) {
                clipped++;
            }
        }
        String ct = "Worst-sensor contrast between line and floor: " + f(minContrast * 100) + "% of the ADC range.";
        if (minContrast < 0.12) {
            r.items.add(new Item(BAD, "Contrast", ct + " Too low to separate line from floor: raise the LED power, lower the sensor, "
                    + "or reduce ambient light."));
        } else if (minContrast < 0.25) {
            r.items.add(new Item(WARN, "Contrast", ct + " Low: a threshold will be fragile."));
        } else if (clipped * 2 >= n) {
            r.items.add(new Item(WARN, "Contrast", ct + " " + clipped + " of " + n + " sensors clip at white (1023): ambient light "
                    + "or LED power is too high."));
        } else {
            r.items.add(new Item(OK, "Contrast", ct + (clipped > 0 ? " (" + clipped + " sensor" + (clipped > 1 ? "s" : "") + " clip at white.)" : "")));
        }

        // 6b. how much of the white reading is ambient light
        double ambPart = 0.9 * m.ambientSeen(m.x + Math.cos(m.th) * m.forward, m.y + Math.sin(m.th) * m.forward) * 1023.0;
        double whiteRead = Math.max(1, s.rawOnLine(half + L + 50)[(n - 1) / 2]);
        double share = Math.min(1, ambPart / whiteRead);
        if (share > 0.35) {
            r.items.add(new Item(share > 0.6 ? BAD : WARN, "Ambient light", f(share * 100) + "% of the white reading is room light. "
                    + "When the light changes (window, lamp, shadow) the readings move with it. Shield the sensors "
                    + "(Hardware > Light shield), lower them, or raise the LED power."));
        } else if (share > 0.01) {
            r.items.add(new Item(OK, "Ambient light", f(share * 100) + "% of the white reading is room light."));
        }

        // 7. latency budget and look-ahead
        Model.Latency lat = m.latency();
        r.latency = lat;
        r.physical = m.driveModel == 1;
        double v = Math.max(m.baseSpeed, 1);
        r.minLook = v * lat.total / 1000.0;
        r.recLook = 2 * r.minLook;
        r.ruleLook = v * 0.1;
        double d = m.forward;
        String ls = "Total delay " + f(lat.total) + " ms: at " + f(v) + " mm/s the robot travels " + f(r.minLook) + " mm before reacting. "
                + "The sensors are " + f(d) + " mm ahead of the axle; aim for " + f(r.recLook) + " mm (2x) to " + f(3 * r.minLook) + " mm (3x). "
                + "The 10 cm per m/s rule says " + f(r.ruleLook) + " mm.";
        if (d < r.minLook) {
            r.items.add(new Item(BAD, "Look-ahead", "Too close. " + ls + " The robot will overshoot corners."));
        } else if (d < r.recLook) {
            r.items.add(new Item(WARN, "Look-ahead", "Marginal. " + ls));
        } else if (d > 4 * r.minLook && d > 1.5 * Math.max(r.ruleLook, 1)) {
            // longer than the rule of thumb: only a problem if the simulated response says so
            Experiments.StepResult st = Experiments.stepResponse(Experiments.snapshot(m));
            if (st.lost || st.label.startsWith("Nervous")) {
                r.items.add(new Item(WARN, "Look-ahead", "Far. " + ls + " A long arm raises the loop gain, and the straight-line "
                        + "test is " + st.label.toLowerCase() + " (overshoot " + f(st.overshootPct) + "%)."));
            } else {
                r.items.add(new Item(OK, "Look-ahead", "Longer than the rule of thumb, but the straight-line test is stable ("
                        + st.label.toLowerCase() + "). " + ls + " Analysis > Tests shows how it handles corners."));
            }
        } else {
            r.items.add(new Item(OK, "Look-ahead", ls));
        }

        // 7a. tipping over sideways in the tightest bend (centre of mass height: Robot editor parts, or an estimate)
        if (!Double.isNaN(m.trackRadiusHint) && m.trackRadiusHint > 0) {
            double vTip = Math.sqrt(m.tipAccel() * m.trackRadiusHint), vb = Math.max(m.baseSpeed, 1);
            String txt = String.format("Centre of mass %.0f mm high%s on a %.0f mm track: it tips over in the %.0f mm bend above "
                    + "about %.0f mm/s", m.comHeight(), m.useParts ? "" : " (estimate - Robot editor parts give the real height)",
                    m.wheelBase, m.trackRadiusHint, vTip);
            r.items.add(vTip < vb * 1.3 ? new Item(vTip < vb ? BAD : WARN, "Tipping over", txt + ", close to the base speed "
                    + f(vb) + " mm/s. Lower the battery or widen the track.") : new Item(OK, "Tipping over", txt + "."));
        }

        // 7b. car steering: can it turn tight enough?
        if (m.steerMode != 0) {
            double rt = m.minTurnRadius(), rb = m.trackRadiusHint;
            String txt = String.format("At full lock (%.0f deg) with %.0f mm between the axles it turns on a %.0f mm radius at the "
                    + "tightest", m.maxSteerDeg, m.axleSpacing, rt);
            if (!Double.isNaN(rb) && rt > rb) {
                r.items.add(new Item(BAD, "Turning circle", txt + ", but the track bends on " + f(rb) + " mm. Shorten the axle "
                        + "spacing or allow more steering lock - otherwise it will run wide at that bend."));
            } else {
                r.items.add(new Item(OK, "Turning circle", txt + (Double.isNaN(rb) ? "." : ", tighter than the track's " + f(rb) + " mm.")));
            }
        }

        // 8. loop rate
        double hz = 1.0 / m.loopPeriodSec();
        if (m.loopMode == 1 && m.computeSec() > 1.0 / Math.max(m.fixedHz, 0.1)) {
            r.items.add(new Item(BAD, "Loop rate", "The loop needs " + f(m.computeSec() * 1000) + " ms but the fixed period is "
                    + f(1000.0 / m.fixedHz) + " ms: it overruns and runs slower than set."));
        } else if (hz < 50) {
            r.items.add(new Item(WARN, "Loop rate", "Loop runs at " + f(hz) + " Hz; 50 Hz or more is the usual minimum."));
        } else {
            r.items.add(new Item(OK, "Loop rate", "Loop runs at " + f(hz) + " Hz" + (m.jitterUs > 0 ? " (jitter ±" + f(m.jitterUs) + " us)." : ".")));
        }

        // 9. sensor mismatch: does a centred straight line look centred?
        s.calibrate();
        r.centredUncal = Math.abs(s.measuredPosition(0, false));
        s.calibrateSweep();
        r.centredCal = Math.abs(s.measuredPosition(0, true));
        if (m.mismatch > 0 && !m.perSensorCal && r.centredUncal >= Math.max(1.0, 0.1 * L)) {
            r.items.add(new Item(WARN, "Calibration", "With one white/black pair for all sensors a centred straight line looks "
                    + f(r.centredUncal) + " mm off-centre (" + f(r.centredCal) + " mm after a per-sensor sweep). Calibrate each sensor."));
        } else if (m.perSensorCal) {
            r.items.add(new Item(OK, "Calibration", "Per-sensor calibration: a centred line reads " + f(r.centredCal) + " mm off-centre."));
        } else {
            r.items.add(new Item(OK, "Calibration", "A centred line reads " + f(r.centredUncal) + " mm off-centre."));
        }
        return r;
    }

    /** True vs measured line position, before and after a per-sensor calibration (the 'straight line looks curved' demo). */
    static double[][] curves(Model m) {
        Model s = Experiments.snapshot(m);
        s.x = m.x;
        s.y = m.y;
        s.th = m.th;
        s.analogMode = true;
        s.errorMode = 0;
        s.calibrate();
        s.calibrateSweep();
        double half = (s.nSensors - 1) / 2.0 * s.spacing, lim = Math.max(half - Math.max(m.lineWidth, 1) / 2 - 1, s.spacing);
        int n = (int) (2 * lim / 1.0) + 1;
        double[][] c = new double[3][n];
        for (int i = 0; i < n; i++) {
            double off = -lim + i * 1.0;
            c[0][i] = off;
            c[1][i] = s.measuredPosition(off, false);
            c[2][i] = s.measuredPosition(off, true);
        }
        return c;
    }

    static String html(Report r) {
        StringBuilder sb = new StringBuilder("<html><body style='width:232px'>");
        String[] tag = {"<b style='color:" + Theme.okHex() + "'>OK</b>", "<b style='color:" + Theme.warnHex() + "'>CHECK</b>",
            "<b style='color:" + Theme.badHex() + "'>FIX</b>"};
        for (Item i : r.items) {
            sb.append(tag[i.level]).append(" <b>").append(i.title).append("</b><br><small>").append(i.text).append("</small><br><br>");
        }
        Model.Latency l = r.latency;
        sb.append("<b>Delay from sensor to wheels</b><small><table cellspacing=0 cellpadding=1>")
                .append(row("Sensor reads (ADC)", l.adc)).append(row("PID maths", l.math))
                .append(row("Command held (half loop)", l.hold)).append(row("Sensor filter", l.filter))
                .append(row("Sensor task age", l.taskAge)).append(row("Motor lag", l.motor))
                .append("<tr><td><b>Total</b></td><td align=right><b>").append(f(l.total)).append(" ms</b></td></tr>");
        if (l.dFilter > 0) {
            sb.append(row("D filter (D part only)", l.dFilter))
                    .append("<tr><td><b>Total for the D part</b></td><td align=right><b>").append(f(l.dTotal)).append(" ms</b></td></tr>");
        }
        sb.append("</table>The P part reacts after the total; the D part (and prediction) after its own total. "
                + (r.physical ? "The physical drive model adds the robot's inertia on top." : "Mechanical inertia is not "
                + "modelled in the simple drive model (Motors tab: Physical) and adds to this.") + "</small>");
        return sb.append("</body></html>").toString();
    }

    private static String row(String name, double ms) {
        return "<tr><td>" + name + "&nbsp;&nbsp;</td><td align=right>" + f(ms) + " ms</td></tr>";
    }

    // ------------------------------------------------------------------ suggestion
    static final class Suggestion {

        double pitch, axle, height, ruleAxle;
        int count;
        String text;
        boolean simulated;
    }

    /**
     * A design for the current line width, speed and delays. Pitch, count and height follow the usual
     * rules. The axle-to-sensor distance is either the rule of thumb (2.5x the distance travelled during
     * the delay) or, with simulate = true, found by running the 90 degree corner test and the
     * straight-line step response for a range of distances.
     */
    static Suggestion suggest(Model m, boolean simulate) {
        Suggestion g = new Suggestion();
        double L = Math.max(m.lineWidth, 1);
        g.pitch = Math.max(Math.round(L * 0.5 * 2) / 2.0, 3.0);
        double width = 2 * L + 25;
        g.count = (int) Math.max(3, Math.min(16, Math.ceil(width / g.pitch) + 1));
        double lat = m.latency().total / 1000.0, v = Math.max(m.baseSpeed, 1);
        g.ruleAxle = Math.max(10, Math.round(2.5 * v * lat / 5.0) * 5.0);
        g.axle = g.ruleAxle;
        double spot = 2 * m.spotRadius;
        g.height = m.sensorHeight;
        if (spot > 0.9 * g.pitch) {
            g.height = Math.max(1, Math.round(0.9 * g.pitch / (2 * Math.tan(Math.toRadians(m.beamAngle))) * 2) / 2.0);
        }
        StringBuilder axleNote = new StringBuilder();
        if (simulate) {
            g.simulated = true;
            double[] cand = {g.ruleAxle, 20, 30, 45, 60, 80, 100, 130, 150};
            java.util.TreeSet<Double> set = new java.util.TreeSet<>();
            for (double c : cand) {
                set.add(c);
            }
            double best = -1, bestScore = 1e9, firstGood = -1, ruleScore = 0;
            axleNote.append("\n  simulated corner overshoot (stable ones only):");
            for (double c : set) {
                Model t = Experiments.snapshot(m);
                t.forward = c;
                double score = Experiments.cornerScore(Experiments.corner(Experiments.snapshot(t)));
                Experiments.StepResult st = Experiments.stepResponse(Experiments.snapshot(t));
                boolean stable = !st.lost && !st.label.startsWith("Nervous");
                if (c == g.ruleAxle) {
                    ruleScore = score;
                }
                axleNote.append(String.format("\n     %3.0f mm: %s%s", c, score >= 999 ? "lost the line" : f(score) + " mm", stable ? "" : "  (unstable on a straight)"));
                if (stable && score < bestScore - 1.0) {
                    bestScore = score;
                    best = c;
                }
                if (stable && firstGood < 0 && score <= L) {
                    firstGood = c;
                }
            }
            g.axle = firstGood > 0 ? firstGood : (best > 0 ? best : g.ruleAxle);
            axleNote.append(firstGood > 0 ? "\n  chosen: the shortest distance whose corner overshoot is within one line width"
                    : "\n  chosen: the stable distance with the smallest overshoot (none reached one line width)");
            axleNote.append("\n  the rule of thumb alone (" + f(g.ruleAxle) + " mm) would overshoot by " + (ruleScore >= 999 ? "losing the line" : f(ruleScore) + " mm"));
        }
        g.text = "Line " + f(L) + " mm at " + f(v) + " mm/s, delay " + f(lat * 1000) + " ms:\n"
                + "  sensor pitch " + f(m.spacing) + " -> " + f(g.pitch) + " mm (half the line width)\n"
                + "  sensors " + m.nSensors + " -> " + g.count + " (array width " + f((g.count - 1) * g.pitch) + " mm, needs " + f(width) + ")\n"
                + "  axle to sensors " + f(m.forward) + " -> " + f(g.axle) + " mm" + (simulate ? "" : " (2.5x the distance travelled in the delay)") + "\n"
                + "  sensor height " + f(m.sensorHeight) + " -> " + f(g.height) + " mm"
                + (g.pitch < 6 ? "\n  note: " + f(g.pitch) + " mm pitch is smaller than most sensor packages" : "")
                + axleNote;
        return g;
    }
}
