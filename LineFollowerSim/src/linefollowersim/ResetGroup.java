package linefollowersim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A set of Resettable controls. Everything added to a group is also added to
 * its parent, so one "master" group at the top resets every control, while a
 * per-tab group resets only that tab. The master group also knows every
 * Saveable control under a unique key ("Tab/Label"), for workspace files.
 */
public class ResetGroup implements Resettable {

    private final List<Resettable> items = new ArrayList<>();
    private final ResetGroup parent;
    private final String name;
    private final Map<String, Saveable> saveables = new LinkedHashMap<>();
    private final Map<String, Integer> seen = new HashMap<>();

    public ResetGroup(ResetGroup parent) {
        this(parent, "Top");
    }

    public ResetGroup(ResetGroup parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    public <T extends Resettable> T add(T item) {
        addNamed(item, name);
        return item;
    }

    private void addNamed(Resettable item, String prefix) {
        items.add(item);
        if (parent != null) {
            parent.addNamed(item, prefix);
        } else if (item instanceof Saveable) {                // the master: remember it under a unique key
            String key = prefix + "/" + ((Saveable) item).label();
            int n = seen.merge(key, 1, Integer::sum);
            saveables.put(n == 1 ? key : key + " #" + n, (Saveable) item);
        }
    }

    /** Every saveable control, in the order they were created (the order to restore them in). */
    Map<String, Saveable> saveables() {
        return saveables;
    }

    /** Register a plain action (e.g. "restore mismatch seed") as part of the reset. */
    public void addAction(Runnable r) {
        add((Resettable) r::run);
    }

    @Override
    public void resetDefault() {
        for (Resettable r : new ArrayList<>(items)) {
            r.resetDefault();
        }
    }
}
