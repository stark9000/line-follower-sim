package linefollowersim;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a chassis outline from a DXF (ASCII: LWPOLYLINE, POLYLINE, LINE, ARC, CIRCLE) or SVG file (polygon,
 * polyline, path, rect, circle, ellipse) and returns the largest closed outline in robot millimetres. The
 * drawing's up direction becomes the robot's front, as in File > Export robot drawing.
 */
final class OutlineImport {

    private OutlineImport() {
    }

    /** Everything in a drawing, for showing it behind the Robot editor (drawing coordinates: x right, y up, mm). */
    static final class Underlay {

        final List<List<double[]>> closed = new ArrayList<>();       // closed outlines
        final List<List<double[]>> open = new ArrayList<>();         // lines that do not close
        final List<double[]> circles = new ArrayList<>();            // x, y, r
        String name;
    }

    private static Underlay collect;                                 // set while reading an underlay (EDT only)

    /** Read a whole DXF / SVG drawing. */
    static synchronized Underlay underlay(File f) throws IOException {
        Underlay u = new Underlay();
        u.name = f.getName();
        collect = u;
        try {
            String name = f.getName().toLowerCase(Locale.ROOT);
            String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
            u.closed.addAll(name.endsWith(".svg") ? svg(text) : dxf(text));
        } finally {
            collect = null;
        }
        if (u.closed.isEmpty() && u.open.isEmpty() && u.circles.isEmpty()) {
            throw new IOException("nothing to show in this drawing");
        }
        return u;
    }

    /** {x forward, y right} points of the biggest closed loop. */
    static List<double[]> read(File f) throws IOException {
        String name = f.getName().toLowerCase(Locale.ROOT);
        String text = new String(Files.readAllBytes(f.toPath()), StandardCharsets.ISO_8859_1);
        List<List<double[]>> loops = name.endsWith(".svg") ? svg(text) : dxf(text);   // drawing coordinates, y up, mm
        List<double[]> best = null;
        double ba = 0;
        boolean anyAround = false;                                         // our own drawings put the axle at 0, 0: the plate holds it
        for (List<double[]> l : loops) {
            anyAround |= l.size() >= 3 && contains(l, 0, 0);
        }
        for (List<double[]> l : loops) {
            double a = Math.abs(area(l));
            if (l.size() >= 3 && a > ba && (!anyAround || contains(l, 0, 0))) {
                ba = a;
                best = l;
            }
        }
        if (best == null) {
            throw new IOException("no closed outline found (draw the plate as one closed polyline)");
        }
        List<double[]> out = new ArrayList<>();
        for (double[] p : best) {
            out.add(new double[]{p[1], p[0]});                             // drawing X = robot right, drawing Y = robot front
        }
        return out;
    }

    private static boolean contains(List<double[]> l, double x, double y) {
        java.awt.geom.Path2D.Double p = new java.awt.geom.Path2D.Double();
        for (int i = 0; i < l.size(); i++) {
            if (i == 0) {
                p.moveTo(l.get(i)[0], l.get(i)[1]);
            } else {
                p.lineTo(l.get(i)[0], l.get(i)[1]);
            }
        }
        p.closePath();
        return p.contains(x, y);
    }

    static double area(List<double[]> l) {
        double a = 0;
        for (int i = 0; i < l.size(); i++) {
            double[] p = l.get(i), q = l.get((i + 1) % l.size());
            a += p[0] * q[1] - q[0] * p[1];
        }
        return a / 2;
    }

    // ------------------------------------------------------------------ DXF
    private static List<List<double[]>> dxf(String text) throws IOException {
        String[] ln = text.split("\r?\n");
        List<String[]> pairs = new ArrayList<>();
        for (int i = 0; i + 1 < ln.length; i += 2) {
            pairs.add(new String[]{ln[i].trim(), ln[i + 1].trim()});
        }
        double unit = 1;
        for (int i = 0; i + 1 < pairs.size(); i++) {
            if (pairs.get(i)[0].equals("9") && pairs.get(i)[1].equals("$INSUNITS")) {
                int u = (int) num(pairs.get(i + 1)[1]);
                unit = u == 1 ? 25.4 : u == 5 ? 10 : u == 6 ? 1000 : 1;
            }
        }
        List<List<double[]>> loops = new ArrayList<>();
        List<double[][]> segs = new ArrayList<>();                         // open pieces to chain: lines and arcs
        boolean inEntities = false;
        for (int i = 0; i < pairs.size(); i++) {
            String c = pairs.get(i)[0], v = pairs.get(i)[1];
            if (c.equals("2") && v.equals("ENTITIES")) {
                inEntities = true;
            }
            if (!inEntities || !c.equals("0")) {
                continue;
            }
            int j = i + 1;
            List<String[]> ent = new ArrayList<>();
            while (j < pairs.size() && !pairs.get(j)[0].equals("0")) {
                ent.add(pairs.get(j++));
            }
            switch (v) {
                case "LWPOLYLINE": {
                    List<double[]> pts = new ArrayList<>();
                    double x = 0;
                    boolean closed = false;
                    for (String[] p : ent) {
                        if (p[0].equals("70")) {
                            closed = ((int) num(p[1]) & 1) != 0;
                        } else if (p[0].equals("10")) {
                            x = num(p[1]) * unit;
                        } else if (p[0].equals("20")) {
                            pts.add(new double[]{x, num(p[1]) * unit});
                        }
                    }
                    addLoopOrSegs(pts, closed, loops, segs);
                    break;
                }
                case "POLYLINE": {
                    boolean closed = false;
                    for (String[] p : ent) {
                        if (p[0].equals("70")) {
                            closed = ((int) num(p[1]) & 1) != 0;
                        }
                    }
                    List<double[]> pts = new ArrayList<>();
                    int k = j;
                    while (k < pairs.size() && !(pairs.get(k)[0].equals("0") && pairs.get(k)[1].equals("SEQEND"))) {
                        if (pairs.get(k)[0].equals("0") && pairs.get(k)[1].equals("VERTEX")) {
                            double x = 0, y = 0;
                            int q = k + 1;
                            while (q < pairs.size() && !pairs.get(q)[0].equals("0")) {
                                if (pairs.get(q)[0].equals("10")) {
                                    x = num(pairs.get(q)[1]);
                                } else if (pairs.get(q)[0].equals("20")) {
                                    y = num(pairs.get(q)[1]);
                                }
                                q++;
                            }
                            pts.add(new double[]{x * unit, y * unit});
                        }
                        k++;
                    }
                    addLoopOrSegs(pts, closed, loops, segs);
                    break;
                }
                case "LINE":
                    segs.add(new double[][]{{get(ent, "10") * unit, get(ent, "20") * unit}, {get(ent, "11") * unit, get(ent, "21") * unit}});
                    break;
                case "CIRCLE": {
                    double cx = get(ent, "10") * unit, cy = get(ent, "20") * unit, r = get(ent, "40") * unit;
                    List<double[]> pts = new ArrayList<>();
                    for (int a = 0; a < 360; a += 6) {
                        pts.add(new double[]{cx + r * Math.cos(Math.toRadians(a)), cy + r * Math.sin(Math.toRadians(a))});
                    }
                    loops.add(pts);
                    if (collect != null) {
                        collect.circles.add(new double[]{cx, cy, r});
                    }
                    break;
                }
                case "ARC": {
                    double cx = get(ent, "10") * unit, cy = get(ent, "20") * unit, r = get(ent, "40") * unit;
                    double a0 = get(ent, "50"), a1 = get(ent, "51");
                    if (a1 < a0) {
                        a1 += 360;
                    }
                    int n = Math.max(2, (int) Math.ceil((a1 - a0) / 6));
                    double[][] s = new double[n + 1][];
                    for (int k = 0; k <= n; k++) {
                        double a = Math.toRadians(a0 + (a1 - a0) * k / n);
                        s[k] = new double[]{cx + r * Math.cos(a), cy + r * Math.sin(a)};
                    }
                    segs.add(s);
                    break;
                }
                default:
            }
        }
        loops.addAll(chain(segs));
        return loops;
    }

    private static void addLoopOrSegs(List<double[]> pts, boolean closed, List<List<double[]>> loops, List<double[][]> segs) {
        if (pts.size() < 2) {
            return;
        }
        double[] a = pts.get(0), b = pts.get(pts.size() - 1);
        if (closed || Math.hypot(a[0] - b[0], a[1] - b[1]) < 1e-3) {
            if (!closed) {
                pts.remove(pts.size() - 1);
            }
            loops.add(pts);
        } else {
            segs.add(pts.toArray(new double[0][]));
        }
    }

    /** Join open pieces end to end into closed loops (ends within 0.05 mm). */
    private static List<List<double[]>> chain(List<double[][]> segs) {
        List<List<double[]>> out = new ArrayList<>();
        List<double[][]> left = new ArrayList<>(segs);
        while (!left.isEmpty()) {
            List<double[]> cur = new ArrayList<>(java.util.Arrays.asList(left.remove(0)));
            boolean grew = true;
            while (grew) {
                grew = false;
                double[] end = cur.get(cur.size() - 1);
                for (int i = 0; i < left.size(); i++) {
                    double[][] s = left.get(i);
                    if (close(s[0], end)) {
                        for (int k = 1; k < s.length; k++) {
                            cur.add(s[k]);
                        }
                    } else if (close(s[s.length - 1], end)) {
                        for (int k = s.length - 2; k >= 0; k--) {
                            cur.add(s[k]);
                        }
                    } else {
                        continue;
                    }
                    left.remove(i);
                    grew = true;
                    break;
                }
            }
            if (cur.size() > 3 && close(cur.get(0), cur.get(cur.size() - 1))) {
                cur.remove(cur.size() - 1);
                out.add(cur);
            } else if (collect != null) {
                collect.open.add(cur);
            }
        }
        return out;
    }

    private static boolean close(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]) < 0.05;
    }

    private static double get(List<String[]> ent, String code) {
        for (String[] p : ent) {
            if (p[0].equals(code)) {
                return num(p[1]);
            }
        }
        return 0;
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException ex) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ SVG
    private static final Pattern NUM = Pattern.compile("[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?");

    private static String attr(String tag, String name) {
        Matcher m = Pattern.compile("(?:\\s|^)" + name + "\\s*=\\s*[\"']([^\"']*)[\"']").matcher(tag);
        return m.find() ? m.group(1) : null;
    }

    private static double[] nums(String s) {
        List<Double> l = new ArrayList<>();
        if (s != null) {
            Matcher m = NUM.matcher(s);
            while (m.find()) {
                l.add(Double.parseDouble(m.group()));
            }
        }
        double[] d = new double[l.size()];
        for (int i = 0; i < d.length; i++) {
            d[i] = l.get(i);
        }
        return d;
    }

    private static List<List<double[]>> svg(String text) {
        double k = 25.4 / 96;                                              // px -> mm unless the file says mm
        Matcher root = Pattern.compile("<svg[^>]*>", Pattern.DOTALL).matcher(text);
        if (root.find()) {
            String t = root.group();
            String w = attr(t, "width");
            double[] vb = nums(attr(t, "viewBox"));
            if (w != null && vb.length == 4 && vb[2] > 0) {
                double wv = nums(w).length > 0 ? nums(w)[0] : vb[2];
                double perUnit = w.endsWith("mm") ? 1 : w.endsWith("cm") ? 10 : w.endsWith("in") ? 25.4 : 25.4 / 96;
                k = wv * perUnit / vb[2];
            } else if (w != null && w.endsWith("mm")) {
                k = 1;
            }
        }
        List<List<double[]>> loops = new ArrayList<>();
        Matcher el = Pattern.compile("<(polygon|polyline|path|rect|circle|ellipse)\\b([^>]*)>", Pattern.DOTALL).matcher(text);
        while (el.find()) {
            String kind = el.group(1), t = el.group(2);
            List<double[]> pts = new ArrayList<>();
            switch (kind) {
                case "polygon":
                case "polyline": {
                    double[] v = nums(attr(t, "points"));
                    for (int i = 0; i + 1 < v.length; i += 2) {
                        pts.add(new double[]{v[i], v[i + 1]});
                    }
                    break;
                }
                case "rect": {
                    double x = val(t, "x"), y = val(t, "y"), w = val(t, "width"), h = val(t, "height");
                    pts.add(new double[]{x, y});
                    pts.add(new double[]{x + w, y});
                    pts.add(new double[]{x + w, y + h});
                    pts.add(new double[]{x, y + h});
                    break;
                }
                case "circle":
                case "ellipse": {
                    double cx = val(t, "cx"), cy = val(t, "cy");
                    double rx = kind.equals("circle") ? val(t, "r") : val(t, "rx"), ry = kind.equals("circle") ? rx : val(t, "ry");
                    if (collect != null && kind.equals("circle")) {
                        collect.circles.add(new double[]{cx * k, -cy * k, rx * k});
                    }
                    for (int a = 0; a < 360; a += 6) {
                        pts.add(new double[]{cx + rx * Math.cos(Math.toRadians(a)), cy + ry * Math.sin(Math.toRadians(a))});
                    }
                    break;
                }
                default:
                    for (List<double[]> sub : path(attr(t, "d"))) {
                        loops.add(scale(sub, k));
                    }
                    continue;
            }
            loops.add(scale(pts, k));
        }
        return loops;
    }

    private static double val(String tag, String name) {
        double[] v = nums(attr(tag, name));
        return v.length > 0 ? v[0] : 0;
    }

    /** SVG y points down: flip it so that up in the drawing is the robot's front. */
    private static List<double[]> scale(List<double[]> pts, double k) {
        List<double[]> o = new ArrayList<>();
        for (double[] p : pts) {
            o.add(new double[]{p[0] * k, -p[1] * k});
        }
        return o;
    }

    /** Sub-paths of an SVG path (M L H V C Q Z, absolute and relative; arcs become a straight line). */
    private static List<List<double[]>> path(String d) {
        List<List<double[]>> out = new ArrayList<>();
        if (d == null) {
            return out;
        }
        Matcher tok = Pattern.compile("[MmLlHhVvCcSsQqTtAaZz]|" + NUM.pattern()).matcher(d);
        List<String> t = new ArrayList<>();
        while (tok.find()) {
            t.add(tok.group());
        }
        List<double[]> cur = null;
        double x = 0, y = 0, sx = 0, sy = 0;
        char cmd = 'M';
        int i = 0;
        while (i < t.size()) {
            String s = t.get(i);
            if (Character.isLetter(s.charAt(0))) {
                cmd = s.charAt(0);
                i++;
                if (cmd == 'Z' || cmd == 'z') {
                    if (cur != null && cur.size() >= 3) {
                        out.add(cur);
                    }
                    cur = null;
                    x = sx;
                    y = sy;
                    continue;
                }
            }
            boolean rel = Character.isLowerCase(cmd);
            char c = Character.toUpperCase(cmd);
            int need = c == 'H' || c == 'V' ? 1 : c == 'C' ? 6 : c == 'S' || c == 'Q' ? 4 : c == 'A' ? 7 : 2;
            if (i + need > t.size()) {
                break;
            }
            double[] a = new double[need];
            for (int k = 0; k < need; k++) {
                a[k] = Double.parseDouble(t.get(i + k));
            }
            i += need;
            double ox = rel ? x : 0, oy = rel ? y : 0;
            switch (c) {
                case 'M':
                    if (cur != null && cur.size() >= 3) {
                        out.add(cur);
                    }
                    cur = new ArrayList<>();
                    x = ox + a[0];
                    y = oy + a[1];
                    sx = x;
                    sy = y;
                    cur.add(new double[]{x, y});
                    cmd = rel ? 'l' : 'L';                                 // further pairs are line-tos
                    continue;
                case 'H':
                    x = ox + a[0];
                    break;
                case 'V':
                    y = oy + a[0];
                    break;
                case 'C':
                case 'Q':
                case 'S': {
                    double[] p0 = {x, y};
                    double ex = ox + a[need - 2], ey = oy + a[need - 1];
                    for (int k = 1; k <= 8 && cur != null; k++) {
                        double tt = k / 8.0;
                        double[] q;
                        if (c == 'C') {
                            double c1x = ox + a[0], c1y = oy + a[1], c2x = ox + a[2], c2y = oy + a[3], u = 1 - tt;
                            q = new double[]{u * u * u * p0[0] + 3 * u * u * tt * c1x + 3 * u * tt * tt * c2x + tt * tt * tt * ex,
                                u * u * u * p0[1] + 3 * u * u * tt * c1y + 3 * u * tt * tt * c2y + tt * tt * tt * ey};
                        } else {
                            double c1x = ox + a[0], c1y = oy + a[1], u = 1 - tt;
                            q = new double[]{u * u * p0[0] + 2 * u * tt * c1x + tt * tt * ex, u * u * p0[1] + 2 * u * tt * c1y + tt * tt * ey};
                        }
                        cur.add(q);
                    }
                    x = ex;
                    y = ey;
                    continue;
                }
                default:                                                   // L, T, A
                    x = ox + a[need - 2];
                    y = oy + a[need - 1];
            }
            if (cur == null) {
                cur = new ArrayList<>();
                cur.add(new double[]{sx, sy});
            }
            cur.add(new double[]{x, y});
        }
        if (cur != null && cur.size() >= 3) {
            double[] f = cur.get(0), l = cur.get(cur.size() - 1);
            if (Math.hypot(f[0] - l[0], f[1] - l[1]) < 0.05) {
                cur.remove(cur.size() - 1);
                out.add(cur);
            }
        }
        return out;
    }
}
