import io.github.azotorp.wowuisync.common.*;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.crypto.*;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.object.ObjectFiles;
import io.github.azotorp.wowuisync.common.protocol.*;
import io.github.azotorp.wowuisync.server.config.*;
import io.github.azotorp.wowuisync.server.db.*;
import io.github.azotorp.wowuisync.server.security.TlsKeys;
import io.github.azotorp.wowuisync.server.sync.Storage;
import io.github.azotorp.wowuisync.server.maintenance.*;
import javax.net.ssl.*;
import java.nio.file.*;
import java.net.*;
import java.security.cert.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
/** Real old/new JVMs at the same installed JAR path, using isolated fixtures only. */
public class JarUpgradeRehearsal {
 static void require(boolean b,String m){if(!b)throw new IllegalStateException(m);}
 public static void main(String[] a)throws Exception {
  Path root=Path.of(a[2]).toAbsolutePath();require(!Files.exists(root),"Use a new isolated directory");Files.createDirectories(root);
  DataLayout l=new DataLayout(root.resolve("persistent"));Files.createDirectories(l.root());var cfg=new ServerConfig();cfg.dataDir=l.root().toString();
  try(var socket=new ServerSocket(0)){cfg.port=socket.getLocalPort();}cfg.bindAddress="127.0.0.1";cfg.publicHost="127.0.0.1";ConfigIO.write(l.config(),cfg);TlsKeys.generate(l.keystore(),"localhost");
  String id=UUID.randomUUID().toString();var pw=Passwords.hash("fixture-password".toCharArray());
  try(var db=ServerDb.open(l.db(),l.dbBackups())){db.insertAccount(new AccountRow(id,"root",Role.ROOT,null,null,AccountRow.AUTH_PASSWORD,pw.hashB64(),pw.saltB64(),pw.iterations(),null,1L,null,null,null,0,null,0));}
  try(var c=DriverManager.getConnection("jdbc:sqlite:"+l.db());var s=c.createStatement()){s.execute("DROP TABLE request_cooldowns");s.execute("DROP TABLE verified_emails");s.execute("UPDATE schema_version SET version=1");}
  byte[] bytes="settings preserved through JAR replacement".getBytes();String hash=Hashing.sha256Hex(bytes);ObjectFiles.write(ObjectFiles.shardPath(l.objects(id,"forever"),hash),bytes,Codec.DEFLATE,3);
  var man=new Manifest(List.of(new ManifestEntry("WTF/settings.lua",bytes.length,hash)));
  ManifestIO.write(l.currentManifest(id,"forever"),Codec.DEFLATE.encodeTagged(Json.toBytes(new Storage.StoredManifest(man.fingerprint(),man.entries(),1L,"old pc","root"))));
  byte[] tls=Files.readAllBytes(l.keystore()),config=Files.readAllBytes(l.config());String pin=TlsKeys.fingerprint(TlsKeys.load(l.keystore()));
  Path installed=root.resolve("existing-custom-name.jar");String oldId;
  Files.copy(Path.of(a[0]),installed);Process process=start(installed,l,root.resolve("old.log"));
  try{oldId=login(process,cfg,pin,1);require(oldId.equals(id),"Old account login");}finally{stop(process);}
  Files.copy(Path.of(a[1]),installed,StandardCopyOption.REPLACE_EXISTING);process=start(installed,l,root.resolve("new.log"));
  try{
   require(login(process,cfg,pin,2).equals(id),"Account changed");Readiness.check(l);
   Storage storage=new Storage(l);require(storage.snapshots(id,"forever").size()==1,"Migration baseline absent");require(storage.readManifest(id,"forever").fingerprint().equals(man.fingerprint()),"Head changed");storage.verify(id,"forever",man);
   try(var in=ObjectFiles.openContent(storage.objectPath(id,"forever",hash))){require(Arrays.equals(bytes,in.readAllBytes()),"Restored bytes differ");}
   require(Arrays.equals(tls,Files.readAllBytes(l.keystore())),"TLS identity changed");require(Arrays.equals(config,Files.readAllBytes(l.config())),"Config changed");
  }finally{stop(process);}
  process=start(installed,l,root.resolve("restart.log"));try{require(login(process,cfg,pin,2).equals(id),"Restart lost account");Readiness.check(l);}finally{stop(process);}
  try(var paths=Files.list(l.root().resolve("migration-checkpoints"))){require(paths.count()==1,"Unexpected repeat migration");}
  System.out.println("PASS: same JAR path/startup arguments; v1 login; automatic migration; unchanged account/config/TLS; verified legacy restore; restart without repeat migration.");
 }
 static Process start(Path jar,DataLayout l,Path log)throws Exception{return new ProcessBuilder("java","-jar",jar.toString(),"run","--data",l.root().toString()).redirectErrorStream(true).redirectOutput(log.toFile()).start();}
 static void stop(Process p)throws Exception{p.destroy();if(!p.waitFor(15,TimeUnit.SECONDS)){p.destroyForcibly();throw new IllegalStateException("Shutdown timed out");}require(p.exitValue()==143||p.exitValue()==0,"Server failed: "+p.exitValue());}
 static String login(Process process,ServerConfig cfg,String pin,int protocol)throws Exception {
  Exception last=null;for(int i=0;i<60;i++){
   require(process.isAlive(),"Server exited during startup");
   try{
    SSLContext ctx=SSLContext.getInstance("TLSv1.3");ctx.init(null,new TrustManager[]{new X509TrustManager(){public X509Certificate[] getAcceptedIssuers(){return new X509Certificate[0];}public void checkClientTrusted(X509Certificate[] c,String s){}public void checkServerTrusted(X509Certificate[] c,String s)throws CertificateException{if(!pin.equals(CertFingerprint.of(c[0])))throw new CertificateException("Pin changed");}}},null);
    SSLSocket socket=(SSLSocket)ctx.getSocketFactory().createSocket();socket.connect(new InetSocketAddress("127.0.0.1",cfg.port),500);socket.setSoTimeout(2000);socket.startHandshake();
    try(Wire wire=new Wire(socket.getInputStream(),socket.getOutputStream(),socket)){
     Msg.HelloOk hello=wire.call(new Msg.Hello(protocol,protocol==1?"1.0.0-SNAPSHOT":Version.APP_VERSION,Codec.supported(),UUID.randomUUID().toString(),"upgrade fixture"),Msg.HelloOk.class);wire.setCodec(Codec.byName(hello.codec()));
     return wire.call(new Msg.LoginPassword("root","fixture-password"),Msg.LoginOk.class).accountId();
    }
   }catch(Exception e){last=e;Thread.sleep(100);}
  }throw last;
 }
}
