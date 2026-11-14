> **1.1.1-alpha update:** See the current root README and ENGINEERING-REPORT.md. Normal server upgrades now support JAR replacement and automatic checkpointed startup migration; offline release activation is optional. Client settings and cache survive JAR replacement. Older instructions below describe the original 1.1.0 workflow.

# WoW UI Sync 1.1.0-alpha — client

Requires Java 21 or newer. Run `java -jar wow-ui-sync.client.jar`.

1. Open Settings. Choose a WoW root, or a specific recognized flavor directory. Additional roots and flavor paths may be entered one per line. A new game installation is recognized before WTF/Interface exists.
2. Open Connect → Join with invite code. Paste the whole invite, enter a username and leave the key path empty to generate a device-specific Ed25519 key. Password accounts and existing key files are also supported. Successful registration signs you in automatically.
3. Compare the TLS fingerprint with your operator's value before trusting a new server. An invitation carries the expected fingerprint. A changed fingerprint blocks access.
4. Choose the account and installation. Each flavor has its own server head. Retail, Classic, Anniversary, Era, Forever and PTR/Beta are separate. Era/Hardcore/SoD share `_classic_era_`.
5. Push uploads a reviewed mirror and creates an immutable snapshot. Pull offers addon/data selection and a review. Close WoW before applying changes.
6. Before any pull/restore/delete, your local state must upload successfully as a recovery snapshot in **your own account**. That recovery snapshot does not move the shared sync head. If the server is unavailable or quota is exhausted, local files are left untouched.
7. Snapshots lists history for the selected remote account/flavor. Compare, verify, label, pin, export a manifest, restore all files or select individual files. Restore leaves later history intact. Inspect recovery snapshots in your own account when pulling another account.
8. If an apply is interrupted, open Recovery before any new sync. Resume uses verified transient staging. Restore reconnects to the recorded original account and snapshot. Local edits made since the interruption are detected and stop automatic replay.

`WTF/Config.wtf`, gamepad configuration, backup/temp files and known machine-specific files are excluded by default. Exclusions operate at file level. Inspect settings stored inside addon-owned Lua files before sharing them across computers.

No new Interface_* or WTF_* backup directories are made. Legacy backups remain visible in a read-only inventory. Existing backups and DB records are preserved.

Password saving uses the Windows user DPAPI vault, macOS Keychain, or Linux Secret Service (`secret-tool`). If the vault is unavailable, use a generated key or prompt at login. New passwords are never saved in SQLite. Legacy plaintext connections prompt you to migrate or delete saved passwords; existing historical database backups may still contain the old values, so protect those backups.

Generated private keys are restricted to your OS account. They are not sent to the server. Keep an encrypted recovery copy or arrange an administrator credential reset for another device. Alpha currently has one account key; independent multi-device key enrollment is deferred.

Client metadata lives in `wow-ui-sync-data` next to the JAR, or a directory selected by `-Dwowuisync.data=/absolute/path`. Keep it outside the WoW installation. Transient chunk staging is separate from the game files and is cleared on startup/shutdown when no apply journal needs it. It is not a permanent full-folder backup.

This is alpha software. Read RELEASE-STATUS.md in the source repository for tested platforms and limitations. The server operator can read stored content; end-to-end encryption is not implemented.

The original artwork is embedded in the JAR. Settings can disable it, adjust its strength or select high contrast. Manual custom flavor identifiers require matching `additionalFlavors` entries on the server; recognized flavors keep their registry identities.

Legacy backups can be imported into a verified server snapshot from Legacy backups. The imported copy is under your own account; original folders remain untouched. A normal restore can then use that snapshot.


## 1.1.2 client scanning and update

Replace only the client JAR with the client-only package; keep its existing data directory, settings, keys, TLS pins, credentials, installation IDs and baselines. The existing 1.1.1-alpha server is compatible (protocol/repository/schema stay 2); no server update or setup is required.

The client asks for the selected account/flavor's shared head before automatic scanning. When absent: “No synced files yet. Push this installation to create the first snapshot.” Local content remains unscanned; historical/recovery snapshots are independent. Existing zero-file snapshots are scanned normally. Errors are not an empty-server result. Explicit Push probes again, performs strict verification, rechecks status and uses server compare-and-swap at begin/commit. Pending journals still require Recovery first.

Modes: **First cache fill**, **Cached check**, and **Strict verification**. Discovery is indeterminate; metadata comparison, content hashing/chunking, cache persistence, upload staging, transfer and verification have their own phase labels. Counts show files checked/total, cache hits, files hashed, actual source bytes read (including retries), elapsed time and mode. Hashing time sums worker work and can exceed wall time. Logs report miss reasons: absent entry, changed metadata, recent timestamp, unavailable identity, corrupt entry or strict verification.

Every stable verified file becomes an advisory cache hint. The coordinator saves at most 128 changed entries per transaction. Cancel and normal window-close flush remaining completed results, including workers that finished just before cancellation. No full manifest, deletion inference or baseline is published after an incomplete scan. A forced kill/power loss may lose the current batch; the next scan rehashes those files. Discovery/metadata comparisons still run after restart, but trusted unchanged file bytes are not reread. Windows uses the existing precise creation-time fallback for null file keys. Recent/unknown metadata is untrusted; metadata-preserving edits can evade advisory checks, so strict operation checks remain mandatory.

TSM's external application may update `TradeSkillMaster_AppHelper/AppData.lua` with WoW closed. Discovery metadata is refreshed before hashing, and an unstable file gets one retry. If it keeps changing, pause the external updater and retry. Successfully verified other files remain cached; the app does not silently omit TSM data.

Default: **one** content worker, avoiding per-file thread handoffs. To evaluate parallel hashing, launch `java -Dwowuisync.scanWorkers=2 -jar YOUR_EXISTING_CLIENT.jar` or use `4`. `1` is the sequential fallback for HDD/network drives. Values clamp to 1–4; only a worker-sized task window is outstanding. A separate 64 MiB content-buffer semaphore limits live file/chunk buffers, even for arbitrarily large files. JVM/metadata/cache memory is additional. A large CDC worker reserves 17 MiB; at most three such workers run together. SQLite stays on the coordinator. Results and fingerprints are deterministic regardless of completion order. Cancellation interrupts/drains workers before returning; pause checkpoints are honored by operation scans.

Scans never create upload staging. Only requested missing objects are generated/verified during upload preparation; all uploads retain server full-file/hash/length verification. Review and precommit strict scans, recovery snapshots, pre-apply checks, immutable reconstruction, journals and post-apply verification remain. No new persistent Interface/WTF backup folders are created.

See the engineering report for exact measurements and limitations. Native CI checks correctness/packaging, not desktop performance, antivirus latency or physical cold-disk throughput.
