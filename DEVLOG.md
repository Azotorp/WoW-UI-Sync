# WoW UI Sync — Dev Log

WoW UI Sync backs up and syncs your entire World of Warcraft UI setup (addons, account-wide settings, and per-character SavedVariables) between PCs, through a server you host yourself.

Open source under GPLv3. Not affiliated with or endorsed by Blizzard Entertainment.

---

## Rules for working from this log (read first)

This file is both the design record and the work queue. It is written so an AI agent (Claude Code) or a human can pick up exactly where the last session stopped without redoing or undoing work.

### Design log vs build plan

- The entries below are **design notes**, not build steps. Entry numbers are not phases.
- When the design is declared complete, all entries are consolidated into one clean **`DESIGN.md`**: a single current spec with no superseded material, plus a build plan split into phases sized for limited usage sessions.
- Each phase must end with code that compiles and is committed, so the next session picks up cleanly. Full testing happens only after the final phase.
- Once `DESIGN.md` exists, it is the spec to build from. This log stays as history, and later design changes are still added here as new entries, then folded into `DESIGN.md`.

### Reading order

1. Read the **Status board** below. It says what is already built and what is next.
2. Read every entry marked `Planned` or `In progress`, in order.
3. Entries marked `Implemented` are context only. Their content already exists in the code. Read them for background, but do not re-implement them.

### Resolve before you code

- Before writing any code, merge all `Planned` and `In progress` entries into one **net spec**. Where a later entry overrides an earlier one, only the later version is built.
- Never implement a section that a later entry supersedes, then change it afterwards. Superseded sections carry an inline marker: `> ⚠️ Superseded by 00X §Y`.
- If two entries conflict and neither says which wins, the later entry wins. If that still seems wrong, stop and ask instead of guessing.

### When code already exists

- Treat new entries as **patches**: change only what the entry describes. Each entry from 002 onward has a **Changes** checklist listing the concrete work.
- Check the code before trusting the Status board. If they disagree, the code is the truth; fix the board.

### Updating this log

- Entries are append-only. Do not rewrite earlier entries.
- The only edits allowed to earlier entries are: adding `> ⚠️ Superseded by …` markers, ticking `Changes` checkboxes, and updating an entry's `Status` line.
- After implementing work, in the same commit: tick the boxes, set the entry status (`Implemented in <short commit sha>` once complete), and update the Status board.
- New design decisions go in a new entry, never into the code alone.

### Entry template

```
## Entry NNN — YYYY-MM-DD — Title
Status: Planned | In progress | Implemented in <sha>
Supersedes: 00X §Y, … (or "nothing")

### Summary
### Decisions
### Changes
- [ ] concrete task
### Open questions
```

---

## Status board

| Entry | Title | Status |
|---|---|---|
| 001 | Initial design draft | Consolidated into DESIGN.md |
| 002 | Cross-platform support, home and local servers, AI working rules | Consolidated into DESIGN.md |
| 003 | Path-based WoW detection, large audio-heavy profiles, resumable transfers | Consolidated into DESIGN.md |
| 004 | Measured file profile, pack types, per-type compression, object sharding, flavor detection | Consolidated into DESIGN.md |
| 005 | Accounts and roles, install-time root, connections, local manager, mirror push, selective pull, local backups | Consolidated into DESIGN.md |
| 006 | Open questions resolved, consolidation decisions | Consolidated into DESIGN.md |

**Code state:** `DESIGN.md` Phases 1–11 are built (Maven reactor `common` → `server` → `client`, jars in `dist/`). See the Phase status table in `DESIGN.md`.

**Next step:** `DESIGN.md` Phase 12, the manual test pass by the owner. Track progress in the Phase status table in `DESIGN.md`, not here. The `Changes` checklists in entries 002–005 are historical; `DESIGN.md` §24 replaces them.

---

## Entry 001 — 2026-11-07 — Initial design draft
Status: Consolidated into DESIGN.md
Supersedes: nothing

### 1. Goal

Clone a player's WoW UI setup to any other PC, and keep a revision history so mistakes can be undone.

- **Push**: upload local changes to the server.
- **Pull**: download server changes to this PC.
- Only files that actually differ are transferred.
- Every sync creates a labeled, timestamped snapshot that can be restored.

Typical use: play on the desktop, push. Later on the laptop, pull. The app makes it obvious which direction is needed and protects against pushing or pulling the wrong way.

> ⚠️ "Every sync creates a snapshot" superseded by 005 §2 and §14: the server keeps one current copy per account; undo comes from local backups.

### 2. Decisions made in this entry

> ⚠️ Superseded in part by 002 §1 (client and server are cross-platform: Windows, Linux, macOS).

| Topic | Decision |
|---|---|
| Hosting | Self-hosted Java server on the user's own Linux box (developed against Ubuntu 24.04). No cPanel, FTP, PHP, or website. |
| Client | Java desktop app, runnable jar, double-click to open. Windows is the primary target. |
| Server | Separate Java jar, headless, run under `screen` with an auto-restart script. |
| Repo | One public repo, one Maven multi-module build producing both jars. |
| License | GPLv3. |
| Java version | Java 21 LTS for both (Ubuntu 24.04 ships `openjdk-21`; virtual threads help the server). Supersedes the earlier "Java 17" note from chat. |
| Packaging | Jars for now. A Windows .exe is a later decision. |

Superseded ideas from early chat, recorded so they are not re-proposed: FTP/FTPS sync to cPanel, PHP API on cPanel, a public website. A hosted "Account mode" is deferred (see §17).

### 3. Terminology

- **Install root**: the WoW folder, e.g. `C:\Games\World of Warcraft`.
- **Flavor folder**: a game version folder inside the install root, e.g. `_forever_`, `_anniversary_`, `_classic_era_`.
- **Profile**: one flavor folder configured for sync. Each profile syncs independently.
- **Device**: one client install, with a user-chosen nickname (e.g. "Desktop", "Laptop"). Default nickname is "My PC".
- **Manifest**: a list of every synced file with its relative path, size, and SHA-256 hash.
- **Snapshot**: a stored manifest representing the state of a profile at one point in time, plus metadata.
- **Head**: the server's current snapshot for a profile.
- **Base**: the snapshot this device last synced with, per profile.

> ⚠️ New terms added by 005 §1 (account, role, connection, local backup).

### 4. What gets synced

Per profile, relative to the flavor folder:

- `Interface\AddOns\` (the addon files themselves)
- `WTF\` (account settings, per-character SavedVariables, macros, keybinds, layouts, etc.)

> ⚠️ Loose files directly inside `Interface\AddOns\` are ignored (005 §12).

**Reference install (dev machine):** `C:\Games\World of Warcraft` with flavor folders `_forever_`, `_anniversary_`, `_classic_era_`. First target profile is `_forever_`:

- `C:\Games\World of Warcraft\_forever_\Interface\AddOns`
- `C:\Games\World of Warcraft\_forever_\WTF`

> ⚠️ Profile detection extended by 004 §7 (`.flavor.info` / `Wow*.exe` markers).

**Profile detection:** when the user picks the install root, the client lists subfolders matching `_*_` that contain a `WTF` or `Interface` folder and offers them as profiles. Users can also add a profile pointing at any folder manually.

**Default exclusions** (editable per profile, glob patterns relative to the flavor folder):

- `WTF/Config.wtf`: graphics, resolution, and per-machine settings.
- `WTF/gamecontrollerdb.txt`, `WTF/GamePadConfig_*.json`: per-machine controller config.
- `WTF/**/*.bak`, `WTF/**/*.old`: WoW's own backup copies.
- `Cache/`, `Logs/`, `Errors/`, `Screenshots/`: never synced (outside the two roots anyway; listed for safety).
- `Interface/AddOns/**/.git/**`, `**/Thumbs.db`, `**/desktop.ini`.

> ⚠️ Superseded by 002 §4 (path casing rules apply on every OS; `.DS_Store` added to exclusions).

Paths are always stored with forward slashes and compared case-insensitively on Windows. The original casing is preserved when writing.

### 5. Architecture

Maven multi-module project:

```
wow-ui-sync/
├── pom.xml                 (parent / reactor)
├── common/                 shared: protocol, manifest, hashing, compression, models
├── server/                 headless server  → wow-ui-sync-server.jar
├── client/                 Swing desktop app → wow-ui-sync-client.jar
├── scripts/
│   ├── start.sh            runs server in screen with auto-restart + countdown
│   ├── stop.sh
│   └── wow-ui-sync.service optional systemd unit
├── .github/workflows/
│   ├── build.yml
│   └── release.yml
├── DEVLOG.md
├── README.md
└── LICENSE                 GPLv3
```

> ⚠️ `scripts/` extended by 002 §3 (adds `start.bat` and `stop.bat`) and 005 §6 (adds `install` and `reset-root` scripts). Libraries extended by 005 §17.

**Division of work:** the client hashes its local files (only it can see its disk) and sends a compact manifest. The server owns everything else: comparing manifests, deciding what to request or send, storing files, building snapshots, retention, and restore.

**Libraries** (all GPLv3-compatible):

- `zstd-jni` (BSD) for compression. Bundles native libs for Windows and Linux.
- `Jackson` (Apache 2) for JSON config and manifests.
- `FlatLaf` (Apache 2) for a modern dark/light Swing look.
- Built-in JDK for TLS (`SSLSocket`), SHA-256, threading.

### 6. Network protocol

> ⚠️ Default port is **2026** (005 §1), replacing 47811 everywhere. Authentication (tokens, `AUTH`) superseded by 005 §4 (accounts with password or public key).

Custom binary protocol over TLS, one TCP port (default `47811`, configurable).

**Framing:** every message is
`[u8 type][u32 requestId][u32 payloadLength][payload]`.
Control payloads are zstd-compressed JSON. Data payloads are raw binary.

**Connections:** one control connection per session, plus N data connections (default 6, configurable 1–16) that join the session with a session token. Data connections pull jobs from a shared queue so large and small transfers interleave instead of blocking each other.

**Authentication:** the server config lists users, each with a long random token. The client stores the server host, port, and token. Every session starts with `HELLO` (protocol version, device id, device nickname) then `AUTH`.

**TLS without a domain:** the server generates a self-signed certificate on first run and prints its SHA-256 fingerprint to the console. The client uses trust-on-first-use: it shows the fingerprint on first connect, saves it once accepted, and refuses to connect (with a clear warning) if it ever changes.

**Core message types (initial):** `HELLO`, `AUTH`, `STATUS_REQ/RESP`, `PUSH_BEGIN`, `PUSH_NEED` (server tells client which hashes it lacks), `PACK_UPLOAD`, `FILE_UPLOAD_CHUNK`, `PUSH_COMMIT`, `PULL_BEGIN`, `PULL_PLAN`, `PACK_DOWNLOAD`, `FILE_DOWNLOAD_CHUNK`, `HISTORY_REQ/RESP`, `RESTORE`, `ERROR`, `PING`.

> ⚠️ Message types extended by 003 §3 (resume support). Status flow refined by 004 §6 (manifest fingerprint). `HISTORY_REQ/RESP` and server-side `RESTORE` dropped by 005 §2; account and invite messages added by 005 §16.

### 7. Hashing and the local hash cache

- SHA-256 per file.
- The client keeps a hash cache per profile: `path → (size, lastModified, sha256)`. A file is only re-hashed when its size or modified time changes, so a status check on thousands of unchanged files takes well under a second.
- Hashing runs on multiple threads.
- Timestamps are never used to decide which side is newer between machines. They only serve as a cache hint on the local machine.

### 8. Sync algorithm

> ⚠️ Push and pull behavior extended by 005 §13–§15 (mirror push, review dialog, selective pull, backups inside the flavor folder). The three-way status below still applies, per account and profile.

The client stores, per profile: the **base** snapshot id and the base manifest.

**Status check** (runs on launch and on demand, no file transfer):

1. Client builds the current local manifest (using the hash cache).
2. Client asks the server for the profile's head snapshot id and metadata.
3. Three-way comparison of local vs base vs head:

| Local changed since base? | Server head ≠ base? | Status shown |
|---|---|---|
| No | No | **Up to date** |
| Yes | No | **Local changes — push recommended** |
| No | Yes | **Server is newer (from Laptop, Nov 7 9:42 PM) — pull recommended** |
| Yes | Yes | **Conflict** (red). Shows which files changed on each side. No automatic resolution. |

**Push:**

1. Client sends its manifest.
2. Server diffs against head and replies with the list of file contents (by hash) it does not already have.
3. Client uploads only those (§9).
4. On `PUSH_COMMIT`, the server writes a new snapshot = the client's manifest, and it becomes head.
5. Files deleted locally are absent from the new manifest, so they are deleted from head. The previous snapshot still has them, so this is undoable.

**Pull:**

1. Client sends its manifest.
2. Server replies with a plan: files to download, files to delete locally.
3. **Before changing anything locally**, the client copies every file that will be overwritten or deleted into a local backup (§11).
4. Client downloads the needed content, writes each file to a temp name, then atomically renames it into place.
5. Client applies deletions, updates base to the pulled snapshot.

**Safety checks:**

- **Pull with local changes**: if local differs from base, warn before pulling ("This PC has changes that are not on the server. Pulling will overwrite them. A local backup will be made. Continue?").
- **Push over newer server**: if head ≠ base, warn before pushing ("The server has newer changes from Laptop. Pushing will replace them. They stay in history. Continue?").
- **WoW is running**: WoW rewrites SavedVariables on logout and reload. Block pull and warn on push if a WoW process is running. Process names are a configurable list (defaults: `Wow.exe`, `WowClassic.exe`, `WowClassicT.exe`, `WowT.exe`, `WowB.exe`). Detected with `ProcessHandle.allProcesses()`.

> ⚠️ WoW-running detection superseded by 003 §1 (path-based, per profile). 002 §5 name matching becomes the fallback. 005 §15 blocks every operation that changes a flavor folder while that flavor's WoW runs.

### 9. Transfer: packs, compression, parallelism

> ⚠️ Pack and compression rules superseded by 004 §2–§3 (separate raw and compressed packs, per-type compression table). Large-file path, parallelism, integrity, and progress below still apply.

**Packs for small files.** Files under the large-file threshold (default 4 MB) are grouped into packs of roughly 8 MB uncompressed. Each pack is one zstd stream containing a small index plus the concatenated file bytes. Compressing many Lua files together gives a much better ratio than compressing each alone, because they share vocabulary. Packs are only a transport format: the server unpacks them and stores each file individually.

**Large files** are streamed individually in 1 MB chunks, so several large files can transfer in parallel across data connections.

**Compression:** zstd, default level 3 (fast, good ratio for text). Files whose extension is already compressed (`.ogg`, `.mp3`, `.png`, `.jpg`, `.zip`, `.blp` is uncertain, decide after measuring) are stored, not recompressed. If compression saves less than about 5%, send raw.

**Parallelism:** a job queue feeds N data connections. Big files are scheduled first so the tail of the transfer is small packs, which keeps all connections busy until the end.

**Integrity:** the receiver verifies SHA-256 of every file before committing it. A mismatch retries that file once, then fails the sync without committing a snapshot.

**Progress:** the client shows files and bytes done/total, current speed, and compressed vs original size.

> ⚠️ Extended by 003 §2–§3 (audio handling, resumable transfers, ETA).

### 10. Server storage

> ⚠️ Object path layout superseded by 004 §5 (two-level sharding: `objects/ab/cd/<sha256>`).
> ⚠️ Folder layout, snapshots, and retention superseded by 005 §2–§3 (configurable data folder, per-account storage, one current copy, no server history).

Content-addressed store plus snapshot manifests:

```
data/
├── server.json                     config (port, users, retention, threads)
├── tls/                            generated keystore
└── users/<user>/
    ├── objects/ab/cdef1234…        file contents named by SHA-256, zstd-compressed at rest
    └── profiles/<profile>/
        ├── head                    id of current snapshot
        └── snapshots/
            └── 20261107-094200-desktop-push.json
```

Why content-addressed:

- A file that exists in 10 snapshots is stored once, so snapshots are almost free.
- Undo and restore on the server are instant: just move `head` to an older snapshot. Nothing is re-uploaded.
- The same file in two profiles is stored once.

Snapshot metadata: id, profile, device id, device nickname, action (`push`, `restore`), timestamp (UTC, shown in local time), file count, total size, and the parent snapshot id.

**Retention:** keep the newest N snapshots per profile (default 20, configurable). After pruning, a garbage-collection pass deletes objects no snapshot references. GC runs in the background, never during an active sync.

**Writes are atomic:** objects are written to `tmp/` and renamed into place. A snapshot only becomes head after every object it references exists and is verified.

**Server-side security:** every incoming path is normalized and rejected if it is absolute, contains `..`, or escapes the profile root. Profile and user names are restricted to `[A-Za-z0-9_-]`.

> ⚠️ "zstd-compressed at rest" refined by 003 §2 and 004 §3 (codec chosen per type; already-compressed files stored raw).

### 11. Client local backups (undo for pulls)

> ⚠️ Superseded by 005 §14 (full backup copies inside the flavor folder, recorded in the client database, unlimited).

A pull can overwrite local files that were never pushed, so the server cannot always restore them. Before every pull, the client copies the files it will overwrite or delete into:

```
<client folder>/backups/<profile>/<timestamp>_<device>_pull/
```

with a small `backup.json` listing what was saved. Keep the newest N local backups per profile (default 10). Only changed files are backed up, so this stays fast.

### 12. History and undo (UI)

> ⚠️ Superseded by 005 §14 (history = the local backup list; no server-side history or server undo).

Per profile, a **History** view lists snapshots and local backups merged on one timeline, each labeled like "Laptop · Push · Nov 7, 9:42 PM" or "Desktop · Pull backup · Nov 7, 10:05 PM".

- **Undo last sync**: one button. After a push, it moves server head back one step. After a pull, it restores the local backup.
- **Restore this version**: on any server snapshot, either restore it to this PC (like a pull of that snapshot) or make it the server head again. Both create new history entries, so a restore can itself be undone.

### 13. Client UI (Swing + FlatLaf)

> ⚠️ Extended by 005 §9–§15 (Connections manager, Accounts panel, invite codes, local manager, review dialog, selective pull).

Main window:

- Profile dropdown, with a status badge (Up to date / Push recommended / Pull recommended / Conflict).
- Status line explaining why, including who last pushed and when.
- Big **Push** and **Pull** buttons. The recommended one is highlighted.
- **Undo last sync** and **History** buttons.
- Progress area during sync.
- After a pull that changed addon files, a note: "If you use CurseForge, WowUp, or a similar addon manager, run a rescan or refresh so it picks up the synced addons."

Settings:

- Server host, port, token; "Test connection"; saved certificate fingerprint with a "Forget" button.
- Device nickname.
- Install root and profile list (add, remove, auto-detect), per-profile exclusions.
- Parallel connections, local backups to keep.

**Sync All** (optional button): runs push or pull on each profile one at a time, only where the status is unambiguous; skips conflicts and reports them.

### 14. Configuration files

> ⚠️ Superseded by 005 §3 (server: data folder, `server.json` without users, SQLite) and 005 §10 (client: data folder next to the jar, Connections, SQLite).

**Client:** `wow-ui-sync-client.json` next to the jar. Contains server host, port, token (stored in plain text by design; trusted environment), pinned certificate fingerprint, device id (random UUID generated on first run), device nickname, install root, profiles with exclusions, and settings. Per-profile state (base snapshot id, base manifest, hash cache) lives in `state/<profile>/`.

**Server:** `data/server.json`. Port, bind address, list of users with tokens, retention count, max connections per session, log level. On first run the server creates a default config and one user with a generated token, and prints the token and certificate fingerprint to the console.

> ⚠️ Defaults for host and bind address set by 002 §2.

### 15. Running the server

> ⚠️ Server console superseded by 005 §6 (no console; log file; admin work happens in the client and in `install` / `reset-root` scripts).

`scripts/start.sh`:

- Starts (or attaches to) a `screen` session named `wowuisync`.
- Runs the server jar in a loop. When the process exits unexpectedly, it prints the exit code and counts down ("Restarting in 10… 9… 8…") before restarting.
- A clean shutdown (`stop` typed in the server console, or `scripts/stop.sh`) writes a stop flag so the loop exits instead of restarting.

The server console accepts simple commands: `stop`, `status`, `users`, `adduser <name>` (prints a new token), `gc`.

Optional `scripts/wow-ui-sync.service` for systemd with `Restart=on-failure`, for people who prefer starting on boot.

Setup notes for the README: install `openjdk-21-jre-headless`, open the port (`sudo ufw allow 47811/tcp`), run `start.sh`.

> ⚠️ Extended by 002 §3 (Windows `.bat` scripts, `screen` fallback, no root needed). Port is 2026 (005 §1).

### 16. Build, CI, and releases

**One command builds both jars, one after the other:** `mvn -B clean package` from the repo root. Reactor order is `common` → `server` → `client`. Each module uses the Maven Shade plugin to produce a self-contained runnable jar. After packaging, the jars are copied into a staging folder:

```
dist/
├── wow-ui-sync-server.jar
└── wow-ui-sync-client.jar
```

**`build.yml`** (every push and pull request): runs on `ubuntu-latest` with Java 21, builds, runs tests, and uploads `dist/` as a workflow artifact named `wow-ui-sync-jars` for download. No Windows runner is needed for jars.

**`release.yml`** (on tags `v*`, used once the first test works): builds, then creates two zips and attaches them to a GitHub Release:

- `wow-ui-sync-client-<version>.zip`: client jar, `README.txt`, `LICENSE`.
- `wow-ui-sync-server-<version>.zip`: server jar, `scripts/`, `README.txt`, `LICENSE`.

### 17. Future / out of scope for v1

- Delta sync for large changed files: content-defined chunking (FastCDC, ~16 KB average chunks) so only the changed parts of big SavedVariables (WeakAuras, Details, etc.) are sent. Chunks would live in the same object store. Planned for phase 2. The protocol reserves message types for it.
- Windows .exe packaging (jpackage) and code signing (e.g. Azure Artifact Signing).
- Hosted "Account mode": a public server run by the project, where users register from the client. Kept out of this repo for now.
- Per-character or per-addon selective sync.
- Optional Windows tray mode with auto-push on WoW exit.

> ⚠️ "Account mode" is now covered by the self-hosted account system (005). Per-addon selective pull is now in scope (005 §15).

### 18. Phases

> ⚠️ Superseded: phases are defined in `DESIGN.md` §24.

1. **Phase 1 (first test):** server with storage and snapshots; client with settings, status, push, pull, packs, zstd, parallel connections, local backups, WoW-running check; build + CI artifacts.
2. **Phase 2:** history UI, restore any version, undo button polish, Sync All, delta sync.
3. **Phase 3:** first release zips, README polish, then decide on .exe.

> ⚠️ Phase 1 scope extended by 003 §3 (resumable transfers are Phase 1).

### 19. Legal and naming

- Name: **WoW UI Sync** (repo `wow-ui-sync`).
- Free and open source under GPLv3.
- README and the client's About box say: "Not affiliated with or endorsed by Blizzard Entertainment. World of Warcraft and WoW are trademarks of Blizzard Entertainment, Inc."
- Do not use Blizzard logos, artwork, or fonts.

### Open questions

1. Exact process name(s) for the `_forever_` client, to confirm the WoW-running check.
2. Rough file count and total size of `_forever_` `Interface\AddOns` + `WTF`, to tune pack size, thread count, and the large-file threshold.
3. Whether `.blp` and `.tga` addon textures compress well enough with zstd to be worth compressing.
4. Should a push also sync deletions of whole addon folders by default (current design: yes, since history makes it undoable)?
5. Default port `47811`: fine, or pick another?

---

## Entry 002 — 2026-11-07 — Cross-platform support, home and local servers, AI working rules
Status: Consolidated into DESIGN.md
Supersedes: 001 §2 (platform targets, in part), 001 §4 (path casing rule). Extends 001 §8, §14, §15.

### Summary

Both jars run on Windows, Linux, and macOS, in any combination (e.g. Windows client with a Linux server, Linux client with a Windows server, or both on one machine). The only thing a user installs is Java 21. The server can run on a home PC without root. Also adds the "Rules for working from this log" section and the Status board at the top of this file.

### 1. Platforms

- **Client and server both support Windows, Linux, and macOS** (Intel and Apple Silicon). Supersedes "Windows is the primary target" in 001 §2; Windows remains the main test platform.
- **Only requirement: Java 21.** The sync protocol and TLS are our own code plus the JDK. No FTP server, system services, or OS packages are needed on either side.
- `zstd-jni` bundles native libraries for Windows (x64, arm64), Linux (x64, arm64), and macOS (x64, arm64) inside the jar and loads the right one automatically. The shaded jars must keep these native resources (do not let the Shade plugin strip them).
- **Fallback:** if the zstd native library fails to load on an unusual platform, fall back to the JDK's built-in Deflater/Inflater (gzip-style) and log a warning. The compression codec is negotiated in `HELLO`, so a client and server on different codecs still work.

### 2. Home, LAN, and same-machine servers

> ⚠️ Port is 2026 (005 §1). Same-machine token auto-detect and first-run token printing superseded by 005 §4–§6 (no tokens; root is created by the installer).

- The server does not need root. Port 47811 is above 1024, so any normal user can run it.
- **Server bind address default: `0.0.0.0`** (all network interfaces). The server does not need to know its own host name. Setting it to `127.0.0.1` makes it reachable only from the same machine.
- **Client host default: `localhost`.** A client and server on the same machine work with no configuration beyond the token.
- **Same-machine first-run convenience:** when the client connects to `localhost` and the server's `data/` folder is next to the client jar (common for a home setup), the client offers to read the generated token from `data/server.json` automatically.
- For another PC on the home network: enter the server's LAN IP. For access from outside the home: forward TCP 47811 on the router. The README covers both.
- On first run, the server console prints its LAN IP addresses alongside the token and certificate fingerprint, so the user knows what to type into clients.

### 3. Running the server on each OS

- `scripts/start.sh` (Linux and macOS): unchanged from 001 §15, except that if `screen` is not installed it prints a one-line hint and runs the same restart loop in the current terminal.
- New `scripts/start.bat` (Windows): runs the server in its own console window with the same restart loop and visible countdown, and honors the same stop flag.
- New `scripts/stop.bat` (Windows): writes the stop flag and asks the server to shut down cleanly.
- The stop flag is a file (`data/stop.flag`) so all scripts behave the same on every OS.
- The systemd unit stays Linux-only and optional.

### 4. Paths across operating systems (supersedes 001 §4 casing rule)

- **Paths are compared case-insensitively on every OS.** WoW and addons assume Windows behavior, so `WTF/Account/ABC` and `WTF/Account/abc` are the same file everywhere.
- The manifest stores the casing of the side that pushed. On a case-sensitive file system (Linux, some macOS volumes), when a pull writes a file whose name differs only in case from an existing one, rename the existing file to the manifest casing instead of creating a duplicate.
- If a push finds two local files whose paths differ only by case, the push fails before transferring anything and lists them.
- Paths containing characters that are invalid on Windows (`< > : " | ? *`, trailing dots or spaces) are skipped with a warning at push time, so they never break a Windows pull.
- File contents are bytes. Never convert line endings or text encodings.
- Add `**/.DS_Store` to the default exclusions.

### 5. WoW-running check per OS (extends 001 §8)

> ⚠️ Superseded by 003 §1. Name matching below is now only the fallback.

- Windows: match the process executable name against the configured list.
- Linux: WoW runs through Wine, Proton, or Lutris, so also match the configured names anywhere in the process command line (e.g. `…/Wow.exe`).
- macOS: add `World of Warcraft` and `World of Warcraft Classic` to the default list.
- If process info is unavailable, the check is skipped with a note in the log, not treated as an error.

### 6. Install root per OS (extends 001 §4)

- Windows: no fixed default; the user picks the folder (the dev machine uses `C:\Games\World of Warcraft`).
- macOS: suggest `/Applications/World of Warcraft`.
- Linux: no default, since it depends on the Wine prefix; the user picks the folder.

### Changes

- [ ] Parent POM: target Java 21; Shade config keeps `zstd-jni` native resources for all platforms.
- [ ] `common`: codec abstraction (zstd, deflate fallback) with negotiation in `HELLO`.
- [ ] `common`: path normalization utilities implementing §4 (case-insensitive keys, invalid-name checks, case collision detection).
- [ ] `server`: bind address default `0.0.0.0`; print LAN IPs, token, and fingerprint on first run.
- [ ] `client`: host default `localhost`; same-machine token auto-detect.
- [ ] `client`: OS-specific WoW process matching (§5) and install root suggestions (§6).
- [ ] `client`: case-only rename handling on pull (§4).
- [ ] `scripts/start.sh`: `screen` fallback. New `scripts/start.bat` and `scripts/stop.bat`. Stop flag at `data/stop.flag`.
- [ ] Default exclusions include `**/.DS_Store`.
- [ ] README: platform requirements, home/LAN/remote setup, port forwarding note.

### Open questions

1. Open questions 1–5 from Entry 001 are still unresolved.
2. Should the client remember multiple servers (e.g. home and remote) and switch between them, or is one server per client enough for v1?

---

## Entry 003 — 2026-11-07 — Path-based WoW detection, large audio-heavy profiles, resumable transfers
Status: Consolidated into DESIGN.md
Supersedes: 001 §8 WoW-running check and 002 §5 (name matching becomes the fallback). Extends 001 §6, §9, §10, §18.

### Summary

Real-world numbers from the dev machine: the `_forever_` profile (AddOns + WTF) is about **3 GB**, mostly **long sound files**, with Lua and SavedVariables making up most of the file count but little of the size. The design is tuned for that: audio is never recompressed, the large-file path gets parallel chunked streaming, and transfers must survive a dropped connection without starting over, since the first push of 3 GB can take a while on a home upload. WoW-running detection switches to checking where a process was launched from, which is reliable across all game versions and needs no exe name list.

> ⚠️ Corrected by 004 §1: measured data shows the audio is ~18,000 short clips (largest 1.1 MB), not long tracks, so audio travels in packs, not the large-file path.

Resolves 001 open question 1 (process names no longer required) and question 2 (size known; exact file count still welcome).

### 1. WoW-running check: path-based, per profile

- For each running process, read its executable path (`ProcessHandle.Info.command()`). If the path is inside the profile's flavor folder (e.g. `C:\Games\World of Warcraft\_forever_\`), that profile's WoW is running.
- Comparison is case-insensitive and uses normalized absolute paths.
- **Per profile:** only the profile whose game is running is blocked. Playing Anniversary does not block syncing Forever.
- **Fallback:** if a process's path is unavailable (e.g. permissions, or Wine where the visible process is the Wine loader), use the 002 §5 name matching, but only when the match also appears in the process command line together with the flavor folder name. If neither method can tell, show "Couldn't check whether WoW is running" as a warning, not a block.
- Note: Windows Task Manager's Processes tab shows the display name "World of Warcraft", not the exe name. Confirmed on the dev machine: Forever runs as `C:\Games\World of Warcraft\_forever_\Wow.exe`.

### 2. Audio-heavy profiles

> ⚠️ Compression list superseded by 004 §3 (per-type table). The "sound files above 4 MB" note does not apply to measured data (004 §1).

- Treat these extensions as already compressed: never compress in transit or at rest: `.ogg`, `.mp3`, `.m4a`, `.aac`, `.flac`, `.opus`, `.wav` is NOT in this list (raw PCM compresses somewhat; let the 5% rule decide), plus the image and archive types from 001 §9.
- Objects for those types are stored raw in the object store (the object header records the codec, so the reader knows). Refines 001 §10 "zstd-compressed at rest".
- Large-file threshold stays 4 MB; sound files above it use chunked streaming across data connections, so several long tracks upload in parallel.
- Dedup still applies: identical sound files shared by multiple addons or profiles are stored and transferred once.
- Expected behavior: the first push is the big one; afterwards, audio almost never changes, so normal syncs only move Lua and SavedVariables and finish in seconds.

### 3. Resumable transfers (now Phase 1)

- **Completed files are never re-sent.** Because storage is content-addressed, a retried push naturally skips every object the server already has (001 §8 push step 2).
- **Partial large files resume.** The server keeps partially received large objects in `tmp/partial/<sha256>` with the byte count received. New messages: `RESUME_QUERY` (client asks which hashes have partial data) and `RESUME_OFFSET` (server replies with offsets). The client continues from that offset. Partial files older than 7 days are cleaned up.
- **Pulls resume too.** The client keeps partial downloads in `state/<profile>/partial/` and asks the server for the remaining range.
- **Automatic reconnect:** if a data connection drops, the client reconnects with backoff (1, 2, 5, 10, 30 s) and continues. After 5 failed attempts, the sync pauses with a "Resume" button. No snapshot is committed until everything is verified.
- **Progress for big transfers:** show an ETA, and on the first push a note such as "First sync uploads everything (about 3 GB). Later syncs only send changes."

### 4. Tuning defaults (revisit after first test)

- Data connections: 6 (unchanged).
- Large-file chunk size: 1 MB (unchanged); consider 4 MB if throughput is limited by per-chunk overhead.
- Server disk: README notes that the server needs roughly the profile size per profile, plus whatever changes between retained snapshots. Audio-heavy profiles cost little extra per snapshot thanks to dedup.

### Changes

- [ ] `client`: path-based WoW-running detection per profile (§1), with the name-matching fallback from 002 §5.
- [ ] `common`: extension list for already-compressed types (§2); object header records codec (`raw`, `zstd`, `deflate`).
- [ ] `server`: store already-compressed objects raw (§2).
- [ ] `common` + `server` + `client`: `RESUME_QUERY` / `RESUME_OFFSET` messages; server partial object store with 7-day cleanup (§3).
- [ ] `client`: partial downloads, reconnect with backoff, pause/Resume button (§3).
- [ ] `client`: ETA and first-sync note in the progress area (§3).
- [ ] README: server disk space note (§4).

### Open questions

1. Rough file count of `_forever_` AddOns + WTF (right-click → Properties shows "Contains: N files"), to tune pack size and threads.
2. Approximate home upload speed on the desktop and download speed on the laptop, to sanity-check the first-sync time estimate.
3. Remaining from 001: questions 3 (textures), 4 (folder deletions), 5 (port). From 002: question 2 (multiple servers).

---

## Entry 004 — 2026-11-07 — Measured file profile, pack types, per-type compression, object sharding, flavor detection
Status: Consolidated into DESIGN.md
Supersedes: 001 §9 pack and compression rules, 001 §10 object path layout, 003 §2 compression list. Extends 001 §4 profile detection, 001 §6/§8 status flow.

### Summary

The dev machine's `_forever_` AddOns + WTF were measured by file type. About **31,300 files, ~3.2 GB**. Audio is 70% of the bytes but is made of small clips, so the dominant cost is per-file overhead, not single huge files. Packs become the main transfer path, and they are split into **compressed packs** (text and uncompressed images) and **raw packs** (audio and already-compressed images). Resolves 001 open questions 2 and 3, and 003 open question 1.

### 1. Measured profile (dev machine, `_forever_`, 2026-11-07)

| Type | Files | Total MB | Largest MB | Notes |
|---|---:|---:|---:|---|
| `.ogg` | 15,980 | 1,440.6 | 0.7 | already compressed audio |
| `.mp3` | 1,986 | 785.9 | 1.1 | already compressed audio |
| `.tga` | 3,708 | 478.2 | 27.0 | often uncompressed; compresses well |
| `.blp` | 1,562 | 227.0 | 5.3 | WoW texture; often DXT-compressed |
| `.lua` | 6,575 | 220.0 | 18.2 | code + SavedVariables; compresses very well |
| `.toc` | 313 | 29.4 | 14.5 | one unusually large `.toc`; handled like any text file |
| `.ttf` / `.otf` | 66 | 14.9 | 3.3 | fonts; compress moderately |
| `.jpg` / `.png` | 257 | 18.7 | 1.0 | already compressed |
| `.xml`, `.txt`, `.md`, other text | ~1,200 | ~5 | 0.2 | compresses very well |
| `.bak` / `.old` | 106 | 5.9 | 4.6 | outside WTF these are synced like any file |

Takeaways:

- Audio is ~2.2 GB but in ~18,000 files, all under 1.1 MB. It goes through packs, not the large-file path.
- Fewer than ~20 files exceed the 4 MB large-file threshold (big `.tga`, a few SavedVariables `.lua`, the large `.toc`, some `.blp`). The threshold stays at 4 MB.
- An 18 MB SavedVariables `.lua` exists, which confirms delta sync (001 §17) is worth doing in Phase 2.

### 2. Two pack types (supersedes 001 §9 pack rule)

- **Compressed pack:** one zstd stream (level 3) holding an index plus concatenated file bytes, ~8 MB uncompressed per pack. Used for text and compressible binaries.
- **Raw pack:** index plus concatenated bytes, no compression, ~16 MB per pack. Used for audio and already-compressed files.
- A pack never mixes the two kinds. The pack header records its type.
- Within a pack, files are sorted by extension, then path, so similar content sits together (better compression and cache locality).
- **Scheduling order across data connections:** large files first, then raw packs and compressed packs interleaved, so CPU (compression) and network stay busy at the same time.
- Packs remain transport only. The receiver splits them and stores each file as its own object.

### 3. Per-type compression (supersedes 001 §9 and 003 §2 lists)

| Category | Extensions | In transit | At rest on server |
|---|---|---|---|
| Already compressed | `.ogg`, `.mp3`, `.m4a`, `.aac`, `.flac`, `.opus`, `.jpg`, `.jpeg`, `.png`, `.zip`, `.gz`, `.7z`, `.rar` | raw pack | raw |
| Text | `.lua`, `.toc`, `.xml`, `.txt`, `.md`, `.html`, `.css`, `.json`, `.wtf`, `.textile`, `.pkgmeta`, `.md5`, `.log`, `.bak`, `.old`, no extension | compressed pack | zstd |
| Compressible binary | `.tga`, `.ttf`, `.otf`, `.wav`, `.m2` | compressed pack | zstd |
| Unsure | `.blp` and any unknown extension | sample test | per sample test |

**Sample test:** compress the first 256 KB of the file at zstd level 1. If that saves under 5%, treat the file as already compressed (raw pack, raw at rest); otherwise compressed. Cache the result per hash so the test runs once per unique file.

### 4. First scan and hashing

- The first status check hashes ~3.2 GB across ~31,000 files. Use one hashing thread per CPU core (capped at 8) and show "Scanning files: N of M" in the UI.
- After that, the hash cache (001 §7) makes status checks near-instant.

### 5. Object store sharding (supersedes 001 §10 path layout)

- Objects are stored at `objects/<hash[0..2]>/<hash[2..4]>/<sha256>` (two levels, 65,536 buckets) so no directory grows too large with tens of thousands of objects per profile.
- Each object has a small header: magic, version, codec (`raw`, `zstd`, `deflate`), original size.

### 6. Manifest size and the status flow (refines 001 §6, §8)

- A full manifest is ~31,000 entries (about 3–4 MB of JSON, roughly 0.5 MB after zstd). That is fine to send during push and pull, but too heavy to send on every status check.
- **Manifest fingerprint:** SHA-256 over the sorted `path|size|hash` lines. Every snapshot stores its fingerprint.
- **Status check sends only fingerprints:** the client sends its local fingerprint and its base snapshot id; the server replies with head id, head metadata, and head fingerprint. The three-way table in 001 §8 is evaluated from these alone. Full manifests are only exchanged when the user actually pushes, pulls, or opens the conflict details.
- **Push when base == head:** the client sends only the entries that differ from its base manifest (added, changed, removed). The server applies them to head to build the new snapshot. Falls back to a full manifest if base ≠ head.

### 7. Flavor folder detection (extends 001 §4)

- A subfolder of the install root counts as a flavor folder if it contains a `.flavor.info` file or a `Wow*.exe` (Windows) / `World of Warcraft*.app` (macOS), plus a `WTF` or `Interface` folder. Folders that match `_*_` but have none of these markers are listed with a "not a WoW install?" note.
- The profile name defaults to the folder name with underscores trimmed (`_forever_` → `forever`), editable by the user. The server-side profile id stays fixed after creation, so renaming the label never splits history.
- `.flavor.info` contents are not parsed in v1 (format undocumented); its presence is the marker.

### Changes

- [ ] `common`: pack format with type header (compressed / raw), index, sorted entries (§2).
- [ ] `common`: per-type compression table and the cached 256 KB sample test (§3).
- [ ] `common` + `server`: object header (magic, version, codec, size); two-level shard paths (§5).
- [ ] `client` + `server`: scheduler that orders large files first, then interleaves raw and compressed packs (§2).
- [ ] `common`: manifest fingerprint; snapshots store it (§6).
- [ ] `client` + `server`: fingerprint-only status check; delta manifest on push when base == head (§6).
- [ ] `client`: parallel first-scan hashing with progress (§4).
- [ ] `client`: flavor detection via `.flavor.info` / `Wow*.exe` markers; profile label vs fixed profile id (§7).

### Open questions

1. Approximate upload speed on the desktop and download speed on the laptop (for the first-sync ETA text).
2. Remaining: 001 question 4 (folder deletions) and 5 (port), 002 question 2 (multiple servers).

---

## Entry 005 — 2026-11-07 — Accounts and roles, install-time root, connections, local manager, mirror push, selective pull, local backups
Status: Consolidated into DESIGN.md
Supersedes: 001 §1 (snapshot per sync), §6 (port 47811, token auth), §10 (folder layout, history, retention), §11, §12, §14, §15 (console), §17 (Account mode), §18 (phases); 002 §2 (port, token auto-detect). Extends 001 §4, §8, §13; 003 §1.

### Summary

The server becomes a small multi-account host while staying self-hosted. Accounts have roles (**root**, **superuser**, **user**) and log in with a **password or a public key**. `install` creates the server and the root account in one interactive run over SSH. The client works like WinSCP: you save **Connections** (host, port, username, password or private key file) and pick one. The server keeps **one current copy** per account; there is no server-side history. The client gains a second job as a **local manager**: full backup copies inside the flavor folder, restore, and deleting addons. Push is a **mirror** (adds, updates, and deletes) behind a **review dialog**. Pull is **selective**: tick which addons and which addon data to bring down, from any account you can access.

Resolves 001 open questions 4 (push mirrors deletions) and 5 (port is 2026), and 002 open question 2 (the client stores multiple Connections).

### 1. Terms and port

- **Account**: a sync identity on the server. It can be a person or just another install or setup (a stream setup, a partner's PC). It has a username, a role, an optional email, and either a password or a public key.
- **Role**: `root`, `superuser`, or `user` (§4).
- **Owner**: every `user` account has an owner, the root or superuser who created it or whose invite code was used.
- **Connection** (client side): a saved login to a server, like a WinSCP site (§10). "Profile" keeps meaning a game flavor folder.
- **Local backup**: a full copy of a flavor folder's `Interface` and `WTF`, made by the client inside that flavor folder (§14).
- **Default port: `2026`** everywhere (server config, installer default, client default, README `ufw` example). Any port 1025–65535 is allowed. The number is a nod to the year WoW Forever launched.

### 2. Server storage: one current copy per account (supersedes 001 §10 history and retention)

- The server keeps exactly **one current copy** per account and game profile: the last successful push. There are no snapshots, no retention count, and no server-side undo or restore. Undo comes only from local backups (§14).
- Storage stays content-addressed per account (004 §5 sharding, object headers, codecs), because that keeps dedup, cheap unchanged-file checks, and resumable transfers (003 §3).
- Each account and profile has one **current manifest** plus its fingerprint (004 §6). A push builds the new manifest in a temp file, verifies every object, then atomically replaces the current manifest.
- After every successful push, objects no longer referenced by that account's current manifests are garbage-collected. GC never runs during an active transfer for that account.
- Accounts do not share objects with each other. Deleting an account deletes its whole folder.
- The three-way status (001 §8) still works: the "base" a client remembers is the manifest fingerprint of the last push or pull it did for that account and profile, and "head" is the server's current manifest fingerprint.

### 3. Server data folder and files (supersedes 001 §14 server part)

- The data folder is a **full path chosen during install**. It can be anywhere the server's OS user can read and write; it does not have to sit next to the jar. The jar finds it through `server.json` next to the jar, or a `--data <path>` argument.
- Layout:

```
<data folder>/
├── server.json          port, bind address, public host, transfer settings, SMTP settings, log level
├── wowuisync.db         SQLite: accounts, roles, owners, credentials, invite codes, reset codes, sessions
├── keys/                public keys for key-based accounts, one file per account
├── tls/                 TLS keystore (server private key + self-signed certificate)
├── logs/server.log      rotating log
├── stop.flag            written by stop scripts (002 §3)
└── accounts/<account-id>/profiles/<profile-id>/
    ├── current.manifest
    └── objects/ab/cd/<sha256>
```

- Accounts are stored on disk by a fixed numeric or random **account id**, not by username, so renaming never moves folders.
- **Permission lockdown on every startup:** on Linux and macOS, the data folder, `keys/`, `tls/`, and `wowuisync.db` are set to `700` (folders) and `600` (files) for the user running the server. On Windows, the same is done with file ACLs (owner only). If anything was looser, the server fixes it and logs a warning. Public keys are not secret, but they must be write-protected, since anyone who could add one could log in as that account.
- The README recommends running the server as its own unprivileged OS user (e.g. `wowuisync`) without sudo.

### 4. Roles and permissions

| | root | superuser | user |
|---|---|---|---|
| How many | exactly one, username `root` (reserved) | any number | any number |
| Created by | installer only | root (direct or invite) | root or a superuser (direct or invite) |
| Can access data of | every account | itself + users it owns | itself |
| Can create | superusers, users, invite codes for either | users, invite codes for users | nothing |
| Can manage (reset password/key, edit email, delete) | every account except that root's own credentials are changed via `reset-root` or its own settings | users it owns | its own password, key, and email |
| Server settings | via `server.json` / scripts on the host | no | no |

- "Access data" means full read and write: view, pull from, and push to that account (push to another account requires the extra confirmation in §13).
- **Superusers are peers.** A superuser can never see, change, or delete root, another superuser, or users owned by someone else. A superuser is not a server admin; it is a group owner for its own set of accounts (stream setup, partner's PC, friends).
- **Max users per superuser:** root sets a cap when creating or editing a superuser. Default **unlimited**. The cap counts existing owned users plus remaining slots on that superuser's active invite codes. A capped superuser cannot create unlimited-use invite codes.
- **Deleting a superuser** moves all of its owned users to root's ownership. Their data is kept.
- **Usernames:** 3–32 characters, `[A-Za-z0-9_-]`, unique case-insensitively. `root` is reserved.

### 5. Authentication (supersedes 001 §6 token auth)

- Every account uses **either a public key or a password, never both**. If a key is set, password login is refused with a clear message ("This account requires a key"), and vice versa.
- **Keys:** OpenSSH public key lines (`ssh-ed25519`, `ssh-rsa` with at least 2048 bits, `ecdsa-sha2-nistp256`). Login is challenge–response: the server sends a random nonce; the client signs the nonce together with the session id and the server's TLS certificate fingerprint using the private key; the server verifies with the stored public key. The private key never leaves the client.
- The client reads **OpenSSH-format private key files**, with a passphrase prompt if encrypted. PuTTYgen users export via **Conversions → Export OpenSSH key** (a `.ppk` import may be added later). The README says to use a separate key pair for WoW UI Sync, not the key used for SSH.
- **WoW UI Sync keys never grant SSH or shell access.** They are checked only by the server jar inside its own protocol.
- **Passwords** are stored as PBKDF2-HMAC-SHA256 hashes (JDK built-in, 600,000 iterations, 16-byte random salt). Minimum length 8.
- After login the server issues a random **session id** for that sync session; data connections join with it. Sessions end on disconnect. Changing an account's password or key ends its active sessions.
- **Email password reset** (password accounts only): each account may have an email. If SMTP is configured in `server.json`, the client shows "Forgot password": the server emails a 6-digit code valid for 15 minutes, single use; the user enters it with a new password. If SMTP is not configured, the option is hidden. Key-only accounts are recovered by their manager (root or the owning superuser) setting a new key or password, and root by `reset-root` (§6).
- **SMTP:** the server sends mail by logging in to an outside mail service (Gmail app password, Brevo, SMTP2GO, Amazon SES, etc.). No mail server is installed on the host. Settings: host, port, username, password, from address, `starttls` or `ssl`.

### 6. Server scripts and no console (supersedes 001 §15 console)

- The server has **no interactive console**. It runs headless and writes `logs/server.log`. `start` / `stop` scripts and the restart loop from 001 §15 and 002 §3 stay.
- Interactive admin tasks are subcommands of the server jar, so they work the same on every OS and can hide password input (`Console.readPassword`). Thin wrappers call them: `scripts/install.sh` / `install.bat`, `scripts/reset-root.sh` / `reset-root.bat`.
- **`install`** (run once over SSH):
  1. Checks Java 21.
  2. Asks for the **data folder full path** (default `~/wow-ui-sync-data`), creates it, checks write access.
  3. Asks for the **port** (default `2026`, must be 1025–65535) and the **public host or IP** (a detected guess is offered; used only in printed connection info and invite codes).
  4. Asks for root's **public key**: paste an OpenSSH public key line, or press Enter to skip. The key is parsed and validated (type, size, not truncated); on failure it asks again.
  5. If no key was given: asks for root's **password twice** with hidden input, repeating on mismatch or if too short.
  6. Asks for root's **email** (optional; Enter to skip).
  7. Writes `server.json`, creates `wowuisync.db`, generates the TLS keystore, creates `keys/`, locks down permissions, and creates the **root account**.
  8. Prints a summary: host, port, username `root`, login method, and the **TLS certificate fingerprint** to compare on the client's first connect. Offers to start the server.
  - `install` refuses to run if root already exists and points to `reset-root`.
- **`reset-root`**: asks whether to set a new public key or a new password (password asked twice, hidden), replaces root's credentials, and ends root's sessions. Works while the server is running (SQLite in WAL mode; the server re-reads credentials on each login).
- The old `invite.sh` idea is dropped: invite codes are made in the client (§8).

### 7. Direct account creation (client, Accounts panel)

- Root or a superuser opens **Accounts → New account** and fills in: username, role (root can pick superuser or user; a superuser can only create users), either a **public key** (paste the OpenSSH public key line) or a **password** (twice), optional email, and for superusers the max-users cap.
- The private key is never pasted here. The new account's owner sets up their own Connection on their own PC, just like adding a new FTP account and then creating a site for it in WinSCP.

### 8. Invite codes (client)

- Root can create invite codes for **superuser** or **user**; a superuser can create codes for **user** only (counted against its cap, §4).
- Options: **uses** = 1, a number N, or unlimited; optional **expiry** date.
- The code is one pasteable string: `wus1-<base64url JSON>` containing host, port, TLS certificate fingerprint, and a random secret of at least 128 bits. Base64 is packaging, not encryption; the secret is what matters. The server stores only a hash of the secret, plus role, owner (the creator), remaining uses, and expiry.
- Each redemption decrements uses. When uses reach 0, or the code expires, the row is **deleted**, so the database stays small. Creators can list and revoke their own codes; root can see all.
- **Redeeming:** in the client, **Join with invite code** → paste → the client fills in host and port, verifies the TLS fingerprint automatically, then asks for a username, a public key file (the client reads the public half and sends only that) or a password, and an optional email. The account is created with owner = code creator, and a Connection is saved.

### 9. Accounts panel (client)

- Visible to root and superusers. Lists every account the logged-in account may manage (§4): username, role, owner, email, login method, last push time, storage used.
- Actions: set new password or key, edit email, set max users (root, on superusers), delete (requires typing the username to confirm; deletes the account's server data). Plus the invite code list with revoke.

### 10. Client Connections and client data (supersedes 001 §13 settings and §14 client part)

- First launch with no saved Connection opens a **Connect** screen. There is no "set up a server" path in the client; it always assumes the server and root already exist.
- A **Connection** stores: name, host, port (default 2026), username, and either a password (saved, or prompted each time, user's choice; plain text by design for trusted environments) or a **private key file path** (passphrase prompted when needed). Multiple Connections can be saved; a list and editor work like WinSCP's site manager.
- **First connect to a host** shows the server's TLS fingerprint to compare with what `install` printed; once accepted it is pinned per host and port, and a mismatch later blocks the connection with a clear warning. Invite codes pin automatically (§8).
- A wrong login method gives a clear error ("This account requires a key; choose a key file").
- **Choosing a Connection or an account changes nothing on disk.** It only sets which account the next push or pull targets. Files change only after **Push** or **Pull** and the review dialog (§13).
- Client data lives in a folder next to the jar, `wow-ui-sync-data/`: `client.json` (device nickname, install root, profiles, exclusions, transfer settings) and `client.db` (SQLite: Connections, pinned fingerprints, hash caches, per account/profile base fingerprints, local backup records).

### 11. Client has two jobs

1. **Local manager** (no server needed): list, restore, and delete local backups (§14); browse installed addons grouped as real addons (§12); delete an addon (its folders) and/or its data, always after a backup.
2. **Cloud sync**: status, push (mirror, §13), and selective pull (§15) against the chosen account.

### 12. Addon grouping and data mapping

- **Loose files directly inside `Interface/AddOns/`** (for example `.zip` files left as manual backups) are **ignored** by sync, review, and grouping. Only folders count. Everything inside an addon folder still syncs normally.
- **Grouping folders into addons** by reading each folder's `.toc`:
  - Folder B belongs to addon A if B's name starts with A's name followed by `_` or `-` (e.g. `AtlasLootClassic_Collections` → `AtlasLootClassic`) **and** B's `.toc` lists A in `## Dependencies`, `## RequiredDeps`, or `## OptionalDeps`; or
  - A and B share a project id (`## X-Curse-Project-ID`, `## X-Wago-ID`, `## X-WoWI-ID`) and one's name is a prefix of the other's.
  - The display name comes from `## Title` (color codes stripped), falling back to the folder name. Folders that match nothing are their own addon.
- **Mapping data to addons:** WoW names SavedVariables files after the addon folder. `WTF/Account/<acct>/SavedVariables/<Folder>.lua` (and `.lua.bak`) plus per-character copies at `WTF/Account/<acct>/<realm>/<char>/SavedVariables/<Folder>.lua` belong to the addon group containing `<Folder>`.
- Everything else in `WTF` is **General settings**: macros, keybindings, chat and layout caches, per-character addon enable lists (`AddOns.txt`), and `Blizzard_*` SavedVariables (default game UI).

### 13. Push = mirror, with a review dialog (extends 001 §8)

- A push makes the account's server copy an **exact mirror** of the local `Interface/AddOns` and `WTF` (minus exclusions and ignored loose files): new files are uploaded, changed files are re-uploaded, and files that no longer exist locally are **removed** from the server. Unchanged files are skipped.
- **Review dialog before every push and pull:** a window listing, in this order, **New files**, **Edited files**, **Removed files**, each as a plain scrollable text list of paths with a count in its heading. No diffs. Buttons: **Yes** and **Cancel**. Nothing changes on either side until Yes.
- **Pushing to an account other than the one you are logged in as** (root or a superuser acting on an owned account): the dialog headline names the target account, and Yes stays disabled until the user types that account's username.
- The existing warnings (001 §8: pushing when the server is newer) still appear first.

### 14. Local backups (supersedes 001 §11 and §12)

- Before any operation that changes a flavor folder (pull, restore, deleting an addon or its data), the client makes a **full copy** of the current `Interface` and `WTF` folders **inside the same flavor folder**:
  - `Interface_<account>_<YYYY-MM-DD_HH-MM-SS>` and `WTF_<account>_<YYYY-MM-DD_HH-MM-SS>`, e.g. `WTF_Girlfriend_2026-11-07_21-30-53` (dashes instead of colons, since Windows forbids colons). `<account>` is the account whose setup was last applied to this flavor folder, or `local` if unknown.
  - WoW only loads `Interface` and `WTF`, so these sibling folders are ignored by the game.
- The copy is made, then the live folders are updated in place (§15). Live folders are never renamed away, so a nearly current PC only downloads the differences.
- Each backup is **recorded in `client.db`**: flavor, account, timestamp, reason (`before pull from <account>`, `before restore`, `before addon delete`, `manual`), source account for pulls, file count, and size. Folder names stay generic; the database holds the meaning. If the database is missing, the client still lists backups by parsing folder names (reason shown as "unknown").
- **Retention: unlimited.** Backups are only deleted when the user deletes them.
- **Backups list** (per flavor): one entry per `Interface`/`WTF` pair, newest first, shown like "Girlfriend · Nov 7, 2026, 9:30:53 PM · Before pull from Azotorp", with a **Manual backup** button and per-entry **Restore** and **Delete**.
- **Restore** first makes a new backup of the current live folders (reason `before restore`), then makes the live folders match the chosen backup (copy back, diff-style: only differing files are copied, extra files removed). The chosen backup stays in the list, so any restore can itself be undone.

### 15. Selective pull (extends 001 §8, replaces 001 §17 "selective sync" as future work)

- **Source:** any account the logged-in account can access (§4): your own from another PC, an owned account, or anyone's for root.
- **Selection window:** a scrollable list of the source account's addons (grouped per §12), each with an **Addon** checkbox and an **Include data** checkbox, plus a **General settings** row. Shortcut buttons: **All addons**, **All data**, **None**. Typical use: select all, untick the voice pack, pull ~100 MB instead of 3 GB.
- **Mode:**
  - **Replace** (default): after the pull, the local `Interface/AddOns` contains exactly the selected addons; local addons not in the selection are removed (they are in the backup). For selected addons with data ticked, their SavedVariables are mirrored from the source; addons without data ticked keep whatever local data exists. General settings are replaced only if ticked.
  - **Keep my other addons** (checkbox): only the selected addons and data are touched; nothing else is removed.
- **Flow:** WoW check → selection → review dialog (§13 lists) → local backup copy (§14) → download only differing files in the selection → apply removals → record the applied account for this flavor → show the CurseForge/WowUp rescan note if addons changed.
- Pulling your own account onto your own laptop uses the same flow; with everything selected it simply brings the laptop up to date.
- **WoW must be closed:** every operation that changes a flavor folder (pull, restore, addon delete, backup restore) is refused while that flavor's WoW is running (003 §1), with a message saying which game to close. Status checks and push still work, but push warns (001 §8).

### 16. Protocol additions (extends 001 §6)

- Login: `LOGIN_PASSWORD`, `LOGIN_KEY_BEGIN` / `LOGIN_KEY_CHALLENGE` / `LOGIN_KEY_PROOF`, `LOGIN_OK` / `LOGIN_FAIL` (reason codes: `wrong_method`, `bad_credentials`, `locked`, …).
- Accounts: `ACCOUNT_LIST`, `ACCOUNT_CREATE`, `ACCOUNT_UPDATE`, `ACCOUNT_DELETE`, `ACCOUNT_SET_CREDENTIAL`.
- Invites: `INVITE_CREATE`, `INVITE_LIST`, `INVITE_REVOKE`, `INVITE_REDEEM`.
- Reset: `RESET_REQUEST`, `RESET_CONFIRM`.
- Selective pull: `PULL_BEGIN` carries the source account, selected addon groups, data flags, general-settings flag, and mode; the server returns the plan for the review dialog.
- Every push, pull, and account message carries the **target account id**; the server checks §4 permissions on every request, never trusting the client.
- Dropped: `HISTORY_REQ/RESP`, server-side `RESTORE`.

### 17. Libraries (extends 001 §5)

- `sqlite-jdbc` (Apache 2) on server and client; bundles native libraries for Windows, Linux, and macOS (keep them when shading, like zstd-jni).
- `BouncyCastle` (MIT-style license) for parsing OpenSSH public and private key formats, including encrypted private keys. Signature checks use JDK `Signature` (Ed25519, RSA, ECDSA).
- **SMTP:** a minimal built-in sender (STARTTLS or SMTPS, AUTH PLAIN/LOGIN) over JDK sockets, to avoid an extra dependency.

### Changes

- [ ] `server`: data folder from install, `server.json` without users, SQLite schema (accounts, roles, owners, credentials, invites, reset codes), permission lockdown on startup (§3).
- [ ] `server`: one current manifest per account and profile; atomic replace; per-account GC; remove snapshot/history code paths (§2).
- [ ] `server`: role and ownership checks on every request; superuser caps; superuser deletion reassigns users to root (§4).
- [ ] `common` + `server` + `client`: password (PBKDF2) and key (challenge–response) login; OpenSSH key parsing; session ids (§5).
- [ ] `server`: SMTP sender and email reset codes (§5).
- [ ] `server`: `install` and `reset-root` subcommands with hidden input; `install`/`reset-root` `.sh` and `.bat` wrappers; no console; log file (§6).
- [ ] `client`: Accounts panel, direct creation, invite codes (create, list, revoke, redeem) (§7–§9).
- [ ] `client`: Connections manager, fingerprint pinning per host, `wow-ui-sync-data/` with `client.json` + `client.db` (§10).
- [ ] `client`: addon grouping from `.toc`, SavedVariables mapping, General settings group, ignore loose files in AddOns (§12).
- [ ] `client`: mirror push with review dialog and typed confirmation for other accounts (§13).
- [ ] `client`: local backups (full copy, naming, DB records, unlimited), backups list, manual backup, restore, delete (§14).
- [ ] `client`: selective pull window, Replace / Keep modes, WoW-closed enforcement for all local changes (§15).
- [ ] `common`: protocol messages from §16.
- [ ] Defaults and README: port 2026 everywhere; `install` walkthrough; separate key pair advice; unprivileged OS user advice; SMTP setup.

### Open questions

> ⚠️ All resolved by 006 §1.

1. **Accounts and game versions:** does one account hold one copy per flavor (Forever, Anniversary, Era each stored under the same account), or should each flavor be its own account? Current assumption: one copy per account **per profile**.
2. **Storage limits:** should root be able to cap disk space per account (and per superuser group)?
3. **Login protection:** lockout or backoff after repeated failed logins (per account and per IP)? Suggested: 5 failures → 15-minute lock, plus per-IP rate limiting.
4. **Server upgrades:** how to install a new server version without losing data (replace the jar, keep the data folder, automatic SQLite schema migrations, and a version check between client and server).
5. When the design is declared complete: consolidate 001–005 into `DESIGN.md` with phases sized for usage sessions (see Rules).

---

## Entry 006 — 2026-11-07 — Open questions resolved, consolidation decisions
Status: Consolidated into DESIGN.md
Supersedes: nothing (resolves 005 open questions; fills gaps found while writing `DESIGN.md`).

### Summary

All open questions are answered and the design is declared complete. Everything from 001–006 is consolidated into **`DESIGN.md`**, which is now the spec to build from, with a 12-phase build plan (11 build phases plus an owner test pass).

### 1. Answers to 005 open questions

1. **Game versions:** one account holds a separate current copy per profile (Forever, Anniversary, Era). **v1 enables Forever only**; other flavors are detected but shown as "Coming later". Enabling one later is adding its profile id to an allowlist in `common`.
2. **Storage limits:** per-account cap in **megabytes**, default **unlimited**, set by root (any account) or a superuser (its users). Optional group cap on a superuser covering itself and its users, set by root. Checked before a push uploads anything, with a clear "needs X MB, limit Y MB" message.
3. **Login protection:** 5 failed logins lock an account for 15 minutes. More than 20 failures (logins, invite redemptions, reset codes) from one IP in 10 minutes block that IP for 15 minutes.
4. **Upgrades:** stop, replace the jar, start. On startup the server copies the database to `db-backups/` and runs schema migrations if needed. `HELLO` carries protocol and app versions; incompatible versions get a clear "please update" message.

### 2. Decisions made while consolidating

- **Base after pulls:** base is set after a push, and after pulling the **same** account with everything selected in Replace mode. Any other pull or a restore clears bases for that flavor folder; status then shows "Different from the server" with no recommendation.
- **Pull plan:** the client groups the source's addons by downloading the source's `.toc` files first; the selection is sent to the server as lists of top-level addon folder names (addons and data) plus the general-settings flag and mode. Grouping, data mapping, and plan rules live in `common` so client and server agree.
- **Free-space check** before every local backup copy.
- **Reset codes:** 5 wrong attempts invalidate a code; responses never reveal whether a username or email exists.
- **Java package root:** `io.github.azotorp.wowuisync`.
- **Monetization:** donations only for now (e.g. GitHub Sponsors), wording to be written later; no paid features in this repo.
