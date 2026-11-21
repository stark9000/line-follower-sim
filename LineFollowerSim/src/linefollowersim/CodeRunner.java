package linefollowersim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Runs the Code tab's program in the simulator: gives MiniC the robot's sensors, the UI values as live
 * constants, and drive() / steer() / followLine() for the wheels. Keeps the console, the plots and the
 * error. No Swing in here (the Code tab shows what this collects).
 */
final class CodeRunner implements Model.Program {

    static final int MAX_LINES = 600, MAX_POINTS = 3000, MAX_SERIES = 8;

    final MiniC c = new MiniC();
    private final Model m;
    String source = "";
    boolean compiled;
    String error;                     // compile or run error (null = none)
    int errorLine = -1;
    long lastSteps, maxSteps, loops;
    private double cdt;
    private Random rnd = new Random(1);

    final List<String> console = new ArrayList<>();
    int consoleVersion;
    private int printsThisLoop;

    static final class Series {

        final String name;
        final double[] t = new double[MAX_POINTS], v = new double[MAX_POINTS];
        int head, count;
        double last;

        Series(String name) {
            this.name = name;
        }

        void add(double tt, double vv) {
            t[head] = tt;
            v[head] = vv;
            head = (head + 1) % MAX_POINTS;
            count = Math.min(count + 1, MAX_POINTS);
            last = vv;
        }

        double tAt(int k) {                        // k = 0 oldest
            return t[(head - count + k + MAX_POINTS) % MAX_POINTS];
        }

        double vAt(int k) {
            return v[(head - count + k + MAX_POINTS) % MAX_POINTS];
        }
    }

    final Map<String, Series> plots = new LinkedHashMap<>();

    CodeRunner(Model m) {
        this.m = m;
        c.printer = this::out;
        c.plotter = (name, v) -> {
            Series s = plots.get(name);
            if (s == null) {
                if (plots.size() >= MAX_SERIES) {
                    return;
                }
                s = new Series(name);
                plots.put(name, s);
            }
            s.add(m.t, v);
        };
        // ---- the robot
        c.constant("N", true, () -> m.nSensors);
        c.constant("ROWS", true, () -> m.nRows);
        c.arrays.put("raw", i -> m.codeRaw(i));
        c.arrays.put("dark", i -> m.codeDark(i));
        fn("isDark", 1, 1, true, a -> m.codeDark((int) a[0]) >= 0.5 ? 1 : 0);
        fn("countDark", 0, 0, true, a -> countDark());
        fn("allDark", 0, 0, true, a -> m.nSensors > 0 && countDark() == m.nSensors ? 1 : 0);
        fn("anyDark", 0, 0, true, a -> countDark() > 0 ? 1 : 0);
        fn("linePosition", 0, 0, false, a -> m.codeLinePosition());
        fn("lineSeen", 0, 0, true, a -> Double.isNaN(m.codeLinePosition()) ? 0 : 1);
        fn("sensorY", 1, 1, false, a -> (int) a[0] >= 0 && (int) a[0] < m.nSensors ? m.lat((int) a[0]) : 0);
        fn("millis", 0, 0, true, a -> Math.floor(m.t * 1000));
        fn("micros", 0, 0, true, a -> Math.floor(m.t * 1e6));
        fn("loopTime", 0, 0, false, a -> cdt);
        fn("speed", 0, 0, false, a -> m.vBody);
        fn("distance", 0, 0, false, a -> m.distance);
        fn("laps", 0, 0, true, a -> m.laps.size());
        fn("drive", 2, 2, true, a -> {
            m.codeDrive(a[0], a[1]);
            return 0;
        });
        fn("steer", 1, 1, true, a -> {
            m.codeSteer(a[0]);
            return 0;
        });
        fn("stop", 0, 0, true, a -> {
            m.codeDrive(0, 0);
            return 0;
        });
        fn("followLine", 0, 0, true, a -> {
            m.codeFollowLine();
            return 0;
        });
        fn("leftCmd", 0, 0, false, a -> m.codePendL());
        fn("rightCmd", 0, 0, false, a -> m.codePendR());
        fn("pause", 0, 0, true, a -> {
            m.running = false;
            out("-- paused by the code --");
            return 0;
        });
        // ---- the UI values (read every time: move a slider and the code sees it)
        c.constant("KP", false, () -> m.kp);
        c.constant("KI", false, () -> m.ki);
        c.constant("KD", false, () -> m.kd);
        c.constant("BASE_SPEED", false, () -> m.baseSpeed);
        c.constant("TOP_SPEED", false, () -> m.maxSpeed);
        c.constant("CORNER_SLOW", false, () -> m.cornerSlow);
        c.constant("PITCH_MM", false, () -> m.pitchEff());
        c.constant("HALF_SPAN", false, () -> m.halfSpan());
        c.constant("THRESHOLD", false, () -> m.threshold);
        c.constant("WHEEL_BASE", false, () -> m.wheelBase);
        // ---- math (Arduino names)
        fn("abs", 1, 1, false, a -> Math.abs(a[0]));
        fn("fabs", 1, 1, false, a -> Math.abs(a[0]));
        fn("min", 2, 2, false, a -> Math.min(a[0], a[1]));
        fn("max", 2, 2, false, a -> Math.max(a[0], a[1]));
        fn("constrain", 3, 3, false, a -> Math.max(a[1], Math.min(a[2], a[0])));
        fn("map", 5, 5, true, a -> {                     // whole numbers, as Arduino's map()
            long x = (long) a[0], il = (long) a[1], ih = (long) a[2], ol = (long) a[3], oh = (long) a[4];
            if (ih == il) {
                throw new IllegalArgumentException("in_min == in_max");
            }
            return (x - il) * (oh - ol) / (ih - il) + ol;
        });
        fn("sq", 1, 1, false, a -> a[0] * a[0]);
        fn("sqrt", 1, 1, false, a -> Math.sqrt(a[0]));
        fn("pow", 2, 2, false, a -> Math.pow(a[0], a[1]));
        fn("sin", 1, 1, false, a -> Math.sin(a[0]));
        fn("cos", 1, 1, false, a -> Math.cos(a[0]));
        fn("tan", 1, 1, false, a -> Math.tan(a[0]));
        fn("atan", 1, 1, false, a -> Math.atan(a[0]));
        fn("atan2", 2, 2, false, a -> Math.atan2(a[0], a[1]));
        fn("exp", 1, 1, false, a -> Math.exp(a[0]));
        fn("log", 1, 1, false, a -> Math.log(a[0]));
        fn("fmod", 2, 2, false, a -> a[0] % a[1]);
        fn("round", 1, 1, true, a -> a[0] >= 0 ? Math.floor(a[0] + 0.5) : -Math.floor(-a[0] + 0.5));   // as Arduino's round()
        fn("floor", 1, 1, false, a -> Math.floor(a[0]));
        fn("ceil", 1, 1, false, a -> Math.ceil(a[0]));
        fn("isnan", 1, 1, true, a -> Double.isNaN(a[0]) ? 1 : 0);
        fn("random", 1, 2, true, a -> {
            long lo = a.length == 2 ? (long) a[0] : 0, hi = (long) a[a.length - 1];
            return hi <= lo ? lo : lo + (long) (rnd.nextDouble() * (hi - lo));
        });
    }

    private void fn(String name, int min, int max, boolean isInt, MiniC.Fn f) {
        c.builtins.put(name, new MiniC.Builtin(min, max, isInt, f));
    }

    private int countDark() {
        int n = 0;
        for (int i = 0; i < m.nSensors; i++) {
            if (m.codeDark(i) >= 0.5) {
                n++;
            }
        }
        return n;
    }

    private void out(String s) {
        if (++printsThisLoop > 20) {
            return;                                    // a print in a loop of a loop: keep the console usable
        }
        console.add(String.format(Locale.ROOT, "%8.3f  %s", m.t, s));
        if (console.size() > MAX_LINES) {
            console.subList(0, console.size() - MAX_LINES).clear();
        }
        consoleVersion++;
    }

    void clearConsole() {
        console.clear();
        consoleVersion++;
    }

    /** Compile new text; true if it is fine. The robot keeps the old program when the new one has an error. */
    boolean compile(String src) {
        source = src;
        try {
            c.compile(src);
            compiled = true;
            error = null;
            errorLine = -1;
            if (!c.hasFunction("loop")) {
                error = "there is no void loop() { ... }";
                errorLine = 1;
                compiled = false;
            }
        } catch (MiniC.CompileError ex) {
            compiled = false;
            error = ex.getMessage();
            errorLine = ex.line;
        }
        return compiled;
    }

    @Override
    public void reset(Model mm) {
        loops = 0;
        maxSteps = 0;
        plots.clear();
        rnd = new Random(1234 + mm.noiseSeed);
        if (!compiled) {
            return;
        }
        if (error != null && errorLine >= 0 && error.startsWith("run")) {
            error = null;
        }
        runError = false;
        try {
            printsThisLoop = 0;
            c.resetGlobals();
            c.run("setup");
        } catch (MiniC.RunError ex) {
            fail(ex);
        } catch (MiniC.Abort ex) {
            // left by the debugger (Restart while stopped)
        }
    }

    boolean runError;

    private void fail(MiniC.RunError ex) {
        runError = true;
        error = "run: " + ex.getMessage();
        errorLine = ex.line;
        out("ERROR " + ex.getMessage());
        m.codeDrive(0, 0);
        m.running = false;
    }

    @Override
    public long loop(Model mm, double dt) {
        if (!compiled || runError) {
            return 0;
        }
        cdt = dt;
        printsThisLoop = 0;
        try {
            lastSteps = c.run("loop");
        } catch (MiniC.RunError ex) {
            lastSteps = c.steps;
            fail(ex);
        } catch (MiniC.Abort ex) {
            lastSteps = c.steps;                                          // left by the debugger: the loop ends here
        }
        maxSteps = Math.max(maxSteps, lastSteps);
        loops++;
        return lastSteps;
    }

    /** The program's globals as "name = value" lines (arrays: the first values). */
    List<String[]> watch() {
        List<String[]> o = new ArrayList<>();
        for (MiniC.Global g : c.globals) {
            if (g.name.indexOf('$') >= 0) {                              // a static local: show as function.name
                o.add(new String[]{g.name.replace('$', '.'), value(g)});
            } else {
                o.add(new String[]{g.name, value(g)});
            }
        }
        return o;
    }

    private static String value(MiniC.Global g) {
        if (!g.isArray) {
            return one(g, g.v[0]);
        }
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < g.v.length && i < 12; i++) {
            sb.append(i > 0 ? ", " : "").append(one(g, g.v[i]));
        }
        return sb.append(g.v.length > 12 ? ", ...}" : "}").toString();
    }

    private static String one(MiniC.Global g, double v) {
        if (g.type == MiniC.T_FLOAT) {
            return MiniC.fmt(v);
        }
        if (g.type == MiniC.T_BOOL) {
            return v != 0 ? "true" : "false";
        }
        return Long.toString((long) v);
    }
}
