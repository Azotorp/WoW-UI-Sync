package io.github.azotorp.wowuisync.server.net;

import io.github.azotorp.wowuisync.common.Version;
import io.github.azotorp.wowuisync.common.codec.Codec;
import io.github.azotorp.wowuisync.common.protocol.ErrorCodes;
import io.github.azotorp.wowuisync.common.protocol.Frame;
import io.github.azotorp.wowuisync.common.protocol.MessageType;
import io.github.azotorp.wowuisync.common.protocol.Msg;
import io.github.azotorp.wowuisync.common.protocol.Wire;
import io.github.azotorp.wowuisync.server.db.AccountRow;
import io.github.azotorp.wowuisync.server.service.AccountService;
import io.github.azotorp.wowuisync.server.service.AuthService;
import io.github.azotorp.wowuisync.server.service.InviteService;
import io.github.azotorp.wowuisync.server.service.ResetService;
import io.github.azotorp.wowuisync.server.sync.SyncService;
import io.github.azotorp.wowuisync.server.util.Logs;

import javax.net.ssl.SSLSocket;
import java.io.EOFException;
import java.io.IOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.sql.SQLException;
import java.util.logging.Level;

/** Handles one TLS connection: a control connection (HELLO first) or a data connection (DATA_JOIN first). */
public final class ConnectionHandler implements Runnable {
    /** Services shared by all connections. */
    public record Services(AuthService auth, AccountService accounts, InviteService invites, ResetService reset, SyncService sync) {}

    private static final int IDLE_TIMEOUT_MS = 10 * 60_000;

    private final ServerContext ctx;
    private final Services svc;
    private final SSLSocket socket;
    private final String ip;

    public ConnectionHandler(ServerContext ctx, Services svc, SSLSocket socket) {
        this.ctx = ctx;
        this.svc = svc;
        this.socket = socket;
        this.ip = socket.getInetAddress().getHostAddress();
    }

    @Override
    public void run() {
        Wire wire = null;
        try {
            socket.setSoTimeout(30_000);
            socket.setTcpNoDelay(true);
            socket.startHandshake();
            wire = new Wire(socket.getInputStream(), socket.getOutputStream(), socket);
            Frame first = wire.read();
            socket.setSoTimeout(IDLE_TIMEOUT_MS);
            if (first.type() == MessageType.HELLO) control(wire, first);
            else if (first.type() == MessageType.DATA_JOIN) data(wire, first);
            else wire.sendError(first.requestId(), ErrorCodes.BAD_REQUEST, "Expected HELLO or DATA_JOIN");
        } catch (EOFException | SocketTimeoutException e) {
            // client went away
        } catch (SocketException e) {
            Logs.LOG.fine("Connection from " + ip + " closed: " + e.getMessage());
        } catch (IOException e) {
            Logs.LOG.fine("Connection from " + ip + " ended: " + e.getMessage());
        } catch (Exception e) {
            Logs.LOG.log(Level.WARNING, "Connection from " + ip + " failed: " + e.getMessage(), e);
        } finally {
            if (wire != null) wire.close();
            try {
                socket.close();
            } catch (IOException ignored) {
                // ignore
            }
        }
    }

    // ------------------------------------------------------------------ control connection

    private void control(Wire wire, Frame helloFrame) throws Exception {
        Msg.Hello hello = helloFrame.message(Msg.Hello.class);
        if (hello.protocolVersion() < Version.MIN_PROTOCOL_VERSION) {
            wire.send(helloFrame.requestId(), new Msg.LoginFail("version",
                    "Please update WoW UI Sync to version " + Version.MIN_APP_VERSION + " or newer.", null, Version.MIN_APP_VERSION));
            return;
        }
        if (hello.protocolVersion() > Version.PROTOCOL_VERSION && Version.PROTOCOL_VERSION < minPeerFor(hello)) {
            wire.send(helloFrame.requestId(), new Msg.LoginFail("version",
                    "This server is outdated (version " + Version.APP_VERSION + "). Ask its owner to update it.", null, null));
            return;
        }
        Codec codec = Codec.negotiate(hello.codecs());
        wire.send(helloFrame.requestId(), new Msg.HelloOk(Version.PROTOCOL_VERSION, Version.APP_VERSION, codec.wireName(),
                Version.MIN_APP_VERSION, ctx.smtp.enabled(), ctx.config.maxDataConnectionsPerSession));
        wire.setCodec(codec);

        Session session = null;
        AuthService.Challenge challenge = null;
        try {
            while (!ctx.stopping()) {
                Frame f = wire.read();
                int id = f.requestId();
                try {
                    if (f.type() == MessageType.PING) {
                        wire.send(id, new Msg.Ok(null));
                        continue;
                    }
                    if (session == null) {
                        switch (f.type()) {
                            case LOGIN_PASSWORD -> {
                                AuthService.Result r = svc.auth().loginPassword(ip, f.message(Msg.LoginPassword.class));
                                session = finishLogin(wire, id, r, null, hello, codec);
                            }
                            case LOGIN_KEY_BEGIN -> {
                                Object o = svc.auth().beginKey(ip, f.message(Msg.LoginKeyBegin.class));
                                if (o instanceof AuthService.Result r) {
                                    wire.send(id, r.fail());
                                } else {
                                    challenge = (AuthService.Challenge) o;
                                    wire.send(id, new Msg.LoginKeyChallenge(challenge.nonce(), challenge.sessionId()));
                                }
                            }
                            case LOGIN_KEY_PROOF -> {
                                if (challenge == null) {
                                    wire.sendError(id, ErrorCodes.BAD_REQUEST, "No key challenge in progress");
                                    continue;
                                }
                                AuthService.Challenge c = challenge;
                                challenge = null;
                                AuthService.Result r = svc.auth().finishKey(ip, c, f.message(Msg.LoginKeyProof.class));
                                session = finishLogin(wire, id, r, c.sessionId(), hello, codec);
                            }
                            case INVITE_REDEEM -> wire.send(id, svc.invites().redeem(ip, f.message(Msg.InviteRedeem.class)));
                            case RESET_REQUEST -> wire.send(id, svc.reset().request(ip, f.message(Msg.ResetRequest.class)));
                            case RESET_CONFIRM -> wire.send(id, svc.reset().confirm(ip, f.message(Msg.ResetConfirm.class)));
                            default -> wire.sendError(id, ErrorCodes.NOT_LOGGED_IN, "Log in first.");
                        }
                        continue;
                    }
                    AccountRow actor = currentActor(session);
                    if (actor == null) {
                        wire.sendError(id, ErrorCodes.NOT_LOGGED_IN, "Your session ended because the account changed. Log in again.");
                        return;
                    }
                    dispatchControl(wire, f, session, actor);
                } catch (ApiException e) {
                    wire.sendError(id, e.code(), e.getMessage());
                } catch (IOException e) {
                    if (e.getCause() instanceof RuntimeException || e.getMessage() != null && e.getMessage().startsWith("Malformed")) {
                        wire.sendError(id, ErrorCodes.BAD_REQUEST, e.getMessage());
                    } else {
                        throw e;
                    }
                } catch (SQLException e) {
                    Logs.LOG.log(Level.SEVERE, "Database error: " + e.getMessage(), e);
                    wire.sendError(id, ErrorCodes.INTERNAL, "Server database error.");
                } catch (RuntimeException e) {
                    Logs.LOG.log(Level.WARNING, "Request " + f.type() + " failed: " + e.getMessage(), e);
                    wire.sendError(id, ErrorCodes.INTERNAL, "Server error: " + e.getMessage());
                }
            }
            if (ctx.stopping()) wire.sendError(0, ErrorCodes.SHUTTING_DOWN, "The server is shutting down.");
        } finally {
            if (session != null) {
                svc.sync().sessionClosed(session);
                ctx.sessions.remove(session);
                session.close();
                Logs.LOG.info("Session ended: " + session.username() + " (" + session.deviceNickname() + ")");
            }
        }
    }

    private static int minPeerFor(Msg.Hello hello) {
        // A newer client tells us nothing about the oldest server it accepts; it checks HELLO_OK itself.
        return 0;
    }

    private Session finishLogin(Wire wire, int id, AuthService.Result r, String sessionId, Msg.Hello hello, Codec codec)
            throws IOException, SQLException {
        if (r.fail() != null) {
            wire.send(id, r.fail());
            return null;
        }
        AccountRow a = r.account();
        String sid = sessionId != null ? sessionId : io.github.azotorp.wowuisync.common.crypto.Passwords.randomToken(32);
        String nick = hello.deviceNickname() == null || hello.deviceNickname().isBlank() ? "Unknown device" : hello.deviceNickname();
        Session s = new Session(sid, a.id(), a.username(), a.role(), a.credentialVersion(), hello.deviceId(), nick, codec, ip, socket);
        ctx.sessions.add(s);
        wire.send(id, new Msg.LoginOk(a.id(), a.username(), a.role().wire(), sid, svc.accounts().accessibleInfos(a),
                ctx.config.maxDataConnectionsPerSession));
        Logs.LOG.info("Login: " + a.username() + " from " + ip + " (" + nick + ")");
        return s;
    }

    /** Re-reads the account; null if it was deleted or its credentials changed. */
    private AccountRow currentActor(Session s) throws SQLException {
        if (s.closed()) return null;
        AccountRow a = ctx.db.accountById(s.accountId()).orElse(null);
        if (a == null || a.credentialVersion() != s.credentialVersion()) {
            s.close();
            ctx.sessions.remove(s);
            return null;
        }
        s.refresh(a.username(), a.role(), a.credentialVersion());
        return a;
    }

    private void dispatchControl(Wire wire, Frame f, Session s, AccountRow actor) throws Exception {
        int id = f.requestId();
        switch (f.type()) {
            case STATUS_REQ -> wire.send(id, svc.sync().status(actor, f.message(Msg.StatusReq.class)));
            case MANIFEST_GET -> wire.send(id, svc.sync().manifest(actor, f.message(Msg.ManifestGet.class)));
            case PUSH_BEGIN -> wire.send(id, svc.sync().pushBegin(actor, s, f.message(Msg.PushBegin.class)));
            case PUSH_COMMIT -> wire.send(id, svc.sync().pushCommit(actor, s, f.message(Msg.PushCommit.class)));
            case PUSH_CANCEL -> {
                svc.sync().pushCancel(s, f.message(Msg.PushCancel.class));
                wire.send(id, new Msg.Ok(null));
            }
            case PULL_BEGIN -> wire.send(id, svc.sync().pullBegin(actor, f.message(Msg.PullBegin.class)));
            case ACCOUNT_LIST -> wire.send(id, svc.accounts().list(actor));
            case ACCOUNT_CREATE -> wire.send(id, svc.accounts().create(actor, f.message(Msg.AccountCreate.class)));
            case ACCOUNT_UPDATE -> wire.send(id, svc.accounts().update(actor, f.message(Msg.AccountUpdate.class)));
            case ACCOUNT_SET_CREDENTIAL -> wire.send(id, svc.accounts().setCredential(actor, s, f.message(Msg.AccountSetCredential.class)));
            case ACCOUNT_DELETE -> {
                svc.accounts().delete(actor, f.message(Msg.AccountDelete.class));
                wire.send(id, new Msg.Ok("Account deleted."));
            }
            case INVITE_CREATE -> wire.send(id, svc.invites().create(actor, f.message(Msg.InviteCreate.class)));
            case INVITE_LIST -> wire.send(id, svc.invites().list(actor));
            case INVITE_REVOKE -> {
                svc.invites().revoke(actor, f.message(Msg.InviteRevoke.class));
                wire.send(id, new Msg.Ok(null));
            }
            default -> wire.sendError(id, ErrorCodes.BAD_REQUEST, "Unexpected " + f.type() + " on the control connection.");
        }
    }

    // ------------------------------------------------------------------ data connection

    private void data(Wire wire, Frame joinFrame) throws Exception {
        Msg.DataJoin join = joinFrame.message(Msg.DataJoin.class);
        Session s = ctx.sessions.get(join.sessionId());
        if (s == null || s.closed()) {
            wire.sendError(joinFrame.requestId(), ErrorCodes.NOT_LOGGED_IN, "Unknown or ended session.");
            return;
        }
        if (!s.addData(socket, ctx.config.maxDataConnectionsPerSession)) {
            wire.sendError(joinFrame.requestId(), ErrorCodes.BUSY, "Too many data connections for this session.");
            return;
        }
        wire.setCodec(s.codec());
        try {
            wire.send(joinFrame.requestId(), new Msg.Ok(null));
            while (!ctx.stopping() && !s.closed()) {
                Frame f = wire.read();
                int id = f.requestId();
                try {
                    AccountRow actor = currentActor(s);
                    if (actor == null) {
                        wire.sendError(id, ErrorCodes.NOT_LOGGED_IN, "Session ended.");
                        return;
                    }
                    switch (f.type()) {
                        case PING -> wire.send(id, new Msg.Ok(null));
                        case PACK_UPLOAD -> wire.send(id, svc.sync().packUpload(actor, s, f));
                        case FILE_UPLOAD_CHUNK -> {
                            Msg.Ok ok = svc.sync().fileUploadChunk(actor, s, f);
                            if (ok != null) wire.send(id, ok);
                        }
                        case RESUME_QUERY -> wire.send(id, svc.sync().resumeQuery(s, f.message(Msg.ResumeQuery.class)));
                        case FETCH -> svc.sync().fetch(actor, f.message(Msg.Fetch.class), wire, id);
                        case FILE_DOWNLOAD_CHUNK -> svc.sync().fileDownload(actor, f.message(Msg.FileDownloadChunk.class), wire, id);
                        default -> wire.sendError(id, ErrorCodes.BAD_REQUEST, "Unexpected " + f.type() + " on a data connection.");
                    }
                } catch (ApiException e) {
                    wire.sendError(id, e.code(), e.getMessage());
                } catch (SQLException e) {
                    Logs.LOG.log(Level.SEVERE, "Database error: " + e.getMessage(), e);
                    wire.sendError(id, ErrorCodes.INTERNAL, "Server database error.");
                } catch (RuntimeException e) {
                    Logs.LOG.log(Level.WARNING, "Data request " + f.type() + " failed: " + e.getMessage(), e);
                    wire.sendError(id, ErrorCodes.INTERNAL, "Server error: " + e.getMessage());
                }
            }
        } finally {
            s.removeData(socket);
        }
    }
}
