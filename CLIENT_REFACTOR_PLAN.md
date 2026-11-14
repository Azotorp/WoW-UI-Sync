# Client refactor plan — phase 1: reliable Browse dialogs

**Decision date:** 2026-11-14  
**Authoritative base:** latest `main` (restored WoW UI Sync 1.0.0)  
**Implementation status:** planned; no code change or build delivered by this document

## Objective

Fix the Windows client browsing experience. The user reports that double-clicking folders in the chooser often takes repeated attempts and can take approximately 20 seconds to respond. That is a user observation, not a confirmed root cause.

This first implementation is **client-only**. Replacing the client JAR must be sufficient; **do not change or restart the existing 1.0.0 server**.

## Current code findings

Both browse actions use Swing's `JFileChooser`:

1. `client/src/main/java/io/github/azotorp/wowuisync/client/ui/SettingsDialog.java`: `DIRECTORIES_ONLY` chooser for the WoW installation root. On selection, fills the path and runs detection.
2. `client/src/main/java/io/github/azotorp/wowuisync/client/ui/ConnectionEditDialog.java`: file chooser for the OpenSSH private-key path. Defaults to the user's `.ssh` folder when the field is blank, and currently allows hidden files.

The shared chooser implementation, OS integration, and timing should be evaluated on the same Windows environment that reproduces the issue. Slow Shell locations and OS integration are plausible explanations, but not yet proven.

## Implementation requirements

- Provide a **shared client-side picker abstraction** with two operations: choose a directory and choose a file; preserve each action's title and initial path behavior.
- On Windows, prefer a true **native directory-selection dialog** for WoW folders and a native file-open dialog for SSH keys, provided the approach is stable on the project's supported Java runtime. Java AWT `FileDialog` alone does not supply a general Windows directory chooser; a directory-specific OS integration or proven library may be necessary. Keep a safe cross-platform fallback.
- A normal single double-click should navigate into a folder without repeated clicks. Ordinary browsing must not freeze or duplicate dialogs.
- Preserve manual path entry/paste for both fields.
- When cancelling, leave the existing field unchanged. On selection, insert the absolute selected path; preserve WoW flavor detection.
- For SSH keys, start in the previously selected key's parent or `~/.ssh`; support hidden files and warn against choosing a public `.pub` key. Do not print or disclose key contents.
- Handle nonexisting initial paths, Windows drive roots, access-denied folders, and network/offline paths with clear error feedback; do not allow hangs or accidental selection.
- Do not change the app's saved configuration format, login logic, network calls, or security checks except as needed to pass the correct file/directory path.

## Phase 1 setup usability: automatic installation discovery (owner decisions, 2026-11-14)

### User-facing terminology

- The UI term is **version** / **versions**, not "flavor" / "flavors". In Settings, use **"Detected versions:"**; other UI labels and messages should likewise use "version" in the context of WoW installations. Internal Java class names, profile IDs, configuration keys, and wire/protocol identifiers may remain unchanged for backwards compatibility; do not silently change server data or require a server update.

### WoW folder field and automatic detection

- Rename **"WoW folder:"** to a clear label such as **"World of Warcraft installation folder:"**, with help text explaining to select the **parent World of Warcraft directory** containing folders such as `_forever_`, **not** a version subfolder.
- On Windows, the current client has **no default**: `FlavorDetector.suggestedRoot()` only suggests `/Applications/World of Warcraft` on macOS. And Settings' `detect()` silently returns if the path is blank. Fix both.
- On first launch / first-time setup, and when opening Settings with no saved valid installation root, **automatically check likely default installation locations**. On Windows, use OS environment variables for Program Files directories and try both `C:\Program Files (x86)\World of Warcraft` and `C:\Program Files\World of Warcraft` when applicable, plus any other deterministic safe candidates. Keep existing macOS suggestion, and allow manual selection on every OS.
- **Only autofill/activate a candidate that exists and validates as a WoW parent directory** by containing recognizable version subdirectories. Do not pretend that a conventional default path exists. If no valid installation is found, show an example/hint and a clear **"Not found automatically — Browse to your World of Warcraft folder"** state instead of an unexplained blank. Never scan entire drives or block the UI with expensive recursive searching.
- If a saved user-selected path is valid, preserve it; do not override it with a default. If the saved path is missing/invalid, explain and offer discovery/browse without silently changing configuration. Keep manual entry/paste.
- **Detection happens automatically** at opening and after the directory is changed (including Browse or a manually pasted path). Remove the redundant **"Detect"** button; the **"Detected versions"** list updates automatically. If editing a path, debounce validation as necessary rather than repeatedly walking directories on every keystroke.
- If someone selects a version folder like `_forever_` directly, explain **"Select the World of Warcraft folder one level above _forever_"** and provide an option to use its parent when that parent validates. Do not accept a version folder as the installation root.
- Report actionable states for empty, inaccessible, invalid, missing, and valid roots, and when zero supported versions are found. Distinguish an installed but not-yet-supported version from a genuinely invalid directory.
- Preserve existing saves/profile IDs and the existing 1.0.0 client/server protocol. This is a **client-only** enhancement; the deployed server remains untouched and running.

### Acceptance checks

- New Windows installation with the game in Program Files (x86) auto-populates the correct installation **parent** and lists Detected versions without clicking Detect.
- Repeat with Program Files (non-x86), a non-default drive, and a custom folder; previously saved custom paths must win over conventional defaults.
- No game installation: obvious guidance, browse and paste still work, no false "found" status, no long disk sweep.
- Pasting/selecting `_forever_` explains the parent-root requirement and avoids saving the wrong path.
- Selecting a valid parent updates the version list automatically; no Detect button is needed.
- All client-facing "flavor" terminology referring to game installations changes to "version" without breaking serialized/internal identifiers or server compatibility.

## Installation discovery: launcher signature and Windows registry (owner decisions, 2026-11-14)

The folder **name is not a reliable installation identifier**. `C:\Games\WoW`, `D:\Games\World of Warcraft`, or any custom-named parent folder may be valid. The goal is to locate the game installation **by contents**, not by insisting its name equal "World of Warcraft".

- On Windows, treat the **exact file `World of Warcraft Launcher.exe` in the parent installation directory** as a high-confidence discovery marker. Look up the file path, not just the name of its parent directory. The known exact spelling includes spaces and capital W/W/L.
- Do **not** make that EXE mandatory for all valid installs, since copied, portable, private-server, and otherwise nonstandard installations might have version folders with valid game executables, `Interface` and `WTF` yet lack this launcher. Secondary validation signals can include `.build.info`, `Data`, and recognizable version subdirectories containing game executables or `Interface`/`WTF`. Do not accept an arbitrary directory based on its name alone.
- **Windows registry discovery should run before guessed-path searches**, read-only and best-effort. Probe registered WoW installation paths under `HKLM\SOFTWARE\Blizzard Entertainment\World of Warcraft` (including the 32-bit view / `WOW6432Node`) for `InstallPath`; examine relevant `HKLM\SOFTWARE\Microsoft\Windows\CurrentVersion\Uninstall` entries (both registry views) with WoW display names and an `InstallLocation`, when present. Consider `HKCU` entries where applicable, and deduplicate. Registry entries may be missing, stale, or point to a subfolder, so **normalize and validate every candidate against actual files**. Do not edit the registry or require elevated privileges.
- When registry information is missing or invalid, check **bounded, fast, common-location candidates** on available local drives: OS-provided `ProgramFiles` and `ProgramFiles(x86)`, and shallow locations such as `Games`, `Blizzard`, `World of Warcraft`, `WoW`, and their common combinations. Recognize arbitrary directory names in candidate parent locations by their EXE/content signature rather than relying only on a fixed named folder.
- On **case-insensitive Windows filesystems**, match candidate folder names case-insensitively and read actual directory entries; do not generate dozens of casing permutations. On Linux, honor filesystem case sensitivity, and never apply a blanket lower-case path conversion. Check filename case consistent with the platform.
- Search only reasonable, bounded parent folders (avoid recursively crawling whole disks, network drives, or every file). Run potentially slow disk/registry discovery off the Swing event-dispatch thread; keep UI responsive and report progress or timeout/failure without freezing.
- Detection is **automatic on opening first-time setup/Settings**, and after changing the selected folder. Once it identifies candidate parent directories, list their recognized versions (e.g. `_forever_`) without a Detect button.
- If multiple valid installations are detected, **let the user pick which one to use**; never silently choose or overwrite a saved selection. A previously saved valid root remains the highest priority. An installer path is only a candidate, not proof.
- Preserve the explicit guidance to choose the parent installation directory, not the version subfolder. No server, wire protocol, or persistent configuration format changes for this feature.

Reference evidence checked 2026-11-14: Blizzard forum install layouts identify `World of Warcraft Launcher.exe` in the parent directory; Microsoft documents `InstallLocation` in Windows uninstall registry; community examples identify Blizzard's legacy `InstallPath` registry value. These are **candidate sources**, not universal guarantees.

## OS-aware discovery: Windows, macOS and Linux (owner decisions, 2026-11-14)

Treat the client JAR as **cross-platform**. Pick the appropriate strategy from the detected operating system (not by guessing from a path string); use standard Java filesystem checks for all platforms. Detecting a WoW installation means identifying the **parent game install root**, not just the launcher executable, and then enumerating recognizable version directories. Preserve the selected version/profile IDs and client/server protocol.

### Windows
- Only Windows uses **Windows registry** queries: existing Blizzard/Windows uninstall install-path hints, read-only, with both 32- and 64-bit registry views when relevant. Missing/stale registry entries must never block detection.
- Check the known filename `World of Warcraft Launcher.exe` as a high-confidence marker, plus game/version content for installations without that launcher. Do not require any particular parent directory name.
- Search only bounded likely folders (Program Files variants, Games, Blizzard, and similar), then validate actual game contents. Respect filesystem semantics and custom/non-default drives; don't recursively scan all disks.

### macOS — research and community validation needed
- Investigate **native macOS application bundles**, especially `World of Warcraft.app` and the app bundle's `Contents/MacOS` executable, rather than checking for Windows `.exe` launchers.
- Initial likely candidate root is `/Applications/World of Warcraft`, but names and install locations can vary. Identify `World of Warcraft.app` **inside a version subdirectory** as well as possible older parent layouts. Validate the enclosing game/version directory structure, not merely the `.app` suffix.
- Research whether the Battle.net launcher provides a reliable install-path hint on macOS; do not treat `Battle.net.app` alone as proof of a WoW installation. macOS has no Windows registry.
- There is **no Mac available to the owner for live testing**. Cross-platform logic should have testable directory fixtures and path-resolution unit tests; **actual macOS UI/filesystem integration must remain explicitly unverified** until an open-source contributor tests it. Invite precise bug reports/PRs rather than claiming success without hardware validation.

### Linux — Wine, Proton, copied Windows installs
- Do **not** assume WoW cannot be installed on Linux. A Windows WoW executable can exist in an ordinary Linux directory or inside a **Wine/Proton prefix**; the detector is locating data, not trying to run the game.
- Check existing, limited user-local candidates, such as `~/.wine/drive_c`, `~/Games`, Lutris game folders, Bottles prefixes, Steam `steamapps/compatdata/*/pfx/drive_c`, and Heroic-managed prefixes when present. Prefixes may contain `Program Files` or `Program Files (x86)`; installation paths may be configured **outside** prefixes. Candidate path examples are hints only, not mandatory layouts.
- Seek Windows-style markers including `World of Warcraft Launcher.exe`, but **do not require a launcher**. Recognize version folders by appropriate `WoW*.exe`, `Interface`, `WTF`, and/or other validated installation markers. For custom locations, allow Browse, paste and optionally launcher-configured paths.
- Linux directory names are **case-sensitive**; enumerate real entries instead of blindly converting to lower case or constructing lots of case permutations. Follow only bounded, safe candidate directories; avoid symlink loops, whole-home recursive searches, unreadable folders and huge Steam-prefix sweeps on the UI thread.
- Linux Wine's registry is **not** the native Windows registry. Do not invoke `reg query` or assume Windows APIs exist there. A future optional Wine-registry hint must be deliberately designed and separately tested.

### Shared user experience and testing
- Valid previously saved custom path wins. If multiple roots validate, let the user choose. No silent overwrite of config.
- Automatic discovery runs at first setup/Settings open, and after manual selection. Remove the redundant Detect button and update **Detected versions** automatically. Explain bad paths, including direct selection of `_forever_`.
- Run bounded discovery off the Swing event-dispatch thread; cancel or time-limit slow probes and keep browsing responsive. No background process or drive-wide indefinite crawl.
- Test via filesystem fixtures on all runnable environments, including **macOS-style `.app` bundles**, Windows roots with arbitrary names, and Linux Wine/Proton paths with case-sensitive names. Tests on non-Mac hardware **do not** count as macOS validation.
- **Client-only scope**: replacing the client JAR must suffice, no server restarts or network/protocol changes. Any server dependency requires the owner's explicit approval first.

### Research sources for the implementation team

- Warcraft Wiki's documented macOS `/Applications/World of Warcraft/World of Warcraft.app` and embedded app-bundle executable (some layouts differ by version).
- Blizzard's Mac technical support discussion (Nov. 2024) describing `World of Warcraft.app` within the game-version folder.
- Lutris' World of Warcraft Linux installation documentation recommends keeping game data outside Wine prefixes; do not hardcode a prefix-only strategy.
- Linux WoW community installation examples describe `~/.wine/drive_c`, Lutris and Steam Proton compatibility prefixes. These are candidate examples; verify actual installed files.

## Settings clarity: detected versions and layered exclusions (owner decisions, 2026-11-14)

- Keep **Profile** on the main screen as the sole **active sync version** selector. Settings' **Detected versions** is a passive, non-clickable text/label display of detected folders and support statuses, refreshed on install-root changes; do not add another active sync version selector.
- In the existing exclusions row, **replace the left-hand label with a dropdown** whose choices are **Global (default)**, then the identified/detected game versions such as **Forever**, **Anniversary**, and **Classic Era**. This dropdown **only switches which exclusion rule list is being edited**, never which version is being synced. Preserve unsaved edits when switching scope within Settings, and save/cancel the scopes together consistently.
- **Global exclusions** initially contain the nine existing default globs and apply to **every** version. Each **version-specific exclusions** list starts **empty** for a fresh configuration, and is **additive** to global: for version V, the effective exclusions are `Global ∪ V-specific`; rules in one version's list do not apply to another. Adding a version-specific rule must not remove, override, negate or duplicate global rules. A global rule is always active even when the editor dropdown displays a blank version-specific list.
- Use **one editable multiline text area** with at least **10 visible rows**, enough for the nine default global rules plus a visibly blank line. In an empty version-specific scope, show an empty editor with instructional text outside the editable area (not a fake stored placeholder). `Restore defaults` in Global restores the nine defaults; in a version scope it clears just that version's additions, with an understandable confirmation if destructive.
- **Above the editor**, just below Detected versions, show a contextual *read-only path-base line* derived from the **actual configured WoW install root** and OS. For Global, display the root as a **parent path followed by a visually distinct placeholder for the version subfolder**, for example Windows `C:\\Games\\World of Warcraft\\<version folder>\\` or Linux `/home/user/Games/WoW/<version folder>/`. macOS uses `/` too. In the actual UI style `<version folder>` subtly (e.g. italic/muted accent) to convey a placeholder, not a real directory. For Forever-specific scope, resolve the placeholder to `_forever_`; similarly `_anniversary_` and `_classic_era_`. Do not invent a real installed path when no valid install is configured; show explanatory placeholder text. The explanatory root is **inside the selected version folder**, not the WoW parent, server directory or backup directory.
- **Separators:** present the physical example root with OS-native separators (`\\` Windows, `/` Linux/macOS), as requested. Permit users to enter `WTF\\**\\*.db` **or** `WTF/**/*.db` in the Windows exclusions editor; on Linux/macOS use `/` examples. Internally normalize both slash styles to portable logical relative paths/patterns, with identical wildcard behavior regardless of OS, and preserve reliable serialization/compatibility. Do not treat user-entered backslashes as regex escape markers or as an absolute Windows drive path. Existing saved forward-slash rules must continue to work on Windows. Display syntax help relevant to the OS without changing the matcher semantics.
- **Optional `./` or `.\\` prefix** indicates relative to that version's root, e.g. `./WTF/**/*.db` or Windows `.\\WTF\\**\\*.db`. 1.0.0's current `Exclusions.toRegex` would interpret the prefix literally; normalize it deliberately before matching/saving or normalize scanned paths consistently. Validate and reject absolute patterns or parent traversal (`../`, `..\\`) to keep exclusions scoped to the selected version. Retain all preexisting patterns, and don't silently break 1.0.0 compatibility.
- Explain `*` = zero or more characters **inside one path segment**, `**/` = **zero or more directory levels**. Show examples: `WTF/*/*.bak` (one nested directory) vs `WTF/**/*.bak` (any depth, including directly in `WTF`).
- **Code audit (current main):** `client/data/ClientConfig.java` currently stores exclusion lists under `profiles[id].exclusions` (each starts with defaults) and `ClientConfig.exclusions(profileId)` returns that one list; `SettingsDialog.java` shows only `Exclusions (<active id>)` via `JTextArea(9, 40)`. Introduce shared global plus additive version-only settings **client-side**, update every scanner/transfer exclusion consumer to use their union, and preserve 1.0.0 server protocol and the ability to load existing `client.json`.
- **Safe migration:** if legacy lists are identical, initialize Global from that list and version additions empty. If legacy lists are different/customized, do not silently union or discard data or impose one version's rules on another; preserve legacy originals and offer a clear migration/reconciliation choice (including cancel). Do not automatically write a config that renders rollback to the old client unusable. Preserve users' explicitly customized per-version exclusions whenever they choose a migration outcome.
- **Tests:** Global rules affect every selected version; Forever-only rules affect Forever only, not Anniversary or Era; selecting a different editor scope doesn't change main-window active sync profile; persistence/restore/cancel; Windows `\\` and POSIX `/` input and root display; `./` support; 10 visible rows; version-specific blank defaults; migration/rollback; no server modifications. Current 1.0.0 live server remains untouched.

## Verification and acceptance

- Reproduce the slow folder navigation on the original client when possible; record Windows version, Java runtime, path type, and elapsed times for opening and navigating.
- Verify WoW directory browsing, selecting/cancelling, pasting a path, and subsequent detection.
- Verify SSH key navigation, selecting/cancelling, hidden `.ssh` contents, keys without extensions, and handling mistaken `.pub` selections.
- Test repeated browse openings, paths with spaces/Unicode, local/remote drives, and missing/inaccessible initial paths.
- Confirm UI responsiveness and that normal navigation requires one reliable double-click.
- Build/test a **client-only** replacement against the existing 1.0.0 protocol. No server deployment or restart.

## Explicitly outside phase 1

- Rewriting scan or hash-cache algorithms; scanning performance can be audited separately.
- Incremental/chunk storage or snapshot redesign.
- Server code, storage, protocol, packaging, service, or deployment modifications.
- Deleting, restoring, or merging `_defunct_` branches.

If any requirement is impossible without a server-side change, **stop that part, explain the exact dependency and impact, and request explicit authorization** rather than modifying the server automatically.
