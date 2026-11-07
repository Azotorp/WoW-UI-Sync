package io.github.azotorp.wowuisync.server;

import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.crypto.CertFingerprint;
import io.github.azotorp.wowuisync.server.net.ConnectionHandler;
import io.github.azotorp.wowuisync.server.net.Listener;
import io.github.azotorp.wowuisync.server.net.ServerContext;
import io.github.azotorp.wowuisync.server.service.AccountService;
import io.github.azotorp.wowuisync.server.service.AuthService;
import io.github.azotorp.wowuisync.server.service.InviteService;
import io.github.azotorp.wowuisync.server.service.LoginGuard;
import io.github.azotorp.wowuisync.server.service.ResetService;
import io.github.azotorp.wowuisync.server.sync.Storage;
import io.github.azotorp.wowuisync.server.sync.SyncService;
import io.github.azotorp.wowuisync.server.config.ConfigIO;
import io.github.azotorp.wowuisync.server.config.DataLayout;
import io.github.azotorp.wowuisync.server.config.ServerConfig;
import io.github.azotorp.wowuisync.server.db.ServerDb;
import io.github.azotorp.wowuisync.server.security.Permissions;
import io.github.azotorp.wowuisync.server.security.TlsKeys;
import io.github.azotorp.wowuisync.server.util.Logs;

import java.nio.file.Files;
import java.security.KeyStore;
import java.util.concurrent.CountDownLatch;
import java.util.logging.Level;

/** The running server: startup checks, network listener, stop-flag watcher, housekeeping. */
public final class ServerApp {
    private final DataLayout layout;
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final CountDownLatch shutdownDone = new CountDownLatch(1);
    private volatile boolean stopping;

    public ServerApp(DataLayout layout) {
        this.layout = layout;
    }

    /** Runs until stopped. Returns the process exit code. */
    public int run() throws Exception {
        Files.createDirectories(layout.logs());
        ServerConfig cfg = ConfigIO.loadSettings(layout);
        Logs.init(layout.logs(), cfg.logLevel);
        Logs.LOG.info(Version.APP_NAME + " server " + Version.APP_VERSION + " starting; data folder " + layout.root());
        if (!Codec.zstdAvailable()) Logs.LOG.warning("zstd native library unavailable; using deflate");

        Files.createDirectories(layout.partial());
        Files.createDirectories(layout.accounts());
        Files.createDirectories(layout.keys());
        Permissions.lockDown(layout);
        if (Files.deleteIfExists(layout.stopFlag())) Logs.LOG.info("Removed a stale stop.flag");

        if (!Files.exists(layout.keystore())) {
            Logs.LOG.severe("TLS keystore missing: " + layout.keystore() + ". Run install.");
            return 1;
        }
        KeyStore ks = TlsKeys.load(layout.keystore());
        String fingerprint = TlsKeys.fingerprint(ks);

        try (ServerDb db = ServerDb.open(layout.db(), layout.dbBackups())) {
            Permissions.lockDown(layout);
            if (db.root().isEmpty()) {
                Logs.LOG.severe("No root account. Run install.");
                return 1;
            }
            ServerContext ctx = new ServerContext(layout, cfg, db, TlsKeys.serverContext(ks), fingerprint);
            Storage storage = new Storage(layout);
            LoginGuard guard = new LoginGuard(ctx);
            AccountService accounts = new AccountService(ctx, storage);
            ConnectionHandler.Services services = new ConnectionHandler.Services(new AuthService(ctx, guard), accounts,
                    new InviteService(ctx, accounts, guard), new ResetService(ctx, guard), new SyncService(ctx, storage));
            Listener listener = new Listener(ctx, services);
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                requestStop();
                try {
                    shutdownDone.await(15, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    // exit
                }
            }, "shutdown"));
            Thread watcher = Thread.ofPlatform().daemon().name("stop-flag").start(this::watchStopFlag);
            Thread housekeeping = Thread.ofPlatform().daemon().name("housekeeping").start(() -> housekeeping(ctx));
            listener.start();
            Logs.LOG.info("TLS certificate SHA-256: " + CertFingerprint.pretty(fingerprint));
            Logs.LOG.info("SMTP password reset: " + (ctx.smtp.enabled() ? "enabled" : "disabled"));
            stopped.await();
            ctx.setStopping();
            listener.close();
            ctx.sessions.closeAll();
            listener.awaitConnections(10_000);
            watcher.interrupt();
            housekeeping.interrupt();
            Logs.LOG.info("Server stopped");
            shutdownDone.countDown();
            return 0;
        } catch (Exception e) {
            Logs.LOG.log(Level.SEVERE, "Server failed: " + e.getMessage(), e);
            return 1;
        }
    }

    private void housekeeping(ServerContext ctx) {
        while (!stopping) {
            try {
                ctx.db.pruneIpFailures();
                ctx.db.deleteExpiredInvites();
                Thread.sleep(10 * 60_000L);
            } catch (InterruptedException e) {
                return;
            } catch (Exception e) {
                Logs.LOG.warning("Housekeeping failed: " + e.getMessage());
            }
        }
    }

    private void watchStopFlag() {
        while (!stopping) {
            if (Files.exists(layout.stopFlag())) {
                Logs.LOG.info("stop.flag found; shutting down");
                requestStop();
                return;
            }
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    public void requestStop() {
        if (stopping) return;
        stopping = true;
        stopped.countDown();
    }
}
