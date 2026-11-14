package io.github.azotorp.wowuisync.common.protocol;

/** Frame type codes. Binary types carry a JSON header followed by raw bytes. */
public enum MessageType {
    HELLO(1, Msg.Hello.class),
    HELLO_OK(2, Msg.HelloOk.class),
    DATA_JOIN(3, Msg.DataJoin.class),
    PING(4, Msg.Ping.class),
    ERROR(5, Msg.Error.class),
    OK(6, Msg.Ok.class),

    LOGIN_PASSWORD(10, Msg.LoginPassword.class),
    LOGIN_KEY_BEGIN(11, Msg.LoginKeyBegin.class),
    LOGIN_KEY_CHALLENGE(12, Msg.LoginKeyChallenge.class),
    LOGIN_KEY_PROOF(13, Msg.LoginKeyProof.class),
    LOGIN_OK(14, Msg.LoginOk.class),
    LOGIN_FAIL(15, Msg.LoginFail.class),

    STATUS_REQ(20, Msg.StatusReq.class),
    STATUS_RESP(21, Msg.StatusResp.class),
    MANIFEST_GET(22, Msg.ManifestGet.class),
    MANIFEST(23, Msg.ManifestResp.class),

    PUSH_BEGIN(30, Msg.PushBegin.class),
    PUSH_NEED(31, Msg.PushNeed.class),
    PACK_UPLOAD(32, Msg.PackUpload.class, true),
    FILE_UPLOAD_CHUNK(33, Msg.FileUploadChunk.class, true),
    RESUME_QUERY(34, Msg.ResumeQuery.class),
    RESUME_OFFSET(35, Msg.ResumeOffset.class),
    PUSH_COMMIT(36, Msg.PushCommit.class),
    PUSH_DONE(37, Msg.PushDone.class),
    PUSH_CANCEL(38, Msg.PushCancel.class),

    PULL_BEGIN(40, Msg.PullBegin.class),
    PULL_PLAN(41, Msg.PullPlan.class),
    FETCH(42, Msg.Fetch.class),
    PACK_DOWNLOAD(43, Msg.PackDownload.class, true),
    FILE_DOWNLOAD_CHUNK(44, Msg.FileDownloadChunk.class, true),

    SNAPSHOT_LIST(80, Msg.SnapshotList.class),
    SNAPSHOT_LIST_RESP(81, Msg.SnapshotListResp.class),
    SNAPSHOT_GET(82, Msg.SnapshotGet.class),
    SNAPSHOT_UPDATE(83, Msg.SnapshotUpdate.class),
    SNAPSHOT_VERIFY(84, Msg.SnapshotVerify.class),
    SNAPSHOT_RETENTION(87,Msg.SnapshotRetention.class),
    EMAIL_VERIFY_REQUEST(85, Msg.EmailVerifyRequest.class),
    EMAIL_VERIFY_CONFIRM(86, Msg.EmailVerifyConfirm.class),

    ACCOUNT_LIST(50, Msg.AccountList.class),
    ACCOUNT_LIST_RESP(51, Msg.AccountListResp.class),
    ACCOUNT_CREATE(52, Msg.AccountCreate.class),
    ACCOUNT_UPDATE(53, Msg.AccountUpdate.class),
    ACCOUNT_SET_CREDENTIAL(54, Msg.AccountSetCredential.class),
    ACCOUNT_DELETE(55, Msg.AccountDelete.class),
    ACCOUNT_INFO(56, Msg.AccountInfo.class),

    INVITE_CREATE(60, Msg.InviteCreate.class),
    INVITE_CREATED(61, Msg.InviteCreated.class),
    INVITE_LIST(62, Msg.InviteList.class),
    INVITE_LIST_RESP(63, Msg.InviteListResp.class),
    INVITE_REVOKE(64, Msg.InviteRevoke.class),
    INVITE_REDEEM(65, Msg.InviteRedeem.class),

    RESET_REQUEST(70, Msg.ResetRequest.class),
    RESET_CONFIRM(71, Msg.ResetConfirm.class);

    private static final MessageType[] BY_CODE = new MessageType[256];

    static {
        for (MessageType t : values()) {
            if (BY_CODE[t.code] != null) throw new ExceptionInInitializerError("Duplicate code " + t.code);
            BY_CODE[t.code] = t;
        }
    }

    private final int code;
    private final Class<?> type;
    private final boolean binary;

    MessageType(int code, Class<?> type) {
        this(code, type, false);
    }

    MessageType(int code, Class<?> type, boolean binary) {
        this.code = code;
        this.type = type;
        this.binary = binary;
    }

    public int code() {
        return code;
    }

    public Class<?> type() {
        return type;
    }

    public boolean binary() {
        return binary;
    }

    public static MessageType byCode(int code) {
        MessageType t = code >= 0 && code < 256 ? BY_CODE[code] : null;
        if (t == null) throw new IllegalArgumentException("Unknown message type " + code);
        return t;
    }

    public static MessageType forClass(Class<?> c) {
        for (MessageType t : values()) if (t.type == c) return t;
        throw new IllegalArgumentException("No message type for " + c.getName());
    }
}
