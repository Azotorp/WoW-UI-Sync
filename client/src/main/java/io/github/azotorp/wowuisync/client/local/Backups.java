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
        throw new UserException("Local folder backups are retired. Use a server snapshot.");
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
        for (String suffix : fromDb.keySet()) bySuffix.putIfAbsent(suffix,fromDb.get(suffix));
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
        throw new UserException("Legacy backups are read-only. Import through a reviewed server snapshot before restoring.");
    }

    public static String describe(BackupRecord b) {
        String r = b.reason();
        String nice = r == null ? "Unknown" : Character.toUpperCase(r.charAt(0)) + r.substring(1);
        return b.account() + " · " + io.github.azotorp.wowuisync.client.ui.StatusView.whenLong(b.createdAt()) + " · " + nice;
    }
}
