package io.github.azotorp.wowuisync.server.sync;

import io.github.azotorp.wowuisync.common.protocol.ErrorCodes;
import io.github.azotorp.wowuisync.common.protocol.Frame;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.protocol.Wire;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.net.ApiException;
import io.github.azotorp.wowuisync.server.net.ServerContext;
import io.github.azotorp.wowuisync.server.net.Session;

/** Status, manifests, push and pull. Implemented in Phase 6. */
public class SyncService {
    protected final ServerContext ctx;
    protected final Storage storage;

    public SyncService(ServerContext ctx, Storage storage) {
        this.ctx = ctx;
        this.storage = storage;
    }

    private static ApiException na() {
        return new ApiException(ErrorCodes.UNAVAILABLE, "Not available yet.");
    }

    public Msg.StatusResp status(AccountRow actor, Msg.StatusReq m) throws Exception { throw na(); }
    public Msg.ManifestResp manifest(AccountRow actor, Msg.ManifestGet m) throws Exception { throw na(); }
    public Msg.PushNeed pushBegin(AccountRow actor, Session s, Msg.PushBegin m) throws Exception { throw na(); }
    public Msg.PushDone pushCommit(AccountRow actor, Session s, Msg.PushCommit m) throws Exception { throw na(); }
    public void pushCancel(Session s, Msg.PushCancel m) {}
    public Msg.PullPlan pullBegin(AccountRow actor, Msg.PullBegin m) throws Exception { throw na(); }
    public Msg.Ok packUpload(AccountRow actor, Session s, Frame f) throws Exception { throw na(); }
    public Msg.Ok fileUploadChunk(AccountRow actor, Session s, Frame f) throws Exception { throw na(); }
    public Msg.ResumeOffset resumeQuery(Session s, Msg.ResumeQuery m) throws Exception { throw na(); }
    public void fetch(AccountRow actor, Msg.Fetch m, Wire wire, int id) throws Exception { throw na(); }
    public void fileDownload(AccountRow actor, Msg.FileDownloadChunk m, Wire wire, int id) throws Exception { throw na(); }
    public void sessionClosed(Session s) {}
}
