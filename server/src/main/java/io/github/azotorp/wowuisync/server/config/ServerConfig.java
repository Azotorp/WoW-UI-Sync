package io.github.azotorp.wowuisync.server.config;

import io.github.azotorp.wowuisync.common.Defaults;

/** {@code server.json} (DESIGN.md §5.2). Mutable for Jackson. */
public class ServerConfig {
    public String dataDir;
    public java.util.List<String> additionalFlavors=new java.util.ArrayList<>();
    public String bindAddress = Defaults.BIND_ADDRESS;
    public int port = Defaults.PORT;
    public String publicHost;
    public int maxDataConnectionsPerSession = Defaults.MAX_DATA_CONNECTIONS;
    public int maxActiveConnections=128;
    public int maxConcurrentHandshakes=16;
    public int maxConnectionsPerIp=32;
    public String logLevel = "INFO";
    public Smtp smtp = new Smtp();
    public Lockout lockout = new Lockout();

    public static class Smtp {
        public boolean enabled = false;
        public String host = "";
        public int port = 587;
        public String username = "";
        public String password = "";
        public String from = "";
        /** {@code starttls} or {@code ssl}. */
        public String security = "starttls";
    }

    public static class Lockout {
        public int accountFailures = 5;
        public int accountLockMinutes = 15;
        public int ipFailuresPer10Min = 20;
        public int ipBlockMinutes = 15;
    }

    public void validate() {
        if (port < 1025 || port > 65535) throw new IllegalArgumentException("port must be 1025-65535");
        if (maxDataConnectionsPerSession < 1 || maxDataConnectionsPerSession > Defaults.MAX_DATA_CONNECTIONS) {
            maxDataConnectionsPerSession = Defaults.MAX_DATA_CONNECTIONS;
        }
        if(maxActiveConnections<1 || maxActiveConnections>512 || maxConcurrentHandshakes<1 || maxConcurrentHandshakes>64 || maxConnectionsPerIp<1 || maxConnectionsPerIp>128)
            throw new IllegalArgumentException("Invalid connection budgets");
        if(additionalFlavors==null)additionalFlavors=new java.util.ArrayList<>();
        for(String flavor:additionalFlavors)if(!io.github.azotorp.wowuisync.common.profile.Profiles.isValidProfileId(flavor))throw new IllegalArgumentException("Invalid additional flavor identifier");
        if (smtp == null) smtp = new Smtp();
        if (lockout == null) lockout = new Lockout();
    }
}
