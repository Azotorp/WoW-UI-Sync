package io.github.azotorp.wowuisync.client.local;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.data.BackupRecord;
import io.github.azotorp.wowuisync.client.data.ClientDb;
import io.github.azotorp.wowuisync.client.util.ClientLog;
import io.github.azotorp.wowuisync.client.util.UserException;
import io.github.azotorp.wowuisync.common.util.Sizes;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Local backups (DESIGN.md §18): full copies of {@code Interface} and {@code WTF} inside the flavor folder, named
 * {@code Interface_<account>_<YYYY-MM-DD_HH-MM-SS>} and {@code WTF_<account>_<YYYY-MM-DD_HH-MM-SS>}.
 */
public final class Backups {
    public static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final Pattern NAME = Pattern.compile("(Interface|WTF)_(.+)_(\\d{4}-\\d{2}-\\d{2}_\\d{2}-\\d{2}-\\d{2})");

    public static final String REASON_MANUAL = "manual";
    public static final String REASON_RESTORE = "before restore";
    public static final String REASON_ADDON_DELETE = "before addon delete";

    public static String reasonBeforePull(String account) {
        return "before pull from " + account;
    }

    /** Progress for long copies. */
    public interface Progress {
        void update(String what, long files, long totalFiles);
    }

    private Backups() {}

    /** The applied account for a flavor folder, or "local". */
    public static String appliedAccount(ClientDb db, Path flavor) throws SQLException {
        ClientDb.Applied a = db.applied(flavor.toString());
        return a == null ? "local" : a.accountName();
    }

    /** Makes a full backup. Checks free space first. */
    public static BackupRecord create(ClientDb db, Path flavor, String reason, String sourceAccount, Progress progress)
            throws IOException, SQLException, UserException {
        return create(db,flavor,reason,sourceAccount,progress,true,true,()->false);
    }

    public record Snapshot(int schema, boolean iface, boolean wtf, Map<String,String> hashes) {}
    private static Path snapshotRoot(Path flavor,String suffix) { return flavor.resolve(".wus-backups").resolve(suffix); }
    public static BackupRecord create(ClientDb db, Path flavor, String reason, String sourceAccount, Progress progress,
                                       boolean includeInterface, boolean includeWtf, java.util.function.BooleanSupplier cancelled)
            throws IOException, SQLException, UserException {
        if(!includeInterface && !includeWtf) return null;
        String account=ClientApp.safeName(appliedAccount(db,flavor));
        Path iface=flavor.resolve("Interface"),wtf=flavor.resolve("WTF");
        FileOps.TreeSize si=includeInterface?FileOps.size(iface):new FileOps.TreeSize(0,0);
        FileOps.TreeSize sw=includeWtf?FileOps.size(wtf):new FileOps.TreeSize(0,0);
        long need=si.bytes()+sw.bytes(),total=si.files()+sw.files();
        if(need+(64L<<20)>FileOps.usableSpace(flavor)) throw new UserException("Insufficient space for selected backup categories; pull has not changed live files.");
        LocalDateTime now=LocalDateTime.now();
        String suffix=account+"_"+now.format(STAMP)+"_"+java.util.UUID.randomUUID().toString().substring(0,8);
        Path destination=snapshotRoot(flavor,suffix),stage=flavor.resolve(".wus-backups").resolve(".partial-"+java.util.UUID.randomUUID());
        Files.createDirectories(stage);
        Map<String,String> hashes=new java.util.TreeMap<>();
        long[] done={0};
        try {
            for(String category:List.of("Interface","WTF")) {
                if(category.equals("Interface")?!includeInterface:!includeWtf) continue;
                Path src=flavor.resolve(category),dst=stage.resolve(category);
                if(!Files.exists(src,java.nio.file.LinkOption.NOFOLLOW_LINKS)) continue;
                Files.walkFileTree(src,new java.nio.file.SimpleFileVisitor<>() {
                    private void check() throws IOException { if(cancelled.getAsBoolean()||Thread.currentThread().isInterrupted()) throw new java.io.InterruptedIOException("Backup cancelled; live files unchanged"); }
                    @Override public java.nio.file.FileVisitResult preVisitDirectory(Path d,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        check();Files.createDirectories(dst.resolve(src.relativize(d)));return java.nio.file.FileVisitResult.CONTINUE;
                    }
                    @Override public java.nio.file.FileVisitResult visitFile(Path file,java.nio.file.attribute.BasicFileAttributes attrs) throws IOException {
                        check();if(!attrs.isRegularFile()) throw new IOException("Backup refuses symlinks or special files");
                        Path target=dst.resolve(src.relativize(file));
                        String before=io.github.azotorp.wowuisync.common.hash.Hashing.sha256Hex(file);
                        Files.copy(file,target,java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
                        String copied=io.github.azotorp.wowuisync.common.hash.Hashing.sha256Hex(target);
                        var after=Files.readAttributes(file,java.nio.file.attribute.BasicFileAttributes.class);
                        if(!before.equals(copied)||attrs.size()!=after.size()||!attrs.lastModifiedTime().equals(after.lastModifiedTime())) throw new IOException("A live file changed during backup; close WoW and external tools then retry");
                        hashes.put(category+"/"+src.relativize(file).toString().replace('\\','/'),copied);
                        done[0]++;if(progress!=null)progress.update("Backing up "+category+"/"+src.relativize(file),done[0],total);
                        return java.nio.file.FileVisitResult.CONTINUE;
                    }
                });
            }
            if(cancelled.getAsBoolean()) throw new java.io.InterruptedIOException("Backup cancelled");
            Files.write(stage.resolve("backup.json"),io.github.azotorp.wowuisync.common.json.Json.toBytes(new Snapshot(1,includeInterface,includeWtf,hashes)));
            Files.move(stage,destination,java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            BackupRecord rec=new BackupRecord(flavor.toString(),suffix,account,now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(),reason,sourceAccount,total,need);
            db.addBackup(rec);return rec;
        } catch(IOException|SQLException e) {
            FileOps.deleteTree(stage);
            // A finished snapshot may exist even if recording it failed. Never report it as a successful backup.
            throw e;
        }
    }

    /** Backups for a flavor, newest first: database records plus folders found on disk (reason "unknown"). */
    public static List<BackupRecord> list(ClientDb db, Path flavor) throws IOException, SQLException {
        Map<String, BackupRecord> bySuffix = new HashMap<>();
        Map<String, BackupRecord> fromDb = new HashMap<>();
        for (BackupRecord r : db.backups(flavor.toString())) fromDb.put(r.suffix(), r);
        if (Files.isDirectory(flavor)) {
            try (Stream<Path> s = Files.list(flavor)) {
                for (Path p : (Iterable<Path>) s.filter(Files::isDirectory)::iterator) {
                    Matcher m = NAME.matcher(p.getFileName().toString());
                    if (!m.matches()) continue;
                    String suffix = m.group(2) + "_" + m.group(3);
                    if (bySuffix.containsKey(suffix)) continue;
                    BackupRecord r = fromDb.get(suffix);
                    if (r == null) {
                        long ts;
                        try {
                            ts = LocalDateTime.parse(m.group(3), STAMP).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
                        } catch (DateTimeParseException e) {
                            continue;
                        }
                        r = new BackupRecord(flavor.toString(), suffix, m.group(2), ts, "unknown", null, -1, -1);
                    }
                    bySuffix.put(suffix, r);
                }
            }
        }
        Path snapshots=flavor.resolve(".wus-backups");
        if(Files.isDirectory(snapshots)) try(var entries=Files.list(snapshots)) {
            for(Path entry:(Iterable<Path>)entries::iterator) {
                String suffix=entry.getFileName().toString();
                if(suffix.startsWith(".")||!Files.isRegularFile(entry.resolve("backup.json")))continue;
                BackupRecord record=fromDb.get(suffix);
                if(record==null) {
                    Snapshot state=io.github.azotorp.wowuisync.common.json.Json.fromBytes(Files.readAllBytes(entry.resolve("backup.json")),Snapshot.class);
                    if(state.schema()!=1)continue;
                    record=new BackupRecord(flavor.toString(),suffix,"local",Files.getLastModifiedTime(entry.resolve("backup.json")).toMillis(),"recovered completed snapshot",null,state.hashes().size(),-1);
                    db.addBackup(record);
                }
                bySuffix.put(suffix,record);
            }
        }
        // Records whose folders were removed by hand are dropped.
        for (String s : fromDb.keySet()) if (!bySuffix.containsKey(s)) db.deleteBackup(flavor.toString(), s);
        List<BackupRecord> out = new ArrayList<>(bySuffix.values());
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    public static void delete(ClientDb db, Path flavor, BackupRecord b) throws IOException, SQLException {
        FileOps.deleteTree(snapshotRoot(flavor,b.suffix()));
        FileOps.deleteTree(flavor.resolve("Interface_" + b.suffix()));
        FileOps.deleteTree(flavor.resolve("WTF_" + b.suffix()));
        db.deleteBackup(flavor.toString(), b.suffix());
        ClientLog.LOG.info("Backup deleted: " + b.suffix());
    }

    /**
     * Restore (DESIGN.md §18): new backup of the current state, then make the live folders match the chosen backup,
     * set the applied account, clear bases. The chosen backup stays.
     */
    public static void restore(ClientDb db, Path flavor, String profileId, BackupRecord b, Progress progress)
            throws IOException, SQLException, UserException {
        Path snapshot=snapshotRoot(flavor,b.suffix());
        Path metadata=snapshot.resolve("backup.json");
        if(Files.isRegularFile(metadata)) {
            Snapshot state=io.github.azotorp.wowuisync.common.json.Json.fromBytes(Files.readAllBytes(metadata),Snapshot.class);
            if(state.schema()!=1)throw new UserException("Unsupported backup schema");
            for(var e:state.hashes().entrySet()) {
                Path file=snapshot.resolve(e.getKey()).normalize();
                if(!file.startsWith(snapshot)||!Files.isRegularFile(file)||!io.github.azotorp.wowuisync.common.hash.Hashing.sha256Hex(file).equals(e.getValue())) throw new UserException("Backup integrity check failed; live files unchanged");
            }
            create(db,flavor,REASON_RESTORE,null,progress,state.iface(),state.wtf(),()->false);
            for(String category:List.of("Interface","WTF")) {
                if(category.equals("Interface")?!state.iface():!state.wtf()) continue;
                Path stage=flavor.resolve(".wus-restore-"+java.util.UUID.randomUUID());
                FileOps.copyTree(snapshot.resolve(category),stage,(f,bytes)->{if(progress!=null)progress.update("Restoring "+category,f,-1);});
                FileOps.deleteTree(flavor.resolve(category));
                if(Files.exists(stage))Files.move(stage,flavor.resolve(category),java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            }
        } else {
            Path bi=flavor.resolve("Interface_"+b.suffix()),bw=flavor.resolve("WTF_"+b.suffix());
            if(!Files.isDirectory(bi)&&!Files.isDirectory(bw))throw new UserException("Backup folders missing");
            create(db,flavor,REASON_RESTORE,null,progress);
            // Legacy backups lack category metadata. Restore only present categories.
            if(Files.isDirectory(bi)){FileOps.deleteTree(flavor.resolve("Interface"));FileOps.copyTree(bi,flavor.resolve("Interface"),null);}
            if(Files.isDirectory(bw)){FileOps.deleteTree(flavor.resolve("WTF"));FileOps.copyTree(bw,flavor.resolve("WTF"),null);}
        }
        db.setApplied(flavor.toString(), b.account(), null, null);
        db.clearBases(profileId);
        ClientLog.LOG.info("Restored backup " + b.suffix());
    }

    public static String describe(BackupRecord b) {
        String r = b.reason();
        String nice = r == null ? "Unknown" : Character.toUpperCase(r.charAt(0)) + r.substring(1);
        return b.account() + " · " + io.github.azotorp.wowuisync.client.ui.StatusView.whenLong(b.createdAt()) + " · " + nice;
    }
}
