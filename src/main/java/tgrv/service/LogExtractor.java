package tgrv.service;

import tgrv.model.RuntimeGroup;
import tgrv.store.RoundCache;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogExtractor {

    private static final Pattern BLOCK_START = Pattern.compile("^\\[[^\\]]*] RUNTIME: runtime error: (.*)$", Pattern.MULTILINE);
    private static final Pattern PROC_LINE = Pattern.compile("(?m)^ -\\s*proc name: (.*)$");
    private static final Pattern SOURCE_LINE = Pattern.compile("(?m)^ -\\s*source file: (.*)$");

    private LogExtractor() {
    }

    public static final class Example {
        public final String serverName;
        public final int roundId;
        public final LocalDate date;
        public final String blockText;

        public Example(String serverName, int roundId, LocalDate date, String blockText) {
            this.serverName = serverName;
            this.roundId = roundId;
            this.date = date;
            this.blockText = blockText;
        }
    }

    public static List<Example> findExamples(RoundCache cache, RuntimeGroup group, int maxExamples) {
        List<Example> examples = new ArrayList<>();
        List<RuntimeGroup.Occurrence> occurrences = group.occurrences();
        for (int i = occurrences.size() - 1; i >= 0 && examples.size() < maxExamples; i--) {
            RuntimeGroup.Occurrence occ = occurrences.get(i);
            String raw = cache.readRawLog(occ.serverName, occ.date, occ.roundId);
            if (raw == null) {
                continue;
            }
            for (String block : findBlocks(raw)) {
                if (examples.size() >= maxExamples) {
                    break;
                }
                if (blockMatches(block, group)) {
                    examples.add(new Example(occ.serverName, occ.roundId, occ.date, block));
                }
            }
        }
        return examples;
    }

    private static boolean blockMatches(String block, RuntimeGroup group) {
        Matcher startMatcher = BLOCK_START.matcher(block);
        if (!startMatcher.find() || !startMatcher.group(1).trim().equals(group.message.trim())) {
            return false;
        }
        Matcher procMatcher = PROC_LINE.matcher(block);
        if (!procMatcher.find() || !procMatcher.group(1).trim().equals(group.procName.trim())) {
            return false;
        }
        Matcher sourceMatcher = SOURCE_LINE.matcher(block);
        return sourceMatcher.find() && sourceMatcher.group(1).trim().equals(group.sourceFile.trim());
    }

    private static List<String> findBlocks(String rawLog) {
        List<String> blocks = new ArrayList<>();
        String[] lines = rawLog.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (!BLOCK_START.matcher(lines[i].stripTrailing()).matches()) {
                continue;
            }
            StringBuilder block = new StringBuilder(lines[i]);
            int j = i + 1;
            while (j < lines.length && lines[j].startsWith(" -")) {
                block.append('\n').append(lines[j]);
                j++;
            }
            blocks.add(block.toString());
            i = j - 1;
        }
        return blocks;
    }
}
