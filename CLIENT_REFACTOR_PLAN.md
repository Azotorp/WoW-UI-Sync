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
