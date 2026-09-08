package tgrv.model;

import java.util.List;

public final class LogServer {
    public final String name;
    public final String baseUrl;

    public LogServer(String name, String baseUrl) {
        this.name = name;
        this.baseUrl = baseUrl;
    }

    public static final List<LogServer> ALL = List.of(
            new LogServer("Sybil", "https://sybil-logs.tgstation13.org"),
            new LogServer("Terry", "https://terry-logs.tgstation13.org"),
            new LogServer("Manuel", "https://manuel-logs.tgstation13.org")
    );

    @Override
    public String toString() {
        return name;
    }
}
