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

    private String localProfile(Flavor f) { return f.profileId()+"@"+app.config().installationId(f.path()); }

    public BaseStore bases() {
        return bases;
    }

    public LocalScan scan(Flavor f, Scanner.Progress progress, BooleanSupplier cancelled) throws Exception {
        if(Files.exists(ApplyJournal.journal(app.paths().root(),f.path()))) throw new UserException("An interrupted apply needs recovery. Open Recovery before syncing.");
        return Scanner.verified(f.path(), app.config().exclusions(f.profileId()), app.db(), progress, cancelled);
    }
    /** Advisory status scan only; reviewed transfer/manual snapshot operations use scan(). */
    public LocalScan statusScan(Flavor f,Scanner.Progress progress,BooleanSupplier cancelled) throws Exception {
        if(Files.exists(ApplyJournal.journal(app.paths().root(),f.path())))throw new UserException("An interrupted apply needs recovery. Open Recovery before syncing.");
        return Scanner.scan(f.path(),app.config().exclusions(f.profileId()),app.db(),progress,cancelled);
    }

    // ------------------------------------------------------------------ status

    public SyncStatus status(ServerConnection c, Msg.AccountInfo account, Flavor f, LocalScan scan) throws Exception {
        String hp = c.hostPort();
        String base = bases.fingerprint(hp, account.id(), localProfile(f));
        String local = scan.manifest().fingerprint();
        Msg.StatusResp r = c.call(new Msg.StatusReq(account.id(), f.profileId(), local, base), Msg.StatusResp.class);
        String cur = r.currentFingerprint();
        SyncStatus.Kind k;
        if (cur == null) {
            k = SyncStatus.Kind.SERVER_EMPTY;
        } else if (local.equals(cur)) {
            if (!cur.equals(base)) bases.set(hp, account.id(), localProfile(f), scan.manifest());
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
        String base = bases.fingerprint(hp, account.id(), localProfile(f));
        Manifest baseManifest = base != null && base.equals(serverFingerprint) ? bases.manifest(hp, account.id(), localProfile(f)) : null;
        Msg.PushBegin msg;
        if (baseManifest != null) {
            ManifestDiff d = ManifestDiff.between(baseManifest, scan.manifest());
            List<ManifestEntry> ups = new ArrayList<>(d.added());
            ups.addAll(d.edited());
            List<String> removed = new ArrayList<>();
            for (ManifestEntry e : d.removed()) removed.add(e.path());
            msg = new Msg.PushBegin(account.id(), f.profileId(), base, true, null, ups, removed, app.config().deviceNickname,"push",app.config().installationId(f.path()),serverFingerprint);
        } else {
            msg = new Msg.PushBegin(account.id(), f.profileId(), base, false, scan.manifest().entries(), null, null,
                    app.config().deviceNickname,"push",app.config().installationId(f.path()),serverFingerprint);
        }
        return c.call(msg, Msg.PushNeed.class);
    }

    /** Uploads what the server needs, commits, and updates the base. */
    public Msg.PushDone pushUpload(ServerConnection c, Msg.AccountInfo account, Flavor f, LocalScan scan, Msg.PushNeed need,
                                   TransferControl control, TransferEngine.Sink sink) throws Exception {
        return upload(c,account,f,scan,need,control,sink,true);
    }
    private Msg.PushDone upload(ServerConnection c,Msg.AccountInfo account,Flavor f,LocalScan scan,Msg.PushNeed need,
            TransferControl control,TransferEngine.Sink sink,boolean updateBase) throws Exception {
        control.checkpoint();
        Scanner.ensureStaged(scan,new HashSet<>(need.missingHashes()),app.paths().stagedObjects(),control::cancelled);
        Map<String,TransferEngine.UploadItem> byHash=new HashMap<>();
        for(ManifestEntry e:scan.manifest().entries()) for(var ref:e.objects())
            byHash.putIfAbsent(ref.hash(),new TransferEngine.UploadItem(ref.hash(),ref.size(),e.path(),app.paths().stagedObjects().resolve(ref.hash())));
        List<TransferEngine.UploadItem> items=new ArrayList<>();long total=0;
        for(String hash:need.missingHashes()) {
            TransferEngine.UploadItem item=byHash.get(hash);if(item==null) throw new UserException("Unexpected requested chunk");
            items.add(item);total+=item.size();
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
        if(updateBase) {
            LocalScan current=Scanner.verified(f.path(),app.config().exclusions(f.profileId()),app.db(),null,control::cancelled);
            if(!current.manifest().fingerprint().equals(scan.manifest().fingerprint()))throw new UserException("Local files changed during upload. Review and push again.");
        }
        Msg.PushDone done = c.call(new Msg.PushCommit(need.pushId()), Msg.PushDone.class);
        if (updateBase && done.fingerprint().equals(scan.manifest().fingerprint())) {
            bases.set(c.hostPort(), account.id(), localProfile(f), scan.manifest());
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
        for (ManifestEntry e : tocEntries.values()) if (e.size() < (1 << 20)) for(var ref:e.objects()) hashes.add(ref.hash());
        Map<String, byte[]> byHash = TransferEngine.fetchToMemory(c, source.id(), f.profileId(), new ArrayList<>(new HashSet<>(hashes)));
        Map<String, byte[]> tocs = new HashMap<>();
        for (Map.Entry<String, ManifestEntry> e : tocEntries.entrySet()) {
            if(e.getValue().size()>=(1<<20))continue;
            var content=new java.io.ByteArrayOutputStream();
            for(var ref:e.getValue().objects()) { byte[] chunk=byHash.get(ref.hash());if(chunk==null || chunk.length!=ref.size())throw new IOException("Missing toc chunk");content.write(chunk); }
            byte[] b=content.toByteArray();
            if(!io.github.azotorp.wowuisync.common.hash.Hashing.sha256Hex(b).equals(e.getValue().sha256()))throw new IOException("Toc integrity failed");
            tocs.put(e.getKey(),b);
        }
        return new PullSource(man, m.fingerprint(), tocs);
    }

    public Msg.PullPlan pullPlan(ServerConnection c, Msg.AccountInfo source, Flavor f, LocalScan scan, PullSelection sel, PullMode mode)
            throws IOException {
        Msg.PullPlan plan=c.call(new Msg.PullBegin(source.id(),f.profileId(),sel,mode,scan.manifest().entries()),Msg.PullPlan.class);
        var ex=app.config().exclusions(f.profileId());
        var added=plan.newFiles().stream().filter(e->!ex.isExcluded(e.path())).toList();
        var edited=plan.editedFiles().stream().filter(e->!ex.isExcluded(e.path())).toList();
        var removed=plan.removedFiles().stream().filter(p->!ex.isExcluded(p)).toList();
        boolean filtered=added.size()!=plan.newFiles().size()||edited.size()!=plan.editedFiles().size()||removed.size()!=plan.removedFiles().size();
        return new Msg.PullPlan(added,edited,removed,plan.totalBytes(),plan.sourceFingerprint(),plan.selectsEverything()&&!filtered);
    }

    /**
     * Executes a reviewed pull plan: free-space check, local backup, download only differing files, write via temp +
     * atomic rename, apply removals, set the applied account, update or clear bases.
     * Returns true if addon folders changed.
     */
    public boolean pullApply(ServerConnection c, Msg.AccountInfo source, Flavor f, LocalScan scan, Msg.PullPlan plan,
                             TransferControl control, TransferEngine.Sink sink, Backups.Progress backupProgress) throws Exception {
        return apply(c,source,f,scan,plan,control,sink,"before_pull_recovery");
    }
    public Msg.PushDone recoverySnapshot(ServerConnection c,Flavor f,LocalScan scan,TransferControl control,TransferEngine.Sink sink,String reason) throws Exception {
        Msg.AccountInfo own=c.login().accessibleAccounts().stream().filter(x->x.id().equals(c.login().accountId())).findFirst().orElseThrow();
        Msg.PushNeed need=c.call(new Msg.PushBegin(own.id(),f.profileId(),null,false,scan.manifest().entries(),null,null,
            app.config().deviceNickname,reason,app.config().installationId(f.path()),null),Msg.PushNeed.class);
        try { return upload(c,own,f,scan,need,control,sink,false); }
        catch(Exception e) { pushCancel(c,need);throw e; }
    }
    public boolean apply(ServerConnection c,Msg.AccountInfo source,Flavor f,LocalScan scan,Msg.PullPlan plan,
            TransferControl control,TransferEngine.Sink sink,String reason) throws Exception {
        if(io.github.azotorp.wowuisync.client.wow.WowProcess.check(f.path()) == io.github.azotorp.wowuisync.client.wow.WowProcess.State.RUNNING) throw new UserException("Close WoW before applying files");
        if(app.paths().root().toAbsolutePath().normalize().startsWith(f.path().toAbsolutePath().normalize()))throw new UserException("Client staging must be outside the WoW installation");
        Path journal=ApplyJournal.journal(app.paths().root(),f.path());
        if(Files.exists(journal)) throw new UserException("Recover the interrupted operation first");
        LocalScan fresh=Scanner.verified(f.path(),app.config().exclusions(f.profileId()),app.db(),null,control::cancelled);
        if(!fresh.manifest().fingerprint().equals(scan.manifest().fingerprint())) throw new UserException("Local files changed since review. Review again.");
        List<ManifestEntry> writes=new ArrayList<>(plan.newFiles());writes.addAll(plan.editedFiles());
        List<ManifestEntry> removed=new ArrayList<>();
        for(String path:plan.removedFiles()) { var e=scan.manifest().get(path);if(e==null) throw new UserException("Unexpected removal");removed.add(e); }
        Manifest target=new ManifestDiff(plan.newFiles(),plan.editedFiles(),removed).applyTo(scan.manifest());
        long bytes=0;Map<String,Long> needed=new LinkedHashMap<>();
        for(var e:writes) { bytes=Math.addExact(bytes,e.size());for(var ref:e.objects()) needed.putIfAbsent(ref.hash(),ref.size()); }
        long stagingBytes=bytes+needed.values().stream().mapToLong(Long::longValue).sum()+(64L<<20);
        if(FileOps.usableSpace(app.paths().root())<stagingBytes || FileOps.usableSpace(f.path())<bytes+(64L<<20))
            throw new UserException("Insufficient free space for immutable staging and apply");
        Msg.PushDone recovery=recoverySnapshot(c,f,fresh,control,sink,reason);
        if(recovery.snapshotId()==null) throw new UserException("Server did not confirm a recovery snapshot");
        String id=UUID.randomUUID().toString();
        var op=new ApplyJournal.Operation(id,f.path().toAbsolutePath().normalize().toString(),f.profileId(),c.hostPort(),source.id(),
            c.login().accountId(),recovery.snapshotId(),scan.manifest().entries(),target.entries(),plan.selectsEverything());
        List<TransferEngine.DownloadItem> items=new ArrayList<>();
        for(var e:needed.entrySet()) items.add(new TransferEngine.DownloadItem(e.getKey(),e.getValue()));
        new TransferEngine(c,app.config().dataConnections,control,sink,new TransferStats(items.size(),stagingBytes)).download(source.id(),f.profileId(),items,app.paths().stagedObjects());
        ApplyJournal.reconstruct(app.paths().stagedObjects(),ApplyJournal.prepared(app.paths().root(),op),writes);
        LocalScan recheck=Scanner.verified(f.path(),app.config().exclusions(f.profileId()),app.db(),null,control::cancelled);
        if(!recheck.manifest().fingerprint().equals(scan.manifest().fingerprint())) throw new UserException("Local files changed during download. Review again.");
        control.checkpoint();
        if(io.github.azotorp.wowuisync.client.wow.WowProcess.check(f.path()) == io.github.azotorp.wowuisync.client.wow.WowProcess.State.RUNNING) throw new UserException("Close WoW before applying files");
        ApplyJournal.write(journal,op);
        ApplyJournal.apply(app.paths().root(),journal);
        LocalScan after=Scanner.verified(f.path(),app.config().exclusions(f.profileId()),app.db(),null,()->false);
        if(!after.manifest().fingerprint().equals(target.fingerprint())) throw new UserException("Post-apply verification failed. Open Recovery.");
        bases.clearAll(localProfile(f));
        if(plan.selectsEverything() && target.fingerprint().equals(plan.sourceFingerprint())) bases.set(c.hostPort(),source.id(),localProfile(f),after.manifest());
        app.db().setApplied(f.path().toString(),source.username(),source.id(),c.hostPort());
        ApplyJournal.finish(app.paths().root(),journal);
        try { c.call(new Msg.SnapshotUpdate(c.login().accountId(),f.profileId(),recovery.snapshotId(),null,false,false),Msg.Ok.class); }
        catch(IOException ignored) { /* A retained pin is safe if acknowledgement is interrupted. */ }
        return writes.stream().anyMatch(e->SyncPaths.addonFolder(e.path())!=null) || plan.removedFiles().stream().anyMatch(x->SyncPaths.addonFolder(x)!=null);
    }
    public void resume(Flavor f) throws Exception {
        Path journal=ApplyJournal.journal(app.paths().root(),f.path());
        if(io.github.azotorp.wowuisync.client.wow.WowProcess.check(f.path()) == io.github.azotorp.wowuisync.client.wow.WowProcess.State.RUNNING) throw new UserException("Close WoW before recovery");
        var op=ApplyJournal.read(journal);if(!Path.of(op.installationPath()).toAbsolutePath().normalize().equals(f.path().toAbsolutePath().normalize()) || !op.profileId().equals(f.profileId())) throw new UserException("FLAVOR_MISMATCH");
        ApplyJournal.apply(app.paths().root(),journal);
        var after=Scanner.verified(f.path(),app.config().exclusions(f.profileId()),app.db(),null,()->false);
        if(!after.manifest().fingerprint().equals(new Manifest(op.target()).fingerprint())) throw new UserException("Recovery verification failed");
        bases.clearAll(localProfile(f));ApplyJournal.finish(app.paths().root(),journal);
    }
    /** Restore recorded pre-apply state; reconnect only if transient chunks were lost. */
    public void rollback(ServerConnection c,Flavor f,TransferControl control,TransferEngine.Sink sink) throws Exception {
        Path file=ApplyJournal.journal(app.paths().root(),f.path());var old=ApplyJournal.read(file);
        if(!Path.of(old.installationPath()).toAbsolutePath().normalize().equals(f.path().toAbsolutePath().normalize()) || !old.profileId().equals(f.profileId()) || !old.hostPort().equals(c.hostPort()) || !old.recoveryAccountId().equals(c.login().accountId()))
            throw new UserException("Connect to the original recovery account and flavor");
        Msg.ManifestResp r=c.call(new Msg.SnapshotGet(old.recoveryAccountId(),f.profileId(),old.recoverySnapshotId()),Msg.ManifestResp.class);
        var current=Scanner.verified(f.path(),app.config().exclusions(f.profileId()),app.db(),null,()->false);
        Manifest target=new Manifest(r.entries());var diff=ManifestDiff.between(current.manifest(),target);
        List<ManifestEntry> writes=new ArrayList<>(diff.added());writes.addAll(diff.edited());
        Map<String,Long> refs=new LinkedHashMap<>();for(var e:writes) for(var ref:e.objects()) refs.put(ref.hash(),ref.size());
        List<TransferEngine.DownloadItem> items=new ArrayList<>();refs.forEach((h,n)->items.add(new TransferEngine.DownloadItem(h,n)));
        new TransferEngine(c,app.config().dataConnections,control,sink,new TransferStats(items.size(),refs.values().stream().mapToLong(Long::longValue).sum()))
            .download(old.recoveryAccountId(),f.profileId(),items,app.paths().stagedObjects());
        var rollback=new ApplyJournal.Operation(old.id(),old.installationPath(),old.profileId(),old.hostPort(),old.recoveryAccountId(),old.recoveryAccountId(),old.recoverySnapshotId(),current.manifest().entries(),target.entries(),true);
        ApplyJournal.reconstruct(app.paths().stagedObjects(),ApplyJournal.prepared(app.paths().root(),rollback),writes);
        ApplyJournal.write(file,rollback);resume(f);
    }

    private static Path existing(Path p) {
        Path q = p.toAbsolutePath();
        while (q != null && !Files.exists(q)) q = q.getParent();
        return q;
    }

    /** Resolves a relative synced path inside the flavor folder, refusing anything that escapes it. */
    static Path resolve(Path flavor, String rel) throws UserException {
        try { return SyncPaths.resolve(flavor,rel); }
        catch(IOException e) { throw new UserException("Refusing unsafe path "+rel,e); }
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
