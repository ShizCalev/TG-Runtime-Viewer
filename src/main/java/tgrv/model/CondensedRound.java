package tgrv.model;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

public final class CondensedRound {
    public final String serverName;
    public final int roundId;
    public final LocalDate date;
    public final List<RuntimeEntry> entries;
    public volatile boolean isFinal;

    public CondensedRound(String serverName, int roundId, LocalDate date, List<RuntimeEntry> entries, boolean isFinal) {
        this.serverName = serverName;
        this.roundId = roundId;
        this.date = date;
        this.entries = entries == null ? Collections.emptyList() : entries;
        this.isFinal = isFinal;
    }

    public String key() {
        return serverName + "#" + roundId;
    }
}
