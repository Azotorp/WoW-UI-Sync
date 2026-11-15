# Upgrade and isolated testing — 1.2.0-alpha

**Do not replace your working production installation as the first test.** This build uses protocol 2, so keep the 1.0 client for production and run 1.2 against a separate 1.2 service first. No commands were run on your live server during development.

## Separate test service

Extract the ZIP into its own directory on Ubuntu, away from the production JAR and data. From the extracted `WoW-UI-Sync-1.2.0-alpha` directory:

```sh
java -jar server/wow-ui-sync.server.jar version
sudo ./server/setup.sh --dry-run
sudo ./server/setup.sh
```

Use:

| Prompt | Test value |
|---|---|
| Service name | `wow-ui-sync-1.2` |
| Bind address | Automatically detected primary interface IP; override if needed. No localhost fallback. |
| Port | `2027`, or another unused port distinct from production |
| JAR path | The **new test directory's** `server/wow-ui-sync.server.jar` |
| Data directory | That JAR directory's `data`, or another **independent empty** directory/partition |
| Service user/group | An existing unprivileged OS account/group that can read the JAR and access the data directory |

The installer shows an existing named unit and requires `YES` before any reconfiguration. Declining leaves it alone. A second `YES` confirms the fully rendered configuration. It rejects overlapping known stores, conflicting ports, shared deployment JARs, invalid Java and invalid units; the server also holds a process lock on its store. Setup validates before replacing a unit and saves previous unit/config copies. Fresh data is initialized as the selected account; existing populated data is not recursively chowned. Use explicit `--data` in units; no JAR-side configuration is needed.

Then inspect:

```sh
sudo systemctl status wow-ui-sync-1.2.service --no-pager
sudo journalctl -u wow-ui-sync-1.2.service -n 80 --no-pager
sudo systemctl status wow-ui-sync.service --no-pager
```

The last command is read-only confirmation that production remains available. After successful service installation, setup offers **Allow TCP <selected-port> in UFW?** for a non-loopback bind and an already-active UFW firewall. Type `YES` to add only that TCP-port allow rule; declining leaves rules unchanged. It checks UFW status afterward and does not enable UFW, reset rules, reload it, or change SSH rules. Existing matching allow rules are not duplicated; conflicting deny rules require manual review. Inactive/missing UFW is left alone. Router/provider firewalls still require separate configuration. If needed manually later: `sudo ufw allow 2027/tcp` (substitute the selected port).

Extract the **client into its own directory**, retaining its separate `wow-ui-sync-data` folder. Run `java -jar client/wow-ui-sync.client.jar`, create a connection to the test host on port 2027, compare the printed test TLS fingerprint, and log in with the test root account. Test a copied WoW installation, then select a few folders/data entries and review changes before transferring. Never point two live servers at the same data directory, including via symlink or copied configuration.

## Installer follow-up fixes

Setup now defaults to service `wow-ui-sync`, the resolved Java executable and the detected primary interface IP. The user still defaults to the invoking OS user (`SUDO_USER` when using sudo), and the group follows the selected user. No external public-IP lookup is used: the address comes from the OS route/interface, so it is assigned locally and can be bound. If detection fails, enter the address explicitly. Unrelated system-unit quotation syntax no longer aborts the JAR-overlap check; ambiguous sync units and actual shared JARs still fail safely.

## Installer firewall update

This follow-up changes only the Python setup helper and documentation. Existing client/server JARs remain unchanged: no JAR replacement, data migration or service restart is needed for this installer update. Copy the updated `server/service_manager.py` and `server/setup.sh` over those in the uninstalled 1.2 package before running setup. Keep the working `wow-ui-sync-100` service untouched.

## Server upgrades: same service, replace JAR and restart

**No new installer, database migration, account reset or service replacement is required for the existing 1.0 → 1.2 store format.** The server schema stays version 1; current manifests, compressed objects, password hashes, public-key accounts and TLS keystore remain compatible. 1.2 adds only an exclusive `.store.lock` file. Update the client to 1.2 at the same time because protocol 1 peers are deliberately rejected.

When you deliberately choose to update an existing **test** instance, place the new candidate JAR outside its active JAR path and run:

```sh
sudo ./server/update.sh wow-ui-sync-1.2 /absolute/path/to/new/wow-ui-sync.server.jar
sudo systemctl status wow-ui-sync-1.2.service --no-pager
```

The updater reads the selected unit's actual JAR path, checks the candidate's version, asks for `YES`, backs up the active JAR, stops only that service, replaces the JAR preserving ownership, restarts, and checks status. Failed restart restores the previous JAR. It does not replace data, keys, client settings or backups. Custom units with wrappers/multiple ExecStart lines require the manual procedure below. Service drop-ins are not resolved by this updater; review `systemctl cat` before use.

Later, **only when you choose a production cutover**, the same procedure targets the existing production service:

```sh
sudo systemctl cat wow-ui-sync.service
sudo ./server/update.sh wow-ui-sync /absolute/path/to/new/wow-ui-sync.server.jar
sudo systemctl status wow-ui-sync.service --no-pager
```

There is no need to run `setup.sh` against your existing production service. The updater keeps its configured filename, including `wow-ui-sync.server.jar` or `wow-ui-sync-server.jar`.

Manual equivalent, substituting the JAR path shown by `systemctl cat`:

```sh
PRODUCTION_JAR=/absolute/path/from/the/existing/unit/wow-ui-sync.server.jar
NEW_SERVER_JAR=/absolute/path/to/new/wow-ui-sync.server.jar
sudo systemctl stop wow-ui-sync.service
sudo cp -a "$PRODUCTION_JAR" "$PRODUCTION_JAR.before-1.2"
sudo cp "$NEW_SERVER_JAR" "$PRODUCTION_JAR"
sudo systemctl start wow-ui-sync.service
sudo systemctl status wow-ui-sync.service --no-pager
```

`cp` over an existing target retains its owner/mode. Keep the `.before-1.2` copy and your existing backups. Preserve the **whole data directory** for identity and content: `server.json`, `wowuisync.db` and SQLite sidecars when present, `keys/`, `tls/keystore.p12`, `accounts/` and other stored files. The optional keys are application OpenSSH **public keys**, not server shell access; the TLS keystore is a separate identity. Do not regenerate either during update.

## Client scan/progress follow-up

For `WoW-UI-Sync-Client-1.2.0-alpha-scanfix.zip`, let an active transfer finish, close the client, and replace **only** its client JAR in the directory you already use. Preserve its adjacent `wow-ui-sync-data` directory so the completed scan cache and connections are reused. Launch the replacement client. The existing 1.2 server JAR, service, data and protocol need no changes or restart. No migration or new installation is required; the 1.0 server remains a separate incompatible protocol version.

## Client update is separate

Close the client, retain its adjacent `wow-ui-sync-data`, replace its client JAR, and run it again:

```sh
java -jar client/wow-ui-sync.client.jar
```

No client installer is required. The existing client DB schema remains version 1; a separate optional `scan_cache_v2` table stores new scan metadata. The old client can still read the DB, but cannot read new backup snapshots; keep a separate 1.0 client directory for rollback and production until testing is accepted. The alpha adds neither multi-version synchronization nor automatic merging of client/server connections.

## Rollback and deliberate moves

Stop the affected service, replace its JAR with the saved 1.0 JAR, then start that **same** service. Switch back to the 1.0 client. Do not copy test accounts or test data over production. This release has no irreversible database/storage conversion. Server rollback restores software compatibility, **not the previous UI content** if you already pushed changes; use retained backups for content recovery. The server still keeps only the current manifest, not historical server snapshots.

For a deliberate data-directory move: stop its service, make an offline copy of the **entire** store, preserve ownership/permissions, update the copied `server.json` `dataDir` and the unit's explicit `--data` argument/working directory, validate the unit, daemon-reload and start. Retain the original untouched until verified. Do not copy a running SQLite store as a migration method or reuse it concurrently; the 1.0 server predates the new lock and cannot cooperate with it.

Edit/remove only the test service with:

```sh
sudo ./server/edit-service.sh wow-ui-sync-1.2
sudo systemctl restart wow-ui-sync-1.2.service
sudo ./server/uninstall.sh wow-ui-sync-1.2
```

Uninstall intentionally performs no data deletion. Remove an obsolete test directory manually only after verifying it is independent from production and no longer needed.
