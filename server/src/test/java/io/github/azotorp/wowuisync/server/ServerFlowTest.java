package io.github.azotorp.wowuisync.server;

import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.addon.PullMode;
import io.github.azotorp.wowuisync.common.addon.PullSelection;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.crypto.Passwords;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.pack.PackKind;
import io.github.azotorp.wowuisync.common.pack.PackReader;
import io.github.azotorp.wowuisync.common.pack.PackWriter;
import io.github.azotorp.wowuisync.common.protocol.Frame;
import io.github.azotorp.wowuisync.common.protocol.InviteCode;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.protocol.ProtocolException;
import io.github.azotorp.wowuisync.common.protocol.Wire;
import io.github.azotorp.wowuisync.server.config.ConfigIO;
import io.github.azotorp.wowuisync.server.config.DataLayout;
import io.github.azotorp.wowuisync.server.config.ServerConfig;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.db.ServerDb;
import io.github.azotorp.wowuisync.server.security.TlsKeys;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ServerFlowTest {
    @TempDir
    Path dir;

    @Test
    void endToEnd() throws Exception {
        DataLayout l = new DataLayout(dir);
        int port;
        try (ServerSocket ss = new ServerSocket(0)) {
            port = ss.getLocalPort();
        }
        ServerConfig cfg = new ServerConfig();
        cfg.dataDir = dir.toString();
        cfg.port = port;
        cfg.bindAddress = "127.0.0.1";
        cfg.publicHost = "127.0.0.1";
        ConfigIO.write(l.config(), cfg);
        TlsKeys.generate(l.keystore(), "127.0.0.1");
        try (ServerDb db = ServerDb.open(l.db(), l.dbBackups())) {
            Passwords.Hash h = Passwords.hash("password1".toCharArray());
            db.insertAccount(new AccountRow(UUID.randomUUID().toString(), "root", Role.ROOT, null, null, AccountRow.AUTH_PASSWORD,
                    h.hashB64(), h.saltB64(), h.iterations(), null, System.currentTimeMillis(), null, null, null, 0, null, 0));
        }
        ServerApp app = new ServerApp(l);
        Thread t = new Thread(() -> {
            try {
                app.run();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        t.start();
        Thread.sleep(1500);
        try {
            Wire c = connect(port);
            Msg.HelloOk ok = c.call(new Msg.Hello(Version.PROTOCOL_VERSION, Version.APP_VERSION, Codec.supported(), "dev1", "Test PC"), Msg.HelloOk.class);
            c.setCodec(Codec.byName(ok.codec()));
            Frame bad = c.call(new Msg.LoginPassword("root", "wrongpass"));
            assertEquals("bad_credentials", bad.message(Msg.LoginFail.class).reason());
            Msg.LoginOk login = c.call(new Msg.LoginPassword("root", "password1"), Msg.LoginOk.class);
            String rootId = login.accountId();

            // Push two files.
            byte[] a = "-- addon".repeat(100).getBytes(StandardCharsets.UTF_8);
            byte[] b = "saved = {}".getBytes(StandardCharsets.UTF_8);
            List<ManifestEntry> man = List.of(
                    new ManifestEntry("Interface/AddOns/Foo/Foo.toc", a.length, Hashing.sha256Hex(a)),
                    new ManifestEntry("WTF/Account/X/SavedVariables/Foo.lua", b.length, Hashing.sha256Hex(b)));
            Msg.PushNeed need = c.call(new Msg.PushBegin(rootId, "forever", null, false, man, null, null, "Test PC"), Msg.PushNeed.class);
            assertEquals(2, need.missingHashes().size());
            assertEquals(2, need.newFiles().size());

            Wire d = connect(port);
            d.setCodec(Codec.byName(ok.codec()));
            d.call(new Msg.DataJoin(login.sessionId()), Msg.Ok.class);
            PackWriter w = new PackWriter(PackKind.COMPRESSED, Codec.preferred(), 3);
            w.add("Interface/AddOns/Foo/Foo.toc", Hashing.sha256Hex(a), a);
            w.add("WTF/Account/X/SavedVariables/Foo.lua", Hashing.sha256Hex(b), b);
            byte[] pack = w.build();
            int rid = d.newRequestId();
            d.sendBinary(rid, new Msg.PackUpload(need.pushId()), pack, 0, pack.length);
            d.read().message(Msg.Ok.class);
            Msg.PushDone done = c.call(new Msg.PushCommit(need.pushId()), Msg.PushDone.class);
            assertNotNull(done.fingerprint());

            Msg.StatusResp st = c.call(new Msg.StatusReq(rootId, "forever", null, null), Msg.StatusResp.class);
            assertEquals(done.fingerprint(), st.currentFingerprint());
            assertEquals("Test PC", st.lastPushDevice());

            // Delta push with no changes.
            Msg.PushNeed need2 = c.call(new Msg.PushBegin(rootId, "forever", done.fingerprint(), true, null, List.of(), List.of(), "Test PC"), Msg.PushNeed.class);
            assertTrue(need2.missingHashes().isEmpty());
            c.call(new Msg.PushCancel(need2.pushId()), Msg.Ok.class);

            // Pull plan into an empty folder, fetch.
            Msg.PullPlan plan = c.call(new Msg.PullBegin(rootId, "forever", new PullSelection(List.of("Foo"), List.of("Foo"), true),
                    PullMode.REPLACE, List.of()), Msg.PullPlan.class);
            assertEquals(2, plan.newFiles().size());
            assertTrue(plan.selectsEverything());
            int fid = d.newRequestId();
            d.send(fid, new Msg.Fetch(rootId, "forever", List.of(Hashing.sha256Hex(a), Hashing.sha256Hex(b))));
            Frame pf = d.read();
            assertTrue(pf.message(Msg.PackDownload.class).last());
            assertEquals(2, PackReader.read(pf.payload(), pf.bodyOffset(), pf.bodyLength(), true).entries().size());

            // Large file download in chunks.
            int lid = d.newRequestId();
            d.send(lid, new Msg.FileDownloadChunk(rootId, "forever", Hashing.sha256Hex(a), 10, 0, false));
            Frame lf = d.read();
            Msg.FileDownloadChunk ch = lf.message(Msg.FileDownloadChunk.class);
            assertTrue(ch.last());
            assertEquals(a.length - 10, lf.bodyLength());

            // Accounts and invites.
            Msg.AccountInfo su = c.call(new Msg.AccountCreate("Super1", "superuser", null, "password2", null, null, 1, null), Msg.AccountInfo.class);
            assertEquals("superuser", su.role());
            Msg.InviteCreated inv = c.call(new Msg.InviteCreate("user", 1, null), Msg.InviteCreated.class);
            InviteCode code = InviteCode.decode(inv.code());
            Wire c2 = connect(port);
            c2.call(new Msg.Hello(Version.PROTOCOL_VERSION, Version.APP_VERSION, Codec.supported(), "dev2", "PC2"), Msg.HelloOk.class);
            Msg.AccountInfo redeemed = c2.call(new Msg.InviteRedeem(code.secret(), "Partner", "password3", null, null), Msg.AccountInfo.class);
            assertEquals("user", redeemed.role());
            assertThrows(ProtocolException.class, () -> c2.call(new Msg.InviteRedeem(code.secret(), "Other", "password3", null, null), Msg.AccountInfo.class));
            Msg.LoginOk l2 = c2.call(new Msg.LoginPassword("partner", "password3"), Msg.LoginOk.class);
            assertEquals(1, l2.accessibleAccounts().size());
            assertThrows(ProtocolException.class, () -> c2.call(new Msg.StatusReq(rootId, "forever", null, null), Msg.StatusResp.class));
            Msg.AccountListResp list = c.call(new Msg.AccountList(), Msg.AccountListResp.class);
            assertEquals(3, list.accounts().size());
            // Quota.
            c.call(new Msg.AccountUpdate(redeemed.id(), null, 0L, null, null), Msg.AccountInfo.class);
            ProtocolException q = assertThrows(ProtocolException.class, () -> c2.call(new Msg.PushBegin(redeemed.id(), "forever", null, false, man, null, null, "PC2"), Msg.PushNeed.class));
            assertEquals("quota", q.code());
            c.call(new Msg.AccountDelete(redeemed.id(), "Partner"), Msg.Ok.class);
            c.close();
            d.close();
            c2.close();
        } finally {
            Files.writeString(l.stopFlag(), "stop");
            t.join(20_000);
        }
    }

    private static Wire connect(int port) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        ctx.init(null, new TrustManager[] {new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        SSLSocket s = (SSLSocket) ctx.getSocketFactory().createSocket("127.0.0.1", port);
        s.startHandshake();
        return new Wire(s.getInputStream(), s.getOutputStream(), s);
    }
}
