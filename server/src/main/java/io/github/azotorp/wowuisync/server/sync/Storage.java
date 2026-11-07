package io.github.azotorp.wowuisync.server.sync;

import io.github.azotorp.wowuisync.server.config.DataLayout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/** Per-account object storage on disk (DESIGN.md §9). */
public class Storage {
    protected final DataLayout layout;
    private final Set<String> locked = ConcurrentHashMap.newKeySet();

    public Storage(DataLayout layout) {
        this.layout = layout;
    }

    /** Exclusive lock for operations that must not overlap a transfer (delete). */
    public boolean tryLockAccount(String accountId) {
        return locked.add(accountId);
    }

    public void unlockAccount(String accountId) {
        locked.remove(accountId);
    }

    public boolean isLocked(String accountId) {
        return locked.contains(accountId);
    }

    public void deleteAccountData(String accountId) throws IOException {
        deleteTree(layout.accountDir(accountId));
    }

    public static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> s = Files.walk(root)) {
            for (Path p : (Iterable<Path>) s.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(p);
        }
    }
}
