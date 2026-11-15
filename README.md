# WoW UI Sync 1.2.0-alpha

Self-hosted synchronization for the WoW Forever UI (`_forever_`): add-on files, declared SavedVariables, independently selected orphan data, and explicit general WTF settings. **This is an isolated alpha test build. Keep your working 1.0.0 installation and backups.**

Java 21+ runs both JARs. Building requires JDK 21+, Maven 3.9+, and Python 3. Linux service management additionally uses systemd, `runuser`, and an existing OS service account; `edit-service.sh` uses nano. Windows and macOS can run the JARs directly. Only Forever synchronization is enabled; other recognized versions remain listed as unsupported.

## Download layout and startup

- `client/wow-ui-sync.client.jar`: `java -jar client/wow-ui-sync.client.jar`
- `server/wow-ui-sync.server.jar`: headless server; `java -jar server/wow-ui-sync.server.jar version`
- `server/setup.sh`: interactive Linux service setup, with `--dry-run` for a configuration preview. Defaults: resolved Java executable, service `wow-ui-sync`, detected primary interface IP (never localhost fallback), port `2027`, JAR-relative `data`, and detected invoking OS user/primary group. After successful installation with a remote bind address, it offers to allow the chosen TCP port in an already-active UFW firewall. Type `YES` to allow it; inactive UFW is never enabled, and SSH/other rules stay unchanged.
- `server/update.sh SERVICE CANDIDATE_JAR`: stopped-service JAR replacement with rollback on a failed restart.
- `server/edit-service.sh SERVICE`: edit a temporary unit with nano, validate, install, and reload; prints the restart command.
- `server/uninstall.sh SERVICE`: explicitly confirm removal of only that unit. Data, keys, backups and JAR are preserved.
- Source, repeatable tests, build/package scripts, `UPGRADE.md`, `TEST-REPORT.md`, and design documents are included.

**Start with [UPGRADE.md](UPGRADE.md).** Its separate-test-service procedure keeps production on its current port and data directory. There is no production deployment in this delivery. 1.2 uses protocol 2; a 1.2 client requires a 1.2 server and vice versa. Store format/schema stay compatible with 1.0. Existing-service upgrades can replace the configured server JAR and restart that same service; no installer or data migration is required. Update the client JAR separately.

For manual server setup on a platform without systemd:

```sh
java -jar server/wow-ui-sync.server.jar install --data /absolute/path/to/independent-test-data
java -jar server/wow-ui-sync.server.jar run --data /absolute/path/to/independent-test-data
```

Choose a distinct test port during installation. Root can use a password or an OpenSSH public key. The private key stays on the client. TLS fingerprints, account identity, credentials, and current manifests live in the data directory. Never recreate that directory or rerun installation to update an existing store.

## Selecting and syncing

1. In client Settings, select the WoW **parent installation folder**. A version folder with a valid executable or `.flavor.info` is accepted even if Interface/WTF do not exist yet.
2. Create a connection to the separate test server. Confirm its TLS fingerprint and sign in. Do not use a production connection with this alpha client.
3. **Push** and **Pull** both show one row for each top-level `Interface/AddOns/<folder>`. Each row has independent **Addon** and **Include Data** checkboxes, title, exact folder, explanatory family and dependencies, numeric Size and Data Size. Hover for full metadata and locations.
4. **Family is descriptive.** It never combines folders or selects dependencies for you. Missing or deselected required dependencies produce a warning. Optional dependencies remain labeled optional.
5. Unrecognized/shared SavedVariables have a separate, data-only table. Rows group all locations of the same basename **within the same account/character scope**; hover shows every actual path. Unknown `Blizzard_` rows sort first and say **Blizzard (assumed)**. Shared ownership is explicitly marked; no arbitrary owner is picked.
6. General WTF settings are a separate checkbox, initially unchecked. They exclude every recognized SavedVariables path. Defaults still exclude `Config.wtf`, `.bak`, `.old` and the other 1.0 default patterns; remove an exclusion in Settings deliberately if you want those files synced. Ownership of `.lua.bak` is supported when not excluded.
7. **Unchecked means skip.** Unselected folders/data survive. Pull's local-only table defaults to Keep; explicit Remove affects only absent-from-server folders, with exact file deletion preview. A data-only pull preserves add-on files. Push preserves unrelated server files/data, and empty Push is blocked.
8. Pull previews actual creates, updates and deletions before applying. Back up Interface and/or WTF independently; both initially enabled. Estimated category sizes and backup location are shown. Disabled backups require a warning confirmation.

Selected file folders synchronize their source file set, including removing obsolete files **inside that selected folder**. Data settings overwrite only selected source paths; missing source data never authorizes deleting destination data. General WTF selection copies the source's non-SavedVariables paths; it does not delete unrelated destination settings.

## Backups and recovery

New backups are verified, immutable snapshots under `<version>/.wus-backups/<timestamped-id>/`. A completed `backup.json` records selected categories and file hashes. Incomplete `.partial-*` folders are not listed as successful snapshots. Nothing renames/moves a live folder to create a backup. Failure, cancellation or insufficient space prevents Pull from applying changes.

Use **Backups → Restore** to verify a snapshot, back up the current selected categories, and replace only the categories in that snapshot. A selected category that was absent is restored as absent; an unselected category remains untouched. Legacy `Interface_<account>_<timestamp>` / `WTF_<account>_<timestamp>` backups remain readable; only present legacy categories are restored.

Downloads stage before live writes. Changed local files or server manifests after preview abort safely. Writes use verified temp files and rename; a pending-operation journal warns on next startup after an interrupted apply. **The whole Pull is not a single filesystem transaction**: if interrupted during apply, restore its backup categories and retry. With backups disabled, recovery may require your own copy. WoW and external add-on managers should be closed while applying or restoring.

## Client scan/progress update

The `WoW-UI-Sync-Client-1.2.0-alpha-scanfix.zip` follow-up updates only the client. Finish any current transfer, close the client, replace its JAR in the **same directory**, preserve the adjacent `wow-ui-sync-data` folder, and reopen it. Existing completed scan-cache rows remain valid; running from a new directory without that folder starts a cold cache. No server JAR replacement, service restart or data migration is needed. See CLIENT-UPDATE.md.

## Scan cache and progress

The client checkpoints verified file hashes in `client.db` during scanning, every 64 completed hashes or approximately one second, and flushes completed results on cancellation. A killed process can lose the final uncommitted batch, rather than the entire scan. Keys use canonical installation paths, logical relative paths, size, precise mtime and available file identity/change timestamps. Stale/corrupt entries rehash; whole-database errors fail closed. Entries expire after 24 hours and backward clock anomalies invalidate reuse. Defaults restore the 1.0.0 CPU-based worker bound (up to eight workers; `-Dwowuisync.hashThreads=1..8` overrides for profiling). Read buffers are reused per worker and Unix timestamp support is checked once per scan, avoiding unsupported Unix attribute calls on Windows. Scan counts, read/cache statistics, speed/ETA and current file now occupy separate rows, matching transfer progress.

An empty server's automatic status check defers content hashing; a real first Push still scans and verifies its content. Progress shows phases, counts, transfer bytes/rate, elapsed/ETA, scan cache hits/hashed bytes and active work descriptions. Network retry does not claim to resume a committed transaction. Commit acknowledgement and the returned fingerprint are checked before reporting Push success.

## Build and validation

```sh
./build.sh
./package.sh --no-build
```

Build outputs are `dist/wow-ui-sync-client.jar` and `dist/wow-ui-sync-server.jar`. Packaging copies them to the dotted deployable names inside `dist/WoW-UI-Sync-1.2.0-alpha.zip`, validates manifests, includes source/tests, and writes a SHA-256 file. `./package.sh` builds/tests first. ZIP entry timestamps and ordering are fixed; Maven JAR byte reproducibility is limited by the original build configuration.

See [TEST-REPORT.md](TEST-REPORT.md) for measured Linux results and explicit platform/systemd verification limits. [DESIGN-1.2.md](DESIGN-1.2.md) supersedes conflicting 1.0 sync/backup/grouping design details; `DESIGN.md` remains the historical foundation. The separate browse/discovery/exclusions refactor described in `CLIENT_REFACTOR_PLAN.md` is not part of this alpha's implementation.

GPL v3; see LICENSE. Not affiliated with or endorsed by Blizzard Entertainment.
