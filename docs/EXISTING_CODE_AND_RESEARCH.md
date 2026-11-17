# Existing code and research

Inspected 2026-11-17. This records source observations and proposed reuse, not a certification that the existing code meets the gallery's requirements. No live Proton account or remote file was accessed for planning.

## Local source baseline

| Repository | Inspected HEAD | Manifest observation |
| --- | --- | --- |
| `proton-sdk-rs` | `0522c3ca89e19ed0973cbfbe82d472dbf10eb3fb` | Workspace 0.12.0, Rust 2024, declared MSRV 1.96 |
| `proton-stream` | `a475ad35752e9256a3111c1aa1c27e3ff41859c6` | Workspace 2.0.1, SDK dependencies 0.9.0 |
| `proton-drive-linux` | `e9d6d4a907aa1a3771588b84632c14377fd66cca` | SDK dependencies 0.12.0 |

Source observations are from the working checkout, which may include changes beyond HEAD. Versions above are local declarations, not verification of the latest registry release. Some README/comments contain older versions; use implementation/manifests and test the chosen build.

Paths below are relative to this planning directory, so they resolve in the current workspace. They are research references, not future release dependencies.

## Reuse map

| Capability | Existing source | Reuse / qualification |
| --- | --- | --- |
| Account authentication and protected session persistence | [`pstr-core/account.rs`](../../proton-stream/crates/pstr-core/src/account.rs), SDK account/session modules | Port behavior behind the gallery's own credential namespace; save token rotation and test platform stores |
| Independent revision blocks and random reads | [`revision.rs`](../../proton-sdk-rs/crates/proton-drive-rs/src/revision.rs), [`client.rs`](../../proton-sdk-rs/crates/proton-drive-rs/src/client.rs) | `open_revision`, `open_revision_at`, `read_at`, actual block sizes; core streaming foundation |
| Storage URL vs account-session recovery | [`transport.rs`](../../proton-sdk-rs/crates/proton-drive-rs/src/transport.rs) | Reuse SDK behavior; storage blobs use their storage token and do not carry account bearer credentials |
| Byte mapping, memory ring, reader cache, read-ahead, request deduplication | [`pstr-stream/src`](../../proton-stream/crates/pstr-stream/src) | Strong starting point; generic layer must be separated from `pstr-core` and updated to selected SDK |
| Disk block cache crash ordering | [`pstr-stream/disk.rs`](../../proton-stream/crates/pstr-stream/src/disk.rs) | Existing implementation stores decrypted blocks plus length sidecars; retain publication lessons, add authenticated local storage and durable retention references |
| Single-flight cancellation | [`single_flight.rs`](../../proton-stream/crates/pstr-stream/src/single_flight.rs) | Existing leader-drop cleanup is useful; independent consumer lifetime/priority promotion still needs a gallery contract |
| Desktop video callbacks and rendering | [`pstr-player/src`](../../proton-stream/crates/pstr-player/src), [`pstr-app/video.rs`](../../proton-stream/crates/pstr-app/src/video.rs) | Adapt player/opaque handle design, audit native binaries/licenses and Wayland behavior |
| UI threading and pacing | [`ARCHITECTURE.md`](../../proton-stream/docs/ARCHITECTURE.md), [`pacing.rs`](../../proton-stream/crates/pstr-app/src/pacing.rs) | Reuse async engine/event pattern; benchmark a photo grid rather than assuming movie-shelf performance transfers |
| Android bridge and native player | [`pstr-android/src/lib.rs`](../../proton-stream/crates/pstr-android/src/lib.rs), [`android/app/src/main`](../../proton-stream/android/app/src/main) | Prior art for lifecycle, cancellation and player integration; current bridge contains app-specific offline code to move into shared core |
| Persistent offline downloads | Same Android bridge and [`DownloadCoordinator.kt`](../../proton-stream/android/app/src/main/java/io/narl/protonstream/download/DownloadCoordinator.kt) | Existing `.part`/resume and progress behavior is useful, but Kotlin WorkManager state must not become a separate authoritative gallery queue |
| Persistent photo timeline | [`db/photos.rs`](../../proton-drive-linux/crates/pdfs-core/src/db/photos.rs), [`db/albums.rs`](../../proton-drive-linux/crates/pdfs-core/src/db/albums.rs) | Reuse schema lessons, not the daemon database; improve transient vs permanent thumbnail failure semantics |
| Folder pinning | [`db/pins.rs`](../../proton-drive-linux/crates/pdfs-core/src/db/pins.rs), [`cache.rs`](../../proton-drive-linux/crates/pdfs-core/src/cache.rs) | Reuse recursive policy/eviction lessons; model overlaps, revision replacement, traversal checkpoints, and durable completion explicitly |
| EXIF capture times and photo imports | [`exiftime.rs`](../../proton-drive-linux/crates/pdfs-core/src/exiftime.rs), [`takeout.rs`](../../proton-drive-linux/crates/pdfs-core/src/takeout.rs) | Reuse parsing/fixture ideas; preserve uncertainty for timezone-less dates rather than asserting UTC |
| Photos/albums/thumbnail/upload APIs | [`photos.rs`](../../proton-sdk-rs/crates/proton-drive-rs/src/photos.rs), SDK `client.rs` | Existing Photos client, album methods, uploads and per-item thumbnail outcomes; account Photos and ordinary folders remain distinct routes |
| Event polling/cache invalidation | [`event_manager.rs`](../../proton-sdk-rs/crates/proton-drive-rs/src/event_manager.rs) | Useful API/cache behavior; catalog requires its own atomic applied cursor or durable inbox acknowledgement |

## Gaps to resolve before implementation claims

1. **Pagination:** Photos `enumerate_timeline()` returns a collected `Vec`; folder enumeration also exposes collected results. Verify which methods can emit pages before completion. Add first-page/stream APIs at SDK level as needed. Wrapping a full-library future in a UI task does not fix first-use latency.
2. **Atomic event application:** current manager broadcasts then persists its polling cursor, without an application transaction acknowledgement. A restart can miss catalog updates if the gallery treats that cursor as applied. Use explicit durable application.
3. **Authenticity outcomes:** revision range reads explicitly skip whole-content-manifest verification. `download_file_to` invokes verification, but warning/status behavior and Photos/public-link differences need auditing. A pin should not require redownloading every cached block solely to obtain a verification claim; expose verification over a completed local block set.
4. **Offline local opens:** existing online `RevisionOpener` behavior must not be a prerequisite for the gallery's local retained reader. Persist all descriptors needed to read without session/network access.
5. **Resource control:** the SDK already has transfer permits and its own concurrency. Gallery scheduling cannot assume a top-level task semaphore bounds actual block traffic, memory, or priority. Measure and add upstream hooks if necessary.
6. **Storage:** existing `pstr-stream` disk cache is plaintext and uses size checks; it is a disposable cache, not the proposed authenticated retained store. Do not describe it as an already encrypted offline implementation.
7. **Uploads:** streaming input exists, but draft identity, partial resume, uncertain commit recovery, and streaming-reader restrictions need explicit capability tests before the gallery promises them.
8. **Mobile media:** the existing Android mpv path offers reuse; Media3 may offer a smaller native integration for phone video. Selection requires real codec, battery, seek, packaging, and FFI measurements.
9. **Licensing:** `proton-stream` documents MIT shared Rust/desktop source and GPL-3.0-or-later Android/iOS applications and combined native distributions. Do not copy its Android bridge/UI under an assumed MIT license. Inspect every copied file and selected native library build.
10. **Photos range-reader routing:** `open_revision_inner` currently calls ordinary `get_link_details` and `resolve_parent_key`, whereas Photos metadata/thumbnail methods use Photos-aware routes. Do not assume ordinary Drive random access already works for Photos videos. Test it explicitly and add Photos-aware revision opens in the SDK if required; whole-photo downloads are not an adequate substitute for video streaming.

## Official references checked

- [Android DataSource interface](https://developer.android.com/reference/androidx/media3/datasource/DataSource): position-based media input contract; motivates a thin Rust range-reader adapter.
- [Media3 supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats): container support, seek limitations, and device decoder dependence; does not guarantee every phone supports every original.
- [Android transfer API selection](https://developer.android.com/develop/background-work/background-tasks/data-transfer-options): separates deferrable work from long user-initiated transfers.
- [User-initiated transfer jobs](https://developer.android.com/develop/background-work/background-tasks/uidt): API 34+ option and launch/notification conditions; execution remains OS-controlled.
- [Long-running WorkManager workers](https://developer.android.com/develop/background-work/background-tasks/persistent/how-to/long-running): Android 16 quota implications; do not mechanically reuse the old download coordinator.
- [UniFFI async internals](https://mozilla.github.io/uniffi-rs/latest/internals/async-ffi.html): Rust future lifecycle and cleanup; pinned-version integration tests must prove application task cancellation too.
- [mpv official embedding examples](https://github.com/mpv-player/mpv-examples/tree/master/libmpv): custom stream and embedding examples, complementing the existing integration.
- [SQLite WAL](https://www.sqlite.org/wal.html): concurrency/checkpoint tradeoffs; supports the local catalog design with bounded transactions and off-thread maintenance.
- [Cargo dependency overrides](https://doc.rust-lang.org/cargo/reference/overriding-dependencies.html): local SDK experiments can use uncommitted overrides, while release inputs must remain independently resolvable.

These references support individual technical choices. Performance budgets, frontend recommendations, offline policies, and roadmap ordering are design proposals inferred from the task and source inspection, not upstream guarantees.
