# Client-only scan/progress update — 1.2.0-alpha

1. Let your current upload/pull finish, then close the client.
2. Copy `client/wow-ui-sync.client.jar` from this ZIP over the client JAR in the **same directory you already run it from**. Keep the old filename if necessary.
3. Keep the adjacent `wow-ui-sync-data` directory. It holds connections and the scan cache; starting from a new directory without it forces another full scan.
4. Reopen the client.

No server JAR replacement, service restart, installer or data migration is required for this client patch. Keep the working 1.2 server running. The 1.0 server remains separate.

Changes: scan statistics and current path are on separate rows like upload progress; hashing restores the bounded 1.0 concurrency (up to eight workers), reuses per-worker buffers and avoids unsupported Unix timestamp calls on Windows. Existing cache metadata and durable checkpoints remain compatible.

31 Java tests pass. Linux worker/scan measurements are included in the full package report; actual Windows cold-scan speed still needs your retest. An unchanged follow-up scan is verified to reuse cache with zero content reads.
