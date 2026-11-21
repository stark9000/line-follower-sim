package linefollowersim;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Headless checks of the model, tracks, files and analysis (no JUnit needed).
 * In NetBeans: right-click this file > Run File. Exit code 0 = all passed.
 */
public final class SelfTest {

    private static int pass, fail;

    private SelfTest() {
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass++;
            System.out.println("  ok    " + name);
        } else {
            fail++;
            System.out.println("  FAIL  " + name + "   (" + detail + ")");
        }
    }

    private static Model run(int preset, double seconds) {
        return run(preset, seconds, null);
    }

    private static String f(double v) {
        return String.format(java.util.Locale.ROOT, "%.3f", v);
    }

    private static Model run(int preset, double seconds, java.util.function.Consumer<Model> setup) {
        Model m = new Model();
        m.loadPreset(preset);
        if (setup != null) {
            setup.accept(m);
        }
        m.reset();
        for (double t = 0; t < seconds; t += 0.02) {
            m.advance(0.02);
        }
        return m;
    }

    public static void main(String[] args) throws Exception {
        System.out.println("LineFollowerSim self-test");

        // ---- following the presets with the default robot
        for (int p : new int[]{1, 2, 3, 5, 11, 12}) {
            Model m = run(p, p == 1 || p == 3 ? 3.5 : 8, null);         // the open tracks end after about 4 s
            double lostShare = m.lineLostTime / m.t;
            check("preset " + p + " (" + TrackBuilder.PRESETS[p] + ") followed", !m.outOfArena && lostShare < 0.1,
                    "out=" + m.outOfArena + " lost=" + lostShare);
        }
        Model st = run(0, 3, null);
        check("straight: pulled onto the line", Math.abs(st.y + Math.sin(st.th) * st.forward - st.sy + 28) < 6,
                "offset " + (st.y - (st.sy - 28)));

        // ---- defaults are neutral: new options off give identical runs
        Model a = run(5, 4, null), b = run(5, 4, mm -> {
            mm.whiteLeds = false;
            mm.tractionMu = 0;
            mm.junctionRule = 0;
        });
        check("neutral defaults reproduce the run", a.x == b.x && a.y == b.y && a.th == b.th, a.x + " vs " + b.x);

        // ---- junctions
        Model tj = run(10, 2.6, mm -> mm.junctionRule = 1);
        check("T-junction, prefer left: turns left", !tj.outOfArena && tj.x < 300, "x=" + tj.x + " y=" + tj.y);
        Model tr = run(10, 2.6, mm -> mm.junctionRule = 2);
        check("T-junction, prefer right: turns right", !tr.outOfArena && tr.x > 500, "x=" + tr.x + " y=" + tr.y);
        Model cr = run(9, 4.5, null);
        check("crossroads, ignore: goes straight across", Math.abs(cr.y - 240) < 15 && cr.x > 480, "x=" + cr.x + " y=" + cr.y);

        // ---- Y-junction: the rule picks the branch
        Model yl = run(13, 3.2, mm -> mm.junctionRule = 1);
        Model yr = run(13, 3.2, mm -> mm.junctionRule = 2);
        check("Y-junction, prefer left: upper branch", yl.y < 200 && yl.lineLostTime < 0.3, "y=" + yl.y + " lost=" + yl.lineLostTime);
        check("Y-junction, prefer right: lower branch", yr.y > 280 && yr.lineLostTime < 0.3, "y=" + yr.y + " lost=" + yr.lineLostTime);

        // ---- per-sensor calibration: HUD bits match what the controller uses
        Model pc = new Model();
        pc.mismatch = 0.4;
        pc.calibrateSweep();
        pc.perSensorCal = true;
        pc.reset();
        boolean same = true;
        for (int i = 0; i < pc.nSensors; i++) {
            same &= pc.bit(i) == (pc.dark[i] > 0.5);
        }
        check("per-sensor calibration: bits = controller's view", same, "");

        // ---- crosstalk: calibration includes it (threshold between the real white and black)
        Model xt = new Model();
        xt.crosstalkPct = 20;
        xt.barriers = false;
        xt.mismatch = 0;
        xt.noise = 0;
        xt.calibrate();
        double[] far = xt.rawOnLine(500), on = xt.rawOnLine(0);
        int mid = (xt.nSensors - 1) / 2;
        check("crosstalk-aware calibration", Math.abs(xt.calWhite - far[mid]) < 15 && xt.threshold > on[mid] && xt.threshold < far[mid],
                "white " + xt.calWhite + " vs " + far[mid]);

        // ---- white LEDs
        Model w0 = new Model();
        w0.noise = 0;
        w0.mismatch = 0;
        double base = w0.rawOnLine(500)[0];
        w0.whiteLeds = true;
        w0.whiteMode = 1;
        w0.whitePower = 1;
        w0.whiteSeen = 0.5;
        double lit = w0.rawOnLine(500)[0];
        check("white floor light raises the white reading", lit > base + 20 || lit >= 1022, base + " -> " + lit);
        w0.whiteMode = 0;
        w0.barriers = false;
        double[] ind = w0.rawOnLine(0), ref = new Model().rawOnLine(0);
        check("indicator leak raises the black reading without barriers", ind[mid] > ref[mid] + 5, ref[mid] + " -> " + ind[mid]);
        w0.calibrate();
        check("calibration accounts for the indicator", w0.threshold > new Model().threshold, w0.threshold + "");
        Model wf = run(5, 6, mm -> {
            mm.whiteLeds = true;
            mm.whiteMode = 0;
            mm.calibrate();
        });
        check("robot still follows with indicator LEDs", !wf.outOfArena && wf.lineLostTime / wf.t < 0.1, "lost " + wf.lineLostTime);

        // ---- traction: acceleration limited by grip
        Model g0 = run(0, 0.3, null), g1 = run(0, 0.3, mm -> mm.tractionMu = 0.02);
        check("low grip: slower take-off", g1.distance < g0.distance * 0.9, g0.distance + " vs " + g1.distance);
        check("low grip: wheels spin faster than the chassis", (g1.vL + g1.vR) / 2 > g1.vBody + 1, g1.vBody + "");

        // ---- line width used by the checks
        Model lw = new Model();
        lw.loadPreset(11);
        check("thin-line preset judged at 10 mm", Math.abs(lw.checkLineWidth() - 10) < 1e-9, "" + lw.checkLineWidth());
        lw.loadPreset(12);
        check("wide-line preset judged at 40 mm", Math.abs(lw.checkLineWidth() - 40) < 1e-9, "" + lw.checkLineWidth());

        // ---- track file round trip, with patches
        Model f = new Model();
        f.loadPreset(5);
        f.addPatch(false, 0.7, 200, 80, 100);
        f.addPatch(true, 0.05, 250, 160, 300);
        File tmp = File.createTempFile("lfs", ".lftrack");
        TrackFile.save(tmp, f);
        Model g = new Model();
        TrackFile.load(tmp, g);
        boolean shapesSame = g.shapes.size() == f.shapes.size();
        for (int i = 0; shapesSame && i < f.shapes.size(); i++) {
            shapesSame = f.shapes.get(i).serialize().equals(g.shapes.get(i).serialize());
        }
        check("track file: shapes round trip", shapesSame, "");
        check("track file: patches round trip", g.patches.size() == 2 && g.patches.get(1).slip
                && Math.abs(g.patches.get(0).amount - 0.7) < 1e-12 && Math.abs(g.patches.get(1).cx - f.patches.get(1).cx) < 1e-9,
                g.patches.size() + " patches");
        g.loadPreset(2);
        check("loading another track clears the patches", g.patches.isEmpty(), g.patches.size() + "");
        List<String> lines = new ArrayList<>(Files.readAllLines(tmp.toPath()));
        lines.add("future-item 1 2 3");
        Files.write(tmp.toPath(), lines);
        TrackFile.load(tmp, g);
        check("track file: unknown lines are skipped", g.shapes.size() == f.shapes.size(), "");
        Files.write(tmp.toPath(), java.util.Arrays.asList("LineFollowerSim-track 1", "arena 800 480", "patch 1 2 x"));
        boolean threw = false;
        try {
            TrackFile.load(tmp, g);
        } catch (java.io.IOException ex) {
            threw = true;
        }
        check("track file: damaged patch line is reported", threw, "");
        tmp.delete();

        // ---- analysis on a copy does not disturb the robot
        Model live = new Model();
        live.loadPreset(4);
        for (int i = 0; i < 50; i++) {
            live.advance(0.02);
        }
        double lx = live.x, ly = live.y;
        DesignCheck.Report rep = DesignCheck.run(live);
        Experiments.CornerResult corner = Experiments.corner(Experiments.snapshot(live));
        Experiments.StepResult step = Experiments.stepResponse(Experiments.snapshot(live));
        check("design check runs", rep.items.size() >= 8, rep.items.size() + " items");
        check("corner test runs", !corner.verdict.isEmpty() && corner.px.length > 10, corner.verdict);
        check("step test runs", !step.label.isEmpty(), step.label);
        check("tests leave the live robot alone", live.x == lx && live.y == ly, "");

        // ---- MCU timing
        Model fast = new Model();
        fast.clockMHz = 600;
        fast.adcCycles = 3000;
        fast.mathCycles = 300;
        fast.loopDelayMs = 0;
        check("fast board: physics step below 0.5 ms", fast.stepSec() < Model.DT, fast.stepSec() + "");
        fast.reset();
        fast.advance(0.1);
        check("fast board: loop rate is not capped at 2 kHz", fast.loopCount > 220, fast.loopCount + " loops in 0.1 s");

        // ---- copySettingsFrom copies every setting
        Model src = new Model();
        src.whiteLeds = true;
        src.whitePower = 0.77;
        src.kp = 7.5;
        src.nRows = 2;
        Model dst = Experiments.snapshot(src);
        check("snapshot copies settings", dst.settingsSignature().equals(src.settingsSignature()), "");

        // ---- physical drive model
        Model ph = new Model();
        ph.driveModel = 1;
        ph.loadPreset(8);
        ph.kp = 0;
        ph.kd = 0;
        ph.cornerSlow = 0;
        ph.baseSpeed = ph.maxSpeed;
        ph.reset();
        double top = ph.physicalTopSpeed(), t63 = -1;
        for (int i = 0; i < 400; i++) {
            ph.advance(0.0025);
            if (t63 < 0 && ph.vBody > 0.63 * top) {
                t63 = ph.t;
            }
        }
        check("physical: 63 % time matches the formula", Math.abs(t63 - ph.mechTimeConstant()) < 0.25 * ph.mechTimeConstant(),
                t63 + " vs " + ph.mechTimeConstant());
        check("physical: reaches the predicted top speed", Math.abs(ph.vBody - top) < 0.03 * top, ph.vBody + " vs " + top);
        double heavy;
        {
            Model hv = new Model();
            hv.massG = 1400;
            heavy = hv.mechTimeConstant();
        }
        check("physical: 4x the mass, 4x slower to speed up", Math.abs(heavy / ph.mechTimeConstant() - 4) < 0.01, "" + heavy);
        double[] stopT = new double[2];
        for (int decay = 0; decay < 2; decay++) {
            Model d = new Model();
            d.driveModel = 1;
            d.decayMode = decay;
            d.loadPreset(8);
            d.kp = 0;
            d.kd = 0;
            d.cornerSlow = 0;
            d.baseSpeed = 300;
            d.reset();
            d.advance(1.0);
            d.baseSpeed = 0;
            double t0 = d.t;
            for (int i = 0; i < 2000 && d.vBody > 30; i++) {
                d.advance(0.001);
            }
            stopT[decay] = d.t - t0;
        }
        check("physical: coasting driver stops later than braking", stopT[1] > stopT[0] * 1.5, stopT[0] + " vs " + stopT[1]);
        Model pf = run(2, 8, mm -> mm.driveModel = 1);
        check("physical: follows the oval", !pf.outOfArena && pf.lineLostTime / pf.t < 0.05, "lost " + pf.lineLostTime);
        Model dz = new Model();
        dz.driveModel = 1;
        dz.gearFrictionPct = 30;
        dz.loadPreset(8);
        dz.baseSpeed = dz.maxSpeed * 0.2;
        dz.kp = 0;
        dz.kd = 0;
        dz.reset();
        dz.advance(1.0);
        check("physical: below the friction the robot does not move", dz.distance < 1, dz.distance + " mm");

        // ---- start direction
        Model sd = new Model();
        sd.loadPreset(2);
        String d0 = sd.loopDirection();
        sd.startDir = 1;
        sd.loadPreset(2);
        check("reversed loop runs the other way", d0 != null && sd.loopDirection() != null && !d0.equals(sd.loopDirection()),
                d0 + " / " + sd.loopDirection());
        Model rv = run(2, 6, mm -> {
            mm.startDir = 1;
            mm.loadPreset(2);
        });
        check("reversed loop is followed", !rv.outOfArena && rv.lineLostTime / rv.t < 0.05, "lost " + rv.lineLostTime);
        Model sn = new Model();
        sn.startDir = 1;
        sn.loadPreset(1);
        check("reversed open track starts at its far end", sn.sx > 600 && Math.cos(sn.sth) < 0, sn.sx + " " + sn.sth);

        // ---- trails and kept runs
        Model tr2 = run(2, 3, null);
        int pts = tr2.trCount;
        Model.Run kept = tr2.keepRun(new java.util.HashMap<>());
        check("keep run copies the trail and numbers", kept.x.length == pts && kept.avgErr == tr2.avgAbsError() && kept.err.length > 100,
                kept.x.length + " / " + pts);
        tr2.clearTrail();
        check("clear trail empties the trail only", tr2.trCount == 0 && tr2.t > 2.9, tr2.trCount + "");
        tr2.lineWidth = 25;
        tr2.rerender();
        check("kept runs survive a new line width", tr2.runs.size() == 1, "" + tr2.runs.size());
        tr2.loadPreset(5);
        check("kept runs are dropped for another track", tr2.runs.isEmpty(), "" + tr2.runs.size());

        // ---- LEDs, shield, flicker
        Model lb = new Model();
        lb.noise = 0;
        lb.mismatch = 0;
        lb.whiteLeds = true;
        lb.whiteMode = 1;
        lb.whitePower = 1;
        lb.irPower = 0.4;                                      // keep the white reading below 1023
        lb.ledPlace = 0;
        lb.ledOffset = 2;
        double near = lb.rawOnLine(500)[mid];
        lb.ledOffset = 25;
        double farLed = lb.rawOnLine(500)[mid];
        check("LED light falls off with distance", near > farLed + 10, near + " vs " + farLed);
        lb.ledColor = 4;
        double blue = lb.rawOnLine(500)[mid];
        lb.ledColor = 1;
        check("IR sensor sees red light more than blue", lb.rawOnLine(500)[mid] > blue, "");
        Model sh = new Model();
        sh.ambient = 0.6;
        sh.irPower = 0.3;
        sh.noise = 0;
        double bright = sh.rawOnLine(500)[mid];
        sh.ambientShield = 0.9;
        check("light shield removes most of the ambient light", sh.rawOnLine(500)[mid] < bright - 300, bright + "");
        Model fl = new Model();
        fl.ambient = 0.3;
        fl.irPower = 0.4;
        fl.noise = 0;
        fl.flickerPct = 50;
        double r0 = fl.rawOnLine(500)[mid];
        fl.t = 0.005;                                          // half a 100 Hz period later
        check("lamp flicker moves the readings", Math.abs(fl.rawOnLine(500)[mid] - r0) > 50, r0 + "");

        // ---- builder: Esc goes back to Select
        Model em = new Model();
        TrackEditor te = new TrackEditor(em);
        te.setTool(TrackEditor.LINE);
        te.escape();
        check("Esc returns to the Select tool", te.tool == TrackEditor.SELECT, "" + te.tool);
        te.setTool(TrackEditor.POLYLINE);
        te.press(new java.awt.geom.Point2D.Double(10, 10), false, 1, false, false);
        check("right-click (nothing drawn) returns to Select", te.tool == TrackEditor.SELECT, "" + te.tool);

        // ---- Arduino export
        Model ax = new Model();
        ax.kp = 6.25;
        String code = ArduinoExport.generate(ax, new ArduinoExport.Options(), "test");
        check("Arduino sketch carries the gains in the same units", code.contains("KP = 6.250") && code.contains("void loop()"), "");

        // ---- sensor array and chassis shapes
        Model va = new Model();
        va.arrayShape = 1;
        va.arrayAngle = 20;
        double reach = va.arrayReach();
        check("V array: outer sensors further forward", Math.abs(reach - 35 * Math.tan(Math.toRadians(20))) < 1e-9, "" + reach);
        va.arrayShape = 2;
        check("arc array: outer sensors trail back", va.arrayReach() == 0 && va.sensorDx(35) < -4, "" + va.sensorDx(35));
        Model vs0 = new Model(), vs1 = new Model();
        vs1.arrayShape = 1;
        vs1.arrayAngle = 10;
        double ov0 = Experiments.corner(Experiments.snapshot(vs0)).overshoot, ov1 = Experiments.corner(Experiments.snapshot(vs1)).overshoot;
        check("a small V array overshoots a 90-degree corner less", ov1 < ov0, ov0 + " -> " + ov1);
        Model ch = new Model();
        double[] cg1 = ch.chassisGeometry();
        java.awt.geom.Rectangle2D cb = ch.chassisOutline().getBounds2D();
        check("chassis area matches its outline", Math.abs(cg1[0] - cb.getWidth() * cb.getHeight()) < 0.05 * cg1[0], cg1[0] + "");
        ch.chassisShape = 3;
        ch.customOutline.add(new double[]{-30, -40});
        ch.customOutline.add(new double[]{90, -40});
        ch.customOutline.add(new double[]{90, 40});
        ch.customOutline.add(new double[]{-30, 40});
        check("custom chassis outline: area", Math.abs(ch.chassisGeometry()[0] - 120 * 80) < 1, "" + ch.chassisGeometry()[0]);
        check("custom outline is copied to snapshots", Experiments.snapshot(ch).customOutline.size() == 4, "");
        double iSmall = ch.yawInertia();
        ch.customOutline.set(1, new double[]{190, -40});
        ch.customOutline.set(2, new double[]{190, 40});
        check("a longer chassis is harder to turn", ch.yawInertia() > iSmall * 1.5, iSmall + " -> " + ch.yawInertia());
        Model cw = new Model();
        cw.casterPos = 60;
        double farW = cw.suggestedWheelWeight();
        cw.casterPos = 20;
        check("front caster closer to the axle takes more weight off the wheels", cw.suggestedWheelWeight() < farW, farW + "");

        // ---- 4 wheels and car steering
        Model w4 = new Model();
        w4.wheelCount = 4;
        w4.axleSpacing = 100;
        check("4WD skid steer turns less than 2WD", w4.skidChi() > 1.2, "" + w4.skidChi());
        Model s4 = run(2, 8, mm -> {
            mm.wheelCount = 4;
            mm.axleSpacing = 100;
            mm.kp = 8;
        });
        check("4WD follows the oval", !s4.outOfArena && s4.lineLostTime / s4.t < 0.05, "lost " + s4.lineLostTime);
        Model p4 = run(2, 6, mm -> {
            mm.wheelCount = 4;
            mm.axleSpacing = 100;
            mm.driveModel = 1;
            mm.kp = 8;
        });
        check("4WD physical model drives", p4.distance > 600 && !p4.outOfArena, "dist " + p4.distance);
        Model car = new Model();
        car.wheelCount = 4;
        car.steerMode = 1;
        car.axleSpacing = 80;
        car.maxSteerDeg = 45;
        check("car turning radius = spacing / tan(lock)", Math.abs(car.minTurnRadius() - 80) < 1e-6, "" + car.minTurnRadius());
        for (int act = 0; act < 2; act++) {
            final int a2 = act;
            Model cr2 = run(2, 8, mm -> {
                mm.wheelCount = 4;
                mm.steerMode = 1;
                mm.axleSpacing = 80;
                mm.maxSteerDeg = 45;
                mm.steerActuator = a2;
            });
            check("car (" + (act == 0 ? "servo" : "DC motor") + " steering) follows the oval", !cr2.outOfArena && cr2.lineLostTime / cr2.t < 0.1,
                    "lost " + cr2.lineLostTime);
        }
        Model wide = run(2, 8, mm -> {
            mm.wheelCount = 4;
            mm.steerMode = 1;
            mm.axleSpacing = 120;
            mm.maxSteerDeg = 25;
        });
        check("car that cannot turn tight enough loses the oval", wide.lineLostTime > 0.5 || wide.outOfArena, "lost " + wide.lineLostTime);

        // ---- track analysis
        Model ta = new Model();
        ta.loadPreset(2);
        TrackAnalysis t2 = TrackAnalysis.analyze(ta);
        check("oval: closed loop, no sharp corners, bend ~110 mm", t2.closed && t2.sharpCorners == 0 && Math.abs(t2.minRadius - 110) < 15,
                t2.summary());
        ta.loadPreset(4);
        TrackAnalysis t4 = TrackAnalysis.analyze(ta);
        check("90-degree track: 6 sharp corners", t4.sharpCorners == 6 && !t4.closed, t4.summary());
        ta.loadPreset(9);
        check("crossroads: a crossing", TrackAnalysis.analyze(ta).junctions >= 1, TrackAnalysis.analyze(ta).summary());
        ta.loadPreset(7);
        check("dashed line: gaps", TrackAnalysis.analyze(ta).gaps >= 3, TrackAnalysis.analyze(ta).summary());
        ta.loadPreset(5);
        check("race circuit: one shape, no junctions", TrackAnalysis.analyze(ta).junctions == 0, "");

        // ---- robot designer
        Model dl = new Model();
        dl.loadPreset(2);
        Model dbase = Experiments.snapshot(dl);
        dbase.setTrack(dl.arenaW, dl.arenaH, new java.util.ArrayList<>(dl.shapes), new double[]{dl.sx, dl.sy, dl.sth});
        Designer.Options dop = new Designer.Options();
        dop.effort = 0;
        dop.reliability = 0.8;
        java.util.List<Designer.Candidate> dres = new Designer(dbase, TrackAnalysis.analyze(dbase), dop).run(p -> { });
        check("designer finds robots", !dres.isEmpty() && dres.get(0).score > 0, dres.size() + "");
        Designer.Candidate best = dres.get(0);
        Model chk = new Model();
        chk.copySettingsFrom(best.robot);
        chk.loadPreset(2);
        for (double t = 0; t < 8; t += 0.02) {
            chk.advance(0.02);
        }
        check("designed robot follows the track", !chk.outOfArena && chk.lineLostTime / chk.t < 0.05, "lost " + chk.lineLostTime);
        check("designed robot is faster than the default", best.avgSpeed > 230, "" + best.avgSpeed);
        Designer.Options lk = new Designer.Options();
        lk.effort = 0;
        lk.lockMotor = lk.lockWheels = lk.lockBattery = lk.lockLayout = true;
        java.util.List<Designer.Candidate> lres = new Designer(dbase, TrackAnalysis.analyze(dbase), lk).run(p -> { });
        check("locked parts stay as they are", lres.get(0).robot.motorRpm == dl.motorRpm && lres.get(0).robot.wheelDia == dl.wheelDia
                && lres.get(0).robot.battVolt == dl.battVolt, lres.get(0).hardware);
        check("design settings use the right units", Designer.toSettings(best.robot).get("PID/Corner slowdown")
                .equals(Double.toString(best.robot.cornerSlow * 100)), "");

        // ---- exports
        java.io.File dxf = java.io.File.createTempFile("lfs", ".dxf"), svg = java.io.File.createTempFile("lfs", ".svg");
        Drawing rd = Exports.robot(car, new Exports.Mounts());
        rd.writeDxf(dxf);
        rd.writeSvg(svg);
        String dx = new String(java.nio.file.Files.readAllBytes(dxf.toPath()), "US-ASCII");
        check("robot DXF: R12, mm, layers, ends with EOF", dx.contains("AC1009") && dx.contains("$INSUNITS") && dx.contains("\nCUT\r")
                && dx.contains("HOLES") && dx.trim().endsWith("EOF"), "");
        check("robot SVG sized in mm", new String(java.nio.file.Files.readAllBytes(svg.toPath()), "UTF-8").contains("mm\" viewBox"), "");
        Model tk = new Model();
        tk.loadPreset(5);
        Drawing td = Exports.track(tk, 0.1);
        double[] tb = td.bounds();
        check("track drawing is 1:1 (arena 800 x 480)", Math.abs(tb[2] - 800) < 1 && Math.abs(tb[3] - 480) < 1, tb[2] + " x " + tb[3]);
        check("parts list names the layout", Exports.partsList(car).contains("rear-wheel drive"), "");
        dxf.delete();
        svg.delete();

        // ---- round 5: laps, telemetry, recovery, repeatability, driver, calibration status
        Model lp = run(2, 30);
        check("laps are counted on a loop", lp.laps.size() >= 2 && Math.abs(lp.laps.get(lp.laps.size() - 1) - lp.laps.get(lp.laps.size() - 2)) < 0.3,
                lp.laps.toString());
        Model fin = new Model();
        fin.loadPreset(1);
        fin.reset();
        for (int i = 0; i < 600 && !fin.outOfArena; i++) {
            fin.advance(0.02);
        }
        check("an open track gives a finish time", fin.gateIsFinish() && fin.laps.size() == 1, fin.laps.toString());
        java.io.File tcsv = java.io.File.createTempFile("lfs", ".csv");
        int rows = Telemetry.writeCsv(lp, tcsv);
        Model.Run imp = Telemetry.readLog(lp, tcsv);
        check("telemetry CSV reads back as a run", rows > 1000 && imp.err.length == rows && imp.x.length == rows, rows + " rows");
        tcsv.delete();
        Model sa = new Model(), sb2 = new Model();
        for (Model q : new Model[]{sa, sb2}) {
            q.noise = 0.04;
            q.loadPreset(4);
            q.reset();
            for (int i = 0; i < 200; i++) {
                q.advance(0.02);
            }
        }
        check("same noise seed = same run", sa.x == sb2.x && sa.y == sb2.y, sa.x + " / " + sb2.x);
        Model stop = new Model();
        stop.lostMode = 5;
        stop.loadPreset(1);
        stop.reset();
        for (int i = 0; i < 600 && !stop.outOfArena; i++) {
            stop.advance(0.02);
        }
        check("lost line: Stop stops", !stop.outOfArena && Math.abs(stop.vL) + Math.abs(stop.vR) < 1, f(stop.vL) + " " + f(stop.vR));
        Model dr = new Model();
        double top0 = dr.hwTopSpeed();
        dr.driverDrop = 2;
        check("an L298N-like drop lowers the top speed", dr.hwTopSpeed() < top0 * 0.8, f(top0) + " -> " + f(dr.hwTopSpeed()));
        Model cs = new Model();
        cs.calibrate();
        double okLevel = cs.calibrationStatus()[0];
        cs.ambient = 0.9;
        check("calibration warning when the light changes", okLevel == 0 && cs.calibrationStatus()[0] == 2, cs.calText);

        // ---- custom layout (Robot editor) and 32 sensors
        Model reg = run(5, 8), cu = run(5, 8, q -> {
            q.layoutFromShape();
            q.customLayout = true;
        });
        check("custom layout at the regular spots drives the same", Math.abs(cu.x - reg.x) < 1e-6 && Math.abs(cu.y - reg.y) < 1e-6,
                f(cu.x) + " / " + f(reg.x));
        Model big = new Model();
        big.nSensors = 32;
        big.spacing = 5;
        big.loadPreset(5);
        big.calibrate();
        big.reset();
        for (int i = 0; i < 400 && !big.outOfArena; i++) {
            big.advance(0.02);
        }
        check("32 sensors follow the line", !big.outOfArena && big.avgAbsError() < 10, f(big.avgAbsError()));
        Model gx = new Model();
        gx.crosstalkPct = 10;
        gx.barriers = false;
        gx.irPower = 0.6;
        int gm = gx.nSensors / 2;
        double[] gx0 = gx.rawOnLine(gx.lat(gm));
        gx.pairGap = 4;
        gx.pairAlternate = true;
        double[] gx1 = gx.rawOnLine(gx.lat(gm));
        check("IR pairs turned round change the crosstalk", Math.abs(gx0[gm] - gx1[gm]) > 1, f(gx0[gm]) + " vs " + f(gx1[gm]));
        RobotDraft dd = new RobotDraft();
        dd.chassis.addAll(RobotDraft.startShape(0, 60, 40, 100));
        dd.chassis.get(1).y += 20;                                         // make it lopsided, then copy the left half
        dd.makeSymmetric(-1);
        java.awt.geom.Rectangle2D cb2 = dd.chassisPath().getBounds2D();
        check("chassis: copy one half to the other", Math.abs(cb2.getMinY() + cb2.getMaxY()) < 1e-6, f(cb2.getMinY()) + " " + f(cb2.getMaxY()));
        java.util.List<RobotDraft.Pt> ps = RobotDraft.placeSensors(1, 9, 80, 70, 20, 0, dd.chassis, 0);
        check("sensors placed evenly in a V", Math.abs(ps.get(1).y - ps.get(0).y - 10) < 1e-9 && ps.get(0).x > ps.get(4).x, f(ps.get(0).x));
        dd.sensors.addAll(ps);
        dd.placeLeds(0, 5);
        check("LEDs placed between the sensors", dd.leds.size() == 8, "" + dd.leds.size());
        check("draft text round trip", RobotDraft.parse(dd.lines()).lines().equals(dd.lines()), "");
        java.io.File odxf = java.io.File.createTempFile("lfs", ".dxf");
        Exports.robot(car, new Exports.Mounts()).writeDxf(odxf);
        java.util.List<double[]> outl = OutlineImport.read(odxf);
        check("chassis outline imports back from our DXF", outl.size() >= 3, outl.size() + " corners");
        odxf.delete();
        java.io.File osvg = java.io.File.createTempFile("lfs", ".svg");
        java.nio.file.Files.write(osvg.toPath(), "<svg width=\"100mm\" height=\"50mm\" viewBox=\"0 0 100 50\"><path d=\"M10 10 L90 10 L90 40 L10 40 Z\"/></svg>".getBytes("UTF-8"));
        java.util.List<double[]> so = OutlineImport.read(osvg);
        check("SVG outline import in mm", so.size() == 4 && Math.abs(Math.abs(OutlineImport.area(so)) - 2400) < 1e-6, f(OutlineImport.area(so)));
        osvg.delete();

        // ---- mounting, comparator, ADC, encoders, battery, failures
        Model hy = new Model();
        hy.hystPct = 30;
        hy.calibrate();
        hy.reset();
        check("hysteresis keeps a sensor's state", hy.nSensors > 0, "");
        Model sl0 = new Model(), sl1 = new Model();
        for (Model q : new Model[]{sl0, sl1}) {
            q.gainL = 80;
            q.encType = 1;
            q.ki = 0;
            q.speedLoop = q == sl1;
            q.loadPreset(0);
            q.calibrate();
            q.reset();
        }
        double e0 = 0, e1 = 0;
        for (int i = 0; i < 200; i++) {
            sl0.advance(0.02);
            sl1.advance(0.02);
            if (i > 50) {
                e0 += sl0.error;
                e1 += sl1.error;
            }
        }
        check("the wheel-speed loop removes the drift of mismatched motors", Math.abs(e1) < Math.abs(e0) * 0.2, f(e0 / 149) + " -> " + f(e1 / 149));
        Model bt = new Model();
        bt.battCapacity = 500;
        bt.loadPreset(2);
        bt.reset();
        for (int i = 0; i < 500; i++) {
            bt.advance(0.02);
        }
        check("current and running time are worked out", bt.currentNow > 0.05 && bt.runtimeMin() > 5 && bt.runtimeMin() < 1000, f(bt.runtimeMin()));
        Model tip = new Model();
        tip.tipCheck = true;
        tip.wheelBase = 40;
        tip.baseSpeed = 900;
        tip.kp = 20;
        tip.useParts = true;
        tip.defaultParts();
        for (Model.Part q : tip.parts) {
            q.h += 60;
        }
        tip.loadPreset(5);
        tip.reset();
        for (int i = 0; i < 500 && !tip.outOfArena; i++) {
            tip.advance(0.02);
        }
        check("a tall narrow fast robot tips over", tip.tipped, "");
        Model mref = new Model();
        mref.analogMode = true;
        mref.loadPreset(0);
        mref.calibrate();
        mref.reset();
        for (int i = 0; i < 150; i++) {
            mref.advance(0.02);
        }
        Model mo = new Model();
        mo.arrayOffset = 6;
        mo.analogMode = true;
        mo.loadPreset(0);
        mo.calibrate();
        mo.reset();
        for (int i = 0; i < 150; i++) {
            mo.advance(0.02);
        }
        check("an array mounted off centre runs beside the line", Math.abs(Math.abs(mo.y - mref.y) - 6) < 2.5, f(mo.y - mref.y));
        TrackShape ts = new TrackShape(TrackShape.Type.RECT);
        ts.pts.add(new java.awt.geom.Point2D.Double(0, 0));
        ts.pts.add(new java.awt.geom.Point2D.Double(100, 50));
        ts.transform(java.awt.geom.AffineTransform.getRotateInstance(Math.toRadians(30)));
        check("a rectangle turned by 30 degrees becomes a path", ts.type == TrackShape.Type.PATH, "" + ts.type);
        String sk = ArduinoExport.generate(cu, new ArduinoExport.Options(), "t");
        check("the sketch carries the sensor positions", sk.contains("SENSOR_Y[N]") && sk.contains("LOST_MODE"), "");

        // ---- tracing and the editor's wheels
        java.awt.image.BufferedImage tim = new java.awt.image.BufferedImage(400, 300, java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D tg = tim.createGraphics();
        tg.setColor(new java.awt.Color(40, 40, 40));
        tg.fillRect(0, 0, 400, 300);
        tg.setColor(new java.awt.Color(30, 90, 200));
        tg.fillRect(50, 50, 200, 100);
        tg.fillRect(130, 150, 40, 120);
        tg.dispose();
        TraceLayer tl = new TraceLayer();
        tl.setPicture(tim, 100);
        tl.mmPerPx = 0.5;
        java.awt.geom.Point2D tp = tl.picToPlane().transform(new java.awt.geom.Point2D.Double(150, 100), null);
        java.util.List<RobotDraft.Pt> trc = tl.autoTrace(tp.getY(), tp.getX(), 20, 1, 0.5);
        RobotDraft tdr = new RobotDraft();
        if (trc != null) {
            tdr.chassis.addAll(trc);
        }
        check("auto-trace finds a T outline (8 corners, area within 3 %)", trc != null && trc.size() == 8
                && Math.abs(tdr.plateGeometry()[0] - (200 * 100 + 40 * 120) * 0.25) < 0.03 * 6200, trc == null ? "none" : trc.size() + " corners, "
                + f(tdr.plateGeometry()[0]) + " mm2");
        java.awt.geom.Point2D bg2 = tl.picToPlane().transform(new java.awt.geom.Point2D.Double(10, 10), null);
        check("auto-trace refuses the background", tl.autoTrace(bg2.getY(), bg2.getX(), 20, 1, 0.5) == null, "");
        java.io.File udxf = java.io.File.createTempFile("lfs", ".dxf");
        Exports.robot(car, new Exports.Mounts()).writeDxf(udxf);
        OutlineImport.Underlay ul = OutlineImport.underlay(udxf);
        check("a drawing shows with its holes as snap points", ul.circles.size() > 2 && !ul.closed.isEmpty(), ul.circles.size() + " circles");
        udxf.delete();
        RobotDraft wd = RobotDraft.from(new Model());
        wd.wheelLayout = 3;
        wd.axleSpacing = 95;
        Model wm = new Model();
        RobotDraft.parse(wd.lines()).applyTo(wm);
        check("the editor's wheels: 4, car, all-wheel drive", wm.wheelCount == 4 && wm.steerMode == 2 && wm.axleSpacing == 95, "");
        wd.boardOnPlate = true;
        check("sensors on the plate: no separate board", wd.board().isEmpty(), "");

        // ---- LEDs while running, real-world effects, sensor parts, board outline, wheel collisions
        Model lsw = new Model();
        lsw.whiteLeds = true;
        lsw.ledOffAt = 1;
        lsw.ledOnAt = 2;
        lsw.reset();
        boolean litA = lsw.ledsLit();
        for (int i = 0; i < 75; i++) {
            lsw.advance(0.02);
        }
        boolean litB = lsw.ledsLit();
        for (int i = 0; i < 50; i++) {
            lsw.advance(0.02);
        }
        check("timed LED switch: on, off at 1 s, on again at 2 s", litA && !litB && lsw.ledsLit(), litA + " " + litB + " " + lsw.ledsLit());
        Model gl = new Model();
        gl.calibrate();
        double c0 = gl.calWhite - gl.calBlack;
        gl.glossPct = 50;
        gl.calibrate();
        check("shiny tape lowers the contrast", gl.calWhite - gl.calBlack < 0.8 * c0, f(c0) + " -> " + f(gl.calWhite - gl.calBlack));
        Model bo = new Model();
        bo.bounceMmPerG = 5;
        bo.driveModel = 1;
        bo.loadPreset(0);
        bo.reset();
        double hMax = 0;
        for (int i = 0; i < 25; i++) {
            bo.advance(0.02);
            hMax = Math.max(hMax, bo.heightNow());
        }
        check("the board lifts while the robot speeds up", hMax > bo.sensorHeight + 0.05, f(hMax));
        Model wa = new Model();
        wa.motorWarmPct = 20;
        wa.loadPreset(2);
        wa.reset();
        for (int i = 0; i < 3000; i++) {
            wa.advance(0.02);
        }
        check("motors lose speed as they warm up", wa.warmFactor() < 0.99, f(wa.warmFactor()));
        Model sp = new Model();
        sp.sensBodyW = 10.2;
        sp.sensBodyL = 5.8;
        DesignCheck.Report spr = DesignCheck.run(sp);
        boolean flagged = false;
        for (DesignCheck.Item it : spr.items) {
            flagged |= it.title.equals("Sensor parts");
        }
        check("TCRT5000-size parts at 10 mm pitch are flagged", flagged, "");
        RobotDraft bd = RobotDraft.from(new Model());
        bd.customBoard = true;
        bd.boardPts.addAll(RobotDraft.outlineOf(bd.autoBoard()));
        Model bm = new Model();
        RobotDraft.parse(bd.lines()).applyTo(bm);
        check("an editable board outline goes to the robot", bm.customBoard && bm.cuBoard.size() >= 3
                && Math.abs(bm.sensorBoard().getBounds2D().getWidth() - bd.board().getBounds2D().getWidth()) < 1e-3, bm.cuBoard.size() + " corners");

        // ---- per-sensor height
        Model hm = new Model();
        hm.customLayout = true;
        for (int i = 0; i < hm.nSensors; i++) {
            hm.cuLat[i] = (i - (hm.nSensors - 1) / 2.0) * hm.spacing;
        }
        hm.cuHeight[0] = 8;                                               // the left tip sits 8 mm higher
        RobotDraft hd = RobotDraft.from(hm);
        Model hm2 = new Model();
        RobotDraft.parse(hd.lines()).applyTo(hm2);
        check("each sensor's height goes through the editor draft", Math.abs(hm2.cuHeight[0] - 8) < 1e-9 && hm2.heightOffset(1) == 0, "");
        check("a higher sensor gets less signal", hm.heightGainAt(hm.heightNow() + 8) < 0.7 * hm.heightGainAt(hm.heightNow()), "");

        // ---- the Code tab's interpreter
        CodeRunner crn = new CodeRunner(new Model());
        crn.compile("int a = 7 / 2; float b = 7 / 2.0; int c = -7 / 2; int d = -7 % 3; int e = (int) 3.9;\n"
                + "int f = map(512, 0, 1023, 0, 255); int g = round(-2.5); int h = 0x0F & 6 | 1 << 4;\n"
                + "int later(int x) { return x * 3; }\nint k = 0;\nvoid loop() { static int n = 0; n++; k = later(n) / 2; }\n");
        crn.c.resetGlobals();
        crn.c.run("loop");
        crn.c.run("loop");
        double[] gv = new double[crn.c.globals.size()];
        for (int i = 0; i < gv.length; i++) {
            gv[i] = crn.c.globals.get(i).v[0];
        }
        check("code: C arithmetic (7/2=3, 7/2.0=3.5, -7/2=-3, -7%3=-1, (int)3.9=3)",
                gv[0] == 3 && gv[1] == 3.5 && gv[2] == -3 && gv[3] == -1 && gv[4] == 3, java.util.Arrays.toString(gv));
        check("code: Arduino map / round, bit operators", gv[5] == 127 && gv[6] == -3 && gv[7] == 22, "");
        check("code: static locals and calls before the definition", gv[8] == 3, "k=" + gv[8]);
        check("code: delay() is refused with a hint", !crn.compile("void loop() { delay(10); }") && crn.error.contains("millis"), crn.error);
        check("code: a missing ';' points at its own line", !crn.compile("void loop() {\n int a = 1\n a++;\n}") && crn.errorLine == 2,
                crn.error);
        crn.compile("void loop() { while (1) { } }");
        boolean stopped = false;
        try {
            crn.c.run("loop");
        } catch (MiniC.RunError ex) {
            stopped = true;
        }
        check("code: an endless loop is stopped", stopped, "");
        Model cm1 = new Model(), cm2 = new Model();
        cm1.loadPreset(2);
        cm2.loadPreset(2);
        CodeRunner empty = new CodeRunner(cm2);
        empty.compile("void loop() { }");
        cm2.program = empty;
        cm2.codeMode = 1;
        cm1.reset();
        cm2.reset();
        for (int i = 0; i < 100; i++) {
            cm1.advance(0.02);
            cm2.advance(0.02);
        }
        check("code: Hooks with an empty loop() drives like the built-in controller (same compute time)",
                Math.abs(cm1.x - cm2.x) < 1e-9 && Math.abs(cm1.y - cm2.y) < 1e-9, f(cm1.x) + " / " + f(cm2.x));
        Model cm3 = new Model();
        cm3.loadPreset(9);
        CodeRunner mk = new CodeRunner(cm3);
        mk.compile(CodeExamples.ALL[1][2].replace("#define STOP_AT 2", "#define STOP_AT 1").replace("if (A == 1) {", "if (A == 99) {"));
        cm3.program = mk;
        cm3.codeMode = 1;
        cm3.reset();
        for (int i = 0; i < 400; i++) {
            cm3.advance(0.02);
        }
        check("code: the marker counter stops the robot on the cross bar", cm3.distance < 400 && Math.abs(cm3.vBody) < 5,
                f(cm3.distance) + " mm");
        Model cm4 = new Model();
        cm4.loadPreset(2);
        CodeRunner pid = new CodeRunner(cm4);
        pid.compile(CodeExamples.ALL[2][2]);
        cm4.program = pid;
        cm4.codeMode = 2;
        cm4.reset();
        for (int i = 0; i < 300; i++) {
            cm4.advance(0.02);
        }
        check("code: the PID written out follows the oval", cm4.offTrackTime < 0.05 && cm4.distance > 800, f(cm4.distance) + " mm");
        Model cm5 = new Model();
        cm5.codeMode = 2;
        cm5.codeSource = CodeExamples.ALL[2][2];
        String skc = ArduinoExport.generate(cm5, new ArduinoExport.Options(), "t");
        check("code: the sketch carries the code in its own namespace", skc.contains("#define CODE_MODE 2") && skc.contains("namespace user {")
                && skc.contains("void userLoop()") && skc.contains("float raw[N], dark[N]"), "");
        cm5.codeSource = "void loop() { oops }";
        check("code: code with an error is left out of the sketch", ArduinoExport.generate(cm5, new ArduinoExport.Options(), "t")
                .contains("#define CODE_MODE 0"), "");

        System.out.println();
        System.out.println(pass + " passed, " + fail + " failed");
        System.exit(fail == 0 ? 0 : 1);
    }
}
