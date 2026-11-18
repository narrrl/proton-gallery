# Validation

## Iced desktop completion — 2026-11-18

The desktop implementation covers account access, Photos browsing and still-photo viewing.
The Compose desktop skeleton is retained while platform/account/accessibility acceptance
remains open. The following checks ran against this checkout, including the existing Android
and desktop work; no real account credentials were used.

### Build and regression checks

| Check | Result |
| --- | --- |
| `env CARGO_NET_OFFLINE=true bash scripts/check.sh` | PASS: formatting, workspace/all-target/all-feature Clippy with warnings denied, then 108 tests passed, 2 live-account diagnostics intentionally ignored |
| `cargo build -p pg-desktop --locked --offline` | PASS: actual Linux Iced executable |
| `cargo check -p pg-core -p pg-drive -p pg-app -p pg-desktop --all-targets --target x86_64-pc-windows-gnu --locked --offline` | PASS: Windows compilation/type checking, including test targets; no Windows execution or executable link claim |
| JDK 21: `/usr/bin/gradle8 -p android --no-daemon testDebugUnitTest lintDebug assembleDebug` | PASS: both native ABIs rebuilt and packaged; see forced execution below |
| `python3 -m py_compile scripts/desktop-smoke.py`, `git diff --check` | PASS |

The final Gradle invocation reused unit-test outputs. A second invocation with
`--rerun-tasks -x buildRust` explicitly reruns Kotlin compilation, Android unit tests,
lint and APK assembly against the native libraries just rebuilt for both ABIs. Result:
**PASS, 36 tests executed, zero failures/errors/skips**, plus lint and debug APK assembly.
This includes the Kotlin/JNA bridge integration tests. Skipping `buildRust` here avoids
rebuilding the already-verified native outputs; it does not substitute for the preceding
native build.

New coverage includes queued sync messages after UI sign-out, stale page/thumbnail/viewer
completion bookkeeping, failed/missing
thumbnail and page suppression until Refresh, challenge credential cancellation, failed
keyring deletion and late token refresh, cleanup surviving restart, original-cache account
authorization, streaming byte limits, partial-file cleanup on cancellation/publication error,
pre-allocation image limits, viewport/focus anchoring, and the placeholder/image widget-state
transition found during actual runtime testing. The request stress test uses 100,000 records
and 500 rapid scroll/viewer replacements. Cache accounting exercises 100,000 distinct tiny
thumbnails through a small byte budget, keeping the recently touched item.

### Linux runtime checks and measurements

The actual Iced executable was exercised with 100,000 synthetic catalog records and encoded
fixture media, not a separate demo renderer. The helper drives three repeated cycles of
25 page-down moves, Home/End jumps, 50 viewer navigation steps per cycle, plus error retry
and Refresh. It verifies that normal window closure preserves the explicitly supplied data
directory. X11 additionally uses mouse-wheel zoom/drag pan, a 640×480 physical window at
125% application scaling, and temporary light/dark preference changes with restoration.
A longer Wayland run checks process-memory behavior as browsing continues.

Runtime evidence and numerical summaries are archived in `docs/desktop-validation/`;
selected app-only screenshots are in `docs/images/desktop-*`. The first final Wayland
run completed with exit code 0 and preserved its explicit directory: first outline in
20.990 seconds (including fixture creation), peak RSS 308.9 MiB, redraw intervals
p50 16.665 ms / p95 19.556 ms / maximum 164.282 ms, widget construction p95 0.988 ms,
peak three page reads and twelve thumbnail tasks. Additional final-run results follow below.

| Final run | Outline ready (fixture indexing included) | Peak RSS | Redraw interval p50 / p95 / max | Widget construction p95 | Outstanding page / thumbnail peak |
| --- | --- | --- | --- | --- | --- |
| Wayland, three cycles | 20.990 s | 308.9 MiB | 16.665 / 19.556 / 164.282 ms | 0.988 ms | 3 / 12 |
| Wayland, six cycles | 26.504 s | 327.4 MiB | 16.863 / 33.172 / 340.149 ms | 1.386 ms | 2 / 12 |
| X11/Xvfb, 125%, three cycles plus theme changes | 30.366 s | 339.7 MiB | 34.451 / 76.449 / 1147.367 ms | 0.648 ms | 3 / 12 |

All three runs exited normally with code 0 and preserved the explicit directory. Screenshots
were visually inspected for grid focus, unavailable previews, still viewing, retry/error state,
zoom/pan, the small window and light/dark changes. X11 preference samples changed from
RGB (150,150,150) to (136,138,144), and the screenshots confirm the full light/dark palette
change. The original desktop preference was restored. The six-cycle Wayland RSS samples
were 207.7, 270.0, 304.7, 316.6, 323.0 and 327.4 MiB: growth slows as caches warm, but this
short run does not prove a permanent process-memory plateau. The separate 100,000-entry
cache regression establishes its decoded-byte accounting bound, not a GPU allocation bound.

Host: Ryzen 7 PRO 3700U (4 cores/8 threads), integrated Radeon Vega (1002:15d8),
about 14.5 GiB OS-visible RAM, Linux 7.3.4-zen2-2-zen, Rust 1.99.1.
Development executable, dependencies optimized at level 2. Native Wayland used Hyprland
on a 1920×1080, 60 Hz display; X11 used Xvfb. Other compilation/test activity overlapped
some runs; thermal state and cache warmth were not controlled. These are instrumented smoke measurements, not release performance acceptance.
`PG_METRICS` requests ongoing redraws; intervals include scheduling/idle stalls and exclude
GPU presentation timing. RSS excludes separately allocated GPU memory. Initial fixture
indexing is not a warm cached launch. The observed frame stalls remain a performance gap.

Runtime investigation found and fixed an Iced keyed-child type transition crash when a
stateless placeholder became a zoomable image. The stable child wrapper now has both a
regression test and repeated native runtime coverage. Initial harness runs also exposed an
X11 map/focus race and hidden grouped-window startup ordering; the helper maps/activates
its own child before awaiting the UI outline and bounds startup waits. Timed-out/incomplete
runs are not counted as acceptance passes. The system's default JDK 27 failed the pinned
Gradle/Kotlin toolchain; JDK 21 succeeds. Existing SDK-XML and deprecation warnings remain
warnings, not skipped failures.

After the queued-sign-out regression fix, the rebuilt executable also passed one final
Wayland cycle: exit 0, explicit directory preserved, 100,000-item outline ready in 9.288 s,
peak RSS 232.6 MiB, redraw p50/p95 16.663/17.027 ms. This shorter run is a final functional
smoke check, not a replacement for the longer cache/memory observations above; its summary
is `docs/desktop-validation/linux-wayland-final-smoke.json`.

### Explicit outstanding acceptance

- Windows runtime, Credential Manager behavior, screen-reader/UI Automation, display
  scaling and native GPU behavior need a Windows machine. The added CI matrix compiles
  the executable and runs shared/desktop tests on Linux and Windows; no remote CI result
  is claimed from adding the workflow.
- Linux screen-reader/AT-SPI operation remains unvalidated. Tiles are buttons with visible
  keyboard focus and keyboard opening/navigation; this does not prove native accessibility.
- Real-account sign-in, saved-session refresh, authenticator/mailbox-password flows, hosted
  CAPTCHA token continuation, Photos synchronization, real previews/originals, network error
  recovery and live sign-out need account acceptance. Their local failure/state transitions
  are regression-tested; fixture media is not real-account evidence.
- Mixed-monitor fractional Wayland scaling, HDR/color management, Windows runtime and
  real original-photo codec/memory behavior remain outside these smoke results. The X11
  125% check is an application scaling override, not a mixed-DPI monitor test.
- SDK encoded thumbnail limits are checked after its download buffer returns; application
  decoding limits do not establish a bound on every internal SDK allocation. Per-decoder,
  decoded-cache and original-stream limits do not imply a total-process/GPU memory cap.
- The historical warm-launch/60 fps gates are not claimed met. Measure a release build
  under controlled load before closing performance acceptance. Desktop video, Drive/album
  controls, backup, offline retention and packaging are later milestones.

## Earlier baseline — 2026-11-17

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
