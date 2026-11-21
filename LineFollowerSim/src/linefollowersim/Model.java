package linefollowersim;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Random;

/**
 * Physics + sensors + controller + microcontroller timing. No Swing
 * components, so it can run headless.
 *
 * Units: 1 pixel = 1 mm. Differential-drive robot. Sensors read pixels of the
 * track image.
 *
 * Sensor model (per sensor): v = irPower * (0.05 + 0.95 * reflectance) * gain_i
 * + 0.9 * ambient + offset_i + noise; raw = clamp(v, 0, 1) * 1023 (10-bit ADC,
 * white = high, black = low). Digital mode: a sensor "sees line" when raw <
 * threshold. Analog mode: darkness = (calWhite - raw) / (calWhite - calBlack).
 *
 * Controller loop timing: the loop takes (sensors * readCost + mathCost) CPU
 * cycles at the chosen clock, plus an optional delay(). Commands only reach
 * the motors after the compute time has passed.
 *
 * Motors: each wheel has its own speed factor, lag and dead zone.
 */
public class Model {

    public static final int W = 800, H = 480, HIST = 1000, MAXS = 32, MAXR = 3, TRAIL = 6000, MAXL = 2 * MAXS;
    public static final double DT = 0.0005;                  // default (largest) physics step: 0.5 ms, see stepSec()

    // The world is measured in mm. The track image has its own resolution: res = mm per image pixel.
    public BufferedImage track;
    int[] pix;
    int imgW, imgH;
    public double arenaW = W, arenaH = H, res = 1.0;         // arena size (mm), mm per track-image pixel

    /** The track is a list of shapes (mm), drawn into the image above. */
    public List<TrackShape> shapes = new ArrayList<>();
    private final List<Runnable> trackListeners = new ArrayList<>();

    // ---- simulation control (used by the UI)
    public boolean running = true;
    public double speedMul = 1.0;

    // ---- environment
    public double lineWidth = 20;
    public double ambient = 0.15;
    public int ambientMode = 0;                              // 0 uniform, 1 gradient, 2 hotspot
    public double flickerPct = 0;                            // room lamps pulse with the mains: depth of the ripple, %
    public double flickerHz = 100;                           // 100 Hz on 50 Hz mains, 120 Hz on 60 Hz
    public int presetIdx = 0;
    public int startDir = 0;                                 // 0 the way the preset is drawn, 1 the other way round
    public Boolean presetClockwise;                          // loops: direction of the normal start (null = not a loop)
    public boolean edited = false;

    // ---- robot
    public double baseSpeed = 200, wheelBase = 100;
    public double maxSpeed;                                  // derived from the hardware spec, see applyHardware()
    public boolean allowReverse = false;

    // ---- hardware spec (real-world numbers)
    public double wheelDia = 65;                             // mm
    public double tireWidth = 10;                            // mm (drawing only: the wheel base is centre to centre)
    public double motorRpm = 200;                            // no-load RPM at the wheel shaft, at motorVolt
    public double motorVolt = 6, battVolt = 6;               // rated voltage of the motor / supply voltage
    public double loadPct = 75;                              // % of no-load speed reached when driving
    public double topSpeedOverride = 0;                      // mm/s, measured; 0 = calculate from the spec
    public double sensorHeight = 10, beamAngle = 22;         // mm above floor, beam half-angle in degrees

    // ---- drive model (0 = simple: each wheel follows its command with a lag; 1 = physical)
    public int driveModel = 0;
    public double massG = 350;                               // whole robot with battery, g
    public int wheelCount = 2;                               // 2 = two driven wheels + caster, 4 = skid steer (two motors per side)
    public int steerMode = 0;                                // 0 differential / skid, 1 car: front steering + rear drive, 2 car: front steering + all-wheel drive
    public double maxSteerDeg = 30;                          // car: steering lock either way, degrees
    public double servoRate = 300;                           // car: how fast the steering servo turns, degrees per second
    public int steerActuator = 0;                            // car: 0 servo (any angle), 1 DC motor to the end stops (left / centre / right)
    public double steerDelayMs = 15;                         // car: delay before the steering starts to move (servo frame, motor start)
    public double steerAngle;                                // car: front wheel angle now, rad (+ = turning right)
    double trackRadiusHint = Double.NaN;                     // tightest bend of the track (set by the Analysis tab for its checks)
    private double pendSteer, cmdSteer, steerGoal;
    private final java.util.ArrayDeque<double[]> steerQueue = new java.util.ArrayDeque<>();   // {time it takes effect, goal}
    public double axleSpacing = 80;                          // 4 wheels: front axle to rear axle, mm (the robot's axle = half-way)
    public double skidFactor = 0.5;                          // 4 wheels: how much the sideways scrub widens the turn (simple model)
    public double sideFriction = 0.6;                        // 4 wheels: tyre friction when scrubbing sideways (physical model)
    public double stallTorque = 0.8;                         // per motor at the wheel shaft, at the rated voltage, kg.cm
    public double driveWeightPct = 70;                       // share of the weight on the two driven wheels (rest on the caster)
    public double gearFrictionPct = 6;                       // friction inside motor + gearbox, % of the stall torque
    public double casterCrr = 0.05;                          // rolling resistance of the caster / skid (0.02 ball on paper ... 0.3 skid)
    public int driverType = 0;                               // 0 ideal, 1 TB6612FNG, 2 DRV8833, 3 L298N (sets the drop and the decay)
    public double driverDrop = 0;                            // volts lost in the motor driver at running current
    public int decayMode = 0;                                // H-bridge between PWM pulses: 0 brake (slow decay), 1 coast (fast decay)
    public double backlashMs = 0;                            // gearbox play: no drive for this long after a wheel reverses
    private double pV, pW;                                   // physical model: chassis speed (mm/s) and turn rate (rad/s)
    private final int[] blSign = new int[2];
    private final double[] blUntil = new double[2];

    // ---- sensor array shape and chassis shape (defaults: a straight array on the old rounded-box chassis)
    public int arrayShape = 0;                               // 0 straight, 1 V / arrow, 2 arc
    public double arrayAngle = 20;                           // V: degrees the wings are swept (+ = outer sensors further forward)
    public double arrayRadius = 120;                         // arc radius, mm
    public int chassisShape = 0;                             // 0 rectangle, 1 round, 2 T (body + arm), 3 custom outline
    public double bodyWidth = 0, bodyFront = 0, bodyRear = 0;   // mm, 0 = automatic
    public double armWidth = 24, roundDia = 0;               // T-arm width, round plate diameter (0 = automatic), mm
    public final List<double[]> customOutline = new ArrayList<>();   // robot frame points (x forward, y right), mm
    public double casterPos = 0;                             // caster x (mm, + = ahead of the axle), 0 = automatic (at the back)
    public double boardMargin = 5;                           // sensor board edge beyond the sensors, mm

    // ---- motors (independent left / right)
    public double gainL = 100, gainR = 100;                  // speed factor, % of commanded
    public double lagL = 40, lagR = 40;                      // time constant, ms
    public double deadL = 0, deadR = 0;                      // dead zone, % of max speed

    // ---- microcontroller
    public double clockMHz = McuProfile.ALL[0].mhz;
    public double adcCycles = McuProfile.ALL[0].adcCycles;
    public double digCycles = McuProfile.ALL[0].digCycles;
    public double mathCycles = McuProfile.ALL[0].mathCycles;
    public double loopDelayMs = 4;

    // ---- sensors
    public int nSensors = 8;
    public double spacing = 10, forward = 60, spotRadius = 4, irPower = 0.8, noise = 0.01, mismatch = 0.1;
    public double threshold = 512, calWhite = 1023, calBlack = 0;
    public boolean analogMode = false;                       // what the controller uses
    public int readout = 0;                                  // HUD: 0 auto, 1 analog, 2 digital, 3 both
    final double[] mmGain = new double[MAXS * MAXR], mmOff = new double[MAXS * MAXR];   // [row * MAXS + sensor]

    // ---- PID
    public double kp = 5, ki = 0, kd = 0.5, dFilterMs = 20, cornerSlow = 0.5;
    public double predictMs = 0;                             // predictive term: P acts on error + d(error)/dt * T
    public int errorMode = 0;                                // 0 normalised weighted average (mm), 1 raw weighted sum
    public int junctionRule = 0;                             // 0 ignore, 1 prefer the left branch, 2 prefer the right
    public double predictAheadMm = 0;                        // multi-row: extrapolate the line this far beyond the front row

    // ---- repeatability: the noise of every run starts from this seed (unless every run should differ)
    public int noiseSeed = 1;
    public boolean varyNoise = false;

    // ---- line lost: what the robot does when no sensor sees the line
    public int lostMode = 0;                                 // 0 smart (edge: steer that way, centre: straight on), 1 hold the last error,
                                                             // 2 steer hard to the last side, 3 straight on, 4 spin towards the last side, 5 stop
    public double edgeZonePct = 60;                          // smart: lost beyond this share of the half array = "left at the edge"
    public double searchSpeed = 80;                          // spin search: wheel speed, mm/s
    public double lostTimeout = 0;                           // stop after this long without the line, s (0 = never)
    public int confirmCount = 1;                             // seen this many loops in a row before following again
    public boolean noDKick = true;                          // no derivative spike when the line is lost or found again
    private double lostSince;
    private int seenCount;
    private boolean recoverSpin, recoverStop;

    // ---- sensor realism and processing (every default is neutral: no effect)
    public double crosstalkPct = 0;                          // optical crosstalk to the neighbour, % (at 10 mm pitch)
    public boolean barriers = true;                          // light barriers between sensors: crosstalk x 0.1
    // ---- more real-world effects (all off by default)
    public double bounceMmPerG = 0;                          // the sensors rise by this much per 1 g of forward acceleration (nose up)
    public double glossPct = 0;                              // shiny tape: share of the contrast lost to glare (and sparkle)
    public double respMs = 0;                                // sensor response time (phototransistor + load resistor)
    public double motorWarmPct = 0;                          // motors lose this much speed per minute at full load (warming up)
    public double gripLossPctPerMin = 0;                     // dust on the tyres: grip falls by this much per minute
    public int sensorType = 0;                               // 0 as set, else a part (sets beam, emitter gap, size, best height)
    public double sensBodyW = 0, sensBodyL = 0;              // the sensor package: across / along the robot, mm (0 = not drawn)
    private double aLong, prevVForAccel, motorHeat;
    private final double[][] respState = new double[MAXR][MAXS];
    private boolean respInit;

    /** Sensor height now: the set height plus the lift of the board under acceleration. */
    public double heightNow() {
        return Math.max(0.3, sensorHeight + bounceMmPerG * aLong / 9810.0);
    }

    /** Reflectance the shiny line adds by glare (stronger close to the floor). */
    double glossAdd() {
        if (glossPct <= 0) {
            return 0;
        }
        double fr = floorRefl / 100.0, lr = lineRefl / 100.0;
        return glossPct / 100.0 * Math.max(0, fr - lr) * clamp(5.0 / Math.max(heightNow(), 1), 0.3, 1);
    }

    /** Speed factor of the motors as they warm up (1 = cold). */
    public double warmFactor() {
        return motorWarmPct <= 0 ? 1 : Math.max(0.6, 1 - motorWarmPct / 100.0 * motorHeat);
    }

    public double heightRef = 10, heightScale = 6;           // signal gain (1 at heightRef): (1+(ref/s)^2)/(1+(h/s)^2)
    public double floorRefl = 100, lineRefl = 0;             // reflectance of floor / line, %
    public int filterMode = 0;                               // 0 none, 1 moving average, 2 low-pass
    public int filterN = 4;                                  // moving-average length (samples)
    public double filterTauMs = 5;                           // low-pass time constant
    public int nRows = 1;                                    // sensor rows (1-3), rows sit behind the front row
    public double rowSpacing = 20;                           // mm between rows
    public boolean perSensorCal = false;                     // calibrate every sensor separately (sweep)

    // ---- white LEDs beside the sensors (off by default: no effect)
    public boolean whiteLeds = false;
    public double ledOffAt = 0, ledOnAt = 0;                 // disturbance: the LEDs switch off at / back on at (s, 0 = never)
    public boolean ledRecal = true;                          // the controls recalibrate when the LEDs are changed

    /** Are the LEDs lit now (fitted, and not switched off by the timed disturbance)? */
    public boolean ledsLit() {
        if (!whiteLeds) {
            return false;
        }
        boolean off = ledOffAt > 0 && t >= ledOffAt && !(ledOnAt > ledOffAt && t >= ledOnAt);
        return !off;
    }
    public int whiteMode = 0;                                // 0 indicator (lit while the sensor sees the line), 1 floor light (always on)
    public double whitePower = 0.5;                          // brightness, 0-1 (same scale as the IR LED power)
    public double whiteSeen = 0.3;                           // how much of the white light the IR sensor responds to (daylight filter: low)
    public int ledColor = 0;                                 // 0 white, 1 red, 2 yellow, 3 green, 4 blue
    public int ledPlace = 0;                                 // 0 behind each sensor, 1 between the sensors, 2 row in front, 3 row behind
    public double ledOffset = 5;                             // mm from the sensor row (behind / in front)
    public double ambientShield = 0;                         // share of the ambient light a skirt / shield blocks, 0-0.95
    final boolean[] whiteLit = new boolean[MAXS * MAXR];     // [row * MAXS + sensor]
    private final double[] reflBuf = new double[MAXS], ledX = new double[MAXL], ledY = new double[MAXL];
    private final boolean[] ledOn = new boolean[MAXL];

    // ---- custom layout (Robot editor): free sensor and LED positions, IR pair geometry, parts with weights
    public boolean customLayout;                             // sensors where the editor put them (else the array shape)
    public final double[] cuDx = new double[MAXS], cuLat = new double[MAXS];   // sensor: x ahead of the array reference, y sideways, mm
    public final String[] cuPin = new String[MAXS];          // MCU pin of each sensor (Arduino export), null = automatic
    public final double[] cuHeight = new double[MAXS];       // custom layout: each sensor's height above (+) or below (-) the set height, mm
    public boolean customLeds;                               // LEDs where the editor put them
    public int cuLedCount;
    public final double[] cuLedDx = new double[MAXL], cuLedLat = new double[MAXL];
    public final int[] cuLedSensor = new int[MAXL];          // indicator mode: the sensor that lights this LED
    public int pairOrient = 0;                               // IR pair: 0 emitter / receiver side by side, 1 one behind the other
    public boolean pairAlternate;                            // every other pair turned round
    public double pairGap = 0;                               // emitter-receiver distance, mm (0 = crosstalk by pitch only)
    public boolean boardJoined = true;                       // the sensor board is screwed to the chassis (mount holes)
    public boolean boardOnPlate;                             // one-piece PCB: the sensors are on the plate, no separate board
    public boolean customBoard;                              // the board outline drawn in the Robot editor
    public final List<double[]> cuBoard = new ArrayList<>(); // its corners: x ahead of the array reference, y sideways
    public double boardStem = 0;                             // a neck from the array back to the plate, mm wide (0 = none)
    public double boardHoleDia = 3.2;

    /** A part on the robot with its weight: the centre of mass and its height come from these. */
    public static final class Part {

        public String name;
        public int kind;                                     // 0 battery, 1 MCU board, 2 motor driver, 3 motor, 4 other
        public double x, y, w, l, h, massG;                  // centre x (ahead of the axle) / y, width, length, height of its centre, g

        public Part(String name, int kind, double x, double y, double w, double l, double h, double massG) {
            this.name = name;
            this.kind = kind;
            this.x = x;
            this.y = y;
            this.w = w;
            this.l = l;
            this.h = h;
            this.massG = massG;
        }

        Part copy() {
            return new Part(name, kind, x, y, w, l, h, massG);
        }
    }

    public final List<Part> parts = new ArrayList<>();
    public boolean useParts;                                 // mass, centre of mass and its height from the parts
    public double plateThick = 3, plateDensity = 1.19;       // chassis plate: mm, g/cm3 (acrylic 1.19, FR4 1.85, alu 2.7)

    /** Lateral position of sensor i (mm, + = right). */
    public double lat(int i) {
        return customLayout ? cuLat[i] : (i - (nSensors - 1) / 2.0) * spacing;
    }

    /** Forward offset of sensor i from the array reference (Axle to sensors), mm. */
    public double dxOf(int i) {
        return customLayout ? cuDx[i] : sensorDx(lat(i));
    }

    /** Half the array width: the largest |lateral position|, mm. */
    public double halfSpan() {
        if (!customLayout) {
            return (nSensors - 1) / 2.0 * spacing;
        }
        double h = 0;
        for (int i = 0; i < nSensors; i++) {
            h = Math.max(h, Math.abs(cuLat[i]));
        }
        return h;
    }

    /** Average sensor pitch (the set pitch, or of the custom layout), mm. */
    public double pitchEff() {
        if (!customLayout || nSensors < 2) {
            return spacing;
        }
        return Math.max(0.5, (cuLat[nSensors - 1] - cuLat[0]) / (nSensors - 1));
    }

    /** Copy the current array shape into the custom layout (start point for the editor). */
    public void layoutFromShape() {
        boolean was = customLayout;
        customLayout = false;
        for (int i = 0; i < nSensors; i++) {
            cuLat[i] = lat(i);
            cuDx[i] = dxOf(i);
        }
        customLayout = was;
    }

    /** Copy the LED placement of the Hardware tab into the custom LED list. */
    public void ledsFromPlacement() {
        boolean was = customLeds;
        customLeds = false;
        double[] lx = new double[MAXL], ly = new double[MAXL];
        int n = ledLayout(lx, ly);
        for (int j = 0; j < n; j++) {
            cuLedDx[j] = lx[j] - forward;
            cuLedLat[j] = ly[j];
            cuLedSensor[j] = ledPlace == 1 ? j : Math.min(j, nSensors - 1);
        }
        cuLedCount = n;
        customLeds = was;
    }

    /** Sort the custom sensors left to right (the controller counts them that way) and keep the LED links. */
    public void sortLayout() {
        Integer[] idx = new Integer[nSensors];
        for (int i = 0; i < nSensors; i++) {
            idx[i] = i;
        }
        Arrays.sort(idx, (a, b) -> Double.compare(cuLat[a], cuLat[b]));
        double[] dx = cuDx.clone(), la = cuLat.clone();
        String[] pin = cuPin.clone();
        double[] hh = cuHeight.clone();
        int[] inv = new int[MAXS];
        for (int k = 0; k < nSensors; k++) {
            cuDx[k] = dx[idx[k]];
            cuLat[k] = la[idx[k]];
            cuPin[k] = pin[idx[k]];
            cuHeight[k] = hh[idx[k]];
            inv[idx[k]] = k;
        }
        for (int j = 0; j < cuLedCount; j++) {
            cuLedSensor[j] = inv[Math.max(0, Math.min(nSensors - 1, cuLedSensor[j]))];
        }
    }

    /** Emitter (e = true) or receiver position of sensor i's IR pair, robot frame relative to the array reference: {dx, lat}. */
    double[] pairPoint(int i, boolean e) {
        double half = pairGap / 2 * (e ? -1 : 1) * (pairAlternate && i % 2 == 1 ? -1 : 1);
        return pairOrient == 1 ? new double[]{dxOf(i) - half, lat(i)} : new double[]{dxOf(i), lat(i) + half};
    }

    /** Crosstalk coupling from sensor j's emitter into sensor i (share of j's own signal). */
    double xtCoef(int i, int j) {
        if (crosstalkPct <= 0 || i == j) {
            return 0;
        }
        if (pairGap <= 0) {
            return crosstalkFactor() * Math.pow(0.25, Math.abs(i - j) - 1);
        }
        double[] em = pairPoint(j, true), rc = pairPoint(i, false), own = pairPoint(j, false);
        double d = Math.hypot(em[0] - rc[0], em[1] - rc[1]);
        double self = Math.max(2, Math.hypot(em[0] - own[0], em[1] - own[1]));   // j's own emitter-receiver distance
        double c = crosstalkPct / 100.0 * Math.min(3.0, sq(10.0 / Math.max(d, 2.0))) * (pairGap > 0 ? Math.min(1, sq(self / 4)) : 1);
        return barriers ? c * 0.1 : c;
    }

    /** Total plate mass from its area, thickness and density, g. */
    public double plateMassG() {
        return chassisGeometry()[0] * plateThick / 1000.0 * plateDensity;
    }

    /** Mass of the robot from the parts (plate, parts, wheels, sensor board), g. */
    public double partsMassG() {
        double m0 = plateMassG();
        for (Part p : parts) {
            m0 += p.massG;
        }
        return m0;
    }

    /** {x, y, height} of the centre of mass from the parts, mm. */
    public double[] partsCentre() {
        double[] g = chassisGeometry();
        double pm = plateMassG(), sm = pm, sx = pm * g[1], sy = pm * g[2], sz = pm * (wheelDia / 2 + plateThick / 2);
        for (Part p : parts) {
            sm += p.massG;
            sx += p.massG * p.x;
            sy += p.massG * p.y;
            sz += p.massG * p.h;
        }
        return sm > 0 ? new double[]{sx / sm, sy / sm, sz / sm} : new double[]{0, 0, wheelDia / 2};
    }

    /** Height of the centre of mass above the floor, mm (an estimate without parts). */
    public double comHeight() {
        return useParts && !parts.isEmpty() ? partsCentre()[2] : wheelDia / 2 + 12;
    }

    /** Sideways acceleration that would tip the robot over its outer wheels, mm/s^2. */
    public double tipAccel() {
        return 9810 * (wheelBase / 2) / Math.max(comHeight(), 5);
    }

    /** The usual parts of a small line follower, placed on the chassis (a start for the editor). */
    public void defaultParts() {
        parts.clear();
        double z = wheelDia / 2 + plateThick, cx = casterX();
        boolean front = cx > 0;                                            // the caster ahead of the axle (robot runs tail first)
        // the battery (the heaviest part) between the axle and the caster, nearer the axle: weight on the wheels, no tipping
        double bx = wheelCount == 4 ? 0 : cx * 0.6;
        parts.add(new Part("Battery", 0, bx, 0, 34, Math.min(60, Math.max(30, Math.abs(cx) * 0.6)), z + 10, 95));
        double ex = front ? -20 : Math.min(forward * 0.3, 20);               // electronics on the other side of the axle
        parts.add(new Part("MCU board", 1, ex, -13, 24, 44, z + 8, 10));
        parts.add(new Part("Motor driver", 2, ex, 17, 20, 20, z + 18, 4));
        double hw = wheelBase / 2 - tireWidth / 2 - 14;
        parts.add(new Part("Left motor", 3, 0, -Math.max(hw, 10), 22, 18, wheelDia / 2, 30));
        parts.add(new Part("Right motor", 3, 0, Math.max(hw, 10), 22, 18, wheelDia / 2, 30));
        parts.add(new Part("Wheels", 4, 0, 0, 0, 0, wheelDia / 2, 2 * 12));
        parts.add(new Part("Sensor board", 4, forward, 0, 0, 0, sensorHeight + 1, 6 + 1.5 * nSensors));
        parts.add(new Part("Caster", 4, casterX(), 0, 0, 0, 6, 8));
    }

    /** Where the board is screwed to the chassis: two holes in the overlap of board and plate, robot frame {x, y}. */
    public List<double[]> boardHoles() {
        List<double[]> l = new ArrayList<>();
        if (!boardJoined) {
            return l;
        }
        java.awt.geom.Area ov = new java.awt.geom.Area(sensorBoard());
        ov.intersect(new java.awt.geom.Area(chassisOutline()));
        java.awt.geom.Rectangle2D b = ov.getBounds2D();
        if (b.isEmpty() || b.getWidth() < boardHoleDia + 2 || b.getHeight() < boardHoleDia + 2) {
            return l;
        }
        double x = b.getCenterX();
        for (double fy : new double[]{-0.3, 0.3}) {
            double y = b.getCenterY() + fy * b.getHeight();
            for (double dx = 0; dx < b.getWidth() / 2; dx += 0.5) {      // look for a spot inside the overlap
                if (ov.contains(x - dx, y)) {
                    l.add(new double[]{x - dx, y});
                    break;
                }
                if (ov.contains(x + dx, y)) {
                    l.add(new double[]{x + dx, y});
                    break;
                }
            }
        }
        return l;
    }

    /** Write the layout (sensors, LEDs, pairs, parts) as text lines (workspace files). */
    public List<String> layoutLines() {
        List<String> o = new ArrayList<>();
        o.add("layout " + (customLayout ? 1 : 0) + " " + (customLeds ? 1 : 0) + " " + pairOrient + " " + (pairAlternate ? 1 : 0)
                + " " + pairGap + " " + (boardJoined ? 1 : 0) + " " + (useParts ? 1 : 0) + " " + plateThick + " " + plateDensity + " " + boardStem + " " + (boardOnPlate ? 1 : 0) + " " + (customBoard ? 1 : 0));
        for (double[] q : cuBoard) {
            o.add("lboard " + q[0] + " " + q[1]);
        }
        for (int i = 0; i < nSensors; i++) {
            o.add("lsensor " + cuDx[i] + " " + cuLat[i] + " " + (cuPin[i] == null || cuPin[i].isEmpty() ? "-" : cuPin[i]) + " " + cuHeight[i]);
        }
        for (int j = 0; j < cuLedCount; j++) {
            o.add("lled " + cuLedDx[j] + " " + cuLedLat[j] + " " + cuLedSensor[j]);
        }
        for (Part p : parts) {
            o.add("lpart " + p.kind + " " + p.x + " " + p.y + " " + p.w + " " + p.l + " " + p.h + " " + p.massG + " "
                    + p.name.replace(' ', '_'));
        }
        return o;
    }

    /** Read lines written by layoutLines (other lines are ignored). */
    public void layoutFrom(List<String> lines) {
        int ns = 0;
        cuLedCount = 0;
        List<Part> ps = new ArrayList<>();
        for (String ln : lines) {
            String[] t = ln.trim().split("\\s+");
            switch (t[0]) {
                case "layout":
                    customLayout = t[1].equals("1");
                    customLeds = t[2].equals("1");
                    pairOrient = Integer.parseInt(t[3]);
                    pairAlternate = t[4].equals("1");
                    pairGap = Double.parseDouble(t[5]);
                    boardJoined = t[6].equals("1");
                    useParts = t[7].equals("1");
                    plateThick = Double.parseDouble(t[8]);
                    plateDensity = Double.parseDouble(t[9]);
                    boardStem = t.length > 10 ? Double.parseDouble(t[10]) : 0;
                    boardOnPlate = t.length > 11 && t[11].equals("1");
                    customBoard = t.length > 12 && t[12].equals("1");
                    cuBoard.clear();
                    break;
                case "lboard":
                    cuBoard.add(new double[]{Double.parseDouble(t[1]), Double.parseDouble(t[2])});
                    break;
                case "lsensor":
                    if (ns < MAXS) {
                        cuDx[ns] = Double.parseDouble(t[1]);
                        cuLat[ns] = Double.parseDouble(t[2]);
                        cuPin[ns] = t[3].equals("-") ? null : t[3];
                        cuHeight[ns] = t.length > 4 ? Double.parseDouble(t[4]) : 0;
                        ns++;
                    }
                    break;
                case "lled":
                    if (cuLedCount < MAXL) {
                        cuLedDx[cuLedCount] = Double.parseDouble(t[1]);
                        cuLedLat[cuLedCount] = Double.parseDouble(t[2]);
                        cuLedSensor[cuLedCount] = Integer.parseInt(t[3]);
                        cuLedCount++;
                    }
                    break;
                case "lpart":
                    ps.add(new Part(t.length > 8 ? t[8].replace('_', ' ') : "Part", Integer.parseInt(t[1]), Double.parseDouble(t[2]),
                            Double.parseDouble(t[3]), Double.parseDouble(t[4]), Double.parseDouble(t[5]), Double.parseDouble(t[6]),
                            Double.parseDouble(t[7])));
                    break;
                default:
            }
        }
        if (customLayout && ns > 0) {
            nSensors = ns;
        }
        parts.clear();
        parts.addAll(ps);
    }

    /** Relative response of an IR phototransistor (daylight filter) to each LED colour, white = 1. */
    static final double[] LED_SEEN = {1.0, 1.6, 0.9, 0.45, 0.15};
    public static final String[] LED_COLORS = {"White", "Red", "Yellow", "Green", "Blue"};
    public static final Color[] LED_RGB = {new Color(255, 255, 240), new Color(255, 70, 60), new Color(255, 210, 60),
        new Color(80, 230, 110), new Color(80, 140, 255)};

    // ---- environment (neutral defaults)
    public double tempC = 25, tempRisePerMin = 0;            // LED output falls 0.3 % per degree above 25
    public double battDrainPctPerMin = 0, battSagPct = 0;    // battery voltage loss with time / with motor load

    // ---- battery capacity and current (capacity 0 = the voltage is not taken from it)
    public double battCapacity = 0;                          // mAh
    public double battResistance = 0.15;                     // internal resistance (pack + wires), ohm
    public double stallCurrent = 1.2;                        // one motor at stall at the rated voltage, A
    public double logicCurrent = 0.06;                       // MCU and the rest, A
    public double currentNow, usedMah;                       // A, mAh since the start

    // ---- array mounting errors, comparator, ADC
    public double arrayOffset = 0;                           // the array sits this far right of the centre line, mm
    public double arrayYaw = 0;                              // and turned by this much (+ = clockwise from above), degrees
    public double hystPct = 0;                               // digital readout: hysteresis, % of the white-black span
    public int adcBits = 0;                                  // 0 = ideal, else 8 / 10 / 12-bit steps
    public boolean esp32Adc;                                 // ESP32-style ADC: dead below ~0.13 V, flat above ~3.1 V
    private final boolean[][] hystDark = new boolean[MAXR][MAXS];

    // ---- failures (all off by default)
    public int stuckSensor = -1;                             // front-row sensor that is stuck (-1 = none)
    public boolean stuckOnLine = true;                       // stuck reading "line" (else "floor")
    public double brownoutV = 0;                             // MCU resets below this battery voltage (0 = off)
    public boolean tipCheck;                                 // stop when the robot would tip over in a bend
    public boolean tipped;
    public int brownouts;
    private double brownoutUntil = -1;

    // ---- wheel encoders and the wheel-speed loop
    public int encType = 0;                                  // 0 none, 1 N20 magnetic, 2 TT optical disc, 3 Pololu magnetic, 4 custom
    public double encPpr = 7, encGear = 30;                  // pulses per motor turn, gear ratio
    public boolean encQuad = true;                           // count both edges of both channels (x4)
    public double encCustomCpr = 360;                        // custom: counts per wheel turn
    public boolean speedLoop;                                // wheel speeds held by a PI loop on the encoders
    public double speedKp = 0.6, speedKi = 8;                // 1, 1/s
    private double encPosL, encPosR, encIntL, encIntR;
    private long encLastL, encLastR;
    public double encSpeedL, encSpeedR;                      // measured, mm/s
    public boolean sensorsOnBattery = false;                 // IR LEDs dim with the battery voltage
    public double tractionMu = 0;                            // wheel grip: 0 = unlimited

    // ---- loop timing (neutral defaults)
    public int loopMode = 0;                                 // 0 compute + delay(), 1 fixed period
    public double fixedHz = 50, jitterUs = 0;                // fixed-loop rate, random period jitter
    public boolean sensorTask = false;                       // sensors read by a separate task (ESP32 style)
    public double sensorTaskHz = 1000;
    public double physicsStepMs = 0;                         // 0 = automatic

    // ---- background picture to trace (not seen by the sensors)
    public BufferedImage bgImage;
    public String bgPath;
    public double bgX, bgY, bgW = 800, bgAlpha = 0.5;
    public boolean bgVisible = true;

    // ---- robot state
    public double x, y, th, vL, vR, cmdL, cmdR;
    public double sx, sy, sth;                               // start pose
    public double t, ctrlAcc, histAcc, stepAcc;
    double pendL, pendR, applyAt;
    boolean hasPending;
    public boolean outOfArena = false;
    public final double[] raw = new double[MAXS], dark = new double[MAXS];
    public final double[] sensX = new double[MAXS], sensY = new double[MAXS];
    public double error, prevError, lastValidError, integral, dFilt, pTerm, iTerm, dTerm, outTerm;
    public boolean lineVisible = true, firstCycle = true;

    // sensor rows: row 0 (the front row) is also available as raw / dark / sensX / sensY
    final double[][] rawRow = new double[MAXR][], darkRow = new double[MAXR][];
    final double[][] sensXRow = new double[MAXR][], sensYRow = new double[MAXR][];
    final double[][] calMin = new double[MAXR][MAXS], calMax = new double[MAXR][MAXS];
    private final double[] ownBuf = new double[MAXS], baseBuf = new double[MAXS], instBuf = new double[MAXS];
    private final double[] tmpX = new double[MAXS], tmpY = new double[MAXS];
    private final double[][][] maBuf = new double[MAXR][MAXS][16];
    private final int[] maPos = new int[MAXR], maCnt = new int[MAXR];
    private final double[][] lpState = new double[MAXR][MAXS];
    private final boolean[] lpInit = new boolean[MAXR];
    private int lastFilterMode;
    double lastSampleT = -1, sensAcc, jitterOff, loadAvg, stallL = -1, stallR = -1;
    long loopCount;                                          // controller runs so far (for the timing tests)
    private boolean junctionNow, turnActive;                // a junction is under the array / a committed turn is under way
    private double turnStartTh, turnSign, turnCooldown;
    public double vBody;                                     // forward speed of the chassis (differs from the wheels only when they slip)
    private final Random jrnd = new Random(7);               // separate stream: never disturbs the sensor noise

    /** A disturbance zone on the track: a faded part of the line, or a slippery patch (amount = grip mu). */
    public static final class Patch {

        public final double cx, cy, ang, hl, hw, amount;
        public final boolean slip;

        Patch(double cx, double cy, double ang, double length, double width, boolean slip, double amount) {
            this.cx = cx;
            this.cy = cy;
            this.ang = ang;
            this.hl = length / 2;
            this.hw = width / 2;
            this.slip = slip;
            this.amount = amount;
        }

        public boolean contains(double px, double py) {
            double c = Math.cos(ang), s = Math.sin(ang), dx = px - cx, dy = py - cy;
            return Math.abs(dx * c + dy * s) <= hl && Math.abs(-dx * s + dy * c) <= hw;
        }
    }

    public final List<Patch> patches = new ArrayList<>();

    // ---- stats
    public double distance, offTrackTime, lineLostTime, absErrInt, maxAbsErr;

    // ---- start / finish gate and laps (the gate is crossed by the centre of the sensor array)
    public boolean gateSet;                                  // false = automatic (through the start; far end of an open preset)
    public double gx1, gy1, gx2, gy2;                        // gate end points, mm
    public final List<Double> laps = new ArrayList<>();      // lap (or finish) times, s
    private double lapStartT, gateTravel, prevAx = Double.NaN, prevAy;
    double[] presetFinish;                                   // open preset: a finish gate at the far end (null = through the start)

    // ---- telemetry: every 10 ms of the last 2 minutes
    public static final int TEL = 12000;
    public static final String[] TEL_COLS = {"t_s", "x_mm", "y_mm", "heading_deg", "error_mm", "p", "i", "d", "out_mm_s", "cmd_left",
        "cmd_right", "v_left", "v_right", "line_seen", "off_line", "steer_deg"};
    final float[][] tel = new float[TEL][];
    int telHead, telCount;

    /** A finished run kept for comparison: its trail, its numbers and the settings it was made with. */
    public static final class Run {

        public final String name;
        public final Color color;
        public final double[] x, y, errT, err;
        public final double time, distance, avgErr, maxErr, offLine, lost;
        public final java.util.Map<String, String> settings;
        public boolean visible = true;
        public double[] laps = new double[0];            // lap (or finish) times
        public boolean imported;                         // a log from a real robot (or another program)

        Run(String name, Color color, double[] x, double[] y, double[] errT, double[] err, double time, double distance,
                double avgErr, double maxErr, double offLine, double lost, java.util.Map<String, String> settings) {
            this.name = name;
            this.color = color;
            this.x = x;
            this.y = y;
            this.errT = errT;
            this.err = err;
            this.time = time;
            this.distance = distance;
            this.avgErr = avgErr;
            this.maxErr = maxErr;
            this.offLine = offLine;
            this.lost = lost;
            this.settings = settings;
        }
    }

    public static final Color[] RUN_COLORS = {new Color(0x2563EB), new Color(0xEA580C), new Color(0x16A34A), new Color(0x9333EA),
        new Color(0xDC2626), new Color(0x0D9488), new Color(0xDB2777), new Color(0x92400E)};
    public final List<Run> runs = new ArrayList<>();
    private int runNo;

    /** Keep the current trail and statistics as a run to compare (at most 8; the oldest goes). */
    /** A free colour for the next kept run (the oldest run is dropped when all 8 are used). */
    Color nextRunColor() {
        if (runs.size() >= RUN_COLORS.length) {
            runs.remove(0);
        }
        java.util.Set<Color> used = new java.util.HashSet<>();
        for (Run r : runs) {
            used.add(r.color);
        }
        for (Color k : RUN_COLORS) {
            if (!used.contains(k)) {
                return k;
            }
        }
        return RUN_COLORS[0];
    }

    /** After runs were read from a file: number new runs after them. */
    void runsLoaded() {
        for (Run r : runs) {
            if (r.name.startsWith("Run ")) {
                try {
                    runNo = Math.max(runNo, Integer.parseInt(r.name.substring(4).trim()));
                } catch (NumberFormatException ex) {
                    // a renamed or imported run
                }
            }
        }
    }

    int nextRunNo() {
        return ++runNo;
    }

    public Run keepRun(java.util.Map<String, String> settings) {
        int n = trCount, start = (trHead - trCount + TRAIL) % TRAIL;
        double[] rx = new double[n], ry = new double[n];
        for (int j = 0; j < n; j++) {
            rx[j] = trX[(start + j) % TRAIL];
            ry[j] = trY[(start + j) % TRAIL];
        }
        int hn = hCount, hs = (hHead - hCount + HIST) % HIST;
        double[] et = new double[hn], ev = new double[hn];
        for (int j = 0; j < hn; j++) {
            et[j] = t - (hn - j) * 0.01;
            ev[j] = hist[0][(hs + j) % HIST];
        }
        if (runs.size() >= RUN_COLORS.length) {
            runs.remove(0);
        }
        java.util.Set<Color> used = new java.util.HashSet<>();
        for (Run r : runs) {
            used.add(r.color);
        }
        Color c = RUN_COLORS[0];
        for (Color k : RUN_COLORS) {
            if (!used.contains(k)) {
                c = k;
                break;
            }
        }
        Run r = new Run("Run " + (++runNo), c, rx, ry, et, ev, t, distance, avgAbsError(), maxAbsErr,
                t > 0 ? offTrackTime / t : 0, lineLostTime, settings);
        r.laps = laps.stream().mapToDouble(Double::doubleValue).toArray();
        runs.add(r);
        return r;
    }

    /** Forget the drawn trail (the run keeps going and its numbers are kept). */
    public void clearTrail() {
        trHead = trCount = 0;
        lastTrX = x;
        lastTrY = y;
    }

    // ---- history for graphs
    public final double[][] hist = new double[7][HIST];      // err, P, I, D, out, vL, vR
    public int hHead, hCount;

    // ---- trail
    public final double[] trX = new double[TRAIL], trY = new double[TRAIL];
    public int trHead, trCount;
    double lastTrX, lastTrY;

    final Random rnd = new Random(1);
    private final double[] cosT = new double[8], sinT = new double[8];

    public Model() {
        rawRow[0] = raw;
        darkRow[0] = dark;
        sensXRow[0] = sensX;
        sensYRow[0] = sensY;
        for (int r = 1; r < MAXR; r++) {
            rawRow[r] = new double[MAXS];
            darkRow[r] = new double[MAXS];
            sensXRow[r] = new double[MAXS];
            sensYRow[r] = new double[MAXS];
        }
        for (int r = 0; r < MAXR; r++) {
            Arrays.fill(calMin[r], 0);
            Arrays.fill(calMax[r], 1023);
        }
        applyHardware();
        allocateImage();
        for (int k = 0; k < 8; k++) {
            cosT[k] = Math.cos(k * Math.PI / 4);
            sinT[k] = Math.sin(k * Math.PI / 4);
        }
        resetMismatch();
        loadPreset(0);
        calibrate();
    }

    // ------------------------------------------------------------------ helpers
    static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    static double clamp01(double v) {
        return clamp(v, 0, 1);
    }

    private static void fillMismatch(Random r, double[] gain, double[] off) {
        // same order as with 16 sensors (row * 16 + sensor first), so older setups get the same pattern
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < MAXR * 16; k++) {
                int row = k / 16, i = k % 16 + (pass == 1 ? 16 : 0);
                if (pass == 1 && MAXS <= 16) {
                    break;
                }
                int ix = row * MAXS + i;
                gain[ix] = r.nextDouble() * 2 - 1;
                off[ix] = r.nextDouble() * 2 - 1;
            }
        }
    }

    /** New random sensor mismatch pattern. */
    public void randomizeMismatch() {
        fillMismatch(new Random(), mmGain, mmOff);
    }

    /** A mismatch pattern from a given seed (repeatable). */
    public void randomizeMismatch(long seed) {
        fillMismatch(new Random(seed), mmGain, mmOff);
    }

    /** Back to the original (seeded) mismatch pattern. */
    public void resetMismatch() {
        fillMismatch(new Random(1), mmGain, mmOff);
    }

    // ------------------------------------------------------------------ hardware model
    public double wheelCircumference() {
        return Math.PI * wheelDia;
    }

    /** Wheel speed at full PWM: from the motor spec unless a measured value is given. */
    public double hwTopSpeed() {
        if (topSpeedOverride > 0) {
            return topSpeedOverride;
        }
        double rpm = motorRpm * (Math.max(0, battVolt - driverDrop) / Math.max(motorVolt, 0.1)) * (loadPct / 100.0);
        return wheelCircumference() * rpm / 60.0;
    }

    public double arrayWidth() {
        return customLayout && nSensors > 1 ? cuLat[nSensors - 1] - cuLat[0] : (nSensors - 1) * spacing;
    }

    /** Push the hardware numbers into the simulation (max wheel speed, sensor spot size). */
    public void applyHardware() {
        maxSpeed = hwTopSpeed();
        spotRadius = sensorHeight * Math.tan(Math.toRadians(beamAngle));
    }

    // ------------------------------------------------------------------ MCU timing
    /** CPU time (s) the control loop needs for one pass. With a separate sensor task the ADC time is not part of it. */
    public double computeSec() {
        double perSensor = analogMode ? adcCycles : digCycles;
        double reads = sensorTask ? 0 : nSensors * nRows * perSensor;
        return (reads + mathCycles + (program != null && codeMode != 0 ? codeCycles : 0)) / (Math.max(clockMHz, 0.001) * 1e6);
    }

    /** The loop period before the physics step limit: compute + delay(), or the fixed period (never shorter than the compute time). */
    public double basePeriodSec() {
        double c = computeSec();
        return loopMode == 1 ? Math.max(c, 1.0 / Math.max(fixedHz, 0.1)) : c + loopDelayMs / 1000.0;
    }

    /** Physics step: automatic = half the shortest loop period, between 20 us and 0.5 ms. */
    public double stepSec() {
        if (physicsStepMs > 0) {
            return physicsStepMs / 1000.0;
        }
        double p = basePeriodSec();
        if (sensorTask) {
            p = Math.min(p, 1.0 / Math.max(sensorTaskHz, 1.0));
        }
        return clamp(p / 2, 2e-5, DT);
    }

    /** Time between two controller runs (never below one physics step). */
    public double loopPeriodSec() {
        return Math.max(stepSec(), basePeriodSec());
    }

    /** The delays between a sensor reading and the wheels reacting, in ms. */
    public static final class Latency {

        public double adc, math, hold, filter, taskAge, motor, total;
        public double dFilter, dTotal;                       // the derivative low-pass delays the D (and prediction) part only
    }

    public Latency latency() {
        Latency l = new Latency();
        double clk = Math.max(clockMHz, 0.001) * 1e6;
        double perSensor = analogMode ? adcCycles : digCycles;
        l.adc = sensorTask ? 0 : nSensors * nRows * perSensor / clk * 1000.0;
        l.math = mathCycles / clk * 1000.0;
        l.hold = loopPeriodSec() / 2 * 1000.0;                              // command is held until the next loop
        double sample = sensorTask ? 1.0 / Math.max(sensorTaskHz, 1.0) : loopPeriodSec();
        l.filter = filterMode == 1 ? (Math.max(1, Math.min(filterN, 16)) - 1) / 2.0 * sample * 1000.0
                : filterMode == 2 ? filterTauMs : 0;
        l.taskAge = sensorTask ? sample / 2 * 1000.0 : 0;                   // reading is on average half a task period old
        l.motor = Math.max(lagL, lagR);
        l.total = l.adc + l.math + l.hold + l.filter + l.taskAge + l.motor;
        l.dFilter = kd > 0 || predictMs > 0 ? dFilterMs : 0;
        l.dTotal = l.total + l.dFilter;
        return l;
    }

    // ------------------------------------------------------------------ HUD helpers
    public boolean readoutAnalog() {
        return readout == 1 || readout == 3 || (readout == 0 && analogMode);
    }

    public boolean readoutDigital() {
        return readout == 2 || readout == 3 || (readout == 0 && !analogMode);
    }

    /** Digital reading of sensor i (true = sees the line). */
    public boolean bit(int i) {
        return raw[i] < thresholdFor(0, i);
    }

    /** Decision level of front-row sensor i, as the HUD shows it. */
    public double hudThreshold(int i) {
        return thresholdFor(0, i);
    }

    /**
     * The line width the design checks and tests should use: the width most of the drawn track
     * uses (so the thin / wide presets and hand-drawn tracks are judged correctly), else the preset setting.
     */
    public double checkLineWidth() {
        List<Double> ws = new ArrayList<>();
        for (TrackShape sh : shapes) {
            if (!sh.erase) {
                ws.add(sh.width);
            }
        }
        if (ws.isEmpty()) {
            return lineWidth;
        }
        java.util.Collections.sort(ws);
        return ws.get(ws.size() / 2);
    }

    // ------------------------------------------------------------------ track building
    public void loadPreset(int idx) {
        renderPreset(idx);
        reset();
    }

    /** Re-draw the current preset (e.g. after the line width changed) without moving the robot. */
    public void rerender() {
        double a = sx, b = sy, c = sth;
        renderPreset(presetIdx);
        sx = a;
        sy = b;
        sth = c;
    }

    /** Remove every shape (blank white arena). */
    public void clearTrack() {
        shapes.clear();
        edited = true;
        renderTrack();
    }

    // ------------------------------------------------------------------ background picture
    /** Load a photo or scan to trace over (shown under the shapes, never seen by the sensors). */
    public void loadBackground(File f) throws IOException {
        BufferedImage img = javax.imageio.ImageIO.read(f);
        if (img == null) {
            throw new IOException("This is not an image the program can read (use PNG, JPG, GIF or BMP).");
        }
        bgImage = img;
        bgPath = f.getAbsolutePath();
        bgVisible = true;
        fitBackground();
    }

    /** Use a picture that came from a saved file (embedded), remembering where it was linked from. */
    public void useBackground(BufferedImage img, String path) {
        bgImage = img;
        bgPath = path;
        bgVisible = true;
        fitBackground();
    }

    public boolean bgEmbed = true;                           // keep the picture inside saved track / workspace files

    public void clearBackground() {
        bgImage = null;
        bgPath = null;
        bgX = 0;
        bgY = 0;
        bgW = 800;
    }

    /** Height in mm that matches the width and the picture's proportions. */
    public double bgH() {
        return bgImage == null ? 0 : bgW * bgImage.getHeight() / bgImage.getWidth();
    }

    /** Scale the picture to fill the arena (keeping its proportions) and centre it. */
    public void fitBackground() {
        if (bgImage == null) {
            return;
        }
        double ar = (double) bgImage.getWidth() / bgImage.getHeight();
        bgW = Math.min(arenaW, arenaH * ar);
        bgX = (arenaW - bgW) / 2;
        bgY = (arenaH - bgH()) / 2;
    }

    /** Called when a shape-editing action changes the track. */
    public void markEdited() {
        edited = true;
    }

    public void setShapes(List<TrackShape> list) {
        shapes = list;
    }

    /** Be told when the whole track is replaced (preset loaded, file opened), e.g. to drop selection and undo. */
    public void addTrackListener(Runnable r) {
        trackListeners.add(r);
    }

    private void fireTrackReplaced() {
        patches.clear();                                     // disturbances belong to the old track
        for (Runnable r : trackListeners) {
            r.run();
        }
    }

    /** Draw every shape into the track image (the image is what the sensors read). */
    public void renderTrack() {
        Graphics2D g = track.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, imgW, imgH);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        g.scale(1.0 / res, 1.0 / res);                       // shapes are in mm
        for (TrackShape s : shapes) {
            s.paint(g);
        }
        g.dispose();
    }

    /** Replace the whole track (used when opening a track file). */
    public void setTrack(double w, double h, List<TrackShape> newShapes, double[] pose) {
        arenaW = clamp(w, 200, 6000);
        arenaH = clamp(h, 200, 6000);
        allocateImage();
        shapes = new ArrayList<>(newShapes);
        edited = true;
        runs.clear();
        presetFinish = null;
        gateSet = false;
        sx = pose[0];
        sy = pose[1];
        sth = pose[2];
        renderTrack();
        fireTrackReplaced();
        reset();
    }

    /** Which way a preset loop is driven now, for display: "clockwise", "counter-clockwise" or null. */
    public String loopDirection() {
        if (edited || presetClockwise == null) {
            return null;
        }
        return presetClockwise ^ (startDir == 1) ? "clockwise" : "counter-clockwise";
    }

    /** Turn the start position round (drive the track the other way) and restart. */
    public void turnRound() {
        placeRobot(sx, sy, sth + Math.PI);
    }

    /**
     * Use another model's track without drawing it again (the image is only read, so several copies can
     * run at the same time on different threads). Also copies the start pose and the patches.
     */
    void shareTrackFrom(Model o) {
        arenaW = o.arenaW;
        arenaH = o.arenaH;
        res = o.res;
        imgW = o.imgW;
        imgH = o.imgH;
        track = o.track;
        pix = o.pix;
        shapes = o.shapes;
        edited = true;
        sx = o.sx;
        sy = o.sy;
        sth = o.sth;
        patches.clear();
        patches.addAll(o.patches);
        reset();
    }

    public void placeRobot(double px, double py, double ang) {
        sx = px;
        sy = py;
        sth = ang;
        reset();
    }

    void renderPreset(int idx) {
        if (idx != presetIdx || edited) {
            runs.clear();                                    // kept runs belong to the old track (not to a new width / direction)
        }
        presetIdx = idx;
        edited = false;
        TrackBuilder.Result r = TrackBuilder.build(idx, lineWidth, arenaW, arenaH);
        shapes = r.shapes;
        double[] p = startDir == 1 ? r.poseReversed : r.pose;
        presetFinish = r.finish == null ? null : (startDir == 1 ? r.finishReversed : r.finish);
        gateSet = false;
        sx = p[0];
        sy = p[1];
        sth = p[2];
        presetClockwise = r.clockwise;
        renderTrack();
        fireTrackReplaced();
    }

    // ------------------------------------------------------------------ arena
    /** Create an empty track image for the current arena size (res = mm per pixel). */
    private void allocateImage() {
        res = Math.max(1.0, Math.max(arenaW / 1600.0, arenaH / 1000.0));
        imgW = (int) Math.ceil(arenaW / res);
        imgH = (int) Math.ceil(arenaH / res);
        track = new BufferedImage(imgW, imgH, BufferedImage.TYPE_INT_RGB);
        pix = ((DataBufferInt) track.getRaster().getDataBuffer()).getData();
    }

    /**
     * Change the real size of the arena (mm). A preset track you have not edited is re-built to fit;
     * once you have edited the track its shapes keep their real positions (cropped if the arena shrinks).
     */
    public void setArena(double w, double h) {
        w = clamp(w, 200, 6000);
        h = clamp(h, 200, 6000);
        if (w == arenaW && h == arenaH) {
            return;
        }
        arenaW = w;
        arenaH = h;
        allocateImage();
        if (edited) {
            sx = clamp(sx, 0, arenaW);
            sy = clamp(sy, 0, arenaH);
            renderTrack();
            reset();
        } else {
            loadPreset(presetIdx);
        }
    }

    /** Spread of the "sunny patch" light, scales with the arena. */
    public double hotSigma() {
        return 0.23 * Math.min(arenaW, arenaH);
    }

    // ------------------------------------------------------------------ reset
    public void reset() {
        x = sx;
        y = sy;
        th = sth;
        outOfArena = false;
        vL = vR = cmdL = cmdR = 0;
        hasPending = false;
        t = 0;
        histAcc = 0;
        stepAcc = 0;
        ctrlAcc = loopPeriodSec();                           // run controller on the first step
        jitterOff = 0;
        sensAcc = 0;
        loadAvg = 0;
        stallL = stallR = -1;
        lastSampleT = -1;
        lpInit[0] = lpInit[1] = lpInit[2] = false;
        turnActive = false;
        junctionNow = false;
        seenCount = 0;
        lostSince = 0;
        long seed = varyNoise ? System.nanoTime() : noiseSeed;               // same seed = the same noise, run after run
        rnd.setSeed(seed);
        jrnd.setSeed(seed * 31 + 7);
        recoverSpin = recoverStop = false;
        turnCooldown = 0;
        loopCount = 0;
        vBody = 0;
        pV = pW = 0;
        steerAngle = cmdSteer = pendSteer = steerGoal = 0;
        steerQueue.clear();
        blSign[0] = blSign[1] = 0;
        blUntil[0] = blUntil[1] = -1;
        maCnt[0] = maCnt[1] = maCnt[2] = 0;
        Arrays.fill(whiteLit, false);
        error = prevError = lastValidError = integral = dFilt = 0;
        pTerm = iTerm = dTerm = outTerm = 0;
        lineVisible = true;
        firstCycle = true;
        distance = offTrackTime = lineLostTime = absErrInt = maxAbsErr = 0;
        laps.clear();
        lapStartT = 0;
        usedMah = currentNow = 0;
        aLong = prevVForAccel = motorHeat = 0;
        respInit = false;
        tipped = false;
        brownouts = 0;
        brownoutUntil = -1;
        encPosL = encPosR = encIntL = encIntR = encSpeedL = encSpeedR = 0;
        encLastL = encLastR = 0;
        for (boolean[] hr : hystDark) {
            Arrays.fill(hr, false);
        }
        gateTravel = 0;
        prevAx = Double.NaN;
        telHead = telCount = 0;
        hHead = hCount = 0;
        for (double[] h : hist) {
            Arrays.fill(h, 0);
        }
        trHead = trCount = 0;
        lastTrX = x;
        lastTrY = y;
        readSensors();                                                     // for the read-out before the start
        lastSampleT = -1;                                                  // the filters start with the first real loop
        Arrays.fill(lpInit, false);
        maCnt[0] = maCnt[1] = maCnt[2] = 0;
        if (program != null && codeMode != 0) {
            program.reset(this);                                           // the code's globals back to their first values, setup()
        }
    }

    // ------------------------------------------------------------------ environment
    double ambientAt(double px, double py) {
        if (flickerPct > 0) {
            return ambientSteady(px, py) * (1 + flickerPct / 100.0 * Math.cos(2 * Math.PI * flickerHz * t));
        }
        return ambientSteady(px, py);
    }

    private double ambientSteady(double px, double py) {
        switch (ambientMode) {
            case 1:
                return ambient * (2.0 * px / arenaW);
            case 2: {
                double dx = px - arenaW / 2.0, dy = py - arenaH / 2.0, sg = hotSigma();
                return ambient * (0.25 + 1.75 * Math.exp(-(dx * dx + dy * dy) / (2 * sg * sg)));
            }
            default:
                return ambient;
        }
    }

    /** Brightness of the track image at a point: 0 = black line, 1 = white floor. */
    double grayAt(double px, double py) {
        int ix = (int) Math.floor(px / res), iy = (int) Math.floor(py / res);
        if (ix < 0 || iy < 0 || ix >= imgW || iy >= imgH) {
            return 1.0;
        }
        return (pix[iy * imgW + ix] & 0xFF) / 255.0;
    }

    /** What the sensors see: the image mapped to the floor / line reflectance, with faded patches. */
    double reflectanceAt(double px, double py) {
        double g = grayAt(px, py);
        double fr = floorRefl / 100.0, lr = lineRefl / 100.0;
        for (Patch p : patches) {
            if (!p.slip && p.contains(px, py)) {
                lr = lr + (fr - lr) * p.amount;                     // the line is faded here
            }
        }
        return lr + (fr - lr) * g;
    }

    /** Anything that gives a reflectance for a point: the track, or a made-up line for calibration. */
    interface Surface {

        double at(double px, double py);
    }

    private final Surface trackSurface = (px, py) -> reflectanceAt(px, py);

    double spotReflectance(double px, double py, Surface surf) {
        return spotReflectance(px, py, surf, spotRadius);
    }

    double spotReflectance(double px, double py, Surface surf, double rad) {
        double sum = surf.at(px, py);
        if (rad < 0.75) {
            return sum;
        }
        for (int k = 0; k < 8; k++) {
            sum += surf.at(px + rad * cosT[k], py + rad * sinT[k]);
        }
        return sum / 9.0;
    }

    /** Extra height of sensor i (custom layout only), mm. */
    public double heightOffset(int i) {
        return customLayout ? cuHeight[i] : 0;
    }

    /** Signal gain at a given height (as heightGain, for one sensor). */
    double heightGainAt(double h) {
        double a = 1 + sq(heightRef / heightScale), b = 1 + sq(Math.max(0.3, h) / heightScale);
        return clamp(a / b, 0.02, 4.0);
    }

    // ---- sensor signal chain: LED -> floor -> sensor -> ADC -> filter -> calibration
    private static double sq(double v) {
        return v * v;
    }

    /** Signal strength at the sensor height: 1 at the reference height, weaker when higher, stronger when lower. */
    double heightGain() {
        double a = 1 + sq(heightRef / heightScale), b = 1 + sq(heightNow() / heightScale);
        return clamp(a / b, 0.02, 4.0);
    }

    public double tempNow() {
        return tempC + tempRisePerMin * t / 60.0;
    }

    public double battNow() {
        double v = battVolt * (1.0 - battDrainPctPerMin / 100.0 * t / 60.0 - battSagPct / 100.0 * loadAvg);
        if (battCapacity > 0) {                                            // from the charge used and the current drawn
            double f = usedMah / battCapacity;
            v -= battVolt * (0.12 * Math.min(f, 1) + (f > 0.9 ? (f - 0.9) * 3 : 0)) + currentNow * battResistance;
        }
        return Math.max(0.0, v);
    }

    /** Counts per wheel turn of the chosen encoder (0 = none). */
    public double encCountsPerRev() {
        switch (encType) {
            case 1:
            case 3:
                return encPpr * encGear * (encQuad ? 4 : 1);
            case 2:
                return encPpr * (encQuad ? 2 : 1);                         // a slotted disc on the wheel shaft
            case 4:
                return Math.max(1, encCustomCpr);
            default:
                return 0;
        }
    }

    /** mm of travel per encoder count (0 = no encoder). */
    public double mmPerCount() {
        double c = encCountsPerRev();
        return c > 0 ? wheelCircumference() / c : 0;
    }

    /** Current drawn from the battery now, A: motors (back-EMF and PWM), MCU, IR and white LEDs. */
    double currentDraw() {
        double v0 = Math.max(maxSpeed, 1), vr = battNow() / Math.max(motorVolt, 0.1);
        double i = 0;
        double[][] w = {{cmdL, vL}, {cmdR, vR}};
        int motors = wheelCount == 4 && steerMode == 0 ? 2 : 1;            // a 4-wheel skid robot: two motors a side
        for (double[] k : w) {
            double d = clamp(Math.abs(k[0]) / v0, 0, 1);
            double back = Math.min(1, Math.abs(k[1]) / v0);
            i += motors * stallCurrent * vr * d * (Math.max(0, d - back) + 0.06);    // supply current = duty x motor current
        }
        if (steerMode != 0) {
            i += steerActuator == 0 ? 0.15 : 0.3 * Math.min(1, Math.abs(steerGoal - steerAngle) * 5);
        }
        i += logicCurrent + nSensors * nRows * 0.02 * irPower;
        if (ledsLit()) {
            double[] lx = new double[MAXL], ly = new double[MAXL];
            i += ledLayout(lx, ly) * 0.015 * whitePower;
        }
        return i;
    }

    /** Running time on a full battery at the average current so far, minutes (NaN if unknown). */
    public double runtimeMin() {
        double avg = t > 0.5 ? usedMah / (t / 3600.0) / 1000.0 : currentNow;
        return battCapacity > 0 && avg > 0.001 ? battCapacity / 1000.0 / avg * 60 : Double.NaN;
    }

    double voltScale() {
        double nominal = battVolt - driverDrop;
        return nominal > 0 ? Math.max(0, battNow() - driverDrop) / nominal : 1.0;
    }

    /** IR LED output relative to nominal: falls with temperature, and with the battery if the LEDs run from it. */
    double ledGain() {
        double g = clamp(1 - 0.003 * (tempNow() - 25), 0.05, 2.0);
        if (sensorsOnBattery && battVolt > 1.3) {
            g *= clamp((battNow() - 1.2) / (battVolt - 1.2), 0.0, 1.5);
        }
        return g;
    }

    /** Effective IR power: LED power x height x temperature / battery. */
    public double irEffective() {
        return irPower * heightGain() * ledGain();
    }

    /** Light leaking from an LED straight into a sensor: small with light barriers, large without. */
    double whiteLeakFactor() {
        return barriers ? 0.05 : 0.4;
    }

    /** Ambient light that reaches the sensors (a shield / skirt blocks part of it). */
    double ambientSeen(double px, double py) {
        return ambientAt(px, py) * (1 - clamp(ambientShield, 0, 0.95));
    }

    /** Where the LEDs sit, in the robot's frame (x forward from the axle, y sideways). Returns the count. */
    public int ledLayout(double[] lx, double[] ly) {
        if (customLeds) {
            for (int j = 0; j < cuLedCount; j++) {
                lx[j] = forward + cuLedDx[j];
                ly[j] = cuLedLat[j];
            }
            return cuLedCount;
        }
        double mid = (nSensors - 1) / 2.0;
        if (ledPlace == 1) {                                   // between neighbouring sensors, on the sensor row
            for (int j = 0; j < nSensors - 1; j++) {
                ly[j] = customLayout ? (lat(j) + lat(j + 1)) / 2 : (j + 0.5 - mid) * spacing;
                lx[j] = forward + (customLayout ? (dxOf(j) + dxOf(j + 1)) / 2 : sensorDx(ly[j]));
            }
            return Math.max(0, nSensors - 1);
        }
        double dx = ledPlace == 2 ? ledOffset : -ledOffset;    // in front of the row, or behind it (0 and 3)
        for (int j = 0; j < nSensors; j++) {
            lx[j] = forward + dxOf(j) + dx;
            ly[j] = lat(j);
        }
        return nSensors;
    }

    /** Share of an LED's light reaching a floor spot d mm away (1 straight below), for the sensor height. */
    private double ledKernel(double dx, double dy) {
        double h2 = sq(Math.max(sensorHeight, 1));
        return Math.pow(h2 / (h2 + dx * dx + dy * dy), 1.5);
    }

    /**
     * Add the LED light to one row's readings (in baseBuf). Floor light: reflected off each sensor's spot.
     * Indicator: an LED is lit while its sensor sees the line, and only light leaking into a sensor counts.
     * For ideal reads (calibration, checks) the indicators follow the surface under the sensors.
     */
    private void addLedLight(int r, boolean live) {
        int n = ledLayout(ledX, ledY);
        double wp = whitePower * whiteSeen * LED_SEEN[Math.max(0, Math.min(LED_SEEN.length - 1, ledColor))] * ledGain();
        double mid = (nSensors - 1) / 2.0, fwd = forward - r * rowSpacing, half = (floorRefl + lineRefl) / 200.0;
        for (int j = 0; j < n; j++) {
            if (whiteMode == 1) {
                ledOn[j] = true;
            } else if (customLeds) {
                int si = Math.max(0, Math.min(nSensors - 1, cuLedSensor[j]));
                ledOn[j] = live ? whiteLit[si] : reflBuf[si] < half;
            } else if (ledPlace == 1) {
                ledOn[j] = live ? whiteLit[j] || whiteLit[j + 1] : reflBuf[j] < half || reflBuf[j + 1] < half;
            } else {
                ledOn[j] = live ? whiteLit[j] : reflBuf[j] < half;
            }
        }
        for (int i = 0; i < nSensors; i++) {
            double k = 0, sy = lat(i), sdx = dxOf(i);
            for (int j = 0; j < n; j++) {
                if (ledOn[j]) {
                    k += ledKernel(ledX[j] - fwd - sdx, ledY[j] - sy);
                }
            }
            baseBuf[i] += whiteMode == 1 ? wp * heightGain() * k * (0.05 + 0.95 * reflBuf[i]) : wp * whiteLeakFactor() * k;
        }
    }

    /** LED light at the middle sensor with every LED lit (for the white / black calibration). */
    private double ledAtMiddle() {
        int n = ledLayout(ledX, ledY);
        int mi = (nSensors - 1) / 2;
        double k = 0, sy = lat(mi), sdx = dxOf(mi);
        for (int j = 0; j < n; j++) {
            k += ledKernel(ledX[j] - forward - sdx, ledY[j] - sy);
        }
        return k;
    }

    /** Coupling from a sensor to its neighbour (fraction of the neighbour's own signal). */
    double crosstalkFactor() {
        if (crosstalkPct <= 0) {
            return 0;
        }
        double c = crosstalkPct / 100.0 * Math.min(3.0, sq(10.0 / Math.max(pitchEff(), 2.0)));
        return barriers ? c * 0.1 : c;
    }

    /**
     * One ADC reading (0-1023) for each sensor of a row, before filtering. withNoise = false gives the
     * mean value (used for calibration sweeps and the design checks).
     */
    void readRowInstant(int r, Surface surf, boolean withNoise, double[] out, double[] outX, double[] outY) {
        double c = Math.cos(th), s = Math.sin(th), mid = (nSensors - 1) / 2.0;
        double fwd = forward - r * rowSpacing;
        double ir = irEffective(), xt = crosstalkFactor();
        boolean geoXt = pairGap > 0;
        if (geoXt && crosstalkPct > 0) {
            xt = 1;                                                 // only the switch below: xtCoef holds the size
        }
        for (int i = 0; i < nSensors; i++) {
            double lat = lat(i), fi = fwd + dxOf(i);
            if (arrayYaw != 0 || arrayOffset != 0) {                       // the board is mounted a little off / turned
                double a = Math.toRadians(arrayYaw), dxr = fi - forward;
                double nx = dxr * Math.cos(a) - lat * Math.sin(a), ny = dxr * Math.sin(a) + lat * Math.cos(a);
                fi = forward + nx;
                lat = ny + arrayOffset;
            }
            double px = x + c * fi - s * lat, py = y + s * fi + c * lat;
            outX[i] = px;
            outY[i] = py;
            double hOff = heightOffset(i), refl, kH = 1;
            if (hOff != 0) {                                        // this sensor sits higher or lower: other spot, other signal
                double hi = heightNow() + hOff;
                refl = spotReflectance(px, py, surf, spotRadius * Math.max(0.05, hi) / Math.max(sensorHeight, 0.3));
                kH = heightGainAt(hi) / heightGain();
            } else {
                refl = spotReflectance(px, py, surf);
            }
            if (glossPct > 0) {                                     // shiny tape: glare lifts the line, with sparkle
                double fr = floorRefl / 100.0, lr = lineRefl / 100.0;
                double f = fr - lr > 1e-6 ? clamp01((fr - refl) / (fr - lr)) : 0;
                refl += glossAdd() * f * (withNoise ? 1 + 0.35 * rnd.nextGaussian() : 1);
            }
            reflBuf[i] = refl;
            double amb = ambientShield > 0 ? ambientSeen(px, py) : ambientAt(px, py);
            double own = ir * kH * (0.05 + 0.95 * refl) * (1 + mismatch * mmGain[r * MAXS + i]);
            ownBuf[i] = own;
            double v = own + amb * 0.9 + mismatch * 0.1 * mmOff[r * MAXS + i];
            if (withNoise) {
                v += noise * rnd.nextGaussian();
            }
            baseBuf[i] = v;
        }
        if (withNoise ? ledsLit() : whiteLeds) {                    // live readings follow the switch; checks use the setting
            addLedLight(r, withNoise);
        }
        for (int i = 0; i < nSensors; i++) {
            double v = baseBuf[i];
            if (xt > 0) {                                           // light from the neighbours' LEDs
                for (int j = 0; j < nSensors; j++) {
                    if (j != i) {
                        v += (geoXt ? xtCoef(i, j) : xt * Math.pow(0.25, Math.abs(i - j) - 1)) * ownBuf[j];
                    }
                }
            }
            double o = clamp01(v);
            if (esp32Adc) {                                         // dead below ~0.13 V, saturated above ~3.1 V (of 3.3 V)
                o = clamp01((o - 0.04) / 0.91);
            }
            o *= 1023.0;
            if (adcBits > 0) {                                      // the ADC's steps
                double lv = (1 << Math.min(adcBits, 16)) - 1;
                o = Math.round(o / 1023.0 * lv) / lv * 1023.0;
            }
            if (r == 0 && i == stuckSensor && withNoise) {           // a stuck sensor (live readings only; calibration sees it working)
                o = stuckOnLine ? 25 : 1000;
            }
            out[i] = o;
        }
    }

    /** Moving average or low-pass filter on the readings of one row. */
    private void filterRow(int r, double[] inst, double dtSample) {
        double[] out = rawRow[r];
        if (filterMode != lastFilterMode) {                         // mode changed: start the filters afresh
            lastFilterMode = filterMode;
            for (int k = 0; k < MAXR; k++) {
                lpInit[k] = false;
                maCnt[k] = 0;
            }
        }
        if (filterMode == 1) {
            int pos = maPos[r], n = Math.max(1, Math.min(filterN, 16));
            for (int i = 0; i < nSensors; i++) {
                maBuf[r][i][pos] = inst[i];
            }
            maPos[r] = (pos + 1) % 16;
            maCnt[r] = Math.min(maCnt[r] + 1, 16);
            int cnt = Math.min(n, maCnt[r]);
            for (int i = 0; i < nSensors; i++) {
                double sum = 0;
                for (int k = 0; k < cnt; k++) {
                    sum += maBuf[r][i][(pos - k + 32) % 16];
                }
                out[i] = sum / cnt;
            }
        } else if (filterMode == 2) {
            double tau = Math.max(filterTauMs, 0.0) / 1000.0, a = tau + dtSample > 0 ? dtSample / (tau + dtSample) : 1.0;
            for (int i = 0; i < nSensors; i++) {
                if (!lpInit[r]) {
                    lpState[r][i] = inst[i];
                } else {
                    lpState[r][i] += a * (inst[i] - lpState[r][i]);
                }
                out[i] = lpState[r][i];
            }
            lpInit[r] = true;
        } else {
            System.arraycopy(inst, 0, out, 0, nSensors);
        }
    }

    /** Per-sensor decision level used by the digital readout. */
    double thresholdFor(int r, int i) {
        return perSensorCal ? (calMin[r][i] + calMax[r][i]) / 2.0 : threshold;
    }

    /** Darkness of one reading: 0-1 (analog) or 0/1 (digital), using the active calibration. */
    double darkOf(int r, int i, double v) {
        if (analogMode) {
            double lo = perSensorCal ? calMin[r][i] : calBlack, hi = perSensorCal ? calMax[r][i] : calWhite;
            double span = hi - lo;
            return span < 5 ? 0 : clamp01((hi - v) / span);
        }
        return v < thresholdFor(r, i) ? 1 : 0;
    }

    /** Turn the (filtered) ADC readings into darkness 0-1 (analog) or 0/1 (digital). */
    void normalize() {
        for (int r = 0; r < nRows; r++) {
            for (int i = 0; i < nSensors; i++) {
                if (!analogMode && hystPct > 0) {                         // comparator with hysteresis: keeps its state near the threshold
                    double h = hystPct / 100.0 * Math.abs(calWhite - calBlack) / 2, thr = thresholdFor(r, i);
                    hystDark[r][i] = hystDark[r][i] ? rawRow[r][i] < thr + h : rawRow[r][i] < thr - h;
                    darkRow[r][i] = hystDark[r][i] ? 1 : 0;
                } else {
                    darkRow[r][i] = darkOf(r, i, rawRow[r][i]);
                }
                whiteLit[r * MAXS + i] = darkRow[r][i] > 0.5;          // indicator LED follows the sensor
            }
        }
    }

    /** Read all sensors now: ADC, filter, calibration. (The controller does this each loop, or a sensor task does it.) */
    /** Test hook: called at the end of every controller run with the inputs it used and what it decided. */
    java.util.function.Consumer<double[]> tap;
    private final double[] tapIn = new double[MAXR * MAXS + 1];

    void sampleSensors() {
        double dts = lastSampleT < 0 ? 0 : Math.max(0, t - lastSampleT);
        if (tap != null) {
            tapIn[MAXR * MAXS] = dts;
        }
        for (int r = 0; r < nRows; r++) {
            readRowInstant(r, trackSurface, true, instBuf, sensXRow[r], sensYRow[r]);
            if (respMs > 0) {                                       // the sensor follows the light with a delay
                double a = !respInit || dts <= 0 ? 1 : 1 - Math.exp(-dts / (respMs / 1000.0));
                for (int i = 0; i < nSensors; i++) {
                    respState[r][i] += a * (instBuf[i] - respState[r][i]);
                    instBuf[i] = respState[r][i];
                }
            }
            if (tap != null) {
                System.arraycopy(instBuf, 0, tapIn, r * MAXS, MAXS);
            }
            filterRow(r, instBuf, dts);
        }
        lastSampleT = t;
        respInit = true;
        normalize();
    }

    void readSensors() {
        sampleSensors();
    }

    // ---- calibration
    /** Measure ideal white/black readings at the robot's current spot and put the threshold in between (one pair for all sensors). */
    public void calibrate() {
        double[] lv = levelsNow();
        calWhite = lv[0];
        calBlack = lv[1];
        threshold = (calWhite + calBlack) / 2.0;
    }

    /**
     * What a middle sensor reads now on the floor and on the line (no noise), with the light, battery,
     * temperature and LEDs as they are at this moment: {white, black}.
     */
    public double[] levelsNow() {
        double amb = ambientSeen(x + Math.cos(th) * forward, y + Math.sin(th) * forward);
        double ir = irEffective(), fr = floorRefl / 100.0, lr = lineRefl / 100.0 + glossAdd();
        double xt = crosstalkFactor(), nb = 0;                                    // light from the neighbours of a middle sensor
        boolean geoXt = pairGap > 0;
        if (xt > 0) {
            int mid = (nSensors - 1) / 2;
            for (int j = 0; j < nSensors; j++) {
                if (j != mid) {
                    nb += geoXt ? xtCoef(mid, j) : Math.pow(0.25, Math.abs(mid - j) - 1);
                }
            }
        }
        double k = 1 + (geoXt ? nb : xt * nb);
        double wf = 0, wl = 0;                                                    // white LEDs: floor light, or indicator lit on the line
        if (whiteLeds) {
            double wp = whitePower * whiteSeen * LED_SEEN[Math.max(0, Math.min(LED_SEEN.length - 1, ledColor))] * ledGain();
            double kk = ledAtMiddle();
            wf = whiteMode == 1 ? wp * heightGain() * kk * (0.05 + 0.95 * fr) : 0;
            wl = whiteMode == 1 ? wp * heightGain() * kk * (0.05 + 0.95 * lr) : wp * whiteLeakFactor() * kk;
        }
        return new double[]{clamp01(ir * (0.05 + 0.95 * fr) * k + amb * 0.9 + wf) * 1023.0,   // the floor, as the sensors see it
            clamp01(ir * (0.05 + 0.95 * lr) * k + amb * 0.9 + wl) * 1023.0};               // the line (neighbours also on black)
    }

    /**
     * Is the calibration still good for the conditions now? Returns {level 0 ok / 1 check / 2 fail, margin %}
     * and a short text in calText. Digital: how far the floor and the line now read from the threshold;
     * analog: how far they drifted from the calibrated white / black.
     */
    public double[] calibrationStatus() {
        double[] lv = levelsNow();
        double w = lv[0], b = lv[1], span = w - b;
        if (w >= 1022.5 && b >= 1000) {
            calText = "saturated: everything reads white (too much light)";
            return new double[]{2, 0};
        }
        if (span < 0.08 * 1023) {
            calText = String.format("contrast too low (%.0f of 1023)", span);
            return new double[]{2, 0};
        }
        double margin;
        if (!analogMode) {
            double thr = perSensorCal ? (calMin[0][nSensors / 2] + calMax[0][nSensors / 2]) / 2 : threshold;
            if (w <= thr) {
                calText = "floor now reads as line: recalibrate";
                return new double[]{2, 0};
            }
            if (b >= thr) {
                calText = "line now reads as floor: recalibrate";
                return new double[]{2, 0};
            }
            margin = Math.min(w - thr, thr - b) / span * 200;              // 100 % = threshold exactly half-way
        } else {
            double cw = perSensorCal ? calMax[0][nSensors / 2] : calWhite, cb = perSensorCal ? calMin[0][nSensors / 2] : calBlack;
            double drift = Math.max(Math.abs(w - cw), Math.abs(b - cb)) / Math.max(cw - cb, 1);
            margin = Math.max(0, 100 - drift * 200);
        }
        boolean sat = w >= 1022.5;
        calText = String.format("margin %.0f %%%s", margin, sat ? ", white saturates" : "");
        return new double[]{margin < 25 ? 2 : margin < 60 || sat ? 1 : 0, margin};
    }

    public String calText = "";

    private Surface virtualLine(double offset) {
        double c = Math.cos(th), s = Math.sin(th), w = Math.max(lineWidth, 1) / 2, fr = floorRefl / 100.0, lr = lineRefl / 100.0;
        double ox = x, oy = y;
        return (px, py) -> Math.abs(-s * (px - ox) + c * (py - oy) - offset) <= w ? lr : fr;
    }

    /**
     * Sweep every sensor across a straight line of the current line width and remember each sensor's own
     * darkest and brightest reading (like the sensor_min / sensor_max sweep on a real robot).
     */
    public void calibrateSweep() {
        double half = halfSpan() + Math.abs(customLayout ? cuLat[0] + cuLat[nSensors - 1] : 0), w = Math.max(lineWidth, 1);
        for (int r = 0; r < MAXR; r++) {
            Arrays.fill(calMin[r], 1e9);
            Arrays.fill(calMax[r], -1e9);
        }
        for (double off = -(half + w); off <= half + w + 1e-9; off += 0.5) {
            Surface line = virtualLine(off);
            for (int r = 0; r < nRows; r++) {
                readRowInstant(r, line, false, instBuf, tmpX, tmpY);
                for (int i = 0; i < nSensors; i++) {
                    calMin[r][i] = Math.min(calMin[r][i], instBuf[i]);
                    calMax[r][i] = Math.max(calMax[r][i], instBuf[i]);
                }
            }
        }
        for (int r = 0; r < MAXR; r++) {                            // rows that were not swept keep full scale
            for (int i = 0; i < MAXS; i++) {
                if (calMax[r][i] < calMin[r][i]) {
                    calMin[r][i] = 0;
                    calMax[r][i] = 1023;
                }
            }
        }
    }

    /** Mean ADC readings of the front row with a straight line at 'offset' mm from the array centre (no noise). */
    public double[] rawOnLine(double offset) {
        readRowInstant(0, virtualLine(offset), false, instBuf, tmpX, tmpY);
        return Arrays.copyOf(instBuf, nSensors);
    }

    /**
     * Where the front row says a straight line is, in mm, when the line is really at 'trueOffset'
     * (+ = right of the array centre). Uses analog weighting with the chosen calibration; no noise.
     */
    public double measuredPosition(double trueOffset, boolean perSensor) {
        readRowInstant(0, virtualLine(trueOffset), false, instBuf, tmpX, tmpY);
        double mid = (nSensors - 1) / 2.0, sum = 0, sumW = 0;
        for (int i = 0; i < nSensors; i++) {
            double lo = perSensor ? calMin[0][i] : calBlack, hi = perSensor ? calMax[0][i] : calWhite, span = hi - lo;
            double d = span < 5 ? 0 : clamp01((hi - instBuf[i]) / span);
            sum += d;
            sumW += d * lat(i);
        }
        return sum < 0.05 ? Double.NaN : sumW / sum;
    }

    // ------------------------------------------------------------------ controller
    /** Line position (mm, + = right) seen by one sensor row; NaN when that row sees no line. */
    private double rowPosition(int r) {
        if (r == 0) {
            junctionNow = false;
        }
        double[] d = darkRow[r];
        double mid = (nSensors - 1) / 2.0, sum = 0, sumW = 0;
        int first = -1, last = -1, cnt = 0;
        for (int i = 0; i < nSensors; i++) {
            sum += d[i];
            sumW += d[i] * lat(i);
            if (d[i] > 0.5) {
                cnt++;
                if (first < 0) {
                    first = i;
                }
                last = i;
            }
        }
        if (!(sum > (analogMode ? 0.2 : 0.5))) {
            return Double.NaN;
        }
        if (junctionRule != 0 && cnt >= 2) {                                // a fork: two separate lines under the array
            int gapAt = -1;
            for (int i = first + 1; i < last; i++) {
                if (d[i] <= 0.5) {
                    gapAt = i;
                    break;
                }
            }
            if (gapAt > 0) {
                int lo = junctionRule == 1 ? 0 : gapAt, hi = junctionRule == 1 ? gapAt : nSensors;
                if (junctionRule == 2) {                                       // right branch: start after the last gap
                    for (int i = last - 1; i > first; i--) {
                        if (d[i] <= 0.5) {
                            lo = i + 1;
                            break;
                        }
                    }
                }
                double gs = 0, gw = 0;
                for (int i = lo; i < hi; i++) {
                    gs += d[i];
                    gw += d[i] * lat(i);
                }
                if (gs > 0) {
                    return errorMode == 1 ? gw : gw / gs;
                }
            }
        }
        // a crossing / T / sharp corner reaches the edge of the array; a fork that is just splitting does not
        if (junctionRule != 0 && cnt >= Math.max(3, (int) Math.ceil(0.6 * nSensors)) && (first == 0 || last == nSensors - 1)) {
            if (r == 0) {
                junctionNow = true;
            }
            return lat(junctionRule == 1 ? first : last);                     // a junction: lean towards the chosen branch
        }
        return errorMode == 1 ? sumW : sumW / sum;
    }

    /** A program from the Code tab: runs every controller loop (no Swing here). */
    public interface Program {

        /** Restart: globals back to their first values, setup() runs. */
        void reset(Model m);

        /** One loop; returns the work done (interpreter steps), for the MCU timing. */
        long loop(Model m, double cdt);
    }

    public transient Program program;                        // null = no code
    public String codeSource = "";                           // the applied code (the Arduino export puts it in the sketch)
    public int codeMode = 0;                                 // 0 built-in controller, 1 built-in + code (hooks), 2 code only
    public double codeCyclesPerStep = 40;                    // MCU cycles one step of the code costs (rough)
    private double codeCycles;                               // cost of the last code run, cycles
    private boolean builtinRan;
    private double codeDt;

    void controller(double cdt) {
        loopCount++;
        if (!sensorTask) {
            sampleSensors();
        }
        builtinRan = false;
        codeDt = cdt;
        Program prog = codeMode != 0 ? program : null;
        if (prog == null || codeMode == 1) {
            builtinControl(cdt);
        } else {                                                           // code only: nothing moves unless it says so
            pendL = pendR = 0;
            pendSteer = 0;
        }
        codeCycles = 0;
        if (prog != null) {
            long steps = prog.loop(this, cdt);
            codeCycles = steps * codeCyclesPerStep;
            if (!builtinRan) {                                             // full code: keep the error / lost-line statistics going
                double e = rowPosition(0);
                lineVisible = !Double.isNaN(e);
                if (lineVisible) {
                    error = e;
                }
            }
        }
        finishControl(cdt);
    }

    // ---- what the code can use (Code tab)
    /** Run the built-in line follower now (once per loop): its wheel speeds become the output. */
    public void codeFollowLine() {
        if (!builtinRan) {
            builtinControl(codeDt);
        }
    }

    /** Wheel speeds the code asks for, mm/s (+ forward). */
    public void codeDrive(double l, double r) {
        pendL = clamp(l, -maxSpeed, maxSpeed);
        pendR = clamp(r, -maxSpeed, maxSpeed);
    }

    /** Steering angle the code asks for (car), degrees, + = right. */
    public void codeSteer(double deg) {
        double lock = clamp(maxSteerDeg, 1, 60);
        pendSteer = Math.toRadians(clamp(deg, -lock, lock));
    }

    public double codePendL() {
        return pendL;
    }

    public double codePendR() {
        return pendR;
    }

    public double codePendSteerDeg() {
        return Math.toDegrees(pendSteer);
    }

    /** Line position of the front row now, mm (+ = right), NaN when no sensor sees it. */
    public double codeLinePosition() {
        return rowPosition(0);
    }

    public double codeDark(int i) {
        return i >= 0 && i < nSensors ? darkRow[0][i] : 0;
    }

    public double codeRaw(int i) {
        return i >= 0 && i < nSensors ? rawRow[0][i] : 0;
    }

    void builtinControl(double cdt) {
        builtinRan = true;
        double halfSpan = halfSpan(), spacing = pitchEff();
        double e0 = rowPosition(0);
        if (junctionRule == 0) {
            turnActive = false;
        } else if (turnActive) {
            // committed turn: steer hard until the heading has changed by about 80 degrees (a gyro / encoder turn)
            double turned = (th - turnStartTh) * turnSign;
            if (turned >= Math.toRadians(80)) {
                turnActive = false;
                turnCooldown = t + 0.4;
            } else {
                e0 = turnSign * (halfSpan + spacing);
            }
        } else if (junctionNow && t >= turnCooldown) {
            turnActive = true;                                                 // junction found: commit to the chosen side
            turnStartTh = th;
            turnSign = junctionRule == 1 ? -1 : 1;                             // left = heading decreases (screen y points down)
        }
        boolean seen = !Double.isNaN(e0);
        seenCount = seen ? seenCount + 1 : 0;
        boolean accept = seen && (lineVisible || seenCount >= Math.max(1, confirmCount));   // a lost line must be seen again N times
        boolean changed = accept != lineVisible;
        recoverSpin = recoverStop = false;
        if (accept) {
            error = e0;
            if (nRows >= 2 && rowSpacing > 0) {                                // line direction from the front and rear row
                double eb = rowPosition(nRows - 1);
                if (!Double.isNaN(eb)) {
                    error = e0 + (e0 - eb) / ((nRows - 1) * rowSpacing) * predictAheadMm;
                }
            }
            lineVisible = true;
            lastValidError = error;
        } else {
            if (lineVisible) {
                lostSince = t;
            }
            lineVisible = false;
            double hard = Math.signum(lastValidError) * (halfSpan + spacing);
            switch (lostMode) {
                case 1:                                                        // keep doing what it did
                    error = lastValidError;
                    break;
                case 2:                                                        // always steer hard towards where it was
                case 4:
                    error = hard;
                    recoverSpin = lostMode == 4;
                    break;
                case 3:
                    error = 0;
                    break;
                case 5:
                    error = 0;
                    recoverStop = true;
                    break;
                default:
                    if (errorMode == 1) {
                        error = 0;                                             // the raw sum simply reads zero
                    } else {
                        // line left the array sideways -> steer hard that way; vanished near the centre (gap) -> go straight
                        error = Math.abs(lastValidError) > halfSpan * edgeZonePct / 100.0 ? hard : 0;
                    }
            }
            if (lostTimeout > 0 && t - lostSince > lostTimeout) {
                recoverStop = true;                                            // gave up: stop
            }
        }
        double dRaw = firstCycle || (noDKick && changed) ? 0 : (error - prevError) / cdt;
        firstCycle = false;
        double tau = dFilterMs / 1000.0;
        dFilt += (dRaw - dFilt) * (cdt / (tau + cdt));
        prevError = error;

        integral += error * cdt;
        double iLim = ki > 1e-9 ? (maxSpeed * 0.5) / ki : 0;            // anti-windup clamp
        integral = clamp(integral, -iLim, iLim);

        pTerm = kp * (error + (predictMs > 0 ? dFilt * predictMs / 1000.0 : 0.0));
        iTerm = ki * integral;
        dTerm = kd * dFilt;
        outTerm = clamp(pTerm + iTerm + dTerm, -maxSpeed, maxSpeed);

        // slow down when the error is large (corners) so the robot can actually make the turn
        double edge = Math.max(halfSpan + spacing, 1.0);
        double eff = baseSpeed * (1.0 - cornerSlow * Math.min(1.0, Math.abs(error) / edge));
        double lo = allowReverse ? -maxSpeed : 0;
        if (recoverStop) {
            pendL = pendR = 0;
            pendSteer = 0;
        } else if (recoverSpin) {                                           // spin on the spot towards the last side
            double sp = Math.min(Math.abs(searchSpeed), maxSpeed), dir = lastValidError >= 0 ? 1 : -1;
            if (steerMode != 0) {                                           // a car cannot spin: full lock, crawl
                pendSteer = dir * Math.toRadians(clamp(maxSteerDeg, 1, 60));
                pendL = pendR = sp;
            } else {
                pendL = dir * sp;
                pendR = -dir * sp;
            }
        } else if (steerMode != 0) {
            // car: the same PID output asks for the same turn rate a differential robot would get (2 * out / track),
            // the servo angle that gives it at this speed is atan(rate * wheelbase / speed)
            double wDes = 2 * outTerm / Math.max(wheelBase, 1);
            double vRef = Math.max(Math.max(eff, Math.abs(vBody)), 50);
            double lock = Math.toRadians(clamp(maxSteerDeg, 1, 60));
            pendSteer = clamp(Math.atan(wDes * Math.max(axleSpacing, 10) / vRef), -lock, lock);
            pendL = pendR = clamp(eff, lo, maxSpeed);
        } else {
            pendL = clamp(eff + outTerm, lo, maxSpeed);
            pendR = clamp(eff - outTerm, lo, maxSpeed);
        }
    }

    /** After the controller and the code: the encoder speed loop, the compute time, the test hook. */
    private void finishControl(double cdt) {
        double lo = allowReverse || codeMode == 2 && program != null ? -maxSpeed : 0;
        if (encType > 0) {                                                 // encoders: measured wheel speeds (counts per loop)
            double mpc = mmPerCount();
            long cl = (long) Math.floor(encPosL / mpc), cr = (long) Math.floor(encPosR / mpc);
            encSpeedL = (cl - encLastL) * mpc / cdt;
            encSpeedR = (cr - encLastR) * mpc / cdt;
            encLastL = cl;
            encLastR = cr;
            if (speedLoop && !recoverStop) {                               // PI on each wheel: holds the speed against mismatch and sag
                double eL = pendL - encSpeedL, eR = pendR - encSpeedR, lim = maxSpeed * 0.5 / Math.max(speedKi, 1e-6);
                encIntL = clamp(encIntL + eL * cdt, -lim, lim);
                encIntR = clamp(encIntR + eR * cdt, -lim, lim);
                pendL = clamp(pendL + speedKp * eL + speedKi * encIntL, lo, maxSpeed);
                pendR = clamp(pendR + speedKp * eR + speedKi * encIntR, lo, maxSpeed);
            }
        }
        applyAt = t + computeSec();                                        // MCU is busy until then
        hasPending = true;
        if (tap != null) {                                                 // {cdt, dts, t, vBody, inputs[rows*n], error, out, l, r, steer deg, seen}
            double[] rec = new double[4 + nRows * nSensors + 6];
            rec[0] = cdt;
            rec[1] = tapIn[MAXR * MAXS];
            rec[2] = t;
            rec[3] = vBody;
            int k = 4;
            for (int r = 0; r < nRows; r++) {
                for (int i = 0; i < nSensors; i++) {
                    rec[k++] = tapIn[r * MAXS + i];
                }
            }
            rec[k++] = error;
            rec[k++] = outTerm;
            rec[k++] = pendL;
            rec[k++] = pendR;
            rec[k++] = Math.toDegrees(pendSteer);
            rec[k] = lineVisible ? 1 : 0;
            tap.accept(rec);
        }
    }

    // ------------------------------------------------------------------ disturbances
    /** Knock the robot sideways and turn it a little. */
    public void bump(double lateralMm, double headingRad) {
        x += -Math.sin(th) * lateralMm;
        y += Math.cos(th) * lateralMm;
        th += headingRad;
    }

    /** Block a wheel for a while (a jam). */
    public void stallWheel(boolean left, double seconds) {
        if (left) {
            stallL = t + seconds;
        } else {
            stallR = t + seconds;
        }
    }

    /** A patch ahead of the robot: faded line (amount 0-1 = how much of the line contrast is lost) or slippery (amount = mu). */
    public void addPatch(boolean slip, double amount, double length, double width, double aheadMm) {
        double d = forward + aheadMm + length / 2;
        patches.add(new Patch(x + Math.cos(th) * d, y + Math.sin(th) * d, th, length, width, slip, amount));
    }

    public void clearPatches() {
        patches.clear();
    }

    // ------------------------------------------------------------------ physics
    public void advance(double seconds) {
        double dt = stepSec();
        stepAcc += seconds;
        int guard = 0;
        while (stepAcc >= dt && guard++ < 400000 && running) {
            step(dt);
            stepAcc -= dt;
        }
        if (stepAcc >= dt) {
            stepAcc = 0;
        }
    }

    private static double lagCoef(double lagMs, double dt) {
        return lagMs < 1 ? 1.0 : 1.0 - Math.exp(-dt / (lagMs / 1000.0));
    }

    /** Wheel speed a motor is driven towards for a given command (dead zone + speed factor). */
    private double motorTarget(double cmd, double deadPct, double gainPct) {
        double dz = deadPct / 100.0 * maxSpeed;
        if (Math.abs(cmd) < dz) {
            return 0;
        }
        return cmd * gainPct / 100.0 * warmFactor();
    }

    /** Time to wait before the next controller run: the loop period plus this round's random jitter. */
    private double nextLoopWait() {
        return Math.max(Math.max(stepSec(), computeSec()), loopPeriodSec() + jitterOff);
    }

    void step(double dt) {
        if (outOfArena) {
            return;
        }
        if (x < -80 || x > arenaW + 80 || y < -80 || y > arenaH + 80) {
            outOfArena = true;
            vL = vR = 0;
            return;
        }
        t += dt;
        ctrlAcc += dt;
        currentNow = currentDraw();
        usedMah += currentNow * dt / 3.6;
        if (brownoutV > 0 && battNow() < brownoutV && t >= brownoutUntil) {   // the MCU resets: motors off, starts again
            brownouts++;
            brownoutUntil = t + 0.3;
            cmdL = cmdR = pendL = pendR = 0;
            hasPending = false;
            integral = dFilt = prevError = 0;
            firstCycle = true;
        }
        if (t < brownoutUntil) {
            cmdL = cmdR = 0;
            ctrlAcc = 0;
        }
        if (encType > 0) {
            encPosL += vL * dt;
            encPosR += vR * dt;
        }
        if (sensorTask) {                                                  // the sensor task runs on its own clock
            sensAcc += dt;
            double sp = 1.0 / Math.max(sensorTaskHz, 1.0);
            if (sensAcc >= sp - 1e-12) {
                sensAcc = Math.max(0, sensAcc - sp);
                sampleSensors();
            }
        }
        if (ctrlAcc >= nextLoopWait() - 1e-9) {
            controller(ctrlAcc);
            ctrlAcc = 0;
            jitterOff = jitterUs > 0 ? jrnd.nextGaussian() * jitterUs * 1e-6 : 0;
        }
        if (hasPending && applyAt - t < dt) {                              // compute finished -> write PWM
            cmdL = pendL;
            cmdR = pendR;
            cmdSteer = pendSteer;
            hasPending = false;
            double lock = Math.toRadians(clamp(maxSteerDeg, 1, 60));
            double goal = steerActuator == 1                                // a DC motor only knows full left, centre (spring) or full right
                    ? (Math.abs(cmdSteer) > 0.3 * lock ? Math.signum(cmdSteer) * lock : 0) : cmdSteer;
            steerQueue.addLast(new double[]{t + Math.max(steerDelayMs, 0) / 1000.0, goal});   // reacts one delay later
        }
        if (steerMode != 0) {                                              // the servo / motor turns at its own speed
            while (!steerQueue.isEmpty() && steerQueue.peekFirst()[0] <= t + 1e-12) {
                steerGoal = steerQueue.pollFirst()[1];
            }
            double step = Math.toRadians(Math.max(servoRate, 1)) * dt;
            steerAngle += clamp(steerGoal - steerAngle, -step, step);
        }

        if (driveModel == 1) {
            physicalDrive(dt);
        } else {
            simpleDrive(dt);
        }
        double v, w;
        if (driveModel == 1) {
            v = pV;
            w = pW;
            vBody = v;
        } else {
            v = (vL + vR) / 2.0;
            w = steerMode != 0 ? v * Math.tan(steerAngle) / Math.max(axleSpacing, 10)   // car: steered front wheels
                    : (vL - vR) / (wheelBase * skidChi());              // a 4-wheel robot turns less: its wheels scrub sideways
            double mu = gripHere();
            if (mu > 0) {
                // grip limit (friction circle): speeding up / braking uses grip first, turning gets what is left.
                // Wheels that ask for more spin or skid, so the chassis lags behind them and understeers.
                double aMax = mu * 9810.0;
                double aLong = clamp((v - vBody) / dt, -aMax, aMax);
                vBody += aLong * dt;
                v = vBody;
                double aLat = Math.sqrt(Math.max(0, aMax * aMax - aLong * aLong));
                double wmax = aLat / Math.max(Math.abs(v), 30.0);
                w = clamp(w, -wmax, wmax);
            } else {
                vBody = v;
            }
        }
        if (bounceMmPerG != 0) {                                           // forward acceleration, smoothed (the chassis lifts / dips)
            double a = (v - prevVForAccel) / dt;
            aLong += (a - aLong) * Math.min(1, dt / 0.02);
            prevVForAccel = v;
        }
        if (motorWarmPct > 0) {                                            // motors warm with load, cool with time (5 min)
            double duty = (Math.abs(cmdL) + Math.abs(cmdR)) / (2 * Math.max(maxSpeed, 1));
            motorHeat += duty * duty * dt / 60.0 - motorHeat * dt / 300.0;
        }
        if (tipCheck && Math.abs(v * w) > tipAccel()) {                  // sideways acceleration beyond the tipping point
            tipped = true;
            outOfArena = true;
            vL = vR = 0;
            return;
        }
        integrate(v, w, dt);
    }

    /** Wheel grip where the robot is: the setting, or a slippery patch under it (0 = unlimited). */
    private double gripHere() {
        double mu = tractionMu > 0 && gripLossPctPerMin > 0 ? tractionMu * Math.max(0.3, 1 - gripLossPctPerMin / 100.0 * t / 60.0) : tractionMu;
        for (Patch p : patches) {
            if (p.slip && p.contains(x, y)) {
                mu = mu > 0 ? Math.min(mu, p.amount) : p.amount;
            }
        }
        return mu;
    }

    /** Simple model: each wheel moves towards its commanded speed with the motor lag. */
    private void simpleDrive(double dt) {
        double vs = voltScale();
        if (t < stallL) {
            vL = 0;
        } else {
            vL += (motorTarget(cmdL, deadL, gainL) * vs - vL) * lagCoef(lagL, dt);
        }
        if (t < stallR) {
            vR = 0;
        } else {
            vR += (motorTarget(cmdR, deadR, gainR) * vs - vR) * lagCoef(lagR, dt);
        }
    }

    // ---- physical drive model
    /** Stall force of one motor at the tyre, N, at the rated voltage. */
    public double stallForceN() {
        return stallTorque * 98.0665 / Math.max(wheelDia / 2, 1);
    }

    /** No-load wheel speed at the rated voltage, mm/s. */
    public double noLoadSpeedRated() {
        return wheelCircumference() * motorRpm / 60.0;
    }

    /** Mass the motors accelerate, kg (the spinning motor and gears add roughly a fifth). */
    public double effMassKg() {
        return Math.max(massG, 10) / 1000.0 * 1.2;
    }

    /**
     * Turning inertia about the axle centre, kg.mm^2: 60 % of the mass spread evenly over the chassis plate
     * (its real shape), 40 % in the motors, wheels and battery near the axle ends.
     */
    public double yawInertia() {
        double[] g = chassisGeometry();
        double plate = g[0] > 1 ? g[3] / g[0] : 0;                         // polar moment per area, mm^2
        double hw = wheelBase / 2, hl = wheelCount == 4 ? axleSpacing / 2 : 0;
        return Math.max(massG, 10) / 1000.0 * (0.6 * plate + 0.4 * (hw * hw + hl * hl));
    }

    // ------------------------------------------------------------------ sensor array and chassis shape
    /** Forward offset of a sensor at lateral position lat (mm) for the array shape: 0 for a straight array. */
    public double sensorDx(double lat) {
        if (arrayShape == 1) {                                             // V / arrow: + angle = outer sensors further forward
            return Math.abs(lat) * Math.tan(Math.toRadians(clamp(arrayAngle, -60, 60)));
        }
        if (arrayShape == 2) {                                             // arc, bulging forward: outer sensors trail back
            double r = Math.max(arrayRadius, Math.abs(lat) + 1);
            return -(r - Math.sqrt(r * r - lat * lat));
        }
        return 0;
    }

    /** Forward reach of the array (largest sensorDx over the sensors), mm. */
    public double arrayReach() {
        double mx = 0;
        for (int i = 0; i < nSensors; i++) {
            mx = Math.max(mx, dxOf(i));
        }
        return mx;
    }

    private double bodyRearAuto() {
        double wheels = wheelCount == 4 ? axleSpacing / 2 : 0;
        return bodyRear > 0 ? bodyRear : Math.max(25, wheels + wheelDia / 2 + 5);
    }

    /**
     * Skid steering: how much wider a 4-wheel robot "behaves" than its track width when turning
     * (1 for two wheels and a caster). Grows with the axle spacing compared with the track width.
     */
    /** Car steering: the tightest turn radius at full lock (centre of the rear axle), mm. */
    public double minTurnRadius() {
        return steerMode == 0 ? 0 : Math.max(axleSpacing, 10) / Math.tan(Math.toRadians(clamp(maxSteerDeg, 1, 60)));
    }

    public double skidChi() {
        if (wheelCount != 4 || steerMode != 0) {
            return 1;
        }
        double r = axleSpacing / Math.max(wheelBase, 1);
        return 1 + skidFactor * r * r;
    }

    /** Wheel centres in the robot frame: {x, y} for every wheel (2 or 4). */
    public List<double[]> wheelPositions() {
        List<double[]> l = new ArrayList<>();
        double hw = wheelBase / 2;
        if (wheelCount == 4) {
            for (double ax : new double[]{axleSpacing / 2, -axleSpacing / 2}) {
                l.add(new double[]{ax, -hw});
                l.add(new double[]{ax, hw});
            }
        } else {
            l.add(new double[]{0, -hw});
            l.add(new double[]{0, hw});
        }
        return l;
    }

    private double bodyWidthAuto() {
        return bodyWidth > 0 ? bodyWidth : Math.max(20, wheelBase - 12);
    }

    /** The chassis plate seen from above, in the robot frame (x forward from the axle, y to the right), mm. */
    public java.awt.geom.Area chassisOutline() {
        double rear = -bodyRearAuto(), w = bodyWidthAuto();
        double front = bodyFront > 0 ? bodyFront : forward + arrayReach() + 12;
        switch (chassisShape) {
            case 1: {                                                      // round plate centred on the axle
                double d = roundDia > 0 ? roundDia : Math.max(w, 2 * (forward + arrayReach() + 8));
                return new java.awt.geom.Area(new java.awt.geom.Ellipse2D.Double(-d / 2, -d / 2, d, d));
            }
            case 2: {                                                      // T: body at the axle, arm out to the sensor board
                double bf = bodyFront > 0 ? bodyFront : wheelDia / 2 + 15;
                java.awt.geom.Area a = new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Double(rear, -w / 2, bf - rear, w, 10, 10));
                double armEnd = Math.max(bf + 5, forward + arrayReach() - 4);
                double aw = Math.max(6, Math.min(armWidth, w));
                a.add(new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(bf - 6, -aw / 2, armEnd - bf + 6, aw)));
                return a;
            }
            case 3:
                if (customOutline.size() >= 3) {
                    java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
                    for (int i = 0; i < customOutline.size(); i++) {
                        double[] q = customOutline.get(i);
                        if (i == 0) {
                            p.moveTo(q[0], q[1]);
                        } else {
                            p.lineTo(q[0], q[1]);
                        }
                    }
                    p.closePath();
                    return new java.awt.geom.Area(p);
                }
                // fall through: no outline yet
            default:
                return new java.awt.geom.Area(new java.awt.geom.RoundRectangle2D.Double(rear, -w / 2, front - rear, w, 12, 12));
        }
    }

    /** Sensor positions of every row in the robot frame: {x, y} pairs. */
    public List<double[]> sensorPositions() {
        List<double[]> l = new ArrayList<>();
        double mid = (nSensors - 1) / 2.0;
        for (int r = 0; r < nRows; r++) {
            for (int i = 0; i < nSensors; i++) {
                l.add(new double[]{forward - r * rowSpacing + dxOf(i), lat(i)});
            }
        }
        return l;
    }

    /** The sensor board: a strip around the sensors (follows a V or arc array), robot frame, mm. */
    public java.awt.geom.Area sensorBoard() {
        if (boardOnPlate && customLayout) {
            return new java.awt.geom.Area();
        }
        if (customBoard && customLayout && cuBoard.size() >= 3) {
            java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
            for (int i = 0; i < cuBoard.size(); i++) {
                if (i == 0) {
                    p.moveTo(forward + cuBoard.get(i)[0], cuBoard.get(i)[1]);
                } else {
                    p.lineTo(forward + cuBoard.get(i)[0], cuBoard.get(i)[1]);
                }
            }
            p.closePath();
            return new java.awt.geom.Area(p);
        }
        java.awt.geom.Area a = new java.awt.geom.Area();
        double mid = (nSensors - 1) / 2.0, wdt = 2 * boardMargin + 2 * spotRadius;
        for (int r = 0; r < nRows; r++) {
            java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
            for (int i = 0; i < nSensors; i++) {
                double lat = lat(i);
                double fx = forward - r * rowSpacing + dxOf(i);
                if (i == 0) {
                    p.moveTo(fx, lat);
                    if (nSensors == 1) {
                        p.lineTo(fx, lat + 0.01);                          // a single sensor still gets a board
                    }
                } else {
                    p.lineTo(fx, lat);
                }
            }
            a.add(new java.awt.geom.Area(new java.awt.BasicStroke((float) Math.max(wdt, 6), java.awt.BasicStroke.CAP_SQUARE,
                    java.awt.BasicStroke.JOIN_MITER, 4f).createStrokedShape(p)));
        }
        if (nRows > 1) {                                                   // one board joining the rows
            java.awt.geom.Rectangle2D b = a.getBounds2D();
            a.add(new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(b.getX(), lat(0), b.getWidth(), lat(nSensors - 1) - lat(0))));
        }
        if (customLayout && boardStem > 0) {                               // T / anchor board: a neck back to the plate
            java.awt.geom.Area strip = new java.awt.geom.Area(chassisOutline());
            strip.intersect(new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(-1e4, -0.5, 2e4, 1)));
            double from = strip.isEmpty() ? forward - 40 : strip.getBounds2D().getMaxX() - 10;
            if (from < forward) {
                a.add(new java.awt.geom.Area(new java.awt.geom.Rectangle2D.Double(from, -boardStem / 2, forward - from, boardStem)));
            }
        }
        return a;
    }

    /** Area (mm^2), centroid x, centroid y and polar moment about the axle centre (mm^4) of the chassis plate. */
    public double[] chassisGeometry() {
        double area = 0, cx = 0, cy = 0, j = 0;
        double[] c = new double[6];
        java.util.List<double[]> poly = new ArrayList<>();
        java.awt.geom.PathIterator it = chassisOutline().getPathIterator(null, 0.5);
        while (!it.isDone()) {
            int t = it.currentSegment(c);
            if (t == java.awt.geom.PathIterator.SEG_MOVETO) {
                poly.clear();
                poly.add(new double[]{c[0], c[1]});
            } else if (t == java.awt.geom.PathIterator.SEG_LINETO) {
                poly.add(new double[]{c[0], c[1]});
            } else if (t == java.awt.geom.PathIterator.SEG_CLOSE && poly.size() >= 3) {
                double a = 0, sx = 0, sy = 0, ix = 0, iy = 0;
                for (int i = 0; i < poly.size(); i++) {
                    double[] p = poly.get(i), q = poly.get((i + 1) % poly.size());
                    double cr = p[0] * q[1] - q[0] * p[1];
                    a += cr;
                    sx += (p[0] + q[0]) * cr;
                    sy += (p[1] + q[1]) * cr;
                    ix += (p[1] * p[1] + p[1] * q[1] + q[1] * q[1]) * cr;
                    iy += (p[0] * p[0] + p[0] * q[0] + q[0] * q[0]) * cr;
                }
                double sgn = Math.signum(a);                                // outer outlines and holes keep their own sign
                area += a / 2 * sgn;
                cx += sx / 6 * sgn;
                cy += sy / 6 * sgn;
                j += (ix + iy) / 12 * sgn;
            }
            it.next();
        }
        return area > 1e-9 ? new double[]{area, cx / area, cy / area, j} : new double[]{0, 0, 0, 0};
    }

    /** Centre of mass (robot frame): the plate's centroid (60 % of the mass) and the axle (40 %). */
    public double[] centreOfMass() {
        if (useParts && !parts.isEmpty()) {
            double[] p = partsCentre();
            return new double[]{p[0], p[1]};
        }
        double[] g = chassisGeometry();
        return new double[]{0.6 * g[1], 0.6 * g[2]};
    }

    /** Caster position along the robot (x, mm; negative = behind the axle). */
    public double casterX() {
        if (casterPos != 0) {
            return casterPos;
        }
        double minX = chassisOutline().getBounds2D().getMinX();
        return minX + 12;
    }

    /** Share of the weight on the driven wheels that the shape suggests (lever rule with the caster), %. */
    public double suggestedWheelWeight() {
        return clamp(wheelWeightShare() * 100, 5, 100);
    }

    /**
     * Lever rule between the axle and the caster: 1 = all the weight on the wheels. Above 1 the centre of
     * mass is beyond the axle on the far side from the caster, so the robot tips over the axle (onto the
     * sensor board, or onto its tail).
     */
    public double wheelWeightShare() {
        if (wheelCount == 4) {
            return 1;
        }
        double xc = casterX(), xg = centreOfMass()[0];
        if (Math.abs(xc) < 1) {
            return 1;
        }
        return (xg - xc) / (0 - xc);
    }

    /** Time for the robot to reach 63 % of its speed from a standstill at full power (physical model), s. */
    public double mechTimeConstant() {
        double fs = (wheelCount == 4 && steerMode != 1 ? 4 : 2) * stallForceN() * battVolt / Math.max(motorVolt, 0.1);
        double v0 = noLoadSpeedRated() * battVolt / Math.max(motorVolt, 0.1) / 1000.0;
        return fs > 0 ? effMassKg() * v0 / fs : 0;
    }

    /** Steady top speed on the flat at full PWM (physical model), mm/s. */
    public double physicalTopSpeed() {
        double fs = stallForceN(), v0 = noLoadSpeedRated(), vr = battVolt / Math.max(motorVolt, 0.1);
        double loss = gearFrictionPct / 100.0 * fs + rollingForce() / 2;
        return Math.max(0, v0 * (vr - loss / Math.max(fs, 1e-9)));
    }

    /** Smallest PWM duty (0-1) that starts the robot moving (physical model). */
    public double minDutyToMove() {
        double fs = stallForceN(), vr = battVolt / Math.max(motorVolt, 0.1);
        double need = gearFrictionPct / 100.0 * fs + rollingForce() / 2;
        return clamp(need / Math.max(fs * vr, 1e-9), 0, 1);
    }

    /** Rolling resistance of the whole robot, N: tyres on the driven wheels plus the caster. */
    private double rollingForce() {
        double wN = Math.max(massG, 10) / 1000.0 * 9.81;
        double share = wheelCount == 4 ? 1 : clamp(driveWeightPct / 100.0, 0.05, 1);
        int motors = wheelCount == 4 && steerMode != 1 ? 4 : 2;
        return (0.015 * wN * share + casterCrr * wN * (1 - share)) * 2 / motors;   // per side, shared by its motors
    }

    /** Force one motor pushes its wheel with, N (before friction and grip). */
    private double motorForce(int side, double cmd, double wheelV, double gainPct, double deadPct, double vRatio) {
        double u = clamp(cmd / Math.max(maxSpeed, 1), -1, 1);
        if (Math.abs(cmd) < deadPct / 100.0 * maxSpeed) {
            u = 0;                                                         // driver dead band
        }
        int sg = u > 0 ? 1 : (u < 0 ? -1 : 0);
        if (sg != 0 && sg != blSign[side]) {                                // reversing: the gears cross their play first
            if (blSign[side] != 0 && backlashMs > 0) {
                blUntil[side] = t + backlashMs / 1000.0;
            }
            blSign[side] = sg;
        }
        if (t < blUntil[side]) {
            return 0;
        }
        double fs = stallForceN(), v0 = noLoadSpeedRated() * Math.max(gainPct, 1) / 100.0;
        double f = fs * (u * vRatio - wheelV / Math.max(v0, 1));             // DC motor: force falls linearly with speed
        if (decayMode == 1) {                                               // coasting: the motor cannot brake the wheel
            if (wheelV > 0 && f < 0 && u >= 0 || wheelV < 0 && f > 0 && u <= 0) {
                f = 0;
            }
        }
        return f;
    }

    /** Net force on one wheel after the gearbox friction; a wheel standing still stays still until the motor beats the friction. */
    private static double afterFriction(double f, double wheelV, double friction) {
        if (Math.abs(wheelV) > 0.5) {
            return f - Math.signum(wheelV) * friction;
        }
        return Math.abs(f) <= friction ? 0 : f - Math.signum(f) * friction;
    }

    /** Physical model: motor forces push the chassis (mass) and turn it (inertia); grip and friction limit them. */
    private void physicalDrive(double dt) {
        double vRatio = Math.max(0, battNow() - driverDrop) / Math.max(motorVolt, 0.1) * warmFactor();
        double half = wheelBase / 2.0;
        double wl = pV + pW * half, wr = pV - pW * half;
        int motorsPerSide = wheelCount == 4 && steerMode != 1 ? 2 : 1;     // rear-wheel drive car: two motors
        double fl = motorsPerSide * motorForce(0, cmdL, wl, gainL, deadL, vRatio);
        double fr = motorsPerSide * motorForce(1, cmdR, wr, gainR, deadR, vRatio);
        double fric = motorsPerSide * gearFrictionPct / 100.0 * stallForceN();
        double wN = Math.max(massG, 10) / 1000.0 * 9.81;
        double share = steerMode == 1 ? 0.55 : wheelCount == 4 ? 1 : clamp(driveWeightPct / 100.0, 0.05, 1);
        fric += 0.015 * wN * share / 2;                                     // tyre rolling resistance
        fl = afterFriction(fl, wl, fric);
        fr = afterFriction(fr, wr, fric);
        double mu = gripHere();
        if (mu > 0) {                                                       // a wheel cannot push harder than its grip
            double lim = mu * wN * share / 2;
            fl = clamp(fl, -lim, lim);
            fr = clamp(fr, -lim, lim);
        }
        double caster = wheelCount == 4 ? 0 : casterCrr * wN * (1 - share);
        double fsum = fl + fr;
        if (Math.abs(pV) > 0.5) {
            fsum -= Math.signum(pV) * caster;
        } else {
            fsum = Math.abs(fsum) <= caster ? 0 : fsum - Math.signum(fsum) * caster;
        }
        double a = fsum / effMassKg() * 1000.0;                             // mm/s^2
        double inertia = Math.max(yawInertia() * 1.2, 1e-6);
        double torque = (fl - fr) * half;                                  // N.mm
        double nw;
        if (steerMode != 0) {                                               // car: the steered wheels set the turn
            nw = (pV + a * dt) * Math.tan(steerAngle) / Math.max(axleSpacing, 10);
        } else if (wheelCount == 4) {                                       // skid steer: the tyres scrub sideways to turn
            double scrub = sideFriction * wN * axleSpacing / 2;            // N.mm, opposes turning
            if (Math.abs(pW) > 0.02) {
                torque -= Math.signum(pW) * scrub;
                nw = pW + torque * 1000.0 / inertia * dt;
                if (Math.signum(nw) != Math.signum(pW) && Math.abs(fl - fr) * half < scrub) {
                    nw = 0;                                                // the scrub stops the turn, it does not reverse it
                }
            } else {
                nw = Math.abs(torque) <= scrub ? 0 : pW + (torque - Math.signum(torque) * scrub) * 1000.0 / inertia * dt;
            }
        } else {
            nw = pW + torque * 1000.0 / inertia * dt;
        }
        double nv = pV + a * dt;
        if (pV != 0 && Math.signum(nv) != Math.signum(pV) && Math.abs(fl + fr) < 1e-9) {
            nv = 0;                                                         // friction stops the robot, it does not reverse it
        }
        pV = nv;
        pW = nw;
        if (mu > 0) {                                                       // sideways grip limits how tight it can turn
            double wmax = mu * 9810.0 / Math.max(Math.abs(pV), 30.0);
            pW = clamp(pW, -wmax, wmax);
        }
        if (t < stallL || t < stallR) {                                     // a jammed wheel stands still: pivot about it
            double l = t < stallL ? 0 : pV + pW * half, r = t < stallR ? 0 : pV - pW * half;
            pV = (l + r) / 2;
            pW = (l - r) / wheelBase;
        }
        vL = pV + pW * half;
        vR = pV - pW * half;
    }

    private void integrate(double v, double w, double dt) {
        double thMid = th + w * dt / 2.0;
        x += v * Math.cos(thMid) * dt;
        y += v * Math.sin(thMid) * dt;
        if (steerMode != 0) {                                              // car: it turns about the rear axle, half a wheelbase back
            double lever = Math.max(axleSpacing, 10) / 2 * w * dt;
            x += -Math.sin(thMid) * lever;
            y += Math.cos(thMid) * lever;
        }
        th += w * dt;
        loadAvg += (Math.min(1.0, (Math.abs(cmdL) + Math.abs(cmdR)) / (2 * Math.max(maxSpeed, 1))) - loadAvg) * dt / 0.5;

        distance += Math.abs(v) * dt;
        if (offLineNow()) {                                                // "off the line": see offLineNow()
            offTrackTime += dt;
        }
        if (!lineVisible) {
            lineLostTime += dt;
        }
        absErrInt += Math.abs(error) * dt;
        if (lineVisible && Math.abs(error) > maxAbsErr) {
            maxAbsErr = Math.abs(error);
        }

        double dx = x - lastTrX, dy = y - lastTrY;
        if (dx * dx + dy * dy > 9) {
            trX[trHead] = x;
            trY[trHead] = y;
            trHead = (trHead + 1) % TRAIL;
            if (trCount < TRAIL) {
                trCount++;
            }
            lastTrX = x;
            lastTrY = y;
        }

        checkGate();
        histAcc += dt;
        if (histAcc >= 0.01) {
            histAcc -= 0.01;
            float[] row = tel[telHead];
            if (row == null) {
                row = tel[telHead] = new float[TEL_COLS.length + MAXS];
            }
            double[] tv = {t, x, y, Math.toDegrees(th), error, pTerm, iTerm, dTerm, outTerm, cmdL, cmdR, vL, vR, lineVisible ? 1 : 0,
                offLineNow() ? 1 : 0, Math.toDegrees(steerAngle)};
            for (int k = 0; k < tv.length; k++) {
                row[k] = (float) tv[k];
            }
            for (int k = 0; k < MAXS; k++) {
                row[TEL_COLS.length + k] = k < nSensors ? (float) raw[k] : 0;
            }
            telHead = (telHead + 1) % TEL;
            if (telCount < TEL) {
                telCount++;
            }
            hist[0][hHead] = error;
            hist[1][hHead] = pTerm;
            hist[2][hHead] = iTerm;
            hist[3][hHead] = dTerm;
            hist[4][hHead] = outTerm;
            hist[5][hHead] = vL;
            hist[6][hHead] = vR;
            hHead = (hHead + 1) % HIST;
            if (hCount < HIST) {
                hCount++;
            }
        }
    }

    /**
     * "Off the line": the centre of the sensor array is more than one line width from the line's centre
     * (no line under the centre or half a line width either side). The normal wobble of a digital array
     * stays inside this; a robot that has really left the line does not.
     */
    boolean offLineNow() {
        double ax = x + Math.cos(th) * forward, ay = y + Math.sin(th) * forward, h = Math.max(lineWidth, 4) / 2;
        double nx = -Math.sin(th) * h, ny = Math.cos(th) * h;
        return grayAt(ax, ay) > 0.5 && grayAt(ax + nx, ay + ny) > 0.5 && grayAt(ax - nx, ay - ny) > 0.5;
    }

    /** The gate in use: the one set by hand, else the preset's finish, else across the line at the start. {x1, y1, x2, y2} */
    public double[] gate() {
        if (gateSet) {
            return new double[]{gx1, gy1, gx2, gy2};
        }
        double half = Math.max(arrayWidth() / 2 + lineWidth + 20, 60);
        double px = sx + Math.cos(sth) * forward, py = sy + Math.sin(sth) * forward, ang = sth;
        if (presetFinish != null) {
            px = presetFinish[0];
            py = presetFinish[1];
            ang = presetFinish[2];
        }
        double nx = -Math.sin(ang) * half, ny = Math.cos(ang) * half;
        return new double[]{px - nx, py - ny, px + nx, py + ny};
    }

    /** True if the gate is a finish line at the far end (an open track), false for a lap gate. */
    public boolean gateIsFinish() {
        return !gateSet && presetFinish != null;
    }

    /** Count a lap (or the finish) when the array centre crosses the gate going forward. */
    private void checkGate() {
        double ax = x + Math.cos(th) * forward, ay = y + Math.sin(th) * forward;
        if (!Double.isNaN(prevAx)) {
            gateTravel += Math.hypot(ax - prevAx, ay - prevAy);
            double[] gt = gate();
            if (gateTravel > 150 && java.awt.geom.Line2D.linesIntersect(prevAx, prevAy, ax, ay, gt[0], gt[1], gt[2], gt[3])) {
                double gxn = -(gt[3] - gt[1]), gyn = gt[2] - gt[0];          // the gate's "forward" side
                double fwd = (ax - prevAx) * gxn + (ay - prevAy) * gyn;
                double ref = Math.cos(gateIsFinish() ? presetFinish[2] : sth) * gxn + Math.sin(gateIsFinish() ? presetFinish[2] : sth) * gyn;
                if (gateSet || Math.signum(fwd) == Math.signum(ref)) {
                    laps.add(t - lapStartT);
                    lapStartT = t;
                    gateTravel = 0;
                }
            }
        }
        prevAx = ax;
        prevAy = ay;
    }

    /** Put the start / finish line here (both end points, mm). */
    public void setGate(double x1, double y1, double x2, double y2) {
        gx1 = x1;
        gy1 = y1;
        gx2 = x2;
        gy2 = y2;
        gateSet = true;
    }

    /** Back to the automatic gate (through the start, or the finish of an open preset). */
    public void clearGate() {
        gateSet = false;
    }

    public double bestLap() {
        double b = Double.NaN;
        for (double l : laps) {
            b = Double.isNaN(b) ? l : Math.min(b, l);
        }
        return b;
    }

    /** Telemetry rows, oldest first (CSV columns: TEL_COLS then raw_0 .. raw_n-1). */
    public List<float[]> telemetry() {
        List<float[]> l = new ArrayList<>();
        int start = (telHead - telCount + TEL) % TEL;
        for (int i = 0; i < telCount; i++) {
            l.add(tel[(start + i) % TEL]);
        }
        return l;
    }

    public double avgAbsError() {
        return t > 0 ? absErrInt / t : 0;
    }

    // ------------------------------------------------------------------ copying settings (used by the analysis tests)
    /** Runtime state: public fields that copySettingsFrom must not copy. */
    private static final Set<String> STATE = new HashSet<>(Arrays.asList(
            "x", "y", "th", "vL", "vR", "cmdL", "cmdR", "sx", "sy", "sth", "t", "ctrlAcc", "histAcc", "stepAcc",
            "outOfArena", "error", "prevError", "lastValidError", "integral", "dFilt", "pTerm", "iTerm", "dTerm",
            "outTerm", "lineVisible", "firstCycle", "distance", "offTrackTime", "lineLostTime", "absErrInt", "maxAbsErr",
            "hHead", "hCount", "trHead", "trCount", "running", "speedMul", "edited", "presetIdx", "res", "vBody", "steerAngle",
            "arenaW", "arenaH", "currentNow", "usedMah", "tipped", "brownouts", "encSpeedL", "encSpeedR", "gateSet", "gx1", "gy1",
            "gx2", "gy2"));

    /** A text that changes whenever any setting changes (used to know when a report is out of date). */
    public String settingsSignature() {
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : Model.class.getFields()) {
                Class<?> ty = f.getType();
                if (!Modifier.isStatic(f.getModifiers()) && !Modifier.isFinal(f.getModifiers()) && !STATE.contains(f.getName())
                        && (ty == double.class || ty == int.class || ty == boolean.class)) {
                    sb.append(f.get(this)).append(',');
                }
            }
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
        double cal = 0;
        for (int r = 0; r < MAXR; r++) {
            for (int i = 0; i < MAXS; i++) {
                cal += calMin[r][i] * (i + 1) + calMax[r][i] * (i + 2);
            }
        }
        double co = 0;
        for (double[] q : customOutline) {
            co = co * 31 + q[0] * 7 + q[1];
        }
        return sb.append(cal).append(',').append(mmGain[1]).append(',').append(forward).append(',').append(co).toString();
    }

    /** Copy every setting (not the robot's position or statistics, nor the track) from another model. */
    public void copySettingsFrom(Model o) {
        try {
            for (Field f : Model.class.getFields()) {
                Class<?> ty = f.getType();
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isFinal(f.getModifiers()) || STATE.contains(f.getName())
                        || !(ty == double.class || ty == int.class || ty == boolean.class)) {
                    continue;
                }
                f.set(this, f.get(o));
            }
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
        customOutline.clear();
        for (double[] q : o.customOutline) {
            customOutline.add(q.clone());
        }
        System.arraycopy(o.mmGain, 0, mmGain, 0, mmGain.length);
        System.arraycopy(o.mmOff, 0, mmOff, 0, mmOff.length);
        System.arraycopy(o.cuDx, 0, cuDx, 0, MAXS);                       // the Robot editor's layout
        System.arraycopy(o.cuLat, 0, cuLat, 0, MAXS);
        System.arraycopy(o.cuPin, 0, cuPin, 0, MAXS);
        System.arraycopy(o.cuHeight, 0, cuHeight, 0, MAXS);
        System.arraycopy(o.cuLedDx, 0, cuLedDx, 0, MAXL);
        System.arraycopy(o.cuLedLat, 0, cuLedLat, 0, MAXL);
        System.arraycopy(o.cuLedSensor, 0, cuLedSensor, 0, MAXL);
        parts.clear();
        for (Part p : o.parts) {
            parts.add(p.copy());
        }
        cuBoard.clear();
        for (double[] q : o.cuBoard) {
            cuBoard.add(q.clone());
        }
        for (int r = 0; r < MAXR; r++) {
            System.arraycopy(o.calMin[r], 0, calMin[r], 0, MAXS);
            System.arraycopy(o.calMax[r], 0, calMax[r], 0, MAXS);
        }
        applyHardware();
    }
}
