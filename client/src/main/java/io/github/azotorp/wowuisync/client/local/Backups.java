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
        String account = ClientApp.safeName(appliedAccount(db, flavor));
        Path iface = flavor.resolve("Interface");
        Path wtf = flavor.resolve("WTF");
        FileOps.TreeSize si = FileOps.size(iface);
        FileOps.TreeSize sw = FileOps.size(wtf);
        long need = si.bytes() + sw.bytes();
        long free = FileOps.usableSpace(flavor);
        if (need + (64L << 20) > free) {
            throw new UserException("Not enough free disk space for a backup: it needs " + Sizes.human(need) + " but only "
                    + Sizes.human(free) + " is free on that drive. Delete old backups (Backups) or free up space.");
        }
        LocalDateTime now = LocalDateTime.now();
        String suffix = account + "_" + now.format(STAMP);
        Path bi = flavor.resolve("Interface_" + suffix);
        Path bw = flavor.resolve("WTF_" + suffix);
        int n = 1;
        while (Files.exists(bi) || Files.exists(bw)) {
            // Two backups in the same second: wait a moment for a new timestamp.
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("Interrupted");
            }
            now = LocalDateTime.now();
            suffix = account + "_" + now.format(STAMP);
            bi = flavor.resolve("Interface_" + suffix);
            bw = flavor.resolve("WTF_" + suffix);
            if (++n > 5) throw new IOException("Could not choose a backup folder name.");
        }
        long total = si.files() + sw.files();
        try {
            final long ifFiles = si.files();
            FileOps.copyTree(iface, bi, (f, b) -> { if (progress != null) progress.update("Backing up Interface", f, total); });
            FileOps.copyTree(wtf, bw, (f, b) -> { if (progress != null) progress.update("Backing up WTF", ifFiles + f, total); });
        } catch (IOException e) {
            FileOps.deleteTree(bi);
            FileOps.deleteTree(bw);
            throw new IOException("The backup failed and was removed: " + e.getMessage(), e);
        }
        BackupRecord rec = new BackupRecord(flavor.toString(), suffix, account,
                now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli(), reason, sourceAccount, total, need);
        db.addBackup(rec);
        ClientLog.LOG.info("Backup created: " + suffix + " (" + reason + ")");
        return rec;
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
        // Records whose folders were removed by hand are dropped.
        for (String s : fromDb.keySet()) if (!bySuffix.containsKey(s)) db.deleteBackup(flavor.toString(), s);
        List<BackupRecord> out = new ArrayList<>(bySuffix.values());
        out.sort((a, b) -> Long.compare(b.createdAt(), a.createdAt()));
        return out;
    }

    public static void delete(ClientDb db, Path flavor, BackupRecord b) throws IOException, SQLException {
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
        Path bi = flavor.resolve("Interface_" + b.suffix());
        Path bw = flavor.resolve("WTF_" + b.suffix());
        if (!Files.isDirectory(bi) && !Files.isDirectory(bw)) throw new UserException("That backup's folders are missing.");
        create(db, flavor, REASON_RESTORE, null, progress);
        if (progress != null) progress.update("Restoring", 0, -1);
        FileOps.mirror(bi, flavor.resolve("Interface"), (f, by) -> { if (progress != null) progress.update("Restoring Interface", f, -1); });
        FileOps.mirror(bw, flavor.resolve("WTF"), (f, by) -> { if (progress != null) progress.update("Restoring WTF", f, -1); });
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
