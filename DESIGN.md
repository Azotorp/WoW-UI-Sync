# WoW UI Sync — Design

Self-hosted backup, sync, and setup switching for your whole World of Warcraft UI: addons, account-wide settings, and per-character SavedVariables.

Open source under GPLv3. Not affiliated with or endorsed by Blizzard Entertainment. World of Warcraft and WoW are trademarks of Blizzard Entertainment, Inc.

This document is the **single current spec**. It consolidates `DEVLOG.md` entries 001–006 with all superseded material removed. Build from this file, not from the dev log.

---

## 0. How to build from this document (read first)

- Build the phases in §24 **in order**. Each phase is sized for one or two limited usage sessions.
- **Every phase ends with:** `mvn -B clean package` succeeding from the repo root, both jars staged in `dist/` (from Phase 1 on), the phase's checklist ticked here, the Phase status table updated, and one commit.
- **No functional testing until the final phase.** Phases only need to compile and be internally consistent. Small unit tests are welcome where they are cheap (codecs, pack format, path rules), but they are not required to finish a phase.
- If code and this document disagree, the code is the truth for what exists; fix the status table. If a design decision must change, add a new `DEVLOG.md` entry, then update this file in the same commit.
- Never add features that are listed in §23 (Deferred).

### Phase status

| Phase | Title | Status |
|---|---|---|
| 1 | Project skeleton and CI | Done |
| 2 | Common core: models, paths, hashing, codecs | Done |
| 3 | Common core: packs, objects, protocol | Done |
| 4 | Server foundation: data folder, database, install scripts | Done |
| 5 | Server networking, login, accounts, invites | Done |
| 6 | Server storage, push, pull, quotas | Not started |
| 7 | Client foundation: data, Connections, login, main window | Not started |
| 8 | Client scanning, addon grouping, local manager | Not started |
| 9 | Client sync: status, push, selective pull | Not started |
| 10 | Client admin: accounts, invites, password reset | Not started |
| 11 | Release packaging and docs | Not started |
| 12 | Test pass (manual, by the owner) | Not started |

---

## 1. Goals

- **Clone** a WoW UI setup to any PC: push from one machine, pull on another, transferring only what differs.
- **Back up** safely: every local change made by the app is preceded by a full local backup, and any backup can be restored.
- **Switch setups**: pull another account's setup (a stream setup, a partner's setup) onto a PC, selectively, without losing the current one.
- **Self-hosted**: one server jar on the user's own machine (VPS or home PC). One server can host many accounts.
- **Fast**: tens of thousands of small files and multi-GB setups must sync quickly, survive dropped connections, and re-check status near-instantly.

Reference setup used to tune defaults (dev machine, `_forever_`): ~31,300 files, ~3.2 GB. Audio ~2.2 GB as ~18,000 short `.ogg`/`.mp3` clips (largest 1.1 MB); `.tga` 478 MB (largest 27 MB); `.blp` 227 MB; `.lua` 220 MB (largest SavedVariables 18.2 MB).

## 2. Glossary

- **Install root**: the WoW folder, e.g. `C:\Games\World of Warcraft`.
- **Flavor folder**: a game version folder inside it, e.g. `_forever_`, `_anniversary_`, `_classic_era_`.
- **Profile**: a flavor as a sync target, identified by a fixed **profile id** (e.g. `forever`). v1 enables `forever` only (§13).
- **Account**: a sync identity on the server (a person, or just another setup). Has a username, role, optional email, storage cap, and either a password or a public key.
- **Role**: `root`, `superuser`, or `user` (§6).
- **Owner**: the root or superuser that created a `user` account (directly or by invite).
- **Connection**: a saved client login to a server (host, port, username, credential), like a WinSCP site.
- **Device**: one client install, with a nickname (default "My PC").
- **Manifest**: list of synced files with path, size, SHA-256.
- **Fingerprint**: SHA-256 over a manifest's sorted `path|size|sha256` lines.
- **Current copy**: the server's single stored state of an account's profile (its current manifest + objects).
- **Base**: per device, account, and profile, the fingerprint the device last fully synced with (§11.3).
- **Local backup**: a full copy of a flavor folder's `Interface` and `WTF`, kept inside that flavor folder (§18).
- **Applied account**: per flavor folder on a device, the account whose setup was last applied there.

## 3. Platforms and stack

- **Java 21** for both jars. Java 21 is the only thing a user installs. No FTP, mail server, or system packages.
- **Client and server run on Windows, Linux, and macOS** (x64 and arm64), in any combination, including both on one machine. Windows is the main test platform.
- Libraries (all GPLv3-compatible; keep their bundled native libraries when shading):
  - `zstd-jni` (BSD): compression. Fallback to JDK Deflater/Inflater if the native lib fails to load.
  - `sqlite-jdbc` (Apache 2): server and client databases.
  - `Jackson` (Apache 2): JSON.
  - `FlatLaf` (Apache 2): Swing look and feel (dark and light).
  - `BouncyCastle` (MIT-style): OpenSSH public/private key parsing, including encrypted private keys. Signatures verified with JDK `Signature` (Ed25519, RSA, ECDSA).
  - JDK built-ins: TLS (`SSLSocket`, TLS 1.3), SHA-256, PBKDF2, threads (virtual threads on the server).
  - SMTP: a minimal built-in sender (STARTTLS or SMTPS, AUTH PLAIN/LOGIN). No mail library.

## 4. Repository layout

```
wow-ui-sync/
├── pom.xml                      parent / reactor, Java 21, dependency versions
├── common/                      models, paths, hashing, codecs, packs, objects, protocol, addon rules
├── server/                      → dist/wow-ui-sync-server.jar
├── client/                      → dist/wow-ui-sync-client.jar
├── scripts/
│   ├── install.sh   install.bat
│   ├── reset-root.sh reset-root.bat
│   ├── start.sh     start.bat
│   ├── stop.sh      stop.bat
│   └── wow-ui-sync.service      optional systemd unit (Linux)
├── .github/workflows/build.yml
├── .github/workflows/release.yml
├── DESIGN.md                    this file
├── DEVLOG.md                    design history
├── README.md
└── LICENSE                      official GPLv3 text, verbatim
```

Reactor order: `common` → `server` → `client`. Package root: `io.github.azotorp.wowuisync` (`.common`, `.server`, `.client`).

---

## 5. Server

### 5.1 Data folder

Chosen as a full path during install; any folder the server's OS user can read and write. The jar finds it via `server.json` next to the jar (which holds `"dataDir"`), or `--data <path>`.

```
<data folder>/
├── server.json              settings (§5.2)
├── wowuisync.db             SQLite (§5.3)
├── db-backups/              database copies made before migrations (§5.6)
├── keys/                    one public key file per key-based account (<account-id>.pub)
├── tls/keystore.p12         server TLS private key + self-signed certificate
├── logs/server.log          rotating log (10 MB × 5)
├── tmp/                     temp objects and partial uploads (tmp/partial/<sha256>)
├── stop.flag                written by stop scripts
└── accounts/<account-id>/profiles/<profile-id>/
    ├── current.manifest     zstd JSON manifest + fingerprint + last-push metadata
    └── objects/<h0h1>/<h2h3>/<sha256>
```

Accounts are stored by a fixed random **account id** (UUID), never by username.

### 5.2 `server.json`

`dataDir`, `bindAddress` (default `0.0.0.0`), `port` (default **2026**, 1025–65535), `publicHost` (used in printed info and invite codes), `maxDataConnectionsPerSession` (16), `logLevel`, `smtp` { `enabled`, `host`, `port`, `username`, `password`, `from`, `security`: `starttls` | `ssl` }, `lockout` { `accountFailures` 5, `accountLockMinutes` 15, `ipFailuresPer10Min` 20, `ipBlockMinutes` 15 }.

### 5.3 Database schema (`wowuisync.db`, SQLite, WAL mode)

- `schema_version(version)`
- `accounts(id, username, username_lc UNIQUE, role, owner_id NULL, email NULL, auth_method 'password'|'key', password_hash NULL, password_salt NULL, password_iterations NULL, key_type NULL, created_at, max_users NULL, quota_mb NULL, group_quota_mb NULL, failed_logins, locked_until NULL)`
- `invites(id, secret_hash UNIQUE, role, owner_id, uses_left NULL /*NULL = unlimited*/, expires_at NULL, created_at)`
- `reset_codes(account_id, code_hash, expires_at, attempts_left)`
- `ip_failures(ip, window_start, count, blocked_until NULL)`
- `profile_state(account_id, profile_id, fingerprint, stored_bytes, last_push_at, last_push_device, last_push_by_account)`

### 5.4 No console; subcommands and scripts

The server runs headless and only logs. Interactive tasks are jar subcommands (so they work on every OS and can hide input via `Console.readPassword`); scripts are thin wrappers.

- `java -jar wow-ui-sync-server.jar run` — normal start.
- **`install`** (once, over SSH):
  1. Check Java 21.
  2. Ask the **data folder full path** (default `~/wow-ui-sync-data`); create it; check write access.
  3. Ask the **port** (default 2026) and **public host/IP** (offer a detected guess).
  4. Ask for root's **OpenSSH public key line**, or Enter to skip. Validate type and size and that it parses fully (not truncated); re-ask on failure. A key means root is key-only.
  5. If skipped: ask root's **password twice** (hidden, min 8), repeating on mismatch.
  6. Ask root's **email** (optional).
  7. Write `server.json` (next to the jar and in the data folder), create the database, generate the TLS keystore, create folders, lock down permissions, create the **root** account.
  8. Print: host, port, username `root`, login method, TLS certificate SHA-256 fingerprint, and the `ufw` hint. Offer to start the server.
  - Refuse if root exists (point to `reset-root`).
- **`reset-root`**: choose new public key or new password (hidden, twice); replace root's credentials; root's sessions end. Works while the server runs.
- **`start.sh`**: runs `run` inside `screen` session `wowuisync` (or in the current terminal if `screen` is missing, with a hint). Restart loop: on unexpected exit, print the exit code and count down 10 seconds, then restart. Exits the loop when `stop.flag` exists (and deletes it).
- **`start.bat`**: same loop in its own console window.
- **`stop.sh` / `stop.bat`**: write `stop.flag`; the server notices within 2 seconds, finishes or aborts active transfers cleanly, and exits with code 0.
- **`wow-ui-sync.service`**: optional systemd unit, `Restart=on-failure`, runs as an unprivileged user.

### 5.5 Permission lockdown

On every startup: Linux/macOS set the data folder, `keys/`, `tls/`, and `wowuisync.db` to `700`/`600` for the running user; Windows sets owner-only ACLs. If anything was looser, fix it and log a warning. Public keys are not secret but must be write-protected.

### 5.6 Upgrades

Stop the server, replace the jar, start it. The data folder is untouched. On startup, if the database schema is older than the jar's, copy `wowuisync.db` to `db-backups/wowuisync-v<old>-<timestamp>.db`, then run migrations in order. The client database migrates the same way. Version check at connect: §10.3.

---

## 6. Accounts, roles, permissions

| | root | superuser | user |
|---|---|---|---|
| Count | exactly one; username `root` (reserved) | any | any |
| Created by | `install` only | root (direct or invite) | root or a superuser (direct or invite) |
| Data access (read and write) | every account | itself + users it owns | itself |
| Can create | superusers, users, invites for either | users, invites for users | nothing |
| Can manage (credentials, email, quota, delete) | every account (root's own credentials via its settings or `reset-root`) | users it owns | own password/key and email |

- **Superusers are peers**: they can never see, change, or delete root, another superuser, or users they do not own. A superuser is a group owner, not a server admin.
- **Max users** per superuser: set by root; default unlimited. Counts owned users plus remaining slots on that superuser's active invites. A capped superuser cannot create unlimited-use invites.
- **Storage quotas** (MB): per-account cap, default unlimited, set by root (any account) or a superuser (its users). Optional **group cap** on a superuser covering itself and its users, set by root. Enforced before a push uploads anything (§12.6).
- **Deleting a superuser** moves its users to root's ownership. Deleting any account deletes its server data. Deletion requires typing the username in the client.
- **Usernames**: 3–32 chars, `[A-Za-z0-9_-]`, unique case-insensitively; `root` reserved.
- The server checks these rules on **every** request; the client is never trusted.

## 7. Authentication and security

- **TLS 1.3** on one port. Self-signed certificate generated by `install`. The client pins the certificate's SHA-256 fingerprint **per host and port** on first connect (shown for comparison with what `install` printed); a later mismatch blocks with a clear warning. Invite codes carry the fingerprint and pin automatically.
- Each account uses **either a public key or a password, never both.** Wrong method → `LOGIN_FAIL wrong_method` with a clear message.
- **Keys**: OpenSSH public keys `ssh-ed25519`, `ssh-rsa` (≥ 2048 bits), `ecdsa-sha2-nistp256`. Login is challenge–response: server sends a 32-byte nonce; client signs `nonce || sessionId || serverCertFingerprint` with its private key; server verifies with the stored public key. The private key never leaves the client. The client reads OpenSSH private key files (passphrase prompted if encrypted). PuTTYgen users export with **Conversions → Export OpenSSH key**. These keys never grant SSH or shell access.
- **Passwords**: PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte salt. Minimum 8 characters.
- **Sessions**: random 256-bit session id after login; data connections join with it; sessions end on disconnect, and when the account's credentials change or it is deleted.
- **Lockout**: 5 failed logins on an account lock it for 15 minutes (`LOGIN_FAIL locked`, with time remaining). Per IP: more than 20 failures (logins, invite redemptions, reset codes) in 10 minutes blocks that IP for 15 minutes. Successful login resets the account counter.
- **Email reset** (password accounts, only if SMTP is enabled): "Forgot password" sends a 6-digit code (15 minutes, single use, 5 attempts). Entering it with a new password resets the account. Responses never reveal whether a username or email exists. Key-only accounts are recovered by their manager; root by `reset-root`.
- README advice: use a separate key pair for WoW UI Sync; run the server as an unprivileged OS user (e.g. `wowuisync`); open only the sync port.

## 8. Invite codes

- Root: codes for `superuser` or `user`. Superuser: codes for `user` (counted against its cap).
- Options: uses = 1, N, or unlimited; optional expiry.
- Format: `wus1-` + base64url(JSON `{h: host, p: port, f: certFingerprint, s: secret}`). Secret: 128+ random bits. The server stores only `SHA-256(secret)`, role, owner, uses left, expiry.
- Each redemption decrements uses; at 0 or on expiry the row is deleted. Creators list and revoke their codes; root sees all.
- **Redeem** (client, "Join with invite code"): paste → client sets host/port, pins fingerprint → asks username, then a key file (client sends only the public key) or password (twice), and optional email → account created with owner = code creator → Connection saved.

---

## 9. Storage model

- One **current copy** per account per profile: `current.manifest` + content-addressed objects. No server-side history, snapshots, or undo. Undo is local (§18).
- Objects are per account (no cross-account sharing); deleting an account deletes its folder.
- **Object file**: header (magic `WUSO`, version, codec `raw`|`zstd`|`deflate`, original size) + body. Path `objects/<sha[0..2]>/<sha[2..4]>/<sha256>`.
- A push writes objects to `tmp/`, verifies SHA-256, renames into place; the new manifest is written to a temp file and atomically replaces `current.manifest` only after every referenced object exists.
- After each successful push, **GC** deletes objects of that account and profile not referenced by the new manifest. Never during an active transfer on that account.
- `profile_state.stored_bytes` is updated after each push (sum of object file sizes).

## 10. Protocol

### 10.1 Framing and connections

- Frame: `[u8 type][u32 requestId][u32 payloadLength][payload]`. Control payloads are compressed JSON (negotiated codec); data payloads are binary.
- One **control connection** per session; up to N **data connections** (client default 6, range 1–16) that send `DATA_JOIN(sessionId)`. Data connections pull jobs from a shared queue.

### 10.2 Messages

- Session: `HELLO` / `HELLO_OK` (protocol version, app version, supported codecs, device id, device nickname), `DATA_JOIN`, `PING`, `ERROR(code, message)`.
- Login: `LOGIN_PASSWORD`, `LOGIN_KEY_BEGIN`, `LOGIN_KEY_CHALLENGE`, `LOGIN_KEY_PROOF`, `LOGIN_OK` (account id, role, accessible accounts), `LOGIN_FAIL(reason: wrong_method | bad_credentials | locked | ip_blocked | version)`.
- Status: `STATUS_REQ(account, profile, localFingerprint, baseFingerprint)` / `STATUS_RESP(currentFingerprint, last push time, device, by account, stored bytes, quota)`.
- Manifests: `MANIFEST_GET(account, profile)` / `MANIFEST(…)`.
- Push: `PUSH_BEGIN(account, profile, full manifest or delta vs base)` / `PUSH_NEED(hashes missing, partial offsets)`, `PACK_UPLOAD`, `FILE_UPLOAD_CHUNK`, `RESUME_QUERY` / `RESUME_OFFSET`, `PUSH_COMMIT` / `PUSH_DONE(new fingerprint)`.
- Pull: `PULL_BEGIN(source account, profile, selection, mode, local manifest)` / `PULL_PLAN(new, edited, removed, total bytes)`, `FETCH(list of hashes)`, `PACK_DOWNLOAD`, `FILE_DOWNLOAD_CHUNK(hash, offset)`.
- Accounts: `ACCOUNT_LIST`, `ACCOUNT_CREATE`, `ACCOUNT_UPDATE` (email, max users, quotas), `ACCOUNT_SET_CREDENTIAL`, `ACCOUNT_DELETE`.
- Invites: `INVITE_CREATE`, `INVITE_LIST`, `INVITE_REVOKE`, `INVITE_REDEEM`.
- Reset: `RESET_REQUEST`, `RESET_CONFIRM`.
- Every account-scoped message carries the target account id; the server checks §6.

### 10.3 Versioning

`HELLO` carries an integer protocol version and app version. If incompatible, the server replies `LOGIN_FAIL version` with the minimum client version, and the client shows "Please update WoW UI Sync to version X or newer" (or the reverse for an outdated server).

## 11. Sync engine

### 11.1 Manifest and hashing

- Paths relative to the flavor folder, forward slashes, original casing stored, compared case-insensitively (§14).
- Client **hash cache** per flavor folder: `path → (size, lastModified, sha256)`; re-hash only when size or mtime changes. First scan uses one thread per core (max 8) with "Scanning files: N of M".
- Timestamps never decide which side is newer between machines.

### 11.2 Fingerprint status check

The client computes its local fingerprint and sends it with its base; the server replies with the current fingerprint and last-push metadata. Full manifests move only on push, pull, or when the user opens details.

### 11.3 Status per (account, profile) on this device

| Local ≠ base? | Server current ≠ base? | Shown |
|---|---|---|
| no | no | **Up to date** |
| yes | no | **Local changes — push recommended** |
| no | yes | **Server is newer (from Laptop, Nov 7, 9:42 PM) — pull recommended** |
| yes | yes | **Conflict** (red), with "Show details" listing what changed on each side |
| base unknown | — | **Different from the server** (no recommendation), shown when no base exists for this account here |

Base is set after a successful **push**, and after a **pull of this same account with everything selected in Replace mode**. Any other pull (another account, partial selection, Keep mode) or a restore clears the base for every account on that flavor folder.

### 11.4 Push (mirror)

1. WoW check (§16; push allowed with a warning if running).
2. If server current ≠ base: warn "The server has changes from <device> at <time>. Pushing will replace them. Continue?"
3. Client sends a delta vs base if base == current, else the full manifest. Server diffs against current → plan (new, edited, removed) + missing hashes + quota check.
4. **Review dialog** (§17.3). If the target is not the logged-in account, the headline names it and Yes stays disabled until its username is typed.
5. Upload missing content (§11.5), commit, update base.

The server copy becomes an exact mirror: new and edited files uploaded, files missing locally removed.

### 11.5 Transfer

- **Two pack kinds** (transport only; receivers store each file as its own object):
  - **Compressed pack**: index + concatenated bytes in one zstd stream (level 3), ~8 MB uncompressed. For text and compressible binary.
  - **Raw pack**: index + bytes, uncompressed, ~16 MB. For already-compressed types.
  - Never mixed; header records the kind. Entries sorted by extension, then path.
- **Large files** (≥ 4 MB) stream individually in 1 MB chunks.
- **Compression by type**:

| Category | Extensions | Transit | At rest |
|---|---|---|---|
| Already compressed | `.ogg .mp3 .m4a .aac .flac .opus .jpg .jpeg .png .zip .gz .7z .rar` | raw | raw |
| Text | `.lua .toc .xml .txt .md .html .css .json .wtf .textile .pkgmeta .md5 .log .bak .old`, no extension | compressed | zstd |
| Compressible binary | `.tga .ttf .otf .wav .m2` | compressed | zstd |
| Unsure | `.blp` and unknown | sample test | sample test |

  **Sample test**: zstd level 1 on the first 256 KB; under 5% saving → treat as already compressed. Cached per hash.
- **Scheduling**: large files first, then raw and compressed packs interleaved.
- **Integrity**: receiver verifies SHA-256 per file; one retry; then the operation fails with nothing committed.
- **Resume**: completed objects are never re-sent (content-addressed). Partial large files continue from the server's offset (`RESUME_QUERY`/`RESUME_OFFSET`); partials older than 7 days are deleted. Client keeps partial downloads in `wow-ui-sync-data/partial/`. Data connections reconnect with backoff 1, 2, 5, 10, 30 s; after 5 failures the operation pauses with **Resume**.
- **Progress**: files and bytes done/total, speed, ETA, original vs transferred size. First push note: "First sync uploads everything. Later syncs only send changes."
- Codec negotiation: zstd preferred; deflate fallback if either side lacks zstd.

## 12. Selective pull

### 12.1 Source and selection

- Source: any account the logged-in account can access (§6), for the chosen profile.
- The client fetches the source manifest, then downloads the source's `.toc` files (small) to group addons (§15).
- **Selection window**: scrollable list of addon groups, each with **Addon** and **Include data** checkboxes, plus a **General settings** row. Buttons: **All addons**, **All data**, **None**. Shows the download size for the current selection.
- **Mode**: **Replace** (default) or **Keep my other addons** (checkbox).
- The selection is sent as lists of top-level `Interface/AddOns` folder names (addons and data) plus the general flag and mode; the server builds the plan with the shared rules in `common` (§15.3).

### 12.2 Plan rules

- **Replace**: local `Interface/AddOns` ends up with exactly the selected addon folders (mirrored from source); other local addon folders are removed. For addons with data ticked, their SavedVariables (§15.3) are mirrored from source, including removals. Addons without data ticked keep their local data. General settings are mirrored only if ticked.
- **Keep my other addons**: only the selected folders and ticked data are mirrored; nothing outside the selection is removed.

### 12.3 Flow

WoW must be closed (§16) → selection → plan → **review dialog** → free-space check → **local backup** (§18) → download only differing files → write via temp + atomic rename → apply removals → set the applied account → update or clear base (§11.3) → show "If you use CurseForge, WowUp, or a similar addon manager, run a rescan or refresh so it picks up the synced addons." when addon folders changed.

### 12.6 Quotas on push

Before uploading, the server computes the projected stored size of the account (all profiles) after the push and compares it with the account cap and, if the owner is a capped superuser group, the group cap. Over the limit → refuse with "This push needs X MB; your limit is Y MB (Z MB used)."

## 13. Profiles (game flavors)

- One account holds a separate current copy per profile.
- **Detection**: a subfolder of the install root is a flavor folder if it has `.flavor.info` or `Wow*.exe` (Windows) / `World of Warcraft*.app` (macOS), plus `WTF` or `Interface`. `_*_` folders without markers are listed as "not a WoW install?".
- Profile id = folder name with underscores trimmed (`_forever_` → `forever`); fixed once used.
- **v1 enables `forever` only.** Other detected flavors are listed but disabled ("Coming later"). The server accepts profile ids from an allowlist in `common` (v1: `forever`). Enabling another flavor later is adding it to the allowlist.
- Install root: no default on Windows/Linux (user picks; dev machine `C:\Games\World of Warcraft`); macOS suggests `/Applications/World of Warcraft`.

## 14. Paths, exclusions, cross-platform rules

- Synced roots per profile: `Interface/AddOns/` (folders only) and `WTF/`.
- **Loose files directly in `Interface/AddOns/`** (e.g. `.zip` manual backups) are ignored everywhere.
- Default exclusions (editable per profile): `WTF/Config.wtf`, `WTF/gamecontrollerdb.txt`, `WTF/GamePadConfig_*.json`, `WTF/**/*.bak`, `WTF/**/*.old`, `Interface/AddOns/**/.git/**`, `**/Thumbs.db`, `**/desktop.ini`, `**/.DS_Store`.
- Paths compared case-insensitively on every OS; pushing side's casing stored. On case-sensitive file systems, a pulled file differing only in case renames the existing file. Two local paths differing only by case → push fails before transfer, listing them.
- Paths with Windows-invalid characters (`< > : " | ? *`, trailing dot or space) are skipped at push with a warning.
- Server rejects absolute paths, `..`, and anything escaping the profile root.
- File contents are bytes: never convert line endings or encodings.

## 15. Addon grouping and data mapping (`common`, used by client and server)

### 15.1 Grouping

Read each `Interface/AddOns/<Folder>/<Folder>.toc` (fall back to any `.toc` in the folder). Folder B joins addon A if:

- B's name starts with A's name followed by `_` or `-`, and B's `.toc` lists A in `## Dependencies`, `## RequiredDeps`, or `## OptionalDeps`; or
- A and B share `## X-Curse-Project-ID`, `## X-Wago-ID`, or `## X-WoWI-ID`, and one name is a prefix of the other.

Display name: `## Title` with color codes stripped, else the folder name. Unmatched folders are their own addon.

### 15.2 Data

SavedVariables files are named after the addon folder:
`WTF/Account/<acct>/SavedVariables/<Folder>.lua` (+ `.lua.bak`) and `WTF/Account/<acct>/<realm>/<char>/SavedVariables/<Folder>.lua` (+ `.lua.bak`) belong to the group containing `<Folder>`.

### 15.3 General settings

Everything else under `WTF/`: macros, bindings, chat and layout caches, per-character `AddOns.txt`, `Blizzard_*` SavedVariables.

## 16. WoW-running detection

- Per profile: a process whose executable path (`ProcessHandle.Info.command()`) is inside the flavor folder (case-insensitive, normalized) means that flavor's WoW is running. Forever runs as `<install root>\_forever_\Wow.exe`.
- Fallback (path unavailable, e.g. Wine): a process command line containing a WoW executable name (`Wow.exe`, `WowClassic.exe`, `WowClassicT.exe`, `WowT.exe`, `WowB.exe`, `World of Warcraft`, `World of Warcraft Classic`) together with the flavor folder name.
- If undeterminable: warning "Couldn't check whether WoW is running", not a block.
- **While a flavor's WoW runs:** every operation that changes that flavor folder (pull, backup restore, addon delete) is refused with "Close World of Warcraft (<flavor>) first." Status works; push works with a warning.

## 17. Client

### 17.1 Client data

Folder next to the jar: `wow-ui-sync-data/`

- `client.json`: device id (UUID), device nickname, install root, profiles and exclusions, data connections (6), UI prefs.
- `client.db` (SQLite): Connections, pinned fingerprints (host:port), hash caches per flavor, bases per (connection host, account, profile), applied account per flavor, local backup records.
- `partial/`: resumable downloads.
- `logs/client.log`.

### 17.2 Connections

- First launch with no Connection opens **Connect**. The client always assumes the server and root exist; there is no server setup in the client.
- Connection fields: name, host, port (2026), username, and either password (saved in plain text by the user's choice, or prompted) or **private key file path** (passphrase prompted when needed). Manager list like WinSCP's site manager: New, Edit, Delete, Connect.
- Also: **Join with invite code** (§8) and **Forgot password** (§7) on the Connect screen.
- Choosing a Connection or an account **never changes files**. Files change only after Push, Pull, Restore, or Delete and their confirmation.

### 17.3 Main window

- Top: Connection selector (connected account shown), **target account** selector (accounts this login can access), **profile** selector (Forever).
- Status badge and line (§11.3) with last push device and time.
- Buttons: **Push**, **Pull…** (opens selection), **Backups**, **Addons** (local manager), **Accounts** (root and superusers), **Settings**.
- Progress panel during transfers (§11.5), with Pause/Resume and Cancel.
- **Review dialog** (push and pull): three plain scrollable text lists in this order: **New files (N)**, **Edited files (N)**, **Removed files (N)**. No diffs. **Yes** / **Cancel**. Typed-username confirmation when pushing to another account.
- **Settings**: device nickname, install root and profile detection, exclusions, data connections, theme.
- **About**: version, GPLv3 notice, Blizzard disclaimer.

### 17.4 Local manager (no server needed)

- **Addons** view: grouped addons (§15) with sizes; **Delete addon** (its folders) and/or **Delete data** (its SavedVariables across all characters). WoW must be closed; a local backup (§18) is made first.
- **Backups** view: §18.

### 17.5 Accounts panel (root and superusers)

- List: username, role, owner, email, login method, quota and usage, last push.
- **New account**: username, role (root: superuser or user; superuser: user), public key line (paste) or password (twice), email, quota MB, max users and group quota (root, for superusers).
- Actions: set new password/key, edit email, edit quota/caps, delete (type username).
- **Invites** tab: create (role, uses, expiry) → shows the `wus1-…` code with Copy; list with revoke.
- Every account can edit its own email and password/key in **My account**.

## 18. Local backups

- Made before every pull, restore, addon delete, and on **Manual backup**: a full copy of the live `Interface` and `WTF` into the same flavor folder:
  `Interface_<account>_<YYYY-MM-DD_HH-MM-SS>` and `WTF_<account>_<YYYY-MM-DD_HH-MM-SS>` (e.g. `WTF_Girlfriend_2026-11-07_21-30-53`). `<account>` = applied account, or `local` if unknown. WoW ignores these folders.
- Live folders are updated in place afterwards, never renamed away, so only differences download.
- Free-space check first; refuse with a clear message if the copy will not fit.
- Recorded in `client.db`: flavor, account, timestamp, reason (`before pull from <account>`, `before restore`, `before addon delete`, `manual`), source account, file count, size. Without the database, backups are still listed by parsing folder names (reason "unknown").
- **Retention: unlimited**; deleted only by the user.
- **Backups view** per flavor: one entry per pair, newest first, e.g. "Girlfriend · Nov 7, 2026, 9:30:53 PM · Before pull from Azotorp"; **Manual backup**, **Restore**, **Delete** (confirm).
- **Restore**: WoW must be closed → new backup of current (`before restore`) → make live folders match the chosen backup (copy only differing files, remove extras) → set applied account to the backup's account → clear bases (§11.3). The chosen backup stays.

## 19. Build, CI, releases

- `mvn -B clean package` at the root builds `common`, `server`, `client` in order; Maven Shade produces runnable jars; a final step copies them to `dist/wow-ui-sync-server.jar` and `dist/wow-ui-sync-client.jar`. Shading must keep native resources of `zstd-jni` and `sqlite-jdbc`.
- `build.yml`: on push and pull request, `ubuntu-latest`, Java 21 (Temurin), build, upload `dist/` as artifact `wow-ui-sync-jars`.
- `release.yml`: on tags `v*`, build and attach `wow-ui-sync-client-<version>.zip` (client jar, README.txt, LICENSE) and `wow-ui-sync-server-<version>.zip` (server jar, `scripts/`, README.txt, LICENSE) to a GitHub Release.

## 20. Defaults summary

Port 2026 · bind 0.0.0.0 · client host default `localhost` · data connections 6 · large-file threshold 4 MB · compressed pack 8 MB · raw pack 16 MB · chunk 1 MB · zstd level 3 · lockout 5 failures / 15 min · IP block 20 failures per 10 min / 15 min · reset code 15 min · quotas and max users unlimited · local backups unlimited · v1 profile `forever`.

## 21. README contents (Phase 11)

Requirements (Java 21), server install walkthrough (`install.sh`, firewall `ufw allow 2026/tcp`, router port forwarding for remote access, LAN and same-machine use), running and stopping, upgrading, `reset-root`, SMTP setup examples, PuTTYgen OpenSSH export, separate key pair and unprivileged user advice, client walkthrough (Connect, invite codes, push, selective pull, backups), addon manager rescan note, disk space notes, license and disclaimer.

## 22. Legal

GPLv3. Disclaimer in README and About. No Blizzard logos, artwork, or fonts. Donations may be added later (e.g. GitHub Sponsors); no paid features in this repo.

## 23. Deferred (do not build in v1)

Delta sync of large changed files (FastCDC chunking), enabling Anniversary/Era, `.ppk` import, Windows .exe packaging and code signing, tray mode / auto-push on WoW exit, any sharing system other than logging in to an account, server-side history.

---

## 24. Build plan

Each phase: compile-clean, `dist/` produced, checklist ticked, status table updated, one commit.

### Phase 1 — Project skeleton and CI
- [x] Parent `pom.xml` (Java 21, dependency management, Shade config preserving native libs, copy-to-`dist/` step), modules `common`, `server`, `client` with `Main` stubs.
- [x] `.gitignore`, `LICENSE` (official GPLv3 text verbatim), `README.md` stub with disclaimer.
- [x] `.github/workflows/build.yml`.
- Done when: `mvn -B clean package` produces both runnable stub jars in `dist/`.

### Phase 2 — Common core: models, paths, hashing, codecs
- [x] Path normalization, case-insensitive keys, invalid-name checks, case-collision detection, exclusion globs, loose-file rule (§14).
- [x] Manifest model, JSON (Jackson) with zstd wrapper, fingerprint (§11).
- [x] Parallel hashing utilities and hash-cache model.
- [x] Codec abstraction: zstd, deflate fallback, raw; negotiation helper.
- [x] Compression category table and cached sample test (§11.5).
- [x] Profile allowlist (`forever`) and profile id rules (§13).

### Phase 3 — Common core: packs, objects, protocol
- [x] Pack writer/reader (compressed and raw kinds, sorted index).
- [x] Object file format (header, codecs) and shard path helper (§9).
- [x] Frame codec and every message type in §10.2 as typed records; version constants (§10.3).
- [x] Addon grouping, data mapping, general-settings rules, and pull-plan rules (§12.2, §15) as pure functions.

### Phase 4 — Server foundation
- [x] `server.json` model; data folder resolution; logging to `logs/server.log`.
- [x] SQLite schema, `schema_version`, migration runner with pre-migration DB backup (§5.3, §5.6).
- [x] Permission lockdown (§5.5).
- [x] TLS keystore generation and fingerprint.
- [x] `install` and `reset-root` subcommands (§5.4), key validation (BouncyCastle), PBKDF2.
- [x] Scripts: `install`, `reset-root`, `start`, `stop` (`.sh` and `.bat`), stop-flag watcher, systemd unit.

### Phase 5 — Server networking, login, accounts, invites
- [x] TLS listener, control and data connections, sessions, `HELLO` with version check, `DATA_JOIN`, `PING`.
- [x] Password and key login; lockout and IP blocking (§7).
- [x] Permission checks (§6); account messages incl. caps and quotas; superuser deletion reassigns users.
- [x] Invites (§8); SMTP sender and reset codes (§7).

### Phase 6 — Server storage, push, pull, quotas
- [ ] Object store per account/profile; `current.manifest`; atomic commit; GC; `profile_state` (§9).
- [ ] `STATUS`, `MANIFEST_GET`.
- [ ] Push: delta/full manifest, plan, missing hashes, quota check (§12.6), pack and chunk receive, resume, commit.
- [ ] Pull: plan from selection and mode, `FETCH`, pack and chunk send, range resume.

### Phase 7 — Client foundation
- [ ] `wow-ui-sync-data/` with `client.json`, `client.db` (schema + migrations), client log.
- [ ] FlatLaf main window shell (§17.3), Settings, About.
- [ ] Connections manager and Connect screen; fingerprint pinning; password and key-file login with passphrase prompt (§17.2).
- [ ] Install root picker and flavor detection (Forever enabled only); WoW-running detection (§16).

### Phase 8 — Client scanning, addon grouping, local manager
- [ ] Scanner with hash cache and progress; local fingerprint.
- [ ] Addons view using `common` grouping; delete addon / delete data with backup.
- [ ] Local backups (§18): copy with free-space check, naming, DB records, folder-name parsing fallback, Backups view, manual backup, restore, delete.

### Phase 9 — Client sync
- [ ] Status badge and line (§11.3), base handling.
- [ ] Transfer engine client side: data connections, scheduler, pack building, chunking, resume, backoff, Pause/Resume, progress and ETA (§11.5).
- [ ] Push flow with warnings, review dialog, typed confirmation (§11.4).
- [ ] Selective pull: source manifest, `.toc` fetch, selection window, modes, plan, review, backup, download, apply, rescan note (§12).

### Phase 10 — Client admin
- [ ] Accounts panel: list, create, credentials, email, quotas, caps, delete (§17.5).
- [ ] Invites: create, copy, list, revoke; Join with invite code flow (§8).
- [ ] My account; Forgot password flow (§7).

### Phase 11 — Release packaging and docs
- [ ] `release.yml` and zip contents (§19).
- [ ] Full README (§21).
- [ ] Final consistency pass: defaults (§20), messages, disclaimers.

### Phase 12 — Test pass (owner)
Manual checklist: install on the Ubuntu server; connect as root by key; push Forever (~3.2 GB) and interrupt/resume; status on a second PC; selective pull without the voice pack; backups list, restore, and undo restore; create superuser and user, invite redeem, quotas, lockout, password reset email; server upgrade with migration; WoW-running blocks.
