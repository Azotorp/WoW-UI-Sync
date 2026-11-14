# Protocol 2 / repository 2 invariants

Protocol 2 preserves the framed control/data architecture and TLS pinning. Protocol 1 writers are rejected at HELLO. Frame payloads are at most 32 MiB; receive buffers share a 256 MiB global budget. Pack decoded content is at most 16 MiB, with each new object at most 8 MiB. Connection, handshake, per-IP, data-worker, password-hash and mail-queue budgets are bounded.

Manifest file entries contain `p` relative path, `s` full length, `h` whole-file SHA-256 and ordered `c` chunk references `{hash,size}`. The profile fingerprint remains SHA-256 of sorted `path|size|sha256\n` lines. Manifest metadata also has a SHA-256 sidecar. All files are fully verified when committing/reading for restore. Empty c is a legacy whole-file object, accepted from preserved migration data.

`gear-v1` uses a deterministic 256-entry Gear table seeded by 0x6a09e667f3bcc909, generated using SplitMix64. Rolling state is `(state << 1) + table[byte]` with 64-bit unsigned overflow. A boundary is taken at 8 MiB or after 512 KiB when the low 20 bits are zero. Average boundary target is 1 MiB after the minimum. State resets at every boundary. Chunks hash uncompressed plaintext. These bytes are custom WUS repository data, not a restic repository.

Objects retain the `WUSO` format: magic, u8 version 1, u8 codec (0 raw, 1 zstd, 2 deflate), big-endian u64 original length and payload. New chunks are immutable at `accounts/<uuid>/chunks-v2/<h0h1>/<h2h3>/<hash>`. Old objects remain at profiles/<flavor>/objects and are read without destructive conversion. Compression is sampled by transfer policy; compressed and raw network packs remain separate.

Snapshot JSON plus checksum sidecar live in each account/flavor's snapshots-v2 directory. Every snapshot embeds the full manifest and its flavor, parent, origin/device, creation time, installation ID and source type. A mutable head-v2 pointer is the authoritative shared sync head. The commit writes and fsyncs immutable snapshot content first, then atomically replaces the pointer. SQLite profile_state is a rebuildable display cache; status reads the authoritative manifest. Snapshot metadata never changes after commit; labels/pins and explicit deletion tombstones are separate. Recovery/manual snapshots do not move the shared head. Every retained snapshot is independently restorable.

Quota reservation serializes account/group checks across pending pushes. Usage is the union of uncompressed object identities retained or present on disk, plus unique pending object reservations; cancelled upload objects and partials cannot evade the quota. Physical usage is separate. Account-scoped dedupe permits bytes shared by flavors but every fetch must be referenced by a retained snapshot in the requested authorized flavor. Cross-account dedupe is absent.

An apply reconstructs every input into an operation-specific directory before changing live files. The journal contains before/target manifests and the confirmed recovery snapshot ID. Atomic per-file replacement and idempotent deletion are replayable. Destination content outside the recorded before/target states blocks replay. Whole-profile verification is required before removing the journal. New sync is blocked while a journal exists.

Repository operations use a server data-directory OS lock. A single in-process transaction monitor serializes snapshot head activation, quota reservations and GC. GC is offline, marks all retained snapshots/current heads and excludes active leases; unreferenced objects have a 48-hour grace period. Snapshot metadata and old objects remain available across migration until explicitly cleaned.

Database schema 2 adds persistent request cooldowns and verified-email/token state. Account IDs, credential rows, invites, role ownership and TLS identity are preserved. Migration checkpoints use SQLite VACUUM INTO and SHA-256-verified config/key/identity/manifest copies before schema upgrade.

## 1.1.1 metadata performance update

Protocol and repository versions stay 2; server SQLite stays 2. Client SQLite schema 2 adds `scan_cache_v2`, indexed by installation root and exact relative path, with precise nanosecond metadata/file identity, ordered chunk manifests and payload checksums. Existing client connection/pin/base/backup tables remain intact; no v1 hash row is promoted to a chunk manifest. Cache rows are advisory and transfers/destructive operations retain content verification. Unix ctime strengthens metadata validation; Windows lacks that provider attribute.

Status/account display uses memory summaries warmed from retained metadata before listener startup and refreshed on commits/deletions. Current status manifests are keyed by the authoritative head identifier; status does not enumerate history or rehash objects. Quota reservation, restore and integrity verification continue to use authoritative repository content. Cached display summaries are rebuildable and do not grant object access.

OpenJDK 21 WindowsFileAttributes returns null for `fileKey()` (see the OpenJDK source), so Windows uses positive creation time plus precise modification time and size as its advisory replacement hint. Unknown non-Windows identities stay untrusted. This does not weaken strict transfer/apply verification; ordinary metadata-preserving edits on Windows require a verified operation to detect.
