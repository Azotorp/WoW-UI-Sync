# 1.2.0-alpha implementation design

This branch starts at main `4076848`, whose implementation matches the restored 1.0.0 merge `e7de36d`. The intervening changes are documentation. The attached 1.2 work order authorizes this broader branch; it supersedes the earlier first-patch client-only scope **for this branch only**. No defunct branch was merged or cherry-picked.

## Inventory, ownership and selection

`TocInfo` separates folder/title/Group, required and optional dependencies, account/character SavedVariables and project IDs. Keys ignore case; BOM/CRLF and WoW formatting are normalized. Required aliases are handled independently from optional aliases. Active `<folder>_<profile>.toc` takes precedence over generic `<folder>.toc`; unsupported-only suffixed TOCs do not supply guessed metadata. All files still transfer when their folder is selected. One chosen active TOC defines metadata when multiple TOCs disagree, matching the selected version rather than unioning incompatible declarations.

`AddonGrouper` inventories folders without union-find/family merging. `AddonCatalog` resolves an explicit Group first; otherwise a longest meaningful existing folder prefix with delimiter/PascalCase boundary supplies an inferred family, strengthened by an actual required root dependency. It records the evidence text for UI hover. Project IDs are retained for diagnosis, not used to force family membership. Unsupported or ambiguous weak evidence leaves a folder independent. Titles do not control ownership.

For WTF paths matching `Account/<account>[/<realm>/<character>]/SavedVariables/<basename>.lua[.bak]`, declarations are indexed by **scope + basename**. A unique declaring folder owns the data. If multiple folders declare it, an explicit shared data-only row owns the selection; it is never assigned arbitrarily. Otherwise a matching installed folder basename preserves legacy WoW SavedVariables filename ownership. Remaining data becomes an orphan data-only row. TOC ownership precedes the Blizzard prefix heuristic. A row groups identical scoped basenames across accounts/realms/characters, with all paths inspectable. This avoids conflating account vs per-character declarations.

`PullPlanner.target` is shared pure planning logic used for both directions. Source and destination retain their own catalog provenance. Independent file/data/general/orphan selections determine copies; only an explicitly selected folder's obsolete files or an explicitly removed local-only folder authorizes removal. Missing/unchecked data is preserved. Source-orphan mapping is not silently reassigned from destination TOCs. Add-on enabled-state files such as `AddOns.txt`, account macros, bindings and layout files remain general WTF settings, with no claimed per-add-on ownership rule. General never absorbs recognized SavedVariables.

New client Pull planning runs locally after authenticated manifest/TOC fetch: no local TOCs or SavedVariables contents need to be transmitted to plan a Pull. Server `PullBegin` remains supported with source TOC ownership and conservative preservation of local data. Its fingerprint result reflects the actual resulting manifest. Exact future baseline updates depend on a verified resulting fingerprint, not which boxes were checked.

## Protocol and persistent formats

Protocol version/minimum is **2**. Version 1 clients are rejected before login; version 2 clients reject old server HELLO responses. Future unsupported protocol versions are rejected. `PullSelection` adds explicit `removeAddons` and `orphanData`. `PushBegin` adds `expectedCurrentFingerprint`, checked for both full and delta requests. Changes during upload are checked again at commit. No silent full-manifest retry overrides a concurrent server change.

Persistent server schema and object/current-manifest formats remain version 1. Accounts, password hashing, OpenSSH public-key authentication and TLS keystore formats are unchanged. Storage still holds **one current manifest** per account/profile; historical server snapshots and chunked/deduplicated redesigns are deferred. The existing content-addressed compression, resumable transfers and account isolation remain in use.

Client DB schema stays version 1 with an additional optional cache table. Cache rows use canonical root/path, size, precise mtime, creation/file identity and Unix ctime where available, plus verified timestamp and SHA-256. Atomic SQLite transactions checkpoint every 64 completed hashes or ~1s and flush on orderly cancellation. Queue depth is capped at twice the worker count; default workers=2 after baseline profiling. Clock rollback, identity/metadata changes, malformed hash rows, schema-table replacement and 24h expiry invalidate reuse. Corrupt whole DB fails closed rather than discarding accounts/settings. This is metadata-based cache trust: on a filesystem without trustworthy change identity, deliberate same-size edits that preserve every observed timestamp/identity can evade reuse checks until expiry. It is not an adversarial tamper detector.

Traversal errors or symlinks fail a scan rather than generating an incomplete destructive plan. Hashing validates before/after metadata, then revalidates the enumerated files before producing a manifest. Existing transfer verification detects changed content on upload. Empty-server status checks avoid full hashing, while a first Push validates and hashes all selected installation content.

## Backup/apply recovery

Backup generation uses a new `.partial-*` snapshot directory, hashes every copied regular file, records categories/hash manifest and atomically publishes the completed directory. Only selected categories are copied. Live directories are never renamed for backup. Lack of space, read error, changing content or cancellation prevents applying the Pull. Backup opt-out is explicit.

Pull authenticates/rechecks the source fingerprint, backs up chosen categories, stages downloads and verified local reusable objects, and checks all affected local paths against preview before writing. Each target is verified then renamed from a sibling temp file. Symlink destinations are refused. A pending-operation journal remains after an interrupted apply and causes a startup warning; use Backups to restore selected categories before retrying. Whole-tree apply/restore is recoverable via backups, not globally transactional. Backups disabled means no automatic recovery copy for that category.

Restore verifies snapshot hashes and preserves category selection. A selected originally absent directory is restored as absent. Legacy backups restore present categories only. Restoration replaces the selected generation rather than using mtime-only copy shortcuts that could mix generations.

## Service lifecycle

Java holds an exclusive POSIX/Windows file lock before logs/config/DB startup. Python service setup also uses a compatible POSIX record lock before modifying an existing config. Units use explicit Java/JAR/data paths and selected account/group; setup validates a staged unit and maintains rollback copies. Parallel instances require disjoint data, ports and deployment JARs. Existing-service refusal happens before mutation. Uninstall retains data by design. See UPGRADE for operational limits and test-vs-production commands.

After successful installation, setup optionally adds the selected TCP port to an already-active UFW firewall after explicit `YES`. Loopback-only installs skip firewall changes; inactive/missing UFW is never enabled. No SSH/default/reset/reload operations run. UFW failure is independent of service rollback; the installed service remains available. External/provider firewalls are outside this helper.

There is **no Server Console tab or control implementation** in 1.2. Omitting the optional placeholder guarantees no disconnected/non-root placeholder leakage. The integrated console and any management daemon are reserved for 1.3.
