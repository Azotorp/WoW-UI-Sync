package io.github.azotorp.wowuisync.server;

import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.codec.Codec;
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

/** The running server: startup checks, stop-flag watcher, and (from Phase 5) the network listener. */
public final class ServerApp {
    private final DataLayout layout;
    private final CountDownLatch stopped = new CountDownLatch(1);
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
            Runtime.getRuntime().addShutdownHook(new Thread(this::requestStop, "shutdown"));
            Thread watcher = Thread.ofPlatform().daemon().name("stop-flag").start(this::watchStopFlag);
            Logs.LOG.info("TLS certificate SHA-256: " + fingerprint);
            stopped.await();
            watcher.interrupt();
            Logs.LOG.info("Server stopped");
            return 0;
        } catch (Exception e) {
            Logs.LOG.log(Level.SEVERE, "Server failed: " + e.getMessage(), e);
            return 1;
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
