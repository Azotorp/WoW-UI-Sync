import jdk.jfr.*;
import jdk.jfr.consumer.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.sync.Scanner;
import io.github.azotorp.wowuisync.common.path.Exclusions;
/** Diagnostic JFR run. Its instrumentation overhead is excluded from benchmark comparisons. */
public class ProfileScan {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]),recording=root.resolve("scan.jfr");
  if(args.length<2)try(var db=ClientDb.open(root.resolve("client.db"),root.resolve("backups"));var r=new Recording()) {
   r.enable("jdk.FileRead").withThreshold(Duration.ZERO);r.enable("jdk.FileWrite").withThreshold(Duration.ZERO);
   r.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(5));r.start();
   Scanner.scan(root.resolve("wow"),new Exclusions(List.of()),db,null,()->false);
   r.stop();r.dump(recording);
  }
  Map<String,long[]> io=new TreeMap<>();Map<String,Integer> cpu=new HashMap<>();
  try(var events=new RecordingFile(recording)) {
   while(events.hasMoreEvents()) {
    var e=events.readEvent();String name=e.getEventType().getName();
    if(name.equals("jdk.FileRead")||name.equals("jdk.FileWrite")) {
     String path=Objects.toString(e.getString("path"),"<unknown>");String area=path.contains("partial")?"staging":path.contains("/wow/")?"source":"database/runtime";
     var row=io.computeIfAbsent(name+" "+area,k->new long[3]);row[0]++;row[1]+=e.getLong(name.endsWith("Read")?"bytesRead":"bytesWritten");row[2]+=e.getDuration().toNanos();
    } else if(name.equals("jdk.ExecutionSample")&&e.getStackTrace()!=null) {
     String category="other/runtime";
     for(var frame:e.getStackTrace().getFrames()) {
      String type=frame.getMethod().getType().getName();
      if(type.contains("SyncPaths")){category="path validation";break;}
      if(type.contains("ContentChunker")){category="chunking";break;}
      if(type.contains("SHA2")||type.contains("Hashing")){category="hashing";break;}
      if(type.contains("sqlite")){category="SQLite";break;}
      if(type.contains("ScanCache")){category="metadata/cache";break;}
     }
     cpu.merge(category,1,Integer::sum);
    }
   }
  }
  io.forEach((key,v)->System.out.printf(Locale.ROOT,"%s events=%d bytes=%d ms=%.3f%n",key,v[0],v[1],v[2]/1e6));
  new TreeMap<>(cpu).forEach((key,v)->System.out.println("CPU samples "+key+"="+v));
 }
}
