package linefollowersim;

import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.geom.NoninvertibleTransformException;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the Robot editor can trace over: a picture (photo or scan of a board) and a drawing (DXF / SVG of a
 * PCB or plate). Both sit behind the robot, are never part of it and can be moved, turned and scaled.
 * The picture can be traced automatically: click inside the board and its outline is found by colour.
 *
 * Coordinates: the "plane" is the editor seen from above with X to the robot's right and Y to its front,
 * in mm (robot y = X, robot x = Y).
 */
final class TraceLayer {

    // ---- picture
    BufferedImage img;
    double picX, picY;                     // plane position of the picture centre, mm
    double mmPerPx = 0.25;                 // size of one picture pixel
    double picRot;                         // degrees, + = clockwise seen from above
    boolean picFlip, picVisible = true;
    double picAlpha = 0.6;

    // ---- drawing (DXF / SVG)
    OutlineImport.Underlay dwg;
    String dwgPath;
    double dwgX, dwgY;                     // offset, mm
    int dwgRot90;                          // quarter turns clockwise
    boolean dwgMirror, dwgVisible = true;

    // ------------------------------------------------------------------ picture placement
    /** Picture pixel -> plane mm. */
    AffineTransform picToPlane() {
        AffineTransform at = new AffineTransform();
        at.translate(picX, picY);
        at.rotate(-Math.toRadians(picRot));                  // the plane's y points up: clockwise is negative
        at.scale(mmPerPx * (picFlip ? -1 : 1), -mmPerPx);    // picture y points down
        at.translate(-img.getWidth() / 2.0, -img.getHeight() / 2.0);
        return at;
    }

    /** Fit a new picture so it covers about the robot's size (scale it properly with Set scale). */
    void setPicture(BufferedImage im, double robotSizeMm) {
        img = im;
        picX = 0;
        picY = 0;
        picRot = 0;
        picFlip = false;
        picVisible = true;
        mmPerPx = robotSizeMm / Math.max(im.getWidth(), im.getHeight());
    }

    /** Plane point -> picture pixel (NaN outside the picture). */
    double[] planeToPixel(double x, double y) {
        try {
            Point2D p = picToPlane().inverseTransform(new Point2D.Double(x, y), null);
            if (p.getX() < 0 || p.getY() < 0 || p.getX() >= img.getWidth() || p.getY() >= img.getHeight()) {
                return null;
            }
            return new double[]{p.getX(), p.getY()};
        } catch (NoninvertibleTransformException ex) {
            return null;
        }
    }

    /** Scale the picture about plane point (x, y) by f (Set scale: the measured distance / the true one). */
    void scalePictureAbout(double x, double y, double f) {
        picX = x + (picX - x) * f;
        picY = y + (picY - y) * f;
        mmPerPx *= f;
    }

    // ------------------------------------------------------------------ drawing placement
    /** Drawing mm -> plane mm. */
    double[] dwgToPlane(double x, double y) {
        if (dwgMirror) {
            x = -x;
        }
        for (int k = 0; k < ((dwgRot90 % 4) + 4) % 4; k++) {   // a quarter turn clockwise seen from above: (x, y) -> (y, -x)
            double t = x;
            x = y;
            y = -t;
        }
        return new double[]{x + dwgX, y + dwgY};
    }

    /** Move the drawing so its middle sits on the axle centre. */
    void centreDrawing() {
        dwgX = dwgY = 0;
        double[] b = dwgBounds();
        if (b != null) {
            dwgX = -(b[0] + b[2]) / 2;
            dwgY = -(b[1] + b[3]) / 2;
        }
    }

    /** {minX, minY, maxX, maxY} of the drawing in the plane, or null. */
    double[] dwgBounds() {
        if (dwg == null) {
            return null;
        }
        double[] b = {Double.MAX_VALUE, Double.MAX_VALUE, -Double.MAX_VALUE, -Double.MAX_VALUE};
        List<List<double[]>> all = new ArrayList<>(dwg.closed);
        all.addAll(dwg.open);
        for (List<double[]> l : all) {
            for (double[] p : l) {
                double[] q = dwgToPlane(p[0], p[1]);
                b[0] = Math.min(b[0], q[0]);
                b[1] = Math.min(b[1], q[1]);
                b[2] = Math.max(b[2], q[0]);
                b[3] = Math.max(b[3], q[1]);
            }
        }
        for (double[] c : dwg.circles) {
            double[] q = dwgToPlane(c[0], c[1]);
            b[0] = Math.min(b[0], q[0] - c[2]);
            b[1] = Math.min(b[1], q[1] - c[2]);
            b[2] = Math.max(b[2], q[0] + c[2]);
            b[3] = Math.max(b[3], q[1] + c[2]);
        }
        return b[0] > b[2] ? null : b;
    }

    /** Snap points from the drawing: corners and circle centres, robot frame {x, y}. */
    List<double[]> snapPoints() {
        List<double[]> l = new ArrayList<>();
        if (dwg == null || !dwgVisible) {
            return l;
        }
        for (double[] c : dwg.circles) {
            double[] q = dwgToPlane(c[0], c[1]);
            l.add(new double[]{q[1], q[0]});
        }
        List<List<double[]>> all = new ArrayList<>(dwg.closed);
        all.addAll(dwg.open);
        for (List<double[]> path : all) {
            if (path.size() > 60) {
                continue;                                  // a curve: its sample points are not useful corners
            }
            for (double[] p : path) {
                double[] q = dwgToPlane(p[0], p[1]);
                l.add(new double[]{q[1], q[0]});
            }
        }
        return l;
    }

    /** The smallest closed outline of the drawing around a robot point (robot frame corners), or null. */
    List<RobotDraft.Pt> pickClosed(double rx, double ry) {
        if (dwg == null || !dwgVisible) {
            return null;
        }
        List<double[]> best = null;
        double ba = Double.MAX_VALUE;
        for (List<double[]> l : dwg.closed) {
            Path2D.Double p = new Path2D.Double();
            for (int i = 0; i < l.size(); i++) {
                double[] q = dwgToPlane(l.get(i)[0], l.get(i)[1]);
                if (i == 0) {
                    p.moveTo(q[0], q[1]);
                } else {
                    p.lineTo(q[0], q[1]);
                }
            }
            p.closePath();
            double a = Math.abs(OutlineImport.area(l));
            if (p.contains(ry, rx) && a < ba) {
                ba = a;
                best = l;
            }
        }
        if (best == null) {
            return null;
        }
        List<RobotDraft.Pt> out = new ArrayList<>();
        for (double[] p : best) {
            double[] q = dwgToPlane(p[0], p[1]);
            out.add(new RobotDraft.Pt(q[1], q[0]));
        }
        return out;
    }

    // ------------------------------------------------------------------ painting (screen space)
    /** Draw the picture and the drawing; plane -> screen is (offX + X z, offY - Y z). */
    void paint(Graphics2D g, double offX, double offY, double zoom, boolean dark) {
        AffineTransform view = new AffineTransform(zoom, 0, 0, -zoom, offX, offY);
        if (img != null && picVisible) {
            AffineTransform at = new AffineTransform(view);
            at.concatenate(picToPlane());
            Graphics2D gi = (Graphics2D) g.create();
            gi.setComposite(AlphaComposite.SrcOver.derive((float) Math.max(0.05, Math.min(1, picAlpha))));
            gi.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gi.drawImage(img, at, null);
            gi.dispose();
        }
        if (dwg != null && dwgVisible) {
            Graphics2D gd = (Graphics2D) g.create();
            gd.setColor(dark ? new Color(0x67E8F9) : new Color(0x0E7490));
            gd.setStroke(new BasicStroke(1f));
            List<List<double[]>> all = new ArrayList<>(dwg.closed);
            all.addAll(dwg.open);
            for (int k = 0; k < all.size(); k++) {
                List<double[]> l = all.get(k);
                Path2D.Double p = new Path2D.Double();
                for (int i = 0; i < l.size(); i++) {
                    double[] q = dwgToPlane(l.get(i)[0], l.get(i)[1]);
                    Point2D s = view.transform(new Point2D.Double(q[0], q[1]), null);
                    if (i == 0) {
                        p.moveTo(s.getX(), s.getY());
                    } else {
                        p.lineTo(s.getX(), s.getY());
                    }
                }
                if (k < dwg.closed.size()) {
                    p.closePath();
                }
                gd.draw(p);
            }
            for (double[] c : dwg.circles) {
                double[] q = dwgToPlane(c[0], c[1]);
                Point2D s = view.transform(new Point2D.Double(q[0], q[1]), null);
                double r = Math.max(1.5, c[2] * zoom);
                gd.draw(new java.awt.geom.Ellipse2D.Double(s.getX() - r, s.getY() - r, 2 * r, 2 * r));
                gd.drawLine((int) s.getX() - 2, (int) s.getY(), (int) s.getX() + 2, (int) s.getY());
            }
            gd.dispose();
        }
    }

    // ------------------------------------------------------------------ auto-trace
    /**
     * Find the outline of the region around a robot point whose colour is close to the colour there.
     * tolerance 0-100 (% of the colour range), close = gaps up to this many picture pixels (at the
     * working size) are bridged (printing, holes, parts on the board), detail = corners are kept
     * where the outline leaves a straight line by more than this many mm. Returns robot-frame corners
     * (null: nothing found).
     */
    List<RobotDraft.Pt> autoTrace(double rx, double ry, double tolerance, int close, double detail) {
        return autoTrace(rx, ry, tolerance, close, detail, 0);
    }

    // the region traced so far (mode 1 adds to it, mode 2 cuts from it); reset when the picture changes
    private boolean[] acc;
    private BufferedImage accImg;
    private int accW, accH;

    /** mode 0: a new region; 1: add this region to the last one (Shift+click); 2: cut it away (Alt+click). */
    List<RobotDraft.Pt> autoTrace(double rx, double ry, double tolerance, int close, double detail, int mode) {
        if (img == null) {
            return null;
        }
        double[] seed = planeToPixel(ry, rx);
        if (seed == null) {
            return null;
        }
        double k = Math.min(1, 900.0 / Math.max(img.getWidth(), img.getHeight()));   // work on a picture of at most 900 px
        int w = Math.max(2, (int) Math.round(img.getWidth() * k)), h = Math.max(2, (int) Math.round(img.getHeight() * k));
        BufferedImage small = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = small.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(img, 0, 0, w, h, null);
        g.dispose();
        int sx = Math.min(w - 1, (int) (seed[0] * k)), sy = Math.min(h - 1, (int) (seed[1] * k));
        int[] px = small.getRGB(0, 0, w, h, null, 0, w);
        // the seed colour: the average of a small patch (photos are noisy)
        double[] sc = new double[3];
        int n = 0;
        for (int dy = -2; dy <= 2; dy++) {
            for (int dx = -2; dx <= 2; dx++) {
                int x = sx + dx, y = sy + dy;
                if (x >= 0 && y >= 0 && x < w && y < h) {
                    int c = px[y * w + x];
                    sc[0] += (c >> 16) & 255;
                    sc[1] += (c >> 8) & 255;
                    sc[2] += c & 255;
                    n++;
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            sc[i] /= n;
        }
        double lim = Math.max(1, tolerance) / 100.0 * 441.7;               // 441.7 = the largest RGB distance
        boolean[] like = new boolean[w * h];
        for (int i = 0; i < px.length; i++) {
            int c = px[i];
            double dr = ((c >> 16) & 255) - sc[0], dg = ((c >> 8) & 255) - sc[1], db = (c & 255) - sc[2];
            like[i] = Math.sqrt(dr * dr + dg * dg + db * db) <= lim;
        }
        boolean[] region = flood(like, w, h, sx, sy);
        int own = 0;
        for (boolean b : region) {
            own += b ? 1 : 0;
        }
        if (own > 0.6 * w * h) {
            return null;                                                   // that is the background, not a part of the board
        }
        boolean keepAcc = mode != 0 && acc != null && accImg == img && accW == w && accH == h;
        if (keepAcc) {                                                     // combine with what was traced before
            for (int i = 0; i < region.length; i++) {
                region[i] = mode == 1 ? acc[i] || region[i] : acc[i] && !region[i];
            }
        }
        acc = region.clone();
        accImg = img;
        accW = w;
        accH = h;
        if (close > 0) {                                                   // bridge printing, holes and parts
            region = erode(dilate(region, w, h, close), w, h, close);
        }
        region = largest(region, w, h, keepAcc ? -1 : sy * w + sx);
        int count = 0;
        for (boolean b : region) {
            count += b ? 1 : 0;
        }
        if (count < 20 || count > 0.97 * w * h) {
            return null;                                                   // nothing, or the whole picture (background)
        }
        List<int[]> edge = boundary(region, w, h);
        if (edge.size() < 8) {
            return null;
        }
        AffineTransform at = picToPlane();
        List<double[]> pts = new ArrayList<>();
        for (int[] e : edge) {
            Point2D q = at.transform(new Point2D.Double((e[0] + 0.5) / k, (e[1] + 0.5) / k), null);
            pts.add(new double[]{q.getY(), q.getX()});                     // robot x = plane Y, robot y = plane X
        }
        List<double[]> simple = simplify(pts, Math.max(0.05, detail));
        if (simple.size() < 3) {
            return null;
        }
        List<RobotDraft.Pt> out = new ArrayList<>();
        for (double[] p : simple) {
            out.add(new RobotDraft.Pt(p[0], p[1]));
        }
        return out;
    }

    /** The connected piece containing pixel 'at' (or the biggest piece when at < 0 or not in the mask). */
    private static boolean[] largest(boolean[] m, int w, int h, int at) {
        if (at >= 0 && m[at]) {
            return flood(m, w, h, at % w, at / w);
        }
        int[] lab = new int[m.length];                                     // label every piece in one pass
        int next = 0, bestLab = 0, bestN = 0;
        int[] queue = new int[m.length];
        for (int i = 0; i < m.length; i++) {
            if (!m[i] || lab[i] != 0) {
                continue;
            }
            next++;
            int head = 0, tail = 0, n = 0;
            queue[tail++] = i;
            lab[i] = next;
            while (head < tail) {
                int p = queue[head++], x = p % w, y = p / w;
                n++;
                int[] nb = {x > 0 ? p - 1 : -1, x < w - 1 ? p + 1 : -1, y > 0 ? p - w : -1, y < h - 1 ? p + w : -1};
                for (int j : nb) {
                    if (j >= 0 && m[j] && lab[j] == 0) {
                        lab[j] = next;
                        queue[tail++] = j;
                    }
                }
            }
            if (n > bestN) {
                bestN = n;
                bestLab = next;
            }
        }
        boolean[] best = new boolean[m.length];
        for (int i = 0; i < m.length; i++) {
            best[i] = bestLab != 0 && lab[i] == bestLab;
        }
        return best;
    }

    private static boolean[] flood(boolean[] m, int w, int h, int sx, int sy) {
        boolean[] r = new boolean[w * h];
        if (!m[sy * w + sx]) {
            return r;
        }
        ArrayDeque<Integer> q = new ArrayDeque<>();
        q.add(sy * w + sx);
        r[sy * w + sx] = true;
        while (!q.isEmpty()) {
            int i = q.poll(), x = i % w, y = i / w;
            int[] nb = {x > 0 ? i - 1 : -1, x < w - 1 ? i + 1 : -1, y > 0 ? i - w : -1, y < h - 1 ? i + w : -1};
            for (int j : nb) {
                if (j >= 0 && m[j] && !r[j]) {
                    r[j] = true;
                    q.add(j);
                }
            }
        }
        return r;
    }

    /** Square dilation of radius r (separable: rows, then columns). */
    private static boolean[] dilate(boolean[] m, int w, int h, int r) {
        boolean[] a = new boolean[w * h], b = new boolean[w * h];
        for (int y = 0; y < h; y++) {
            int cnt = 0;
            for (int x = -r; x < w; x++) {
                if (x + r < w && m[y * w + x + r]) {
                    cnt++;
                }
                if (x - r - 1 >= 0 && m[y * w + x - r - 1]) {
                    cnt--;
                }
                if (x >= 0) {
                    a[y * w + x] = cnt > 0;
                }
            }
        }
        for (int x = 0; x < w; x++) {
            int cnt = 0;
            for (int y = -r; y < h; y++) {
                if (y + r < h && a[(y + r) * w + x]) {
                    cnt++;
                }
                if (y - r - 1 >= 0 && a[(y - r - 1) * w + x]) {
                    cnt--;
                }
                if (y >= 0) {
                    b[y * w + x] = cnt > 0;
                }
            }
        }
        return b;
    }

    private static boolean[] erode(boolean[] m, int w, int h, int r) {
        boolean[] inv = new boolean[w * h];
        for (int i = 0; i < inv.length; i++) {
            inv[i] = !m[i];
        }
        boolean[] d = dilate(inv, w, h, r);
        for (int i = 0; i < d.length; i++) {
            d[i] = !d[i];
        }
        return d;
    }

    /** The outer boundary of a region, pixel by pixel (Moore neighbour tracing). */
    static List<int[]> boundary(boolean[] m, int w, int h) {
        int start = -1;
        for (int i = 0; i < m.length && start < 0; i++) {
            if (m[i]) {
                start = i;                                                 // topmost, then leftmost
            }
        }
        List<int[]> out = new ArrayList<>();
        if (start < 0) {
            return out;
        }
        int[][] dir = {{-1, 0}, {-1, -1}, {0, -1}, {1, -1}, {1, 0}, {1, 1}, {0, 1}, {-1, 1}};   // W NW N NE E SE S SW (clockwise)
        int x = start % w, y = start / w, sx = x, sy = y, back = 0, firstDir = -1;
        int limit = 8 * m.length;
        for (int step = 0; step < limit; step++) {
            out.add(new int[]{x, y});
            int found = -1;
            for (int k = 1; k <= 8; k++) {                                 // clockwise from just after the way back
                int d = (back + k) % 8, nx = x + dir[d][0], ny = y + dir[d][1];
                if (nx >= 0 && ny >= 0 && nx < w && ny < h && m[ny * w + nx]) {
                    found = d;
                    break;
                }
            }
            if (found < 0) {
                break;                                                     // a single pixel
            }
            if (x == sx && y == sy && step > 0 && found == firstDir) {
                out.remove(out.size() - 1);
                break;                                                     // round once
            }
            if (step == 0) {
                firstDir = found;
            }
            x += dir[found][0];
            y += dir[found][1];
            back = (found + 4) % 8;                                        // the pixel we came from
        }
        return out;
    }

    /** Douglas-Peucker on a closed outline (eps in the points' units). */
    static List<double[]> simplify(List<double[]> pts, double eps) {
        int n = pts.size();
        if (n < 4) {
            return new ArrayList<>(pts);
        }
        int far = 0;                                                       // split the loop at the point furthest from the first
        double fd = -1;
        for (int i = 1; i < n; i++) {
            double d = Math.hypot(pts.get(i)[0] - pts.get(0)[0], pts.get(i)[1] - pts.get(0)[1]);
            if (d > fd) {
                fd = d;
                far = i;
            }
        }
        boolean[] keep = new boolean[n];
        keep[0] = keep[far] = true;
        dp(pts, 0, far, eps, keep);
        List<double[]> second = new ArrayList<>(pts.subList(far, n));
        second.add(pts.get(0));
        boolean[] k2 = new boolean[second.size()];
        dp(second, 0, second.size() - 1, eps, k2);
        for (int i = 1; i < second.size() - 1; i++) {
            if (k2[i]) {
                keep[far + i] = true;
            }
        }
        List<double[]> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (keep[i]) {
                out.add(pts.get(i));
            }
        }
        return out;
    }

    private static void dp(List<double[]> p, int a, int b, double eps, boolean[] keep) {
        if (b <= a + 1) {
            return;
        }
        double[] pa = p.get(a), pb = p.get(b);
        double best = -1;
        int bi = -1;
        for (int i = a + 1; i < b; i++) {
            double d = java.awt.geom.Line2D.ptSegDist(pa[0], pa[1], pb[0], pb[1], p.get(i)[0], p.get(i)[1]);
            if (d > best) {
                best = d;
                bi = i;
            }
        }
        if (best > eps) {
            keep[bi] = true;
            dp(p, a, bi, eps, keep);
            dp(p, bi, b, eps, keep);
        }
    }

    // ------------------------------------------------------------------ saving (workspace lines)
    List<String> lines() {
        List<String> o = new ArrayList<>();
        if (img != null) {
            String d = TrackFile.embedded(img);
            if (d != null) {
                o.add(String.format(Locale.ROOT, "epic %.4f %.4f %.6f %.3f %d %d %.3f %s", picX, picY, mmPerPx, picRot, picFlip ? 1 : 0,
                        picVisible ? 1 : 0, picAlpha, d));
            }
        }
        if (dwgPath != null) {
            o.add(String.format(Locale.ROOT, "edwg %.4f %.4f %d %d %d %s", dwgX, dwgY, dwgRot90, dwgMirror ? 1 : 0, dwgVisible ? 1 : 0, dwgPath));
        }
        return o;
    }

    /** Read epic / edwg lines (a drawing is only linked: it comes back if the file is still there). */
    void from(List<String> lines) {
        img = null;
        dwg = null;
        dwgPath = null;
        for (String l : lines) {
            try {
                if (l.startsWith("epic ")) {
                    String[] t = l.split("\\s+", 9);
                    BufferedImage im = TrackFile.decode(t[8]);
                    if (im != null) {
                        img = im;
                        picX = Double.parseDouble(t[1]);
                        picY = Double.parseDouble(t[2]);
                        mmPerPx = Double.parseDouble(t[3]);
                        picRot = Double.parseDouble(t[4]);
                        picFlip = t[5].equals("1");
                        picVisible = t[6].equals("1");
                        picAlpha = Double.parseDouble(t[7]);
                    }
                } else if (l.startsWith("edwg ")) {
                    String[] t = l.split("\\s+", 7);
                    java.io.File f = new java.io.File(t[6]);
                    if (f.isFile()) {
                        dwg = OutlineImport.underlay(f);
                        dwgPath = t[6];
                        dwgX = Double.parseDouble(t[1]);
                        dwgY = Double.parseDouble(t[2]);
                        dwgRot90 = Integer.parseInt(t[3]);
                        dwgMirror = t[4].equals("1");
                        dwgVisible = t[5].equals("1");
                    }
                }
            } catch (java.io.IOException | RuntimeException ex) {
                // a picture or drawing that cannot be read is left out
            }
        }
    }
}
