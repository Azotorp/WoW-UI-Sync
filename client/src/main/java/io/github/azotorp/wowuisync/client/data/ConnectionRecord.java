package io.github.azotorp.wowuisync.client.data;

/** A saved login to a server (DESIGN.md §17.2). {@code password} is null when not saved. */
public record ConnectionRecord(String id, String name, String host, int port, String username, String authType,
                               String password, String keyPath) {
    public static final String AUTH_PASSWORD = "password";
    public static final String AUTH_KEY = "key";

    public boolean usesKey() {
        return AUTH_KEY.equals(authType);
    }

    public String hostPort() {
        return hostPort(host, port);
    }

    public static String hostPort(String host, int port) {
        return host.trim().toLowerCase(java.util.Locale.ROOT) + ":" + port;
    }

    @Override
    public String toString() {
        return name;
    }
}
