package tgrv.store;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class SettingsStore {

    private static final int CURRENT_VERSION = 2;
    private static final String VERSION_PREFIX = "version=";
    private static final String LEGACY_FILENAME = "group-status.tsv";
    private static final String SETTINGS_FILENAME = "tgrv.settings";
    private static final String COL_PREFIX = "COL";
    private static final String GRP_PREFIX = "GRP";
    private static final String CHK_PREFIX = "CHK";
    private static final String WIN_PREFIX = "WIN";
    private static final String SPL_PREFIX = "SPL";
    private static final String SCL_PREFIX = "SCL";

    private final Path file;
    private final Path legacyFile;
    private final Map<String, GroupStatus> statuses = new ConcurrentHashMap<>();
    private final Map<Integer, ColumnLayout> columns = new ConcurrentHashMap<>();
    private volatile Instant lastCheckTime;
    private volatile WindowBounds windowBounds;
    private volatile Integer splitDividerLocation;
    private volatile Integer uiScalePercent;

    public static final class WindowBounds {
        public final int x;
        public final int y;
        public final int width;
        public final int height;
        public final boolean maximized;

        public WindowBounds(int x, int y, int width, int height, boolean maximized) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
            this.maximized = maximized;
        }
    }

    public SettingsStore(Path cacheRoot) {
        this.file = cacheRoot.resolve(SETTINGS_FILENAME);
        this.legacyFile = cacheRoot.resolve(LEGACY_FILENAME);
    }

    public static final class ColumnLayout {
        public final int width;
        public final boolean visible;

        public ColumnLayout(int width, boolean visible) {
            this.width = width;
            this.visible = visible;
        }
    }

    public synchronized void load() {
        statuses.clear();
        columns.clear();
        lastCheckTime = null;
        windowBounds = null;
        splitDividerLocation = null;
        uiScalePercent = null;
        if (Files.exists(file)) {
            loadCurrent(file);
        } else if (Files.exists(legacyFile)) {
            loadBareGroupLines(legacyFile);
            save();
        }
    }

    private void loadCurrent(Path source) {
        try (BufferedReader r = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String line = r.readLine();
            if (line == null) {
                return;
            }
            Integer version = parseVersion(line);
            if (version == null) {
                return;
            }
            if (version == 1) {
                String l;
                while ((l = r.readLine()) != null) {
                    parseGroupLine(l, false);
                }
                save();
                return;
            }
            if (version != CURRENT_VERSION) {
                return;
            }
            String l;
            while ((l = r.readLine()) != null) {
                if (l.startsWith(COL_PREFIX + "\t")) {
                    parseColumnLine(l);
                } else if (l.startsWith(GRP_PREFIX + "\t")) {
                    parseGroupLine(l, true);
                } else if (l.startsWith(CHK_PREFIX + "\t")) {
                    parseCheckTimeLine(l);
                } else if (l.startsWith(WIN_PREFIX + "\t")) {
                    parseWindowBoundsLine(l);
                } else if (l.startsWith(SPL_PREFIX + "\t")) {
                    parseSplitDividerLine(l);
                } else if (l.startsWith(SCL_PREFIX + "\t")) {
                    parseUiScaleLine(l);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void loadBareGroupLines(Path source) {
        try (BufferedReader r = Files.newBufferedReader(source, StandardCharsets.UTF_8)) {
            String l;
            while ((l = r.readLine()) != null) {
                parseGroupLine(l, false);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Integer parseVersion(String line) {
        if (!line.startsWith(VERSION_PREFIX)) {
            return null;
        }
        try {
            return Integer.parseInt(line.substring(VERSION_PREFIX.length()).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void parseColumnLine(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length < 4) {
            return;
        }
        try {
            int index = Integer.parseInt(parts[1]);
            int width = Integer.parseInt(parts[2]);
            boolean visible = "1".equals(parts[3]);
            columns.put(index, new ColumnLayout(width, visible));
        } catch (NumberFormatException ignored) {
        }
    }

    private void parseCheckTimeLine(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length < 2) {
            return;
        }
        try {
            lastCheckTime = Instant.ofEpochMilli(Long.parseLong(parts[1]));
        } catch (NumberFormatException ignored) {
        }
    }

    private void parseWindowBoundsLine(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length < 6) {
            return;
        }
        try {
            int x = Integer.parseInt(parts[1]);
            int y = Integer.parseInt(parts[2]);
            int width = Integer.parseInt(parts[3]);
            int height = Integer.parseInt(parts[4]);
            boolean maximized = "1".equals(parts[5]);
            windowBounds = new WindowBounds(x, y, width, height, maximized);
        } catch (NumberFormatException ignored) {
        }
    }

    private void parseSplitDividerLine(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length < 2) {
            return;
        }
        try {
            splitDividerLocation = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
        }
    }

    private void parseUiScaleLine(String line) {
        String[] parts = line.split("\t", -1);
        if (parts.length < 2) {
            return;
        }
        try {
            uiScalePercent = Integer.parseInt(parts[1]);
        } catch (NumberFormatException ignored) {
        }
    }

    private void parseGroupLine(String line, boolean prefixed) {
        if (line.isEmpty()) {
            return;
        }
        String[] parts = line.split("\t", -1);
        int base = prefixed ? 1 : 0;
        if (parts.length < base + 3) {
            return;
        }
        String key = TabCodec.unesc(parts[base]);
        boolean hidden = "1".equals(parts[base + 1]);
        boolean fixed = "1".equals(parts[base + 2]);
        if (hidden || fixed) {
            statuses.put(key, new GroupStatus(hidden, fixed));
        }
    }

    public GroupStatus get(String key) {
        return statuses.getOrDefault(key, GroupStatus.NONE);
    }

    public synchronized void setHidden(String key, boolean hidden) {
        update(key, hidden, get(key).fixed);
    }

    public synchronized void setFixed(String key, boolean fixed) {
        update(key, get(key).hidden, fixed);
    }

    private void update(String key, boolean hidden, boolean fixed) {
        if (!hidden && !fixed) {
            statuses.remove(key);
        } else {
            statuses.put(key, new GroupStatus(hidden, fixed));
        }
        save();
    }

    public ColumnLayout getColumnLayout(int index) {
        return columns.get(index);
    }

    public synchronized void setColumnLayouts(Map<Integer, ColumnLayout> newLayouts) {
        columns.clear();
        columns.putAll(newLayouts);
        save();
    }

    public Instant getLastCheckTime() {
        return lastCheckTime;
    }

    public synchronized void setLastCheckTime(Instant time) {
        this.lastCheckTime = time;
        save();
    }

    public WindowBounds getWindowBounds() {
        return windowBounds;
    }

    public synchronized void setWindowBounds(int x, int y, int width, int height, boolean maximized) {
        this.windowBounds = new WindowBounds(x, y, width, height, maximized);
        save();
    }

    public Integer getSplitDividerLocation() {
        return splitDividerLocation;
    }

    public synchronized void setSplitDividerLocation(int location) {
        this.splitDividerLocation = location;
        save();
    }

    public Integer getUiScalePercent() {
        return uiScalePercent;
    }

    public synchronized void setUiScalePercent(int percent) {
        this.uiScalePercent = percent;
        save();
    }

    private void save() {
        try {
            Path dir = file.getParent();
            Files.createDirectories(dir);
            Path tmp = dir.resolve(file.getFileName().toString() + ".tmp");
            try (BufferedWriter w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                w.write(VERSION_PREFIX + CURRENT_VERSION);
                w.newLine();
                if (lastCheckTime != null) {
                    w.write(CHK_PREFIX);
                    w.write('\t');
                    w.write(String.valueOf(lastCheckTime.toEpochMilli()));
                    w.newLine();
                }
                if (windowBounds != null) {
                    w.write(WIN_PREFIX);
                    w.write('\t');
                    w.write(String.valueOf(windowBounds.x));
                    w.write('\t');
                    w.write(String.valueOf(windowBounds.y));
                    w.write('\t');
                    w.write(String.valueOf(windowBounds.width));
                    w.write('\t');
                    w.write(String.valueOf(windowBounds.height));
                    w.write('\t');
                    w.write(windowBounds.maximized ? "1" : "0");
                    w.newLine();
                }
                if (splitDividerLocation != null) {
                    w.write(SPL_PREFIX);
                    w.write('\t');
                    w.write(String.valueOf(splitDividerLocation));
                    w.newLine();
                }
                if (uiScalePercent != null) {
                    w.write(SCL_PREFIX);
                    w.write('\t');
                    w.write(String.valueOf(uiScalePercent));
                    w.newLine();
                }
                for (Map.Entry<Integer, ColumnLayout> e : columns.entrySet()) {
                    w.write(COL_PREFIX);
                    w.write('\t');
                    w.write(String.valueOf(e.getKey()));
                    w.write('\t');
                    w.write(String.valueOf(e.getValue().width));
                    w.write('\t');
                    w.write(e.getValue().visible ? "1" : "0");
                    w.newLine();
                }
                for (Map.Entry<String, GroupStatus> e : statuses.entrySet()) {
                    w.write(GRP_PREFIX);
                    w.write('\t');
                    w.write(TabCodec.esc(e.getKey()));
                    w.write('\t');
                    w.write(e.getValue().hidden ? "1" : "0");
                    w.write('\t');
                    w.write(e.getValue().fixed ? "1" : "0");
                    w.newLine();
                }
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
