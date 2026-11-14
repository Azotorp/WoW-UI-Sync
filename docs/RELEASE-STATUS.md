# 1.1.1-alpha release status

Implemented and locally verified on Linux/Java 21. No production server was changed. This is an alpha release; Windows, actual systemd deployment and WAN behavior are not certified by the local results.

## Implemented

- Protocol/repository 2 snapshots, content-defined chunking, account-scoped dedupe, historical/selected restore, pinned server recovery snapshots, immutable staging and durable apply journals remain intact.
- Persistent metadata/chunk-manifest cache with checksummed compact rows, four-root decoded cache, database-change invalidation and delta persistence. Ordinary checks do not read unchanged content. Unknown/recent metadata is rehashed; Windows uses creation time when Java supplies no file key.
- On-demand verified staging for requested upload objects. Push/destructive verification remains strict, so verified operations can still read unchanged files.
- Lightweight server status/account display; history is summarized before listener startup and on changes, not during those requests. Quota and authorization still consult authoritative data.
- Automatic checkpointed startup migrations for recognized legacy data, same JAR path/service/startup arguments, preserved accounts/config/TLS/objects. Unsupported formats fail closed. Client cache schema migrates without resetting preferences/pins.
- Discovery and measured progress, cache/hash/byte/elapsed counters, phase timings and cancellable status requests. Cancellation during network I/O closes the connection.
- Optional in-place Linux systemd updater with atomic replacement, ownership/mode preservation, previous-binary retention and pinned TLS/application readiness. Existing release-ZIP tooling remains optional.

## Local evidence

- Maven test suite: 44 tests, 43 passed and one optional external private-key fixture test skipped. No failures/errors.
- Three isolated Python updater tests passed (service/name/permissions/config preservation, invalid artifact refusal before stopping, readiness-failure stop without rollback).
- Existing TLS push/pull/history restore, flavor isolation, upload forgery/quota/security and apply-journal recovery tests passed.
- Added cache lifecycle/change/corruption/staging/cancellation/path/Windows-file-key fallback tests, startup migration/failure/newer-schema/WAL backup tests, lightweight-status tests and Swing progress/cancellation tests passed.
- Real old/new JVM rehearsal: same installed JAR path and arguments, old root login, automatic migration, retained verified legacy bytes/snapshot, unchanged TLS/config/account, restart without repeat migration.
- Reproducible 10,001-file benchmark and separate-JVM cache reuse probe. See ENGINEERING-REPORT.md and docs/benchmarks/.
- Both shaded JARs built; the release packer validates manifests, membership, checksums, ZIP shape and executable mode bits.

## Limits and deferred work

- Native Windows/macOS filesystem and GUI behavior, antivirus impact, actual installed systemd service, slow/network drives, WAN failures and all power-loss points have not been exercised here. Windows metadata-only checks can miss deliberately preserved creation/mtime/size; strict verified operations detect content differences.
- First cache fill, database cache reload after restart and strict operations are not universally faster than v1. The benchmark does not establish Windows speed or physical-disk throughput.
- Display metadata warmup still enumerates retained snapshot metadata once at server startup. Advanced at-rest index packs, background scrubbing, pooling, storage dashboards and automatic internet updates remain deferred.
- Routine JAR replacement does not need setup or the optional updater. An old binary cannot be made schema-safe retroactively; never downgrade across incompatible schema/repository versions without a matching verified offline checkpoint.
- The optional JAR updater needs Python 3 and explicit existing unit/data paths; wrapper units require the actual installed JAR path. Its systemd contract was tested with isolated mocks, not a live systemd installation.
- The server operator can read stored plaintext content. Independent off-host backups remain necessary for disaster recovery.

## Completion follow-up

Final local build: 46 Maven tests, 45 passed, one optional fixture skipped; three updater tests passed. Fixed Windows rotating-log handle release at server exit and macOS ancestor-alias path validation after the initial GitHub matrix exposed them. Installation-internal links remain rejected. See ENGINEERING-REPORT.md for the completion evidence; remote matrix results are separate.
