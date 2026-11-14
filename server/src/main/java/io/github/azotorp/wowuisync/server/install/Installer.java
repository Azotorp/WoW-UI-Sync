package io.github.azotorp.wowuisync.server.install;

import io.github.azotorp.wowuisync.common.Defaults;
import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.crypto.CertFingerprint;
import io.github.azotorp.wowuisync.common.util.Usernames;
import io.github.azotorp.wowuisync.server.config.ConfigIO;
import io.github.azotorp.wowuisync.server.config.DataLayout;
import io.github.azotorp.wowuisync.server.config.ServerConfig;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.db.ServerDb;
import io.github.azotorp.wowuisync.server.security.Permissions;
import io.github.azotorp.wowuisync.server.security.TlsKeys;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.UUID;

/** The {@code install} subcommand (DESIGN.md §5.4). */
public final class Installer {
    private Installer() {}

    /** Returns the data folder if the user chose to start the server now, else null. */
    public static Path run(String dataArg) throws Exception {
        io.github.azotorp.wowuisync.server.util.Logs.init(null, "WARNING");
        Prompt in = new Prompt();
        in.println(Version.APP_NAME + " server " + Version.APP_VERSION + " - install");
        in.println("");

        // 1. Java version
        int feature = Runtime.version().feature();
        if (feature < 21) {
            in.println("Java 21 or newer is required (this is Java " + feature + ").");
            return null;
        }
        in.println("Java " + Runtime.version() + " - OK");

        // 2. Data folder
        Path data;
        while (true) {
            String def = dataArg != null ? dataArg : Paths.get(System.getProperty("user.home"), "wow-ui-sync-data").toString();
            String s = in.line("Data folder (full path)", def);
            Path p = Paths.get(s);
            if (!p.isAbsolute()) {
                in.println("  Please enter a full path.");
                continue;
            }
            try {
                Files.createDirectories(p);
                Path probe = Files.createTempFile(p, "probe", ".tmp");
                Files.delete(probe);
                data = p.toAbsolutePath().normalize();
                break;
            } catch (Exception e) {
                in.println("  Cannot write to " + p + ": " + e.getMessage());
            }
        }
        DataLayout layout = new DataLayout(data);
        if(Files.exists(layout.db())) throw new java.io.IOException("Data is already initialized. Use update/migrate; setup will not overwrite it.");

        // 3. Port and public host
        int port;
        while (true) {
            String s = in.line("Port", String.valueOf(Defaults.PORT));
            try {
                port = Integer.parseInt(s);
                if (port >= 1025 && port <= 65535) break;
            } catch (NumberFormatException ignored) {
                // re-ask
            }
            in.println("  Enter a number from 1025 to 65535.");
        }
        String host = in.line("Public host name or IP address clients will use", guessHost());

        // 4-5. Root credentials
        in.println("");
        in.println("Root account. A public key is recommended; it is used only by WoW UI Sync and never grants SSH access.");
        Credentials.Choice cred = Credentials.ask(in, "root", true);

        // 6. Email
        String email = in.line("Root's email address (optional, for password reset)", "");

        // 7. Write everything
        ServerConfig cfg = new ServerConfig();
        cfg.dataDir = data.toString();
        cfg.port = port;
        cfg.publicHost = host;
        cfg.validate();
        Files.createDirectories(layout.keys());
        Files.createDirectories(layout.tls());
        Files.createDirectories(layout.logs());
        Files.createDirectories(layout.partial());
        Files.createDirectories(layout.accounts());
        Files.createDirectories(layout.dbBackups());
        ConfigIO.write(layout.config(), cfg);
        try {
            io.github.azotorp.wowuisync.common.manifest.ManifestIO.write(ConfigIO.jarSideConfig(),io.github.azotorp.wowuisync.common.json.Json.toBytes(java.util.Map.of("dataDir",data.toString())));
        } catch (Exception e) {
            in.println("Note: could not write server.json next to the jar (" + e.getMessage() + "). Start the server with --data " + data);
        }
        if (!Files.exists(layout.keystore())) TlsKeys.generate(layout.keystore(), host);
        String fp = TlsKeys.fingerprint(TlsKeys.load(layout.keystore()));

        String id = UUID.randomUUID().toString();
        try (ServerDb db = ServerDb.open(layout.db(), layout.dbBackups())) {
            AccountRow root;
            if (cred.key() != null) {
                Files.writeString(layout.keyFile(id), cred.key().line() + "\n");
                root = new AccountRow(id, Usernames.ROOT, Role.ROOT, null, blankToNull(email), AccountRow.AUTH_KEY,
                        null, null, null, cred.key().type(), System.currentTimeMillis(), null, null, null, 0, null, 0);
            } else {
                root = new AccountRow(id, Usernames.ROOT, Role.ROOT, null, blankToNull(email), AccountRow.AUTH_PASSWORD,
                        cred.password().hashB64(), cred.password().saltB64(), cred.password().iterations(), null,
                        System.currentTimeMillis(), null, null, null, 0, null, 0);
            }
            db.insertAccount(root);
        }
        io.github.azotorp.wowuisync.server.maintenance.RepositoryMaintenance.markFormat(layout);
        Permissions.lockDown(layout, true);

        // 8. Summary
        in.println("");
        in.println("Installed.");
        in.println("  Host:          " + host);
        in.println("  Port:          " + port);
        in.println("  Username:      root");
        in.println("  Login method:  " + (cred.key() != null ? "public key (" + cred.key().type() + ")" : "password"));
        in.println("  TLS certificate SHA-256 fingerprint:");
        in.println("    " + CertFingerprint.pretty(fp));
        in.println("  Compare this fingerprint with the one the client shows on first connect.");
        in.println("");
        in.println("Open the port in your firewall, for example:  sudo ufw allow " + port + "/tcp");
        in.println("For access from the internet on a home connection, forward TCP port " + port + " on your router to this machine.");
        in.println("Start the server with scripts/start.sh (Linux/macOS) or scripts\\start.bat (Windows).");
        in.println("");
        return in.yesNo("Start the server now in this terminal?", false) ? data : null;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String guessHost() {
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback() || ni.isVirtual()) continue;
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress() && !a.isLinkLocalAddress()) {
                        return a.getHostAddress();
                    }
                }
            }
            return InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            return "localhost";
        }
    }
}
