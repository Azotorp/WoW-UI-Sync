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

    private final java.util.List<ClientApp> clients = new java.util.ArrayList<>();

    @org.junit.jupiter.api.AfterEach
    void closeClients() throws Exception {
        for (ClientApp app : clients) {
            try { app.disconnect(); } finally { app.db().close(); }
        }
    }

    private ClientApp client(String name) throws Exception {
        ClientPaths paths = new ClientPaths(dir.resolve(name + "-data"));
        ClientConfig cfg = ClientConfig.load(paths.config());
        cfg.deviceNickname = name;
        cfg.installRoot = dir.resolve(name + "-wow").toString();
        ClientApp app = new ClientApp(paths, cfg, ClientDb.open(paths.db(), paths.dbBackups()));
        clients.add(app);
        return app;
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
                String external=System.getProperty("wowuisync.integrationServerJar");
                if(external==null)new ServerApp(l).run();
                else {
                    Process server=new ProcessBuilder("java","-jar",external,"run","--data",sd.toString()).redirectErrorStream(true).redirectOutput(dir.resolve("external-server.log").toFile()).start();
                    assertEquals(0,server.waitFor(),"External server exit");
                }
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
            var failIfScanning=new Scanner.Progress(){public void update(int d,int t){fail("Empty head must not scan local files");}};
            assertNull(ops1.automaticStatus(c1,root,fl1,failIfScanning,()->false));
            assertTrue(pc1.db().scanCache(f1.toAbsolutePath().toString()).isEmpty());
            assertFalse(Files.exists(pc1.paths().stagedObjects()));
            // Pending recovery must remain visible even with no server head.
            Path journal=ApplyJournal.journal(pc1.paths().root(),f1);Files.createDirectories(journal.getParent());Files.writeString(journal,"pending");
            assertThrows(Exception.class,()->ops1.automaticStatus(c1,root,fl1,failIfScanning,()->false));Files.delete(journal);
            LocalScan s1 = ops1.scan(fl1, null, () -> false);
            assertEquals(SyncStatus.Kind.SERVER_EMPTY, ops1.status(c1, root, fl1, s1).kind());
            Msg.PushNeed need = ops1.pushBegin(c1, root, fl1, s1, null);
            assertEquals(s1.manifest().size(), need.newFiles().size());
            var cancelledNeed=need;
            var mutateBeforeCommit=new TransferEngine.Sink(){
                public void stats(TransferStats stats){}
                public void connectionLost(String message){fail(message);}
                public void phase(String phase){if(phase.contains("strict source recheck"))try{Files.writeString(f1.resolve("Interface/AddOns/Details/Details.toc"),"external updater changed reviewed content");}catch(Exception e){throw new RuntimeException(e);}}
            };
            assertThrows(Exception.class,()->ops1.pushUpload(c1,root,fl1,s1,cancelledNeed,new TransferControl(),mutateBeforeCommit));
            assertNull(ops1.serverHead(c1,root,fl1).currentFingerprint());
            ops1.pushCancel(c1,cancelledNeed);
            Files.writeString(f1.resolve("Interface/AddOns/Details/Details.toc"),"## Title: Details\n");
            need=ops1.pushBegin(c1,root,fl1,s1,null);
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
            assertEquals(0, Backups.list(pc2.db(), f2).size());
            var history=c2.call(new Msg.SnapshotList(root.id(),"forever"),Msg.SnapshotListResp.class);
            assertEquals(2,history.snapshots().size());
            assertTrue(history.snapshots().stream().anyMatch(x->"before_pull_recovery".equals(x.sourceType())&&!x.current()));
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
            // Restore the original snapshot; all later history remains retained.
            var snapshots=c1.call(new Msg.SnapshotList(root.id(),"forever"),Msg.SnapshotListResp.class).snapshots();
            var first=snapshots.stream().filter(x->"push".equals(x.sourceType())).min(java.util.Comparator.comparingLong(Msg.SnapshotInfo::createdAt)).orElseThrow();
            var firstManifest=c1.call(new Msg.SnapshotGet(root.id(),"forever",first.id()),Msg.ManifestResp.class);
            var local=ops1.scan(fl1,null,()->false);
            var diff=io.github.azotorp.wowuisync.common.manifest.ManifestDiff.between(local.manifest(),new io.github.azotorp.wowuisync.common.manifest.Manifest(firstManifest.entries()));
            var restorePlan=new Msg.PullPlan(diff.added(),diff.edited(),diff.removed().stream().map(io.github.azotorp.wowuisync.common.manifest.ManifestEntry::path).toList(),diff.transferBytes(),first.fingerprint(),true);
            ops1.apply(c1,root,fl1,local,restorePlan,new TransferControl(),SINK,"before_restore");
            assertEquals("WeakAurasSaved = {}",Files.readString(f1.resolve("WTF/Account/ACC/SavedVariables/WeakAuras.lua")));
            assertTrue(c1.call(new Msg.SnapshotList(root.id(),"forever"),Msg.SnapshotListResp.class).snapshots().size()>snapshots.size());
            // The same snapshot id cannot be accessed using another flavor.
            var mismatch=assertThrows(io.github.azotorp.wowuisync.common.protocol.ProtocolException.class,()->c1.call(new Msg.SnapshotGet(root.id(),"retail",first.id()),Msg.ManifestResp.class));
            assertEquals("FLAVOR_MISMATCH",mismatch.code());
            c1.call(new Msg.SnapshotVerify(root.id(),"forever",first.id()),Msg.Ok.class);
            assertEquals(0,Backups.list(pc1.db(),f1).size());
            // Fresh Retail install without WTF/Interface is recognized and independently syncs.
            Path retail=dir.resolve("pc1-wow/_retail_");write(retail.resolve(".flavor.info"),"x".getBytes());
            assertTrue(io.github.azotorp.wowuisync.client.wow.FlavorDetector.detect(retail.getParent()).stream().anyMatch(x->x.profileId().equals("retail")&&x.enabled()));
            Flavor retailFlavor=new Flavor("_retail_",retail,"retail",true);
            write(retail.resolve("WTF/retail.lua"),"retail".getBytes());
            var retailScan=ops1.scan(retailFlavor,null,()->false);var retailNeed=ops1.pushBegin(c1,root,retailFlavor,retailScan,null);
            ops1.pushUpload(c1,root,retailFlavor,retailScan,retailNeed,new TransferControl(),SINK);
            assertNotEquals(ops1.status(c1,root,fl1,ops1.scan(fl1,null,()->false)).server().currentFingerprint(),ops1.status(c1,root,retailFlavor,retailScan).server().currentFingerprint());
            // Recovery history exists without a shared head. An actual empty snapshot is distinct.
            Path era=dir.resolve("pc1-wow/_classic_era_");Files.createDirectories(era);
            Flavor eraFlavor=new Flavor("_classic_era_",era,"classic_era",true);
            var empty=ops1.scan(eraFlavor,null,()->false);
            var saved=ops1.recoverySnapshot(c1,eraFlavor,empty,new TransferControl(),SINK,"manual");
            assertNull(ops1.automaticStatus(c1,root,eraFlavor,failIfScanning,()->false));
            assertNotNull(c1.call(new Msg.SnapshotGet(root.id(),"classic_era",saved.snapshotId()),Msg.ManifestResp.class));
            // The server changes after an absent-head probe: the old expected head cannot overwrite it.
            var firstEmpty=ops1.pushBegin(c1,root,eraFlavor,empty,null);
            ops1.pushUpload(c1,root,eraFlavor,empty,firstEmpty,new TransferControl(),SINK);
            assertNotNull(ops1.automaticStatus(c1,root,eraFlavor,null,()->false));
            assertThrows(io.github.azotorp.wowuisync.common.protocol.ProtocolException.class,()->ops1.pushBegin(c1,root,eraFlavor,empty,null));
            c1.close();
            assertThrows(Exception.class,()->ops1.automaticStatus(c1,root,eraFlavor,failIfScanning,()->false));
            assertNotNull(login);
            c1.close();
            c2.close();
        } finally {
            Files.writeString(l.stopFlag(), "stop");
            st.join(20_000);
        }
    }
}
