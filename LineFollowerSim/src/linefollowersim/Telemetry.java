package linefollowersim;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;

/**
 * Telemetry out (CSV of the last 2 minutes, every 10 ms) and logs in (a CSV from a real robot or from
 * this program, shown as a kept run so it can be compared with simulated runs).
 */
final class Telemetry {

    private Telemetry() {
    }

    /** Write the recorded telemetry: the Model.TEL_COLS columns, then raw_0 .. raw_n-1 (ADC 0-1023). */
    static int writeCsv(Model m, File f) throws IOException {
        List<float[]> rows = m.telemetry();
        try (PrintWriter w = new PrintWriter(Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8))) {
            w.println("# LineFollowerSim telemetry, every 10 ms. Units in the column names; cmd = wheel speed asked (mm/s), "
                    + "v = wheel speed reached, line_seen / off_line 0 or 1, raw = ADC 0-1023");
            StringBuilder h = new StringBuilder(String.join(",", Model.TEL_COLS));
            for (int k = 0; k < m.nSensors; k++) {
                h.append(",raw_").append(k);
            }
            w.println(h);
            int n = Model.TEL_COLS.length;
            for (float[] r : rows) {
                StringBuilder sb = new StringBuilder();
                for (int k = 0; k < n + m.nSensors; k++) {
                    if (k > 0) {
                        sb.append(',');
                    }
                    double v = r[k];
                    sb.append(k == 0 ? String.format(Locale.ROOT, "%.3f", v)
                            : k == 13 || k == 14 ? String.valueOf((int) v) : String.format(Locale.ROOT, "%.2f", v));
                }
                w.println(sb);
            }
        }
        return rows.size();
    }

    /**
     * Read a log. The first line that is not a comment names the columns. Understood (any case):
     * time: t_s / t / time / time_s (seconds) or t_ms / ms / millis (milliseconds);
     * position: x_mm / x and y_mm / y (optional - without them there is no trail);
     * error: error_mm / error / err (mm, optional). Lines starting with "LOG," (the sketch's serial log)
     * have that prefix removed. Separators: comma, semicolon or tab.
     */
    static Model.Run readLog(Model m, File f) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8)) {
            for (String s; (s = r.readLine()) != null;) {
                s = s.trim();
                if (s.startsWith("LOG,")) {
                    s = s.substring(4);
                }
                if (!s.isEmpty() && !s.startsWith("#")) {
                    lines.add(s);
                }
            }
        }
        int hi = 0;                                          // the header: the first line naming a time column (skips boot messages)
        while (hi < lines.size() && !isHeader(lines.get(hi))) {
            hi++;
        }
        if (hi < lines.size()) {
            lines = lines.subList(hi, lines.size());
        }
        if (lines.size() < 3) {
            throw new IOException("the file has no data rows");
        }
        String sep = lines.get(0).contains(";") ? ";" : lines.get(0).contains("\t") ? "\t" : ",";
        String[] head = lines.get(0).split(sep);
        HashMap<String, Integer> col = new HashMap<>();
        for (int i = 0; i < head.length; i++) {
            col.put(head[i].trim().toLowerCase(Locale.ROOT), i);
        }
        int ct = find(col, "t_s", "t", "time", "time_s", "seconds");
        double tk = 1;
        if (ct < 0) {
            ct = find(col, "t_ms", "ms", "millis", "time_ms");
            tk = 0.001;
        }
        if (ct < 0) {
            throw new IOException("no time column (t_s, time or t_ms)");
        }
        int cx = find(col, "x_mm", "x"), cy = find(col, "y_mm", "y"), ce = find(col, "error_mm", "error", "err", "e");
        if (ce < 0 && (cx < 0 || cy < 0)) {
            throw new IOException("need an error column (error_mm) or a position (x_mm, y_mm)");
        }
        List<double[]> d = new ArrayList<>();
        for (int i = 1; i < lines.size(); i++) {
            String[] p = lines.get(i).split(sep);
            try {
                double t = Double.parseDouble(p[ct].trim()) * tk;
                double x = cx >= 0 ? Double.parseDouble(p[cx].trim()) : Double.NaN;
                double y = cy >= 0 ? Double.parseDouble(p[cy].trim()) : Double.NaN;
                double e = ce >= 0 ? Double.parseDouble(p[ce].trim()) : Double.NaN;
                d.add(new double[]{t, x, y, e});
            } catch (RuntimeException ex) {
                // a line that is not data (a message from the robot): skip it
            }
        }
        if (d.size() < 2) {
            throw new IOException("no rows with numbers");
        }
        int n = d.size();
        double t0 = d.get(0)[0], dist = 0, sum = 0, max = 0;
        int ne = 0;
        boolean pos = cx >= 0 && cy >= 0;
        double[] x = new double[pos ? n : 0], y = new double[pos ? n : 0];
        List<Double> et = new ArrayList<>(), ev = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            double[] r = d.get(i);
            if (pos) {
                x[i] = r[1];
                y[i] = r[2];
                if (i > 0) {
                    dist += Math.hypot(r[1] - x[i - 1], r[2] - y[i - 1]);
                }
            }
            if (!Double.isNaN(r[3])) {
                et.add(r[0] - t0);
                ev.add(r[3]);
                sum += Math.abs(r[3]);
                max = Math.max(max, Math.abs(r[3]));
                ne++;
            }
        }
        double[] a = et.stream().mapToDouble(Double::doubleValue).toArray(), b = ev.stream().mapToDouble(Double::doubleValue).toArray();
        String name = f.getName().replaceFirst("\\.[^.]*$", "");
        Model.Run run = new Model.Run(name.length() > 14 ? name.substring(0, 14) : name, m.nextRunColor(), x, y, a, b,
                d.get(n - 1)[0] - t0, dist, ne > 0 ? sum / ne : 0, max, 0, 0, new HashMap<>());
        run.imported = true;
        m.runs.add(run);
        return run;
    }

    private static boolean isHeader(String s) {
        for (String c : s.toLowerCase(Locale.ROOT).split("[,;\t]")) {
            switch (c.trim()) {
                case "t_s": case "t": case "time": case "time_s": case "seconds": case "t_ms": case "ms": case "millis": case "time_ms":
                    return true;
                default:
            }
        }
        return false;
    }

    private static int find(HashMap<String, Integer> col, String... names) {
        for (String s : names) {
            Integer i = col.get(s);
            if (i != null) {
                return i;
            }
        }
        return -1;
    }
}
