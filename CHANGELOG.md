# Changelog

## 1.1.1-alpha

- Restore persistent metadata/chunk-manifest caching for ordinary status checks, with precise timestamps, file identity, Unix change time when available, recent-write distrust, checksummed cache rows and incremental database updates.
- Stop staging or hashing unchanged files during ordinary status checks. Verify and regenerate only missing upload staging; keep strict content verification around push commits and destructive apply/recovery.
- Cache server revision/display usage outside status and account-list requests. Rebuild display metadata once before accepting connections; quota enforcement still uses authoritative repository data.
- Report discovery, cache/metadata comparison, hashing, manifest, persistence and server phases; throttle scan progress and support cancellation, including closing a blocked status connection.
- Automatically migrate recognized server schemas/repositories on startup under the existing exclusive data lock, with verified SQLite/config/identity/manifest checkpoints. Refuse unsupported formats; fail startup safely on migration errors.
- Add optional in-place JAR updater for an explicitly identified existing systemd service, atomic replacement, preserved ownership/mode, previous-binary retention and pinned TLS/protocol readiness verification. Manual JAR replacement is fully supported.
- Preserve protocol 2 and repository 2, snapshots, dedupe, recovery journals, accounts, TLS pins and existing configuration paths. Client cache schema upgrades from 1 to 2 without resetting settings.

## 1.1.0-alpha (recovered source)

Immutable server snapshots, content-defined chunking, recovery journals, multi-flavor isolation and improved account/security workflows. Recovered source and scanfix1 were previously delivered outside GitHub; see ENGINEERING-REPORT.md for provenance.
