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
