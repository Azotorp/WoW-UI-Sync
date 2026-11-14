import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.sync.Scanner;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
/** Reproducible real scanner workload. Run with either baseline or candidate shaded client JAR. */
public class ScanBenchmark {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),wow=root.resolve("wow"),db=root.resolve("client.db");
  int count=args.length>1?Integer.parseInt(args[1]):10000;
  if(!Files.exists(wow)) {
   byte[] b=new byte[8192];new Random(7).nextBytes(b);
   for(int i=0;i<count;i++){Path p=wow.resolve("Interface/AddOns/Addon"+(i/100)+"/file"+i+".lua");Files.createDirectories(p.getParent());Files.write(p,b);Files.setLastModifiedTime(p,FileTime.fromMillis(System.currentTimeMillis()-10000));}
   Path p=wow.resolve("WTF/Account/large.lua");Files.createDirectories(p.getParent());byte[] large=new byte[12<<20];new Random(8).nextBytes(large);Files.write(p,large);Files.setLastModifiedTime(p,FileTime.fromMillis(System.currentTimeMillis()-10000));
  }
  ClientDb connection=ClientDb.open(db,root.resolve("backups"));
  for(String scenario:List.of("first","unchanged","restart","one-changed","one-deleted","new-addon","no-change")) {
   if(scenario.equals("one-changed"))Files.writeString(wow.resolve("Interface/AddOns/Addon0/file0.lua"),"changed");
   if(scenario.equals("one-deleted"))Files.delete(wow.resolve("Interface/AddOns/Addon0/file1.lua"));
   if(scenario.equals("new-addon")){Path p=wow.resolve("Interface/AddOns/New/new.lua");Files.createDirectories(p.getParent());Files.writeString(p,"new");}
   long start=System.nanoTime(),read=io("rchar"),written=io("wchar");
   if(scenario.equals("restart")){connection.close();connection=ClientDb.open(db,root.resolve("backups"));}
   var s=Scanner.scan(wow,new Exclusions(List.of()),connection,null,()->false);System.out.printf(Locale.ROOT,"%s files=%d ms=%.3f rchar=%d wchar=%d fingerprint=%s%n",scenario,s.manifest().size(),(System.nanoTime()-start)/1e6,io("rchar")-read,io("wchar")-written,s.manifest().fingerprint());
  }
  connection.close();
 }
 static long io(String key)throws Exception {for(String s:Files.readAllLines(Path.of("/proc/self/io")))if(s.startsWith(key+":"))return Long.parseLong(s.substring(s.indexOf(':')+1).trim());return 0;}
}
