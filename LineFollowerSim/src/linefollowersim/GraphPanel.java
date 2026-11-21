package linefollowersim;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.Path2D;

/** Error, P/I/D terms and wheel speeds for the last 10 s. */
public class GraphPanel extends JPanel {

    final Model m;

    GraphPanel(Model m) {
        this.m = m;
        setPreferredSize(new Dimension(Model.W, 170));
    }

    @Override
    public void updateUI() {
        super.updateUI();
        setBackground(Theme.bg());
    }

    @Override
    protected void paintComponent(Graphics g0) {
        super.paintComponent(g0);
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        int gap = 6, w = (getWidth() - 4 * gap) / 3, h = getHeight() - 2 * gap;
        plot(g, gap, gap, w, h, "Error (mm)", new int[]{0},
                new Color[]{new Color(200, 40, 40)}, new String[]{"error"}, true, 20);
        plot(g, 2 * gap + w, gap, w, h, "PID terms", new int[]{1, 2, 3, 4},
                new Color[]{new Color(220, 120, 0), new Color(0, 150, 60), new Color(150, 60, 200), Theme.isDark() ? new Color(230, 230, 230) : new Color(30, 30, 30)},
                new String[]{"P", "I", "D", "out"}, true, 40);
        plot(g, 3 * gap + 2 * w, gap, w, h, "Wheel speed (mm/s)", new int[]{5, 6},
                new Color[]{new Color(0, 110, 210), new Color(230, 130, 0)}, new String[]{"left", "right"}, false, 100);
        g.dispose();
    }

    private void plot(Graphics2D g, int x, int y, int w, int h, String title, int[] ser, Color[] cols,
            String[] names, boolean symmetric, double minRange) {
        g.setColor(Theme.plotBg());
        g.fillRect(x, y, w, h);
        g.setColor(Theme.plotBorder());
        g.drawRect(x, y, w, h);
        int n = m.hCount;
        double lo = 0, hi = 0;
        for (int s : ser) {
            for (int j = 0; j < n; j++) {
                double v = m.hist[s][j];
                if (v < lo) {
                    lo = v;
                }
                if (v > hi) {
                    hi = v;
                }
            }
        }
        if (symmetric) {
            double a = Math.max(-lo, hi);
            lo = -a;
            hi = a;
        }
        if (hi - lo < minRange) {
            if (symmetric) {
                lo = -minRange / 2;
                hi = minRange / 2;
            } else {
                hi = lo + minRange;
            }
        }
        double pad = (hi - lo) * 0.08;
        lo -= pad;
        hi += pad;
        int y0 = (int) Math.round(y + h - (0 - lo) / (hi - lo) * h);
        g.setColor(Theme.zeroLine());
        g.drawLine(x, y0, x + w, y0);

        Shape oldClip = g.getClip();
        g.clipRect(x, y, w, h);
        double dxp = w / (double) (Model.HIST - 1);
        int oldest = (m.hHead - n + Model.HIST) % Model.HIST;
        g.setStroke(new BasicStroke(1.4f));
        for (int k = 0; k < ser.length; k++) {
            Path2D.Double path = new Path2D.Double();
            for (int j = 0; j < n; j++) {
                double v = m.hist[ser[k]][(oldest + j) % Model.HIST];
                double px = x + w - (n - 1 - j) * dxp, py = y + h - (v - lo) / (hi - lo) * h;
                if (j == 0) {
                    path.moveTo(px, py);
                } else {
                    path.lineTo(px, py);
                }
            }
            g.setColor(cols[k]);
            g.draw(path);
        }
        g.setClip(oldClip);

        g.setColor(Theme.text());
        g.drawString(title + "  (10 s)", x + 5, y + 13);
        FontMetrics fm = g.getFontMetrics();
        int lx = x + 5 + fm.stringWidth(title + "  (10 s)") + 10;
        for (int k = 0; k < names.length; k++) {
            g.setColor(cols[k]);
            g.drawString(names[k], lx, y + 13);
            lx += fm.stringWidth(names[k]) + 8;
        }
        g.setColor(Theme.textDim());
        String hs = String.format("%.0f", hi), ls = String.format("%.0f", lo);
        g.drawString(hs, x + w - fm.stringWidth(hs) - 3, y + 26);
        g.drawString(ls, x + w - fm.stringWidth(ls) - 3, y + h - 4);
    }
}
