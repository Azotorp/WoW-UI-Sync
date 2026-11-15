import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.local.Backups;
import io.github.azotorp.wowuisync.client.sync.Scanner;
import io.github.azotorp.wowuisync.common.path.Exclusions;
import java.nio.file.*;
import java.util.List;
/** Run with the same corpus and a fresh DB for each client JAR; scanning is outside the backup timer. */
public class BackupBenchmark {
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]),database=Path.of(args[1]);
        try(ClientDb db=ClientDb.open(database,database.getParent().resolve("old-db"))) {
            Scanner.scan(root,new Exclusions(List.of()),db,null,()->false);
            long started=System.nanoTime();var backup=Backups.create(db,root,"synthetic benchmark",null,null);
            System.out.printf(java.util.Locale.ROOT,"%s,%d,%d,%.6f%n",args[2],backup.fileCount(),backup.sizeBytes(),(System.nanoTime()-started)/1e9);
        }
    }
}
