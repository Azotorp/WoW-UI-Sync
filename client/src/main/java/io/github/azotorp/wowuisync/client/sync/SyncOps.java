package io.github.azotorp.wowuisync.client.sync;

import io.github.azotorp.wowuisync.client.ClientApp;
import io.github.azotorp.wowuisync.client.local.Backups;
import io.github.azotorp.wowuisync.client.local.FileOps;
import io.github.azotorp.wowuisync.client.net.ServerConnection;
import io.github.azotorp.wowuisync.client.util.CancelledException;
import io.github.azotorp.wowuisync.client.util.ClientLog;
import io.github.azotorp.wowuisync.client.util.UserException;
import io.github.azotorp.wowuisync.client.wow.Flavor;
import io.github.azotorp.wowuisync.common.addon.AddonGrouper;
import io.github.azotorp.wowuisync.common.addon.PullMode;
import io.github.azotorp.wowuisync.common.addon.PullSelection;
import io.github.azotorp.wowuisync.common.codec.CompressionPolicy;
import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestDiff;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.util.Sizes;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Status, push and pull logic without UI (DESIGN.md §11, §12). */
public final class SyncOps {
    private final ClientApp app;
    private final BaseStore bases;

    public SyncOps(ClientApp app) {
        this.app = app;
        this.bases = new BaseStore(app.db(), app.paths());
    }

    public BaseStore bases() {
        return bases;
    }

    public LocalScan scan(Flavor f, Scanner.Progress progress, BooleanSupplier cancelled) throws Exception {
        return Scanner.scan(f.path(), app.config().exclusions(f.profileId()), app.db(), progress, cancelled);
    }

    // ------------------------------------------------------------------ status

    public SyncStatus status(ServerConnection c, Msg.AccountInfo account, Flavor f, LocalScan scan) throws Exception {
        String hp = c.hostPort();
        String base = bases.fingerprint(hp, account.id(), f.profileId());
        String local = scan.manifest().fingerprint();
        Msg.StatusResp r = c.call(new Msg.StatusReq(account.id(), f.profileId(), local, base), Msg.StatusResp.class);
        String cur = r.currentFingerprint();
        SyncStatus.Kind k;
        if (cur == null) {
            k = SyncStatus.Kind.SERVER_EMPTY;
        } else if (local.equals(cur)) {
            if (!cur.equals(base)) bases.set(hp, account.id(), f.profileId(), scan.manifest());
            base = cur;
            k = SyncStatus.Kind.UP_TO_DATE;
        } else if (base == null) {
            k = SyncStatus.Kind.DIFFERENT;
        } else {
            boolean localChanged = !local.equals(base);
            boolean serverChanged = !cur.equals(base);
            if (localChanged && serverChanged) k = SyncStatus.Kind.CONFLICT;
            else if (localChanged) k = SyncStatus.Kind.LOCAL_CHANGES;
            else if (serverChanged) k = SyncStatus.Kind.SERVER_NEWER;
            else k = SyncStatus.Kind.UP_TO_DATE;
        }
        return new SyncStatus(k, scan, r, base);
    }

    /** Differences between this PC and the server copy, for "Show details". */
    public ManifestDiff serverDiff(ServerConnection c, Msg.AccountInfo account, Flavor f, LocalScan scan) throws IOException {
        Msg.ManifestResp m = c.call(new Msg.ManifestGet(account.id(), f.profileId()), Msg.ManifestResp.class);
        return ManifestDiff.between(scan.manifest(), new Manifest(m.entries() == null ? List.of() : m.entries()));
    }

    // ------------------------------------------------------------------ push

    /** Starts a push: delta vs base when the server still has the base, else the full manifest. */
    public Msg.PushNeed pushBegin(ServerConnection c, Msg.AccountInfo account, Flavor f, LocalScan scan, String serverFingerprint)
            throws Exception {
        String hp = c.hostPort();
        String base = bases.fingerprint(hp, account.id(), f.profileId());
        Manifest baseManifest = base != null && base.equals(serverFingerprint) ? bases.manifest(hp, account.id(), f.profileId()) : null;
        Msg.PushBegin msg;
        if (baseManifest != null) {
            ManifestDiff d = ManifestDiff.between(baseManifest, scan.manifest());
            List<ManifestEntry> ups = new ArrayList<>(d.added());
            ups.addAll(d.edited());
            List<String> removed = new ArrayList<>();
            for (ManifestEntry e : d.removed()) removed.add(e.path());
            msg = new Msg.PushBegin(account.id(), f.profileId(), base, true, null, ups, removed, app.config().deviceNickname);
        } else {
            msg = new Msg.PushBegin(account.id(), f.profileId(), base, false, scan.manifest().entries(), null, null,
                    app.config().deviceNickname);
        }
        try {
            return c.call(msg, Msg.PushNeed.class);
        } catch (io.github.azotorp.wowuisync.common.protocol.ProtocolException e) {
            if ("conflict".equals(e.code()) && msg.delta()) {
                return c.call(new Msg.PushBegin(account.id(), f.profileId(), base, false, scan.manifest().entries(), null, null,
                        app.config().deviceNickname), Msg.PushNeed.class);
            }
            throw e;
        }
    }

    /** Uploads what the server needs, commits, and updates the base. */
    public Msg.PushDone pushUpload(ServerConnection c, Msg.AccountInfo account, Flavor f, LocalScan scan, Msg.PushNeed need,
                                   TransferControl control, TransferEngine.Sink sink) throws Exception {
        Map<String, ManifestEntry> byHash = new HashMap<>();
        for (ManifestEntry e : scan.manifest().entries()) byHash.putIfAbsent(e.sha256(), e);
        List<TransferEngine.UploadItem> items = new ArrayList<>();
        long total = 0;
        for (String h : need.missingHashes()) {
            ManifestEntry e = byHash.get(h);
            if (e == null) throw new UserException("Internal error: the server asked for a file this PC does not have.");
            items.add(new TransferEngine.UploadItem(h, e.size(), e.path(), scan.files().get(SyncPaths.key(e.path()))));
            total += e.size();
        }
        CompressionPolicy policy = new CompressionPolicy();
        try {
            policy.cacheView().putAll(app.db().sampleCache());
        } catch (SQLException ignored) {
            // cache is optional
        }
        TransferStats stats = new TransferStats(items.size(), total);
        new TransferEngine(c, app.config().dataConnections, control, sink, stats).upload(need.pushId(), items, need.partialOffsets(), policy);
        try {
            app.db().saveSampleCache(policy.cacheView());
        } catch (SQLException ignored) {
            // optional
        }
        Msg.PushDone done = c.call(new Msg.PushCommit(need.pushId()), Msg.PushDone.class);
        if (done.fingerprint().equals(scan.manifest().fingerprint())) {
            bases.set(c.hostPort(), account.id(), f.profileId(), scan.manifest());
        }
        ClientLog.LOG.info("Push done to " + account.username() + ": " + scan.manifest().size() + " files");
        return done;
    }

    public void pushCancel(ServerConnection c, Msg.PushNeed need) {
        try {
            c.call(new Msg.PushCancel(need.pushId()), Msg.Ok.class);
        } catch (IOException ignored) {
            // the server drops it when the session ends
        }
    }

    // ------------------------------------------------------------------ pull

    /** Source manifest plus its parsed .toc files (for grouping). */
    public record PullSource(Manifest manifest, String fingerprint, Map<String, byte[]> tocs) {}

    public PullSource pullSource(ServerConnection c, Msg.AccountInfo source, Flavor f) throws Exception {
        Msg.ManifestResp m = c.call(new Msg.ManifestGet(source.id(), f.profileId()), Msg.ManifestResp.class);
        if (m.fingerprint() == null) throw new UserException(source.username() + " has nothing stored for " + f.displayName() + " yet.");
        Manifest man = new Manifest(m.entries());
        Map<String, ManifestEntry> tocEntries = AddonGrouper.tocEntries(man);
        List<String> hashes = new ArrayList<>();
        for (ManifestEntry e : tocEntries.values()) if (e.size() < (1 << 20)) hashes.add(e.sha256());
        Map<String, byte[]> byHash = TransferEngine.fetchToMemory(c, source.id(), f.profileId(), new ArrayList<>(new HashSet<>(hashes)));
        Map<String, byte[]> tocs = new HashMap<>();
        for (Map.Entry<String, ManifestEntry> e : tocEntries.entrySet()) {
            byte[] b = byHash.get(e.getValue().sha256());
            if (b != null) tocs.put(e.getKey(), b);
        }
        return new PullSource(man, m.fingerprint(), tocs);
    }

    public Msg.PullPlan pullPlan(ServerConnection c, Msg.AccountInfo source, Flavor f, LocalScan scan, PullSelection sel, PullMode mode)
            throws IOException {
        return c.call(new Msg.PullBegin(source.id(), f.profileId(), sel, mode, scan.manifest().entries()), Msg.PullPlan.class);
    }

    /**
     * Executes a reviewed pull plan: free-space check, local backup, download only differing files, write via temp +
     * atomic rename, apply removals, set the applied account, update or clear bases.
     * Returns true if addon folders changed.
     */
    public boolean pullApply(ServerConnection c, Msg.AccountInfo source, Flavor f, LocalScan scan, Msg.PullPlan plan,
                             TransferControl control, TransferEngine.Sink sink, Backups.Progress backupProgress) throws Exception {
        Path flavor = f.path();
        List<ManifestEntry> writes = new ArrayList<>(plan.newFiles());
        writes.addAll(plan.editedFiles());

        // Reuse local content with the same hash instead of downloading it.
        Map<String, Path> localByHash = new HashMap<>();
        for (ManifestEntry e : scan.manifest().entries()) localByHash.putIfAbsent(e.sha256(), scan.files().get(SyncPaths.key(e.path())));
        Map<String, Long> needed = new LinkedHashMap<>();
        for (ManifestEntry e : writes) if (!localByHash.containsKey(e.sha256())) needed.putIfAbsent(e.sha256(), e.size());
        long downloadBytes = 0;
        for (long v : needed.values()) downloadBytes += v;
        long writeBytes = 0;
        for (ManifestEntry e : writes) writeBytes += e.size();

        // Free space: staging + new content on the flavor drive (the backup checks its own space).
        long freeFlavor = FileOps.usableSpace(flavor);
        long freeData = FileOps.usableSpace(app.paths().root());
        boolean sameDrive = Files.getFileStore(existing(flavor)).equals(Files.getFileStore(existing(app.paths().root())));
        long needFlavor = writeBytes + (sameDrive ? downloadBytes : 0) + (64L << 20);
        if (needFlavor > freeFlavor) {
            throw new UserException("Not enough free disk space: the pull needs about " + Sizes.human(needFlavor) + " but "
                    + Sizes.human(freeFlavor) + " is free.");
        }
        if (!sameDrive && downloadBytes + (64L << 20) > freeData) {
            throw new UserException("Not enough free disk space for downloads in " + app.paths().root() + ".");
        }

        // Local backup before anything changes.
        Backups.create(app.db(), flavor, Backups.reasonBeforePull(source.username()), source.username(), backupProgress);

        // Download.
        Path staged = app.paths().stagedObjects();
        List<TransferEngine.DownloadItem> items = new ArrayList<>();
        for (Map.Entry<String, Long> e : needed.entrySet()) items.add(new TransferEngine.DownloadItem(e.getKey(), e.getValue()));
        TransferStats stats = new TransferStats(items.size(), downloadBytes);
        new TransferEngine(c, app.config().dataConnections, control, sink, stats).download(source.id(), f.profileId(), items, staged);
        control.checkpoint();

        // Apply writes: temp file next to the target, then atomic rename.
        boolean addonsChanged = false;
        for (ManifestEntry e : writes) {
            Path target = resolve(flavor, e.path());
            Path existingCase = scan.files().get(SyncPaths.key(e.path()));
            if (existingCase != null && !existingCase.equals(target) && Files.exists(existingCase, LinkOption.NOFOLLOW_LINKS)) {
                // Same path with different letter case (case-sensitive file systems): replace the old name.
                Files.delete(existingCase);
            }
            Path src = localByHash.get(e.sha256());
            if (src == null) src = staged.resolve(e.sha256());
            Files.createDirectories(target.getParent());
            Path tmp = target.resolveSibling(target.getFileName() + ".wus-" + UUID.randomUUID().toString().substring(0, 8) + ".tmp");
            Files.copy(src, tmp, StandardCopyOption.REPLACE_EXISTING);
            if (Files.size(tmp) != e.size()) {
                Files.deleteIfExists(tmp);
                throw new UserException("Writing " + e.path() + " failed (size mismatch). Your backup is intact; try again.");
            }
            try {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
            if (SyncPaths.addonFolder(e.path()) != null) addonsChanged = true;
        }
        // Removals.
        for (String p : plan.removedFiles()) {
            Path abs = scan.files().get(SyncPaths.key(p));
            if (abs == null) abs = resolve(flavor, p);
            Path stop = SyncPaths.addonFolder(p) != null ? flavor.resolve("Interface").resolve("AddOns") : flavor.resolve("WTF");
            FileOps.deleteAndPrune(abs, stop);
            if (SyncPaths.addonFolder(p) != null) addonsChanged = true;
        }
        // Staged objects are no longer needed.
        for (String h : needed.keySet()) Files.deleteIfExists(staged.resolve(h));

        app.db().setApplied(flavor.toString(), source.username(), source.id(), c.hostPort());
        bases.clearAll(f.profileId());
        if (plan.selectsEverything()) {
            LocalScan after = scan(f, null, () -> false);
            if (after.manifest().fingerprint().equals(plan.sourceFingerprint())) {
                bases.set(c.hostPort(), source.id(), f.profileId(), after.manifest());
            }
        }
        ClientLog.LOG.info("Pull from " + source.username() + " applied: " + writes.size() + " written, " + plan.removedFiles().size() + " removed");
        return addonsChanged;
    }

    private static Path existing(Path p) {
        Path q = p.toAbsolutePath();
        while (q != null && !Files.exists(q)) q = q.getParent();
        return q;
    }

    /** Resolves a relative synced path inside the flavor folder, refusing anything that escapes it. */
    static Path resolve(Path flavor, String rel) throws UserException {
        if (!SyncPaths.isSafeRelative(rel) || !SyncPaths.isSyncedPath(rel)) throw new UserException("Refusing unsafe path " + rel);
        Path p = flavor.resolve(rel.replace('/', java.io.File.separatorChar)).normalize();
        if (!p.startsWith(flavor.normalize())) throw new UserException("Refusing unsafe path " + rel);
        return p;
    }

    /** Lower-case set helper. */
    static Set<String> lc(List<String> l) {
        Set<String> s = new HashSet<>();
        for (String x : l) s.add(x.toLowerCase(Locale.ROOT));
        return s;
    }

    public static void checkCancelled(TransferControl c) throws CancelledException {
        c.checkpoint();
    }
}
