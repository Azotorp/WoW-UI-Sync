> **1.1.1-alpha update:** See the current root README and ENGINEERING-REPORT.md. Normal server upgrades now support JAR replacement and automatic checkpointed startup migration; offline release activation is optional. Client settings and cache survive JAR replacement. Older instructions below describe the original 1.1.0 workflow.

# WoW UI Sync 1.1.0-alpha — server

Requires Java 21+. The runtime needs no Maven, rsync, restic, Python, screen or desktop display. Every server-facing wrapper is directly in `server/` beside `wow-ui-sync.server.jar`.

## Fresh Ubuntu install

Extract the release ZIP, keep server data outside release directories, and run as a dedicated unprivileged OS user:

```bash
cd /opt/wow-ui-sync/server
chmod +x ./*.sh
./setup.sh --data /var/lib/wow-ui-sync
./start.sh --foreground --data /var/lib/wow-ui-sync
```

The interactive setup asks for host, port and root credentials, generates TLS identity and prints the certificate fingerprint. It refuses an initialized database. Review the host/firewall settings; do not expose unrelated ports. Open the client and add the root connection using that fingerprint. Root can create users and invitations in Accounts.

All paths are quoted; wrappers work from any current directory. `--help` is available on each wrapper. `stop.sh --data ...` requests graceful shutdown; `status.sh --data ...` reports the OS data lock and versions. A second process using the same data directory is refused.

## systemd

Use the included service template with a `wowuisync` OS account. Adjust `/opt/wow-ui-sync/server`, `User`, `Group` and `ReadWritePaths` to the real installation. Put `WUS_DATA=/var/lib/wow-ui-sync` in `/etc/wow-ui-sync.env`, owned by root. Set ownership of the data directory to the service user. Run the daemon through systemd only:

```bash
sudo systemctl daemon-reload
sudo systemctl enable --now wow-ui-sync
sudo systemctl status wow-ui-sync
```

Do not combine screen/restart loops with systemd. The template uses Restart=on-failure, not an unconditional loop. Stop the service with systemctl before an upgrade. For a custom data path, update the template's ReadWritePaths as well as WUS_DATA.

## Windows and desktop mode

Run `setup.bat --data C:\WoWUISyncData`, then `start.bat --data C:\WoWUISyncData`. Paths with spaces must be quoted. The Windows wrappers run in the current terminal and forward all arguments. Optional local desktop status and start/stop controls:

```text
java -jar wow-ui-sync.server.jar gui --data <data-directory>
```

A headless host rejects `gui` cleanly; use CLI and the authenticated client's Accounts/Snapshots panels. The local console is deliberately limited to service status, account inventory, logs and integrity checks.

## Existing v1 installation

Do not run setup over an old installation. Preserve the old JAR and scripts. Read UPGRADE-1.1.md before migration. Stop all writers; migration checkpoints the DB/config/keys/TLS identity, verifies old objects and creates migration_baseline snapshots. New writes require protocol 2; v1 clients receive an update-required response. The TLS fingerprint and account IDs remain unchanged.

## Maintenance

```bash
./verify.sh --data /var/lib/wow-ui-sync
java -jar wow-ui-sync.server.jar migrate --data /var/lib/wow-ui-sync
java -jar wow-ui-sync.server.jar gc --data /var/lib/wow-ui-sync
```

Verify is read-only. For a consistent full verification, stop writers first. GC is offline and has a 48-hour grace period; every retained snapshot and current head remains a root. Snapshot retention defaults to keeping everything. Quota exhaustion blocks new uploads; it never silently deletes history. Unreferenced cancelled-upload objects and partials also count against reservation limits until collected.

SMTP recovery requires an email ownership verification from My account. Existing unverified addresses are not accepted for password reset. Reset requests have IP/account cooldowns and a bounded delivery queue.

The database, config and TLS identity are external to releases. Take independent off-host backups of the full data directory, including content and metadata, while stopped or with a coordinated snapshot. Snapshots on the same disk do not protect against losing that disk.

For a manually registered future flavor, add its exact safe identifier to `additionalFlavors` in the authoritative persistent server.json and restart. This extends the allowlist; it does not allow copying from a different flavor. Retention can be explicitly enabled in the client's Snapshots panel. Interrupted recovery snapshots remain pinned if the client cannot send its completion acknowledgement; unpin them only after verifying recovery is complete.
