# 1.1.2-alpha release status

Implemented and tested on an isolated development branch; no merge or deployment. Protocol/repository/server schema/client schema stay 2. Existing 1.1.1-alpha servers remain compatible with the new client; normal JAR-only upgrades retain service/data paths and need no setup.

## Implemented

- Authoritative head before automatic scanning; absent head skips discovery/content/staging, with distinct zero-file snapshots, retained history, error propagation and pending-journal protection.
- Staging-free ordinary and strict scans; upload stages only requested content with immutable hash/length verification. Strict review/precommit/recovery/apply verification remains.
- Advisory per-file cache writes in bounded 128-entry transactions; cancellation/failure/normal close preserve verified work. Partial scans do not publish manifests, baselines or deletion decisions. One retry handles occasional external-updater writes; persistent mutation fails safely.
- One inline worker by default, optional bounded 2/4-worker hashing, separate 64 MiB content-buffer budget, deterministic references and cancellation cleanup. Existing Windows creation-time identity fallback and migration/settings/pins/credentials are preserved.
- Explicit scan modes, miss reasons, actual source bytes, phase timings and throttled EDT progress. Subsequent indeterminate phases never display a stale completed percentage.
- Validated deployment and client-only ZIPs with SHA-256 sidecars; source/engineering report/raw fixture measurements in PR #3.

## Evidence

- Local required compile/package: 53 tests, 52 passed, one optional external-key fixture skipped, no failures/errors. Three updater tests pass. Existing security/migration/TLS/flavor/quota/recovery/sync coverage remains.
- Actual separate-JVM 1.1.1-alpha server compatibility flow passed with the new client, including mutation refusal, push/pull/recovery/restore and head races.
- Native GitHub Linux/Windows/macOS build/test/package steps passed on implementation commit aaad631; final follow-up checks are available on PR #3.
- Identical 36,000-file / 505,037,951-byte fixture: recorded first fill 8.01 s main → 4.76 s sequential optimized. Scan staging 505 MB → zero. Before-push repeat and fresh-process checks reuse all 36,000 hints with zero source bytes read. Complete ordered chunk-reference digests match every worker variant/scenario.

## Limits

No native Windows desktop/antivirus performance claim, cold physical-disk claim, actual production service deployment, HDD/network-drive speed claim or exhaustive power-loss proof. Some metadata/restart timings regress; parallelism is optional because results vary. A forced kill can lose the current small cache batch; normal window-close flushes it. Metadata hints remain advisory and cannot detect every metadata-preserving edit on Windows. Strict operation verification remains mandatory. See ENGINEERING-REPORT.md for exact evidence and remaining bottlenecks.
