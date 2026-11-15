import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.sync.Scanner;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import java.nio.file.*;
import java.util.List;
/** Standalone harness: compile once and run separately against each release's client JAR. */
public class ScanBenchmark {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),database=Path.of(args[1]);
        try(ClientDb db=ClientDb.open(database,database.getParent().resolve("backups"))) {
            for(int i=0;i<3;i++) {
                long start=System.nanoTime();var result=Scanner.scan(root,new Exclusions(List.of()),db,null,()->false);
                System.out.printf(java.util.Locale.ROOT,"%s,%d,%d,%d,%.6f%n",args[2],i,result.manifest().size(),result.manifest().totalBytes(),(System.nanoTime()-start)/1e9);
            }
        }
    }
}
