package io.github.azotorp.wowuisync.server.net;

import io.github.azotorp.wowuisync.common.Role;
import io.github.azotorp.wowuisync.common.codec.Codec;

import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/** A logged-in session: one control connection plus up to N data connections. */
public final class Session {
    private final String id;
    private final String accountId;
    private volatile String username;
    private volatile Role role;
    private volatile int credentialVersion;
    private final String deviceId;
    private final String deviceNickname;
    private final Codec codec;
    private final String ip;
    private final Socket controlSocket;
    private final Set<Socket> dataSockets = ConcurrentHashMap.newKeySet();
    private final AtomicInteger dataCount = new AtomicInteger();
    private volatile boolean closed;
    private volatile long authenticatedAt=System.currentTimeMillis();
    public void reauthenticated(){authenticatedAt=System.currentTimeMillis();}
    public boolean freshCredential(){return System.currentTimeMillis()-authenticatedAt<300_000;}

    public Session(String id, String accountId, String username, Role role, int credentialVersion, String deviceId,
                   String deviceNickname, Codec codec, String ip, Socket controlSocket) {
        this.id = id;
        this.accountId = accountId;
        this.username = username;
        this.role = role;
        this.credentialVersion = credentialVersion;
        this.deviceId = deviceId;
        this.deviceNickname = deviceNickname;
        this.codec = codec;
        this.ip = ip;
        this.controlSocket = controlSocket;
    }

    public String id() { return id; }
    public String accountId() { return accountId; }
    public String username() { return username; }
    public Role role() { return role; }
    public int credentialVersion() { return credentialVersion; }
    public String deviceId() { return deviceId; }
    public String deviceNickname() { return deviceNickname; }
    public Codec codec() { return codec; }
    public String ip() { return ip; }
    public boolean closed() { return closed; }

    public void refresh(String username, Role role, int credentialVersion) {
        this.username = username;
        this.role = role;
        this.credentialVersion = credentialVersion;
    }

    public boolean addData(Socket s, int max) {
        if (dataCount.incrementAndGet() > max) {
            dataCount.decrementAndGet();
            return false;
        }
        dataSockets.add(s);
        return true;
    }

    public void removeData(Socket s) {
        if (dataSockets.remove(s)) dataCount.decrementAndGet();
    }

    /** Closes the control connection and all data connections. */
    public void close() {
        closed = true;
        closeQuietly(controlSocket);
        for (Socket s : dataSockets) closeQuietly(s);
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {
            // ignore
        }
    }
}
