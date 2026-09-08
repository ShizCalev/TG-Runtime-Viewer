package tgrv.net;

import tgrv.Version;
import tgrv.json.Json;
import tgrv.model.LogServer;
import tgrv.model.CondensedRound;
import tgrv.model.RuntimeEntry;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class LogHttpClient {

    private static final Pattern ROUND_LINK = Pattern.compile("round-(\\d+)/?");
    private static final DateTimeFormatter DAY_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public List<Integer> listRoundsForDay(LogServer server, LocalDate day) throws IOException, InterruptedException {
        String url = server.baseUrl + "/" + DAY_FMT.format(day) + "/";
        HttpResponse<String> resp = get(url);
        if (resp.statusCode() == 404) {
            return new ArrayList<>();
        }
        if (resp.statusCode() != 200) {
            throw new IOException("Unexpected status " + resp.statusCode() + " for " + url);
        }
        Set<Integer> ids = new LinkedHashSet<>();
        Matcher m = ROUND_LINK.matcher(resp.body());
        while (m.find()) {
            ids.add(Integer.parseInt(m.group(1)));
        }
        return new ArrayList<>(ids);
    }

    public CondensedRound fetchCondensed(LogServer server, LocalDate day, int roundId, boolean isFinal) throws IOException, InterruptedException {
        String url = server.baseUrl + "/" + DAY_FMT.format(day) + "/round-" + roundId + "/runtime.condensed.json";
        HttpResponse<String> resp = get(url);
        List<RuntimeEntry> entries = new ArrayList<>();
        if (resp.statusCode() == 200 && !resp.body().isBlank()) {
            entries = parseCondensed(resp.body());
        }
        return new CondensedRound(server.name, roundId, day, entries, isFinal);
    }

    public String fetchRawLog(LogServer server, LocalDate day, int roundId) throws IOException, InterruptedException {
        String url = server.baseUrl + "/" + DAY_FMT.format(day) + "/round-" + roundId + "/runtime.log";
        HttpResponse<String> resp = get(url);
        if (resp.statusCode() == 200) {
            return resp.body();
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<RuntimeEntry> parseCondensed(String json) {
        List<RuntimeEntry> entries = new ArrayList<>();
        Object root = Json.parse(json);
        if (!(root instanceof Map)) {
            return entries;
        }
        Object runtimesObj = ((Map<String, Object>) root).get("runtimes");
        if (!(runtimesObj instanceof List)) {
            return entries;
        }
        for (Object o : (List<Object>) runtimesObj) {
            if (!(o instanceof Map)) {
                continue;
            }
            Map<String, Object> e = (Map<String, Object>) o;
            entries.add(new RuntimeEntry(
                    Json.asString(e.get("message")),
                    Json.asString(e.get("proc_name")),
                    Json.asString(e.get("source_file")),
                    Json.asString(e.get("src")),
                    Json.asString(e.get("src_loc")),
                    Json.asString(e.get("usr")),
                    Json.asLong(e.get("count"), 1)
            ));
        }
        return entries;
    }

    private HttpResponse<String> get(String url) throws IOException, InterruptedException {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("User-Agent", "TG-Runtime-Viewer/" + Version.CURRENT)
                .GET()
                .build();
        return http.send(req, HttpResponse.BodyHandlers.ofString());
    }
}
