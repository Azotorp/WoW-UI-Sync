package io.github.azotorp.wowuisync.server.sync;

import io.github.azotorp.wowuisync.common.Defaults;
import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.addon.PullMode;
import io.github.azotorp.wowuisync.common.addon.PullPlanner;
import io.github.azotorp.wowuisync.common.addon.PullSelection;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.hash.Hashing;
import io.github.azotorp.wowuisync.common.manifest.Manifest;
import io.github.azotorp.wowuisync.common.manifest.ManifestDiff;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;
import io.github.azotorp.wowuisync.common.object.ObjectFiles;
import io.github.azotorp.wowuisync.common.pack.PackEntry;
import io.github.azotorp.wowuisync.common.pack.PackKind;
import io.github.azotorp.wowuisync.common.pack.PackReader;
import io.github.azotorp.wowuisync.common.pack.PackWriter;
import io.github.azotorp.wowuisync.common.path.SyncPaths;
import io.github.azotorp.wowuisync.common.profile.Profiles;
import io.github.azotorp.wowuisync.common.protocol.ErrorCodes;
import io.github.azotorp.wowuisync.common.protocol.Frame;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.protocol.Wire;
import io.github.azotorp.wowuisync.common.util.Sizes;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.db.ProfileStateRow;
import io.github.azotorp.wowuisync.server.net.ApiException;
import io.github.azotorp.wowuisync.server.net.ServerContext;
import io.github.azotorp.wowuisync.server.net.Session;
import io.github.azotorp.wowuisync.server.service.Access;
import io.github.azotorp.wowuisync.server.util.Logs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Status, manifests, push (mirror) and selective pull (DESIGN.md §9–§12). */
public class SyncService {
    /** A push between PUSH_BEGIN and PUSH_COMMIT. */
    static final class PushState {
        final String id;
        final Session session;
        final String accountId;
        final String profileId;
        final Manifest manifest;
        final String startFingerprint;
        final String startHead;
        final String sourceType, installationId;
        final Map<String,Long> objects = new HashMap<>();
        boolean recovery() { return !"push".equals(sourceType); }
        final Set<String> needed;
        final Set<String> remaining = ConcurrentHashMap.newKeySet();

        PushState(String id, Session session, String accountId, String profileId, Manifest manifest, String startFingerprint,
                  Set<String> needed, String sourceType, String installationId,String startHead) {
            this.startHead=startHead;
            this.sourceType=sourceType;this.installationId=installationId;
            Storage.addRefs(objects,manifest);
            this.id = id;
            this.session = session;
            this.accountId = accountId;
            this.profileId = profileId;
            this.manifest = manifest;
            this.startFingerprint = startFingerprint;
            this.needed = needed;
            this.remaining.addAll(needed);
        }
    }

    protected final ServerContext ctx;
    protected final Storage storage;
    private final Map<String, PushState> pushes = new ConcurrentHashMap<>();

    public SyncService(ServerContext ctx, Storage storage) {
        this.ctx = ctx;
        this.storage = storage;
    }

    private void requireProfile(String profileId) throws ApiException {
        if (profileId == null || (!Profiles.isEnabled(profileId)&&!ctx.config.additionalFlavors.contains(profileId))) {
            throw new ApiException(ErrorCodes.INVALID, "This server does not accept profile '" + profileId + "'.");
        }
    }

    private Manifest current(String accountId, String profileId) throws IOException {
        Storage.StoredManifest sm = storage.readManifest(accountId, profileId);
        return sm == null ? Manifest.empty() : sm.manifest();
    }

    // ------------------------------------------------------------------ status and manifest

    public Msg.StatusResp status(AccountRow actor, Msg.StatusReq m) throws ApiException, SQLException, IOException {
        requireProfile(m.profileId());
        AccountRow t = Access.requireData(ctx.db, actor, m.accountId());
        Storage.StoredManifest sm=storage.statusManifest(t.id(),m.profileId());
        return new Msg.StatusResp(sm==null?null:sm.fingerprint(),sm==null?null:sm.lastPushAt(),
            sm==null?null:sm.lastPushDevice(),sm==null?null:sm.lastPushBy(),storage.statusUsage(t.id()),t.quotaMb(),storage.statusUsage(t.id()));
    }

    public Msg.ManifestResp manifest(AccountRow actor, Msg.ManifestGet m) throws ApiException, SQLException, IOException {
        requireProfile(m.profileId());
        AccountRow t = Access.requireData(ctx.db, actor, m.accountId());
        Storage.StoredManifest sm = storage.readManifest(t.id(), m.profileId());
        if (sm == null) return new Msg.ManifestResp(null, List.of(), null, null, null);
        return new Msg.ManifestResp(sm.fingerprint(), sm.manifest().entries(), sm.lastPushAt(), sm.lastPushDevice(), sm.lastPushBy());
    }

    // ------------------------------------------------------------------ push

    private static void validateEntries(List<ManifestEntry> entries) throws ApiException {
        if(entries==null || entries.size()>250_000) throw new ApiException(ErrorCodes.INVALID,"Manifest entry limit exceeded");
        long refs=0, total=0;
        Map<String,Long> sizes=new HashMap<>();
        List<String> paths = new ArrayList<>(entries.size());
        for (ManifestEntry e : entries) {
            if (e == null || e.path() == null || !SyncPaths.isSafeRelative(e.path()) || !SyncPaths.isSyncedPath(e.path())) {
                throw new ApiException(ErrorCodes.INVALID, "Rejected path: " + (e == null ? "null" : e.path()));
            }
            if (!Hashing.isSha256Hex(e.sha256()) || e.size() < 0) throw new ApiException(ErrorCodes.INVALID, "Bad entry for " + e.path());
            if(e.path().length()>1024 || SyncPaths.invalidWindowsNameReason(e.path())!=null || e.size()>(64L<<30))
                throw new ApiException(ErrorCodes.INVALID,"Unsafe path or file size");
            long size=0;
            for(var r:e.objects()) {
                if(!Hashing.isSha256Hex(r.hash()) || r.size()<0 || (!e.chunks().isEmpty() && r.size()>(8L<<20)))
                    throw new ApiException(ErrorCodes.INVALID,"Invalid chunk");
                Long old=sizes.putIfAbsent(r.hash(),r.size());
                if(old!=null && old!=r.size()) throw new ApiException(ErrorCodes.INVALID,"Inconsistent hash size");
                size=Math.addExact(size,r.size());
                if(++refs>1_000_000) throw new ApiException(ErrorCodes.INVALID,"Too many chunk references");
            }
            if(size!=e.size()) throw new ApiException(ErrorCodes.INVALID,"Chunk lengths do not match file");
            total=Math.addExact(total,e.size());
            if(total>(1L<<40)) throw new ApiException(ErrorCodes.INVALID,"Manifest too large");
            paths.add(e.path());
        }
        List<String> ordered=paths.stream().map(SyncPaths::key).sorted().toList();
        for(int i=1;i<ordered.size();i++)if(ordered.get(i).startsWith(ordered.get(i-1)+"/"))throw new ApiException(ErrorCodes.INVALID,"File/directory collision");
        List<List<String>> collisions = SyncPaths.caseCollisions(paths);
        if (!collisions.isEmpty()) {
            throw new ApiException(ErrorCodes.INVALID, "Paths differ only by letter case: " + collisions.get(0));
        }
    }

    public synchronized Msg.PushNeed pushBegin(AccountRow actor, Session s, Msg.PushBegin m) throws ApiException, SQLException, IOException {
        requireProfile(m.profileId());
        AccountRow t = Access.requireData(ctx.db, actor, m.accountId());
        String source=m.sourceType()==null?"push":m.sourceType();
        if(!Set.of("push","before_pull_recovery","manual","before_restore","legacy_import").contains(source)) throw new ApiException(ErrorCodes.INVALID,"Unknown snapshot source");
        boolean recovery=!"push".equals(source);
        if(recovery && (!t.id().equals(s.accountId()) || m.installationId()==null || m.installationId().length()>100))
            throw new ApiException(ErrorCodes.INVALID,"Recovery requires your own account and installation identity");
        if(pushes.values().stream().filter(x->x.accountId.equals(t.id()) && x.session!=s).count()>=2)
            throw new ApiException(ErrorCodes.BUSY,"Too many pending transfers");
        Storage.StoredManifest sm = storage.readManifest(t.id(), m.profileId());
        Manifest cur = sm == null ? Manifest.empty() : sm.manifest();
        String curFp = sm == null ? null : sm.fingerprint();

        if(!recovery && !java.util.Objects.equals(curFp,m.expectedFingerprint()))
            throw new ApiException(ErrorCodes.CONFLICT,"The sync head changed. Review the current server setup again.");
        Manifest next;
        if (m.delta()) {
            if (m.baseFingerprint() == null || !m.baseFingerprint().equals(curFp)) {
                throw new ApiException(ErrorCodes.CONFLICT, "The server copy changed; send the full manifest.");
            }
            List<ManifestEntry> ups = m.upserts() == null ? List.of() : m.upserts();
            validateEntries(ups);
            List<ManifestEntry> removed = new ArrayList<>();
            if (m.removed() != null) for (String p : m.removed()) {
                if(!SyncPaths.isSafeRelative(p)||!SyncPaths.isSyncedPath(p))throw new ApiException(ErrorCodes.INVALID,"Unsafe removed path");
                removed.add(new ManifestEntry(p,0,""));
            }
            next = new ManifestDiff(ups, List.of(), removed).applyTo(cur);
        } else {
            List<ManifestEntry> full = m.full() == null ? List.of() : m.full();
            validateEntries(full);
            next = new Manifest(full);
        }
        validateEntries(next.entries());

        ManifestDiff plan = ManifestDiff.between(cur, next);
        Set<String> unique = Storage.uniqueHashes(next);
        Map<String,Long> sizeByHash=new HashMap<>();Storage.addRefs(sizeByHash,next);
        List<String> missing=new ArrayList<>();Map<String,Long> partials=new LinkedHashMap<>();long upload=0;
        for(var entry:sizeByHash.entrySet()) {
            String h=entry.getKey();long size=entry.getValue();
            if(storage.hasObject(t.id(),m.profileId(),h)) {
                if(ObjectFiles.header(storage.objectPath(t.id(),m.profileId(),h)).originalSize()!=size)
                    throw new ApiException(ErrorCodes.INTEGRITY,"Stored object length mismatch");
            } else {
                if(size>(8L<<20)) throw new ApiException(ErrorCodes.INVALID,"New data requires v2 chunking");
                missing.add(h);long partial=storage.partialSize(t.id(),h);
                if(partial>0 && partial<=size) partials.put(h,partial);
                upload=Math.addExact(upload,size);
            }
        }
        PushState ps=new PushState(UUID.randomUUID().toString(),s,t.id(),m.profileId(),next,curFp,new HashSet<>(missing),source,m.installationId(),storage.headId(t.id(),m.profileId()));
        synchronized(storage.transactionLock) {
            dropSession(s);
            reserveQuota(t,ps);
            if(Files.getFileStore(ctx.layout.root()).getUsableSpace()<upload+(256L<<20))
                throw new ApiException(ErrorCodes.QUOTA,"Server disk reserve would be exceeded");
            if(!storage.beginTransfer(t.id(),m.profileId())) throw new ApiException(ErrorCodes.BUSY,"Account is locked");
            pushes.put(ps.id,ps);
        }
        long projectedAccount=storage.logicalBytes(t.id())+upload;

        return new Msg.PushNeed(ps.id, paths(plan.added()), paths(plan.edited()), paths(plan.removed()), missing, partials,
                upload, projectedAccount, next.fingerprint());
    }

    private static void mergeReservations(Map<String,Long> refs,Map<String,Long> pending) {
        pending.forEach((h,n)->refs.merge(h,n,Math::max));
    }
    private long reserved(String account,PushState candidate) throws IOException {
        Map<String,Long> refs=storage.chargedObjects(account);
        for(PushState ps:pushes.values()) if(ps.accountId.equals(account)) mergeReservations(refs,ps.objects);
        if(candidate!=null && candidate.accountId.equals(account)) mergeReservations(refs,candidate.objects);
        return Storage.sum(refs);
    }
    private void reserveQuota(AccountRow t,PushState ps) throws IOException,SQLException,ApiException {
        long projected=reserved(t.id(),ps);
        if(t.quotaMb()!=null && projected>Math.multiplyExact(t.quotaMb(),1L<<20))
            throw new ApiException(ErrorCodes.QUOTA,"Retained snapshots and pending uploads exceed the account quota");
        AccountRow owner=t.role()==Role.SUPERUSER?t:t.ownerId()==null?null:ctx.db.accountById(t.ownerId()).orElse(null);
        if(owner!=null && owner.role()==Role.SUPERUSER && owner.groupQuotaMb()!=null) {
            long total=reserved(owner.id(),ps);
            for(AccountRow a:ctx.db.accountsOwnedBy(owner.id())) total=Math.addExact(total,reserved(a.id(),ps));
            if(total>Math.multiplyExact(owner.groupQuotaMb(),1L<<20)) throw new ApiException(ErrorCodes.QUOTA,"Group snapshot quota exceeded");
        }
    }
    private void drop(PushState ps) {
        if(pushes.remove(ps.id,ps)) storage.endTransfer(ps.accountId,ps.profileId);
    }
    private void dropSession(Session s) { for(PushState ps:pushes.values()) if(ps.session==s) drop(ps); }

    private static List<String> paths(List<ManifestEntry> l) {
        List<String> out = new ArrayList<>(l.size());
        for (ManifestEntry e : l) out.add(e.path());
        return out;
    }

    private PushState push(Session s, String pushId) throws ApiException {
        PushState ps = pushId == null ? null : pushes.get(pushId);
        if (ps == null || ps.session != s) throw new ApiException(ErrorCodes.NOT_FOUND, "This push is no longer active. Start it again.");
        return ps;
    }

    public Msg.Ok packUpload(AccountRow actor, Session s, Frame f) throws ApiException, IOException, SQLException {
        Msg.PackUpload h = f.message(Msg.PackUpload.class);
        PushState ps = push(s, h.pushId());
        Access.requireData(ctx.db,actor,ps.accountId);
        if (!storage.beginTransfer(ps.accountId, ps.profileId)) throw new ApiException(ErrorCodes.BUSY, "The account is being deleted.");
        try {
            PackReader.Pack pack;
            try {
                pack = PackReader.read(f.payload(), f.bodyOffset(), f.bodyLength(), true);
            } catch (IOException e) {
                throw new ApiException(ErrorCodes.INTEGRITY, "Pack failed verification: " + e.getMessage());
            }
            boolean compress = pack.kind() == PackKind.COMPRESSED;
            for (PackEntry e : pack.entries()) {
                if (!ps.needed.contains(e.sha256()) || !java.util.Objects.equals(ps.objects.get(e.sha256()),(long)e.data().length))
                    throw new ApiException(ErrorCodes.INTEGRITY,"Pack object is not an expected size/hash");
                storage.storeObject(ps.accountId, ps.profileId, e.sha256(), e.data(), compress);
                ps.remaining.remove(e.sha256());
            }
            return new Msg.Ok(null);
        } finally {
            storage.endTransfer(ps.accountId, ps.profileId);
        }
    }

    public Msg.Ok fileUploadChunk(AccountRow actor, Session s, Frame f) throws ApiException, IOException, SQLException {
        Msg.FileUploadChunk h = f.message(Msg.FileUploadChunk.class);
        PushState ps = push(s, h.pushId());
        Access.requireData(ctx.db,actor,ps.accountId);
        if (!Hashing.isSha256Hex(h.hash()) || !ps.needed.contains(h.hash())) {
            throw new ApiException(ErrorCodes.INVALID, "This push does not need that file.");
        }
        Long expected=ps.objects.get(h.hash());
        if(expected==null || h.totalSize()!=expected || h.offset()<0 || h.offset()>expected || f.bodyLength()>expected-h.offset() ||
                h.last()!=(h.offset()+f.bodyLength()==expected)) throw new ApiException(ErrorCodes.INTEGRITY,"Unexpected chunk length or offset");
        if (f.bodyLength() > Defaults.CHUNK_BYTES) throw new ApiException(ErrorCodes.INVALID, "Chunk too large.");
        if (!storage.beginTransfer(ps.accountId, ps.profileId)) throw new ApiException(ErrorCodes.BUSY, "The account is being deleted.");
        try {
          synchronized(storage.objectLock(ps.accountId,h.hash())) {
            if (storage.hasObject(ps.accountId, ps.profileId, h.hash())) {
                ps.remaining.remove(h.hash());
                return new Msg.Ok("have");
            }
            long now = storage.appendPartial(ps.accountId, h.hash(), h.offset(), f.payload(), f.bodyOffset(), f.bodyLength());
            if (now != h.offset() + f.bodyLength()) {
                throw new ApiException(ErrorCodes.CONFLICT, "offset:" + now);
            }
            if (h.last()) {
                Path part = storage.partialPath(ps.accountId, h.hash());
                if (now != h.totalSize() || !storage.sha256OfFile(part).equals(h.hash())) {
                    storage.deletePartial(ps.accountId, h.hash());
                    throw new ApiException(ErrorCodes.INTEGRITY, "File failed verification; it will be sent again.");
                }
                storage.storeObjectFromFile(ps.accountId, ps.profileId, h.hash(), part, h.compress());
                storage.deletePartial(ps.accountId, h.hash());
                ps.remaining.remove(h.hash());
            }
            return new Msg.Ok(null);
          }
        } finally {
            storage.endTransfer(ps.accountId, ps.profileId);
        }
    }

    public Msg.ResumeOffset resumeQuery(Session s, Msg.ResumeQuery m) throws ApiException {
        PushState ps = push(s, m.pushId());
        if (!Hashing.isSha256Hex(m.hash()) || !ps.objects.containsKey(m.hash())) throw new ApiException(ErrorCodes.INVALID, "Bad hash.");
        // offset -1 = the server already has the object.
        if (storage.hasObject(ps.accountId, ps.profileId, m.hash())) return new Msg.ResumeOffset(m.hash(), -1);
        return new Msg.ResumeOffset(m.hash(), storage.partialSize(ps.accountId, m.hash()));
    }

    public Msg.PushDone pushCommit(AccountRow actor, Session s, Msg.PushCommit m) throws ApiException, SQLException, IOException {
        PushState ps = push(s, m.pushId());
        Access.requireData(ctx.db, actor, ps.accountId);
        synchronized (storage.transactionLock) {
            Storage.StoredManifest sm = storage.readManifest(ps.accountId, ps.profileId);
            String curFp = sm == null ? null : sm.fingerprint();
            if (!ps.recovery() && (!java.util.Objects.equals(curFp,ps.startFingerprint)||!java.util.Objects.equals(storage.headId(ps.accountId,ps.profileId),ps.startHead))) {
                drop(ps);
                throw new ApiException(ErrorCodes.CONFLICT, "The server copy changed while you were pushing. Check the status and push again.");
            }
            Set<String> unique = Storage.uniqueHashes(ps.manifest);
            List<String> stillMissing = new ArrayList<>();
            for (String h : unique) if (!storage.hasObject(ps.accountId, ps.profileId, h)) stillMissing.add(h);
            if (!stillMissing.isEmpty()) {
                throw new ApiException(ErrorCodes.INTEGRITY, stillMissing.size() + " files have not been uploaded yet.");
            }
            reserveQuota(ctx.db.accountById(ps.accountId).orElseThrow(),ps);
            long now=System.currentTimeMillis();String fp=ps.manifest.fingerprint();
            Storage.Snapshot snap=storage.commitSnapshot(ps.accountId,ps.profileId,
                new Storage.StoredManifest(fp,ps.manifest.entries(),now,s.deviceNickname(),actor.username()),ps.sourceType,ps.installationId,!ps.recovery());
            if(ps.sourceType.equals("before_pull_recovery")||ps.sourceType.equals("before_restore"))storage.annotate(ps.accountId,ps.profileId,snap.id(),"Recovery · "+ps.installationId,true,false);
            long stored=storage.logicalBytes(ps.accountId);
            if(!ps.recovery()) ctx.db.putProfileState(new ProfileStateRow(ps.accountId,ps.profileId,fp,stored,now,s.deviceNickname(),actor.username()));
            drop(ps);
            storage.prune(ps.accountId,ps.profileId);
            Logs.LOG.info("Snapshot committed: "+snap.id()+" account "+ps.accountId+" flavor "+ps.profileId+" source "+ps.sourceType);
            return new Msg.PushDone(fp,stored,now,snap.id());
        }
    }

    public void pushCancel(Session s, Msg.PushCancel m) {
        PushState ps = m.pushId() == null ? null : pushes.get(m.pushId());
        if (ps != null && ps.session == s) drop(ps);
    }

    public void sessionClosed(Session s) {
        dropSession(s);
    }

    // ------------------------------------------------------------------ pull

    public Msg.PullPlan pullBegin(AccountRow actor, Msg.PullBegin m) throws ApiException, SQLException, IOException {
        requireProfile(m.profileId());
        AccountRow src = Access.requireData(ctx.db, actor, m.sourceAccountId());
        Storage.StoredManifest sm = m.snapshotId()==null ? storage.readManifest(src.id(),m.profileId()) : requireSnapshot(src.id(),m.profileId(),m.snapshotId()).content();
        if (sm == null) throw new ApiException(ErrorCodes.NOT_FOUND, src.username() + " has nothing stored for this profile yet.");
        Manifest source = sm.manifest();
        List<ManifestEntry> localEntries = m.localManifest() == null ? List.of() : m.localManifest();
        validateEntries(localEntries);
        Manifest local = new Manifest(localEntries);
        PullSelection sel = m.selection() == null ? new PullSelection(List.of(), List.of(), false) : m.selection();
        PullMode mode = m.mode() == null ? PullMode.REPLACE : m.mode();
        ManifestDiff plan = PullPlanner.plan(source, local, sel, mode);
        Set<String> seen = new HashSet<>();
        long total = 0;
        for (ManifestEntry e : plan.added()) if (seen.add(e.sha256())) total += e.size();
        for (ManifestEntry e : plan.edited()) if (seen.add(e.sha256())) total += e.size();
        return new Msg.PullPlan(plan.added(), plan.edited(), paths(plan.removed()), total, sm.fingerprint(),
                PullPlanner.selectsEverything(source, sel, mode));
    }

    private Storage.Snapshot requireSnapshot(String a,String p,String id) throws IOException,ApiException {
        try { return storage.snapshot(a,p,id); }
        catch(java.nio.file.NoSuchFileException e) {
            for(String other:storage.profiles(a)) if(!other.equals(p)) {
                try { storage.snapshot(a,other,id);throw new ApiException(ErrorCodes.FLAVOR_MISMATCH,"Snapshot belongs to another flavor"); }
                catch(java.nio.file.NoSuchFileException ignored) {}
            }
            throw new ApiException(ErrorCodes.NOT_FOUND,"No retained snapshot with that identity");
        }
    }
    public Msg.SnapshotListResp snapshotList(AccountRow actor,Msg.SnapshotList m) throws Exception {
        requireProfile(m.profileId());var a=Access.requireData(ctx.db,actor,m.accountId());List<Msg.SnapshotInfo> out=new ArrayList<>();
        for(var snap:storage.snapshots(a.id(),m.profileId())) out.add(storage.info(a.id(),m.profileId(),snap));
        return new Msg.SnapshotListResp(out);
    }
    public Msg.ManifestResp snapshotGet(AccountRow actor,Msg.SnapshotGet m) throws Exception {
        requireProfile(m.profileId());var a=Access.requireData(ctx.db,actor,m.accountId());
        var sm=requireSnapshot(a.id(),m.profileId(),m.snapshotId()).content();
        return new Msg.ManifestResp(sm.fingerprint(),sm.entries(),sm.lastPushAt(),sm.lastPushDevice(),sm.lastPushBy());
    }
    public Msg.Ok snapshotUpdate(AccountRow actor,Msg.SnapshotUpdate m) throws Exception {
        requireProfile(m.profileId());var a=Access.requireData(ctx.db,actor,m.accountId());requireSnapshot(a.id(),m.profileId(),m.snapshotId());
        storage.annotate(a.id(),m.profileId(),m.snapshotId(),m.label(),m.pinned(),m.delete());
        Logs.LOG.info("Snapshot metadata updated by "+actor.username()+" snapshot "+m.snapshotId());return new Msg.Ok("Snapshot updated");
    }
    public Msg.Ok snapshotRetention(AccountRow actor,Msg.SnapshotRetention m) throws Exception {
        requireProfile(m.profileId());var a=Access.requireData(ctx.db,actor,m.accountId());storage.retention(a.id(),m.profileId(),m.keepLast());
        return new Msg.Ok(m.keepLast()==0?"All snapshots retained":"Retention enabled: latest "+m.keepLast()+", plus pins, current head and a 48-hour protection window");
    }
    public Msg.Ok snapshotVerify(AccountRow actor,Msg.SnapshotVerify m) throws Exception {
        requireProfile(m.profileId());var a=Access.requireData(ctx.db,actor,m.accountId());
        storage.verify(a.id(),m.profileId(),requireSnapshot(a.id(),m.profileId(),m.snapshotId()).content().manifest());
        return new Msg.Ok("All file and chunk checksums verified");
    }

    /** Sends the requested objects as packs (compressed and raw packs, never mixed). */
    public void fetch(AccountRow actor, Msg.Fetch m, Wire wire, int id) throws ApiException, SQLException, IOException {
        requireProfile(m.profileId());
        AccountRow t = Access.requireData(ctx.db, actor, m.accountId());
        List<String> hashes = m.hashes() == null ? List.of() : m.hashes();
        if (hashes.size() > 5000) throw new ApiException(ErrorCodes.INVALID, "Too many hashes.");
        for (String h : hashes) {
            if (!Hashing.isSha256Hex(h) || !storage.hasObject(t.id(), m.profileId(), h) || !storage.authorizedObject(t.id(),m.profileId(),h)) {
                throw new ApiException(ErrorCodes.NOT_FOUND, "The server does not have " + h + ". Check the status and try again.");
            }
        }
        if (!storage.beginTransfer(t.id(), m.profileId())) throw new ApiException(ErrorCodes.BUSY, "The account is being deleted.");
        try {
            Codec codec = wire.codec() == Codec.RAW ? Codec.preferred() : wire.codec();
            PackWriter comp = new PackWriter(PackKind.COMPRESSED, codec, Defaults.ZSTD_LEVEL);
            PackWriter raw = new PackWriter(PackKind.RAW, Codec.RAW, 0);
            int index = 0;
            for (String h : hashes) {
                Path p = storage.objectPath(t.id(), m.profileId(), h);
                ObjectFiles.Header hdr = ObjectFiles.header(p);
                if(hdr.originalSize()>(8L<<20)) throw new ApiException(ErrorCodes.INVALID,"Large legacy object requires streaming download");
                byte[] data = ObjectFiles.read(p);
                if(!Hashing.sha256Hex(data).equals(h)) throw new ApiException(ErrorCodes.INTEGRITY,"Stored chunk is corrupt");
                boolean compressed = hdr.codec() != Codec.RAW;
                PackWriter w = compressed ? comp : raw;
                long limit = compressed ? Defaults.COMPRESSED_PACK_BYTES : Defaults.RAW_PACK_BYTES;
                if (!w.isEmpty() && w.uncompressedBytes() + data.length > limit) {
                    send(wire, id, w, index++, false);
                    w = compressed ? (comp = new PackWriter(PackKind.COMPRESSED, codec, Defaults.ZSTD_LEVEL))
                            : (raw = new PackWriter(PackKind.RAW, Codec.RAW, 0));
                }
                w.add("", h, data);
            }
            if (!comp.isEmpty() && !raw.isEmpty()) {
                send(wire, id, comp, index++, false);
                send(wire, id, raw, index, true);
            } else if (!comp.isEmpty()) {
                send(wire, id, comp, index, true);
            } else {
                send(wire, id, raw, index, true);
            }
        } finally {
            storage.endTransfer(t.id(), m.profileId());
        }
    }

    private static void send(Wire wire, int id, PackWriter w, int index, boolean last) throws IOException {
        byte[] pack = w.build();
        wire.sendBinary(id, new Msg.PackDownload(index, last), pack, 0, pack.length);
    }

    /** Streams one object from {@code offset} in 1 MB chunks. */
    public void fileDownload(AccountRow actor, Msg.FileDownloadChunk m, Wire wire, int id) throws ApiException, SQLException, IOException {
        requireProfile(m.profileId());
        AccountRow t = Access.requireData(ctx.db, actor, m.accountId());
        if (!Hashing.isSha256Hex(m.hash()) || !storage.hasObject(t.id(), m.profileId(), m.hash()) || !storage.authorizedObject(t.id(),m.profileId(),m.hash())) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "The server does not have that file.");
        }
        if (!storage.beginTransfer(t.id(), m.profileId())) throw new ApiException(ErrorCodes.BUSY, "The account is being deleted.");
        try {
            Path p = storage.objectPath(t.id(), m.profileId(), m.hash());
            long total = ObjectFiles.header(p).originalSize();
            try(InputStream verify=ObjectFiles.openContent(p)) {
                var md=Hashing.newSha256();byte[] bytes=new byte[65536];long count=0;int n;
                while((n=verify.read(bytes))!=-1) { count+=n;if(count>total) throw new IOException("Stored object too long");md.update(bytes,0,n); }
                if(count!=total || !Hashing.hex(md.digest()).equals(m.hash())) throw new ApiException(ErrorCodes.INTEGRITY,"Stored object failed verification");
            }
            long offset = Math.max(0, Math.min(m.offset(), total));
            try (InputStream in = ObjectFiles.openContent(p)) {
                in.skipNBytes(offset);
                byte[] buf = new byte[Defaults.CHUNK_BYTES];
                if (offset == total) {
                    wire.sendBinary(id, new Msg.FileDownloadChunk(null, null, m.hash(), offset, total, true), buf, 0, 0);
                    return;
                }
                while (offset < total) {
                    int n = in.readNBytes(buf, 0, (int) Math.min(buf.length, total - offset));
                    if (n <= 0) throw new IOException("Object shorter than its header says");
                    boolean last = offset + n >= total;
                    wire.sendBinary(id, new Msg.FileDownloadChunk(null, null, m.hash(), offset, total, last), buf, 0, n);
                    offset += n;
                }
            }
        } finally {
            storage.endTransfer(t.id(), m.profileId());
        }
    }
}
