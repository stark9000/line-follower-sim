package linefollowersim;

import java.io.*;
import java.util.*;

/**
 * Named presets saved by the user (robots, arenas). Stored as a small text
 * file, ~/.linefollowersim/presets.properties, which you can also edit or copy
 * by hand. Use -Dlinefollowersim.presets=PATH to keep it somewhere else.
 *
 * Each entry: key = "category|name", value = comma-separated numbers.
 */
final class UserPresets {

    static final String ROBOT = "robot", ARENA = "arena";

    private UserPresets() {
    }

    static File file() {
        String p = System.getProperty("linefollowersim.presets");
        if (p != null && !p.isEmpty()) {
            return new File(p);
        }
        return new File(new File(System.getProperty("user.home"), ".linefollowersim"), "presets.properties");
    }

    private static Properties load() {
        Properties pr = new Properties();
        File f = file();
        if (f.isFile()) {
            try (InputStream in = new FileInputStream(f)) {
                pr.load(in);
            } catch (IOException | IllegalArgumentException ex) {
                // unreadable or damaged file: behave as if there are no saved presets
            }
        }
        return pr;
    }

    private static void save(Properties pr) throws IOException {
        File f = file();
        File dir = f.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create folder " + dir);
        }
        try (OutputStream out = new FileOutputStream(f)) {
            pr.store(out, "LineFollowerSim saved presets (category|name = numbers)");
        }
    }

    /** Saved names of one category, sorted. */
    static List<String> names(String cat) {
        List<String> r = new ArrayList<>();
        String prefix = cat + "|";
        for (String k : load().stringPropertyNames()) {
            if (k.startsWith(prefix) && get(cat, k.substring(prefix.length())) != null) {
                r.add(k.substring(prefix.length()));
            }
        }
        Collections.sort(r, String.CASE_INSENSITIVE_ORDER);
        return r;
    }

    /** The numbers of a saved preset, or null if missing or damaged. */
    static double[] get(String cat, String name) {
        String v = load().getProperty(cat + "|" + name);
        if (v == null) {
            return null;
        }
        String[] parts = v.split(",");
        double[] d = new double[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) {
                d[i] = Double.parseDouble(parts[i].trim());
            }
        } catch (NumberFormatException ex) {
            return null;
        }
        return d;
    }

    static void put(String cat, String name, double[] values) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(Double.toString(values[i]));
        }
        Properties pr = load();
        pr.setProperty(cat + "|" + name, sb.toString());
        save(pr);
    }

    /** A program setting (e.g. the theme), stored in the same file as "ui|key". */
    static String setting(String key, String def) {
        return load().getProperty("ui|" + key, def);
    }

    static void setSetting(String key, String value) {
        Properties pr = load();
        pr.setProperty("ui|" + key, value);
        try {
            save(pr);
        } catch (IOException ex) {
            // not important enough to bother the user: the setting is simply not remembered
        }
    }

    static void delete(String cat, String name) throws IOException {
        Properties pr = load();
        pr.remove(cat + "|" + name);
        save(pr);
    }
}
