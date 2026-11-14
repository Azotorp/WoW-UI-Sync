package io.github.azotorp.wowuisync.client;

import io.github.azotorp.wowuisync.client.data.ClientConfig;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.data.ClientPaths;
import io.github.azotorp.wowuisync.client.data.ConnectionRecord;
import io.github.azotorp.wowuisync.client.net.ServerConnection;
import io.github.azotorp.wowuisync.client.util.ClientLog;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.client.wow.FlavorDetector;
import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.protocol.Msg;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Client state shared by the UI: config, database, current connection, selected account and flavor. */
public final class ClientApp {
    private final ClientPaths paths;
    private final ClientConfig config;
    private final ClientDb db;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();

    private volatile ServerConnection connection;
    private volatile ConnectionRecord connectionRecord;
    private volatile Msg.AccountInfo targetAccount;
    private volatile Flavor flavor;
    private volatile boolean busy;

    public ClientApp(ClientPaths paths, ClientConfig config, ClientDb db) {
        this.paths = paths;
        this.config = config;
        this.db = db;
    }

    public ClientPaths paths() { return paths; }
    public ClientConfig config() { return config; }
    public ClientDb db() { return db; }
    public ServerConnection connection() { return connection; }
    public ConnectionRecord connectionRecord() { return connectionRecord; }
    public Msg.AccountInfo targetAccount() { return targetAccount; }
    public Flavor flavor() { return flavor; }
    public boolean busy() { return busy; }

    public boolean connected() {
        ServerConnection c = connection;
        return c != null && !c.isClosed() && c.login() != null;
    }

    public Msg.LoginOk login() {
        ServerConnection c = connection;
        return c == null ? null : c.login();
    }

    public Role role() {
        Msg.LoginOk l = login();
        return l == null ? null : Role.parse(l.role());
    }

    public void setConnection(ServerConnection c, ConnectionRecord rec) {
        ServerConnection old = this.connection;
        if (old != null && old != c) old.close();
        this.connection = c;
        this.connectionRecord = rec;
        this.targetAccount = null;
        if (c != null && c.login() != null) {
            for (Msg.AccountInfo a : c.login().accessibleAccounts()) {
                if (a.id().equals(c.login().accountId())) targetAccount = a;
            }
        }
        if (rec != null) {
            config.lastConnectionId = rec.id();
            saveConfig();
        }
        fire();
    }

    /** Replaces the accessible-account list after account changes. */
    public void updateAccessible(List<Msg.AccountInfo> accounts) {
        ServerConnection c = connection;
        if (c == null || c.login() == null) return;
        Msg.LoginOk l = c.login();
        c.updateLogin(new Msg.LoginOk(l.accountId(), l.username(), l.role(), l.sessionId(), accounts, l.maxDataConnections()));
        Msg.AccountInfo keep = null;
        for (Msg.AccountInfo a : accounts) {
            if (targetAccount != null && a.id().equals(targetAccount.id())) keep = a;
        }
        if (keep == null) {
            for (Msg.AccountInfo a : accounts) if (a.id().equals(l.accountId())) keep = a;
        }
        targetAccount = keep;
        fire();
    }

    public void disconnect() {
        setConnection(null, null);
    }

    public void setTargetAccount(Msg.AccountInfo a) {
        this.targetAccount = a;
        fire();
    }

    public void setFlavor(Flavor f) {
        this.flavor = f;
        if (f != null) {
            config.lastProfileId = f.profileId();
            config.lastInstallationId=config.installationId(f.path());
            saveConfig();
        }
        fire();
    }

    public void setBusy(boolean b) {
        this.busy = b;
        fire();
    }

    public List<Flavor> flavors() {
        java.util.LinkedHashMap<String,Flavor> found=new java.util.LinkedHashMap<>();
        java.util.List<Path> roots=new java.util.ArrayList<>();
        if(installRoot()!=null) roots.add(installRoot());
        for(String r:config.installRoots) roots.add(Paths.get(r));
        Path suggested=FlavorDetector.suggestedRoot();if(suggested!=null) roots.add(suggested);
        for(Path root:roots) for(Flavor f:FlavorDetector.detect(root)) {
            found.putIfAbsent(config.installationId(f.path()),f);
        }
        for(var entry:config.manualFlavors.entrySet()) {
            Path path=Paths.get(entry.getKey());if(!java.nio.file.Files.isDirectory(path))continue;
            for(var detected:FlavorDetector.detect(path))if(detected.path().equals(path)&&!io.github.azotorp.wowuisync.common.profile.Profiles.isEnabled(detected.profileId()))
                found.put(config.installationId(path),new Flavor(path.getFileName().toString(),path,entry.getValue(),detected.valid(),true));
        }
        saveConfig();
        return java.util.List.copyOf(found.values());
    }

    public Path installRoot() {
        return config.installRoot == null || config.installRoot.isBlank() ? null : Paths.get(config.installRoot);
    }

    /** Re-detects flavors and keeps the selection when possible. */
    public void refreshFlavor() {
        Flavor keep = null;
        for (Flavor f : flavors()) {
            if (!f.enabled()) continue;
            if(keep==null) keep=f;
            if(config.lastInstallationId!=null && config.installationId(f.path()).equals(config.lastInstallationId)) { keep=f;break; }
            if(config.lastInstallationId==null && f.profileId().equals(config.lastProfileId)) keep=f;
        }
        this.flavor = keep;
        if(keep!=null && config.lastInstallationId==null) { config.lastInstallationId=config.installationId(keep.path());saveConfig(); }
        fire();
    }

    public void saveConfig() {
        try {
            config.save(paths.config());
        } catch (Exception e) {
            ClientLog.LOG.warning("Could not save client.json: " + e.getMessage());
        }
    }

    public void addListener(Runnable r) {
        listeners.add(r);
    }

    public void fire() {
        for (Runnable r : listeners) {
            if (javax.swing.SwingUtilities.isEventDispatchThread()) r.run();
            else javax.swing.SwingUtilities.invokeLater(r);
        }
    }

    /** Name for the applied-account record and backup folder names. */
    public static String safeName(String s) {
        return s == null ? "local" : s.replaceAll("[^A-Za-z0-9_-]", "_");
    }
}
