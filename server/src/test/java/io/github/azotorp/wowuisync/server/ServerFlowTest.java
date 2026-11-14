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
            io.github.azotorp.wowuisync.server.maintenance.Readiness.check(l);
            Wire c = connect(port);
            Msg.HelloOk ok = c.call(new Msg.Hello(Version.PROTOCOL_VERSION, Version.APP_VERSION, Codec.supported(), "dev1", "Test PC"), Msg.HelloOk.class);
            c.setCodec(Codec.byName(ok.codec()));
            Frame bad = c.call(new Msg.LoginPassword("root", "wrongpass"));
            assertEquals("bad_credentials", bad.message(Msg.LoginFail.class).reason());
            Msg.LoginOk login = c.call(new Msg.LoginPassword("root", "password1"), Msg.LoginOk.class);
            String rootId = login.accountId();
            assertEquals("Credential verified",c.call(new Msg.LoginPassword("root","password1"),Msg.Ok.class).message());

            // Push two files.
            byte[] a = "-- addon".repeat(100).getBytes(StandardCharsets.UTF_8);
            byte[] b = "saved = {}".getBytes(StandardCharsets.UTF_8);
            List<ManifestEntry> man = List.of(
                    new ManifestEntry("Interface/AddOns/Foo/Foo.toc", a.length, Hashing.sha256Hex(a)),
                    new ManifestEntry("WTF/Account/X/SavedVariables/Foo.lua", b.length, Hashing.sha256Hex(b)));
            // Declared size cannot authorize larger uploaded bytes or arbitrary pack objects.
            var forged=List.of(new ManifestEntry("WTF/forged.lua",1,Hashing.sha256Hex(a)));
            var forgedNeed=c.call(new Msg.PushBegin(rootId,"forever",null,false,forged,null,null,"Test PC"),Msg.PushNeed.class);
            try(Wire forgedData=connect(port)) {
                forgedData.call(new Msg.DataJoin(login.sessionId()),Msg.Ok.class);
                var forgedPack=new PackWriter(PackKind.RAW,Codec.RAW,0);forgedPack.add("",Hashing.sha256Hex(a),a);byte[] bytes=forgedPack.build();
                forgedData.sendBinary(forgedData.newRequestId(),new Msg.PackUpload(forgedNeed.pushId()),bytes,0,bytes.length);
                assertEquals("integrity",assertThrows(ProtocolException.class,()->forgedData.read().message(Msg.Ok.class)).code());
                forgedData.sendBinary(forgedData.newRequestId(),new Msg.FileUploadChunk(forgedNeed.pushId(),Hashing.sha256Hex(a),0,a.length,true,false),a,0,a.length);
                assertThrows(ProtocolException.class,()->forgedData.read().message(Msg.Ok.class));
                assertThrows(ProtocolException.class,()->forgedData.call(new Msg.ResumeQuery(forgedNeed.pushId(),Hashing.sha256Hex(b)),Msg.ResumeOffset.class));
            }
            c.call(new Msg.PushCancel(forgedNeed.pushId()),Msg.Ok.class);
            // Duplicate paths and file/directory collisions are rejected before transfer.
            assertThrows(ProtocolException.class,()->c.call(new Msg.PushBegin(rootId,"forever",null,false,List.of(man.getFirst(),man.getFirst()),null,null,"PC"),Msg.PushNeed.class));
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

            // A physical hash shared inside an account does not grant access from a different flavor.
            d.send(d.newRequestId(),new Msg.Fetch(rootId,"retail",List.of(Hashing.sha256Hex(a))));
            assertThrows(ProtocolException.class,()->d.read().message(Msg.PackDownload.class));
            try(Wire oldClient=connect(port)) {
                var incompatible=oldClient.call(new Msg.Hello(1,"1.0.0",Codec.supported(),"old","old"));
                assertEquals("version",incompatible.message(Msg.LoginFail.class).reason());
            }
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
            // Simultaneous single-use invite redemption creates exactly one account.
            Msg.InviteCreated race=c.call(new Msg.InviteCreate("user",1,null),Msg.InviteCreated.class);InviteCode raceCode=InviteCode.decode(race.code());
            var winners=new java.util.concurrent.atomic.AtomicInteger();java.util.List<java.util.concurrent.CompletableFuture<Void>> racers=new java.util.ArrayList<>();
            for(String name:List.of("RaceOne","RaceTwo"))racers.add(java.util.concurrent.CompletableFuture.runAsync(()->{
                try(Wire wire=connect(port)) {
                    wire.call(new Msg.Hello(Version.PROTOCOL_VERSION,Version.APP_VERSION,Codec.supported(),name,name),Msg.HelloOk.class);
                    try{wire.call(new Msg.InviteRedeem(raceCode.secret(),name,"password4",null,null),Msg.AccountInfo.class);winners.incrementAndGet();}
                    catch(ProtocolException expected){}
                }catch(Exception error){throw new RuntimeException(error);}
            }));
            java.util.concurrent.CompletableFuture.allOf(racers.toArray(java.util.concurrent.CompletableFuture[]::new)).join();assertEquals(1,winners.get());
            // Quotas include reservations held by other active sessions.
            Msg.AccountInfo limited=c.call(new Msg.AccountCreate("QuotaUser","user",null,"password5",null,1L,null,null),Msg.AccountInfo.class);
            try(Wire one=connect(port);Wire two=connect(port)) {
                for(Wire wire:List.of(one,two)){wire.call(new Msg.Hello(Version.PROTOCOL_VERSION,Version.APP_VERSION,Codec.supported(),"quota","quota"),Msg.HelloOk.class);wire.call(new Msg.LoginPassword("QuotaUser","password5"),Msg.LoginOk.class);}
                byte[] part1=new byte[600_000],part2=new byte[600_000];part2[0]=1;
                var req1=List.of(new ManifestEntry("WTF/one.lua",part1.length,Hashing.sha256Hex(part1)));
                var req2=List.of(new ManifestEntry("WTF/two.lua",part2.length,Hashing.sha256Hex(part2)));
                var pending=one.call(new Msg.PushBegin(limited.id(),"forever",null,false,req1,null,null,"one"),Msg.PushNeed.class);
                assertEquals("quota",assertThrows(ProtocolException.class,()->two.call(new Msg.PushBegin(limited.id(),"forever",null,false,req2,null,null,"two"),Msg.PushNeed.class)).code());
                one.call(new Msg.PushCancel(pending.pushId()),Msg.Ok.class);
            }
            c.close();
            d.close();
            c2.close();
        } finally {
            Files.writeString(l.stopFlag(), "stop");
            t.join(20_000);
            assertFalse(t.isAlive(), "Server did not stop");
            assertTrue(java.util.Arrays.stream(java.util.logging.Logger.getLogger("").getHandlers())
                    .noneMatch(h -> h instanceof java.util.logging.FileHandler), "Server log remains open");
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
