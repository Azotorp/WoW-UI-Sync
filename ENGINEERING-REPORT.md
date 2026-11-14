# WoW UI Sync 1.1.1-alpha engineering report

## Source audit and provenance

Dedicated branch: `perf/persistent-scan-jar-upgrades`, based on `main`. Nothing was merged or deployed to production.

| Reference | Exact identity |
| --- | --- |
| GitHub main | `e7de36d252888beb16ded2d8d7c1247406c0d1b5` (PR #1 merge) |
| Other remote branch | `ccr-bc9e9633-4tnnd4`, `2c9b5163031130fd3ba146b4c662df05dfc9b926` |
| Historical v1 benchmark | `2c9b5163031130fd3ba146b4c662df05dfc9b926`; code reports 1.0.0-SNAPSHOT |
| Recovered implementation commit | `4b41eb7069b50a04aaa33cbcf5b530a753453f30` |
| Recovered source/build ZIP SHA-256 | `e31fab5002c6ac9a28f2f442061aa8d68a8d459215e8400ae76089e4724eabde` |
| Later scanfix1 ZIP SHA-256 | `0c0dfd870a2bc8243035e306ddc95ff73e5795a052a2f3ff0b5839138a1b0484` |

The two remote branches have identical application code; the later branch adds README/.gitignore changes. No tags or GitHub releases existed during audit. History includes the original scanner/cache (`a3ce2c9`), transfer implementation (`e84290d`) and release workflow (`cec2117`). There is no final 1.0.0 tag, so the historical source above is the available comparison.

The 1.1 implementation was absent from every remote branch. Recovered `WoW-UI-Sync-1.1.0-alpha-Recovered-Source-and-Build.zip` and later `WoW-UI-Sync-Client-1.1.0-alpha-scanfix1.zip` from the prior deliverables. Their only source differences are Scanner, ProgressPanel and SyncController. The later complete source is the baseline, preserved in its own recovery commit. Its original upstream commit identity is unavailable. The recovered AGENTS.md, release status, repository-format and 1.1 design/upgrade documents were read before implementation.

## Confirmed root causes

1. The 1.1 scanner bypassed the v1 persistent hash cache. Each status check read all content, performed CDC and verified/staged every chunk. Existing staging checks read chunks again. Discovery-only scanfix1 did not restore caching.
2. `SyncService.status` called `logicalBytes` twice, walking retained snapshot manifests each time. Account listing/login did this per account too. Current status manifests were reparsed for every request.
3. Discovery had no measurable total while the UI displayed a generic checking message. Scanfix1 improved discovery reporting but did not explain cache/hashing work or cancel an outstanding server call.
4. `RepositoryMaintenance.requireSupported` demanded an offline migrate command, while release activation tooling changed release pointers. This prevented normal JAR-only startup upgrades.

## Components changed

| Component | Change |
| --- | --- |
| Scanner / ScanCache / ClientDb | Persistent precise metadata and chunk manifests, compact checksummed rows, recent-write distrust, Unix ctime, Windows creation-time fallback, four-root decoded cache, external DB invalidation, delta transactions |
| SyncOps | Verify/regenerate requested missing staging only; retain strict push review/commit, manual snapshot and destructive/recovery scans, source-change checks and server content verification |
| SyncController / TransferControl / ServerConnection | Honest indeterminate discovery, measured counts, cache/hash/byte/elapsed and phase timings, throttled UI updates, cancellation including closing an outstanding status connection; connect/auth/status timing logs |
| Storage / SyncService / AccountService / ServerApp | Warm display summaries before listening, cache current manifest by authoritative head, refresh summaries on commit/deletion; no history/object scan during routine status/account display |
| RepositoryMaintenance / ServerDb / ClientDb | Automatic startup migration under existing exclusive lock, schema recognition, WAL-consistent verified checkpoints, transactional SQL rollback, newer-format refusal, failed-open connection cleanup |
| Readiness / ServerMain / update.sh | Pinned TLS/protocol/version readiness; optional same-service atomic JAR replacement, preserved owner/mode, previous binary and no unsafe automatic rollback |
| Build / tests / tools / docs | 1.1.1-alpha artifacts, root package documentation, CI updater check, regression tests, reproducible benchmarks and real-process upgrade rehearsal |

## Benchmarks

Linux, Java 21.0.13, local scratch filesystem. Fixture: 10,000 seeded 8 KiB addon files plus one seeded 12 MiB WTF file, **94,502,912 source bytes**. Versions ran sequentially with separate fixture roots and identical bytes. No network was used by scanner measurements. Wall time excludes fixture generation/source-launch compilation and includes scanner work. One run per version is evidence for this fixture, not a statistical Windows/slow-disk claim.

| Scenario | v1 historical ms | 1.1 scanfix1 ms | 1.1.1 ms |
| --- | ---: | ---: | ---: |
| first | 1170.380 | 1600.414 | 2233.844 |
| unchanged | 201.891 | 1186.583 | 196.045 |
| restart | 155.016 | 1109.294 | 409.207 |
| one-changed | 172.977 | 1140.137 | 245.663 |
| one-deleted | 173.174 | 1175.038 | 202.375 |
| new-addon | 170.158 | 1189.168 | 216.202 |
| no-change | 193.110 | 1217.215 | 214.728 |

The first unchanged scan is **6.1× faster than scanfix1** (1186.583 → 196.045 ms), comparable to v1's 201.891 ms. Cache reload after DB reopen is slower than v1 (409.207 vs 155.016 ms), and first cache fill is slower (2233.844 vs 1170.380 ms). The later no-change row is 214.728 vs v1's 193.110 ms. The blanket goal “faster than v1 in every scenario” is **not met**; the stronger metadata checks and persistent chunk manifests carry overhead.

The first unchanged scan reports **10,001 hits, zero files/bytes hashed**. Read-character counters fall from **189,006,052 to 228** bytes; fixed write-character counters are 338 bytes of diagnostic output, while v1 rewrites its hash cache and wrote 6,794,064 bytes. `/proc/self/io` counters include database/staging/runtime I/O, are not physical-device reads or syscall counts, and exclude metadata traversal. Reopened scans read cache metadata, not unchanged WoW content.

One changed file hashes seven bytes. Immediate subsequent checks may rehash it and the three-byte new file because timestamps younger than two seconds remain untrusted. After they age, a **separate fresh JVM** reports 10,001 hits and zero files/bytes hashed (386.450 ms recorded scanner elapsed). Fingerprints match all three versions in every workload scenario. The benchmark's `restart` row closes/reopens the DB within one JVM; the separate probe verifies actual process restart. Raw evidence is in `docs/benchmarks/`.

A GUI status operation sends one StatusReq after scanning; count and latency are logged separately. Scanner workloads have no network requests. Filesystem syscall counts, native Windows antivirus effects, slow-disk performance and WAN timings were not measured.

Reproduce with a new fixture path for each baseline, running versions sequentially:

```bash
java -cp dist/wow-ui-sync-client.jar tools/ScanBenchmark.java /tmp/new-fixture 10000
java -cp dist/wow-ui-sync-client.jar tools/ScanProbe.java /tmp/new-fixture
```

## Verification

- Maven: **44 tests, 43 passed, one optional external private-key fixture skipped**, zero failures/errors. Both shaded JARs compile. Three isolated Python updater tests pass.
- Existing integration/security tests exercise TLS login, incremental push/pull, snapshot/history restore, recovery snapshots, cross-flavor/object authorization, upload forgery/quota/frame limits and journal replay/local-change refusal.
- New tests cover cache restart/change/deletion/rename/new files, restored mtime plus ctime, recent files, corrupt cache, missing/corrupt staging, cancellation, unsafe names/links/case collisions, migration/pin preservation, Windows null-file-key fallback, Swing progress, automatic server migration, newer/unrecognized schemas, failed SQL migration rollback, committed WAL backup and history-free routine status.
- Real old/new JVM rehearsal at the same installed `existing-custom-name.jar` path and identical startup arguments passed: old root login, automatic migration, unchanged account ID/credentials/config/TLS bytes, verified stored legacy content and migration snapshot, restart without repeat migration. This models JAR/process compatibility, not a live installed systemd service.
- Optional updater contract tests use isolated fake systemctl/java commands. Actual pinned TLS readiness is tested by server integration and the real-process rehearsal. Invalid artifacts leave the service running; failed readiness leaves it stopped without automatic database/binary rollback.
- `./compile.sh` and `./package.sh` run the full Maven reactor. This environment has a Java 21 compiler module but no javac launcher/ct.sym. An external temporary launcher delegates to that module, and Maven uses source/target 21 instead of --release. Repository full-JDK defaults remain --release 21. This workaround does not add runtime dependencies.
- Packaging validates 29 flat members, both real shaded JAR entrypoints/version manifests, checksums and executable shell modes. Generated JARs, private keys and account databases are excluded from source commits.

## Migration and deployment

Protocol and repository remain **2**, server SQLite remains **2**, client cache schema becomes **2**. Existing 1.1 servers normally need no schema conversion. V1 hashes cannot supply CDC manifests, so the first new-client cache fill is necessary.

Startup migrates recognized legacy data while holding the existing data-directory lock and before opening the listener. Pre-migration checkpoints contain SQLite committed WAL via VACUUM INTO, configuration, keys/TLS, and manifest/snapshot/head metadata with SHA-256 checksums. SQL changes are transactional. Filesystem conversion reuses legacy objects without deleting them, creates migration-baseline snapshots, and resumes safely; migration failure prevents serving and retains checkpoints/diagnostics. Unsupported schemas/repository formats are refused before conversion.

**Deploy:** stop the actual existing unit → retain/replace its actual installed JAR with preserved ownership/mode → start the same unit. Keep its data path, configuration and startup arguments. Do not run setup again. The current README includes commands and the optional updater. `ready --data <existing path>` checks local pinned TLS, protocol and running application version; process startup alone is insufficient. Initial migration and metadata warmup can delay readiness.

Upgrade protocol-1 servers before using the protocol-2 client. Client updates replace only their JAR beside the existing data folder, preserving settings/pins/device/keys/baselines. Cancelling a blocked status call closes the connection and requires reconnecting.

On failed migration, stop the service, preserve data/checkpoints/logs, inspect checksums and restore matching metadata offline with its matching binary. Never automatically downgrade a database. The historical release `rollback` command requires an activation record from the optional ZIP updater; manual JAR upgrades require explicit offline checkpoint restore. No production migration was attempted.

## Remaining limitations

The performance regression is measurably reduced, unchanged content is not rehashed by routine checks, and snapshot/integrity/fixture upgrade behavior is verified. **First fill/reopen and some later metadata-heavy scans remain slower than v1.** No Windows speed claim is made.

Native Windows/macOS filesystem and GUI behavior, Windows junction/antivirus impact, slow/network drives, actual installed systemd operation, real WAN interruptions and every power-loss point remain untested. OpenJDK's Windows provider returns null for fileKey (checked against [OpenJDK 21 source](https://github.com/openjdk/jdk/blob/jdk-21-ga/src/java.base/windows/classes/sun/nio/fs/WindowsFileAttributes.java)); tests simulate its creation-time fallback rather than the OS. Metadata-preserving Windows edits can evade an advisory check. Strict push/destructive verification still reads/hashes content and detects differences, so those operations can remain slower than routine status.

Server display warmup enumerates retained metadata once before listening. At-rest packs, background scrubbing, pooling and automatic network updates remain deferred. Production service paths/names were unavailable; examples deliberately use operator-selected existing values. The optional JAR updater requires Python 3 and the real existing unit/data paths; wrapper units need the actual JAR path. Remote CI results are separate from local verification.

## Completion validation — November 13, 2026

Recovered PR #2 source tree matched local tree `ea53b1451402791eff83aaa89b7e1155927d8c7a`. Its first GitHub matrix run passed Linux but exposed two portability defects: Windows retained the server log FileHandler after shutdown, preventing temporary-directory cleanup; macOS rejected normal paths beneath its `/var` ancestor alias.

Server shutdown now closes the owned rotating FileHandler, including startup-failure exits. Path validation resolves ancestor aliases component by component while continuing to reject links/junctions at or beneath the selected installation root. Added an ancestor-alias regression and a server-stop log-release assertion.

Completion build: `compile.sh` and `package.sh` succeeded locally using the documented compiler workaround. Final Maven suite: 45 tests, 44 passed, one optional external-key fixture skipped, zero failures/errors. Three isolated updater tests passed. The final deployment ZIP is rebuilt from these corrected sources and validates its JAR versions, checksums, membership and executable permissions. Native GitHub Windows/macOS reruns are tracked separately; these results do not certify a production deployment.
