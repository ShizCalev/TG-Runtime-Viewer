package tgrv;

import tgrv.model.LogServer;
import tgrv.net.LogHttpClient;
import tgrv.service.SyncService;
import tgrv.store.SettingsStore;
import tgrv.store.RoundCache;
import tgrv.ui.MainWindow;

import javax.swing.*;
import javax.swing.plaf.FontUIResource;
import java.awt.*;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class Main {

    public static void main(String[] args) {
        Path cacheDir = args.length > 0 ? Paths.get(args[0]) : defaultCacheDir();
        List<LogServer> servers = LogServer.ALL;

        RoundCache cache = new RoundCache(cacheDir);
        SettingsStore settingsStore = new SettingsStore(cacheDir);
        LogHttpClient client = new LogHttpClient();
        SyncService syncService = new SyncService(cache, client, servers);

        SwingUtilities.invokeLater(() -> {
            settingsStore.load();
            applyDarkTheme(uiScalePercent(settingsStore));
            cache.load();

            MainWindow window = new MainWindow(cache, syncService, settingsStore, servers);
            syncService.setListener(event -> {
                if (event.started) {
                    window.onSyncStarted();
                } else {
                    window.onSyncFinished(event);
                }
            });

            window.setVisible(true);
            window.refreshTable();
            syncService.start(initialSyncDelaySeconds(settingsStore));
        });
    }

    private static long initialSyncDelaySeconds(SettingsStore settingsStore) {
        Instant lastCheck = settingsStore.getLastCheckTime();
        if (lastCheck == null) {
            return 0;
        }
        long secondsSince = Instant.now().getEpochSecond() - lastCheck.getEpochSecond();
        long intervalSeconds = SyncService.POLL_INTERVAL_MINUTES * 60;
        return Math.max(0, Math.min(intervalSeconds, intervalSeconds - secondsSince));
    }

    private static int uiScalePercent(SettingsStore settingsStore) {
        Integer saved = settingsStore.getUiScalePercent();
        return saved != null ? saved : 100;
    }

    private static boolean fontDefaultsCaptured = false;

    private static void captureFontDefaults() {
        if (fontDefaultsCaptured) {
            return;
        }
        Map<Object, Font> fontDefaults = new HashMap<>();
        UIDefaults defaults = UIManager.getDefaults();
        for (Object key : defaults.keySet()) {
            Object value = defaults.get(key);
            if (value instanceof Font) {
                fontDefaults.put(key, (Font) value);
            }
        }
        UIManager.put("tgrv.fontDefaults", fontDefaults);
        fontDefaultsCaptured = true;
    }

    public static void applyFontScale(int percent) {
        Object stored = UIManager.get("tgrv.fontDefaults");
        if (!(stored instanceof Map)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<Object, Font> fontDefaults = (Map<Object, Font>) stored;
        for (Map.Entry<Object, Font> e : fontDefaults.entrySet()) {
            Font reference = e.getValue();
            float scaledSize = reference.getSize2D() * percent / 100f;
            UIManager.put(e.getKey(), new FontUIResource(reference.deriveFont(scaledSize)));
        }
    }

    private static Path defaultCacheDir() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null && !localAppData.isBlank()) {
            return Paths.get(localAppData, "TG Runtime Viewer");
        }
        return Paths.get(System.getProperty("user.home"), ".tgrv-cache");
    }

    private static void applyDarkTheme(int scalePercent) {
        installNimbusAndScale(scalePercent);
    }

    private static boolean nimbusInstalled = false;

    public static void installNimbusAndScale(int scalePercent) {
        if (!nimbusInstalled) {
            try {
                UIManager.setLookAndFeel(new javax.swing.plaf.nimbus.NimbusLookAndFeel());
            } catch (Exception ignored) {
                return;
            }
            nimbusInstalled = true;
        }
        captureFontDefaults();
        applyFontScale(scalePercent);
        applyDarkColors();
    }

    private static void applyDarkColors() {
        Color base = new Color(45, 48, 50);
        Color background = new Color(43, 43, 43);
        Color lightBackground = new Color(55, 58, 60);
        Color text = new Color(220, 220, 220);

        UIManager.put("control", background);
        UIManager.put("info", lightBackground);
        UIManager.put("nimbusBase", base);
        UIManager.put("nimbusAlertYellow", new Color(248, 187, 0));
        UIManager.put("nimbusDisabledText", new Color(128, 128, 128));
        UIManager.put("nimbusFocus", new Color(115, 164, 209));
        UIManager.put("nimbusGreen", new Color(176, 179, 50));
        UIManager.put("nimbusInfoBlue", new Color(66, 139, 221));
        UIManager.put("nimbusLightBackground", lightBackground);
        UIManager.put("nimbusOrange", new Color(191, 98, 4));
        UIManager.put("nimbusRed", new Color(169, 46, 34));
        UIManager.put("nimbusSelectedText", Color.WHITE);
        UIManager.put("nimbusSelectionBackground", new Color(63, 100, 148));
        UIManager.put("text", text);
        UIManager.put("textForeground", text);
        UIManager.put("Table.background", lightBackground);
        UIManager.put("Table.foreground", text);
    }
}
