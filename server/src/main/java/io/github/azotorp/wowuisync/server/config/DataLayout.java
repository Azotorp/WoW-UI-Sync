package io.github.azotorp.wowuisync.server.config;

import java.nio.file.Path;

/** Paths inside the server data folder (DESIGN.md §5.1). */
public record DataLayout(Path root) {
    public Path config() { return root.resolve("server.json"); }
    public Path db() { return root.resolve("wowuisync.db"); }
    public Path dbBackups() { return root.resolve("db-backups"); }
    public Path keys() { return root.resolve("keys"); }
    public Path keyFile(String accountId) { return keys().resolve(accountId + ".pub"); }
    public Path tls() { return root.resolve("tls"); }
    public Path keystore() { return tls().resolve("keystore.p12"); }
    public Path logs() { return root.resolve("logs"); }
    public Path tmp() { return root.resolve("tmp"); }
    public Path partial() { return tmp().resolve("partial"); }
    public Path stopFlag() { return root.resolve("stop.flag"); }
    public Path accounts() { return root.resolve("accounts"); }
    public Path accountDir(String accountId) { return accounts().resolve(accountId); }
    public Path profileDir(String accountId, String profileId) { return accountDir(accountId).resolve("profiles").resolve(profileId); }
    public Path objects(String accountId, String profileId) { return profileDir(accountId, profileId).resolve("objects"); }
    public Path currentManifest(String accountId, String profileId) { return profileDir(accountId, profileId).resolve("current.manifest"); }
}
