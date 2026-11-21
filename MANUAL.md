# LineFollowerSim - Manual

A line-follower robot simulator for designing, tuning and then building real robots: the robot's
shape, sensor array, LEDs, motors, wheels, microcontroller timing, PID and recovery code, lighting
and the track. Everything is in real units (mm, mm/s, ms, g, V, A).
Java 8 + Swing, no external libraries. NetBeans project.

Every option, its default and what it does is listed in **OPTIONS-REFERENCE.txt**.

---

## 1. Run it

- Open the folder in NetBeans (JDK 8 or newer) and press **Run**
  (main class `linefollowersim.LineFollowerSim`), or after Clean and Build:
  `java -jar dist/LineFollowerSim.jar`
- The window is made for 1366 x 768 and larger; it remembers its size and position.
- Start options: `-Dlinefollowersim.theme=dark|light` (dark is the default),
  `-Dlinefollowersim.presets=PATH` (where the settings file lives).

## 2. Quick start

1. Pick a **Track** in the top bar, press **Run** (F5).
2. Change **Kp / Kd** on the PID tab and watch the error graph under the track.
3. **Keep this run** (Ctrl+K), change something, **Restart** (F6), keep again, and compare
   on the **Runs** tab (times, laps, errors, which settings differ).
4. Build your own robot on the **Robot editor** tab (top of the window), press
   **Apply to robot**, go back to **Simulator**.
5. **Tune PID for this track** (Ctrl+T) or **Design a robot for this track** (Ctrl+G).
6. Write your own algorithm on the **Code** tab (Examples > Marker counter is a good start), test it
   here with breakpoints, then export it in the Arduino sketch.
7. Save everything with **File > Save workspace** (Ctrl+S).

## 3. The window

Three tabs at the top: **Simulator**, **Robot editor** and **Code**.

**Simulator**: the track (robot to scale, trail, kept runs, start / finish line), a HUD (sensor
bars or bits, error, laps, live calibration check, battery), graphs (error, P/I/D, wheel speeds)
and the control column:

| Tab | What is there |
|---|---|
| PID | Base speed, Kp (1/s), Ki (1/s^2), Kd, derivative filter, corner slowdown, no-D-kick, line-lost recovery (6 modes), Tune PID |
| Hardware | Presets, sensor part (TCRT5000, QRD1114, QRE1113, ITR20001, KTIR0711S, 3 / 5 mm IR LED pairs), wheels (2 + caster, 4 skid, car rear / all-wheel drive), sizes, chassis shape, sensor array (1-32 sensors, straight / V / arc / custom), drive, LEDs near the sensors, light shield |
| Sensors | IR power, threshold, noise + seed, mismatch, calibration, crosstalk, filters, error formula, junction rule, mounting errors, comparator hysteresis, ADC resolution |
| Motors | Speed factors, lag, dead zone, Simple / Physical drive model, motor driver (drop + decay), encoders + speed loop, battery capacity and current, from real measurements |
| MCU | Board presets (Uno ... ESP32, RP2040, Teensy), cycle costs, loop delay / fixed period, jitter, sensor task |
| World | Light, flicker, floor / line, battery drain, temperature, grip, disturbances (incl. timed LED switch), real-world effects (board lift, shiny tape, sensor response, motor warm-up, dust), realism presets, failures (stuck sensor, brown-out, tip-over) |
| Track | Arena, view, line width, start direction, finish line, trails, background picture |
| Builder | Draw tracks: lines, curves, arcs, shapes, erase, robot position, finish line, measure; scale / rotate / mirror; grid and snapping |
| Analysis | Design check, delay budget, calibration demo, tests, robot designer |
| Runs | Kept runs, laps, telemetry CSV export, real-robot log import |

Every control has a reset button, every tab a "Reset ... tab" button, **Reset all** resets the
simulator (never the Robot editor, which has its own Reset). Settings that do nothing at the moment
are greyed out. The LEDs can be switched (bulb button, F7) and dimmed (F8 / Shift+F8) while it runs.
F1 lists the keyboard shortcuts.

## 4. Robot editor

A drawing editor for the whole robot (front up, mm, x ahead of the axle, y to the right):

- **Chassis plate**: start shapes, add / move corners, copy one half to the other, merge corners,
  import a DXF / SVG outline.
- **Sensor board**: up to 32 sensors anywhere, or placed evenly as straight / V / arc / along the
  plate front / arc by angle (anchor boards whose ends curl back) / swept wings, by width or
  pitch, fitted to the plate; IR pair orientation (emitter / receiver gap, every other pair turned
  round: crosstalk from real distances); LEDs placed with the sensors; a stem back to the plate
  (T / anchor boards); "sensors sit on the plate" for one-piece PCBs; the sensors' height above the
  floor, and **Heights / pins...**: each sensor's own height (a curved or bent board puts the tips
  higher: less signal, a wider spot) and MCU pin.
- **Wheels and drive**: 2 + caster, 4 skid steer, car rear drive, car all-wheel drive; track,
  wheel, tyre, axle spacing, steering lock, servo or DC steering.
- **Parts and weight**: battery, boards, motors ... with mass and height -> total mass, centre of
  mass, weight on the wheels, tip-over limit.
- **Tracing**: load a photo or scan, set its scale with two clicks, move / turn / flip it, then
  trace by hand or **auto-trace** (click inside the board; Shift+click adds an area, Alt+click
  cuts one). Or load a DXF / SVG drawing as a cyan underlay whose corners and hole centres are
  snap points; click inside one of its shapes to take it as the plate.
- **Picking**: click an edge to drag that wall, inside the board to move the whole array, inside the
  plate to move the plate. The board outline can follow the sensors or be edited corner by corner.
  Wheels that something runs into turn red.
- **Pro tools**: zoom / pan / fit, rulers, grid size, snap to grid / points / centre line,
  mirrored editing, box select, scale handles, typed move / scale / rotate, mirror, align,
  distribute, measure, undo / redo (200), checks (overlapping spots, gaps wider than the line,
  board not joined, tipping ...).
- **New** (from scratch), **Reset** (view / draft / everything), **Apply to robot**,
  **Take from robot**. The draft is saved in the workspace, finished or not, and autosaved
  (Recover after a crash).

Tips: take photos straight down (an angled photo is stretched and scaling cannot fix it), with a
ruler in the picture. A board covered with parts traces best from a photo of the bare PCB.

## 5. Code tab

Write the robot's program as plain text in Arduino-style C and test it on the simulated robot.

- **Mode**: *Built-in controller* (the code is not used), *Hooks* (the built-in follower drives,
  then your `loop()` runs every controller loop and may read the sensors and change the wheels),
  *Full code* (only your code drives; the wheels stop unless it calls `drive()` or
  `followLine()`). `setup()` runs at every Restart; globals start again from their values.
- **The sliders are live constants**: `KP KI KD BASE_SPEED TOP_SPEED CORNER_SLOW THRESHOLD
  PITCH_MM HALF_SPAN WHEEL_BASE N ROWS` - move a slider and the running code sees the new value.
- **Sensors**: `raw[i]`, `dark[i]`, `isDark(i)`, `countDark()`, `allDark()`, `anyDark()`,
  `linePosition()` (mm, NaN when lost), `lineSeen()`, `sensorY(i)`. **Wheels**: `drive(l, r)` mm/s,
  `steer(deg)`, `stop()`, `followLine()`. **Time / state**: `millis()`, `micros()`, `loopTime()`,
  `speed()`, `distance()`, `laps()`. **Debug**: `print(...)` (console, Serial in the sketch),
  `plot("name", v)` (Plot tab, Serial Plotter in the sketch), `pause()`. Arduino maths.
  The Help tab beside the console lists everything.
- **Language**: int / long / float / double / bool and the Arduino integer types, arrays,
  functions (also called before they are defined), `#define`, const, static locals, if / else,
  while, do, for, switch, break / continue / return, `? :`, casts, all operators. Whole numbers
  divide like C (`7 / 2` is 3). `delay()` is refused with a hint to use a `millis()` timer: on
  a real robot it freezes the sensors too. An endless loop is stopped (400 000 steps per loop).
  Errors show their line; the robot keeps the last code that compiled.
- **The code costs time**: each loop's steps are added to the MCU compute time (about 40 cycles a
  step), so a heavy loop() slows the loop rate like it would on the board.
- **Examples**: empty hooks, marker counter (A++ on each all-dark bar on its rising edge, with a
  confirm count; A == 2 = end of the maze -> stop), the PID controller written out, bang-bang,
  a timed turn at a junction (state machine with `millis()`), stop after N laps, sensor read-out.
- **Debugger**: click the line numbers (or F9) for a breakpoint, right-click for a condition
  (`A == 2`, `countDark() >= 3`). Continue (F5), Break, Step over (F10), Step into (F11), Step
  out (Shift+F11), Step loop. While stopped the whole simulation stands still: the yellow arrow
  shows the line, the Watch tab the call stack (click a call for its variables), the globals and
  the sensors. A run error (index outside an array, division by zero ...) stops on its line the same
  way. Restart, Apply and the mode leave a stopped program cleanly.
- **Export**: File > Export Arduino sketch (or Export sketch... on the tab) puts the code in the
  sketch (`CODE_MODE`): the same functions in C++, your code in its own `namespace user` so its
  names cannot clash with the sketch's, setup / loop renamed userSetup / userLoop, prototypes
  added. Checked: 7 programs replayed through the generated C++ drive the same (to 0.0002 mm/s)
  and print the same text, and they compile for the Uno and the Mega with avr-gcc.
- Saved in the workspace: the text, the applied version, the mode and the breakpoints.

## 6. From the simulator to a real robot

1. Design it here (Robot editor, Design check, Designer), then build it:
   **File > Export robot drawing** (DXF / SVG, 1:1, layers CUT / HOLES / MARK / REF / TEXT),
   **Parts list**, **Export track drawing**, **Print track at real size** (tiled pages).
2. Measure the real robot: top speed, 0-63 % time, lowest PWM that moves, each wheel's RPM;
   enter them in **Motors > From real measurements** and choose the Physical drive model.
3. Retune here (Tune PID), then **File > Export Arduino sketch** (Ctrl+E). The sketch runs the
   simulator's controller line by line in mm and mm/s, so the gains are the same numbers. It was
   checked by replaying the simulator's recorded sensor readings through the generated C++ (15
   set-ups, same outputs to 0.01 mm/s). Set TOP_SPEED to your measured value, check the motor
   directions, start slower. Works for AVR, ESP32, ESP8266 (one analog pin), RP2040, STM32.
   Differences that remain are listed in the sketch header (committed junction turn, sensor task,
   car steering speed source, one-channel encoder counting).
4. Turn on the sketch's serial log (LOG 1), copy the output into a file and **Import a run log**
   to compare the real run with the simulated ones.

## 7. Files

| File | Contents |
|---|---|
| `.lfsim` workspace (text) | every control (`Tab/Label=value`), mismatch, calibration, robot layout (with each sensor's height), Robot editor draft and trace picture, the Code tab (text, mode, breakpoints), kept runs, then the track |
| `.lftrack` track (text) | arena, start, background picture (linked and embedded), shapes, patches, finish line |
| `.ino` sketch | see section 6 |
| `.dxf` / `.svg` drawings | robot and track, 1:1 mm, layered |
| `.csv` telemetry | t_s, x_mm, y_mm, heading, error, P, I, D, output, commands, wheel speeds, line seen, off line, steering, raw ADC per sensor |
| `~/.linefollowersim/presets.properties` | saved robots, arenas, theme, recent files, last folder, window |
| `~/.linefollowersim/robot-draft.txt` | Robot editor autosave |

Older files open: workspaces from 16-sensor versions are widened, unknown lines are skipped.

## 8. Source layout (for developers)

`src/linefollowersim` (about 50 classes, no Swing in the model):

| Class | Role |
|---|---|
| `Model` | the whole simulation: sensors, optics, calibration, controller, MCU timing, drive models, battery, encoders, failures, laps, telemetry, robot layout and parts |
| `LineFollowerSim` | main window: Simulator / Robot editor / Code tabs, timer |
| `MiniC` | the Code tab's interpreter (Arduino-style C subset: parser, C integer maths, step limit, debugger hooks) |
| `CodeRunner`, `CodeTab`, `CodeExamples` | the code in the model (sensors, sliders, drive), the tab (editor, debugger, console, watch, plot), examples and help |
| `ControlPanel` | every tab, menus, workspace open / save, exports, dialogs |
| `TrackPanel`, `GraphPanel`, `PlotPanel` | track view + HUD, graphs |
| `TrackEditor`, `TrackShape`, `TrackBuilder`, `TrackFile` | Builder tools, shapes, preset tracks, .lftrack |
| `RobotEditor`, `RobotCanvas`, `RobotDraft` | Robot editor panel, drawing canvas, the draft (text form for undo / saving) |
| `TraceLayer`, `OutlineImport` | picture / drawing to trace over, auto-trace; DXF / SVG reading |
| `DesignCheck`, `AnalysisTab`, `Experiments` | design check, delay budget, tests |
| `TrackAnalysis`, `Designer`, `DesignWizard`, `TunePidDialog` | robot designer and PID tuner |
| `ArduinoExport` + `sketch_body.txt` | sketch generator (header in Java, body template as a resource) |
| `Drawing`, `Exports` | DXF / SVG writer, robot / track drawings, parts list, printing |
| `Telemetry`, `RunsPanel` | CSV out / logs in, kept runs |
| `Workspace`, `UserPresets` | .lfsim files, settings file |
| `Param`, `SpinParam`, `ComboParam`, `CheckParam`, `MenuParam`, `ResetGroup`, `Saveable` | controls with reset / save |
| `Theme`, `Icons`, `UI`, `HardwarePresets`, `McuProfile` | look, icons (PNG in `icons/`, dark variants made at run time), helpers, presets |

New options are added neutral (off / zero), so a default robot always behaves the same; the
control's label is its saved key ("Tab/Label"), so labels must not contain "=".

## 9. Tests

`test/linefollowersim/SelfTest.java` - right-click > Run File. **131 checks** (tracks, junctions,
calibration, LEDs, grip, physical model, car steering, designer, exports, laps, telemetry, recovery
modes, repeatability, custom layouts, 32 sensors, IR pairs, outline import, encoders, battery,
tipping, auto-trace, editor wheels, sensor heights, the code interpreter's C arithmetic, examples
and code export ...). Exits 0 when all pass.

During development these were also run (not in the zip): 52-scenario regression (defaults never
change), Java-vs-C++ sketch replay (15 set-ups, and 7 programs in code mode incl. their printed
text), 36 sketch variants compiled with g++ (half with code) and the code sketches with avr-gcc
for the Uno / Mega, debugger stepping / conditions / error stops, a scan of every tab for
cut-off text at 1366 x 768, random control and Robot-editor fuzzing, workspace round trips, DXF
audits with ezdxf, and auto-trace on a photo of a real board.

## 10. Things to know

- A 90-degree corner never "cuts" here: a straight array lies across the heading and cannot see the
  new branch early. Longer axle-to-sensor distance helps corners until the straight line turns
  nervous.
- A V array of 20 degrees or more turns in early and can lose a sharp corner (the "lost near the
  centre -> straight on" rule sends it straight); ~10 degrees helps.
- "Axle distance = 2.5 x the distance travelled in the delay" did worse than the default in the
  simulator, so "Suggest (simulate corners)" tests it instead.
- Two sensor rows with extrapolation help corners a little but did worse on curvy tracks.
- Grip only matters for fast robots or low mu; corner slowdown hides it at normal speeds.
- On anchor arrays whose ends curl back, a tip sensor can sit beside a middle one in the left-to-
  right order; the error is right, the junction / fork rule is less reliable there.
- The default 2-wheel chassis with the caster behind puts the weight near the axle; the editor
  and the Hardware tab warn when a robot would tip onto its board.
- Code tab: whole numbers here are 64-bit; on an AVR an `int` is 16-bit (-32768 ... 32767), so use
  `long` / `unsigned long` for big numbers and `millis()`. `map()` and `round()` work in whole
  numbers like Arduino's. On the real robot `distance()` is estimated from the commanded speed and
  `laps()` is 0 (there is no lap gate: count markers). `pause()` stops the real robot until reset.
  Your `#define` names live in the whole sketch, so avoid the sketch's own (LOG, FILTER, N ...).
- MCU cycle counts, motor / wheel / battery presets, part weights and hole patterns are typical
  values, not measurements: measure your parts.

## 11. Simplified or not modelled

Simplified: board lift (height only), glare, motor heating (one lumped value), dust (linear), battery discharge curve, motor current (from duty, speed and stall current), car
steering is kinematic (no slip angles), 4-wheel skid scrub, tipping is sideways only and stops
the run, encoders count with rounding only.
Not modelled: wheel spin-up when it slips, PWM frequency, obstacles / ramps / bumps; LEDs do not move
with "array off centre / turned".

## 12. History

- **Round 7**: Code tab (Arduino-style C with Built-in / Hooks / Full code modes, live slider
  constants, examples incl. the marker counter, console / watch / plot, a debugger with
  breakpoints, conditions, step over / into / out and error stops, code exported in the sketch and
  checked by replay and avr-gcc); each sensor's own height (Heights / pins table) and the sensor
  height in the Robot editor.
- **Round 6, late**: LEDs switchable while running (and a timed switch), greyed-out unused settings,
  real-world effects (board lift, shiny tape, sensor response, motor warm-up, dust), sensor parts with
  real sizes, editable board outline, edge / whole-shape picking, wheel collision check.
- **Round 6**: Robot editor (full window: chassis, board, sensors up to 32, IR pairs, LEDs, parts,
  wheels and drive, tracing over photos and DXF / SVG with auto-trace, New / Reset); line-lost
  modes; repeatable noise; laps and finish line; telemetry and log import; runs saved; motor
  driver; live calibration check; encoders + speed loop; battery capacity and current; mounting
  errors, hysteresis, ADC; failures; Tune PID; designer tunes Ki and the D filter; sketch parity
  with a replay test; RP2040; embedded background pictures; Builder measure / scale / rotate /
  mirror; options reference rewritten. Fixes: "=" in labels broke saving, filters started early,
  sketch calibration rounding, default parts tipping forward, Reset all touching the editor.
- **Round 5**: array shapes (V, arc), chassis shapes, 4-wheel skid and car steering (servo / DC),
  robot designer wizard, DXF / SVG exports, real-size printing, parts list. Fix: steering delay
  restarted on every command.
- **Round 4**: Physical drive model, from-real-measurements, flicker, coloured LEDs + shield,
  Arduino export, graphics-app zoom, start direction, typed line width, kept runs.
- **Round 3**: workspaces, menus, new canvas, grid styles, tire width, dark default, UI sweep.
- **Round 2**: themes and icons, vertical tabs, white LEDs, Y-junction, friction-circle grip,
  many fixes (fork vs junction, calibration with crosstalk, stale calibration, Reset all).
- **Before**: Analysis tab, filters and calibration options, rows, MCU timing modes, World tab,
  background picture, junction tracks.
