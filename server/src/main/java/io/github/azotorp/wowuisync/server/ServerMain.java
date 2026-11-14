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
        if(args.length>0 && java.util.Set.of("package","update","rollback").contains(args[0])) {
            try { io.github.azotorp.wowuisync.server.maintenance.ReleaseManager.command(args); }
            catch(Exception e) { System.err.println("Error: "+e.getMessage());System.exit(1); }return;
        }
        String cmd = null;
        String data = null;
        for(int i=0;i<args.length;i++) {
            String a=args[i];
            if(a.equals("--help")||a.equals("-h")){usage();return;}
            if(a.equals("--version")){cmd="version";continue;}
            if(a.equals("--data")) {
                if(i+1>=args.length||args[i+1].startsWith("--")){System.err.println("--data requires a directory");System.exit(2);return;}
                data=args[++i];
            }else if(a.startsWith("--data=")){
                data=a.substring(7);if(data.isBlank()){System.err.println("--data requires a directory");System.exit(2);return;}
            }else if(cmd==null&&!a.startsWith("-"))cmd=a;
            else{System.err.println("Unknown argument: "+a);System.exit(2);return;}
        }
        if (cmd == null) cmd = "help";
        try {
            switch (cmd) {
                case "verify" -> io.github.azotorp.wowuisync.server.maintenance.RepositoryMaintenance.verify(requireData(data));
                case "migrate" -> io.github.azotorp.wowuisync.server.maintenance.RepositoryMaintenance.migrate(requireData(data));
                case "gc" -> io.github.azotorp.wowuisync.server.maintenance.RepositoryMaintenance.gc(requireData(data));
                case "ready" -> io.github.azotorp.wowuisync.server.maintenance.Readiness.check(requireData(data));
                case "status" -> io.github.azotorp.wowuisync.server.maintenance.RepositoryMaintenance.status(requireData(data));
                case "gui" -> io.github.azotorp.wowuisync.server.ui.ServerAdmin.show(requireData(data));
                case "run" -> System.exit(new ServerApp(requireData(data)).run());
                case "install", "setup" -> {
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
                case "help", "--help", "-h" -> usage();
                default -> throw new UsageException("Unknown command: "+cmd);
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
        System.out.println("  status       running state, data path and format versions");
        System.out.println("  migrate      offline, checkpointed v1 to v2 migration");
        System.out.println("  verify       read-only database, snapshots and content integrity check");
        System.out.println("  gc           offline unreferenced chunk collection, 48h grace");
        System.out.println("  gui          optional local desktop status and service controls");
        System.out.println();
        System.out.println(Version.DISCLAIMER);
    }

    static final class UsageException extends Exception {
        UsageException(String m) {
            super(m);
        }
    }
}
