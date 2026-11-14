package io.github.azotorp.wowuisync.server;
import io.github.azotorp.wowuisync.common.*;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.json.Json;
import io.github.azotorp.wowuisync.server.maintenance.ReleaseManager;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;
class ReleaseTest {
 @TempDir Path dir;
 private byte[] jar(String main)throws Exception{
  Manifest manifest=new Manifest();manifest.getMainAttributes().putValue("Manifest-Version","1.0");manifest.getMainAttributes().putValue("Implementation-Version",Version.APP_VERSION);manifest.getMainAttributes().putValue("Main-Class",main);
  var bytes=new ByteArrayOutputStream();try(var jar=new JarOutputStream(bytes,manifest)){}return bytes.toByteArray();
 }
 private Path archive(boolean tamper)throws Exception{
  Map<String,byte[]> files=new TreeMap<>();files.put("LICENSE","GPL".getBytes());for(String n:List.of("README.md","CHANGELOG.md","ENGINEERING-REPORT.md"))files.put(n,n.getBytes());files.put("client/README.txt","client".getBytes());files.put("server/README.txt","server".getBytes());files.put("server/wow-ui-sync.service","unit".getBytes());
  files.put("client/wow-ui-sync.client.jar",jar("io.github.azotorp.wowuisync.client.ClientMain"));files.put("server/wow-ui-sync.server.jar",jar("io.github.azotorp.wowuisync.server.ServerMain"));
  for(String script:List.of("setup","update","start","stop","status","rollback","verify","reset-root","common"))for(String ext:List.of(".sh",".bat"))files.put("server/"+script+ext,"wrapper".getBytes());
  Map<String,String> hashes=new TreeMap<>();files.forEach((n,b)->hashes.put(n,Hashing.sha256Hex(b)));
  files.put("release-manifest.json",Json.toBytes(new ReleaseManager.Release(Version.APP_VERSION,2,2,2,"alpha",hashes)));
  StringBuilder sums=new StringBuilder();files.forEach((n,b)->sums.append(Hashing.sha256Hex(b)).append("  ").append(n).append('\n'));files.put("SHA256SUMS",sums.toString().getBytes());
  if(tamper)files.put("server/start.sh","tampered".getBytes());
  Path archive=dir.resolve(tamper?"bad.zip":"good.zip");try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){for(var e:files.entrySet()){zip.putNextEntry(new ZipEntry(e.getKey()));zip.write(e.getValue());zip.closeEntry();}}return archive;
 }
 @Test void exactLayoutAndChecksums()throws Exception{assertEquals(29,ReleaseManager.inspect(archive(false)).size());assertThrows(Exception.class,()->ReleaseManager.inspect(archive(true)));}
 @Test void zipTraversalRejected()throws Exception{
  Path archive=dir.resolve("escape.zip");try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){zip.putNextEntry(new ZipEntry("../server.json"));zip.write("secret".getBytes());zip.closeEntry();}
  assertThrows(Exception.class,()->ReleaseManager.inspect(archive));assertFalse(Files.exists(dir.getParent().resolve("server.json")));
 }
 @Test void trustedSignatureAndExplicitUnsignedPolicy()throws Exception{
  Path archive=archive(false);KeyPair pair=KeyPairGenerator.getInstance("Ed25519").generateKeyPair();Signature signer=Signature.getInstance("Ed25519");signer.initSign(pair.getPrivate());signer.update(Files.readAllBytes(archive));Path sig=dir.resolve("release.sig");Files.write(sig,signer.sign());
  Path key=dir.resolve("public.pem");Files.writeString(key,"-----BEGIN PUBLIC KEY-----\n"+Base64.getEncoder().encodeToString(pair.getPublic().getEncoded())+"\n-----END PUBLIC KEY-----\n");ReleaseManager.signature(archive,sig,key);
  Files.write(sig,new byte[64]);assertThrows(Exception.class,()->ReleaseManager.signature(archive,sig,key));
  String[] options={"update","--data",dir.resolve("data").toString(),"--from",archive.toString(),"--check"};assertThrows(Exception.class,()->ReleaseManager.command(options));
  ReleaseManager.command(new String[]{"update","--data",dir.resolve("data").toString(),"--from",archive.toString(),"--check","--allow-unsigned-local","--sha256",Hashing.sha256Hex(archive)});assertFalse(Files.exists(dir.resolve("data")));
 }
}
