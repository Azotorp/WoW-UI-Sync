package io.github.azotorp.wowuisync.server.security;

import io.github.azotorp.wowuisync.server.config.DataLayout;
import io.github.azotorp.wowuisync.server.util.Logs;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Permission lockdown for sensitive server paths (DESIGN.md §5.5). */
public final class Permissions {
    private static final Set<PosixFilePermission> DIR = PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE = PosixFilePermissions.fromString("rw-------");

    private Permissions() {}

    public static void lockDown(DataLayout l) {
        lockDown(l, false);
    }

    /** {@code quiet}: do not warn (used right after creating the files). */
    public static void lockDown(DataLayout l, boolean quiet) {
        lock(l.root(), quiet);
        lockTree(l.keys(), quiet);
        lockTree(l.tls(), quiet);
        for (String suffix : List.of("", "-wal", "-shm")) {
            Path db = l.root().resolve("wowuisync.db" + suffix);
            if (Files.exists(db)) lock(db, quiet);
        }
        if (Files.exists(l.config())) lock(l.config(), quiet);
    }

    private static void lockTree(Path dir, boolean quiet) {
        if (!Files.exists(dir)) return;
        lock(dir, quiet);
        try (Stream<Path> s = Files.list(dir)) {
            s.forEach(p -> lock(p, quiet));
        } catch (IOException e) {
            Logs.LOG.warning("Could not list " + dir + ": " + e.getMessage());
        }
    }

    /** Restricts {@code p} to its owner; logs a warning if it was looser. */
    public static void lock(Path p, boolean quiet) {
        try {
            if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
                boolean dir = Files.isDirectory(p, LinkOption.NOFOLLOW_LINKS);
                Set<PosixFilePermission> want = dir ? DIR : FILE;
                Set<PosixFilePermission> have = Files.getPosixFilePermissions(p, LinkOption.NOFOLLOW_LINKS);
                if (!have.equals(want)) {
                    Files.setPosixFilePermissions(p, want);
                    if (!quiet) Logs.LOG.warning("Permissions on " + p + " were " + PosixFilePermissions.toString(have) + "; fixed to " + PosixFilePermissions.toString(want));
                }
            } else {
                AclFileAttributeView v = Files.getFileAttributeView(p, AclFileAttributeView.class);
                if (v == null) return;
                UserPrincipal owner = Files.getOwner(p);
                List<AclEntry> current = v.getAcl();
                boolean loose = current.size() != 1 || !current.get(0).principal().equals(owner);
                if (loose) {
                    AclEntry e = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                            .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                            .setFlags(Files.isDirectory(p)
                                    ? EnumSet.of(java.nio.file.attribute.AclEntryFlag.FILE_INHERIT, java.nio.file.attribute.AclEntryFlag.DIRECTORY_INHERIT)
                                    : EnumSet.noneOf(java.nio.file.attribute.AclEntryFlag.class))
                            .build();
                    v.setAcl(List.of(e));
                    if (!quiet) Logs.LOG.warning("Access to " + p + " was not owner-only; restricted it.");
                }
            }
        } catch (IOException | UnsupportedOperationException | SecurityException e) {
            Logs.LOG.warning("Could not restrict permissions on " + p + ": " + e.getMessage());
        }
    }
}
