package linefollowersim;

import java.awt.Shape;
import java.awt.geom.PathIterator;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A 1:1 drawing in millimetres (y up, like CAD) made of polylines, circles and text on named layers,
 * written as DXF (R12 ASCII, opens in LightBurn, RDWorks, LibreCAD, Inkscape, AutoCAD, Fusion,
 * FreeCAD) or SVG (sized in mm).
 */
final class Drawing {

    static final class Item {

        final String layer;
        final double[] pts;          // polyline x0,y0,x1,y1,... ; circle cx,cy,r ; text x,y,height
        final boolean closed, circle;
        final String text;

        Item(String layer, double[] pts, boolean closed, boolean circle, String text) {
            this.layer = layer;
            this.pts = pts;
            this.closed = closed;
            this.circle = circle;
            this.text = text;
        }
    }

    /** Layer name -> DXF colour number (1 red, 2 yellow, 3 green, 4 cyan, 5 blue, 6 magenta, 7 black/white, 8 grey). */
    final Map<String, Integer> layers = new LinkedHashMap<>();
    final List<Item> items = new ArrayList<>();

    void layer(String name, int color) {
        layers.put(name, color);
    }

    void poly(String layer, List<double[]> p, boolean closed) {
        if (p.size() < 2) {
            return;
        }
        double[] a = new double[p.size() * 2];
        for (int i = 0; i < p.size(); i++) {
            a[2 * i] = p.get(i)[0];
            a[2 * i + 1] = p.get(i)[1];
        }
        items.add(new Item(layer, a, closed, false, null));
    }

    void line(String layer, double x0, double y0, double x1, double y1) {
        items.add(new Item(layer, new double[]{x0, y0, x1, y1}, false, false, null));
    }

    void rect(String layer, double x, double y, double w, double h) {
        items.add(new Item(layer, new double[]{x, y, x + w, y, x + w, y + h, x, y + h}, true, false, null));
    }

    void circle(String layer, double cx, double cy, double r) {
        items.add(new Item(layer, new double[]{cx, cy, r}, false, true, null));
    }

    void text(String layer, double x, double y, double h, String s) {
        items.add(new Item(layer, new double[]{x, y, h}, false, false, s));
    }

    /** Add every outline of a shape (flattened to 'tol' mm), mapping each point with 'map' (e.g. flip y). */
    void shape(String layer, Shape s, double tol, java.util.function.UnaryOperator<double[]> map) {
        double[] c = new double[6];
        List<double[]> cur = new ArrayList<>();
        for (PathIterator it = s.getPathIterator(null, tol); !it.isDone(); it.next()) {
            int t = it.currentSegment(c);
            if (t == PathIterator.SEG_MOVETO) {
                poly(layer, cur, false);
                cur = new ArrayList<>();
                cur.add(map.apply(new double[]{c[0], c[1]}));
            } else if (t == PathIterator.SEG_LINETO) {
                cur.add(map.apply(new double[]{c[0], c[1]}));
            } else if (t == PathIterator.SEG_CLOSE) {
                if (cur.size() > 1) {
                    double[] a = cur.get(0), b = cur.get(cur.size() - 1);
                    if (Math.abs(a[0] - b[0]) < 1e-6 && Math.abs(a[1] - b[1]) < 1e-6) {
                        cur.remove(cur.size() - 1);
                    }
                }
                if (cur.size() >= 2) {
                    double[] arr = new double[cur.size() * 2];
                    for (int i = 0; i < cur.size(); i++) {
                        arr[2 * i] = cur.get(i)[0];
                        arr[2 * i + 1] = cur.get(i)[1];
                    }
                    items.add(new Item(layer, arr, true, false, null));
                }
                cur = new ArrayList<>();
            }
        }
        poly(layer, cur, false);
    }

    double[] bounds() {
        double x0 = 1e18, y0 = 1e18, x1 = -1e18, y1 = -1e18;
        for (Item it : items) {
            if (it.circle) {
                x0 = Math.min(x0, it.pts[0] - it.pts[2]);
                x1 = Math.max(x1, it.pts[0] + it.pts[2]);
                y0 = Math.min(y0, it.pts[1] - it.pts[2]);
                y1 = Math.max(y1, it.pts[1] + it.pts[2]);
            } else {
                int n = it.text != null ? 2 : it.pts.length;
                for (int i = 0; i + 1 < n; i += 2) {
                    x0 = Math.min(x0, it.pts[i]);
                    x1 = Math.max(x1, it.pts[i] + (it.text != null ? it.text.length() * it.pts[2] * 0.78 : 0));
                    y0 = Math.min(y0, it.pts[i + 1]);
                    y1 = Math.max(y1, it.pts[i + 1] + (it.text != null ? it.pts[2] : 0));
                }
            }
        }
        return x0 > x1 ? new double[]{0, 0, 1, 1} : new double[]{x0, y0, x1, y1};
    }

    // ------------------------------------------------------------------ DXF R12 (ASCII)
    private static String n(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }

    void writeDxf(File f) throws IOException {
        double[] b = bounds();
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.US_ASCII))) {
            w.print("0\r\nSECTION\r\n2\r\nHEADER\r\n9\r\n$ACADVER\r\n1\r\nAC1009\r\n9\r\n$INSUNITS\r\n70\r\n4\r\n"
                    + "9\r\n$EXTMIN\r\n10\r\n" + n(b[0]) + "\r\n20\r\n" + n(b[1]) + "\r\n9\r\n$EXTMAX\r\n10\r\n" + n(b[2]) + "\r\n20\r\n" + n(b[3])
                    + "\r\n0\r\nENDSEC\r\n");
            w.print("0\r\nSECTION\r\n2\r\nTABLES\r\n0\r\nTABLE\r\n2\r\nLTYPE\r\n70\r\n1\r\n0\r\nLTYPE\r\n2\r\nCONTINUOUS\r\n70\r\n0\r\n"
                    + "3\r\nSolid line\r\n72\r\n65\r\n73\r\n0\r\n40\r\n0.0\r\n0\r\nENDTAB\r\n");
            w.print("0\r\nTABLE\r\n2\r\nLAYER\r\n70\r\n" + layers.size() + "\r\n");
            for (Map.Entry<String, Integer> e : layers.entrySet()) {
                w.print("0\r\nLAYER\r\n2\r\n" + e.getKey() + "\r\n70\r\n0\r\n62\r\n" + e.getValue() + "\r\n6\r\nCONTINUOUS\r\n");
            }
            w.print("0\r\nENDTAB\r\n0\r\nENDSEC\r\n0\r\nSECTION\r\n2\r\nENTITIES\r\n");
            for (Item it : items) {
                if (it.circle) {
                    w.print("0\r\nCIRCLE\r\n8\r\n" + it.layer + "\r\n10\r\n" + n(it.pts[0]) + "\r\n20\r\n" + n(it.pts[1]) + "\r\n30\r\n0.0\r\n40\r\n"
                            + n(it.pts[2]) + "\r\n");
                } else if (it.text != null) {
                    w.print("0\r\nTEXT\r\n8\r\n" + it.layer + "\r\n10\r\n" + n(it.pts[0]) + "\r\n20\r\n" + n(it.pts[1]) + "\r\n30\r\n0.0\r\n40\r\n"
                            + n(it.pts[2]) + "\r\n1\r\n" + it.text.replaceAll("[^\\x20-\\x7E]", "?") + "\r\n");
                } else if (it.pts.length == 4 && !it.closed) {
                    w.print("0\r\nLINE\r\n8\r\n" + it.layer + "\r\n10\r\n" + n(it.pts[0]) + "\r\n20\r\n" + n(it.pts[1]) + "\r\n30\r\n0.0\r\n11\r\n"
                            + n(it.pts[2]) + "\r\n21\r\n" + n(it.pts[3]) + "\r\n31\r\n0.0\r\n");
                } else {
                    w.print("0\r\nPOLYLINE\r\n8\r\n" + it.layer + "\r\n66\r\n1\r\n10\r\n0.0\r\n20\r\n0.0\r\n30\r\n0.0\r\n70\r\n" + (it.closed ? 1 : 0) + "\r\n");
                    for (int i = 0; i + 1 < it.pts.length; i += 2) {
                        w.print("0\r\nVERTEX\r\n8\r\n" + it.layer + "\r\n10\r\n" + n(it.pts[i]) + "\r\n20\r\n" + n(it.pts[i + 1]) + "\r\n30\r\n0.0\r\n");
                    }
                    w.print("0\r\nSEQEND\r\n8\r\n" + it.layer + "\r\n");
                }
            }
            w.print("0\r\nENDSEC\r\n0\r\nEOF\r\n");
        }
    }

    // ------------------------------------------------------------------ SVG (mm)
    private static final String[] SVG_COLORS = {"#000000", "#e00000", "#c8b400", "#00a000", "#00a0a0", "#0000e0", "#c000c0",
        "#000000", "#808080"};

    void writeSvg(File f) throws IOException {
        double[] b = bounds();
        double pad = 5, x0 = b[0] - pad, y1 = b[3] + pad, w = b[2] - b[0] + 2 * pad, h = b[3] - b[1] + 2 * pad;
        try (PrintWriter p = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            p.println("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
            p.printf(Locale.ROOT, "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"%.3fmm\" height=\"%.3fmm\" viewBox=\"0 0 %.3f %.3f\">%n",
                    w, h, w, h);
            p.println("<!-- LineFollowerSim drawing, 1 unit = 1 mm. Layers are groups. -->");
            for (Map.Entry<String, Integer> e : layers.entrySet()) {
                int ci = e.getValue();
                String col = ci >= 0 && ci < SVG_COLORS.length ? SVG_COLORS[ci] : "#000000";
                p.printf("<g id=\"%s\" stroke=\"%s\" fill=\"none\" stroke-width=\"0.2\">%n", e.getKey(), col);
                for (Item it : items) {
                    if (!it.layer.equals(e.getKey())) {
                        continue;
                    }
                    if (it.circle) {
                        p.printf(Locale.ROOT, "<circle cx=\"%.3f\" cy=\"%.3f\" r=\"%.3f\"/>%n", it.pts[0] - x0, y1 - it.pts[1], it.pts[2]);
                    } else if (it.text != null) {
                        p.printf(Locale.ROOT, "<text x=\"%.3f\" y=\"%.3f\" font-size=\"%.2f\" font-family=\"sans-serif\" fill=\"%s\" stroke=\"none\">%s</text>%n",
                                it.pts[0] - x0, y1 - it.pts[1], it.pts[2] * 1.3, col, it.text.replace("&", "&amp;").replace("<", "&lt;"));
                    } else {
                        StringBuilder sb = new StringBuilder();
                        for (int i = 0; i + 1 < it.pts.length; i += 2) {
                            sb.append(String.format(Locale.ROOT, "%.3f,%.3f ", it.pts[i] - x0, y1 - it.pts[i + 1]));
                        }
                        p.printf("<%s points=\"%s\"/>%n", it.closed ? "polygon" : "polyline", sb.toString().trim());
                    }
                }
                p.println("</g>");
            }
            p.println("</svg>");
        }
    }
}
