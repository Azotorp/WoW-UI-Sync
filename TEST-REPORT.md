# 1.2.0-alpha test report

Date: 2026-11-14 (America/Vancouver). Baseline: main `4076848`, restored 1.0.0 implementation. New branch: `feature/1.2.0-alpha-sync-redesign`. No production service, database, keys, main branch or published release was modified.

## Results

- JDK 21.0.13 / Maven 3.9.9, Linux: **29 Java tests passed, zero failures/errors/skips** using freshly generated synthetic key fixtures. Normal builds skip the optional supplied-key test unless its property is supplied; no private keys are packaged.
- **33 Python service-tool tests passed**: names/quoting, atomic writes/backups, occupied port, overlapping stores, existing-service refusal without writes, invalid edited-unit preservation, non-root guidance, cross-process store lock, failed-update rollback, uninstall preserving data/other units.
- Baseline 1.0.0 builds/tests pass. New shaded client/server JARs have executable manifests and version `1.2.0-alpha`; both version entry points launch with Java 21.
- Live loopback TLS integration: authenticate, push/commit, Pull on a second client, explicit local-only removal, data-only Push preserving unrelated server content, non-folder-named TOC SavedVariables transfer, incremental update, data-only Pull, canceled Pull before apply, marker-only fresh-install Pull creating Interface/WTF, empty Push refusal, protocol-1 rejection, stale full-Push rejection, roles/quotas/account isolation from existing server tests.
- Scanner tests include orderly cancellation + DB reopen, changed metadata/content, malformed cache row recovery, denied file access (Linux), symlink refusal, and **a child JVM halted during scan** with subsequent checkpoint recovery and successful completion.
- Backup tests cover all four checkbox combinations, absent folders/restoring absent state, cancellation without live changes, insufficient space using a sparse synthetic file, corrupt-snapshot refusal, legacy coverage and generation replacement.
- Headless Swing table test confirms numeric sorting, zero numeric size and disabled Addon/enabled Include Data for data-only rows.
- Store compatibility smoke: create a synthetic key-authenticated store with the baseline JAR, open it with 1.2, start/stop the 1.2 server on an independent loopback port, then read it again with 1.0. Current-manifest fingerprint, synthetic public-key hash and TLS fingerprint were identical before/after. Server schema remains version 1.

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
| Dynamic services and rollback | 33 service-helper tests; Java store locking tested; actual privileged systemd lifecycle **not run here** |
| Protocol rejection, conflict, identity/storage compatibility | Loopback TLS and synthetic 1.0-store smoke pass |
| Console boundary | Optional placeholder omitted; no server-console controls shipped |
| Packaged JARs and reproducible packaging | Linux entry points/manifests checked; explicit source/deployable allowlist, fixed ZIP metadata, checksums |
| Windows/macOS | Cross-platform JARs built; native Windows/macOS GUI launch and Windows scan performance **not locally verified**. Branch CI is configured for Windows compile/tests/JAR and GUI-process launch smoke, but was not triggered because source publication awaits approval. macOS hardware unavailable. |

Full TCP fault injection mid-transfer, power loss during every backup/apply boundary, actual root/sudo/nano/systemd interaction, OS-specific browse latency, and hostile same-size/same-metadata edits on Windows remain **unverified**. Existing resumable network behavior is retained; cancellation, commit fingerprint checks, phase-aware acknowledgement errors and recovery journals are implemented, but no stronger resume guarantee is claimed from the tests above.

A whole Pull/Restore is not globally atomic; interrupted apply can leave mixed live content until a backup is restored. User-selected backup opt-out has no automatic content recovery. Completed hash checkpoints survive a crash; the last uncommitted batch may rehash. The cache is an accelerator based on observed metadata, not adversarial content verification. Entire corrupt `client.db` fails closed to protect account/settings state.

The existing `.bak` default exclusion remains in place; `.lua.bak` ownership is tested with exclusions deliberately disabled. Historical server snapshot browsing/storage redesign and integrated Server Console remain deferred; the server stores only its current manifest. The separate first-patch browse/discovery/layered-exclusions refactor remains planned. There are no invented Windows/systemd success claims in this report.

## Reference privacy

Original supplied reference observations matched 19,208 entries / 187 top-level TOCs / 246 WTF Lua files. The smaller re-upload contained 13,481 entries / 178 top-level TOCs / 246 WTF Lua files. Both were intentionally partial. Only public-style TOC title/group/dependency/SavedVariables declarations for the named add-ons became sanitized test metadata. No WTF values, account/realm/character paths, private keys, archive contents or credentials are in source or deliverables. Both local archive copies were removed once no longer needed.
