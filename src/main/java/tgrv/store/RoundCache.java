package tgrv.store;

import tgrv.model.CondensedRound;
import tgrv.model.RuntimeEntry;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class RoundCache {

    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private final Path root;
    private final Map<String, CondensedRound> byKey = new ConcurrentHashMap<>();

    public RoundCache(Path root) {
        this.root = root;
    }

    public Path root() {
        return root;
    }

    public synchronized void load() {
        byKey.clear();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> serverDirs = Files.newDirectoryStream(root)) {
            for (Path serverDir : serverDirs) {
                if (!Files.isDirectory(serverDir)) {
                    continue;
                }
                String serverName = serverDir.getFileName().toString();
                try (DirectoryStream<Path> days = Files.newDirectoryStream(serverDir)) {
                    for (Path dayDir : days) {
                        if (!Files.isDirectory(dayDir)) {
                            continue;
                        }
                        LocalDate date;
                        try {
                            date = LocalDate.parse(dayDir.getFileName().toString(), DAY_FMT);
                        } catch (Exception e) {
                            continue;
                        }
                        try (DirectoryStream<Path> files = Files.newDirectoryStream(dayDir, "*.trv")) {
                            for (Path f : files) {
                                CondensedRound rc = readFile(f, serverName, date);
                                if (rc != null) {
                                    byKey.put(rc.key(), rc);
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public CondensedRound get(String serverName, int roundId) {
        return byKey.get(serverName + "#" + roundId);
    }

    public List<CondensedRound> all() {
        return new ArrayList<>(byKey.values());
    }

    public synchronized void put(CondensedRound rc) {
        byKey.put(rc.key(), rc);
        try {
            writeFile(rc);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public synchronized void markDayListed(String serverName, LocalDate day) {
        try {
            Files.createDirectories(dayDir(serverName, day));
            Files.writeString(listedMarkerFile(serverName, day), "");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public boolean isDayListed(String serverName, LocalDate day) {
        return Files.exists(listedMarkerFile(serverName, day));
    }

    public Path rawLogFile(String serverName, LocalDate day, int roundId) {
        return dayDir(serverName, day).resolve(roundId + ".log");
    }

    public boolean hasRawLog(String serverName, LocalDate day, int roundId) {
        return Files.exists(rawLogFile(serverName, day, roundId));
    }

    public synchronized void writeRawLog(String serverName, LocalDate day, int roundId, String text) {
        try {
            Path file = rawLogFile(serverName, day, roundId);
            Files.createDirectories(file.getParent());
            Files.writeString(file, text, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public String readRawLog(String serverName, LocalDate day, int roundId) {
        Path file = rawLogFile(serverName, day, roundId);
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Path dayDir(String serverName, LocalDate day) {
        return root.resolve(serverName).resolve(DAY_FMT.format(day));
    }

    private Path listedMarkerFile(String serverName, LocalDate day) {
        return dayDir(serverName, day).resolve(".listed");
    }

    public synchronized void pruneOlderThan(LocalDate cutoff) {
        List<String> stale = byKey.values().stream()
                .filter(rc -> rc.date.isBefore(cutoff))
                .map(CondensedRound::key)
                .collect(Collectors.toList());
        for (String key : stale) {
            byKey.remove(key);
        }
        if (!Files.isDirectory(root)) {
            return;
        }
        try (DirectoryStream<Path> serverDirs = Files.newDirectoryStream(root)) {
            for (Path serverDir : serverDirs) {
                if (!Files.isDirectory(serverDir)) {
                    continue;
                }
                try (DirectoryStream<Path> days = Files.newDirectoryStream(serverDir)) {
                    for (Path dayDir : days) {
                        if (!Files.isDirectory(dayDir)) {
                            continue;
                        }
                        LocalDate date;
                        try {
                            date = LocalDate.parse(dayDir.getFileName().toString(), DAY_FMT);
                        } catch (Exception e) {
                            continue;
                        }
                        if (date.isBefore(cutoff)) {
                            deleteRecursive(dayDir);
                        }
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void deleteRecursive(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path p : stream) {
                if (Files.isDirectory(p)) {
                    deleteRecursive(p);
                } else {
                    Files.deleteIfExists(p);
                }
            }
        }
        Files.deleteIfExists(dir);
    }

    private Path fileFor(CondensedRound rc) {
        return dayDir(rc.serverName, rc.date).resolve(rc.roundId + ".trv");
    }

    private void writeFile(CondensedRound rc) throws IOException {
        Path file = fileFor(rc);
        Files.createDirectories(file.getParent());
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("FINAL=" + rc.isFinal);
            w.newLine();
            for (RuntimeEntry e : rc.entries) {
                w.write(esc(e.message));
                w.write('\t');
                w.write(esc(e.procName));
                w.write('\t');
                w.write(esc(e.sourceFile));
                w.write('\t');
                w.write(esc(e.src));
                w.write('\t');
                w.write(esc(e.srcLoc));
                w.write('\t');
                w.write(esc(e.usr));
                w.write('\t');
                w.write(Long.toString(e.count));
                w.newLine();
            }
        }
    }

    private CondensedRound readFile(Path file, String serverName, LocalDate date) {
        String name = file.getFileName().toString();
        int roundId;
        try {
            roundId = Integer.parseInt(name.substring(0, name.length() - ".trv".length()));
        } catch (NumberFormatException e) {
            return null;
        }
        List<RuntimeEntry> entries = new ArrayList<>();
        boolean isFinal = false;
        try (BufferedReader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header != null && header.startsWith("FINAL=")) {
                isFinal = Boolean.parseBoolean(header.substring("FINAL=".length()));
            }
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    continue;
                }
                String[] parts = line.split("\t", -1);
                if (parts.length < 7) {
                    continue;
                }
                entries.add(new RuntimeEntry(
                        unesc(parts[0]), unesc(parts[1]), unesc(parts[2]),
                        unesc(parts[3]), unesc(parts[4]), unesc(parts[5]),
                        Long.parseLong(parts[6])
                ));
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return new CondensedRound(serverName, roundId, date, entries, isFinal);
    }

    private static String esc(String s) {
        return TabCodec.esc(s);
    }

    private static String unesc(String s) {
        return TabCodec.unesc(s);
    }
}
