# WoW UI Sync — v1.1.0-alpha Design & Upgrade Plan

**Status:** Approved-direction design specification; implementation not started by this document  
**Repository:** https://github.com/Azotorp/WoW-UI-Sync  
**Baseline audited:** `ccr-bc9e9633-4tnnd4` (development branch, November 8, 2026); also compare against `main` before implementation  
**Prepared:** November 11, 2026  
**Target:** Client and server release `1.1.0-alpha`  
**Primary platform:** Windows desktop client; Linux/Ubuntu headless server; retain Windows server and macOS client/server support where practical  
**Implementation audience:** GPT Work / a software-development agent or human maintainer

> **IMPORTANT:** This is a development handoff, not a declaration that the proposed functions already exist. Do not delete or replace any currently deployed data, merge branches, or deploy without explicit operator approval. Preserve working v1.0 installations until migration has passed verification.

---

## 1. Executive decision record

1. **Support every recognized WoW installation flavor** instead of only Forever. Detect known folder names, support multiple game roots and manual paths, and allow adding future identifiers through a maintained registry.
2. **No cross-flavor synchronization, migration, import, or restore in v1.1.0-alpha.** A flavor can only synchronize with the same flavor. In particular, Retail ↔ Forever and Classic ↔ Anniversary are prohibited. Treat different instances of a shared physical directory as *one* installation, rather than inventing duplicate profiles.
3. **Replace persistent local full-folder backups** (`Interface_*`, `WTF_*`) with immutable, incremental **server-side snapshots**. Never create those old local backup folders in the new workflow. Temporary per-file staging and an operation journal are allowed for safe application; neither is a permanent local full-folder backup.
4. **Restore any retained snapshot** to the original flavor, inspect differences, and optionally restore selected files. Snapshots remain independently addressable until explicitly deleted or removed by a user-enabled retention policy. No snapshot is silently pruned by default.
5. **Use restic-like content-defined chunking (CDC), SHA-256 integrity, deduplication, compression, and snapshot manifests** as the primary new data engine. This is a custom Java implementation inspired by the *techniques*, not a restic repository and not a claim of binary/format compatibility with restic. Genuine rsync block delta is a separate, optional future optimization, not a required external binary.
6. **Make onboarding self-service with an invitation.** The Windows client can accept an invite, generate a local private key or use a password, register, store connection details safely, and sign in automatically. Public registration remains disabled by default.
7. **Security and integrity fixes take precedence over new artwork or visual features.** The v1.0 scan/pull/transfer edge cases, quota problems, request limits, credential storage, and crash recovery must be addressed.
8. **Package both runnable JARs and all server scripts in one deployable ZIP**, with no nested `scripts/` folder. Provide standalone `compile.sh` and `package.sh` at repository root and optional `.bat` counterparts.
9. **Use explicit, transactional upgrades** with verified artifacts, database migration, data-store migration, and rollback. Support optional update-on-next-restart only after signed release verification and explicit admin opt-in. Do not silently self-update by default.
10. **The existing server is command-line/headless, not a desktop GUI.** Continue supporting a robust headless service; add an optional, separate desktop server administration GUI mode if feasible without making the service dependent on a display.
11. **Visual theme:** dark, readable fantasy/arcane synchronization aesthetic. Embed separately generated client/server backgrounds as optional, muted resources; they must never compromise text contrast, and offer a solid-background/high-contrast preference.

### Goals and deliberate non-goals

| In scope for 1.1.0-alpha | Out of scope for 1.1.0-alpha |
| --- | --- |
| Safe snapshots, restore, storage dedupe, incremental transfers | WoW addon runtime code or an in-game addon |
| All recognized flavor directories and manual discovery | Cross-flavor copying or automatic compatibility conversions |
| Registration onboarding and account recovery hardening | Open, anonymous signup |
| Clean Linux/Windows scripts, release ZIP, safe upgrade | Mandatory third-party rsync/restic installation |
| Optional desktop server administration UI | Requirement to run a GUI on a headless server |
| Security, migration, and failure/recovery testing | Claiming a zero-downtime database migration |

---

## 2. Existing system and repository inspection

### 2.1 Baseline capabilities to preserve

The current Java 21 multi-module Maven project includes `common`, `client`, and `server`. It already provides TLS 1.3 with server-certificate fingerprint pinning, password and SSH public-key login, hashed invitation secrets, roles (root/superuser/user), server quotas, control/data connections, SHA-256 object hashes, compression, manifest delta uploads, selective pulls, and resumable large files. It supports game-running detection and automatically creates **full local directory copies** before pulls, which must be replaced by the new snapshot model. Existing client account creation via invite works, but the invite workflow defaults to requiring an existing SSH private-key file and does not automatically log the new user in.

Current flavor support is explicitly limited by `common/.../profile/Profiles.java`: `ENABLED = Set.of("forever")`. The client detector already scans an install root and recognizes underscored folders and `.flavor.info`/WoW executables, but currently requires local content folders for valid detection, and only has a macOS suggested install root. Current server-side `Storage` keeps content-addressed full-file objects per account/profile and only one current manifest. There is **no immutable historical snapshot repository** yet.

### 2.2 Key implementation files (audit and future work)

- `common/src/main/java/io/github/azotorp/wowuisync/common/profile/Profiles.java`
- `common/.../path/{SyncPaths,Exclusions}.java`
- `common/.../{manifest,pack,codec}/...`
- `client/src/main/java/io/github/azotorp/wowuisync/client/wow/{Flavor,FlavorDetector,WowProcess}.java`
- `client/.../sync/{Scanner,SyncOps,TransferEngine,BaseStore}.java`
- `client/.../local/{Backups,FileOps}.java`
- `client/.../net/ServerConnection.java`
- `client/.../ui/{JoinInviteDialog,ConnectionEditDialog,ConnectionsDialog,MainWindow,SyncController,PullSelectionDialog,WowGuard}.java`
- `client/.../data/{ClientConfig,ClientDb,ClientPaths}.java`
- `server/.../sync/{SyncService,Storage}.java`
- `server/.../service/{AuthService,AccountService,InviteService,ResetService,LoginGuard,Access}.java`
- `server/.../net/{ConnectionHandler,Listener,Session}.java`
- `server/.../{ServerMain,ServerApp}.java`
- `server/.../install/Installer.java`
- `server/.../config/{ConfigIO,ServerConfig,DataLayout}.java`
- `server/.../db/ServerDb.java`
- Root `pom.xml`, `.github/workflows/{build,release}.yml`, `README.md`, `DESIGN.md`.

### 2.3 Source-audit findings to resolve

The following are code-inspection findings, not successful exploit or benchmark claims.

| Severity | Existing behavior / concern | Required remediation |
| --- | --- | --- |
| **Critical** | Quota estimation uses declared manifest sizes; some upload paths are not validated against the declared expected size and permitted object membership before storage and commit. | Validate actual chunk/pack bytes against expected size and hash, verify all references at commit, reserve/charge storage transactionally, and enforce per-account/group quotas including pending transfers. |
| **High** | `Scanner.visitFileFailed` logs and continues, which can result in a push omitting files it could not traverse. | Fail closed on filesystem traversal errors and on unsafe names unless explicitly excluded. Never interpret missing/unreadable local files as deletions. |
| **High** | `TransferEngine.download` reuses previously staged files based on size alone. | Verify SHA-256 before reuse, before writing, and after reconstruction; reject and re-fetch corrupt staged files. |
| **High** | `SyncOps.pullApply` writes individual files atomically where supported but does not commit an entire operation atomically, and local-by-hash source files may be overwritten during the same operation. | Immutable staging of every input, durable apply journal, verified pre-apply recovery snapshot, startup recovery/rollback, re-check destination state. |
| **High** | Server `fetch` reads whole objects into memory and may decompress data in memory, potentially multiplying per-connection memory pressure. | Bound total frame, pack, decompressed object and per-session memory; stream large objects; use per-session/global budgets. |
| **High** | Password reset requests lack a clear per-account/email cooldown and can trigger repeated mail delivery. | Account/IP limits, resend cooldown, mail queue bounds, and monitoring; preserve nonenumerating responses. |
| **High** | TLS listener uses a virtual thread per connection without a global cap on handshakes/active connections. | Connection quotas, semaphore at accept, handshake deadlines, pre-auth rate limits and idle timeouts; close excess sockets. |
| **Medium** | Optional plaintext saved passwords in client SQLite DB. | Store secrets in OS credential vault or use generated keys; migrate/remove plaintext values with consent. |
| **Medium** | Recovery email can be set without ownership verification. | Verify email with expiring token before enabling reset to that address. |
| **Medium** | One shared synchronized SQLite connection can serialize concurrent requests. | Profile latency, use a bounded connection pool/readers as safe, move heavy processing out of database locks. |
| **Medium** | `FileOps` backup and restore actions can copy entire `Interface` + `WTF`; space checks are separately calculated for backup/pull. | Replace persistent directory copies with server snapshots; budget total peak staging and recovery requirements in advance. |
| **Medium** | Large-file partial uploads are addressed by account+hash, and concurrent workers may append to same partial without a per-object lock. | Lock/reserve each partial chunk object; validate offset, ownership, expected total and session. |
| **Medium** | Push status is optimistic based on hash cache using size+mtime, and another process may change files between scan and apply. | Re-check source identity during upload; revalidate destination before applying; optionally require WoW to be closed for destructive operations. |
| **Medium** | User-facing server errors may include internal exception messages in some cases. | Sanitize all external errors; keep stack traces only in restricted logs. |

Additional checks: ensure `SyncPaths.resolve` refuses symlink/junction escapes and that deletions never follow attacker-controlled links, validate manifest count/length and decompression limits before allocating memory, forbid arbitrary object fetching by guessed hash, and validate all permissions on *every* snapshot and chunk access.

---

## 3. WoW flavors: discovery, naming, isolation

### 3.1 Recognized directory registry

The initial registry should recognize these **installation directory names**, treating version/expansion labels as display metadata, not permanent storage keys:

| Folder identifier | Friendly label (2026 example) | Logical flavor key |
| --- | --- | --- |
| `_retail_` | Retail | `retail` |
| `_classic_` | Progression Classic / Mists of Pandaria Classic | `classic` |
| `_anniversary_` | Classic Anniversary / TBC Anniversary | `anniversary` |
| `_classic_era_` | Classic Era / Hardcore / Season of Discovery (shared installation) | `classic_era` |
| `_forever_` | WoW Forever | `forever` |
| `_classic_titan_` | Classic Titan Reforged | `classic_titan` |
| `_ptr_` | Retail PTR | `ptr` |
| `_xptr_` | Retail PTR 2 | `xptr` |
| `_beta_` | Retail Beta | `beta` |
| `_classic_ptr_` | Classic PTR | `classic_ptr` |
| `_classic_era_ptr_` | Classic Era / Anniversary PTR | `classic_era_ptr` |
| `_classic_beta_` | Classic / Forever Beta (build-specific) | `classic_beta` |

These names are an **observed registry**, not a promise that Blizzard will never rename a folder or that each expansion gets its own install. Determine a flavor using directory + supported game markers/build metadata; do not infer separate `mop`, `tbc`, `sod` directories unless actually present. The registry must support safe updates and manually confirmed unknown installations.

### 3.2 Discovery rules

1. Detect common Windows, macOS, and user-configured WoW roots. Prefer launcher-provided install locations when obtainable without elevated privileges; inspect multiple drives only within reasonably bounded candidate directories, not uncontrolled whole-disk crawling.
2. Permit **multiple independent installation roots**, custom paths, removable drives and manually registered directories. Persist a stable local `installationId` (UUID) distinct from `flavorId`, absolute path, display label, game build, last verified timestamp, and status.
3. A known directory with valid game markers is eligible even if `WTF` or `Interface/AddOns` does not exist yet. Show `Not configured yet` rather than `Not a WoW install`.
4. Detect canonical paths/case-insensitive aliases on Windows, avoid duplicate entries caused by junctions/symlinks, and prevent arbitrary symlink traversal during sync.
5. Known PTR/Beta folders are **separate flavors**, even if they share an addon API with Retail/Classic.
6. For `_classic_era_`, **Season of Discovery, Hardcore and Era remain one installation/flavor** unless the user has genuinely separate installations. Provide account, realm and character selection *within* that flavor to avoid mixing settings across characters; don't fabricate a separate SoD profile.
7. Treat a changed game build as metadata and a possible compatibility warning, not permission to copy from another flavor. For major/structural mismatches within a flavor, refuse automatic destructive apply until the user reviews differences.
8. Keep automatic account/profile selection stable after restart. If the old single `lastProfileId` preference is still present, migrate it into the new local installation selection.

### 3.3 Isolation invariant — mandatory

`flavorId` is bound to each remote sync head, immutable snapshot and restore operation. **An operation's source and destination `flavorId` MUST match exactly.** Validate this server-side, not merely by disabling an option in Swing. Do not expose an API that accepts an arbitrary source flavor and different target flavor. Refuse cross-flavor transfer requests with a stable `FLAVOR_MISMATCH` error.

Model profiles using a compound identity such as `(accountId, flavorId, remoteProfileId)`; `remoteProfileId` distinguishes optional independent tracks **within the same flavor**, not across flavors. Local `installationId`s can map to the same remote track when the user deliberately syncs two computers. Every snapshot is tied to that compound identity and, for recovery snapshots, a device/installation identity. Shared physical chunk bytes within the **same account** do not imply cross-flavor file access.

### 3.4 Configuration categories

Defaults: **include** `Interface/AddOns/*` and selected SavedVariables, character/realm addon settings, user-selected macros/keybinds; **exclude** known device-specific display/audio/controller configuration and cache/temp artifacts. Preserve the existing exclusion of `WTF/Config.wtf` and gamepad files by default. Allow profile-specific exclusions and a clear preview.

Caution: do **not** pretend that video/audio/resolution values can be safely excluded as isolated properties if they share files or are stored in addon-owned data. Initially, support proven file-level exclusion; setting-level manipulation requires a parser with explicit tests and an opt-in advanced mode. Warn that addon executables and Lua code pulled from another account can run inside WoW; only pull from trusted sources.

---

## 4. New synchronization and incremental snapshot engine

### 4.1 Why CDC rather than full-file snapshots or external rsync

Current full-file content hashing avoids reuploading unchanged files but does not efficiently reuse most content when a large file changes slightly. **Content-defined chunking (CDC)** splits large files at content-dependent boundaries, so many unaffected chunks can be reused when bytes are inserted or removed. Restic uses similar deduplicated snapshot architecture and chunking. **The rsync algorithm is different**: it computes block matches between remote files using rolling and strong checksums; the executable or exact rsync protocol need not be included in this release.

**Chosen v1.1 approach:** implemented-in-Java CDC with strong hashes and immutable snapshots. No required `rsync`, `restic`, or platform-specific daemon. Continue to use TLS and explicit authenticated transfers. A future benchmark may justify rsync-like delta transfer for a special class of oversized files, but do not implement two competing transfer protocols in alpha.

### 4.2 Chunk and pack design

- For small files (proposed threshold: `<512 KiB`), store one logical content object; optionally pack several small objects together to reduce metadata and filesystem overhead.
- For large files, use a deterministic content-defined chunker (e.g., FastCDC or carefully implemented Rabin-based CDC), with **starting tunables** `min=512 KiB`, `avg=1 MiB`, `max=8 MiB`. These values are initial benchmark targets, not fixed commitments. Store chunker algorithm/version as repository metadata so changes never silently break old chunks.
- Hash **uncompressed plaintext** chunks with SHA-256; verify hashes on upload, disk reads used for repair/restore, and final file assembly. Use a canonical manifest mapping each path to ordered chunk IDs, total size and whole-file SHA-256.
- Compress incompressible content only when beneficial; prefer zstd level 3, fallback to DEFLATE, and record compression method and original length. Support streaming decompression with strict hard bounds against zip bombs.
- Store chunk objects immutably, write to a temp path, `fsync` where supported, then atomic rename to final object identity; write manifests/indices only after all chunks exist and verify.
- Use compact packs for small chunks/objects; maintain an index to locate entries without repeated directory traversal. Pack rewrites, GC, and compaction must not invalidate snapshots that still reference chunks.
- Use cached metadata and bounded parallel hashing, chunking, compression, encryption if later enabled, and network transfers. Do not assume more threads always means more throughput.
- Dedupe physically across profiles **within the same account**, while preserving logical quotas and metadata isolation for each flavor. Cross-account global dedupe is **disabled** in alpha to avoid unauthorized cross-tenant existence information, complexity and ownership issues.

### 4.3 Snapshot/manifest schema (conceptual)

```
Account
  ├── Remote profile: (flavorId, remoteProfileId)
  │     ├── currentHead -> snapshotId
  │     └── Snapshot [id, parentId(s), timestamp, authorDevice,
  │                  sourceType, manifestHash, label, retained, status]
  │           └── FileEntry [relativePath, size, sha256, orderedChunkIds]
  ├── Recovery snapshots [flavorId, localInstallationId, parent/head]
  └── Account-scoped content store
        ├── immutable chunks + small-object packs
        └── index, live references, validation state
```

Maintain these database entities in a migration: `snapshot`, `snapshot_file`/manifest tree, `snapshot_parent`, `profile_head`, `chunk`, `pack`, `chunk_location`, `transfer_reservation`, `transfer_lease`, `recovery_operation`, `retention_policy`, and `audit_event`. Exact normalized schema may vary. Use stable IDs and foreign keys. Snapshot creation is **idempotent** by operation ID and fingerprint, atomic in metadata, and never points to a missing chunk.

Snapshot types include `sync_push`, `before_pull_recovery`, `manual`, `restore`, and `migration_baseline`. Snapshot history is append-only; changing a head creates a new snapshot or head movement with a durable audit event, never rewrites old snapshot contents.

### 4.4 Safe push: protocol flow

1. Discover installation; scan files and validate permissions/paths, exclusions, case collisions, links, read errors and game state.
2. Compare local manifest against remote head and known common ancestor. Clearly show **local changes, remote changes and conflicts** before destructive operations.
3. Upload manifest intent; server validates schema, flavor ID, authorization, quotas, path safety, count/size limits and base fingerprint, and creates a bounded, expiring transfer reservation.
4. Negotiate missing chunk IDs scoped to the authorized account; never disclose object existence across accounts. Group small chunks into bounded packs.
5. Upload/verify chunks, retry/resume interrupted chunks with per-object locks; reject incorrect sizes, offsets and digests.
6. Commit under a per-profile head lock and **re-check the expected base fingerprint**. Check chunk availability and quotas again. If another writer won, return conflict instead of overwriting.
7. Write immutable snapshot + move remote head in a database transaction and durably persist; reply only after commit.
8. Update client baseline and show snapshot ID/time/device; never report completion before confirmation.

Support fair per-session/global concurrency, bandwidth throttles and server-side resource reservations. Use leases for transfers in progress so garbage collection cannot delete their chunks.

### 4.5 Safe pull and restore flow — no full local folder backups

1. Select the **same flavor** remote profile and snapshot (latest by default). Show add/edit/remove counts, install path, per-category selection and projected data usage.
2. Refuse destructive changes if WoW is running; if process detection is uncertain, require explicit warning/confirmation. Re-scan/revalidate after the review dialog.
3. Before changing local files, create a **server-side `before_pull_recovery` snapshot of the local state**. Upload any missing local chunks and verify that recovery snapshot is committed and restorable. This snapshot is device+flavor scoped and **must not replace the shared remote sync head**.
4. If the recovery snapshot cannot be committed because of network failure, quota or server availability, **abort without changing the local WoW directory**. Never fall back to a permanent `Interface_*`/`WTF_*` local backup.
5. Download missing chunks to bounded temporary staging, verify hashes, and reconstruct all target files from **immutable** staged data. Never reuse a live local file as a source that an earlier operation step may overwrite.
6. Build a durable *operation journal* listing old state, target state, file writes/deletions, and recovery snapshot ID. Validate paths, free space and current local state one final time.
7. Apply verified files with atomic per-file rename where supported; make deletes only after required writes and with validated canonical paths. The operation journal tracks progress so an interruption is detectable.
8. On interruption, do not mark success. On next start, block new sync until the client **resumes completion or restores from the committed recovery snapshot**. If network is unavailable and rollback content is not locally staged, keep the journal and clearly say recovery requires reconnecting; do not invent offline recovery.
9. Once applied, re-scan, check final target fingerprint for whole-profile actions, update baseline/device state, mark operation committed, and remove transient staging by policy.

There must be no newly created persistent `Interface_<name>_<date>` or `WTF_<name>_<date>` directories from v1.1 code. Existing legacy backup folders are left untouched by migration; offer a **separate explicit cleanup tool**, only after the user verifies server-side snapshots, with no automatic deletion.

### 4.6 Snapshot history and restore UX

- `Snapshots` view: current head, timeline/list, timestamp in local timezone and UTC, device, account, flavor, file count, logical size, physically added bytes, notes, retention/pin state, integrity status.
- Filters for device, date range, snapshot source, tagged/pinned, and files changed.
- Restore **entire flavor** or **selected files/folders** from **any retained snapshot**, to the same flavor only. Preview added/changed/removed paths and require a recovery snapshot of the current destination before restore.
- Restoring a prior version creates a **new restore event/snapshot**; it does not erase or rewrite later history.
- Provide `Verify snapshot`, `Compare with current`, `Pin`, `Label`, `Export manifest`, and `Delete snapshot` (with dependency-aware GC and confirmation).
- Immutable snapshots **do not imply infinite retention**: default policy `retain all; warn on size/quota`. Allow explicit configurable retention (`keep last`, daily, weekly, monthly, pinned forever, minimum protection window). Quotas must not delete snapshots without authorization. On quota exhaustion, stop new destructive work and offer cleanup, not silent pruning.
- Optional offline snapshot export can be a separate future feature; initial restore requires server access and verified chunks.

### 4.7 Garbage collection, compacting, and integrity

- Mark-and-sweep from all retained snapshots, active heads, pinned snapshots, in-progress transactions and recovery leases; only purge unreferenced chunks after a safe grace period.
- Make GC crash safe and concurrency safe: lock/lease correctly, never delete a pack referenced by a committed snapshot, and keep a repair/reindex process.
- Maintain checksums of stored objects and pack indices. Add scheduled scrub on idle periods with rate limiting; log corruption and mark affected snapshots degraded rather than silently returning corrupt data.
- Provide per-account logical usage, unique physical bytes, total physical server usage, dedupe ratio, pending-transfer footprint and reclaimable bytes. Use conservative accurate quota accounting with transactional reservations.
- Support server-admin export/backup of database, config, TLS identity, keys and content store. **Server-side snapshots on one disk are not a substitute for off-host disaster recovery.** Document a separate external backup strategy (e.g. backup server data to another disk/object storage using an external tool).

---

## 5. Security requirements

### 5.1 Authentication and registration

- Keep TLS 1.3 and certificate fingerprint pinning; invitation codes may safely include the server fingerprint. On certificate rotation, require out-of-band verification; never auto-trust a changed fingerprint.
- Keep roles (root, superuser, user) and authorize every account, profile, snapshot, chunk and administrative request server-side.
- Default registration: **invite-only**. Use secure random, single-purpose, expiring invite tokens with configurable use counts and role restrictions. Redeeming a token must be transactional with account creation and capacity allocation; roll back both on failure.
- In the client onboarding wizard: paste invite → verify host/fingerprint → choose username → choose **recommended automatically generated modern key** or password → optionally verify recovery email → register → save connection securely → **auto sign-in** → detect/select WoW folder. Preserve the ability to import an existing private key, but do not require one.
- Generate private keys locally. Store using platform facilities with restrictive permissions and optional passphrase; send only public keys to the server. Show a recovery-key export warning and a way to enroll another device without sharing private key bytes when feasible.
- Keep password-based accounts, but remove the UI option to save cleartext in SQLite. Prefer Windows Credential Manager / macOS Keychain / Linux Secret Service; if unavailable, default to prompting, with no insecure fallback silently enabled. Prompt users to migrate/delete legacy saved plaintext secrets.
- Require current credential or stronger reauthentication for sensitive self-service changes; root/superuser credential resets and account ownership actions must generate audit logs and revoke old sessions.
- Verify recovery emails before making them usable, and add request cooldown, rate-limit and queue limits. Do not leak existence of accounts in reset responses.
- Use bounded PBKDF2/Argon2id parameters with migration strategy and resource limits; do not silently accept arbitrary client-requested iteration counts. Ensure secret chars/buffers are cleared when possible.

### 5.2 Network and storage hardening

- Global and per-IP limits for unauthenticated connections, handshakes and requests; limits for authenticated sessions, transfer workers, pending transfers, frame size, manifest entries, paths, chunk size and in-memory decompression.
- Every binary transfer must validate declared sizes against actual bytes and expected chunk/file hash **before** persistent commit. Apply per-account/group logical quota limits and global disk-reserve watermark at both preflight and commit.
- Validate canonicalized paths, case collisions, Windows reserved names, symlinks and junctions on both client and server; restrict I/O to allowed per-flavor namespaces. Never allow payload path traversal or cross-flavor output paths.
- Do not expose raw cross-account chunk lookup APIs; use snapshot-authorized objects and scoped opaque tokens when necessary. Guard against dedupe-oracle attacks.
- Credential files, SQLite database, TLS keystore and config are protected with restricted permissions/ACLs. Avoid logging passwords, invite secrets, key material or full reset codes. Logs should use limited retention and redaction.
- Keep an audit log of login, invite creation/redemption, account creation/deletion, snapshot restore/delete, permission changes, upgrade and rollback.
- Declare the privacy model clearly: TLS protects transfer and server storage is account-authorized, but the **server operator can access stored plaintext content** in alpha. End-to-end encryption is **not** promised unless fully designed and tested later.

### 5.3 Threat and failure test matrix

Required simulated/adversarial tests include: malformed frame lengths and codec tags; compressed-bomb input; guessed hash/snapshot access; account and flavor isolation; simultaneous invite redemption; missing file errors; symlink/junction escapes; quota bypass via falsified manifest sizes; high-concurrency connection floods; reset-email floods; chunk corruption; abrupt loss of client/server power during commit, update and restore; and concurrent GC with active transfer.

---

## 6. Client and server UX

### 6.1 Client (Swing desktop) screens

1. **First-run welcome:** `Join with invite`, `I already have an account`, `Advanced / server administration`.
2. **Guided account creation:** automatic local key generation by default, password alternative, save connection, auto-login, simple error states and recovery guidance.
3. **Installation manager:** auto-found and manually added paths across drives; show folder, flavor, build, active status, local installation ID, linked remote profile, and warning for unknown/broken installs.
4. **Dashboard:** selected account, selected flavor, chosen installation, sync status, last remote update, last local snapshot, transfer statistics, storage/quota and backup integrity.
5. **Push/Pull review:** side-by-side local vs server diff; explicit file/category selection, preflight checks, conflicts and destination path; never auto-overwrite during conflict.
6. **Snapshot browser:** chronological restore, compare, labels, pin, integrity check and retention settings.
7. **Settings:** per-flavor exclusions, device name, credentials, storage policy, update channel, network throttles, accessibility/theme, audit logs.
8. **Recovery center:** pending interrupted operations, safe resume/rollback options, server reachability, last known verified recovery snapshot.

Allow one flavor active at a time in the dashboard, but let users manage all installed flavors. Distinguish **sync head** from **recovery snapshots** prominently. If the app supports a future optional scheduled sync, it must remain disabled by default until collision/recovery tests pass.

### 6.2 Server administration UI

**Present state:** server is headless (`ServerMain` commands `run/install/reset-root/stop/data-dir/version`) with no desktop GUI. v1.1 should keep a headless daemon as the canonical production mode and add an **optional local desktop `gui` mode**, hosted by the same server JAR if a graphical environment exists. Do not require Swing or an X display to start the server service.

The optional server GUI should show service status, active sessions, per-account quotas, storage usage/dedupe metrics, snapshot integrity, admin logs, invite/user management, update status, backup/GC maintenance and safe stop/restart. Sensitive admin actions need authenticated local-owner authorization; GUI presence does not implicitly grant root rights to a remote user. Client-side accounts management should remain available to authorized roles as the primary cross-platform remote admin UI.

Use matching muted client/server art. Put images in resources and embed in the respective JARs so both JARs run standalone. Provide light/dark, wallpaper on/off and solid-color high-contrast fallback; apply an opaque/near-opaque panel behind every text input/table/log. Background artwork is decorative, never the sole means of communicating state. Support window resizing and HiDPI.

### 6.3 Multi-device behavior

A local `installationId` is unique to a physical install. Devices may deliberately map the same flavor to the same remote profile (e.g., Forever desktop ↔ Forever laptop); never assume sync merely from matching folder names if no account/profile mapping exists. Capture snapshot origin device, conflict ancestry and linked install metadata. When a new device connects, show **download setup** vs **upload this PC** choices with non-destructive defaults and recovery snapshot protection.

---

## 7. Server sizing and performance plan

Target reference host from the project owner: **AMD Ryzen 7 9700X (Zen 5, 8 cores / 16 threads, official maximum boost up to 5.5 GHz), 64 GiB DDR5 RAM**. The previously suggested 5.8 GHz is not the AMD published stock max; any higher observed clock would depend on settings/overclock. Reference: https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-7-9700X.html

Suggested initial values are **tuning baselines**, not measured throughput predictions:

- Data connections per active client: **4–6** default; cap **8** initially, raised only after safe memory and network benchmarking; existing code permits up to 16.
- Chunk/hash/compress server worker pool: separate bounded CPU pool, start around **4–8 effective workers**, with backpressure. Virtual threads can continue for lightweight socket handling but must not imply unlimited memory or CPU work.
- Max concurrent handshakes/clients, pack bytes, pending bytes and per-user bandwidth must be configurable and bounded; reserve memory proportional to active sessions.
- Batch metadata reads/writes and indexes; avoid serializing transfer I/O on the SQLite writer lock. Continue SQLite/WAL initially and benchmark with concurrent sessions before considering PostgreSQL.
- Benchmark WAN latency (client desktop vs laptop), SSD/NVMe IOPS, small-file heavy sets, one huge `.lua` SavedVariables file, compressible/uncompressible addons, 2/8/32 concurrent users, large account histories and GC under load.
- Collect latency histograms, throughput, dedupe rate, bytes hashed/transferred, restore time, memory peak, disk amplification and observed CPU utilization. No guessed speedup percentages may appear in release notes.

---

## 8. Script audit and new deployment standard

### 8.1 Existing scripts inspected

| Existing file | Current behavior / risk | 1.1 action |
| --- | --- | --- |
| `scripts/common.sh` | Locates JAR in script folder or parent and checks Java major version. | Rename/rework as `server/common.sh`; resolve own path safely, fail fast on ambiguity, use absolute release paths and quoted arguments. |
| `scripts/common.bat` | JAR lookup + Java existence check, but no robust Java version check. | `server/common.bat`; reliable quoting, absolute paths, version validation and error propagation. |
| `scripts/install.sh`, `.bat` | Calls `install` wizard; `.bat` pauses unconditionally. | Replace with `server/setup.sh` / `.bat` idempotent first-time setup; never overwrite an existing installation. |
| `scripts/start.sh` | Uses GNU screen if available; otherwise foreground; includes endless restart loop on crashes. | `server/start.sh`: choose one documented mode; support `--foreground`, optional screen, and prefer systemd on Linux; bounded restart backoff and no duplicate supervisors. |
| `scripts/start.bat` | New console window + restart loop; only forwards `%1 %2` to some commands. | `server/start.bat`: preserve all args, quote paths, detect duplicates, consistent exit handling. |
| `scripts/stop.sh`, `.bat` | Invokes stop flag via server JAR. | Keep as `server/stop.*`; verify correct data path and service ownership, prefer controlled service stop where installed. |
| `scripts/reset-root.sh`, `.bat` | Resets root key/password while service may be running. | Keep; reauth where possible, protect sensitive input/log output, stop/restart policy, audit action. |
| `scripts/wow-ui-sync.service` | Example systemd unit for headless server. | Place at `server/wow-ui-sync.service`, use explicit paths/environment, hardening and real start/stop/update commands; avoid screen under systemd. |
| `.github/workflows/build.yml` | Maven Java 21 build, uploads built JARs. | Add automated test matrix, static/dependency scan and package content verification. |
| `.github/workflows/release.yml` | Produces separate client/server ZIPs with scripts under `server/scripts/`. | Replace with single release ZIP and checksum/signature release artifacts. |
| `Installer.java` / `ConfigIO.java` | Writes settings to data-dir and optionally next to JAR; installer aborts when DB has root. | Define authoritative config location, explicit migration/update route and safe idempotent detection; do not confuse reinstallation with update. |

Additional issue: the current `start.sh` can restart indefinitely after persistent configuration errors, and using `screen` plus `systemd` simultaneously can create unexpected duplicate processes. Distinguish **graceful stopped** vs **unexpected failure**, and require one supervisor per installation.

### 8.2 Required repository-level commands

The source repository must contain and document:

```bash
./compile.sh                      # Java 21+ check, clean/compile/tests; build both JARs
./compile.sh --clean              # optional clean build
./compile.sh --skip-tests         # explicit developer-only mode
./package.sh                      # uses successful build artifacts, validates, writes ZIP + SHA-256
./package.sh --version 1.1.0-alpha
```

Recommended Windows equivalents: `compile.bat`, `package.bat`; a PowerShell implementation may be shared internally but invoked by predictable wrappers. Scripts must run from **any current working directory**, handle spaces, test tool availability (Java, Maven, zip), propagate failure, and never package stale JARs. Keep generated archives under `dist/releases/` (not in a source folder). `package.sh` should call the build or require an explicit matching build manifest and matching version to avoid stale artifacts.

### 8.3 Exact deployable ZIP layout

Filename: `wow-ui-sync-1.1.0-alpha.zip`. Top-level should be directly deployable, without an extra parent folder:

```text
client/
  wow-ui-sync.client.jar
  README.txt
  # Optional: platform launchers and license; client artwork embedded in JAR.
server/
  wow-ui-sync.server.jar
  setup.sh
  update.sh
  start.sh
  stop.sh
  status.sh
  rollback.sh
  verify.sh
  reset-root.sh
  common.sh
  setup.bat
  update.bat
  start.bat
  stop.bat
  status.bat
  rollback.bat
  verify.bat
  reset-root.bat
  common.bat
  wow-ui-sync.service
  README.txt
LICENSE
SHA256SUMS
release-manifest.json
```

**No `scripts/` directory anywhere inside the deployable archive.** Source scripts may live in a dedicated repository path during development, but packaging flattens all server-facing scripts directly into `server/`. Rename the compiled/shaded JARs on packaging **exactly** to `wow-ui-sync.client.jar` and `wow-ui-sync.server.jar`, regardless of Maven internal artifact names. Avoid accidental double-nesting. Validate file list, executable bit for Linux scripts, entrypoint and JAR manifests, build version, license, architecture assumptions and checksums in CI.

Never package `server.json`, `client.db`, account DB, private keys, TLS keystore, passwords, invitation secrets or user's WoW directories. Data is always external to release directories.

### 8.4 Fresh install and safe update

- `server/setup.sh`: detect Java/Maven not required at runtime; choose external persistent data dir and bind address; confirm firewall/TLS fingerprint; initialize service account, config, db and root safely; optionally install systemd service using least privileges; idempotent and aborts if data already initialized. `server/setup.bat` equivalent for Windows without Linux-specific service steps.
- `server/update.sh --from <local-release.zip> [--dry-run]`: verify ZIP structure, signatures/checksums, required free space and migration compatibility; acquire update lock; stop service gracefully; back up **server** database/config/identity metadata; stage new JAR/scripts in versioned release directory; migrate data with audit logs; verify; atomically switch active release; restart health check; rollback release pointer when startup fails. Never update while transfer/snapshot commit is in flight without graceful drain.
- `server/update.sh --check`: compare installed and supported signed release manifest without changing anything. Optional `--apply` or `--schedule-next-restart` requires admin opt-in. Do **not** fetch and run arbitrary shell scripts from the internet.
- Auto-update-on-next-start **may** be implemented as an explicitly enabled policy that downloads only signed, trusted release assets to a staging directory. Verify signatures, target channel (`alpha`), version monotonicity, compatibility and checksum; apply before startup, report audit event, and revert on unhealthy startup. Keep default off. Avoid systemd `ExecStartPre` doing privileged network fetches.
- `server/rollback.sh`: roll back binary/config migrations **only if** the stored data schema and chunk store remain compatible, or restore the verified migration checkpoint offline. Never pretend a legacy server JAR can read a new schema.
- `server/verify.sh`: run database integrity check, verify referenced chunks/indexes/manifests/snapshots, free space and TLS identity with read-only default, report nonzero exit status on failure.
- `server/status.sh`: print running state, build/protocol/schema versions and active data path.
- All scripts must include `--help`, safe exit codes, path quoting, logs, reentrancy locks, status and noninteractive flags suitable for automation.

### 8.5 Service choices

**Linux production:** systemd only, no screen/restart-loop nested inside it; dedicated unprivileged service user, filesystem restrictions, clear `EnvironmentFile=` or `--data` path, start/stop with `systemctl`. Provide optional foreground mode for debugging. **Windows:** straightforward `start.bat`/`stop.bat` and optional documented service installation later. **Desktop optional:** `java -jar server/wow-ui-sync.server.jar gui --data ...` to open a local admin UI while ensuring no duplicate daemon is started. If `gui` is unsupported due to environment or time, it must fail gracefully and be called out as incomplete before release.

---

## 9. Migration and upgrade plan from existing v1.0 installs

### 9.1 Non-negotiable guarantees

- **Preserve all existing accounts, credentials, invites where valid, role ownership, quotas, TLS identity/fingerprint, client trust pins, connection records, exclusions, and current sync data.** Never reset account IDs or fingerprint on upgrade.
- Never delete existing `Interface` or `WTF` user files during installation or migration. Do not delete any existing local legacy backup folders automatically.
- Preserve old full-file objects and manifest until a new baseline snapshot has passed complete integrity verification. There is no forceful destructive in-place overwrite.
- Make schema migration versioned, idempotent and reversible via a verified checkpoint when possible. No live mixed-version writers against the same account dataset.

### 9.2 Required preflight

1. Record current repo version/commit and current server binary, data path, profile list and SQLite schema version.
2. Ensure no active pushes/pulls; place server into maintenance/drain mode, stop cleanly and acquire exclusive migration lock.
3. Check free disk space for a **database checkpoint plus transitional metadata and any required chunk conversion**. If chunk conversion would duplicate a large entire content store, permit a conservative, verified staged migration plan or fail with clear space requirement.
4. Back up server SQLite using its backup API / `VACUUM INTO` safely, plus config, TLS keystore, keys and old manifests. This is *server metadata/identity backup*, not new client `Interface`/`WTF` folder backups.
5. Record SHA-256 of migration checkpoint and current TLS fingerprint. Do not continue if checkpoint verification fails.

### 9.3 Conversion and dual-read strategy

- Create new snapshot/index schema without changing old account IDs; convert each existing current manifest into a **`migration_baseline` snapshot** under the exact same flavor name.
- Reuse existing full-file objects as either legacy chunks/objects in a **versioned dual-read** storage layer or rewrite into CDC chunks in a resumable migration worker. Do not delete old objects until every referenced file has been reconstructed/verified in the new store.
- Every conversion operation has a durable cursor/checkpoint, checksums and idempotent restart. Record a per-account/profile completion marker.
- During transition, server may read legacy full-file objects for restore; new writes use v2 snapshot/chunk format. Enforce one authoritative head; never permit old v1 writers to create incompatible unsnapshotted state.
- Upgrade server first. A v1.0 client must receive a **clear incompatible-client/update-required message** when attempting unsupported v2 writes. Optionally allow safe read-only compatibility only if explicitly implemented and tested; **do not silently write with a mismatched protocol**.
- Upgrade each client DB schema, preserving connection records and certificate pins. Migrate old plaintext saved passwords through a consent-based secure-storage process, then clear DB entries. Migration errors do not erase existing configs.
- Replace old backup UI with snapshot UI, keeping access to any legacy local backup folders through a **read-only legacy import/cleanup assistant** until the owner confirms successful recovery snapshots. Never silently drop old backup records on client DB upgrade.

### 9.4 Cutover, health and rollback

1. Verify all migrated manifests and file digests; run account/profile authorization checks and sample restore to a *temporary isolated test directory* (not another flavor).
2. Atomically activate new schema/state and release, then run a health check: DB integrity, root login, TLS fingerprint, listing current heads, snapshot read, restore dry-run.
3. Allow controlled client reconnection; verify Forever-to-Forever desktop/laptop sync and one non-Forever flavor.
4. Keep old binaries and verified migration checkpoint for operator-defined retention. Failed migration → stop and restore checkpoint after analyzing the error; never auto-rollback into an older schema without restoring matching data.
5. Produce a migration report: versions, schema, old/new object totals, snapshots created, bad files, retained legacy artifacts and cleanup recommendations.

---

## 10. Example server configuration schema (illustrative, not yet implemented)

```json
{
  "version": "1.1.0-alpha",
  "storage": {
    "repositoryFormat": 2,
    "contentDefinedChunking": {
      "enabled": true,
      "minKiB": 512,
      "avgKiB": 1024,
      "maxKiB": 8192
    },
    "compression": "zstd-auto",
    "crossAccountDeduplication": false,
    "gcGraceHours": 48,
    "verifyOnRestore": true
  },
  "snapshots": {
    "retentionMode": "retain-all",
    "createBeforeDestructivePull": true,
    "protectPinned": true,
    "allowCrossFlavorOperations": false
  },
  "limits": {
    "maxDataConnectionsPerSession": 8,
    "maxConcurrentHandshakes": 32,
    "maxActiveConnections": 256,
    "maxManifestEntries": 250000,
    "maxPendingTransfersPerAccount": 2
  },
  "registration": {
    "mode": "invite-only",
    "requireVerifiedEmailForPasswordRecovery": true
  },
  "updates": {
    "channel": "alpha",
    "automaticInstallOnRestart": false,
    "requireTrustedSignature": true
  }
}
```

Schema changes must follow existing config migration conventions; preserve `server.json` as the authoritative source in the persistent data directory, and write a minimal *discovery pointer only* adjacent to the JAR if necessary. `ConfigIO` should not silently override a newer persistent setting with a stale jar-adjacent file. Validate config fields strictly, document defaults and provide migration comments. Names and values above are proposed and must be finalized in implementation with tests.

---

## 11. Implementation work packages (do not skip hardening)

| Work package | Deliverable | Gate |
| --- | --- | --- |
| WP0 — Baseline and tests | Document current main/dev branch, add reproducing tests for audit issues, script inventory, capture migration fixtures | Existing tests pass on both branches or deviations are explained |
| WP1 — Integrity/security | Fix traversal errors, staging hash checks, path protections, quotas, bounds, reset abuse, connection budgets | Adversarial tests green |
| WP2 — Storage v2 | CDC, immutable chunks/packs, indices, snapshots, metadata transactions, GC and scrub | Crash consistency + dedupe and restore tests green |
| WP3 — Safe apply | Device recovery snapshot before pull, immutable staging, operation journal, startup recovery, selected restore | Interruption at each phase recoverable |
| WP4 — Multi-flavor | Registry, multi-root detection, metadata, per-profile exclusions and strict server-side isolation | Full flavor test matrix green; cross-flavor requests rejected |
| WP5 — Account UX | Invite wizard, generated key, auto sign-in, credential vault, email verification | Fresh client can register/connect without server shell access |
| WP6 — Client UI | Dashboard, snapshots, recovery view, accessible art, high-contrast toggle | Usability and accessibility acceptance |
| WP7 — Server UI and scripts | Optional local GUI, CLI parity, script reorganization, systemd/Windows workflows | No display required in headless mode; installer/update idempotent |
| WP8 — Migration and release | v1→v2 migration, protocol negotiation, compile/package scripts, single ZIP, CI, rollback | Upgrade/rollback staging rehearsal complete |

Do not merge a work package if its core regression tests are failing. Keep incremental commits, update `AGENTS.md` if present, `README.md`, `DESIGN.md`, changelog, protocol/schema documents and upgrade instructions in each package. Do not overwrite the source repository's existing design document without preserving its historical specification or explicitly versioning the new design.

---

## 12. Acceptance tests and definition of done

### Functional

- [ ] `v1.1.0-alpha` reported consistently by both JARs, CLI `version`, About dialogs, ZIP filename, Maven metadata and release manifest.
- [ ] Recognizes `_forever_`, `_retail_`, `_classic_`, `_anniversary_`, `_classic_era_`, known PTR/Beta clients and manual folders without assuming expansion-specific directories.
- [ ] Same-flavor desktop ↔ laptop sync works; cross-flavor push, pull, snapshot restore and data access **fail server-side**.
- [ ] Handles multiple WoW install roots and two local installations of the same flavor without conflating local IDs.
- [ ] New client invite flow generates key if selected, registers, securely saves connection and signs in automatically.
- [ ] Never persists new cleartext client passwords in SQLite.
- [ ] Every successful changed push produces an immutable snapshot; any retained snapshot can be compared, verified and restored within the same flavor.
- [ ] New pull/restore creates and confirms a server recovery snapshot **before** local file change; no local full `Interface_*` or `WTF_*` backup is created.
- [ ] Device-specific video/audio/controller configuration excluded by conservative defaults, without unsupported claims of per-setting filtering.

### Integrity/security

- [ ] Unreadable/failed directory scan blocks destructive push; corrupt staged bytes are detected and discarded.
- [ ] Swapped file contents, changed files mid-scan, interrupted apply, concurrent pushes and partial chunks recover safely.
- [ ] Missing/corrupt referenced chunks fail verification with no false success or silent corruption.
- [ ] Quota bypass attempts, guessed hashes, symlink traversals and mismatched flavor operations rejected.
- [ ] Password-reset flood, connection flood, malformed frame, very large manifest and compressed bomb tests all bounded.
- [ ] TLS pins preserved on upgrade; reset only with verified operator intervention.
- [ ] GC never deletes chunks referenced by current/retained/pinned/recovery snapshots or active leases.

### Scripts and upgrade

- [ ] `./compile.sh` and `./package.sh` build/test/package from arbitrary CWD; `.bat` equivalents work on Windows if offered.
- [ ] ZIP contains **exactly** `client/wow-ui-sync.client.jar` and `server/wow-ui-sync.server.jar` plus flat `server/*` scripts. **No `scripts/` directory**.
- [ ] Fresh setup works with custom data path and no root shell requirement for the running daemon.
- [ ] Existing install upgrade preserves credentials, data, snapshots, clients, host key and config; interrupted migration can safely resume or roll back via verified checkpoint.
- [ ] Optional auto-update is off by default; signed artifact and health check required before next-start application.
- [ ] Server launches headless under systemd; optional GUI runs only with desktop environment; no duplicated process supervisors.
- [ ] CI verifies functional tests, package shape, SHA-256 checksums, metadata and release documentation.

### User verification scenarios

A. **Fresh install:** Setup Ubuntu server, generate invite, join from Windows, detect WoW Forever, push, install on laptop, pull same flavor, confirm local files and hashes.  
B. **Multi-flavor:** Add Retail, MoP Classic, Anniversary and Era alongside Forever; sync separately; confirm no shared heads.  
C. **Uncertain client config:** A device has different resolution/audio hardware; no automatic overwrite of machine-specific settings.  
D. **Conflict:** Two machines change the same SavedVariables; second push must be blocked pending explicit resolution.  
E. **Time travel:** Create snapshots S1/S2/S3; inspect and restore S1 without erasing S2/S3, and recover previous local state.  
F. **Crash recovery:** Terminate client during chunk download, manifest commit, file replacement and deletion; restart and recover with no undetected partial success.  
G. **Upgrade:** Move existing v1.0 data and scripts through safe 1.1 migration, verify full history baseline and credentials, then rehearse operator-controlled rollback.  
H. **Host/resource:** Concurrent sessions + GC + reset floods on bounded worker pools without unbounded memory or database lock contention.

---

## 13. Developer execution instructions (handoff)

1. Inspect **all current source and scripts** before modifying anything; compare `main` to the most recent development branch and identify the exact implementation baseline. Existing source and tests override any guessed behavior in this document; record discrepancies.
2. Create a dedicated development branch for `1.1.0-alpha`, and write a short implementation plan for the work packages above. Do not change or deploy a production instance without approval.
3. Start by reproducing and fixing integrity/security regressions, then build storage and migration tests. A successful compilation alone does not prove safety.
4. For new snapshot/chunk formats, document exact byte formats, DB schema, versioning, locking and crash-consistency invariants. Include sample upgrade fixtures from existing `wowuisync.db` data.
5. Produce portable build/package scripts, maintain backward-compatible CLI aliases where harmless, and thoroughly test clean setup, upgrade, rollback and service restart.
6. Use the existing generated client/server background artwork as a **visual direction**; implement adjustable opacity or overlays, with safe fallback. Keep original artwork out of logs and data directories.
7. Deliver source code, both JAR artifacts, a single deployable ZIP, SHA-256 checksums, unit/integration/security tests, operator instructions, fresh-install and upgrade guides, known limitations and a migration report template.
8. Keep explicit status reports: `implemented`, `tested`, `not tested`, `deferred`, and `known issue`. Never present proposed features as shipped until tests and packaging pass.

---

## 14. References and evidence

**Repository baseline:** https://github.com/Azotorp/WoW-UI-Sync/tree/ccr-bc9e9633-4tnnd4  
**AMD Ryzen 7 9700X official specs:** https://www.amd.com/en/products/processors/desktops/ryzen/9000-series/amd-ryzen-7-9700X.html  
**WoW current directory registry (third-party live reference, verify periodically):** https://warcraft.wiki.gg/wiki/Template:LatestPatchInfo  
**restic design: content-addressed snapshots, chunking, atomicity:** https://github.com/restic/restic/blob/master/doc/design.rst  
**rsync algorithm technical report:** https://rsync.samba.org/tech_report/  
**rsync implementation overview:** https://rsync.samba.org/how-rsync-works.html

**Original visual assets from this conversation (for eventual inclusion in source resources):** `mystical_sync_network_at_dusk.png` (client) and `fantasy_server_synchronization_citadel.png` (server). Ensure text regions retain opaque panels and accessible contrast. Artwork resources are not credentials or mutable server data.

---

**Final project rule:** `1.1.0-alpha` must be safe to upgrade, support all recognized WoW flavors **without cross-flavor access**, store **incremental immutable server snapshots**, enable restore to any **retained** point in time, create **no new persistent local full-folder backups**, simplify self-service invite registration, harden the network and sync engine, and ship as **one flat-layout client+server deployable ZIP**.
