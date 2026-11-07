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
        final Set<String> needed;
        final Set<String> remaining = ConcurrentHashMap.newKeySet();

        PushState(String id, Session session, String accountId, String profileId, Manifest manifest, String startFingerprint,
                  Set<String> needed) {
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

    private static void requireProfile(String profileId) throws ApiException {
        if (profileId == null || !Profiles.isEnabled(profileId)) {
            throw new ApiException(ErrorCodes.INVALID, "This server does not accept profile '" + profileId + "'.");
        }
    }

    private Manifest current(String accountId, String profileId) throws IOException {
        Storage.StoredManifest sm = storage.readManifest(accountId, profileId);
        return sm == null ? Manifest.empty() : sm.manifest();
    }

    // ------------------------------------------------------------------ status and manifest

    public Msg.StatusResp status(AccountRow actor, Msg.StatusReq m) throws ApiException, SQLException {
        requireProfile(m.profileId());
        AccountRow t = Access.requireData(ctx.db, actor, m.accountId());
        ProfileStateRow ps = ctx.db.profileState(t.id(), m.profileId()).orElse(null);
        return new Msg.StatusResp(ps == null ? null : ps.fingerprint(), ps == null ? null : ps.lastPushAt(),
                ps == null ? null : ps.lastPushDevice(), ps == null ? null : ps.lastPushByAccount(),
                ps == null ? 0 : ps.storedBytes(), t.quotaMb(), ctx.db.storedBytes(t.id()));
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
        List<String> paths = new ArrayList<>(entries.size());
        for (ManifestEntry e : entries) {
            if (e == null || e.path() == null || !SyncPaths.isSafeRelative(e.path()) || !SyncPaths.isSyncedPath(e.path())) {
                throw new ApiException(ErrorCodes.INVALID, "Rejected path: " + (e == null ? "null" : e.path()));
            }
            if (!Hashing.isSha256Hex(e.sha256()) || e.size() < 0) throw new ApiException(ErrorCodes.INVALID, "Bad entry for " + e.path());
            paths.add(e.path());
        }
        List<List<String>> collisions = SyncPaths.caseCollisions(paths);
        if (!collisions.isEmpty()) {
            throw new ApiException(ErrorCodes.INVALID, "Paths differ only by letter case: " + collisions.get(0));
        }
    }

    public Msg.PushNeed pushBegin(AccountRow actor, Session s, Msg.PushBegin m) throws ApiException, SQLException, IOException {
        requireProfile(m.profileId());
        AccountRow t = Access.requireData(ctx.db, actor, m.accountId());
        Storage.StoredManifest sm = storage.readManifest(t.id(), m.profileId());
        Manifest cur = sm == null ? Manifest.empty() : sm.manifest();
        String curFp = sm == null ? null : sm.fingerprint();

        Manifest next;
        if (m.delta()) {
            if (m.baseFingerprint() == null || !m.baseFingerprint().equals(curFp)) {
                throw new ApiException(ErrorCodes.CONFLICT, "The server copy changed; send the full manifest.");
            }
            List<ManifestEntry> ups = m.upserts() == null ? List.of() : m.upserts();
            validateEntries(ups);
            List<ManifestEntry> removed = new ArrayList<>();
            if (m.removed() != null) for (String p : m.removed()) removed.add(new ManifestEntry(p, 0, ""));
            next = new ManifestDiff(ups, List.of(), removed).applyTo(cur);
        } else {
            List<ManifestEntry> full = m.full() == null ? List.of() : m.full();
            validateEntries(full);
            next = new Manifest(full);
        }
        validateEntries(next.entries());

        ManifestDiff plan = ManifestDiff.between(cur, next);
        Set<String> unique = Storage.uniqueHashes(next);
        Map<String, Long> sizeByHash = new HashMap<>();
        for (ManifestEntry e : next.entries()) sizeByHash.put(e.sha256(), e.size());
        List<String> missing = new ArrayList<>();
        Map<String, Long> partials = new LinkedHashMap<>();
        long upload = 0;
        long projectedProfile = 0;
        for (String h : unique) {
            Path op = storage.objectPath(t.id(), m.profileId(), h);
            if (Files.isRegularFile(op)) {
                projectedProfile += Files.size(op);
            } else {
                missing.add(h);
                long size = sizeByHash.get(h);
                projectedProfile += size;
                long part = size >= Defaults.LARGE_FILE_THRESHOLD ? storage.partialSize(t.id(), h) : 0;
                if (part > 0 && part <= size) partials.put(h, part);
                upload += size - Math.min(part, size);
            }
        }

        // Quota check (DESIGN.md §12.6), before anything is uploaded.
        long used = ctx.db.storedBytes(t.id());
        long thisProfile = ctx.db.profileState(t.id(), m.profileId()).map(ProfileStateRow::storedBytes).orElse(0L);
        long projectedAccount = used - thisProfile + projectedProfile;
        checkQuota(t, used, projectedAccount, upload);

        PushState ps = new PushState(UUID.randomUUID().toString(), s, t.id(), m.profileId(), next, curFp, new HashSet<>(missing));
        // One pending push per session.
        pushes.values().removeIf(p -> p.session == s);
        pushes.put(ps.id, ps);

        return new Msg.PushNeed(ps.id, paths(plan.added()), paths(plan.edited()), paths(plan.removed()), missing, partials,
                upload, projectedAccount, next.fingerprint());
    }

    private void checkQuota(AccountRow t, long used, long projected, long upload) throws ApiException, SQLException {
        if (t.quotaMb() != null && projected > t.quotaMb() * (1L << 20) && projected > used) {
            throw new ApiException(ErrorCodes.QUOTA, "This push needs " + Sizes.mb(Math.max(upload, projected - used)) + " MB; your limit is "
                    + t.quotaMb() + " MB (" + Sizes.mb(used) + " MB used).");
        }
        AccountRow groupOwner = null;
        if (t.role() == Role.SUPERUSER) groupOwner = t;
        else if (t.ownerId() != null) {
            AccountRow o = ctx.db.accountById(t.ownerId()).orElse(null);
            if (o != null && o.role() == Role.SUPERUSER) groupOwner = o;
        }
        if (groupOwner != null && groupOwner.groupQuotaMb() != null) {
            long groupUsed = ctx.db.storedBytes(groupOwner.id());
            for (AccountRow u : ctx.db.accountsOwnedBy(groupOwner.id())) groupUsed += ctx.db.storedBytes(u.id());
            long groupProjected = groupUsed - used + projected;
            if (groupProjected > groupOwner.groupQuotaMb() * (1L << 20) && projected > used) {
                throw new ApiException(ErrorCodes.QUOTA, "This push needs " + Sizes.mb(Math.max(upload, projected - used))
                        + " MB; your group's limit is " + groupOwner.groupQuotaMb() + " MB (" + Sizes.mb(groupUsed) + " MB used).");
            }
        }
    }

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

    public Msg.Ok packUpload(AccountRow actor, Session s, Frame f) throws ApiException, IOException {
        Msg.PackUpload h = f.message(Msg.PackUpload.class);
        PushState ps = push(s, h.pushId());
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
                if (!ps.needed.contains(e.sha256())) continue;
                storage.storeObject(ps.accountId, ps.profileId, e.sha256(), e.data(), compress);
                ps.remaining.remove(e.sha256());
            }
            return new Msg.Ok(null);
        } finally {
            storage.endTransfer(ps.accountId, ps.profileId);
        }
    }

    public Msg.Ok fileUploadChunk(AccountRow actor, Session s, Frame f) throws ApiException, IOException {
        Msg.FileUploadChunk h = f.message(Msg.FileUploadChunk.class);
        PushState ps = push(s, h.pushId());
        if (!Hashing.isSha256Hex(h.hash()) || !ps.needed.contains(h.hash())) {
            throw new ApiException(ErrorCodes.INVALID, "This push does not need that file.");
        }
        if (f.bodyLength() > Defaults.CHUNK_BYTES * 2) throw new ApiException(ErrorCodes.INVALID, "Chunk too large.");
        if (!storage.beginTransfer(ps.accountId, ps.profileId)) throw new ApiException(ErrorCodes.BUSY, "The account is being deleted.");
        try {
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
        } finally {
            storage.endTransfer(ps.accountId, ps.profileId);
        }
    }

    public Msg.ResumeOffset resumeQuery(Session s, Msg.ResumeQuery m) throws ApiException {
        PushState ps = push(s, m.pushId());
        if (!Hashing.isSha256Hex(m.hash())) throw new ApiException(ErrorCodes.INVALID, "Bad hash.");
        // offset -1 = the server already has the object.
        if (storage.hasObject(ps.accountId, ps.profileId, m.hash())) return new Msg.ResumeOffset(m.hash(), -1);
        return new Msg.ResumeOffset(m.hash(), storage.partialSize(ps.accountId, m.hash()));
    }

    public Msg.PushDone pushCommit(AccountRow actor, Session s, Msg.PushCommit m) throws ApiException, SQLException, IOException {
        PushState ps = push(s, m.pushId());
        Access.requireData(ctx.db, actor, ps.accountId);
        synchronized (storage.profileLock(ps.accountId, ps.profileId)) {
            Storage.StoredManifest sm = storage.readManifest(ps.accountId, ps.profileId);
            String curFp = sm == null ? null : sm.fingerprint();
            if (curFp == null ? ps.startFingerprint != null : !curFp.equals(ps.startFingerprint)) {
                pushes.remove(ps.id);
                throw new ApiException(ErrorCodes.CONFLICT, "The server copy changed while you were pushing. Check the status and push again.");
            }
            Set<String> unique = Storage.uniqueHashes(ps.manifest);
            List<String> stillMissing = new ArrayList<>();
            for (String h : unique) if (!storage.hasObject(ps.accountId, ps.profileId, h)) stillMissing.add(h);
            if (!stillMissing.isEmpty()) {
                throw new ApiException(ErrorCodes.INTEGRITY, stillMissing.size() + " files have not been uploaded yet.");
            }
            long now = System.currentTimeMillis();
            String fp = ps.manifest.fingerprint();
            storage.writeManifest(ps.accountId, ps.profileId,
                    new Storage.StoredManifest(fp, ps.manifest.entries(), now, s.deviceNickname(), actor.username()));
            long stored;
            if (storage.activeTransfers(ps.accountId, ps.profileId) == 0) {
                stored = storage.gc(ps.accountId, ps.profileId, unique);
            } else {
                stored = storage.objectBytes(ps.accountId, ps.profileId, unique);
            }
            ctx.db.putProfileState(new ProfileStateRow(ps.accountId, ps.profileId, fp, stored, now, s.deviceNickname(), actor.username()));
            pushes.remove(ps.id);
            Logs.LOG.info("Push committed: account " + ps.accountId + " profile " + ps.profileId + " by " + actor.username()
                    + " (" + ps.manifest.size() + " files, " + Sizes.human(stored) + " stored)");
            return new Msg.PushDone(fp, stored, now);
        }
    }

    public void pushCancel(Session s, Msg.PushCancel m) {
        PushState ps = m.pushId() == null ? null : pushes.get(m.pushId());
        if (ps != null && ps.session == s) pushes.remove(ps.id);
    }

    public void sessionClosed(Session s) {
        pushes.values().removeIf(p -> p.session == s);
    }

    // ------------------------------------------------------------------ pull

    public Msg.PullPlan pullBegin(AccountRow actor, Msg.PullBegin m) throws ApiException, SQLException, IOException {
        requireProfile(m.profileId());
        AccountRow src = Access.requireData(ctx.db, actor, m.sourceAccountId());
        Storage.StoredManifest sm = storage.readManifest(src.id(), m.profileId());
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

    /** Sends the requested objects as packs (compressed and raw packs, never mixed). */
    public void fetch(AccountRow actor, Msg.Fetch m, Wire wire, int id) throws ApiException, SQLException, IOException {
        requireProfile(m.profileId());
        AccountRow t = Access.requireData(ctx.db, actor, m.accountId());
        List<String> hashes = m.hashes() == null ? List.of() : m.hashes();
        if (hashes.size() > 100_000) throw new ApiException(ErrorCodes.INVALID, "Too many hashes.");
        for (String h : hashes) {
            if (!Hashing.isSha256Hex(h) || !storage.hasObject(t.id(), m.profileId(), h)) {
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
                byte[] data = ObjectFiles.read(p);
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
        if (!Hashing.isSha256Hex(m.hash()) || !storage.hasObject(t.id(), m.profileId(), m.hash())) {
            throw new ApiException(ErrorCodes.NOT_FOUND, "The server does not have that file.");
        }
        if (!storage.beginTransfer(t.id(), m.profileId())) throw new ApiException(ErrorCodes.BUSY, "The account is being deleted.");
        try {
            Path p = storage.objectPath(t.id(), m.profileId(), m.hash());
            long total = ObjectFiles.header(p).originalSize();
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
