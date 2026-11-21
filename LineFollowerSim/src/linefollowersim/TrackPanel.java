package linefollowersim;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.geom.*;

/**
 * Track view. Everything is drawn in real millimetres through a view
 * transform: either the whole arena fitted to the panel, or a camera that
 * follows the robot at a fixed pixels-per-mm zoom.
 */
public class TrackPanel extends JPanel {

    static final double MIN_ZOOM = 0.02, MAX_ZOOM = 20;       // pixels per mm
    private static final Color FLOOR = new Color(252, 252, 250);

    final Model m;
    boolean showTrail = true;
    final TrackEditor editor;
    private boolean spaceDown;                            // Space held: drag pans the view
    private int scaleStep;                                // 0 off; 1 waiting for the first point, 2 for the second
    private Point2D.Double scaleP1, scaleP2;
    private java.util.function.Consumer<double[]> scaleDone;
    /** 0 = fit whole arena, 1 = follow robot, 2 = free (zoom and pan by hand). */
    int viewMode = 0;
    double zoom = 1.0;                                    // px per mm in follow / free mode
    double cx, cy;                                        // world point at the panel centre (free mode)

    TrackPanel(Model m) {
        this.m = m;
        this.editor = new TrackEditor(m);
        this.cx = m.arenaW / 2;
        this.cy = m.arenaH / 2;
        setPreferredSize(new Dimension(Model.W, Model.H));
        setFocusable(true);
        MouseAdapter ma = new MouseAdapter() {
            Point lastScreen;
            boolean panning;

            private void prepare() {
                editor.setScale(viewTransform().getScaleX());
            }

            @Override
            public void mousePressed(MouseEvent e) {
                requestFocusInWindow();
                lastScreen = e.getPoint();
                panning = SwingUtilities.isMiddleMouseButton(e) || spaceDown
                        || (editor.tool == TrackEditor.PAN && SwingUtilities.isLeftMouseButton(e));
                if (panning) {
                    setCursor(Cursor.getPredefinedCursor(Cursor.MOVE_CURSOR));
                    return;
                }
                if (scaleStep > 0) {                              // measuring a distance on the background picture
                    Point2D w = toWorld(e.getPoint());
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        if (scaleStep == 1) {
                            scaleP1 = new Point2D.Double(w.getX(), w.getY());
                            scaleStep = 2;
                        } else {
                            scaleP2 = new Point2D.Double(w.getX(), w.getY());
                            scaleStep = 0;
                            java.util.function.Consumer<double[]> done = scaleDone;
                            double[] pts = {scaleP1.x, scaleP1.y, scaleP2.x, scaleP2.y};
                            boolean ok = scaleP1.distance(scaleP2) > 1;
                            repaint();
                            if (done != null && ok) {
                                done.accept(pts);
                            }
                            scaleP1 = null;                       // measured: remove the markers
                            scaleP2 = null;
                        }
                    } else {
                        scaleStep = 0;                            // right click cancels
                        scaleP1 = null;
                        scaleP2 = null;
                    }
                    return;
                }
                prepare();
                if (SwingUtilities.isRightMouseButton(e) && editor.tool == TrackEditor.PAN) {
                    editor.setTool(TrackEditor.SELECT);
                } else {
                    editor.press(toWorld(e.getPoint()), SwingUtilities.isLeftMouseButton(e), e.getClickCount(),
                            e.isShiftDown(), e.isAltDown());
                }
                updateCursor();
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                if (panning) {
                    panBy(e.getX() - lastScreen.x, e.getY() - lastScreen.y);
                    lastScreen = e.getPoint();
                    return;
                }
                prepare();
                editor.drag(toWorld(e.getPoint()), e.isShiftDown(), e.isAltDown());
            }

            @Override
            public void mouseMoved(MouseEvent e) {
                prepare();
                editor.hover(toWorld(e.getPoint()), e.isAltDown());
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (panning) {
                    panning = false;
                    updateCursor();
                    return;
                }
                prepare();
                editor.release(toWorld(e.getPoint()));
            }

            @Override
            public void mouseWheelMoved(MouseWheelEvent e) {
                zoomBy(Math.pow(1.15, -e.getPreciseWheelRotation()), e.getPoint());
            }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);
        installKeys();
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setBackground(FLOOR);
    }

    // ------------------------------------------------------------------ view (zoom / pan)
    private static Action act(Runnable r) {
        return new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        };
    }

    /** Keyboard shortcuts while the track has focus (click it once): + - zoom, 0 fit, F follow, arrows pan. */
    private void installKeys() {
        InputMap im = getInputMap(WHEN_FOCUSED);
        ActionMap am = getActionMap();
        Object[][] keys = {
            {KeyStroke.getKeyStroke('+'), "zin", act(() -> zoomBy(1.25, null))},
            {KeyStroke.getKeyStroke('='), "zin2", act(() -> zoomBy(1.25, null))},
            {KeyStroke.getKeyStroke('-'), "zout", act(() -> zoomBy(0.8, null))},
            {KeyStroke.getKeyStroke('0'), "fit", act(() -> setViewMode(0))},
            {KeyStroke.getKeyStroke('f'), "follow", act(() -> setViewMode(viewMode == 1 ? 2 : 1))},
            {KeyStroke.getKeyStroke("LEFT"), "pl", act(() -> panBy(40, 0))},
            {KeyStroke.getKeyStroke("RIGHT"), "pr", act(() -> panBy(-40, 0))},
            {KeyStroke.getKeyStroke("UP"), "pu", act(() -> panBy(0, 40))},
            {KeyStroke.getKeyStroke("DOWN"), "pd", act(() -> panBy(0, -40))}
        };
        for (Object[] k : keys) {
            im.put((KeyStroke) k[0], k[1]);
            am.put(k[1], (Action) k[2]);
        }
        Object[][] edit = {
            {KeyStroke.getKeyStroke("pressed SPACE"), "spaceOn", act(() -> setSpace(true))},
            {KeyStroke.getKeyStroke("released SPACE"), "spaceOff", act(() -> setSpace(false))},
            {KeyStroke.getKeyStroke("DELETE"), "del", act(editor::deleteSelected)},
            {KeyStroke.getKeyStroke("BACK_SPACE"), "del2", act(editor::deleteSelected)},
            {KeyStroke.getKeyStroke("ESCAPE"), "esc", act(this::escapeKey)},
            {KeyStroke.getKeyStroke("ENTER"), "enter", act(editor::finish)},
            {KeyStroke.getKeyStroke("ctrl Z"), "undo", act(editor::undo)},
            {KeyStroke.getKeyStroke("ctrl Y"), "redo", act(editor::redo)},
            {KeyStroke.getKeyStroke("ctrl shift Z"), "redo2", act(editor::redo)},
            {KeyStroke.getKeyStroke("ctrl D"), "dup", act(editor::duplicateSelected)}
        };
        for (Object[] k : edit) {
            im.put((KeyStroke) k[0], k[1]);
            am.put(k[1], (Action) k[2]);
        }
    }

    private void setSpace(boolean down) {
        spaceDown = down;
        setCursor(Cursor.getPredefinedCursor(down ? Cursor.MOVE_CURSOR : Cursor.DEFAULT_CURSOR));
        if (!down) {
            updateCursor();
        }
    }

    /** Esc: stop measuring, else cancel drawing / back to Select / deselect. */
    void escapeKey() {
        if (scaleStep > 0) {
            scaleStep = 0;
            scaleP1 = null;
            scaleP2 = null;
        } else {
            editor.escape();
        }
        updateCursor();
    }

    private String toastText;
    private long toastUntil;

    /** A short notice in the corner of the track (e.g. "Saved"). */
    void toast(String text) {
        toastText = text;
        toastUntil = System.currentTimeMillis() + 2500;
    }

    /** Ask for two clicks on the track (the ends of a known distance on the background picture). */
    void startScale(java.util.function.Consumer<double[]> done) {
        scaleDone = done;
        scaleStep = 1;
        scaleP1 = null;
        scaleP2 = null;
    }

    boolean isMeasuring() {
        return scaleStep > 0;
    }

    void setTool(int t) {
        editor.setTool(t);
        updateCursor();
    }

    private void updateCursor() {
        int t = editor.tool;
        int c = t == TrackEditor.PAN ? Cursor.MOVE_CURSOR
                : (t == TrackEditor.SELECT || t == TrackEditor.ROBOT) ? Cursor.DEFAULT_CURSOR : Cursor.CROSSHAIR_CURSOR;
        setCursor(Cursor.getPredefinedCursor(c));
    }

    /** World (mm) to screen (px). */
    AffineTransform viewTransform() {
        double pw = Math.max(getWidth(), 1), ph = Math.max(getHeight(), 1);
        if (viewMode == 0) {
            double s = Math.min(pw / m.arenaW, ph / m.arenaH);
            return new AffineTransform(s, 0, 0, s, (pw - m.arenaW * s) / 2, (ph - m.arenaH * s) / 2);
        }
        double z = zoom;
        double wx = viewMode == 1 ? m.x : cx, wy = viewMode == 1 ? m.y : cy;
        if (viewMode == 2) {                                   // free view: the arena never leaves the middle of the view
            wx = Math.max(0, Math.min(m.arenaW, wx));
            wy = Math.max(0, Math.min(m.arenaH, wy));
        }
        return new AffineTransform(z, 0, 0, z, pw / 2 - wx * z, ph / 2 - wy * z);
    }

    /** Scale (px per mm) at which the whole arena just fits the panel. */
    double fitScale() {
        double pw = Math.max(getWidth(), 1), ph = Math.max(getHeight(), 1);
        return Math.min(pw / m.arenaW, ph / m.arenaH);
    }

    /** Smallest zoom: the arena at a tenth of the fit size (never below MIN_ZOOM). */
    private double minZoom() {
        return Math.max(MIN_ZOOM, Math.min(fitScale() * 0.1, 1.0));
    }

    Point2D toWorld(Point2D p) {
        try {
            // both points must be Point2D.Double: with any other source type Java returns float precision
            return viewTransform().inverseTransform(new Point2D.Double(p.getX(), p.getY()), new Point2D.Double());
        } catch (NoninvertibleTransformException ex) {
            return new Point2D.Double(p.getX(), p.getY());
        }
    }

    double zoomPct() {
        return viewTransform().getScaleX() * 100.0;
    }

    private static double clampZoom(double z) {
        return Math.max(MIN_ZOOM, Math.min(MAX_ZOOM, z));
    }

    /** Switch to free mode, keeping exactly what is on screen now. */
    private void ensureFree() {
        if (viewMode == 2) {
            return;
        }
        AffineTransform t = viewTransform();
        zoom = clampZoom(t.getScaleX());
        cx = (getWidth() / 2.0 - t.getTranslateX()) / t.getScaleX();
        cy = (getHeight() / 2.0 - t.getTranslateY()) / t.getScaleX();
        viewMode = 2;
    }

    void setViewMode(int mode) {
        if (mode == viewMode) {
            return;
        }
        if (mode == 2) {
            ensureFree();
        } else if (mode == 1) {
            zoom = clampZoom(Math.max(viewTransform().getScaleX(), 1.0));
            viewMode = 1;
        } else {
            viewMode = 0;
        }
    }

    /** Zoom by a factor, keeping the world point under the anchor (screen pixel) fixed; null = panel centre. */
    void zoomBy(double factor, Point2D anchor) {
        if (viewMode == 1) {                              // following: the robot stays in the middle
            zoom = Math.max(clampZoom(zoom * factor), minZoom());
            return;
        }
        ensureFree();
        double pw = getWidth(), ph = getHeight();
        Point2D a = anchor != null ? anchor : new Point2D.Double(pw / 2, ph / 2);
        Point2D wa = toWorld(a);
        double nz = Math.max(clampZoom(zoom * factor), minZoom());
        cx = wa.getX() - (a.getX() - pw / 2) / nz;
        cy = wa.getY() - (a.getY() - ph / 2) / nz;
        zoom = nz;
    }

    /** Set the zoom as a percentage (100 % = 1 px per mm), keeping the current view centre. */
    void setZoomPct(double pct) {
        double z = Math.max(clampZoom(pct / 100.0), minZoom());
        if (viewMode == 1) {
            zoom = z;
        } else {
            ensureFree();
            zoom = z;
        }
    }

    /** Drag the picture by (dx, dy) screen pixels. */
    void panBy(double dx, double dy) {
        ensureFree();
        AffineTransform t = viewTransform();                  // start from what is on screen (already kept inside)
        double z = t.getScaleX();
        cx = (getWidth() / 2.0 - t.getTranslateX()) / z - dx / z;
        cy = (getHeight() / 2.0 - t.getTranslateY()) / z - dy / z;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        AffineTransform at = viewTransform();
        double s = at.getScaleX(), u = 1.0 / s;                 // u = size of one screen pixel in mm
        g.setColor(FLOOR);                                      // the floor goes on past the arena (sensors read white there)
        g.fillRect(0, 0, getWidth(), getHeight());
        Graphics2D gw = (Graphics2D) g.create();
        gw.transform(at);
        gw.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gw.drawImage(m.track, AffineTransform.getScaleInstance(m.res, m.res), null);
        if (m.bgImage != null && m.bgVisible) {                 // picture to trace over
            Composite oc = gw.getComposite();
            gw.setComposite(AlphaComposite.SrcOver.derive((float) Math.max(0, Math.min(1, m.bgAlpha))));
            double k = m.bgW / m.bgImage.getWidth();
            AffineTransform bt = new AffineTransform();
            bt.translate(m.bgX, m.bgY);
            bt.scale(k, k);
            gw.drawImage(m.bgImage, bt, null);
            gw.setComposite(oc);
        }
        gw.setColor(new Color(0, 0, 0, 40));                     // arena edge: soft shadow + thin line + size label
        gw.setStroke(new BasicStroke((float) (4 * u)));
        gw.draw(new Rectangle2D.Double(-2 * u, -2 * u, m.arenaW + 4 * u, m.arenaH + 4 * u));
        gw.setColor(new Color(120, 125, 135));
        gw.setStroke(new BasicStroke((float) u));
        gw.draw(new Rectangle2D.Double(0, 0, m.arenaW, m.arenaH));
        Font lf = new Font(Font.SANS_SERIF, Font.PLAIN, 11);
        Graphics2D gl = (Graphics2D) g.create();
        gl.setFont(lf);
        gl.setColor(new Color(120, 125, 135));
        Point2D corner = at.transform(new Point2D.Double(m.arenaW, m.arenaH), null);
        String size = String.format("%.0f \u00D7 %.0f mm", m.arenaW, m.arenaH);
        gl.drawString(size, (int) corner.getX() - gl.getFontMetrics().stringWidth(size), (int) corner.getY() + 14);
        gl.dispose();
        editor.setScale(s);
        editor.paintGrid(gw, u);
        editor.paintDrawing(gw, u);
        paintPatches(gw, u);
        paintGate(gw, u);
        paintAmbient(gw);
        paintRuns(gw, u);
        if (showTrail) {
            paintTrail(gw, u);
        }
        paintRobot(gw, u);
        editor.paintOverlay(gw, u);
        if (scaleStep > 0 || scaleP1 != null) {
            gw.setColor(new Color(220, 0, 120));
            gw.setStroke(new BasicStroke((float) (1.5 * u)));
            for (Point2D.Double q : new Point2D.Double[]{scaleP1, scaleP2}) {
                if (q != null) {
                    gw.draw(new Ellipse2D.Double(q.x - 5 * u, q.y - 5 * u, 10 * u, 10 * u));
                }
            }
            if (scaleP1 != null && scaleP2 != null) {
                gw.draw(new Line2D.Double(scaleP1, scaleP2));
            }
        }
        gw.dispose();

        paintHud(g);                                            // HUD stays in screen pixels
        paintViewInfo(g);
        g.dispose();
    }

    /** Start / finish line: a chequered strip, with a small arrow on the "forward" side. */
    private void paintGate(Graphics2D g, double u) {
        double[] gt = m.gate();
        double len = Math.hypot(gt[2] - gt[0], gt[3] - gt[1]);
        if (len < 1) {
            return;
        }
        AffineTransform old = g.getTransform();
        g.translate(gt[0], gt[1]);
        g.rotate(Math.atan2(gt[3] - gt[1], gt[2] - gt[0]));
        double sq = Math.max(4, Math.min(10, len / 12));
        int nx = (int) Math.ceil(len / sq);
        for (int i = 0; i < nx; i++) {
            for (int j = 0; j < 2; j++) {
                g.setColor((i + j) % 2 == 0 ? new Color(30, 32, 36, 150) : new Color(255, 255, 255, 150));
                g.fill(new Rectangle2D.Double(i * sq, -sq + j * sq, Math.min(sq, len - i * sq), sq));
            }
        }
        g.setColor(new Color(60, 65, 75, 170));
        g.setStroke(new BasicStroke((float) u));
        g.draw(new Rectangle2D.Double(0, -sq, len, 2 * sq));
        g.setTransform(old);
        g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 1).deriveFont((float) (11 * u)));
        g.setColor(new Color(60, 65, 75));
        g.drawString(m.gateIsFinish() ? "FINISH" : "START / FINISH", (float) (gt[2] + 4 * u), (float) (gt[3] + 4 * u));
    }

    /** Disturbance patches: faded line (orange) and slippery floor (blue). */
    private void paintPatches(Graphics2D g, double u) {
        for (Model.Patch p : m.patches) {
            AffineTransform old = g.getTransform();
            g.translate(p.cx, p.cy);
            g.rotate(p.ang);
            Color c = p.slip ? new Color(0, 110, 255) : new Color(255, 160, 0);
            Rectangle2D r = new Rectangle2D.Double(-p.hl, -p.hw, 2 * p.hl, 2 * p.hw);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 45));
            g.fill(r);
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 200));
            g.setStroke(new BasicStroke((float) (1.5 * u), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f,
                    new float[]{(float) (6 * u), (float) (4 * u)}, 0f));
            g.draw(r);
            g.setTransform(old);
        }
    }

    private void paintAmbient(Graphics2D g) {
        double a = m.ambient;
        if (a < 0.005) {
            return;
        }
        double W = m.arenaW, H = m.arenaH;
        Paint p;
        if (m.ambientMode == 1) {
            p = new GradientPaint(0, 0, ambColor(0), (float) W, 0, ambColor(2 * a));
        } else if (m.ambientMode == 2) {
            p = new RadialGradientPaint(new Point2D.Double(W / 2, H / 2), (float) (2.9 * m.hotSigma()),
                    new float[]{0f, 1f}, new Color[]{ambColor(2.0 * a), ambColor(0.25 * a)});
        } else {
            p = ambColor(a);
        }
        g.setPaint(p);
        g.fill(new Rectangle2D.Double(0, 0, W, H));
    }

    private static Color ambColor(double level) {
        int alpha = (int) (255 * Math.min(0.75, level * 0.35));
        return new Color(255, 225, 90, Math.max(0, alpha));
    }

    /** Trails of the kept runs, in their colours, under the live trail. */
    private void paintRuns(Graphics2D g, double u) {
        for (Model.Run r : m.runs) {
            if (!r.visible || r.x.length < 2) {
                continue;
            }
            Path2D.Double p = new Path2D.Double();
            p.moveTo(r.x[0], r.y[0]);
            for (int i = 1; i < r.x.length; i++) {
                p.lineTo(r.x[i], r.y[i]);
            }
            Color c = r.color;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), 170));
            g.setStroke(new BasicStroke((float) (2 * u), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 10f,
                    new float[]{(float) (7 * u), (float) (4 * u)}, 0f));
            g.draw(p);
        }
    }

    private void paintTrail(Graphics2D g, double u) {
        if (m.trCount < 2) {
            return;
        }
        g.setColor(new Color(30, 140, 255, 190));
        g.setStroke(new BasicStroke((float) (2 * u)));
        int start = (m.trHead - m.trCount + Model.TRAIL) % Model.TRAIL;
        Path2D.Double path = new Path2D.Double();
        for (int j = 0; j < m.trCount; j++) {
            int k = (start + j) % Model.TRAIL;
            if (j == 0) {
                path.moveTo(m.trX[k], m.trY[k]);
            } else {
                path.lineTo(m.trX[k], m.trY[k]);
            }
        }
        g.draw(path);
    }

    private void paintRobot(Graphics2D g, double u) {
        AffineTransform old = g.getTransform();
        g.translate(m.x, m.y);
        g.rotate(m.th);
        double hw = m.wheelBase / 2.0;
        Area body = m.chassisOutline();                                  // the chassis plate (Hardware > Chassis shape)
        g.setColor(new Color(70, 110, 190, 120));
        g.fill(body);
        g.setColor(new Color(20, 40, 100));
        g.setStroke(new BasicStroke((float) (1.5 * u)));
        g.draw(body);
        Area board = m.sensorBoard();                                    // the sensor board
        g.setColor(new Color(30, 120, 70, 110));
        g.fill(board);
        g.setColor(new Color(15, 80, 45));
        g.setStroke(new BasicStroke((float) (1.0 * u)));
        g.draw(board);
        if (m.wheelCount != 4) {                                         // caster (a 4-wheel robot has none)
            double cxs = m.casterX();
            g.setColor(new Color(90, 90, 100));
            g.fill(new Ellipse2D.Double(cxs - 5, -5, 10, 10));
        }
        g.setColor(new Color(35, 35, 35));
        double tw = m.tireWidth;                                         // real diameter and tire width
        for (double[] wp : m.wheelPositions()) {                         // 2 or 4 wheels (a car's front wheels steer)
            Shape wheel = new RoundRectangle2D.Double(wp[0] - m.wheelDia / 2, wp[1] - tw / 2, m.wheelDia, tw, 3, 3);
            if (m.steerMode != 0 && wp[0] > 0) {
                wheel = AffineTransform.getRotateInstance(m.steerAngle, wp[0], wp[1]).createTransformedShape(wheel);
            }
            g.fill(wheel);
        }
        g.setColor(new Color(255, 200, 0));                              // heading marker + axle centre
        g.fill(new Ellipse2D.Double(-3, -3, 6, 6));
        Path2D.Double arrow = new Path2D.Double();
        double ax = m.forward + m.arrayReach();
        arrow.moveTo(ax + 18, 0);
        arrow.lineTo(ax + 6, -6);
        arrow.lineTo(ax + 6, 6);
        arrow.closePath();
        g.fill(arrow);
        double mid = (m.nSensors - 1) / 2.0;
        double r = Math.max(m.spotRadius, 2.5 * u);                      // never smaller than ~2.5 screen px
        for (int i = 0; i < m.nSensors; i++) {
            double lat = m.lat(i);
            double sxp = m.forward + m.dxOf(i);
            int gb = (int) (255 * (1 - m.dark[i]));
            g.setColor(new Color(255, gb, gb, 235));
            g.fill(new Ellipse2D.Double(sxp - r, lat - r, 2 * r, 2 * r));
            g.setColor(Color.BLACK);
            g.setStroke(new BasicStroke((float) (0.8 * u)));
            g.draw(new Ellipse2D.Double(sxp - r, lat - r, 2 * r, 2 * r));
        }
        if (m.whiteLeds) {                                               // LEDs where the Hardware tab places them
            double lr = Math.max(1.6, Math.min(3.0, m.spacing * 0.22));
            double[] lxs = new double[Model.MAXL], lys = new double[Model.MAXL];
            int nl = m.ledLayout(lxs, lys);
            Color lc = Model.LED_RGB[Math.max(0, Math.min(Model.LED_RGB.length - 1, m.ledColor))];
            for (int i = 0; i < nl; i++) {
                double lx = lxs[i], lat = lys[i];
                if (m.ledPlace == 0 && m.ledOffset < r + lr) {
                    lx = lxs[i] + m.ledOffset - r - lr - 0.5;             // drawn just clear of the sensor
                }
                boolean on = m.ledsLit() && (m.whiteMode == 1 || (m.customLeds
                        ? m.whiteLit[Math.max(0, Math.min(m.nSensors - 1, m.cuLedSensor[i]))]
                        : m.ledPlace == 1 ? m.whiteLit[i] || m.whiteLit[i + 1] : m.whiteLit[i]));
                if (on && m.whitePower > 0.01) {
                    float a = (float) Math.min(0.55, 0.15 + 0.45 * m.whitePower);
                    g.setPaint(new RadialGradientPaint(new Point2D.Double(lx, lat), (float) (lr * 3.2), new float[]{0f, 1f},
                            new Color[]{new Color(lc.getRed(), lc.getGreen(), lc.getBlue(), (int) (255 * a)),
                                new Color(lc.getRed(), lc.getGreen(), lc.getBlue(), 0)}));
                    g.fill(new Ellipse2D.Double(lx - lr * 3.2, lat - lr * 3.2, lr * 6.4, lr * 6.4));
                }
                g.setColor(on ? lc : new Color(120, 125, 135));
                g.fill(new Ellipse2D.Double(lx - lr, lat - lr, 2 * lr, 2 * lr));
                g.setColor(new Color(60, 60, 60));
                g.setStroke(new BasicStroke((float) (0.6 * u)));
                g.draw(new Ellipse2D.Double(lx - lr, lat - lr, 2 * lr, 2 * lr));
            }
        }
        for (int row = 1; row < m.nRows; row++) {                        // further sensor rows, behind the front row
            double fwd = m.forward - row * m.rowSpacing;
            for (int i = 0; i < m.nSensors; i++) {
                double lat = m.lat(i);
                int gb = (int) (255 * (1 - m.darkRow[row][i]));
                g.setColor(new Color(255, gb, gb, 200));
                g.fill(new Ellipse2D.Double(fwd + m.dxOf(i) - r * 0.8, lat - r * 0.8, 1.6 * r, 1.6 * r));
                g.setColor(new Color(0, 0, 0, 150));
                g.setStroke(new BasicStroke((float) (0.8 * u)));
                g.draw(new Ellipse2D.Double(fwd + m.dxOf(i) - r * 0.8, lat - r * 0.8, 1.6 * r, 1.6 * r));
            }
        }
        g.setTransform(old);
    }

    private void paintViewInfo(Graphics2D g) {
        String mode = viewMode == 0 ? "fit" : viewMode == 1 ? "follow" : "free";
        String t = String.format("view: %s %.0f %%   wheel = zoom, Space/middle-drag = pan, keys + - 0 F", mode, zoomPct());
        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
        FontMetrics fm = g.getFontMetrics();
        int w = fm.stringWidth(t) + 10, y = getHeight() - 6;
        g.setColor(hudBox(190));
        g.fillRoundRect(6, y - 12, w, 16, 8, 8);
        g.setColor(Theme.text());
        g.drawString(t, 11, y);
    }

    /**
     * Sensor read-out. Analog = ADC bars + numbers (0-1023). Digital = 0/1 boxes
     * + bit string. Which one(s) show is set by Model.readout.
     */
    private void paintHud(Graphics2D g) {
        int n = m.nSensors;
        boolean showA = m.readoutAnalog(), showD = m.readoutDigital();
        int cw = n > 16 ? (showA ? 15 : 12) : showA ? 26 : 19, bw = cw - 4, bh = 34;
        int secH = (showA ? bh + 14 : 0) + (showD ? 36 : 0);
        int boxW = Math.max(n * cw + 14, 250), boxH = secH + 134;
        g.setColor(hudBox(222));
        g.fillRoundRect(6, 6, boxW, boxH, 10, 10);
        Color ink = Theme.text(), cell = Theme.isDark() ? new Color(0x3A3D43) : new Color(235, 235, 235);

        int y = 12;
        if (showA) {
            Font small = new Font(Font.MONOSPACED, Font.PLAIN, 9);
            for (int i = 0; i < n; i++) {
                int bx = 12 + i * cw;
                g.setColor(cell);
                g.fillRect(bx, y, bw, bh);
                int fill = (int) Math.round(m.raw[i] / 1023.0 * bh);
                g.setColor(m.dark[i] > 0.5 ? new Color(220, 60, 60) : new Color(110, 120, 140));
                g.fillRect(bx, y + bh - fill, bw, fill);
                g.setColor(Theme.textDim());
                g.drawRect(bx, y, bw, bh);
                if (!m.analogMode || showD) {                              // threshold tick (per sensor after a sweep)
                    int ty = y + bh - (int) Math.round(m.hudThreshold(i) / 1023.0 * bh);
                    g.setColor(new Color(0, 160, 0));
                    g.drawLine(bx - 1, ty, bx + bw + 1, ty);
                }
                if (n <= 16) {                                             // 32 sensors: the bars only
                    g.setFont(small);
                    g.setColor(ink);
                    g.drawString(String.format("%4.0f", m.raw[i]), bx - 1, y + bh + 10);
                }
            }
            y += bh + 14;
        }
        if (showD) {
            Font bold = new Font(Font.MONOSPACED, Font.BOLD, 11);
            g.setFont(bold);
            FontMetrics fm = g.getFontMetrics();
            StringBuilder bits = new StringBuilder();
            for (int i = 0; i < n; i++) {
                boolean one = m.bit(i);
                bits.append(one ? '1' : '0');
                int bx = 12 + i * cw;
                g.setColor(one ? new Color(220, 60, 60) : cell);
                g.fillRect(bx, y, bw, 16);
                g.setColor(Theme.textDim());
                g.drawRect(bx, y, bw, 16);
                g.setColor(one ? Color.WHITE : Theme.textDim());
                String d = one ? "1" : "0";
                g.drawString(d, bx + (bw - fm.stringWidth(d)) / 2, y + 12);
            }
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
            g.setColor(ink);
            g.drawString("bits = " + bits, 12, y + 30);
            y += 36;
        }

        g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11));
        int ty = y + 10;
        g.setColor(ink);
        g.drawString(String.format("t = %6.2f s    v = %4.0f mm/s", m.t, (m.vL + m.vR) / 2), 12, ty);
        if (m.lineVisible) {
            g.drawString(String.format("error    = %6.1f mm", m.error), 12, ty + 14);
        } else {
            g.setColor(new Color(0xF0, 0x40, 0x40));
            g.drawString(String.format("error    = %6.1f mm  LINE LOST", m.error), 12, ty + 14);
            g.setColor(ink);
        }
        double pct = m.t > 0 ? 100.0 * m.offTrackTime / m.t : 0;
        g.drawString(String.format("off-line  = %5.2f s (%.0f%%)", m.offTrackTime, pct), 12, ty + 28);
        g.drawString(String.format("avg|err|  = %6.1f mm", m.avgAbsError()), 12, ty + 42);
        g.drawString(String.format("distance  = %6.2f m", m.distance / 1000.0), 12, ty + 56);
        double loopMs = m.loopPeriodSec() * 1000.0;
        String clk = m.clockMHz == Math.rint(m.clockMHz) ? String.format("%.0f", m.clockMHz) : String.format("%.1f", m.clockMHz);
        g.drawString(String.format("MCU %s MHz  loop %.2f ms", clk, loopMs), 12, ty + 70);
        int nl = m.laps.size();
        String lap;
        if (m.gateIsFinish()) {
            lap = nl > 0 ? String.format("finish    = %6.2f s", m.laps.get(0)) : "finish    = -";
        } else {
            lap = nl == 0 ? "laps 0" : String.format("laps %d  last %.2f s  best %.2f s", nl, m.laps.get(nl - 1), m.bestLap());
        }
        g.drawString(lap, 12, ty + 84);
        double[] cs = m.calibrationStatus();
        g.setColor(cs[0] == 0 ? new Color(40, 170, 80) : cs[0] == 1 ? new Color(220, 150, 20) : new Color(0xF0, 0x40, 0x40));
        g.drawString("cal " + (cs[0] == 0 ? "ok: " : cs[0] == 1 ? "check: " : "FAIL: ") + m.calText, 12, ty + 98);
        g.setColor(ink);
        double rt = m.runtimeMin();
        g.drawString(String.format("batt %4.1f V %4.2f A%s%s", m.battNow(), m.currentNow, Double.isNaN(rt) ? "" : String.format(" ~%.0f min", rt),
                m.brownouts > 0 ? "  RESETS " + m.brownouts : ""), 12, ty + 112);

        if (m.outOfArena) {
            String msg = m.tipped ? "Robot tipped over in a bend - press Restart (F6)" : "Robot left the arena - press Restart (F6)";
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 15));
            FontMetrics fm = g.getFontMetrics();
            int mw = fm.stringWidth(msg) + 24, mx = (getWidth() - mw) / 2, my = getHeight() / 2 - 18;
            g.setColor(new Color(170, 20, 20, 225));
            g.fillRoundRect(mx, my, mw, 32, 12, 12);
            g.setColor(Color.WHITE);
            g.drawString(msg, mx + 12, my + 21);
        }
        if (toastText != null && System.currentTimeMillis() < toastUntil) {
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            int mw = fm.stringWidth(toastText) + 24, mx = (getWidth() - mw) / 2, my = getHeight() - 60;
            g.setColor(new Color(30, 120, 60, 230));
            g.fillRoundRect(mx, my, mw, 28, 12, 12);
            g.setColor(Color.WHITE);
            g.drawString(toastText, mx + 12, my + 19);
        }
        if (!m.outOfArena && !m.running) {
            String msg = "Paused - F5 or Run to continue";
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
            FontMetrics fm = g.getFontMetrics();
            int mw = fm.stringWidth(msg) + 20, mx = getWidth() - mw - 8;
            g.setColor(hudBox(215));
            g.fillRoundRect(mx, 8, mw, 24, 10, 10);
            g.setColor(Theme.text());
            g.drawString(msg, mx + 10, 25);
        }
    }

    private static Color hudBox(int alpha) {
        return Theme.isDark() ? new Color(30, 31, 34, alpha) : new Color(255, 255, 255, alpha);
    }
}
