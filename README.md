# WoW UI Sync 1.1.2-alpha

Self-hosted Java 21 desktop client and TLS server for synchronizing WoW addons and settings, with incremental transfers, deduplication and immutable server-side snapshots. Different WoW flavors stay isolated. GPLv3; not affiliated with Blizzard.

This release restores fast cached status scans and supports normal server upgrades by replacing the existing JAR and restarting the same service. See [ENGINEERING-REPORT.md](ENGINEERING-REPORT.md) for measurements, provenance, validation and limitations, and [CHANGELOG.md](CHANGELOG.md) for changes.

## Downloads and package

The deployment ZIP contains `client/wow-ui-sync.client.jar`, `server/wow-ui-sync.server.jar`, flat server scripts, documentation, license, release metadata and checksums. No Maven or external sync engine is needed at runtime. Never put mutable data inside a temporary extracted release folder.

## Existing server: JAR-only upgrade

Keep the actual installed JAR filename, path, service name and startup arguments. Inspect the existing service if unsure:

```bash
systemctl cat YOUR_EXISTING_SERVICE
systemctl show YOUR_EXISTING_SERVICE --property=ExecStart,User,WorkingDirectory
```

1. Stop that existing service.
2. Retain the previous JAR and replace the JAR at the exact path used by its existing startup command. Preserve its owner and permissions.
3. Start the same service. Do not run setup again.

The server recognizes supported existing schemas and migrates automatically before opening the listener, holding its existing data-directory lock. Necessary metadata checkpoints use SQLite `VACUUM INTO`, including committed WAL data, plus verified configuration, keys, TLS identity and manifest copies under `migration-checkpoints/`. Existing protocol-2 installations normally need no server schema change. Config, accounts, snapshots, objects and service settings remain where they are.

```bash
sudo systemctl stop YOUR_EXISTING_SERVICE
# Retain old JAR; replace its actual installed path and preserve ownership/mode.
sudo systemctl start YOUR_EXISTING_SERVICE
sudo journalctl -u YOUR_EXISTING_SERVICE -n 80 --no-pager
java -jar /ACTUAL/INSTALLED/SERVER.jar ready --data /ACTUAL/EXISTING/DATA
```

`ready` verifies the configured local TLS identity and an application protocol handshake with this release. A loaded systemd process alone is insufficient. First migration/metadata warmup can delay readiness; inspect the journal instead of resetting data.

The optional Linux updater can automate this procedure:

```bash
sudo ./server/update.sh --jar ./replacement.server.jar \
  --service YOUR_EXISTING_SERVICE --data /ACTUAL/EXISTING/DATA \
  --installed-jar /ACTUAL/INSTALLED/SERVER.jar --timeout 120
```

The convenience updater needs Python 3. It inspects the existing unit, supports direct `java -jar` units and requires `--installed-jar` for wrapper-based units. It preserves the existing unit, ownership and mode, atomically replaces the JAR, retains the previous binary and checks pinned TLS/protocol readiness. It stops the service on a failed readiness check; it never automatically reinstalls or rolls the database back. Legacy verified release-ZIP activation remains available through `update.sh --from ...`; this is optional.

Unsupported/newer schemas or repository formats fail closed. Migration failure keeps checkpoints and diagnostics and prevents listener startup. Preserve the entire data directory, stop the service, and restore the verified checkpoint with its matching binary using the documented offline recovery procedure; never put an older JAR against a newer incompatible schema. The historical checkpoint rollback command requires an activation record from the optional ZIP updater; manual JAR upgrades have their own checkpoint and need explicit offline restore.

## Existing client

Close the client, replace only its existing JAR at the same path, and reopen it. Keep `wow-ui-sync-data` and its database, settings, pins, device identity and keys. Cache schema 1 upgrades to 2 automatically with a SQLite metadata backup. Old millisecond-only v1 hashes cannot supply CDC manifests, so the first scan fills the new cache. Subsequent ordinary checks reuse unchanged file hashes and chunk references, including after restarts and reconnects.

Upgrade a protocol-1 server before using a protocol-2 client. Version errors identify the incompatible peer; no cross-flavor conversion is supported.

## Client update from 1.1.1-alpha

The 1.1.2 client works with the existing 1.1.1-alpha protocol-2 server; a real-process integration rehearsal exercises push, pull, recovery, restore and empty-head races against that exact server build. Use the client-only ZIP, close the client normally, replace its JAR and reopen. Preserve its existing data folder. No server setup, deployment, service/path change or new cache migration is needed.

## Scanning and integrity

Connection/profile selection queries the authoritative server head first. No head means no automatic local discovery, hashing or staging; the first scan begins on explicit Push. An existing empty snapshot still has a head. History and recovery remain available, and network/authentication failures are errors. Pending local journals block sync even without a head.

Ordinary status checks compare path, size, precise modification/creation times, file identity and Unix change time when supported. Recent timestamps and unknown identities are rehashed. On Windows, where Java returns no file key, creation time is the replacement hint. They do not read trusted unchanged content. All scans, including strict verification, build hashes/references without staging. Upload preparation stages only requested objects, with full verification. Progress shows discovery, metadata/cache comparison, changed-file hashing, manifest construction and server contact. Verified file hints are saved every 128 changed entries, with one SQLite writer and bounded transactions. Cancellation/failure saves the final completed batch; normal window-close cancels workers, drains verified results and flushes before exit. Force-kill/power loss can lose the current batch; already committed hints survive. Partial scans never publish a manifest, infer deletions or change sync baselines. A file updated by an external app such as TSM is retried once with fresh metadata, then fails safely if it keeps changing.

The default is one hashing worker. Set `java -Dwowuisync.scanWorkers=2 -jar YOUR_EXISTING_CLIENT.jar` (or `4`) to evaluate parallel hashing; `1` is the HDD/network-drive fallback. Values are clamped to 1–4. Outstanding tasks and a separate 64 MiB content-buffer budget are bounded. This budget excludes metadata, manifests, JVM and cache memory. Measurements do not establish a universal parallel speedup.

Cancel stops scanning; cancelling a blocked status request closes that connection and requires reconnection.

A metadata cache is a status hint. Windows does not expose Unix change time through this provider; metadata-preserving edits can evade an advisory check. Push commits and destructive apply/recovery retain full verification, and every uploaded chunk/file is verified by hash and length. Consequently these operations can still read unchanged content for integrity. First scans also populate the CDC cache and can be slower than v1. No new persistent full-folder Interface/WTF backups are created; immutable server recovery snapshots and temporary staging/journals protect destructive operations.

## Fresh setup

Extract the package, install Java 21+, and run `server/setup.sh` (or `setup.bat`) only for a new installation. Keep persistent data outside release directories. Use the desktop client to connect, verify the TLS fingerprint and authenticate. Linux systemd deployment and detailed account/snapshot workflows are described in `server/README.txt` and the source `docs/` directory. See [docs/README-1.0.md](docs/README-1.0.md) only for historical v1 setup details.

## Build and verify

With a full JDK 21+ and Maven 3.9+:

```bash
./compile.sh
./package.sh
python3 tools/test_jar_updater.py
```

Both scripts work from arbitrary current directories; packaging builds and tests first. JARs go to `dist/`; the verified deployment ZIP and SHA-256 sidecar go to `dist/releases/`. CI includes Linux, Windows and macOS builds; local results do not imply that remote CI has run.

A reproducible Linux scanner workload is available:

```bash
java -cp dist/wow-ui-sync-client.jar tools/ScanBenchmark.java /tmp/wus-benchmark-new 10000
```

Use a new fixture path per baseline and run versions sequentially on the same machine. `/proc/self/io` counters include database/staging I/O and are not physical-device reads. See the engineering report for measured results and untested environments.

## Developer references

Read `AGENTS.md`, `docs/RELEASE-STATUS.md`, `docs/FORMAT-2.md`, this README and the engineering report before extending the current code. `docs/DESIGN-1.1.md` records the prior design; `DESIGN.md` and `DEVLOG.md` preserve historical v1 decisions. Later implementation reports and the current code take precedence over obsolete upgrade requirements.
