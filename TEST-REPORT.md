# 1.2.0-alpha test report

Date: 2026-11-14 (America/Vancouver). Baseline: main `4076848`, restored 1.0.0 implementation. New branch: `feature/1.2.0-alpha-sync-redesign`. No production service, database, keys, main branch or published release was modified.

## Server-only add-on purge follow-up

Push now exposes destination-only add-on folders for explicit removal, with individual checkboxes, Keep all/Purge all selectors and exact deletion counts before the existing server review/commit. Default Keep and Pull behavior are retained. Candidates compare folder names case-insensitively and exclude SavedVariables. Purge applies only to the selected server account/profile; it does not remove unrelated WTF files or local backups. Source-present folders cannot be selected as orphan purge candidates. No common/server production code, protocol or schema change. Windows and Ubuntu CI run 44030138368 passed at source 31e7c7226b7add27486bcf53d6251b9032bc7abd, including Maven tests/packages, version entry points, Windows GUI-process launch and 35 Linux service-helper tests.

Regression coverage: case-insensitive candidate discovery; data-only entries never become folder purge candidates; real loopback TLS deletion-only Push after removing an add-on locally; default selection keeps it; exact review lists only its two server files; cancel leaves the server fingerprint unchanged; confirmed Push removes that folder while keeping its SavedVariables and unrelated add-on files; status becomes Up to date. The transfer needs no new blob uploads. Local Linux Maven package: 39 Java tests, 37 passed, two documented skips (optional key fixtures and native Windows test), zero failures/errors.

## Status/selection consistency follow-up

Client-only fix: settings-only differences no longer recommend a default Push that excludes those settings. The badge identifies general WTF differences, keeps Show details, and leaves actual baseline fingerprints intact. Exact manifest matches retain the green Up to date badge. Add-on and SavedVariables differences are not suppressed. The no-op Push message refers only to selected content. Comparison reuses the validated current base manifest where possible and fetches the remote manifest only when needed; no extra content hashing or server changes.

Regression coverage reproduces changed macros-cache.txt after a successful Push: status identifies settings-only differences, default selection produces the unchanged remote manifest, explicitly selecting general settings produces a change, the base stays unchanged, and restoring the file returns Up to date. Separate checks cover account/character SavedVariables and add-on differences in both directions. Local Linux validation: 38 Java tests, 36 passed and two documented skips (optional supplied-key fixture and Windows-native execution), zero failures/errors. Windows and Ubuntu CI run 43681147631 passed at source 265d87157534aedbb13a40fd3276b52e03740850, including the status regression, Maven packages, version entry points, Windows GUI-process launch and 35 Linux service-helper tests.

## Results

- JDK 21.0.13 / Maven 3.9.9, Linux: **37 Java tests: 36 passed, zero failures/errors, one Windows-only native-copy test skipped locally** using freshly generated synthetic key fixtures. Normal builds skip the optional supplied-key test unless its property is supplied; no private keys are packaged.
- **35 Python service-tool tests passed**: names/quoting, atomic writes/backups, occupied port, overlapping stores, existing-service refusal without writes, invalid edited-unit preservation, non-root guidance, cross-process store lock, failed-update rollback, uninstall preserving data/other units.
- Baseline 1.0.0 builds/tests pass. New shaded client/server JARs have executable manifests and version `1.2.0-alpha`; both version entry points launch with Java 21.
- Live loopback TLS integration: authenticate, push/commit, Pull on a second client, explicit local-only removal, data-only Push preserving unrelated server content, non-folder-named TOC SavedVariables transfer, incremental update, data-only Pull, canceled Pull before apply, marker-only fresh-install Pull creating Interface/WTF, empty Push refusal, protocol-1 rejection, stale full-Push rejection, roles/quotas/account isolation from existing server tests.
- Scanner tests include orderly cancellation + DB reopen, changed metadata/content, malformed cache row recovery, denied file access (Linux), symlink refusal, and **a child JVM halted during scan** with subsequent checkpoint recovery and successful completion.
- Backup tests cover all four checkbox combinations, absent folders/restoring absent state, cancellation without live changes, insufficient space using a sparse synthetic file, corrupt-snapshot refusal, legacy coverage and generation replacement.
- Headless Swing table test confirms numeric sorting, zero numeric size and disabled Addon/enabled Include Data for data-only rows.
- Store compatibility smoke: create a synthetic key-authenticated store with the baseline JAR, open it with 1.2, start/stop the 1.2 server on an independent loopback port, then read it again with 1.0. Current-manifest fingerprint, synthetic public-key hash and TLS fingerprint were identical before/after. Server schema remains version 1.

## Native folder backup and progress follow-up

Windows now uses one Robocopy process per selected top-level backup category, with bounded workers, /E, COPY:DAT/DCOPY:DAT, /XJ and zero retries. No move, mirror or deletion options are used. Return codes 0–7 proceed to independent verification; >=8 fails. Native cancellation forcibly stops and joins the child before partial-stage cleanup. Other platforms use parallel Java copy workers. Hash verification remains mandatory: cached source hashes are reused only when size, exact mtime, identity/change metadata and verification age remain valid. Destinations are hashed in parallel; uncached/excluded files also get a source hash. Source changes, missing/extra paths and hash mismatches fail before snapshot publication. Workers are joined before cleanup.

Backup, native copy, verification, download and apply now have clean phase boundaries, counts/data/rate rows and a separate normalized path row. Native-copy counts are indeterminate until the OS command completes rather than guessed. UI updates are throttled to about 10 Hz. Scan summary/metrics are cleared at the phase transition. Manual backup/restore dialogs also separate the path.

Linux results: 37 Java tests, 36 passing and one Windows-only native-copy test skipped; 35 Python helper tests unchanged. New tests cover safe native arguments/return-code mapping, cached+excluded files and restore, cancellation without publication, source modification detection, and clean backup progress after scanning. Windows CI run 43677758352 passed all five backup-copy tests, including real Robocopy/Unicode-path copy, cached verification, restore, cancellation and source-change refusal. That run exposed a separate Pull path-canonicalization issue; the follow-up canonicalizes the live root consistently with the scanner so empty removed add-on directories can be pruned under Windows temporary-path aliases. Full rerun 43677960647 at source 8bb34fb6ea0882d36b61a3fa6f17dd8006fb9d16 passed Maven builds/tests on Windows and Ubuntu, both JAR version entry points, the Windows GUI-process launch smoke and all 35 Linux service-helper tests. Optional supplied-key and OS-specific tests retain their documented platform skips. Test-only cleanup closes server log handlers and client DBs on Windows; production server code/protocol remain unchanged.

Synthetic Linux corpus (10,010 files / 51,445,760 bytes, scan cache populated outside backup timer): old backup 2.536235 s; new portable parallel backup 0.772378 s. OS cache is warm; shared-host load varies. See benchmarks/BackupBenchmark.java and benchmarks/backup-progress-results.csv. This does not predict Windows/large-install timing and is not a guarantee that a 3.7 GB backup completes in one second. Native Windows timing on the user's hardware still needs retest. Robocopy flags and exit handling follow [Microsoft's command reference](https://learn.microsoft.com/en-us/windows-server/administration/windows-commands/robocopy).

The new client-only backupfix ZIP requires replacing/rerunning only the client JAR in its existing directory, preserving adjacent wow-ui-sync-data and local backups. No server JAR, service restart, reinstall or data migration is required.

## Client scan/progress follow-up

Client-only patch: scan counters/read bytes/cache hits, throughput/elapsed/ETA and the active relative path now use separate rows, matching upload progress. A headless Swing layout regression renders the panel, verifies path/statistic separation and clearing at completion. A real follow-up-scan test confirms all unchanged entries hit cache with zero content bytes read. All 31 Java tests pass, including optional synthetic-key tests; server protocol/storage are unchanged.

Windows observation supplied during testing: 35,962 files, 3,722,718,415 bytes hashed, 0 hits, traversal 0.590s, cache writes 2.038s, elapsed 97.967s. This is a cold-cache reading/hashing workload, not evidence of slow traversal or cache persistence. The prior two-worker cap is replaced by the original 1.0 CPU-based bound (maximum eight). Buffers are reused per worker, avoiding a fresh 256 KiB allocation per file; Unix attribute support is detected once instead of throwing unsupported-view exceptions per file on Windows. Existing precise metadata checks and durable checkpoint policy remain.

`benchmarks/scan-progress-fix-results.csv` contains Linux before/after and worker-count trials on the same 10,010-file synthetic corpus. These are sequential, OS-cache-warm measurements with varying shared-host load, not a measurement of the user Windows installation and not a guaranteed speedup. Windows cold-scan performance still requires user retest; upload throughput is a different metric. Existing cache rows are preserved. This update requires replacing/rerunning only the client JAR in its current folder, retaining its existing adjacent wow-ui-sync-data. No server restart or update.

## WorkingDirectory directive fix

Removed shell-style outer quotes from WorkingDirectory while retaining quoted ExecStart arguments and escaped percent specifiers. Two further tests pass, including a real systemd-analyze verify invocation against temporary generated units with plain, spaced, percent and dollar-containing paths. All four native unit validations pass on this Linux environment; no units were installed or services started. This adds native unit-syntax evidence, not a claim of a full privileged installation test. Java binaries remain unchanged.

## Installer defaults and quotation fixes

Thirteen further tests pass: six system-unit quotation/collision cases and seven primary-IP/invoking-user default cases. Setup tolerates unmatched quotes in unrelated unit commands, still refuses ambiguous sync-unit commands and shared JAR paths, and handles continued/quoted commands. IP detection reads the OS route source (or UDP socket route selection without transmitting packets), rejects loopback/unspecified/link-local/multicast defaults and fails with a required explicit entry instead of selecting localhost. Java defaults to its resolved path; service defaults to `wow-ui-sync`; the invoking OS user remains the account default. No live system units were changed during tests.

## Optional UFW prompt follow-up

Nine additional mocked firewall tests pass: loopback bind skips UFW; missing/inactive UFW stays unchanged; decline performs no mutation; confirmed selection adds exactly the chosen TCP port and verifies the result; existing allow rules are not duplicated; conflicting deny rules are not overridden; command failure and absent post-command rules are not reported as success. Firewall failure runs outside service-install rollback, so it does not undo an installed healthy service. No live UFW, SSH rules or remote server was changed during these tests. The previously tested Java JARs are unchanged and were repackaged without recompilation.

## Repeating checks

```sh
mvn -B clean package
python3 -m unittest discover -s tests -v
java -jar dist/wow-ui-sync-client.jar --version
java -jar dist/wow-ui-sync-server.jar version
./package.sh --no-build
```

To include the optional OpenSSH private-key parser test, supply `-Dwowuisync.testKeys=/absolute/path/to/synthetic-key-fixtures` with ed/rsa/ec plain, encrypted and PEM fixtures described in `PrivateKeysTest`. These are throwaway test keys, never user keys. End-to-end tests create independent temporary accounts/stores and ports and cleanly stop their own test servers.

`benchmarks/StoreCompatibilitySmoke.java` can be compiled/run against a supplied baseline server JAR, then run against the alpha JAR with the same **synthetic** store. It refuses to create a fixture over an existing directory. Do not run it against production.

## Performance evidence

Synthetic corpus: **10,010 files, 51,445,760 bytes**, 50 add-on directories of small Lua files plus a media directory with ten 1 MiB files. Same host/storage, Java 21, separate fresh client DBs; three sequential scans per build. “Cold” means empty application hash cache, **not** cold OS disk cache. Corpus creation and run order warm the filesystem; these are local engineering measurements, not Windows/antivirus benchmarks. Exact per-run values are in `benchmarks/results.csv`.

Initial measured baseline vs alpha:

| Scan | 1.0.0 | 1.2 alpha initial |
|---|---:|---:|
| Empty application cache | 1.268 s | 1.294 s |
| Warm 1 | 0.385 s | 0.294 s |
| Warm 2 | 0.340 s | 0.210 s |

Alpha stage profiling on that run: first traversal 0.231 s, durable cache writes 0.236 s, end-to-end scanner 1.231 s (wrapper adds setup/return overhead). Warm scans read **zero content bytes**, hit all 10,010 cache entries, and traversed in 0.075/0.081 s. The small synthetic corpus did not justify wider-than-two default hash workers. There is no claim of a large cold-scan speedup. A later isolated follow-up measured baseline 1.621/0.412/0.317 s and final alpha 1.186/0.282/0.218 s (cold/warm/warm). A separate alpha-only repeat under variable shared-host load took 17.576/3.365/2.019 s; without a simultaneous baseline it is not a useful relative speed claim. All runs are retained in the CSV. Results vary with host load; neither set establishes Windows performance.

Traversal/stat, cache persistence and end-to-end elapsed are measured separately. Hashing/queueing/remaining validation use the remaining elapsed time; detailed per-OS antivirus, UI-thread and network profiling was **not** performed. UI count callbacks are throttled to ~10 Hz. Empty-server automatic status now defers hashing altogether; first real Push still hashes/verifies files.

## Acceptance checklist and limits

| Work-order area | Evidence/status |
|---|---|
| Folder rows, separate families/dependencies, TOC precedence | Automated synthetic/reference-metadata tests pass |
| Scope-aware declarations, shared ownership, orphan order/location grouping | Automated tests pass; shared ownership is an explicit data-only row |
| Safe file/data/general selection, local-only Keep/Remove | Planner tests and live integration pass |
| Category backups, absent/failed/canceled/restore | Automated tests pass |
| Fresh installation and empty Push | Automated/live integration pass |
| Persistent incremental scan cache and process termination | Automated child-process recovery passes; Linux benchmark measured |
| Numeric sorting and zero values | Headless model test passes; actual native GUI layout still needs user review |
| Dynamic services and rollback | 35 service-helper tests; Java store locking tested; actual privileged systemd lifecycle **not run here** |
| Protocol rejection, conflict, identity/storage compatibility | Loopback TLS and synthetic 1.0-store smoke pass |
| Console boundary | Optional placeholder omitted; no server-console controls shipped |
| Packaged JARs and reproducible packaging | Linux entry points/manifests checked; explicit source/deployable allowlist, fixed ZIP metadata, checksums |
| Windows/macOS | Cross-platform JARs built; Windows build/tests, native Robocopy copy and GUI-process launch smoke pass in branch CI run 43677960647. Actual Windows layout and large-install performance still need user review/retest; macOS hardware unavailable. |

Full TCP fault injection mid-transfer, power loss during every backup/apply boundary, actual root/sudo/nano/systemd interaction, OS-specific browse latency, and hostile same-size/same-metadata edits on Windows remain **unverified**. Existing resumable network behavior is retained; cancellation, commit fingerprint checks, phase-aware acknowledgement errors and recovery journals are implemented, but no stronger resume guarantee is claimed from the tests above.

A whole Pull/Restore is not globally atomic; interrupted apply can leave mixed live content until a backup is restored. User-selected backup opt-out has no automatic content recovery. Completed hash checkpoints survive a crash; the last uncommitted batch may rehash. The cache is an accelerator based on observed metadata, not adversarial content verification. Entire corrupt `client.db` fails closed to protect account/settings state.

The existing `.bak` default exclusion remains in place; `.lua.bak` ownership is tested with exclusions deliberately disabled. Historical server snapshot browsing/storage redesign and integrated Server Console remain deferred; the server stores only its current manifest. The separate first-patch browse/discovery/layered-exclusions refactor remains planned. There are no invented Windows/systemd success claims in this report.

## Reference privacy

Original supplied reference observations matched 19,208 entries / 187 top-level TOCs / 246 WTF Lua files. The smaller re-upload contained 13,481 entries / 178 top-level TOCs / 246 WTF Lua files. Both were intentionally partial. Only public-style TOC title/group/dependency/SavedVariables declarations for the named add-ons became sanitized test metadata. No WTF values, account/realm/character paths, private keys, archive contents or credentials are in source or deliverables. Both local archive copies were removed once no longer needed.
