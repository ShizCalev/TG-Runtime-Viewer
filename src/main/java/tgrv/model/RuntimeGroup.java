package tgrv.model;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

public final class RuntimeGroup {
    public final String message;
    public final String procName;
    public final String sourceFile;
    public String exampleSrc;
    public String exampleUsr;

    private final Map<String, Occurrence> byRound = new LinkedHashMap<>();
    private final TreeSet<String> servers = new TreeSet<>();

    private static final char SEP = 0x01;

    public String key() {
        return message + SEP + procName + SEP + sourceFile;
    }

    public RuntimeGroup(String message, String procName, String sourceFile) {
        this.message = message;
        this.procName = procName;
        this.sourceFile = sourceFile;
    }

    public void add(String serverName, int roundId, LocalDate date, RuntimeEntry entry) {
        String occKey = serverName + "#" + roundId;
        byRound.merge(occKey, new Occurrence(serverName, roundId, date, entry.count),
                (a, b) -> new Occurrence(a.serverName, a.roundId, a.date, a.count + b.count));
        servers.add(serverName);
        if (exampleSrc == null && entry.src != null && !entry.src.isEmpty()) {
            exampleSrc = entry.src;
        }
        if (exampleUsr == null && entry.usr != null && !entry.usr.isEmpty() && !"null".equals(entry.usr)) {
            exampleUsr = entry.usr;
        }
    }

    public long totalCount() {
        long total = 0;
        for (Occurrence o : byRound.values()) {
            total += o.count;
        }
        return total;
    }

    public int roundCount() {
        return byRound.size();
    }

    public TreeSet<String> servers() {
        return servers;
    }

    public String serversLabel() {
        return String.join(", ", servers);
    }

    public String serversShortLabel() {
        StringBuilder sb = new StringBuilder();
        for (String s : servers) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(s.isEmpty() ? '?' : Character.toUpperCase(s.charAt(0)));
        }
        return sb.toString();
    }

    public LocalDate firstSeen() {
        LocalDate min = null;
        for (Occurrence o : byRound.values()) {
            if (min == null || o.date.isBefore(min)) {
                min = o.date;
            }
        }
        return min;
    }

    public LocalDate lastSeen() {
        LocalDate max = null;
        for (Occurrence o : byRound.values()) {
            if (max == null || o.date.isAfter(max)) {
                max = o.date;
            }
        }
        return max;
    }

    public List<Occurrence> occurrences() {
        List<Occurrence> list = new ArrayList<>(byRound.values());
        list.sort(Comparator.<Occurrence, String>comparing(o -> o.serverName).thenComparingInt(o -> o.roundId));
        return list;
    }

    public static final class Occurrence {
        public final String serverName;
        public final int roundId;
        public final LocalDate date;
        public final long count;

        public Occurrence(String serverName, int roundId, LocalDate date, long count) {
            this.serverName = serverName;
            this.roundId = roundId;
            this.date = date;
            this.count = count;
        }
    }
}
