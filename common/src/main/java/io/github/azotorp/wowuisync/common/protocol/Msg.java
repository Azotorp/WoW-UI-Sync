package io.github.azotorp.wowuisync.common.protocol;

import io.github.azotorp.wowuisync.common.addon.PullMode;
import io.github.azotorp.wowuisync.common.addon.PullSelection;
import io.github.azotorp.wowuisync.common.manifest.ManifestEntry;

import java.util.List;
import java.util.Map;

/** Every protocol message as a typed record (DESIGN.md §10.2). */
public final class Msg {
    private Msg() {}

    // ---- Session ----
    public record Hello(int protocolVersion, String appVersion, List<String> codecs, String deviceId, String deviceNickname) {}

    public record HelloOk(int protocolVersion, String appVersion, String codec, String minAppVersion,
                          boolean passwordResetAvailable, int maxDataConnections) {}

    public record DataJoin(String sessionId) {}

    public record Ping() {}

    public record Ok(String message) {}

    public record Error(String code, String message) {}

    // ---- Login ----
    public record LoginPassword(String username, String password) {}

    public record LoginKeyBegin(String username) {}

    /** Client signs {@code nonce || sessionId || serverCertFingerprint} (UTF-8 of the id and fingerprint hex). */
    public record LoginKeyChallenge(String nonce, String sessionId) {}

    public record LoginKeyProof(String signature) {}

    public record LoginOk(String accountId, String username, String role, String sessionId,
                          List<AccountInfo> accessibleAccounts, int maxDataConnections) {}

    /** reason: wrong_method | bad_credentials | locked | ip_blocked | version */
    public record LoginFail(String reason, String message, Long retryAfterSeconds, String minVersion) {}

    public record AccountInfo(String id, String username, String role, String ownerId, String ownerName,
                              String email, String authMethod, String keyType, Long quotaMb, Long groupQuotaMb,
                              Integer maxUsers, long usedBytes, Long lastPushAt, String lastPushDevice,
                              long createdAt) {}

    // ---- Status / manifests ----
    public record StatusReq(String accountId, String profileId, String localFingerprint, String baseFingerprint) {}

    public record StatusResp(String currentFingerprint, Long lastPushAt, String lastPushDevice, String lastPushBy,
                             long storedBytes, Long quotaMb, long accountUsedBytes) {}

    public record ManifestGet(String accountId, String profileId) {}

    public record ManifestResp(String fingerprint, List<ManifestEntry> entries, Long lastPushAt, String lastPushDevice,
                               String lastPushBy) {}

    // ---- Push ----
    /**
     * Either {@code full} (complete manifest) or a delta against {@code baseFingerprint}:
     * {@code upserts} (new/changed entries) and {@code removed} paths.
     */
    public record PushBegin(String accountId, String profileId, String baseFingerprint, boolean delta,
                            List<ManifestEntry> full, List<ManifestEntry> upserts, List<String> removed,
                            String deviceNickname, String sourceType, String installationId, String expectedFingerprint) {
        public PushBegin(String accountId,String profileId,String baseFingerprint,boolean delta,List<ManifestEntry> full,
                List<ManifestEntry> upserts,List<String> removed,String deviceNickname) {
            this(accountId,profileId,baseFingerprint,delta,full,upserts,removed,deviceNickname,"push",null,baseFingerprint);
        }
    }

    public record PushNeed(String pushId, List<String> newFiles, List<String> editedFiles, List<String> removedFiles,
                           List<String> missingHashes, Map<String, Long> partialOffsets, long uploadBytes,
                           long projectedAccountBytes, String newFingerprint) {}

    /** Header of a PACK_UPLOAD frame; the pack follows. */
    public record PackUpload(String pushId) {}

    /** Header of a FILE_UPLOAD_CHUNK frame; up to one chunk of bytes follows. */
    public record FileUploadChunk(String pushId, String hash, long offset, long totalSize, boolean last, boolean compress) {}

    public record ResumeQuery(String pushId, String hash) {}

    public record ResumeOffset(String hash, long offset) {}

    public record PushCommit(String pushId) {}

    public record PushDone(String fingerprint, long storedBytes, long pushedAt, String snapshotId) {
        public PushDone(String fp,long bytes,long at) { this(fp,bytes,at,null); }
    }

    public record PushCancel(String pushId) {}

    // ---- Pull ----
    public record PullBegin(String sourceAccountId, String profileId, PullSelection selection, PullMode mode,
                            List<ManifestEntry> localManifest, String snapshotId) {
        public PullBegin(String account,String profile,PullSelection sel,PullMode mode,List<ManifestEntry> local) {
            this(account,profile,sel,mode,local,null);
        }
    }

    public record PullPlan(List<ManifestEntry> newFiles, List<ManifestEntry> editedFiles, List<String> removedFiles,
                           long totalBytes, String sourceFingerprint, boolean selectsEverything) {}

    public record Fetch(String accountId, String profileId, List<String> hashes) {}

    /** Header of a PACK_DOWNLOAD frame; the pack follows. */
    public record PackDownload(int index, boolean last) {}

    /**
     * Client → server: request a large object from {@code offset}. Server → client: header of a chunk; bytes follow.
     */
    public record FileDownloadChunk(String accountId, String profileId, String hash, long offset, long totalSize,
                                    boolean last) {}

    // ---- Immutable snapshots (flavor is always checked against the stored identity) ----
    public record SnapshotList(String accountId,String profileId) {}
    public record SnapshotInfo(String id,String parentId,String profileId,long createdAt,String device,String sourceType,
            String installationId,String fingerprint,int fileCount,long logicalBytes,long physicalBytes,String label,boolean pinned,boolean current) {}
    public record SnapshotListResp(List<SnapshotInfo> snapshots) {}
    public record SnapshotGet(String accountId,String profileId,String snapshotId) {}
    public record SnapshotUpdate(String accountId,String profileId,String snapshotId,String label,Boolean pinned,boolean delete) {}
    public record SnapshotRetention(String accountId,String profileId,int keepLast) {}
    public record SnapshotVerify(String accountId,String profileId,String snapshotId) {}
    public record EmailVerifyRequest() {}
    public record EmailVerifyConfirm(String token) {}

    // ---- Accounts ----
    public record AccountList() {}

    public record AccountListResp(List<AccountInfo> accounts) {}

    public record AccountCreate(String username, String role, String email, String password, String publicKey,
                                Long quotaMb, Integer maxUsers, Long groupQuotaMb) {}

    /**
     * Null fields are unchanged. For quotas and max users, a negative value means "unlimited".
     * An empty email clears it.
     */
    public record AccountUpdate(String accountId, String email, Long quotaMb, Integer maxUsers, Long groupQuotaMb) {}

    /** Exactly one of password or publicKey. */
    public record AccountSetCredential(String accountId, String password, String publicKey) {}

    public record AccountDelete(String accountId, String confirmUsername) {}

    // ---- Invites ----
    public record InviteCreate(String role, Integer uses, Long expiresAt) {}

    public record InviteInfo(String id, String role, String ownerId, String ownerName, Integer usesLeft,
                             Long expiresAt, long createdAt) {}

    public record InviteCreated(String code, InviteInfo invite) {}

    public record InviteList() {}

    public record InviteListResp(List<InviteInfo> invites) {}

    public record InviteRevoke(String id) {}

    public record InviteRedeem(String secret, String username, String password, String publicKey, String email) {}

    // ---- Password reset ----
    public record ResetRequest(String username) {}

    public record ResetConfirm(String username, String code, String newPassword) {}
}
