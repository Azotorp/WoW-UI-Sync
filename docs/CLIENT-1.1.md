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
