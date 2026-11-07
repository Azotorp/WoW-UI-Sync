package io.github.azotorp.wowuisync.server;

import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.server.config.ConfigIO;
import io.github.azotorp.wowuisync.server.config.DataLayout;
import io.github.azotorp.wowuisync.server.install.Installer;
import io.github.azotorp.wowuisync.server.install.ResetRoot;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Server entry point. Subcommands: {@code run}, {@code install}, {@code reset-root}, {@code stop}, {@code data-dir},
 * {@code version}. Option: {@code --data <path>}.
 */
public final class ServerMain {
    private ServerMain() {}

    public static void main(String[] args) {
        String cmd = null;
        String data = null;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.equals("--data") && i + 1 < args.length) data = args[++i];
            else if (a.startsWith("--data=")) data = a.substring(7);
            else if (cmd == null) cmd = a;
        }
        if (cmd == null) cmd = "help";
        try {
            switch (cmd) {
                case "run" -> System.exit(new ServerApp(requireData(data)).run());
                case "install" -> {
                    Path started = Installer.run(data);
                    if (started != null) System.exit(new ServerApp(new DataLayout(started)).run());
                }
                case "reset-root" -> ResetRoot.run(requireData(data));
                case "stop" -> {
                    DataLayout l = requireData(data);
                    Files.writeString(l.stopFlag(), "stop\n");
                    System.out.println("Stop requested. The server exits within a few seconds.");
                }
                case "data-dir" -> System.out.println(requireData(data).root());
                case "version", "--version" -> System.out.println(Version.APP_NAME + " server " + Version.APP_VERSION
                        + " (protocol " + Version.PROTOCOL_VERSION + ")");
                default -> usage();
            }
        } catch (UsageException e) {
            System.err.println(e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static DataLayout requireData(String data) throws Exception {
        Path p = ConfigIO.resolveDataDir(data);
        if (p == null) {
            throw new UsageException("No data folder configured. Run 'install' first, or pass --data <path>.");
        }
        return new DataLayout(p);
    }

    private static void usage() {
        System.out.println(Version.APP_NAME + " server " + Version.APP_VERSION);
        System.out.println();
        System.out.println("Usage: java -jar wow-ui-sync-server.jar <command> [--data <path>]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  install      first-time setup: data folder, port, TLS certificate, root account");
        System.out.println("  run          start the server (normally via scripts/start.sh or start.bat)");
        System.out.println("  reset-root   replace root's public key or password");
        System.out.println("  stop         ask a running server to stop");
        System.out.println("  data-dir     print the data folder path");
        System.out.println("  version      print the version");
        System.out.println();
        System.out.println(Version.DISCLAIMER);
    }

    static final class UsageException extends Exception {
        UsageException(String m) {
            super(m);
        }
    }
}
