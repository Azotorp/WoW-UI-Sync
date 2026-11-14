> **1.1.1-alpha update:** See the current root README and ENGINEERING-REPORT.md. Normal server upgrades now support JAR replacement and automatic checkpointed startup migration; offline release activation is optional. Client settings and cache survive JAR replacement. Older instructions below describe the original 1.1.0 workflow.

# 1.0 → 1.1 upgrade and rollback

Run a staging rehearsal with a copy of your existing data first. Do not run old and new writers on the same repository. The current specification is DESIGN-1.1.md; the older root DESIGN.md is preserved for historical behavior.

1. Record the current binary version, data path, root login and certificate fingerprint. Keep the original JAR, scripts and configuration.
2. Stop the service with `systemctl stop wow-ui-sync`, or use the old stop wrapper and wait for the process to exit. Do not use setup for an upgrade.
3. Extract the new release ZIP to a separate directory. The new updater can target the old server launch directory via `-Dwowuisync.home=...`. Keep the old server JAR there so rollback can find it. The previous `wow-ui-sync-server.jar` name is supported by the new launcher.
4. Check the archive's SHA-256 through a trusted channel. Production distribution should be signed with an operator-approved Ed25519 release key. Pass a detached signature and trusted public key to the updater:

```bash
java -Dwowuisync.home=/opt/wow-ui-sync/server \
  -jar /path/to/new-release/server/wow-ui-sync.server.jar update \
  --data /var/lib/wow-ui-sync --from /path/to/wow-ui-sync-1.1.0-alpha.zip \
  --signature /path/to/release.zip.sig --trust-key /path/to/trusted-release-public.pem --dry-run
```

For this locally built unsigned alpha, explicitly opt in and provide the independently checked digest instead:

```bash
java -Dwowuisync.home=/opt/wow-ui-sync/server \
  -jar /path/to/new-release/server/wow-ui-sync.server.jar update \
  --data /var/lib/wow-ui-sync --from /path/to/wow-ui-sync-1.1.0-alpha.zip \
  --allow-unsigned-local --sha256 <checked-sha256> --dry-run
```

5. Repeat without `--dry-run` to stage, checkpoint, migrate, verify and activate the versioned release. The updater never fetches executable code or updates automatically. The data directory must be offline. A failed preflight does not activate the new binary.
6. Keep the stable new launch wrappers from the new `server/` directory in the old server launch directory; retain the previous JAR. They honor its atomic `active-release` pointer and load the selected version. The archive itself is directly runnable without a scripts subdirectory.
7. Start the service, check status/logs and run verify. Compare the TLS fingerprint with the recorded one. Log in with existing credentials, list the migration baseline and test a temporary restore before normal use. Upgrade clients to 1.1.

The migration is restartable per account/flavor. Original full-file objects and current manifests remain untouched; migrated snapshots can read legacy objects while new data uses account-scoped chunks. Checkpoints are in `migration-checkpoints/`; `migration-report.txt` records IDs and the TLS fingerprint. Do not delete checkpoints or old objects until recovery has been verified.

## Rollback

Stop the service. Use `rollback.sh --data ...` from the stable new launch directory (or invoke the new JAR's rollback command with the same home property). It verifies checkpoint checksums and previous binary availability before changing data, preserves post-upgrade metadata, restores the matching metadata checkpoint and previous release pointer. New snapshots/data created after the checkpoint remain preserved for manual recovery; they are not visible to an old v1 server. Do not point a legacy binary at a v2 database.

Activation is offline; starting and validating the production service is an explicit operator step. Network update discovery, scheduled update installation and unattended restart/health rollback are not implemented in this alpha.

## Signing a release

Keep the private release key outside the repository and deployment ZIP. With an existing operator-owned Ed25519 key:

```bash
openssl pkeyutl -sign -rawin -inkey release-private.pem \
  -in wow-ui-sync-1.1.0-alpha.zip -out release.zip.sig
```

Distribute the public verification key through a trusted channel. The release contains no signing private key, account database, TLS private identity, invite secret or user WoW files.
