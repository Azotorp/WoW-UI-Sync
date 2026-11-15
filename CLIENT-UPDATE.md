# Client-only backup/progress update — 1.2.0-alpha

1. Let your current upload/pull finish, then close the client.
2. Copy `client/wow-ui-sync.client.jar` from this ZIP over the client JAR in the **same directory you already run it from**. Keep the old filename if necessary.
3. Keep the adjacent `wow-ui-sync-data` directory. It holds connections and the scan cache; starting from a new directory without it forces another full scan.
4. Reopen the client.

No server JAR replacement, service restart, installer or data migration is required for this client patch. Keep the working 1.2 server running. The 1.0 server remains separate.

Changes: scan statistics and current path are on separate rows like upload progress; hashing restores the bounded 1.0 concurrency (up to eight workers), reuses per-worker buffers and avoids unsupported Unix timestamp calls on Windows. Existing cache metadata and durable checkpoints remain compatible.

Linux worker/scan measurements are included in the full package report; actual Windows cold-scan speed still needs your retest. An unchanged follow-up scan is verified to reuse cache with zero content reads.

Backup follow-up: Windows copies each selected Interface/WTF folder with Robocopy (/E, bounded /MT, no move/mirror/delete options). The client then verifies destination content in parallel, reusing freshly validated scan-cache hashes instead of rereading unchanged sources. Uncached files still get source and destination hash checks. Linux/macOS use bounded parallel Java copies. A snapshot is published only after verification and source/destination inventory checks succeed.

Copy, verification, download and apply are separate phases; stale scan text is cleared. Native copy uses indeterminate progress while the OS job runs; verification displays real file/byte counts and a separate normalized path.

Linux validation: 36 Java tests passed, one Windows-only native-copy test skipped locally. Windows and Ubuntu branch CI builds/tests pass, including real Windows Robocopy and the Windows client launch smoke (run 43677960647). Large-install Windows timing still needs user retest. The synthetic Linux backup benchmark improved from 2.536 s to 0.772 s for 10,010 files/51.4 MB. This is not a one-second guarantee for a 3.7 GB Windows installation.
