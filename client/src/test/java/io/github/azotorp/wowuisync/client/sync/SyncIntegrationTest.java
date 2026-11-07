package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.data.ClientConfig;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.data.ClientPaths;
import io.github.azotorp.wowuisync.client.data.ConnectionRecord;
import io.github.azotorp.wowuisync.client.local.Backups;
import io.github.azotorp.wowuisync.client.net.ServerConnection;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.addon.PullMode;
import io.github.azotorp.wowuisync.common.addon.PullSelection;
import io.github.azotorp.wowuisync.common.crypto.Passwords;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.server.ServerApp;
import io.github.azotorp.wowuisync.server.config.ConfigIO;
import io.github.azotorp.wowuisync.server.config.DataLayout;
import io.github.azotorp.wowuisync.server.config.ServerConfig;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.db.ServerDb;
import io.github.azotorp.wowuisync.server.security.TlsKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SyncIntegrationTest {
    @TempDir
    Path dir;

    static final ServerConnection.Callbacks CB = new ServerConnection.Callbacks() {
        public boolean trustNewFingerprint(String hp, String fp) { return true; }
        public String askPassword(ConnectionRecord rec, String m) { return null; }
        public char[] askPassphrase(Path k, boolean w) { return null; }
    };

    static final TransferEngine.Sink SINK = new TransferEngine.Sink() {
        public void stats(TransferStats s) {}
        public void connectionLost(String m) { throw new AssertionError(m); }
    };

    private static void write(Path p, byte[] b) throws Exception {
        Files.createDirectories(p.getParent());
        Files.write(p, b);
    }

    private ClientApp client(String name) throws Exception {
        ClientPaths paths = new ClientPaths(dir.resolve(name + "-data"));
        ClientConfig cfg = ClientConfig.load(paths.config());
        cfg.deviceNickname = name;
        cfg.installRoot = dir.resolve(name + "-wow").toString();
        return new ClientApp(paths, cfg, ClientDb.open(paths.db(), paths.dbBackups()));
    }

    @Test
    void pushPull() throws Exception {
        // --- server
        Path sd = dir.resolve("server");
        DataLayout l = new DataLayout(sd);
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ServerConfig cfg = new ServerConfig();
        cfg.dataDir = sd.toString();
        cfg.port = port;
        cfg.bindAddress = "127.0.0.1";
        ConfigIO.write(l.config(), cfg);
        TlsKeys.generate(l.keystore(), "127.0.0.1");
        try (ServerDb db = ServerDb.open(l.db(), l.dbBackups())) {
            Passwords.Hash h = Passwords.hash("password1".toCharArray());
            db.insertAccount(new AccountRow(UUID.randomUUID().toString(), "root", Role.ROOT, null, null, AccountRow.AUTH_PASSWORD,
                    h.hashB64(), h.saltB64(), h.iterations(), null, System.currentTimeMillis(), null, null, null, 0, null, 0));
        }
        Thread st = new Thread(() -> {
            try {
                new ServerApp(l).run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        st.start();
        Thread.sleep(1500);
        try {
            ConnectionRecord rec = new ConnectionRecord("c", "test", "127.0.0.1", port, "root", "password", "password1", null);

            // --- PC1 with files
            ClientApp pc1 = client("pc1");
            Path f1 = dir.resolve("pc1-wow/_forever_");
            write(f1.resolve(".flavor.info"), "x".getBytes());
            write(f1.resolve("Interface/AddOns/WeakAuras/WeakAuras.toc"), "## Title: WeakAuras\n".getBytes());
            write(f1.resolve("Interface/AddOns/WeakAuras_Options/WeakAuras_Options.toc"), "## Dependencies: WeakAuras\n".getBytes());
            write(f1.resolve("Interface/AddOns/Details/Details.toc"), "## Title: Details\n".getBytes());
            byte[] big = new byte[6 << 20];
            new Random(1).nextBytes(big);
            write(f1.resolve("Interface/AddOns/Details/sounds/big.ogg"), big);
            for (int i = 0; i < 300; i++) write(f1.resolve("Interface/AddOns/WeakAuras/lib/f" + i + ".lua"), ("-- file " + i).repeat(50).getBytes());
            write(f1.resolve("WTF/Account/ACC/SavedVariables/WeakAuras.lua"), "WeakAurasSaved = {}".getBytes());
            write(f1.resolve("WTF/Account/ACC/SavedVariables/Details.lua"), "Details = {}".getBytes());
            write(f1.resolve("WTF/Account/ACC/macros-cache.txt"), "MACRO".getBytes());
            Flavor fl1 = new Flavor("_forever_", f1, "forever", true);
            ServerConnection c1 = ServerConnection.open("127.0.0.1", port, pc1.db(), null, CB, pc1.config().deviceId, "pc1");
            Msg.LoginOk login = c1.login(rec, CB);
            pc1.setConnection(c1, rec);
            Msg.AccountInfo root = pc1.targetAccount();
            SyncOps ops1 = new SyncOps(pc1);
            LocalScan s1 = ops1.scan(fl1, null, () -> false);
            assertEquals(SyncStatus.Kind.SERVER_EMPTY, ops1.status(c1, root, fl1, s1).kind());
            Msg.PushNeed need = ops1.pushBegin(c1, root, fl1, s1, null);
            assertEquals(s1.manifest().size(), need.newFiles().size());
            ops1.pushUpload(c1, root, fl1, s1, need, new TransferControl(), SINK);
            assertEquals(SyncStatus.Kind.UP_TO_DATE, ops1.status(c1, root, fl1, ops1.scan(fl1, null, () -> false)).kind());

            // --- PC2 pulls everything
            ClientApp pc2 = client("pc2");
            Path f2 = dir.resolve("pc2-wow/_forever_");
            write(f2.resolve(".flavor.info"), "x".getBytes());
            write(f2.resolve("Interface/AddOns/Old/Old.toc"), "## Title: Old\n".getBytes());
            Flavor fl2 = new Flavor("_forever_", f2, "forever", true);
            ServerConnection c2 = ServerConnection.open("127.0.0.1", port, pc2.db(), null, CB, pc2.config().deviceId, "pc2");
            c2.login(rec, CB);
            pc2.setConnection(c2, rec);
            SyncOps ops2 = new SyncOps(pc2);
            LocalScan s2 = ops2.scan(fl2, null, () -> false);
            assertEquals(SyncStatus.Kind.DIFFERENT, ops2.status(c2, root, fl2, s2).kind());
            SyncOps.PullSource src = ops2.pullSource(c2, root, fl2);
            assertTrue(src.tocs().containsKey("weakauras"));
            PullSelection all = new PullSelection(List.of("WeakAuras", "WeakAuras_Options", "Details"), List.of("WeakAuras", "WeakAuras_Options", "Details"), true);
            Msg.PullPlan plan = ops2.pullPlan(c2, root, fl2, s2, all, PullMode.REPLACE);
            assertTrue(plan.selectsEverything());
            assertEquals(1, plan.removedFiles().size());
            ops2.pullApply(c2, root, fl2, s2, plan, new TransferControl(), SINK, null);
            assertArrayEquals(big, Files.readAllBytes(f2.resolve("Interface/AddOns/Details/sounds/big.ogg")));
            assertFalse(Files.exists(f2.resolve("Interface/AddOns/Old")));
            assertEquals(1, Backups.list(pc2.db(), f2).size());
            LocalScan s2b = ops2.scan(fl2, null, () -> false);
            assertEquals(SyncStatus.Kind.UP_TO_DATE, ops2.status(c2, root, fl2, s2b).kind());

            // --- PC2 edits and pushes a delta; PC1 sees "server newer"
            write(f2.resolve("WTF/Account/ACC/SavedVariables/WeakAuras.lua"), "WeakAurasSaved = {changed=true}".getBytes());
            LocalScan s2c = ops2.scan(fl2, null, () -> false);
            SyncStatus st2 = ops2.status(c2, root, fl2, s2c);
            assertEquals(SyncStatus.Kind.LOCAL_CHANGES, st2.kind());
            Msg.PushNeed need2 = ops2.pushBegin(c2, root, fl2, s2c, st2.server().currentFingerprint());
            assertEquals(1, need2.editedFiles().size());
            assertEquals(1, need2.missingHashes().size());
            ops2.pushUpload(c2, root, fl2, s2c, need2, new TransferControl(), SINK);
            assertEquals(SyncStatus.Kind.SERVER_NEWER, ops1.status(c1, root, fl1, ops1.scan(fl1, null, () -> false)).kind());

            // --- PC1: selective pull of WeakAuras data only, keep mode
            LocalScan s1b = ops1.scan(fl1, null, () -> false);
            Msg.PullPlan p2 = ops1.pullPlan(c1, root, fl1, s1b, new PullSelection(List.of(), List.of("WeakAuras"), false), PullMode.KEEP);
            assertEquals(1, p2.editedFiles().size());
            assertEquals(0, p2.removedFiles().size());
            assertFalse(p2.selectsEverything());
            ops1.pullApply(c1, root, fl1, s1b, p2, new TransferControl(), SINK, null);
            assertEquals("WeakAurasSaved = {changed=true}", Files.readString(f1.resolve("WTF/Account/ACC/SavedVariables/WeakAuras.lua")));
            assertEquals(SyncStatus.Kind.UP_TO_DATE, ops1.status(c1, root, fl1, ops1.scan(fl1, null, () -> false)).kind());
            assertNotNull(login);
            c1.close();
            c2.close();
        } finally {
            Files.writeString(l.stopFlag(), "stop");
            st.join(20_000);
        }
    }
}
