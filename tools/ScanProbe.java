import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.sync.Scanner;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import java.nio.file.*;
import java.util.*;
/** Separate JVM cache-reuse probe after ScanBenchmark has populated the fixture. */
public class ScanProbe {
 public static void main(String[] args)throws Exception {
  Path root=Path.of(args[0]);
  try(var db=ClientDb.open(root.resolve("client.db"),root.resolve("backups"))) {
   Scanner.scan(root.resolve("wow"),new Exclusions(List.of()),db,new Scanner.Progress(){public void update(int d,int t){}public void statistics(Scanner.Statistics s){if(s.persistenceNanos()>0)System.out.println(s);}},()->false);
  }
 }
}
