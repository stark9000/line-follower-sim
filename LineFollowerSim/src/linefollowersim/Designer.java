package linefollowersim;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * Designs a robot for the current track: the usual rules give the sensor array and a speed target,
 * then candidate robots (motor / wheel / battery / wheel layout / array shape) are tuned by running
 * them on the real track (axle distance, speed, Kp, Kd, corner slowdown). Every candidate is judged on
 * several slightly different runs (sensor noise and mismatch, start offset, motor mismatch) so the
 * winner is robust, not lucky. No Swing: the wizard runs it on a background thread.
 */
final class Designer {

    /** What the user lets the designer change, and how it should judge. */
    static final class Options {

        double reliability = 0.5;          // 0 = fastest lap, 1 = never lose the line
        boolean lockMotor, lockWheels, lockBattery, lockLayout, lockArray, lockSize, lockPid;
        boolean pidOnly;                   // Tune PID: keep the whole robot, tune only the PID tab (from the current values too)
        double maxWidth = 0, maxLength = 0;// mm, 0 = no limit
        double grip = 0.7;                 // tyre grip to plan with (used when the World tab grip is unlimited)
        int effort = 1;                    // 0 quick, 1 normal, 2 thorough
    }

    /** One designed robot and how it did. */
    static final class Candidate implements Comparable<Candidate> {

        Model robot;
        double score, avgSpeed, lostShare, avgErr, worstScore;
        boolean crashed;
        String hardware = "";
        final List<String> reasons = new ArrayList<>();

        @Override
        public int compareTo(Candidate o) {
            return Double.compare(o.score, score);
        }
    }

    private final Model base;
    private final TrackAnalysis track;
    private final Options opt;
    private final AtomicBoolean cancel = new AtomicBoolean();
    private final List<Candidate> results = new ArrayList<>();
    private int evals, evalsPlanned;

    Designer(Model base, TrackAnalysis track, Options opt) {
        this.base = base;
        this.track = track;
        this.opt = opt;
    }

    void cancel() {
        cancel.set(true);
    }

    boolean cancelled() {
        return cancel.get();
    }

    // ------------------------------------------------------------------ rules
    private double grip() {
        return base.tractionMu > 0 ? base.tractionMu : opt.grip;
    }

    /** Fastest the robot can take the tightest bend without sliding, mm/s. */
    double cornerSpeedLimit() {
        return Math.sqrt(grip() * 9810.0 * Math.max(track.designRadius(), 10));
    }

    /** Sensor array from the line width and the bends: pitch, count, height. Returns reasons. */
    private void ruleArray(Model r, List<String> why) {
        double L = Math.max(track.lineWidth, 3);
        double pitch = Math.max(4, Math.min(15, Math.round(L * 0.5 * 2) / 2.0));
        double need = 2 * L + 25;                                          // covers the line with room for bends
        if (track.sharpCorners > 0 || track.designRadius() < 4 * L) {
            need = Math.max(need, 4 * L + 30);                             // sharp corners: see the new branch early
        }
        if (opt.maxWidth > 0) {
            need = Math.min(need, opt.maxWidth - 10);
        }
        int count = (int) Math.max(3, Math.min(16, Math.ceil(need / pitch) + 1));
        r.spacing = pitch;
        r.nSensors = count;
        double h = 0.9 * pitch / (2 * Math.tan(Math.toRadians(r.beamAngle)));
        r.sensorHeight = Math.max(3, Math.min(15, Math.round(h * 2) / 2.0));
        r.applyHardware();
        why.add(f("Sensor pitch %.1f mm: about half the %.0f mm line, so two sensors see it.", pitch, L));
        why.add(f("%d sensors = %.0f mm wide array%s.", count, (count - 1) * pitch,
                track.sharpCorners > 0 ? ": sharp corners need a wide view" : ": covers the line and the bends"));
        why.add(f("Sensor height %.1f mm: the spot (%.1f mm) stays inside one pitch.", r.sensorHeight, 2 * r.spotRadius));
    }

    /** Hardware combinations (motor, wheel, battery) whose top speed suits the target. */
    private List<double[]> hardwareChoices(double target) {
        List<double[]> out = new ArrayList<>();
        HardwarePresets.P[] motors = HardwarePresets.MOTORS, wheels = HardwarePresets.WHEELS, batts = HardwarePresets.BATTERIES;
        for (int mi = 0; mi < (opt.lockMotor ? 1 : motors.length); mi++) {
            for (int wi = 0; wi < (opt.lockWheels ? 1 : wheels.length); wi++) {
                for (int bi = 0; bi < (opt.lockBattery ? 1 : batts.length); bi++) {
                    double rpm = opt.lockMotor ? base.motorRpm : motors[mi].v[0];
                    double rated = opt.lockMotor ? base.motorVolt : motors[mi].v[1];
                    double dia = opt.lockWheels ? base.wheelDia : wheels[wi].v[0];
                    double batt = opt.lockBattery ? base.battVolt : batts[bi].v[0];
                    if (batt > rated * 1.35 || batt < rated * 0.6) {
                        continue;                                          // would burn the motor or barely turn it
                    }
                    double top = Math.PI * dia * rpm / 60 * batt / rated * 0.75;
                    double fit = Math.abs(Math.log(top / (1.7 * target)));    // aim for top speed ~1.7x the target
                    out.add(new double[]{fit, mi, wi, bi, top});
                }
            }
        }
        if (out.isEmpty()) {                                               // nothing passed the checks: keep the current parts
            out.add(new double[]{0, -1, -1, -1, base.maxSpeed});
        }
        out.sort((x, y) -> Double.compare(x[0], y[0]));
        int keep = new int[]{3, 5, 8}[opt.effort];
        return out.subList(0, Math.min(keep, out.size()));
    }

    private void applyHardware(Model r, double[] hw) {
        if (!opt.lockMotor && hw[1] >= 0) {
            HardwarePresets.P mp = HardwarePresets.MOTORS[(int) hw[1]];
            r.motorRpm = mp.v[0];
            r.motorVolt = mp.v[1];
            r.lagL = r.lagR = mp.v[2];
            r.stallTorque = mp.v[3];
        }
        if (!opt.lockWheels && hw[2] >= 0) {
            r.wheelDia = HardwarePresets.WHEELS[(int) hw[2]].v[0];
        }
        if (!opt.lockBattery && hw[3] >= 0) {
            r.battVolt = HardwarePresets.BATTERIES[(int) hw[3]].v[0];
        }
        r.topSpeedOverride = 0;
        r.loadPct = 75;
        r.applyHardware();
    }

    // ------------------------------------------------------------------ simulation
    private static String f(String fmt, Object... a) {
        return String.format(Locale.ROOT, fmt, a);
    }

    /** Run one robot on the track: {score, average speed on the line, share of time lost, avg |error|, crashed}. */
    private double[] runOnce(Model design, int variant) {
        Model m = new Model();
        m.copySettingsFrom(design);
        m.shareTrackFrom(base);
        java.util.Random rv = new java.util.Random(1000 + variant);
        if (variant == 1) {                                                // other sensors: new mismatch, more noise
            m.randomizeMismatch(4242 + variant);
            m.noise = Math.max(m.noise, 0.02);
        } else if (variant == 2) {                                         // placed a little off
            m.placeRobot(m.sx - Math.sin(m.sth) * 4, m.sy + Math.cos(m.sth) * 4, m.sth + 0.06);
        } else if (variant == 3) {                                         // mismatched motors
            m.gainL = m.gainL * (0.95 + 0.02 * rv.nextDouble());
            m.gainR = m.gainR * (1.05 - 0.02 * rv.nextDouble());
        } else if (variant == 4) {                                         // a tired battery (calibrated when it was full)
            m.calibrate();
            m.battDrainPctPerMin = 0;
            m.battSagPct = Math.max(m.battSagPct, 8);
        }
        if (variant != 4) {
            m.calibrate();
        }
        if (m.perSensorCal) {
            m.calibrateSweep();
        }
        double v = Math.max(m.baseSpeed, 40);
        double horizon = Model.clamp(track.length / v * (track.closed ? 1.0 : 0.9), 3, 18);
        double onLine = 0, lastD = 0, t = 0;
        boolean crashed = false;
        while (t < horizon) {
            m.advance(0.02);
            t += 0.02;
            if (m.lineVisible) {
                onLine += m.distance - lastD;
            }
            lastD = m.distance;
            if (m.outOfArena) {
                crashed = track.closed || m.distance < 0.8 * track.length;     // running off the end of an open track is fine
                break;
            }
        }
        double time = Math.max(m.t, 0.1);
        double speed = onLine / time, lost = m.lineLostTime / time, err = m.avgAbsError() / Math.max(track.lineWidth, 3);
        double rel = opt.reliability;
        double penalty = lost * (2 + 8 * rel) + err * (0.1 + 0.6 * rel) + (crashed ? 0.6 + 0.4 * rel : 0);
        double score = speed * Math.max(0.02, 1 - penalty);
        return new double[]{score, speed, lost, m.avgAbsError(), crashed ? 1 : 0};
    }

    private int variants() {
        return new int[]{3, 4, 5}[opt.effort];
    }

    /** Judge a design on several runs: blend of the average and the worst (more worst for reliability). */
    private Candidate judge(Model design) {
        int n = variants();
        double sum = 0, worst = Double.MAX_VALUE, sp = 0, lost = 0, err = 0;
        boolean crash = false;
        for (int v = 0; v < n && !cancel.get(); v++) {
            double[] r = runOnce(design, v);
            sum += r[0];
            worst = Math.min(worst, r[0]);
            sp += r[1];
            lost += r[2];
            err += r[3];
            crash |= r[4] > 0;
        }
        synchronized (this) {
            evals++;
        }
        Candidate c = new Candidate();
        c.robot = design;
        double rel = opt.reliability;
        c.worstScore = worst;
        c.score = (1 - 0.7 * rel) * sum / n + 0.7 * rel * worst;
        c.avgSpeed = sp / n;
        c.lostShare = lost / n;
        c.avgErr = err / n;
        c.crashed = crash;
        return c;
    }

    // ------------------------------------------------------------------ search
    private static final String[] TUNE = {"baseSpeed", "forward", "kp", "kd", "cornerSlow", "wheelBase", "ki", "dFilterMs"};

    private static double get(Model m, String k) {
        switch (k) {
            case "baseSpeed":
                return m.baseSpeed;
            case "forward":
                return m.forward;
            case "kp":
                return m.kp;
            case "kd":
                return m.kd;
            case "wheelBase":
                return m.wheelBase;
            case "ki":
                return m.ki;
            case "dFilterMs":
                return m.dFilterMs;
            default:
                return m.cornerSlow;
        }
    }

    private static void set(Model m, String k, double v) {
        switch (k) {
            case "baseSpeed":
                m.baseSpeed = Model.clamp(v, 40, Math.max(60, m.maxSpeed * 0.92));
                break;
            case "forward":
                m.forward = Model.clamp(v, 15, 300);
                break;
            case "kp":
                m.kp = Model.clamp(v, 0.3, 40);
                break;
            case "kd":
                m.kd = Model.clamp(v, 0, 5);
                break;
            case "wheelBase":
                m.wheelBase = Model.clamp(v, 50, 250);
                break;
            case "ki":
                m.ki = Model.clamp(v, 0, 5);
                break;
            case "dFilterMs":
                m.dFilterMs = Model.clamp(v, 1, 60);
                break;
            default:
                m.cornerSlow = Model.clamp(v, 0, 0.9);
        }
    }

    /** Coordinate search: try each setting a step up and down, keep improvements, then halve the steps. */
    private Candidate tune(Model start, int budget) {
        Candidate best = judge(start);
        if (opt.lockPid && opt.lockSize) {
            return best;
        }
        double[] step = {0.18, 0.22, 0.3, 0.5, 0.25, 0.15, 0.5, 0.4};     // relative steps (corner slowdown: absolute)
        int used = 1;
        for (int round = 0; round < 4 && used < budget && !cancel.get(); round++) {
            boolean improved = false;
            for (int i = 0; i < TUNE.length && used < budget && !cancel.get(); i++) {
                String k = TUNE[i];
                boolean size = k.equals("forward") || k.equals("wheelBase");
                if (opt.lockPid && !size || opt.lockSize && size) {
                    continue;
                }
                for (int dir : new int[]{1, -1}) {
                    Model t = Experiments.snapshot(best.robot);
                    double v = get(t, k);
                    double nv = k.equals("cornerSlow") ? v + dir * step[i]
                            : k.equals("kd") && v < 0.05 ? (dir > 0 ? 0.2 : 0)
                            : k.equals("ki") && v < 0.05 ? (dir > 0 ? Math.max(0.1, t.kp * 0.2) : 0)
                            : v * (1 + dir * step[i]);
                    set(t, k, nv);
                    if (opt.maxLength > 0 && t.forward + t.arrayReach() + 15 > opt.maxLength
                            || opt.maxWidth > 0 && t.wheelBase + t.tireWidth > opt.maxWidth) {
                        continue;
                    }
                    Candidate c = judge(t);
                    used++;
                    if (c.score > best.score * 1.005) {
                        best = c;
                        improved = true;
                        break;
                    }
                }
            }
            if (!improved) {
                for (int i = 0; i < step.length; i++) {
                    step[i] *= 0.5;
                }
            }
        }
        return best;
    }

    /** Run the whole design. progress gets (done 0-1, message, best so far). Returns the candidates, best first. */
    List<Candidate> run(Consumer<Object[]> progress) {
        Model seed = Experiments.snapshot(base);
        List<String> why = new ArrayList<>();
        why.add("Track: " + track.summary().replace('\n', ' '));
        double vLimit = cornerSpeedLimit();
        double target = vLimit * (1.15 - 0.55 * opt.reliability);
        why.add(f("Grip %.2f and the tightest bend (%.0f mm) allow about %.0f mm/s there; aiming for %.0f mm/s base speed "
                + "(%s).", grip(), track.designRadius(), vLimit, target,
                opt.reliability < 0.34 ? "mostly speed" : opt.reliability > 0.66 ? "mostly reliability" : "balanced"));
        if (opt.pidOnly) {
            opt.lockMotor = opt.lockWheels = opt.lockBattery = opt.lockLayout = opt.lockArray = opt.lockSize = true;
            opt.lockPid = false;
        }
        if (!opt.lockArray) {
            ruleArray(seed, why);
        }
        double amb = 0.9 * base.ambientSeen(base.x, base.y) * 1023 / Math.max(1, seed.rawOnLine(1000)[seed.nSensors / 2]);
        if (amb > 0.3 && seed.ambientShield < 0.7 && !opt.pidOnly) {
            seed.ambientShield = 0.8;
            why.add(f("Room light is %.0f%% of the white reading: add a light shield (80%%).", amb * 100));
        }
        // the robots to try
        List<double[]> hw = hardwareChoices(target);
        List<int[]> layouts = new ArrayList<>();                           // {wheel count, steering}
        layouts.add(new int[]{seed.wheelCount, seed.steerMode});
        if (!opt.lockLayout && opt.effort > 0) {
            for (int[] l : new int[][]{{2, 0}, {4, 0}, {4, 1}, {4, 2}}) {
                boolean car = l[1] != 0;
                if ((l[0] != seed.wheelCount || l[1] != seed.steerMode) && (!car || opt.effort == 2)) {
                    layouts.add(l);                                        // cars only on "thorough": they rarely win
                }
            }
        }
        List<double[]> arrays = new ArrayList<>();                         // {shape, angle}
        arrays.add(new double[]{seed.arrayShape, seed.arrayAngle});
        if (!opt.lockArray && (track.sharpCorners > 0 || track.designRadius() < 150)) {
            arrays.add(new double[]{1, 25});                               // a V reaches into the corner earlier
        }
        List<Model> starts = new ArrayList<>();
        List<String> labels = new ArrayList<>();
        if (opt.pidOnly) {                                                 // the current settings are one of the starts
            starts.add(Experiments.snapshot(seed));
            labels.add("your settings as the start");
        }
        for (double[] h : hw) {
            for (int[] lay : layouts) {
                for (double[] ar : arrays) {
                    Model r = Experiments.snapshot(seed);
                    applyHardware(r, h);
                    r.wheelCount = lay[0];
                    r.steerMode = lay[1];
                    if (lay[0] == 4 && !opt.lockSize) {
                        r.axleSpacing = Math.max(60, Math.min(r.wheelBase, r.wheelDia + 25));
                        if (lay[1] != 0) {                                 // a car must turn tighter than the tightest bend
                            r.maxSteerDeg = Math.max(r.maxSteerDeg, 40);
                            double fit = 0.8 * track.designRadius() * Math.tan(Math.toRadians(r.maxSteerDeg));
                            r.axleSpacing = Math.max(r.wheelDia + 10, Math.min(r.axleSpacing, fit));
                        }
                    }
                    r.arrayShape = (int) ar[0];
                    r.arrayAngle = ar[1];
                    if (!opt.lockPid) {                                    // a sensible starting point for the tuning
                        r.baseSpeed = Model.clamp(Math.min(target, r.maxSpeed * 0.7), 60, 2000);
                        r.kp = Model.clamp(r.baseSpeed / 40.0, 1.5, 15) * (lay[0] == 4 && lay[1] == 0 ? 1.5 : 1);
                        r.kd = r.kp * 0.1;
                        r.ki = 0;
                        r.cornerSlow = 0.5;
                    }
                    if (!opt.lockSize) {                                   // look-ahead: 2.5x the distance travelled in the delay
                        double look = r.baseSpeed * r.latency().total / 1000.0 * 2.5;
                        r.forward = Model.clamp(Math.round(Math.max(look, 40) / 5) * 5, 30, 200);
                    }
                    starts.add(r);
                    labels.add(opt.pidOnly ? "a fresh start" : hardwareText(r, h));
                }
            }
        }
        int budget = new int[]{16, 40, 80}[opt.effort];
        evalsPlanned = starts.size() * budget;
        progress.accept(new Object[]{0.0, "Trying " + starts.size() + " robots ...", null});
        int threads = Math.max(1, Math.min(starts.size(), Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads, r -> {
            Thread th = new Thread(r, "designer");
            th.setDaemon(true);
            return th;
        });
        List<Future<Candidate>> jobs = new ArrayList<>();
        for (Model s : starts) {
            jobs.add(pool.submit(() -> tune(s, budget)));
        }
        pool.shutdown();
        for (int i = 0; i < jobs.size(); i++) {
            try {
                while (!jobs.get(i).isDone()) {
                    Thread.sleep(150);
                    Candidate bestSoFar = results.isEmpty() ? null : Collections.min(results);
                    progress.accept(new Object[]{Math.min(0.99, evals / (double) Math.max(1, evalsPlanned)),
                        "Simulated " + evals + " designs ...", bestSoFar});
                }
                Candidate c = jobs.get(i).get();
                c.hardware = labels.get(i) + ", wheel base " + Math.round(c.robot.wheelBase) + " mm";
                synchronized (results) {
                    results.add(c);
                }
            } catch (Exception ex) {
                // a failed candidate is simply left out
            }
        }
        Collections.sort(results);
        if (!results.isEmpty()) {
            Candidate best = results.get(0);
            best.reasons.addAll(why);
            Model r = best.robot;
            best.reasons.add(f("Chosen: %s.", best.hardware));
            best.reasons.add(f("Top speed %.0f mm/s, base speed %.0f mm/s, axle to sensors %.0f mm.", r.maxSpeed, r.baseSpeed, r.forward));
            best.reasons.add(f("PID found by simulation: Kp %.2f, Ki %.2f, Kd %.3f, D filter %.0f ms, corner slowdown %.0f%%.",
                    r.kp, r.ki, r.kd, r.dFilterMs, r.cornerSlow * 100));
            best.reasons.add(f("On this track: %.0f mm/s along the line, line lost %.1f%% of the time, average error %.1f mm "
                    + "(over %d slightly different runs each).", best.avgSpeed, best.lostShare * 100, best.avgErr, variants()));
            if (r.wheelCount == 4 && r.steerMode == 0) {
                best.reasons.add("4 wheels: more grip and room for a bigger battery, but it scrubs in bends.");
            } else if (r.steerMode != 0) {
                best.reasons.add(f("Car steering: tightest turn %.0f mm radius (axle spacing %.0f mm, lock %.0f deg).",
                        r.minTurnRadius(), r.axleSpacing, r.maxSteerDeg));
            }
            if (r.arrayShape == 1) {
                best.reasons.add(f("V array (%.0f degrees): the outer sensors reach %.0f mm further and see corners earlier.",
                        r.arrayAngle, r.arrayReach()));
            }
        }
        progress.accept(new Object[]{1.0, "Done: " + evals + " designs simulated.", results.isEmpty() ? null : results.get(0)});
        return results;
    }

    static String hardwareText(Model r, double[] hw) {
        String motor = hw[1] >= 0 ? HardwarePresets.MOTORS[(int) hw[1]].name : f("%.0f rpm %.0f V motor", r.motorRpm, r.motorVolt);
        String batt = hw[3] >= 0 ? HardwarePresets.BATTERIES[(int) hw[3]].name : f("%.1f V battery", r.battVolt);
        String layout = r.wheelCount == 2 ? "2 wheels + caster" : r.steerMode == 1 ? "car, rear drive"
                : r.steerMode == 2 ? "car, all-wheel drive" : "4WD skid steer";
        int motors = r.wheelCount == 4 && r.steerMode != 1 ? 4 : 2;
        return f("%s, %d x %s, %.0f mm wheels, %s, %d sensors x %.1f mm%s", layout, motors, motor, r.wheelDia, batt, r.nSensors, r.spacing,
                r.arrayShape == 1 ? f(", V %.0f deg", r.arrayAngle) : r.arrayShape == 2 ? ", arc" : "");
    }

    /** The design as control values ("Tab/Label" -> value in the control's own units), ready to apply. */
    static Map<String, String> toSettings(Model r) {
        Map<String, String> s = new LinkedHashMap<>();
        s.put("PID/Base speed", d(r.baseSpeed));
        s.put("PID/Kp", d(r.kp));
        s.put("PID/Ki", d(r.ki));
        s.put("PID/Kd", d(r.kd));
        s.put("PID/Corner slowdown", d(r.cornerSlow * 100));
        s.put("PID/Derivative filter", d(r.dFilterMs));
        s.put("Hardware/Wheels", Integer.toString(r.wheelCount == 2 ? 0 : 1 + r.steerMode));
        s.put("Hardware/Steering lock (car)", d(r.maxSteerDeg));
        s.put("Hardware/Steering speed (car)", d(r.servoRate));
        s.put("Hardware/Axle spacing (4WD)", d(r.axleSpacing));
        s.put("Hardware/Wheel base", d(r.wheelBase));
        s.put("Motors/Model", Integer.toString(r.driveModel));
        s.put("Hardware/Wheel diameter", d(r.wheelDia));
        s.put("Hardware/Axle to sensors", d(r.forward));
        s.put("Hardware/Sensor count", Integer.toString(r.nSensors));
        s.put("Hardware/Sensor pitch", d(r.spacing));
        s.put("Hardware/Sensor height", d(r.sensorHeight));
        s.put("Hardware/Array shape", Integer.toString(r.arrayShape));
        s.put("Hardware/V angle", d(r.arrayAngle));
        s.put("Hardware/Motor RPM", d(r.motorRpm));
        s.put("Hardware/Motor rated V", d(r.motorVolt));
        s.put("Hardware/Battery voltage", d(r.battVolt));
        s.put("Hardware/Load factor", d(r.loadPct));
        s.put("Hardware/Measured top speed", d(r.topSpeedOverride));
        s.put("Hardware/Ambient light blocked", d(r.ambientShield * 100));
        s.put("Motors/Motor lag", d(r.lagL));
        s.put("Motors/Motor lag #2", d(r.lagR));
        s.put("Motors/Stall torque", d(r.stallTorque));
        return s;
    }

    private static String d(double v) {
        return Double.toString(v);
    }
}
