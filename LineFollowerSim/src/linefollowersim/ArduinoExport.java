package linefollowersim;

import java.util.Locale;

/**
 * Writes an Arduino sketch that runs the same controller as the simulator. Positions are in mm and
 * speeds in mm/s in the sketch too, so the PID gains are the same numbers as in the PID tab; the only
 * conversion is speed -> PWM through the measured top speed.
 */
final class ArduinoExport {

    /** Choices made in the export dialog. */
    static final class Options {

        String sensorPins = "A0, A1, A2, A3, A4, A5, A6, A7";
        int driver = 0;                       // 0 TB6612FNG, 1 L298N, 2 DRV8833
        String lPwm = "5", lIn1 = "7", lIn2 = "8", rPwm = "6", rIn1 = "9", rIn2 = "10", stby = "4";
        boolean lineReadsLow = true;          // analog: the reading is lower on the black line (most IR modules)
        boolean digitalLineHigh = true;       // digital modules: output HIGH on the line
        boolean spinCalibration = true;       // spin on the spot at start-up and calibrate every sensor
        boolean deadBandComp = false;         // lift small PWM values over the measured dead band
        int startDelayMs = 2000;
        String servoPin = "3";                // car steering servo
        int servoCenter = 90;                 // servo angle with the wheels straight
        boolean servoReversed = false;        // servo turns the wrong way round
        String sPwm = "11", sIn1 = "12", sIn2 = "13";   // DC steering motor (needs its own driver channel)
        boolean digitalPins = false;          // digital sensor modules (digitalRead) instead of analog pins
        boolean log = false;                  // serial CSV log
        String encL = "2", encR = "3";        // encoder channel A pins (interrupt capable)
    }

    static final String[] DRIVERS = {"TB6612FNG (brakes between pulses)", "L298N (EN = PWM, coasts)",
        "DRV8833 (two PWM pins, brakes)"};

    private ArduinoExport() {
    }

    private static String f(double v) {
        return String.format(Locale.ROOT, "%.6g", v);
    }

    /** Sensor pins per row: rows separated by ';', pins by ','. */
    static String[][] pinRows(Options o) {
        String[] rows = o.sensorPins.split(";");
        String[][] r = new String[rows.length][];
        for (int i = 0; i < rows.length; i++) {
            String[] p = rows[i].split(",");
            for (int k = 0; k < p.length; k++) {
                p[k] = p[k].trim();
            }
            r[i] = p;
        }
        return r;
    }

    static int sensorCount(Options o) {
        return pinRows(o)[0].length;
    }

    static int rowCount(Options o) {
        return pinRows(o).length;
    }

    /** What the sketch does differently from the simulator (shown in the sketch and in the export dialog). */
    static java.util.List<String> differences(Model m) {
        java.util.List<String> l = new java.util.ArrayList<>();
        if (m.junctionRule != 0) {
            l.add("At a junction the simulator commits to an 80 degree turn (like a gyro / encoder turn); the sketch only leans to the chosen side.");
        }
        if (m.sensorTask) {
            l.add("The simulator reads the sensors in their own task at " + Math.round(m.sensorTaskHz) + " Hz; the sketch reads them once per loop.");
        }
        if (m.steerMode != 0) {
            l.add("Car steering: the simulator uses the measured speed for the steering angle; the sketch uses the speed it asked for last loop.");
        }
        if (m.encType > 0) {
            l.add("Encoders: the sketch counts both edges of channel A only (half the resolution of x4 counting) and takes the "
                    + "direction from the command.");
        }
        if (m.loopMode != 1) {
            l.add("The simulator's loop period comes from the MCU timing; the sketch waits for a fixed LOOP_US with that value.");
        }
        return l;
    }

    private static String arr(double[][] v, int rows, int n) {
        StringBuilder sb = new StringBuilder("{");
        for (int r = 0; r < rows; r++) {
            sb.append(r > 0 ? ", {" : "{");
            for (int i = 0; i < n; i++) {
                sb.append(i > 0 ? ", " : "").append(String.format(Locale.ROOT, "%.3f", v[r][i]));
            }
            sb.append("}");
        }
        return sb.append("}").toString();
    }

    static String generate(Model m, Options o, String name) {
        String[][] pr = pinRows(o);
        int n = pr[0].length, rows = Math.min(pr.length, Model.MAXR);
        StringBuilder pinList = new StringBuilder("{");
        for (int r = 0; r < rows; r++) {
            pinList.append(r > 0 ? ", {" : "{");
            for (int i = 0; i < n; i++) {
                pinList.append(i > 0 ? ", " : "").append(i < pr[r].length ? pr[r][i] : pr[r][pr[r].length - 1]);
            }
            pinList.append("}");
        }
        pinList.append("}");
        double top = m.maxSpeed;
        double loopUs = m.loopMode == 1 ? 1e6 / Math.max(m.fixedHz, 1) : m.loopPeriodSec() * 1e6;
        double deadL = m.deadL / 100.0 * 255, deadR = m.deadR / 100.0 * 255;
        double[][] w = new double[rows][n], b = new double[rows][n], th = new double[rows][n];
        for (int r = 0; r < rows; r++) {
            for (int i = 0; i < n; i++) {
                int k = Math.min(i, Model.MAXS - 1);
                w[r][i] = m.perSensorCal ? m.calMax[r][k] : m.calWhite;
                b[r][i] = m.perSensorCal ? m.calMin[r][k] : m.calBlack;
                th[r][i] = m.thresholdFor(r, k);
            }
        }
        StringBuilder s = new StringBuilder();
        s.append("// ").append(name).append(".ino - line follower generated by LineFollowerSim\n");
        s.append("//\n");
        s.append("// Same controller as the simulator: line position in mm, speeds in mm/s, so KP, KI and KD below are\n");
        s.append("// the numbers from the PID tab. Speed becomes PWM through TOP_SPEED: MEASURE it on your robot\n");
        s.append("// (full PWM, time a known distance) and put it here - every other number depends on it.\n");
        s.append("//\n");
        s.append("// Sensors are listed LEFT to RIGHT as seen from above with the robot driving away from you;\n");
        s.append("// with more rows, the front row first.\n");
        s.append(String.format(Locale.ROOT, "// Simulated robot: %d sensors x %d row(s), %.1f mm pitch, %.0f mm ahead of the axle, wheel base %.0f mm,%n",
                m.nSensors, m.nRows, m.spacing, m.forward, m.wheelBase));
        s.append(String.format(Locale.ROOT, "// %.0f mm wheels, %s readout, loop %.2f ms, motor driver %s.%n", m.wheelDia,
                m.analogMode ? "analog" : "digital", loopUs / 1000, ControlPanel.DRIVER_NAMES[Math.max(0, Math.min(3, m.driverType))]));
        if (n != m.nSensors) {
            s.append("// NOTE: the pin list has ").append(n).append(" sensors but the simulated robot has ").append(m.nSensors).append(".\n");
        }
        if (rows != m.nRows) {
            s.append("// NOTE: the pin list has ").append(rows).append(" row(s) but the simulated robot has ").append(m.nRows).append(".\n");
        }
        java.util.List<String> diff = differences(m);
        if (!diff.isEmpty()) {
            s.append("//\n// Differences from the simulator:\n");
            for (String d : diff) {
                s.append("//  - ").append(d).append("\n");
            }
        }
        s.append("//\n// ESP8266 has one analog pin (A0): use digital sensor modules or an analog multiplexer.\n");
        boolean car = m.steerMode != 0;
        s.append("\n#include <Arduino.h>\n");
        boolean dcSteer = car && m.steerActuator == 1;
        s.append("#define STEERING ").append(car ? 1 : 0).append("   // 1 = car: the front wheels are steered, both motor channels drive\n");
        s.append("#define STEER_DC ").append(dcSteer ? 1 : 0).append("   // 1 = steered by a DC motor to its end stops (not a servo)\n");
        s.append("#define LOG ").append(o.log ? 1 : 0).append("        // 1 = print a CSV log on the serial port (115200 baud) for File > Import a run log\n");
        s.append("#define LOG_EVERY ").append(Math.max(1, (int) Math.round(10000 / Math.max(loopUs, 1)))).append("  // loops between log lines (about every 10 ms)\n");
        boolean enc = m.encType > 0;
        s.append("#define ENCODERS ").append(enc ? 1 : 0).append("   // wheel encoders on interrupt pins (Motors tab)\n");
        MiniC code = codeProgram(m);
        s.append("#define CODE_MODE ").append(code == null ? 0 : m.codeMode)
                .append("  // 0 built-in controller, 1 built-in + your loop() (hooks), 2 only your code (Code tab)\n");
        s.append("#define FILTER ").append(m.filterMode).append("     // 0 none, 1 moving average, 2 low-pass (Sensors tab)\n");
        s.append("#if STEERING && !STEER_DC\n#if defined(ESP32)\n#include <ESP32Servo.h>   // install \"ESP32Servo\" from the Library Manager\n#else\n"
                + "#include <Servo.h>\n#endif\nServo steering;\n#endif\n\n");
        s.append("// ---------------- your hardware\n");
        s.append("const uint8_t N = ").append(n).append(", ROWS = ").append(rows).append(";\n");
        s.append("const uint8_t SENSOR_PIN[ROWS][N] = ").append(pinList).append(";\n");
        s.append("const bool DIGITAL_PINS = ").append(o.digitalPins).append(";     // true = digital sensor modules (digitalRead)\n");
        s.append("const bool ANALOG_MODE = ").append(m.analogMode && !o.digitalPins).append(";      // true = weighted darkness, false = 0 / 1 per sensor\n");
        s.append("const bool LINE_READS_LOW = ").append(o.lineReadsLow).append(";   // analog: lower reading on the black line\n");
        s.append("const uint8_t DIGITAL_ON_LINE = ").append(o.digitalLineHigh ? "HIGH" : "LOW").append(";   // digital modules: level on the line\n");
        s.append("const float PITCH_MM = ").append(f(m.pitchEff())).append(";      // (average) sensor pitch\n");
        StringBuilder sy = new StringBuilder();
        for (int i = 0; i < n; i++) {
            sy.append(i > 0 ? ", " : "").append(f(i < m.nSensors ? m.lat(i) : (i - (n - 1) / 2.0) * m.pitchEff()));
        }
        s.append("const float SENSOR_Y[N] = {").append(sy).append("};   // mm, + = right of the array centre\n");
        s.append("const float HALF_SPAN = ").append(f(n == m.nSensors ? m.halfSpan() : (n - 1) / 2.0 * m.pitchEff())).append(";\n");
        s.append("const float ROW_SPACING = ").append(f(m.rowSpacing)).append(";      // mm between rows\n");
        s.append("const float WHEEL_BASE = ").append(f(m.wheelBase)).append(";      // mm, left to right wheel centres\n");
        s.append("const float AXLE_SPACING = ").append(f(m.axleSpacing)).append(";    // mm, front to rear axle (car steering)\n");
        s.append("const float MAX_STEER_DEG = ").append(f(m.maxSteerDeg)).append(";\n");
        if (car) {
            s.append("const uint8_t SERVO_PIN = ").append(o.servoPin).append(";\n");
            s.append("const int SERVO_CENTER = ").append(o.servoCenter).append(";      // servo angle with the wheels straight - trim this\n");
            s.append("const int SERVO_DIR = ").append(o.servoReversed ? -1 : 1).append(";          // -1 if the robot steers the wrong way\n");
            if (dcSteer) {
                s.append("const uint8_t S_PWM = ").append(o.sPwm).append(", S_IN1 = ").append(o.sIn1).append(", S_IN2 = ").append(o.sIn2)
                        .append(";   // steering motor channel\n");
            }
        }
        s.append("const float TOP_SPEED = ").append(f(top)).append(";      // mm/s at PWM 255 - MEASURE THIS\n");
        s.append("const float TRIM_L = ").append(f(100.0 / Math.max(m.gainL, 1))).append(", TRIM_R = ")
                .append(f(100.0 / Math.max(m.gainR, 1))).append(";   // evens out mismatched motors (from the speed factors)\n");
        s.append("const bool DEAD_BAND_COMP = ").append(o.deadBandComp).append(";\n");
        s.append("const int DEAD_PWM_L = ").append(Math.round(deadL)).append(", DEAD_PWM_R = ").append(Math.round(deadR))
                .append(";   // lowest PWM that turns each wheel\n\n");
        s.append("// motor driver: ").append(DRIVERS[o.driver]).append("\n");
        if (o.driver == 2) {
            s.append("const uint8_t L_IN1 = ").append(o.lIn1).append(", L_IN2 = ").append(o.lIn2).append(";   // both PWM pins\n");
            s.append("const uint8_t R_IN1 = ").append(o.rIn1).append(", R_IN2 = ").append(o.rIn2).append(";\n");
        } else {
            s.append("const uint8_t L_PWM = ").append(o.lPwm).append(", L_IN1 = ").append(o.lIn1).append(", L_IN2 = ").append(o.lIn2).append(";\n");
            s.append("const uint8_t R_PWM = ").append(o.rPwm).append(", R_IN1 = ").append(o.rIn1).append(", R_IN2 = ").append(o.rIn2).append(";\n");
        }
        if (o.driver == 0) {
            s.append("const uint8_t STBY = ").append(o.stby).append(";\n");
        }
        s.append("\n// ---------------- controller settings (copied from the simulator)\n");
        s.append("const float KP = ").append(f(m.kp)).append(", KI = ").append(f(m.ki)).append(", KD = ").append(f(m.kd))
                .append(";   // KP 1/s, KI 1/s2, KD no unit (error mm -> speed mm/s)\n");
        s.append("const float D_FILTER_S = ").append(f(m.dFilterMs / 1000)).append(";      // derivative low-pass\n");
        s.append("const float PREDICT_S = ").append(f(m.predictMs / 1000)).append(";       // P acts on error + rate x this\n");
        s.append("const float THRESHOLD = ").append(f(m.threshold)).append(";     // the threshold set in the simulator (0-1023)\n");
        s.append("const float BASE_SPEED = ").append(f(m.baseSpeed)).append(";     // mm/s\n");
        s.append("const float CORNER_SLOW = ").append(f(m.cornerSlow)).append(";    // speed drops by this share at full error\n");
        s.append("const bool ALLOW_REVERSE = ").append(m.allowReverse).append(";\n");
        s.append("const int ERROR_MODE = ").append(m.errorMode).append(";         // 0 weighted average (mm), 1 raw weighted sum\n");
        s.append("const int JUNCTION = ").append(m.junctionRule).append(";           // 0 ignore, 1 prefer left, 2 prefer right\n");
        s.append("const float PREDICT_AHEAD_MM = ").append(f(m.predictAheadMm)).append(";  // more rows: extrapolate the line this far ahead\n");
        s.append("const int LOST_MODE = ").append(m.lostMode).append(";          // 0 smart, 1 hold, 2 steer hard, 3 straight, 4 spin, 5 stop\n");
        s.append("const float EDGE_ZONE = ").append(f(m.edgeZonePct / 100)).append(";      // smart: lost beyond this share of the half array\n");
        s.append("const float SEARCH_SPEED = ").append(f(m.searchSpeed)).append(";    // spin search, mm/s\n");
        s.append("const float LOST_TIMEOUT_S = ").append(f(m.lostTimeout)).append(";   // stop after this long without the line (0 = never)\n");
        s.append("const int CONFIRM = ").append(Math.max(1, m.confirmCount)).append(";            // loops the line must be seen before following again\n");
        s.append("const bool NO_D_KICK = ").append(m.noDKick).append(";\n");
        s.append("const int FILTER_N = ").append(Math.max(1, Math.min(m.filterN, 16))).append(";           // moving-average length\n");
        s.append("const float FILTER_TAU_S = ").append(f(m.filterMode == 2 ? Math.max(m.filterTauMs, 0) / 1000 : 0)).append(";   // low-pass time constant\n");
        s.append("const float HYST_PCT = ").append(f(m.analogMode ? 0 : m.hystPct)).append(";   // digital: hysteresis, % of the white-black span\n");
        s.append("const float HYST_LEVEL = ").append(f(m.analogMode ? 0 : m.hystPct / 100.0 * Math.abs(m.calWhite - m.calBlack) / 2))
                .append(";\n");
        if (enc) {
            double cpr = m.encType == 2 ? m.encPpr * 2 : m.encType == 4 ? m.encCustomCpr : m.encPpr * m.encGear * 2;
            s.append("const uint8_t ENC_L = ").append(o.encL).append(", ENC_R = ").append(o.encR).append(";   // channel A of each encoder\n");
            s.append("const float MM_PER_COUNT = ").append(f(m.wheelCircumference() / Math.max(cpr, 1)))
                    .append(";   // both edges of one channel: ").append(Math.round(cpr)).append(" counts per wheel turn\n");
            s.append("const bool SPEED_LOOP = ").append(m.speedLoop).append(";\n");
            s.append("const float SPEED_KP = ").append(f(m.speedKp)).append(", SPEED_KI = ").append(f(m.speedKi)).append(";\n");
        }
        s.append("const unsigned long LOOP_US = ").append(Math.max(200, Math.round(loopUs))).append(";   // fixed loop period\n\n");
        s.append("// calibration from the simulator (replaced by the start-up spin if it is on): white, black, threshold\n");
        s.append("const float SIM_WHITE[ROWS][N] = ").append(arr(w, rows, n)).append(";\n");
        s.append("const float SIM_BLACK[ROWS][N] = ").append(arr(b, rows, n)).append(";\n");
        s.append("const float SIM_THR[ROWS][N] = ").append(arr(th, rows, n)).append(";\n\n");
        s.append(body().replace("%DRIVER%", driverCode(o.driver)).replace("%SPIN%", Boolean.toString(o.spinCalibration))
                .replace("%START%", Integer.toString(o.startDelayMs)).replace("%CODE%", codeSection(m, code)));
        return s.toString();
    }

    /** The Code tab's program if the export should carry it (code mode on and it compiles), else null. */
    static MiniC codeProgram(Model m) {
        if (m.codeMode == 0 || m.codeSource == null || m.codeSource.trim().isEmpty()) {
            return null;
        }
        CodeRunner r = new CodeRunner(new Model());
        return r.compile(m.codeSource) ? r.c : null;
    }

    /** The C++ for the code mode: the same functions the simulator gives the code, then the code itself. */
    static String codeSection(Model m, MiniC code) {
        if (code == null) {
            return "";
        }
        StringBuilder s = new StringBuilder();
        s.append("// ---------------- what your code can use (the same names as on the Code tab)\n");
        s.append("float raw[N], dark[N];           // front row: reading after the filter (0-1023), darkness 0..1\n");
        s.append("float lfsDt = 0, lfsDist = 0;\n");
        s.append("bool lfsFollowed = false, lfsHalted = false, lfsPlotted = false;\n");
        s.append("unsigned long lfsLoops = 0;\n");
        s.append("const unsigned long PLOT_EVERY = ").append(Math.max(1, (int) Math.round(20000 / Math.max(m.loopPeriodSec() * 1e6, 1))))
                .append(";   // plot() every this many loops (about 50 per second, for the Serial Plotter)\n\n");
        s.append("int isDark(int i) { return i >= 0 && i < N && sDark[0][i] >= 0.5f; }\n");
        s.append("int countDark() { int c = 0; for (uint8_t i = 0; i < N; i++) if (sDark[0][i] >= 0.5f) c++; return c; }\n");
        s.append("int allDark() { return countDark() == N; }\n");
        s.append("int anyDark() { return countDark() > 0; }\n");
        s.append("float linePosition() { return rowPosition(0); }   // mm, + = right; NAN when no sensor sees the line\n");
        s.append("int lineSeen() { return !isnan(rowPosition(0)); }\n");
        s.append("float sensorY(int i) { return i >= 0 && i < N ? SENSOR_Y[i] : 0; }\n");
        s.append("float loopTime() { return lfsDt; }\n");
        s.append("float speed() { return vBody; }\n");
        s.append("float distance() { return lfsDist; }\n");
        s.append("int laps() { return 0; }                          // a real robot has no lap gate: count markers instead\n");
        s.append("float leftCmd() { return outL; }\n");
        s.append("float rightCmd() { return outR; }\n");
        s.append("void drive(float l, float r) { outL = clampf(l, -TOP_SPEED, TOP_SPEED); outR = clampf(r, -TOP_SPEED, TOP_SPEED); }\n");
        s.append("void steer(float deg) { float lk = clampf(MAX_STEER_DEG, 1, 60); steerDeg = clampf(deg, -lk, lk); }\n");
        s.append("void stop() { outL = outR = 0; }\n");
        s.append("void followLine() { if (!lfsFollowed) { controlStep(lfsDt); lfsFollowed = true; } }\n");
        s.append("void pause() { lfsHalted = true; outL = outR = 0; }   // the robot stops until it is reset\n");
        s.append("void lfsOut(const char* t) { Serial.print(t); }\n");
        s.append("void lfsOut(char c) { Serial.print(c); }\n");
        s.append("void lfsOut(float v) { Serial.print(v, 2); }\n");
        s.append("void lfsOut(double v) { Serial.print(v, 2); }\n");
        s.append("template <class T> void lfsOut(T v) { Serial.print((long) v); }\n");
        s.append("void print() { Serial.println(); }\n");
        s.append("template <class T, class... R> void print(T a, R... r) { lfsOut(a); if (sizeof...(r) > 0) Serial.print(' '); print(r...); }\n");
        s.append("template <class... R> void println(R... r) { print(r...); }\n");
        s.append("void plot(const char* name, float v) {            // Serial Plotter: name:value pairs, one line per loop\n");
        s.append("  if (lfsLoops % PLOT_EVERY != 0) return;\n");
        s.append("  if (lfsPlotted) Serial.print(',');\n");
        s.append("  for (const char* p = name; *p; p++) Serial.print(*p == ' ' || *p == ':' || *p == ',' ? '_' : *p);\n");
        s.append("  Serial.print(':');\n");
        s.append("  Serial.print(v, 3);\n");
        s.append("  lfsPlotted = true;\n");
        s.append("}\n\n");
        // the user's code: #include lines outside, the rest in a namespace so its names cannot clash with the sketch's
        String src = m.codeSource.replace("\r\n", "\n");
        StringBuilder inc = new StringBuilder(), rest = new StringBuilder();
        for (String l : src.split("\n", -1)) {
            (l.trim().startsWith("#include") ? inc : rest).append(l).append('\n');
        }
        String body = rename(rest.toString());
        s.append("// ---------------- your code (Code tab, ").append(m.codeMode == 1 ? "Hooks: the built-in controller runs first" : "Full code")
                .append(")\n");
        s.append(inc);
        s.append("namespace user {\n");
        for (String p : code.prototypes()) {
            s.append(rename(p)).append('\n');
        }
        if (!code.hasFunction("setup")) {
            s.append("void userSetup() { }\n");
        }
        s.append("\n").append(body.trim()).append("\n");
        s.append("}  // namespace user\n\n");
        s.append("void codeStep(float dt) {\n");
        s.append("  for (uint8_t i = 0; i < N; i++) { raw[i] = filt[0][i]; dark[i] = sDark[0][i]; }\n");
        s.append("  lfsDt = dt;\n");
        s.append("  lfsDist += fabs(vBody) * dt;\n");
        s.append("  lfsLoops++;\n");
        if (m.codeMode == 1) {
            s.append("  controlStep(dt);                               // hooks: the built-in controller first\n");
            s.append("  lfsFollowed = true;\n");
        } else {
            s.append("  outL = outR = 0;                               // full code: nothing moves unless the code says so\n");
            s.append("  steerDeg = 0;\n");
            s.append("  lfsFollowed = false;\n");
        }
        s.append("  if (lfsHalted) { outL = outR = 0; steerDeg = 0; return; }\n");
        s.append("  user::userLoop();\n");
        s.append("  if (lfsPlotted) { Serial.println(); lfsPlotted = false; }\n");
        s.append("}\n\n");
        return s.toString();
    }

    private static String rename(String c) {
        return c.replaceAll("\\bsetup\\s*\\(", "userSetup(").replaceAll("\\bloop\\s*\\(", "userLoop(");
    }

    private static String body() {
        try (java.io.InputStream in = ArduinoExport.class.getResourceAsStream("sketch_body.txt")) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            for (int k; (k = in.read(buf)) > 0;) {
                bo.write(buf, 0, k);
            }
            return new String(bo.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (java.io.IOException | NullPointerException ex) {
            throw new IllegalStateException("sketch_body.txt is missing from the program", ex);
        }
    }

    private static String driverCode(int d) {
        switch (d) {
            case 1:   // L298N: direction on IN1/IN2, speed as PWM on EN -> the motor coasts between pulses
                return "void driverBegin() {\n"
                        + "  pinMode(L_PWM, OUTPUT); pinMode(L_IN1, OUTPUT); pinMode(L_IN2, OUTPUT);\n"
                        + "  pinMode(R_PWM, OUTPUT); pinMode(R_IN1, OUTPUT); pinMode(R_IN2, OUTPUT);\n"
                        + "}\n\n"
                        + "void motor(uint8_t pwm, uint8_t in1, uint8_t in2, int v) {\n"
                        + "  digitalWrite(in1, v >= 0 ? HIGH : LOW);\n"
                        + "  digitalWrite(in2, v >= 0 ? LOW : HIGH);\n"
                        + "  analogWrite(pwm, abs(v));\n"
                        + "}\n\n"
                        + "void setMotors(int l, int r) {\n"
                        + "  motor(L_PWM, L_IN1, L_IN2, l);\n"
                        + "  motor(R_PWM, R_IN1, R_IN2, r);\n"
                        + "}\n";
            case 2:   // DRV8833: hold one input HIGH and PWM the other inverted -> slow decay (brakes)
                return "void driverBegin() {\n"
                        + "  pinMode(L_IN1, OUTPUT); pinMode(L_IN2, OUTPUT); pinMode(R_IN1, OUTPUT); pinMode(R_IN2, OUTPUT);\n"
                        + "}\n\n"
                        + "void motor(uint8_t in1, uint8_t in2, int v) {\n"
                        + "  int p = 255 - abs(v);              // slow decay: one pin HIGH, the other PWM inverted\n"
                        + "  if (v >= 0) { digitalWrite(in1, HIGH); analogWrite(in2, p); }\n"
                        + "  else        { analogWrite(in1, p); digitalWrite(in2, HIGH); }\n"
                        + "}\n\n"
                        + "void setMotors(int l, int r) {\n"
                        + "  motor(L_IN1, L_IN2, l);\n"
                        + "  motor(R_IN1, R_IN2, r);\n"
                        + "}\n";
            default:  // TB6612FNG: PWM on PWMx, direction on INx -> short brake between pulses
                return "void driverBegin() {\n"
                        + "  pinMode(L_PWM, OUTPUT); pinMode(L_IN1, OUTPUT); pinMode(L_IN2, OUTPUT);\n"
                        + "  pinMode(R_PWM, OUTPUT); pinMode(R_IN1, OUTPUT); pinMode(R_IN2, OUTPUT);\n"
                        + "  pinMode(STBY, OUTPUT); digitalWrite(STBY, HIGH);\n"
                        + "}\n\n"
                        + "void motor(uint8_t pwm, uint8_t in1, uint8_t in2, int v) {\n"
                        + "  digitalWrite(in1, v >= 0 ? HIGH : LOW);\n"
                        + "  digitalWrite(in2, v >= 0 ? LOW : HIGH);\n"
                        + "  analogWrite(pwm, abs(v));\n"
                        + "}\n\n"
                        + "void setMotors(int l, int r) {\n"
                        + "  motor(L_PWM, L_IN1, L_IN2, l);\n"
                        + "  motor(R_PWM, R_IN1, R_IN2, r);\n"
                        + "}\n";
        }
    }

}
