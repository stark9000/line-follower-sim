package linefollowersim;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;

/** A small line plot: several series, axes with tick labels, optional equal scaling (for paths). */
final class PlotPanel extends JPanel {

    static final class Series {

        final double[] x, y;
        final Color color;
        final String name;
        final float width;
        final boolean dashed;

        Series(double[] x, double[] y, Color color, String name, float width, boolean dashed) {
            this.x = x;
            this.y = y;
            this.color = color;
            this.name = name;
            this.width = width;
            this.dashed = dashed;
        }
    }

    private final List<Series> series = new ArrayList<>();
    private String title = "", xLabel = "", yLabel = "";
    private boolean equalAspect, invertY;
    private String emptyText = "no data";

    void setEmptyText(String t) {
        emptyText = t;
        repaint();
    }

    PlotPanel(int w, int h) {
        setPreferredSize(new Dimension(w, h));
        setMinimumSize(new Dimension(w, h));
        setMaximumSize(new Dimension(Integer.MAX_VALUE, h));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setBackground(Theme.plotBg());
        setBorder(BorderFactory.createLineBorder(Theme.plotBorder()));
    }

    void setup(String title, String xLabel, String yLabel, boolean equalAspect, boolean invertY) {
        this.title = title;
        this.xLabel = xLabel;
        this.yLabel = yLabel;
        this.equalAspect = equalAspect;
        this.invertY = invertY;
    }

    void clear() {
        series.clear();
        repaint();
    }

    void add(double[] x, double[] y, Color c, String name, float width, boolean dashed) {
        series.add(new Series(x, y, c, name, width, dashed));
        repaint();
    }

    private static double niceStep(double range) {
        double raw = range / 4, mag = Math.pow(10, Math.floor(Math.log10(Math.max(raw, 1e-12))));
        double f = raw / mag;
        return (f < 1.5 ? 1 : f < 3.5 ? 2 : f < 7.5 ? 5 : 10) * mag;
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 10));
        int w = getWidth(), h = getHeight(), l = 38, r = 8, t = 16, b = 24;
        double x0 = 1e18, x1 = -1e18, y0 = 1e18, y1 = -1e18;
        for (Series s : series) {
            for (int i = 0; i < s.x.length; i++) {
                if (!Double.isNaN(s.y[i]) && !Double.isNaN(s.x[i])) {
                    x0 = Math.min(x0, s.x[i]);
                    x1 = Math.max(x1, s.x[i]);
                    y0 = Math.min(y0, s.y[i]);
                    y1 = Math.max(y1, s.y[i]);
                }
            }
        }
        g.setColor(Theme.text());
        g.drawString(title, l, 11);
        if (x0 > x1) {
            String msg = emptyText;
            g.setColor(Theme.textDim());
            g.drawString(msg, (w - g.getFontMetrics().stringWidth(msg)) / 2, h / 2);
            g.dispose();
            return;
        }
        if (x1 - x0 < 1e-9) {
            x1 = x0 + 1;
        }
        if (y1 - y0 < 1e-9) {
            y1 = y0 + 1;
        }
        double pad = (y1 - y0) * 0.06;
        y0 -= pad;
        y1 += pad;
        int pw = w - l - r, ph = h - t - b;
        double sx = pw / (x1 - x0), sy = ph / (y1 - y0);
        if (equalAspect) {
            double s = Math.min(sx, sy);
            sx = s;
            sy = s;
        }
        final double fx0 = x0, fy0 = y0, fsx = sx, fsy = sy;
        final int ph2 = ph;
        java.util.function.DoubleUnaryOperator px = v -> l + (v - fx0) * fsx;
        java.util.function.DoubleUnaryOperator py = v -> invertY ? t + (v - fy0) * fsy : t + ph2 - (v - fy0) * fsy;
        // axes and ticks
        g.setColor(Theme.gridLine());
        double stepX = niceStep(x1 - x0), stepY = niceStep(y1 - y0);
        for (double v = Math.ceil(x0 / stepX) * stepX; v <= x1; v += stepX) {
            int X = (int) px.applyAsDouble(v);
            g.drawLine(X, t, X, t + ph);
        }
        for (double v = Math.ceil(y0 / stepY) * stepY; v <= y1; v += stepY) {
            int Y = (int) py.applyAsDouble(v);
            g.drawLine(l, Y, l + pw, Y);
        }
        g.setColor(Theme.plotBorder());
        g.drawRect(l, t, pw, ph);
        g.setColor(Theme.text());
        for (double v = Math.ceil(x0 / stepX) * stepX; v <= x1; v += stepX) {
            String s = fmt(v);
            g.drawString(s, (int) px.applyAsDouble(v) - g.getFontMetrics().stringWidth(s) / 2, t + ph + 12);
        }
        for (double v = Math.ceil(y0 / stepY) * stepY; v <= y1; v += stepY) {
            String s = fmt(v);
            g.drawString(s, l - 4 - g.getFontMetrics().stringWidth(s), (int) py.applyAsDouble(v) + 4);
        }
        g.drawString(xLabel, l + pw - g.getFontMetrics().stringWidth(xLabel), h - 3);
        g.drawString(yLabel, 2, 11 + 0);
        Shape clip = g.getClip();
        g.clipRect(l, t, pw, ph);
        int lx = l + 6, ly = t + 12;
        for (Series s : series) {
            Path2D.Double p = new Path2D.Double();
            boolean pen = false;
            for (int i = 0; i < s.x.length; i++) {
                if (Double.isNaN(s.y[i])) {
                    pen = false;
                    continue;
                }
                double X = px.applyAsDouble(s.x[i]), Y = py.applyAsDouble(s.y[i]);
                if (!pen) {
                    p.moveTo(X, Y);
                    pen = true;
                } else {
                    p.lineTo(X, Y);
                }
            }
            g.setColor(s.color);
            g.setStroke(s.dashed ? new BasicStroke(s.width, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 10f, new float[]{4f, 4f}, 0f)
                    : new BasicStroke(s.width));
            g.draw(p);
        }
        g.setClip(clip);
        g.setStroke(new BasicStroke(1f));
        int named = 0, legW = 0;
        for (Series s : series) {
            if (s.name != null && !s.name.isEmpty()) {
                named++;
                legW = Math.max(legW, g.getFontMetrics().stringWidth(s.name));
            }
        }
        if (named > 0) {                                                  // legend box, so curves do not run through the text
            Color pb = Theme.plotBg();
            g.setColor(new Color(pb.getRed(), pb.getGreen(), pb.getBlue(), 215));
            g.fillRoundRect(lx - 4, ly - 11, legW + 26, named * 11 + 5, 6, 6);
        }
        for (Series s : series) {                                         // legend
            if (s.name == null || s.name.isEmpty()) {
                continue;
            }
            g.setColor(s.color);
            g.drawLine(lx, ly - 3, lx + 12, ly - 3);
            g.setColor(Theme.text());
            g.drawString(s.name, lx + 16, ly);
            ly += 11;
        }
        g.dispose();
    }

    private static String fmt(double v) {
        return Math.abs(v) >= 100 || v == Math.rint(v) ? String.format("%.0f", v) : String.format("%.1f", v);
    }
}
