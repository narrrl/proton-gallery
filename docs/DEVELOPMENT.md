# Development and current implementation

The native Android gallery provides a combined phone/Proton Photos timeline, automatic phone-folder backup, Proton Photos album management, nested Drive galleries with a folder chooser, photo/video viewing, sharing, and Android picker integration. Rust owns the local catalog, indexing, Proton operations, and media services; Kotlin/Compose owns the interface and Android integration. The desktop UI, encrypted persistent remote previews, range-backed remote video playback, and full remote offline retention remain under development. The CLI is a headless diagnostic client with its own narrower account and storage behavior. See [Android development](ANDROID.md) for current app behavior and [the README](../README.md) for the user overview.

## Implemented

- Independent Rust workspace: `pg-core`, `pg-drive`, `pg-cli`, and `pg-mobile`. Registry SDK dependencies are pinned in the manifest and lockfile; no sibling checkout is a build dependency.
- Typed UniFFI mobile bridge, bidirectional keyset paging, durable content-addressed device imports, and forward-only schema migrations, typed source scopes, nested album trees, and stored media shapes. Kotlin supplies native Compose UI and platform media/picker integration.
- A bounded async catalog facade over a dedicated SQLite thread. WAL, forward-only schema initialization, application identity checks, and refusal to open a newer or unrelated database.
- Persistent source membership and timeline assets with full-key pagination, including tied timestamps. Queries can run while a remote scan is waiting for its next page.
- Atomic metadata-page/checkpoint commits, restart/resume, scan generations that reject stale workers, and cooperative cancellation. A scan does not infer deletion from absent results.
- A Proton folder adapter, direct-child media indexing, interactive CLI login with TOTP and dual-password handling, and revision-pinned SDK block reads. Credentials remain in memory and errors do not print raw remote responses.
- Range mapping over actual block sizes, EOF handling, bounded reads, shared fetch locks, and bounded disk/network operations.
- AES-256-GCM local block objects with random nonces, account/asset/revision/block identity authenticated as associated data, opaque filenames, synced atomic publication on Linux, and a key-check marker that rejects the wrong store key.
- Single-revision offline retention with persisted intent/descriptors, completion after durable publication, interrupted-download reuse, and a local-only reader that does not construct an SDK reader or request account credentials.
- Deterministic account-free fixtures, a CLI query benchmark, and fault/cancellation/restart tests.

## Run locally

Requires Rust 1.96 or later as declared by the workspace/SDK. Current validation uses the installed Rust 1.99.1 toolchain on Linux; the declared minimum and other targets still require their own CI validation.

From `proton-gallery`:

```bash
cargo run -p pg-cli -- --help
cargo run -p pg-cli -- --data-dir /tmp/proton-gallery-demo demo --count 10000
cargo run -p pg-cli -- --data-dir /tmp/proton-gallery-demo list --limit 20
cargo run -p pg-cli -- --data-dir /tmp/proton-gallery-demo stats
cargo run -p pg-cli -- --data-dir /tmp/proton-gallery-demo benchmark --queries 200
```

`list` returns JSON with `items` and an opaque `next` token. Pass that token with `--after` and preserve the same `--source` filter. `sources` lists source IDs for filtered queries. The demo uses repeated timestamps to exercise the pagination tie-breakers and never allocates the entire synthetic library.

Rerunning `demo` resumes a pending scan and skips an already completed scan. If changing `--count` or intentionally refreshing, use `--restart`. To exercise visible incremental indexing, add `--delay-ms 50` and run `list` in a second process during the scan. Ctrl-C checkpoints at committed page boundaries.

## Offline demonstration

```bash
cargo run -p pg-cli -- --data-dir /tmp/proton-gallery-offline offline-demo --interrupt-after 1
cargo run -p pg-cli -- --data-dir /tmp/proton-gallery-offline offline-demo
cargo run -p pg-cli -- --data-dir /tmp/proton-gallery-offline offline-demo --read-only
```

These are patterned synthetic bytes, **not playable media**. The fixture uses a public fixed key in a dedicated synthetic namespace; it must never be used for real account media. Core storage accepts a caller-supplied random key, but OS key wrapping and real-media CLI pinning are not connected yet. The read-only invocation creates no remote reader and validates cross-block and tail reads from stored descriptors/objects.

The first invocation demonstrates a partially cached range followed by an interrupted pin; the second reuses those blocks. The third, in a new process, reports zero remote block fetches. An interrupted pin never reports complete. If the key marker is missing from a nonempty store, or a different key is supplied, opening fails rather than overwriting retained data.

## Proton indexing and galleries

The current [product direction](PRODUCT.md) puts normal Photos first and Drive folders in nested galleries. Additional commands:

```bash
cargo run -p pg-cli -- scan-photos --username you@example.com
cargo run -p pg-cli -- scan-gallery --username you@example.com --folder 'VOLUME~NODE'
cargo run -p pg-cli -- albums
cargo run -p pg-cli -- albums --gallery GALLERY_ID --parent ALBUM_SOURCE_ID
```

`scan-gallery` recursively discovers folders and indexes direct media in each, preserving parent relationships. `scan-photos` uses Photos-aware SDK methods and server capture dates. Use `--restart` to refresh completed scans. Neither command downloads originals or proves Photos streaming.

The original direct-folder diagnostic remains available:

```bash
cargo run -p pg-cli -- drive-folders --username you@example.com
cargo run -p pg-cli -- scan-drive --username you@example.com --folder 'VOLUME~NODE'
cargo run -p pg-cli -- sources
cargo run -p pg-cli -- list --source 'SOURCE_ID' --limit 20
```

Use `--data-dir` consistently if choosing a custom library location. Without it, the platform application-data directory is used. Passwords and authenticator codes are entered through hidden prompts; do not put them on command lines or in `.env` files. Each Proton command signs in for that invocation. Security-key-only sign-in and CAPTCHA challenges produce explicit unsupported-flow errors. The CLI does not save sessions, access a keyring, upload, replace, trash, or delete media. The SDK may initialize an absent My Files root for a new account.

The folder UID comes from `drive-folders`; leaving `--folder` out uses My Files. Only direct media children are indexed. The SDK currently returns a complete child-ID list before node materialization, so initial listing latency is still proportional to that ID enumeration. Node metadata is then decrypted/committed in pages of 100. The adapter reports `incremental_listing: false`. SDK paging is the next upstream integration task; this adapter deliberately does not duplicate private Proton endpoints.

A saved scan cursor includes a fingerprint of the child-ID sequence. If folder contents/order change between interrupted invocations, the adapter refuses the stale continuation and asks for `--restart`. Refresh is non-destructive; removal/event reconciliation is not implemented yet, so deleted or moved items may remain in the local catalog until that feature lands.

## Validation

```bash
bash scripts/check.sh
```

Equivalent offline checks after dependencies have been fetched:

```bash
cargo fmt --all -- --check
cargo clippy --workspace --all-targets --all-features --locked --offline -- -D warnings
cargo test --workspace --all-features --locked --offline
```

Rust tests use isolated temporary directories, synthetic sources, and no credentials/network. Android has separate build, lint, and Robolectric checks; see [validation](VALIDATION.md) for actual results. Live Proton account sign-in, Windows filesystem behavior, remote Android media/codec behavior, and optimized release builds remain unverified. Native sign-in, Keystore session persistence, Photos metadata sync, and memory-only remote previews are implemented.

The [validation record](VALIDATION.md) includes actual checks and an indicative 100,000-item local query measurement. It is separate from the performance targets in the roadmap.

## Remaining boundaries

There is no priority scheduler, memory ring, eviction/GC, disk-space reservation, secure key wrapping, durable transfer lease owner, folder retention, event replay, or authenticity-verification claim yet. Offline completion currently means local original-byte completeness/integrity for a selected revision; it does not claim a verified Proton content-manifest signature. Retained descriptors/intent live in SQLite, while existing block files provide the resume checkpoint. There is no independent background download service.

Source-filtered pages now seek the `memberships_timeline` covering index and join asset payloads by primary key. A query-plan regression test rejects a temporary sort and verifies shared timestamp propagation. The historical latency measurement covers the earlier global query, not a new Android/album performance claim.

The app-private metadata database is plaintext. Unix file/directory permissions are restricted for new catalog/object files, but Windows directory ACL setup and full power-loss publication semantics need platform validation. Stored objects are authenticated; startup does not hash/scan every retained block. Missing/corrupt objects fail on read and can be repaired when an online source is attached.

Next integration work: incremental SDK timeline delivery, desktop secret-store adapters, encrypted persistent remote previews, shared priority scheduling, range-backed Android video playback, and a desktop thumbnail grid. The [roadmap](IMPLEMENTATION_PLAN.md) retains the device acceptance gates.
