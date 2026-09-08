package tgrv.service;

import tgrv.model.CondensedRound;
import tgrv.model.RuntimeEntry;
import tgrv.model.RuntimeGroup;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class Aggregator {

    private Aggregator() {
    }

    public static List<RuntimeGroup> aggregate(Collection<CondensedRound> rounds, LocalDate from, LocalDate to, String serverFilter) {
        Map<String, RuntimeGroup> groups = new LinkedHashMap<>();
        for (CondensedRound rc : rounds) {
            if (rc.date.isBefore(from) || rc.date.isAfter(to)) {
                continue;
            }
            if (serverFilter != null && !serverFilter.equals(rc.serverName)) {
                continue;
            }
            for (RuntimeEntry entry : rc.entries) {
                RuntimeGroup g = groups.computeIfAbsent(entry.groupKey(),
                        k -> new RuntimeGroup(entry.message, entry.procName, entry.sourceFile));
                g.add(rc.serverName, rc.roundId, rc.date, entry);
            }
        }
        List<RuntimeGroup> result = new ArrayList<>(groups.values());
        result.sort(Comparator.comparingLong(RuntimeGroup::totalCount).reversed());
        return result;
    }
}
