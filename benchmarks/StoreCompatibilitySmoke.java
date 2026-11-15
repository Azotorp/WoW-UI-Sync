import io.github.azotorp.wowuisync.server.config.*;
import io.github.azotorp.wowuisync.server.db.*;
import io.github.azotorp.wowuisync.server.security.*;
import io.github.azotorp.wowuisync.server.sync.*;
import io.github.azotorp.wowuisync.common.*;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.*;
import io.github.azotorp.wowuisync.common.object.ObjectFiles;
import java.nio.file.*;
import java.util.*;
/** Creates/reads a synthetic 1.0 store using a separately supplied JAR. Never use on production. */
public class StoreCompatibilitySmoke {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[1]).toAbsolutePath();DataLayout layout=new DataLayout(root);
        if(args[0].equals("create")) {
            if(Files.exists(root))throw new IllegalArgumentException("Fixture directory must not exist");
            Files.createDirectories(root);ServerConfig config=new ServerConfig();config.dataDir=root.toString();config.bindAddress="127.0.0.1";
            try(var socket=new java.net.ServerSocket(0)){config.port=socket.getLocalPort();}
            ConfigIO.write(layout.config(),config);TlsKeys.generate(layout.keystore(),"127.0.0.1");
            Files.createDirectories(layout.keys());Files.copy(Path.of(args[2]),layout.keyFile("fixture-root"));
            try(ServerDb db=ServerDb.open(layout.db(),layout.dbBackups())) {
                db.insertAccount(new AccountRow("fixture-root","root",Role.ROOT,null,null,AccountRow.AUTH_KEY,null,null,null,"ssh-ed25519",1L,null,null,null,0,null,0));
                byte[] bytes="## Title: Synthetic Store Probe\n".getBytes();String sha=Hashing.sha256Hex(bytes);
                Storage storage=new Storage(layout);storage.storeObject("fixture-root","forever",sha,bytes,true);
                Manifest manifest=new Manifest(List.of(new ManifestEntry("Interface/AddOns/Fixture/Fixture.toc",bytes.length,sha)));
                storage.writeManifest("fixture-root","forever",new Storage.StoredManifest(manifest.fingerprint(),manifest.entries(),1L,"fixture","root"));
                db.putProfileState(new ProfileStateRow("fixture-root","forever",manifest.fingerprint(),Files.size(storage.objectPath("fixture-root","forever",sha)),1L,"fixture","root"));
            }
        }
        try(ServerDb db=ServerDb.open(layout.db(),layout.dbBackups())) {
            var account=db.root().orElseThrow();if(!account.id().equals("fixture-root")||!account.authMethod().equals(AccountRow.AUTH_KEY))throw new AssertionError("Identity changed");
            Storage storage=new Storage(layout);var stored=storage.readManifest("fixture-root","forever");
            for(var entry:stored.entries())if(!Hashing.sha256Hex(ObjectFiles.read(storage.objectPath("fixture-root","forever",entry.sha256()))).equals(entry.sha256()))throw new AssertionError("Object mismatch");
            System.out.println("fingerprint="+stored.fingerprint());
            System.out.println("tls="+TlsKeys.fingerprint(TlsKeys.load(layout.keystore())));
            System.out.println("publicKey="+Hashing.sha256Hex(Files.readAllBytes(layout.keyFile("fixture-root"))));
        }
    }
}
