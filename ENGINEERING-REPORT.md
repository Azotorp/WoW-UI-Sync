# 1.1.2-alpha client scanning report

Baseline: latest `main`, `19bd4185a49dec9af3afb77beb32a880d25d69a5` (PR #2). Development branch: `perf/client-scan-1.1.2`. No merge or deployment. The previous report is retained in `docs/ENGINEERING-1.1.1.md`. Read AGENTS, current reports, release status, format/design and README before changes.

## Findings and implementation

The supplied Windows figures (1,450/35,962 files, 16.1 s, 21.8 MiB, no hits) establish slow first-fill work, not its cause. Source inspection confirmed that `Scanner.hashFile` wrote/verified staging during ordinary checks and saved cache rows only after the whole scan. A TSM external-app write could therefore abort a long scan and lose all completed cache work. No native Windows timing attribution is claimed.

- Probe the authoritative selected account/flavor head before automatic discovery. Only a null fingerprint means absent; a zero-file snapshot has a non-null fingerprint. Error/auth failures propagate. History/recovery remain independent. Pending journals are checked before the probe. Explicit operations re-probe; Push rechecks after scanning and retains expected-head checks at begin and commit.
- All scans now produce hashes and ordered references without staging. Small files reuse the whole-file digest as their sole chunk digest. Staging is generated/verified only for requested upload objects; existing immutable stage reuse and server full-file/hash/length verification remain.
- Verified hints are upserted every 128 changed rows, by one coordinated writer with bounded transactions. Cancellation/failure flushes remaining stable results, including completed workers. Normal window-close drains active scans and flushes SQLite before exiting. Force-kill/power loss can lose the small current batch. Partial hints cannot publish a manifest, infer filesystem deletions, update baselines or authorize apply. Successful scans prune obsolete advisory rows only after validation.
- Preserve the existing Windows null-fileKey creation-time hint and cache schema. Avoid probing unsupported Unix ctime once per file on Windows. Keep unreadable/unsafe/link/junction/collision failures closed, including top-level addon discovery. Ancestor aliases such as macOS `/var` remain supported. Directory validation is not cached across scans or trusted for later content opens.
- Hash reads obtain fresh metadata immediately before content and verify it afterward; the returned stamp is the verified cache stamp. This eliminates duplicate outer before/after reads without discarding an integrity check. Discovery attributes are still used for advisory comparisons. A source changed after discovery is no longer automatically an error. Mid-read changes get one bounded retry; repeated changes block completion and mention external updaters such as TSM.
- Optional platform-thread hashing has a worker-sized outstanding window, bounded executor queue, independent 64 MiB content-buffer semaphore, private digests and deterministic coordinator assembly. Large files stream through CDC and reserve 17 MiB, independent of source length. SQLite/progress remain coordinated. Cancellation interrupts/drains all tasks and rechecks before returning success. Operation checkpoints support pause/resume. Sequential mode hashes inline without thread hops.
- Discovery and scan reports are throttled to about 10 Hz; Swing changes stay on the EDT. Modes and source-byte counters distinguish first fill, cached check and strict verification. Bytes include retries. Logs separate cache load, metadata, hash work, manifest and persistence, plus six miss reasons. Staging/transfer/server verification have explicit phases. Worker hash times are summed work, not disjoint wall-clock phases.

## Integrity audit

Strict review plus strict precommit Push checks remain: review authorizes the content presented; a later content scan detects changes during staging/transfer, including deduplicated files not requested by the server. The server still verifies every referenced object and whole file and rejects changed heads. Pull/restore retain strict review, fresh pre-recovery scan, committed server recovery, immutable staging/reconstruction, strict pre-apply recheck, durable replay journal and strict post-apply verification. These scans guard different mutation windows and were not replaced with metadata hints. Removing staging from each repeated scan removes redundant disk work while retaining those content checks. Recovery/journal semantics and no persistent local folder-backup policy are unchanged.

## Measured evidence

Linux, Java 21.0.13, overlay filesystem, `-Xmx512m`. Identical seeded fixture, independent caches: 35,996 mixed 128–24,703-byte addon files plus 4/8/12/32 MiB WTF files; **36,000 files, 505,037,951 source bytes**. Fixture copies and normal prior activity warm OS caches; no physical cold-cache or native desktop claim. Single runs show variability, not a statistically established ranking. Raw results and phase counters are in `docs/benchmarks/1.1.2/`; timings below are scanner-call wall milliseconds (before staging inventory). Source-launch compilation is outside the scanner-call timer.

| Scenario | main | optimized 1 worker | 2 workers | 4 workers |
| --- | ---: | ---: | ---: | ---: |
| First fill | 8013 | 4760 | 4779 | 5069 |
| Unchanged before any push | 415 | 388 | 479 | 545 |
| One changed file | 460 | 551 | 456 | 432 |
| Addition | 441 | 422 | 561 | 556 |
| Deletion | 392 | 432 | 337 | 536 |
| Rename | 423 | 395 | 358 | 388 |
| Separate fresh JVM | 1226 | — | 1375 | — |

The default is **one worker**: after eliminating sequential thread hops, the final fixture shows no consistent parallel advantage. Earlier exploratory runs favored parallelism; that was not stable across runs and environments. Two/four remain explicit opt-ins; HDD/network/native Windows performance must be measured locally. The optimized first fill is about 41% faster in the recorded final sequential run. Some metadata/restart scenarios regress; no blanket speedup is promised.

First fill reads exactly 505,037,951 source bytes in both versions. Main creates **36,040 staging files / 505,037,951 staged bytes**; all optimized scan scenarios create **zero**. Before-push repeat and fresh-process checks have 36,000 hits, zero files hashed and zero source bytes read. Fingerprints and an additional digest of **every manifest entry including ordered chunk references** match all four variants in every change scenario. Regression tests separately compare large-file chunk references across worker counts.

Main first-fill phase counters: discovery 287 ms, cache load 4 ms, metadata 100 ms, hash/path/staging work 4737 ms, manifest 93 ms, persistence 499 ms, total scan 7980 ms. Optimized sequential: 338 / 4 / 111 / 3450 / 73 / 529 ms, total 4744 ms. Remaining unassigned wall time includes coordinator/path/bookkeeping, allocation and scheduling; hash timers include filesystem/path checks. These are not a claim to have isolated every syscall cost.

A separate diagnostic JFR run confirms 505,037,951 staging bytes written on main and zero on the candidate. Java staging-write event durations total about 1998 ms; source-read durations about 629 ms (main) / 280 ms (candidate). Those instrumented runs are excluded from timing comparisons. CPU samples include path validation, chunking, hashing, cache and runtime work. JFR Java file events do **not** capture SQLite native I/O or all filesystem metadata syscalls; CPU samples are not wall-time attribution. Headless scanner benchmarks exclude Swing overhead and network latency. `/proc` rchar/wchar include runtime/database I/O and are not physical-device counters.

Sampled process RSS (50 ms sampling, includes JVM/source-launch/compiler/cache/GC) is about 379 MiB main and 428/424/444 MiB for 1/2/4 workers. This is not a live-heap measure. The separate 64 MiB limit covers content buffers only. Path checks, SQLite decoding after restart, JVM allocation and metadata enumeration remain bottlenecks; per-read full path validation deliberately remains because caching it across mutation windows could weaken link protection.

## Validation and delivery

Local suite: 53 tests, 52 passed, one optional external-key fixture skipped, zero failures/errors in the completed runs. Added partial-cache cancellation/restart safety, separate-process normal-close flushing, fresh discovery metadata/TSM-style write, mid-read mutation, deterministic parallel references, cancellation/worker cleanup, miss reasons, absent/zero-file heads, retained recovery without a head, error propagation, pending journals, stale empty-head begin races and mutation before commit. Existing migration/security/quota/flavor/TLS/recovery/sync tests remain.

The full integration flow also passed against an actual **main 1.1.1-alpha server JAR in a separate JVM** (`-Dwowuisync.integrationServerJar=/absolute/main-server.jar`), including push/pull/restore/recovery/head races. Protocol/repository/server schema/client schema remain 2. No installed server replacement is necessary for the client-only package. Server runtime behavior is unchanged; release packaging adds the client-only ZIP.

Required compile/package checks, updater tests and native GitHub Linux/Windows/macOS matrix are recorded in the completion addendum after execution. The local runtime lacks javac launcher/ct.sym; an external launcher delegates to the installed compiler module, and Maven source/target 21 overrides --release only for this environment. Repository/CI defaults remain a full JDK 21 with --release 21.

Reproduce with `tools/ScanWorkload.java` against the baseline and candidate JARs, a fresh fixture per cache and `-Dwowuisync.scanWorkers=1|2|4`. `tools/measure_scan.py` samples Linux process RSS. `tools/ProfileScan.java` records optional diagnostic JFR. Keep benchmark runs separate from tests/builds and repeat on native storage before choosing workers.

Delivery contains a versioned deployment ZIP, client-only ZIP and SHA-256 sidecars. Close client, replace its existing JAR, reopen beside its existing data directory. Preserve settings, pins, credentials, installation IDs and cache. Server JAR-only upgrades, if separately chosen by the operator, retain the existing unit/data paths and need no setup. Nothing was merged or deployed. Native CI proves correctness/packaging, not desktop speed, antivirus behavior, HDD/network-drive throughput or every power-loss race.


## Completion evidence

`./compile.sh` and `./package.sh` passed locally (full JDK workaround described above); packaging runs all 53 tests, with 52 passed and the optional external-key fixture skipped. Three isolated updater tests passed. Both deployment/client-only archives were inspected for exact membership, checksums, JAR entrypoints/versions and executable shell permissions. The 1.1.1-alpha separate-process compatibility flow passed again after the precommit-mutation regression was added.

PR: https://github.com/Azotorp/WoW-UI-Sync/pull/3. Initial native matrix on implementation commit `aaad6311f1f8f9fd880b5fb5c64b3afdc6ceeb13`: https://github.com/Azotorp/WoW-UI-Sync/actions/runs/43570240286. Linux, Windows and macOS build/test/package/artifact steps succeeded; final completion includes a rerun after progress-label and documentation follow-ups. See the PR checks for the exact final commit. These are correctness/portability checks, not native desktop performance benchmarks.
