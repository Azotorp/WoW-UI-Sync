import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.sync.Scanner;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
/** Same source runs against main and candidate JARs. All timings are warm/unspecified OS cache. */
public class ScanWorkload {
 static final Exclusions EX=new Exclusions(List.of());
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),wow=root.resolve("wow");
  if(args.length>1&&args[1].equals("generate")) {
   Random random=new Random(7341);
   for(int i=0;i<35996;i++) {
    byte[] data=new byte[128+random.nextInt(24576)];random.nextBytes(data);
    put(wow.resolve("Interface/AddOns/Addon"+(i/100)+"/f"+i+".lua"),data);
   }
   for(int size:List.of(4,8,12,32)){byte[] data=new byte[size<<20];random.nextBytes(data);put(wow.resolve("WTF/Account/large"+size+".lua"),data);}
   return;
  }
  try(var db=ClientDb.open(root.resolve("client.db"),root.resolve("backups"))) {
   if(args.length>1&&args[1].equals("probe")){run("fresh-process",root,db);return;}
   run("first",root,db);run("unchanged-before-push",root,db);
   put(wow.resolve("Interface/AddOns/Addon0/f0.lua"),"changed".getBytes());run("one-change",root,db);
   put(wow.resolve("Interface/AddOns/Added/new.lua"),"new".getBytes());run("addition",root,db);
   Files.delete(wow.resolve("Interface/AddOns/Addon0/f1.lua"));run("deletion",root,db);
   Files.move(wow.resolve("Interface/AddOns/Added/new.lua"),wow.resolve("Interface/AddOns/Added/renamed.lua"));run("rename",root,db);
  }
 }
 static void put(Path path,byte[] data)throws Exception{Files.createDirectories(path.getParent());Files.write(path,data);Files.setLastModifiedTime(path,FileTime.fromMillis(1600000000000L));}
 static void run(String scenario,Path root,ClientDb db)throws Exception {
  long start=System.nanoTime(),r=io("rchar"),w=io("wchar");
  var scan=Scanner.scan(root.resolve("wow"),EX,db,new Scanner.Progress(){public void update(int d,int t){}public void statistics(Scanner.Statistics s){if(s.persistenceNanos()>0)System.out.println("stats "+scenario+" "+s);}},()->false);
  long elapsed=System.nanoTime()-start;
  long stagingBytes=0,stagingFiles=0;
  if(Files.exists(db.staging()))try(var paths=Files.walk(db.staging())){for(Path p:paths.filter(Files::isRegularFile).toList()){stagingFiles++;stagingBytes+=Files.size(p);}}
  System.out.printf(Locale.ROOT,"RESULT %s files=%d ms=%.3f rchar=%d wchar=%d stagedFiles=%d stagedBytes=%d fingerprint=%s%n",scenario,scan.manifest().size(),elapsed/1e6,io("rchar")-r,io("wchar")-w,stagingFiles,stagingBytes,scan.manifest().fingerprint());
  var digest=io.github.azotorp.wowuisync.common.hash.Hashing.newSha256();
  for(var e:scan.manifest().entries())digest.update(e.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
  System.out.println("REFERENCES "+scenario+" "+io.github.azotorp.wowuisync.common.hash.Hashing.hex(digest.digest()));
 }
 static long io(String key)throws Exception {
  Path p=Path.of("/proc/self/io");if(!Files.exists(p))return -1;
  for(String s:Files.readAllLines(p))if(s.startsWith(key+":"))return Long.parseLong(s.substring(s.indexOf(':')+1).trim());return -1;
 }
}
