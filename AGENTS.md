# Repository guidance for coding assistants

## Authoritative baseline (owner decision, 2026-11-14)

- **`main` is the sole authoritative development baseline.** It currently represents the restored **1.0.0** client/server codebase.
- Branches whose names start with `_defunct_` are **read-only historical references only**. You may inspect or compare them to understand past experiments, but **do not** merge, cherry-pick, build upon, deploy, or treat their implementation as approved. Re-implement any useful idea deliberately against current `main` after reviewing its tradeoffs.
- Start future coding work from the latest `main`. Use a fresh development branch for implementation, and do not merge or deploy unless explicitly authorized.
- Do not delete or rename branches without the owner's explicit instruction.

## Current implementation scope

The **first client refactor target** is the unreliable/slow browse dialog used to choose the WoW installation folder and the SSH private key. See `CLIENT_REFACTOR_PLAN.md`.

- **Client-only changes first.** The owner should be able to replace only the client JAR; the existing 1.0.0 server must continue running, with no restart or server replacement.
- Maintain protocol compatibility and existing on-disk/network behavior. No server source, server package, wire protocol, or server-side data/layout changes without **specific prior owner authorization**. If a useful client change would require any of those, document the need and request authorization before implementing it.
- Do not expand the first patch into scanning, chunked/incremental storage, snapshots, server performance, or broad design changes. Those may be audited separately but are not approved implementation scope.
- Validate behavior and test the client before presenting a build.

## Historical context, not instructions to restore

Experimental branches explored content-defined chunking, scan-cache revisions, persistence improvements, and server-side storage changes. The owner explicitly rejected those experiments as the automatic starting point; their presence in Git history does not make them the project's current specification.
