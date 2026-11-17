# Validation — 2026-11-17

## Linux checks

- `cargo fmt --all -- --check`
- `cargo clippy --workspace --all-targets --all-features --locked --offline -- -D warnings`
- `cargo test --workspace --all-features --locked --offline`: **39 tests passed** (2 smart-grid geometry, 9 byte/storage, 13 catalog/indexing/migration, 4 library hierarchy/account-scoping, 2 device import, 3 SDK-model conversion, 5 session/vault/refresh tests, 1 mobile facade).
- `cargo build -p pg-cli --locked --offline`
- Cargo metadata confirms both registry SDK crates are **0.12.0**. No resolved package declares an MSRV above the workspace's Rust 1.96 minimum; this does not replace compiling with that minimum toolchain.

Tests cover committed-page visibility while the next remote page is blocked, tied-date pagination, source filters and account boundaries, failed/cancelled scan recovery, stale scan generations, revision isolation, exact irregular-block ranges, overlapping consumers, cancellation, equal-length corruption, wrong keys, cache-write failure, and interrupted offline retention across catalog/store reopening.

The Android slice adds reverse keyset traversal after dropped pages, forward schema migration preserving existing sources, staging confinement, empty/nonmedia refusal, content deduplication, original repair, durable metadata reopening, typed mobile errors, nested/overlapping galleries, cycle/account boundary rejection, Photos capture dates, source-index seeks, and shared timestamp propagation.

## Executable smoke checks

Run against isolated temporary app-data directories, without credentials or network access:

1. Index 100,000 synthetic assets; restart the CLI and query two adjacent 13-item pages. Verify no repeated IDs and the persisted item count.
2. Query all 100,000 assets in 500 keyset pages of 200. Reject a zero-sized page request.
3. Inspect SQLite's deep-page plan: `SEARCH a USING INDEX assets_timeline ((sort_time,account,volume,node)<(?,?,?,?))`, with a covering membership lookup. This confirms an index seek rather than walking all prior pages.
4. Interrupt the synthetic offline demo after one pin-block completion. Confirm `complete: false` and refusal to open it as a fully retained revision.
5. Resume in a fresh process. Only the two remaining blocks are fetched; previously committed blocks are reused.
6. Open again with `offline-demo --read-only` in another process. Validate four ranges, including cross-block and tail reads, with **zero remote block fetches**.

Synthetic fixture length is 491,518 bytes over four irregular blocks. The initial range spans two blocks; both remain reusable after interruption. The bytes are deterministic test data, not playable media.

## Indicative local measurements

These measurements were recorded for the initial core slice before Android integration; they are retained as a baseline, not new Android measurements.

Host: AMD Ryzen 7 PRO 3700U, 8 logical CPUs, Linux x86_64 (`7.3.4-zen2-2-zen`), Rust 1.99.1. Development build with dependencies optimized at level 2 and workspace code unoptimized. The filesystem cache was warm from indexing; a Windows cross-compilation was also running. This is a smoke measurement, not a controlled release benchmark or a device-wide responsiveness claim.

| Operation | Observed |
| --- | --- |
| Index 100,000 synthetic assets | 10,340 ms |
| 200-item local page, p50 | 3,008 µs |
| 200-item local page, p95 | 6,574 µs |
| 200-item local page, p99 | 8,530 µs |
| 200-item local page, maximum | 9,671 µs |

Query times include the Rust catalog request, SQLite read, JSON decoding, and DTO construction. They exclude rendering, FFI, thumbnail loading, original decoding, real Proton latency, initial login, and media playback.

Reproduce with `demo --count 100000 --restart`, followed by `benchmark --queries 500 --limit 200`, using the same `--data-dir` for both commands.

## Platform boundaries

The shared core passed both cross-target compilation checks:

```bash
cargo check -p pg-core --target x86_64-pc-windows-gnu --locked --offline
cargo ndk -t arm64-v8a check -p pg-core --locked --offline
```

These checks include the bundled SQLite C build for each target. No Windows executable has been exercised. The signed Android app has now been installed and launched on a Pixel 6 (API 37); this is a startup smoke check, not a codec/streaming performance test. The Proton adapter has been compiled and its model conversion tested on Linux, but no live account sign-in, folder enumeration, or real media stream was performed.

## Android application checks

The pinned project build script passes:

```bash
JAVA_HOME=/path/to/jdk-17 bash scripts/build-android.sh check
```

This runs `testDebugUnitTest`, `lintDebug`, and `assembleDebug` using Gradle 8.14.5, AGP 8.13.2, Kotlin 2.2.21, Android SDK 36/build tools 37.0.0, and NDK 30.0.16248370. The app targets API 36 with minimum API 31.

- Both arm64-v8a and x86_64 Rust libraries compile and package in the APK, including bundled SQLite.
- **8 Android tests pass**, with no skips: 7 Robolectric interaction/layout tests and 1 generated Kotlin/JNA → actual host Rust-library integration test. The latter checks import, typed records/errors, bidirectional cursors, file paths, and reopening. It does not substitute for loading an Android ABI on a device.
- Layout/interaction coverage includes a 412×892dp phone, 800×1100dp tablet, and a 320×640dp phone at 1.5× text scaling. Actions cover importing, opening/closing a photo, nested Drive albums and parent navigation, appearance controls, and keeping device fixtures out of the default Photos view.
- Eight layout screenshots render; populated grids use procedural JPEG fixtures. Phone/tablet light/dark layouts were visually inspected. These are fixture media, not shipped content or user photographs.
- Android lint passes after correcting Media3 opt-in calls, backup/data-transfer exclusions, and preference editing. Dependency update notices are informational; no lint baseline hides errors.
- Native ELF load segments for the Rust and JNA arm64 libraries align to 16KiB. APK alignment passes `zipalign -c -P 16 4`, and `apksigner verify` confirms the development signature.

The full extended icon dependency was replaced with a licensed twelve-vector subset plus the small core icon dependency. No release, frame-time, physical codec, provider, or real Proton performance claim follows from these checks. The development APK is at `android/app/build/outputs/apk/debug/app-debug.apk`; screenshots and further boundaries are documented in [Android](ANDROID.md).


## Persistent testing certificate

The device-testing key is RSA-4096 in PKCS12 format, generated under `~/Keys/proton-gallery` with owner-only access. Its public certificate SHA-256 is:

```text
ba9bafa041e560e429a737bb6949333bef62f15434823d0db5578ab243a79f10
```

The signed variant has package `io.narl.protongallery.testing`. It is intended for device testing and keeps signing secrets outside the repository. Build and signing instructions are in [Android](ANDROID.md).


## Signed installation smoke check

- `app-device.apk` builds with the persistent RSA-4096 testing key; `apksigner verify` passes and its certificate digest matches the keystore's exported certificate.
- The APK is `io.narl.protongallery.testing`, version `0.1.0-testing`, minimum API 31, target API 36, with arm64-v8a and x86_64 libraries. ZIP/native alignment is compatible with 16KiB pages.
- USB installation on a Pixel 6 running API 37 succeeded. `am start -W` returned `Status: ok`; the app process remained running and its filtered startup warning/error log contained no relevant errors or fatal exceptions.
- One reported warm activity launch took 200ms. This excludes initial installation/cold startup and is not a gallery frame-time or network benchmark.

Real-account sign-in and Photos thumbnail delivery, remote video, physical-device media decoding, offline retention across restarts, and scrolling performance still require their own checks. The native account flow and memory-only remote thumbnail path are now implemented.
