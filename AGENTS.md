# Development rules

Read docs/RELEASE-STATUS.md, docs/FORMAT-2.md and docs/DESIGN-1.1.md before extending 1.1. The original DESIGN.md describes v1 and is retained as history.

Preserve account identifiers, TLS identity/pins, credentials, legacy objects and local WoW files. Never run setup against existing data. Production migration/deployment requires operator approval; work on fixtures or isolated data copies during development.

A changed upload must verify actual object lengths, chunk hashes and full-file hashes before committing an immutable snapshot. The authoritative sync head changes only after snapshot durability. Recovery/manual snapshots must not move the head. Serialize account/group reservations and count cancelled uploads. Keep GC offline and protect retained, pinned and active recovery references.

Keep flavor identities exact on every snapshot and chunk access. Treat Era/Hardcore/SoD as one installation when they share the same directory. Local installation IDs and baselines are distinct for independent roots.

Never introduce new persistent Interface_* or WTF_* backups. A destructive apply needs a committed server recovery snapshot, immutable verified staging and a durable replay journal. Block new sync while a journal is pending. Unreadable files and unsafe names block a destructive scan.

Run ./compile.sh and ./package.sh for release changes. Include security, migration and recovery regressions for changes to those paths. Verify archive shape, executable permissions, versions and checksums. Never commit generated JARs, test private keys, account databases or signing secrets.

Keep docs explicit about implemented, tested, not tested, deferred and known issues. A successful compile is not proof of deployment safety.

For 1.1.1-alpha also read README.md and ENGINEERING-REPORT.md. Normal server migrations are automatic under the existing data lock; do not reintroduce mandatory setup or release-directory switching. Routine scans may use checksummed metadata hints, but push/destructive verification and hash-verified transfer staging must remain. Run `python3 tools/test_jar_updater.py` on Linux when changing the updater.
