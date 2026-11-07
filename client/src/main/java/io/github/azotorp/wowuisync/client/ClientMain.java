package io.github.azotorp.wowuisync.client;

import io.github.azotorp.wowuisync.client.data.ClientConfig;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.data.ClientPaths;
import io.github.azotorp.wowuisync.client.ui.MainWindow;
import io.github.azotorp.wowuisync.client.ui.Theme;
import io.github.azotorp.wowuisync.client.util.ClientLog;
import io.github.azotorp.wowuisync.common.Version;

import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.util.logging.Level;

/** Client entry point. */
public final class ClientMain {
    private ClientMain() {}

    public static void main(String[] args) {
        if (args.length > 0 && (args[0].equals("--version") || args[0].equals("version"))) {
            System.out.println(Version.APP_NAME + " client " + Version.APP_VERSION);
            return;
        }
        ClientPaths paths = ClientPaths.locate();
        try {
            Files.createDirectories(paths.root());
            ClientLog.init(paths.logs());
            ClientLog.LOG.info(Version.APP_NAME + " client " + Version.APP_VERSION + " starting; data in " + paths.root());
            ClientConfig cfg = ClientConfig.load(paths.config());
            cfg.save(paths.config());
            ClientDb db = ClientDb.open(paths.db(), paths.dbBackups());
            ClientApp app = new ClientApp(paths, cfg, db);
            Theme.apply(cfg.theme, false);
            SwingUtilities.invokeLater(() -> {
                MainWindow w = new MainWindow(app);
                w.setVisible(true);
                w.start();
            });
        } catch (Throwable e) {
            ClientLog.LOG.log(Level.SEVERE, "Startup failed", e);
            String msg = "WoW UI Sync could not start:\n" + e.getMessage() + "\n\nData folder: " + paths.root();
            if (java.awt.GraphicsEnvironment.isHeadless()) System.err.println(msg);
            else JOptionPane.showMessageDialog(null, msg, Version.APP_NAME, JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
