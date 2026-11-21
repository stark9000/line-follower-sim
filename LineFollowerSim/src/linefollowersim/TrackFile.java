package linefollowersim;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Track files (.lftrack): plain text, one line per item.
 *
 *   LineFollowerSim-track 1
 *   arena 2000 1000
 *   robot 100 240 0.0
 *   shape LINE w=20.0 dash=0.0 ... pts=40.0,240.0;760.0,240.0
 *   patch 400 240 0.0 200 80 fade 0.8      (centre x y, angle, length, width, fade|slip, amount)
 *   gate 100 200 100 280                   (start / finish line set by hand: two end points)
 *
 * Older readers skip lines they do not know, so files with patches still open in older versions.
 */
public final class TrackFile {

    static final String HEADER = "LineFollowerSim-track 1";

    private TrackFile() {
    }

    public static void save(File f, Model m) throws IOException {
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
            w.println(HEADER);
            writeTrack(w, m);
        }
    }

    /** The picture as base64 PNG (pictures over 2000 px are scaled down first). */
    static String embedded(java.awt.image.BufferedImage img) {
        try {
            java.awt.image.BufferedImage im = img;
            int mx = Math.max(img.getWidth(), img.getHeight());
            if (mx > 2000) {
                double k = 2000.0 / mx;
                im = new java.awt.image.BufferedImage((int) (img.getWidth() * k), (int) (img.getHeight() * k),
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                java.awt.Graphics2D g = im.createGraphics();
                g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, im.getWidth(), im.getHeight(), null);
                g.dispose();
            }
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(im, "png", bo);
            return java.util.Base64.getEncoder().encodeToString(bo.toByteArray());
        } catch (IOException ex) {
            return null;
        }
    }

    static java.awt.image.BufferedImage decode(String b64) {
        try {
            return javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(java.util.Base64.getDecoder().decode(b64)));
        } catch (IOException | IllegalArgumentException ex) {
            return null;
        }
    }

    /** The track lines (arena, robot, background, shapes, patches) - also used inside workspace files. */
    static void writeTrack(PrintWriter w, Model m) {
        w.println("arena " + m.arenaW + " " + m.arenaH);
        w.println("robot " + m.sx + " " + m.sy + " " + m.sth);
        if (m.bgImage != null && m.bgPath != null) {
            w.println("background " + m.bgX + " " + m.bgY + " " + m.bgW + " " + m.bgAlpha + " " + (m.bgVisible ? 1 : 0) + " " + m.bgPath);
            if (m.bgEmbed) {                                 // the picture itself, so the file still works on another computer
                String d = embedded(m.bgImage);
                if (d != null) {
                    w.println("bgdata " + d);
                }
            }
        }
        for (TrackShape s : m.shapes) {
            w.println(s.serialize());
        }
        if (m.gateSet) {
            w.println("gate " + m.gx1 + " " + m.gy1 + " " + m.gx2 + " " + m.gy2);
        }
        for (Model.Patch p : m.patches) {
            w.println("patch " + p.cx + " " + p.cy + " " + p.ang + " " + (2 * p.hl) + " " + (2 * p.hw) + " "
                    + (p.slip ? "slip" : "fade") + " " + p.amount);
        }
    }

    /** Read a track file and make it the current track. Nothing changes if the file is damaged. */
    public static void load(File f, Model m) throws IOException {
        List<String> lines = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String first = r.readLine();
            if (first == null || !first.startsWith("LineFollowerSim-track")) {
                throw new IOException("This is not a LineFollowerSim track file.");
            }
            String line;
            while ((line = r.readLine()) != null) {
                lines.add(line);
            }
        }
        readTrack(lines, m);
    }

    /** Make the track lines the current track (lines it does not know are skipped). Nothing changes on an error. */
    static void readTrack(List<String> all, Model m) throws IOException {
        double aw = 0, ah = 0;
        double[] pose = null;
        String[] bg = null;                                  // x y w alpha visible path
        List<TrackShape> shapes = new ArrayList<>();
        List<Model.Patch> patches = new ArrayList<>();
        double[] gate = null;
        String bgData = null;
        for (String line : all) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] t = line.split("\\s+");
            try {
                if (t[0].equals("arena")) {
                    aw = Double.parseDouble(t[1]);
                    ah = Double.parseDouble(t[2]);
                } else if (t[0].equals("robot")) {
                    pose = new double[]{Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3])};
                } else if (t[0].equals("background")) {
                    bg = line.split("\\s+", 7);
                    Double.parseDouble(bg[1]);
                } else if (t[0].equals("shape")) {
                    shapes.add(TrackShape.parse(line));
                } else if (t[0].equals("bgdata")) {
                    bgData = t.length > 1 ? t[1] : null;
                } else if (t[0].equals("gate")) {
                    gate = new double[]{Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3]), Double.parseDouble(t[4])};
                } else if (t[0].equals("patch")) {
                    boolean slip = t[6].equals("slip");
                    if (!slip && !t[6].equals("fade")) {
                        throw new IllegalArgumentException(t[6]);
                    }
                    patches.add(new Model.Patch(Double.parseDouble(t[1]), Double.parseDouble(t[2]), Double.parseDouble(t[3]),
                            Double.parseDouble(t[4]), Double.parseDouble(t[5]), slip, Double.parseDouble(t[7])));
                }
            } catch (RuntimeException ex) {
                throw new IOException("Damaged line: " + line);
            }
        }
        if (aw < 200 || ah < 200) {
            throw new IOException("The file has no valid arena size.");
        }
        if (pose == null) {
            pose = new double[]{aw / 8, ah / 2, 0};
        }
        m.setTrack(aw, ah, shapes, pose);
        m.patches.addAll(patches);
        if (gate != null) {
            m.setGate(gate[0], gate[1], gate[2], gate[3]);
        }
        m.clearBackground();
        if (bg != null && bg.length >= 7) {                  // the picture is only linked: restore it if the file is still there
            try {
                File img = new File(bg[6]);
                java.awt.image.BufferedImage emb = bgData != null && !img.isFile() ? decode(bgData) : null;
                if (img.isFile() || emb != null) {
                    if (emb != null) {
                        m.useBackground(emb, bg[6]);
                    } else {
                        m.loadBackground(img);
                    }
                    m.bgX = Double.parseDouble(bg[1]);
                    m.bgY = Double.parseDouble(bg[2]);
                    m.bgW = Double.parseDouble(bg[3]);
                    m.bgAlpha = Double.parseDouble(bg[4]);
                    m.bgVisible = bg[5].equals("1");
                }
            } catch (IOException | RuntimeException ex) {
                m.clearBackground();
            }
        }
    }
}
