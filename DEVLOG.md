# WoW UI Sync — Dev Log

WoW UI Sync backs up and syncs your entire World of Warcraft UI setup (addons, account-wide settings, and per-character SavedVariables) between PCs, through a server you host yourself.

Open source under GPLv3. Not affiliated with or endorsed by Blizzard Entertainment.

---

## Rules for working from this log (read first)

This file is both the design record and the work queue. It is written so an AI agent (Claude Code) or a human can pick up exactly where the last session stopped without redoing or undoing work.

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
| 001 | Initial design draft | Planned |
| 002 | Cross-platform support, home and local servers, AI working rules | Planned |

**Code state:** nothing implemented yet. The repo contains only this file.

**Next step:** merge 001 + 002 into a net spec, then build Phase 1 (001 §18) with the 002 changes included.

---

## Entry 001 — 2026-11-07 — Initial design draft
Status: Planned
Supersedes: nothing

### 1. Goal

Clone a player's WoW UI setup to any other PC, and keep a revision history so mistakes can be undone.

- **Push**: upload local changes to the server.
- **Pull**: download server changes to this PC.
- Only files that actually differ are transferred.
- Every sync creates a labeled, timestamped snapshot that can be restored.

Typical use: play on the desktop, push. Later on the laptop, pull. The app makes it obvious which direction is needed and protects against pushing or pulling the wrong way.

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

### 4. What gets synced

Per profile, relative to the flavor folder:

- `Interface\AddOns\` (the addon files themselves)
- `WTF\` (account settings, per-character SavedVariables, macros, keybinds, layouts, etc.)

**Reference install (dev machine):** `C:\Games\World of Warcraft` with flavor folders `_forever_`, `_anniversary_`, `_classic_era_`. First target profile is `_forever_`:

- `C:\Games\World of Warcraft\_forever_\Interface\AddOns`
- `C:\Games\World of Warcraft\_forever_\WTF`

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

> ⚠️ `scripts/` extended by 002 §3 (adds `start.bat` and `stop.bat`).

**Division of work:** the client hashes its local files (only it can see its disk) and sends a compact manifest. The server owns everything else: comparing manifests, deciding what to request or send, storing files, building snapshots, retention, and restore.

**Libraries** (all GPLv3-compatible):

- `zstd-jni` (BSD) for compression. Bundles native libs for Windows and Linux.
- `Jackson` (Apache 2) for JSON config and manifests.
- `FlatLaf` (Apache 2) for a modern dark/light Swing look.
- Built-in JDK for TLS (`SSLSocket`), SHA-256, threading.

### 6. Network protocol

Custom binary protocol over TLS, one TCP port (default `47811`, configurable).

**Framing:** every message is
`[u8 type][u32 requestId][u32 payloadLength][payload]`.
Control payloads are zstd-compressed JSON. Data payloads are raw binary.

**Connections:** one control connection per session, plus N data connections (default 6, configurable 1–16) that join the session with a session token. Data connections pull jobs from a shared queue so large and small transfers interleave instead of blocking each other.

**Authentication:** the server config lists users, each with a long random token. The client stores the server host, port, and token. Every session starts with `HELLO` (protocol version, device id, device nickname) then `AUTH`.

**TLS without a domain:** the server generates a self-signed certificate on first run and prints its SHA-256 fingerprint to the console. The client uses trust-on-first-use: it shows the fingerprint on first connect, saves it once accepted, and refuses to connect (with a clear warning) if it ever changes.

**Core message types (initial):** `HELLO`, `AUTH`, `STATUS_REQ/RESP`, `PUSH_BEGIN`, `PUSH_NEED` (server tells client which hashes it lacks), `PACK_UPLOAD`, `FILE_UPLOAD_CHUNK`, `PUSH_COMMIT`, `PULL_BEGIN`, `PULL_PLAN`, `PACK_DOWNLOAD`, `FILE_DOWNLOAD_CHUNK`, `HISTORY_REQ/RESP`, `RESTORE`, `ERROR`, `PING`.

### 7. Hashing and the local hash cache

- SHA-256 per file.
- The client keeps a hash cache per profile: `path → (size, lastModified, sha256)`. A file is only re-hashed when its size or modified time changes, so a status check on thousands of unchanged files takes well under a second.
- Hashing runs on multiple threads.
- Timestamps are never used to decide which side is newer between machines. They only serve as a cache hint on the local machine.

### 8. Sync algorithm

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

> ⚠️ WoW-running detection extended by 002 §5 (Linux/Wine and macOS matching).

### 9. Transfer: packs, compression, parallelism

**Packs for small files.** Files under the large-file threshold (default 4 MB) are grouped into packs of roughly 8 MB uncompressed. Each pack is one zstd stream containing a small index plus the concatenated file bytes. Compressing many Lua files together gives a much better ratio than compressing each alone, because they share vocabulary. Packs are only a transport format: the server unpacks them and stores each file individually.

**Large files** are streamed individually in 1 MB chunks, so several large files can transfer in parallel across data connections.

**Compression:** zstd, default level 3 (fast, good ratio for text). Files whose extension is already compressed (`.ogg`, `.mp3`, `.png`, `.jpg`, `.zip`, `.blp` is uncertain, decide after measuring) are stored, not recompressed. If compression saves less than about 5%, send raw.

**Parallelism:** a job queue feeds N data connections. Big files are scheduled first so the tail of the transfer is small packs, which keeps all connections busy until the end.

**Integrity:** the receiver verifies SHA-256 of every file before committing it. A mismatch retries that file once, then fails the sync without committing a snapshot.

**Progress:** the client shows files and bytes done/total, current speed, and compressed vs original size.

### 10. Server storage

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

### 11. Client local backups (undo for pulls)

A pull can overwrite local files that were never pushed, so the server cannot always restore them. Before every pull, the client copies the files it will overwrite or delete into:

```
<client folder>/backups/<profile>/<timestamp>_<device>_pull/
```

with a small `backup.json` listing what was saved. Keep the newest N local backups per profile (default 10). Only changed files are backed up, so this stays fast.

### 12. History and undo (UI)

Per profile, a **History** view lists snapshots and local backups merged on one timeline, each labeled like "Laptop · Push · Nov 7, 9:42 PM" or "Desktop · Pull backup · Nov 7, 10:05 PM".

- **Undo last sync**: one button. After a push, it moves server head back one step. After a pull, it restores the local backup.
- **Restore this version**: on any server snapshot, either restore it to this PC (like a pull of that snapshot) or make it the server head again. Both create new history entries, so a restore can itself be undone.

### 13. Client UI (Swing + FlatLaf)

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

**Client:** `wow-ui-sync-client.json` next to the jar. Contains server host, port, token (stored in plain text by design; trusted environment), pinned certificate fingerprint, device id (random UUID generated on first run), device nickname, install root, profiles with exclusions, and settings. Per-profile state (base snapshot id, base manifest, hash cache) lives in `state/<profile>/`.

**Server:** `data/server.json`. Port, bind address, list of users with tokens, retention count, max connections per session, log level. On first run the server creates a default config and one user with a generated token, and prints the token and certificate fingerprint to the console.

> ⚠️ Defaults for host and bind address set by 002 §2.

### 15. Running the server

`scripts/start.sh`:

- Starts (or attaches to) a `screen` session named `wowuisync`.
- Runs the server jar in a loop. When the process exits unexpectedly, it prints the exit code and counts down ("Restarting in 10… 9… 8…") before restarting.
- A clean shutdown (`stop` typed in the server console, or `scripts/stop.sh`) writes a stop flag so the loop exits instead of restarting.

The server console accepts simple commands: `stop`, `status`, `users`, `adduser <name>` (prints a new token), `gc`.

Optional `scripts/wow-ui-sync.service` for systemd with `Restart=on-failure`, for people who prefer starting on boot.

Setup notes for the README: install `openjdk-21-jre-headless`, open the port (`sudo ufw allow 47811/tcp`), run `start.sh`.

> ⚠️ Extended by 002 §3 (Windows `.bat` scripts, `screen` fallback, no root needed).

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

### 18. Phases

1. **Phase 1 (first test):** server with storage and snapshots; client with settings, status, push, pull, packs, zstd, parallel connections, local backups, WoW-running check; build + CI artifacts.
2. **Phase 2:** history UI, restore any version, undo button polish, Sync All, delta sync.
3. **Phase 3:** first release zips, README polish, then decide on .exe.

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
Status: Planned
Supersedes: 001 §2 (platform targets, in part), 001 §4 (path casing rule). Extends 001 §8, §14, §15.

### Summary

Both jars run on Windows, Linux, and macOS, in any combination (e.g. Windows client with a Linux server, Linux client with a Windows server, or both on one machine). The only thing a user installs is Java 21. The server can run on a home PC without root. Also adds the "Rules for working from this log" section and the Status board at the top of this file.

### 1. Platforms

- **Client and server both support Windows, Linux, and macOS** (Intel and Apple Silicon). Supersedes "Windows is the primary target" in 001 §2; Windows remains the main test platform.
- **Only requirement: Java 21.** The sync protocol and TLS are our own code plus the JDK. No FTP server, system services, or OS packages are needed on either side.
- `zstd-jni` bundles native libraries for Windows (x64, arm64), Linux (x64, arm64), and macOS (x64, arm64) inside the jar and loads the right one automatically. The shaded jars must keep these native resources (do not let the Shade plugin strip them).
- **Fallback:** if the zstd native library fails to load on an unusual platform, fall back to the JDK's built-in Deflater/Inflater (gzip-style) and log a warning. The compression codec is negotiated in `HELLO`, so a client and server on different codecs still work.

### 2. Home, LAN, and same-machine servers

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
