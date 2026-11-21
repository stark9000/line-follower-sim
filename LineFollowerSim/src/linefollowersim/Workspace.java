package linefollowersim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Workspace files (.lfsim): every setting of every tab, the sensor mismatch pattern and the whole
 * track, in one plain-text file.
 *
 *   LineFollowerSim-workspace 1
 *   set PID/Kp=5.0                       (tab / control label = value)
 *   mismatch g0,g1,... o0,o1,...
 *   calibration white,black,min...,max...
 *   arena 800.0 480.0                    (then the same lines as a .lftrack file)
 *   shape LINE ...
 */
final class Workspace {

    static final String HEADER = "LineFollowerSim-workspace 2", EXT = "lfsim";

    /** What a workspace file holds, read but not yet applied. */
    static final class Content {

        final Map<String, String> settings = new LinkedHashMap<>();
        double[] gain, off;
        double[] cal;                                      // calWhite, calBlack, then calMin and calMax of every row and sensor
        List<double[]> chassis;                            // custom chassis outline (robot mm), null = none saved
        final List<String> layout = new ArrayList<>();     // robot editor: sensors, LEDs, pairs, parts
        final List<String> draft = new ArrayList<>();      // robot editor: the unfinished draft
        final List<String> runs = new ArrayList<>();       // kept runs
        final List<String> track = new ArrayList<>();
    }

    private Workspace() {
    }

    static void save(File f, Map<String, Saveable> controls, Model m) throws IOException {
        save(f, controls, m, null);
    }

    /** extra: more lines to keep (the Robot editor's draft), may be null. */
    static void save(File f, Map<String, Saveable> controls, Model m, List<String> extra) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(tmp), StandardCharsets.UTF_8))) {
            w.println(HEADER);
            w.println("# Line Follower Robot Simulator workspace. Plain text: settings, then the track.");
            for (Map.Entry<String, Saveable> e : controls.entrySet()) {
                w.println("set " + e.getKey() + "=" + e.getValue().value());
            }
            w.println("mismatch " + join(m.mmGain) + " " + join(m.mmOff));
            double[] cal = new double[2 + 2 * Model.MAXR * Model.MAXS];
            cal[0] = m.calWhite;
            cal[1] = m.calBlack;
            for (int r = 0; r < Model.MAXR; r++) {
                for (int i = 0; i < Model.MAXS; i++) {
                    cal[2 + r * Model.MAXS + i] = m.calMin[r][i];
                    cal[2 + Model.MAXR * Model.MAXS + r * Model.MAXS + i] = m.calMax[r][i];
                }
            }
            w.println("calibration " + join(cal));
            if (!m.customOutline.isEmpty()) {
                StringBuilder sb = new StringBuilder("chassis ");
                for (int i = 0; i < m.customOutline.size(); i++) {
                    sb.append(i > 0 ? ";" : "").append(m.customOutline.get(i)[0]).append(',').append(m.customOutline.get(i)[1]);
                }
                w.println(sb);
            }
            for (String l : m.layoutLines()) {
                w.println(l);
            }
            if (extra != null) {
                for (String l : extra) {
                    w.println(l);
                }
            }
            for (String l : runLines(m)) {
                w.println(l);
            }
            TrackFile.writeTrack(w, m);
        }
        if (f.exists() && !f.delete()) {
            tmp.delete();
            throw new IOException("Cannot replace " + f.getName() + " (is it open in another program?)");
        }
        if (!tmp.renameTo(f)) {
            throw new IOException("Cannot write " + f.getName());
        }
    }

    /** Kept runs: one "run" line with the numbers, then its trail, error curve, laps and settings. */
    static List<String> runLines(Model m) {
        List<String> o = new ArrayList<>();
        for (Model.Run r : m.runs) {
            o.add(String.format(java.util.Locale.ROOT, "run %s %06x %d %d %.4f %.3f %.4f %.4f %.5f %.4f", r.name.replace(' ', '_'),
                    r.color.getRGB() & 0xFFFFFF, r.visible ? 1 : 0, r.imported ? 1 : 0, r.time, r.distance, r.avgErr, r.maxErr, r.offLine, r.lost));
            o.add("runxy " + pairs(r.x, r.y, "%.1f"));
            o.add("runerr " + pairs(r.errT, r.err, "%.3f"));
            o.add("runlaps " + join(r.laps));
            for (Map.Entry<String, String> e : r.settings.entrySet()) {
                o.add("runset " + e.getKey() + "=" + e.getValue());
            }
        }
        return o;
    }

    private static String pairs(double[] a, double[] b, String fmt) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < a.length; i++) {
            sb.append(i > 0 ? ";" : "").append(String.format(java.util.Locale.ROOT, fmt, a[i])).append(',')
                    .append(String.format(java.util.Locale.ROOT, fmt, b[i]));
        }
        return sb.toString();
    }

    private static double[][] unpair(String s) {
        String[] p = s.trim().isEmpty() ? new String[0] : s.trim().split(";");
        double[][] d = new double[2][p.length];
        for (int i = 0; i < p.length; i++) {
            String[] q = p[i].split(",");
            d[0][i] = Double.parseDouble(q[0]);
            d[1][i] = Double.parseDouble(q[1]);
        }
        return d;
    }

    /** The kept runs back from runLines (damaged runs are skipped). */
    static List<Model.Run> readRuns(List<String> lines) {
        List<Model.Run> out = new ArrayList<>();
        String[] head = null;
        double[][] xy = {new double[0], new double[0]}, err = {new double[0], new double[0]};
        double[] laps = new double[0];
        Map<String, String> set = new java.util.LinkedHashMap<>();
        List<String> all = new ArrayList<>(lines);
        all.add("run END");
        for (String l : all) {
            try {
                if (l.startsWith("run ")) {
                    if (head != null && head.length >= 11) {
                        Model.Run r = new Model.Run(head[1].replace('_', ' '), new java.awt.Color(Integer.parseInt(head[2], 16)), xy[0], xy[1],
                                err[0], err[1], Double.parseDouble(head[5]), Double.parseDouble(head[6]), Double.parseDouble(head[7]),
                                Double.parseDouble(head[8]), Double.parseDouble(head[9]), Double.parseDouble(head[10]), set);
                        r.visible = head[3].equals("1");
                        r.imported = head[4].equals("1");
                        r.laps = laps;
                        out.add(r);
                    }
                    head = l.split("\\s+");
                    xy = new double[][]{new double[0], new double[0]};
                    err = new double[][]{new double[0], new double[0]};
                    laps = new double[0];
                    set = new java.util.LinkedHashMap<>();
                } else if (l.startsWith("runxy ")) {
                    xy = unpair(l.substring(6));
                } else if (l.startsWith("runerr ")) {
                    err = unpair(l.substring(7));
                } else if (l.startsWith("runlaps ")) {
                    String s = l.substring(8).trim();
                    laps = s.isEmpty() ? new double[0] : parse(s.split(","));
                } else if (l.startsWith("runset ")) {
                    int eq = l.lastIndexOf('=');
                    if (eq > 7) {
                        set.put(l.substring(7, eq), l.substring(eq + 1));
                    }
                }
            } catch (RuntimeException ex) {
                head = null;                                   // a damaged run is left out
            }
        }
        return out;
    }

    private static String join(double[] v) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < v.length; i++) {
            sb.append(i > 0 ? "," : "").append(v[i]);
        }
        return sb.toString();
    }

    private static double[] split(String s, int n) {
        String[] p = s.split(",");
        if (p.length != n) {
            throw new IllegalArgumentException("expected " + n + " numbers");
        }
        return parse(p);
    }

    private static double[] parse(String[] p) {
        int n = p.length;
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            d[i] = Double.parseDouble(p[i]);
        }
        return d;
    }

    /** Per-row arrays saved with 16 sensors a row become MAXS a row (the rest filled with fill). */
    private static double[] widen(double[] v, double fill) {
        int n = Model.MAXR * Model.MAXS;
        if (v.length == n) {
            return v;
        }
        if (v.length != Model.MAXR * 16) {
            throw new IllegalArgumentException("expected " + n + " numbers");
        }
        double[] w = new double[n];
        java.util.Arrays.fill(w, fill);
        for (int r = 0; r < Model.MAXR; r++) {
            System.arraycopy(v, r * 16, w, r * Model.MAXS, 16);
        }
        return w;
    }

    /** Read and check a workspace file. Throws (and nothing is changed) if it is not one or is damaged. */
    static Content read(File f) throws IOException {
        Content c = new Content();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String first = r.readLine();
            if (first == null || !first.startsWith("LineFollowerSim-workspace")) {
                throw new IOException("This is not a LineFollowerSim workspace file.");
            }
            String line;
            while ((line = r.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("set ")) {
                    int eq = t.lastIndexOf('=');
                    if (eq > 4) {
                        c.settings.put(t.substring(4, eq), t.substring(eq + 1));
                    }
                } else if (t.startsWith("mismatch ")) {
                    String[] p = t.split("\\s+");
                    try {
                        c.gain = widen(parse(p[1].split(",")), 0);
                        c.off = widen(parse(p[2].split(",")), 0);
                    } catch (RuntimeException ex) {
                        throw new IOException("Damaged line: " + t);
                    }
                } else if (t.startsWith("chassis ")) {
                    try {
                        c.chassis = new ArrayList<>();
                        for (String pt : t.substring(8).trim().split(";")) {
                            String[] xy = pt.split(",");
                            c.chassis.add(new double[]{Double.parseDouble(xy[0]), Double.parseDouble(xy[1])});
                        }
                    } catch (RuntimeException ex) {
                        throw new IOException("Damaged line: " + t);
                    }
                } else if (t.startsWith("run ") || t.startsWith("runxy ") || t.startsWith("runerr ") || t.startsWith("runlaps ")
                        || t.startsWith("runset ")) {
                    c.runs.add(t);
                } else if (t.startsWith("epic ") || t.startsWith("edwg ") || CodeTab.isCodeLine(t)) {
                    c.draft.add(t);
                } else if (t.startsWith("draft ") || t.startsWith("dwheel ") || t.startsWith("dboard ") || t.startsWith("db ") || t.startsWith("dc ") || t.startsWith("ds ") || t.startsWith("dl ") || t.startsWith("dp ")) {
                    c.draft.add(t);
                } else if (t.startsWith("layout ") || t.startsWith("lsensor ") || t.startsWith("lled ") || t.startsWith("lpart ") || t.startsWith("lboard ")) {
                    c.layout.add(t);
                } else if (t.startsWith("calibration ")) {
                    try {
                        double[] v = parse(t.substring(12).trim().split(","));
                        if (v.length == 2 + 2 * Model.MAXR * 16 && Model.MAXS != 16) {      // older file: 16 sensors per row
                            double[] w = new double[2 + 2 * Model.MAXR * Model.MAXS];
                            w[0] = v[0];
                            w[1] = v[1];
                            double[] mn = widen(java.util.Arrays.copyOfRange(v, 2, 2 + Model.MAXR * 16), 0);
                            double[] mx = widen(java.util.Arrays.copyOfRange(v, 2 + Model.MAXR * 16, v.length), 1023);
                            System.arraycopy(mn, 0, w, 2, mn.length);
                            System.arraycopy(mx, 0, w, 2 + mn.length, mx.length);
                            v = w;
                        }
                        if (v.length != 2 + 2 * Model.MAXR * Model.MAXS) {
                            throw new IllegalArgumentException("calibration size");
                        }
                        c.cal = v;
                    } catch (RuntimeException ex) {
                        throw new IOException("Damaged line: " + t);
                    }
                } else {
                    c.track.add(line);
                }
            }
        }
        TrackFile.readTrack(c.track, new Model());          // dry run: a damaged track is reported before anything changes
        return c;
    }
}
