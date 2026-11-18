# Implementation plan and acceptance criteria

This is a dependency-ordered backlog, not a delivery-date estimate. Each milestone ends with demonstrated behavior and evidence. Performance numbers below are proposed targets, not measured results.

Implementation includes the shared Rust core, CLI, native Android gallery, account service with Keystore session storage, and Photos metadata/thumbnail delivery over UniFFI. Real-account acceptance, remote originals/video, persistent encrypted previews, and the Drive folder chooser remain open. [Development status](DEVELOPMENT.md) and [Android](ANDROID.md) distinguish implemented behavior from remaining milestone gates; no milestone is claimed complete merely because its initial code exists.

The [current product direction](PRODUCT.md) changes the delivery order: normal Proton Photos is the main view in the first delivery; Drive folders are separate galleries with nested albums. The shared Rust justified grid and source/album catalog foundation are implemented. The old M5 placement of basic Photos support is superseded.

## M0 — Prove the difficult seams

Before building the full interface:

- [ ] Audit the local SDK 0.12 API against `proton-stream`'s 0.9 dependency. Select an available reproducible SDK version/commit and record it.
- [ ] Trace login/session rotation, Photos routing, per-block reads, cancellation, thumbnail batching, upload drafts, and signature outcomes. Produce a capability/gap matrix.
- [ ] Verify random reads for Photos-volume media separately from ordinary Drive. Add Photos-aware revision-reader APIs in the SDK if ordinary link/key routing is insufficient.
- [ ] Expose SDK folder/Photos pages or streams if current `Vec` enumeration waits for every page. First screen must not require full library enumeration.
- [ ] Validate the Iced desktop browser ([desktop plan](DESKTOP.md)) with 100,000 synthetic records, realistic thumbnail sizes, high DPI, keyboard navigation, accessibility, and Windows/Wayland behavior.
- [ ] Prototype Android Compose -> UniFFI -> Rust query/range read with cancellation and one thumbnail batch on an arm64 device. Compare Media3 and existing mpv integration with header/tail seeks.
- [ ] Validate encrypted block-store seek cost, crash publication, and local key wrapping on Linux, Windows, and Android.
- [ ] Audit player/codec/source licenses and select distribution approach before copying GPL components.

**Gate:** architecture decisions for desktop renderer, Android player, storage format, platform floors, and SDK changes have small working proofs. Record measurements and revise this plan; no claims based solely on existing app README text.

## M1 — Independent Rust foundation

- [x] Create a standalone Cargo workspace with `pg-core`, `pg-drive`, and `pg-cli`; initially use modules for the remaining core responsibilities.
- [ ] Add domain IDs, source capabilities, revision descriptors, bounded query DTOs, typed recoverable errors, lifecycle generations, and cancellation handles.
- [ ] Implement app directories, OS credential adapters, local key wrapping, SQLite migrations, and durable jobs.
- [ ] Implement local indexed timeline/folder pages with keyset cursors and stable scroll anchors.
- [ ] Add a deterministic fake source with configurable latency, revisions, absent thumbnails, irregular block sizes, and injected faults.
- [ ] Implement SDK adapter and paged first-scope indexing; foreground query must not await background scan.
- [ ] Add redacted tracing and a headless diagnostic/benchmark CLI.

**Gate:** `pg-cli` signs in, selects a folder, lists a local page while indexing continues, restarts with a populated catalog, and lists that page with network disabled. No dependency on a sibling checkout, mount, daemon, or live credentials in tests.

## M2 — First end-to-end gallery slice

Depends on M1 and M0 player/storage choices.

- [ ] Implement bounded priority scheduler, shared byte permits, single-flight with independent consumer lifetimes, and viewport generations.
- [ ] Implement revision-aware range reader and encrypted object store with local-first opens.
- [ ] Port relevant range, cancellation, cache, and player tests with attribution.
- [x] Implement the Iced virtualized Photos grid, media-provider loading, still viewer and next/previous navigation. Real-account, Windows runtime and accessibility acceptance remain tracked in VALIDATION.md.
- [ ] Play and seek one video through the Rust range reader, including an MP4 whose index is at the tail.
- [ ] Implement durable single-item retention, basic storage/progress display, and crash/restart recovery.
- [ ] Exercise the same core catalog and offline reader through the Android bridge, without a second Android transfer state machine.

**Gate:** on desktop, open a folder, see its first tiles before scanning completes, view a photo, and start/seek a large video before the entire file is fetched. Pin an image and a video, restart with network denied, browse the local catalog, open/zoom the image, and seek to several positions in the video. Record request count and bytes proving partial video access. The Android integration test proves offline reading after process restart.

## M3 — Durable folders and incremental synchronization

- [ ] Add recursive folder requests, persisted traversal, overlapping retention references, discovery progress, inherited-pin UI, pause/resume/retry, and storage constraints.
- [ ] Reuse cached blocks during pinning; update revisions without removing last complete local copy.
- [ ] Implement transactional event application/inbox, pre-scan baseline, restart replay, expired-cursor reconciliation, and scoped periodic repair.
- [ ] Implement clear-cache/unpin/remove-download separately, old-revision display, corrupt-block repair, and orphan recovery.
- [ ] Add typed remote manifest verification capability or explicitly record unverifiable status; do not invent assurance from local checksums.
- [ ] Handle revoke/delete, source removal, logout during transfers, low disk, suspend/resume, and generation races.

**Gate:** pin a nested folder, interrupt midway, resume without refetching committed blocks, add/move/rename/update media remotely, and observe correct retained membership and revision transitions. Unpin an overlapping folder without deleting a directly retained item. Offline content survives a new session token requirement. A failed scan cannot erase retained availability.

## M4 — Uploads and usable Android client

These are two independent workstreams once M3 interfaces settle; complete both for the first release.

- [ ] Desktop picker/drag-drop and Android picker/share-intent uploads with local pending thumbnails, streaming reads, progress, collision handling, and confirmed remote commit.
- [ ] Persist URI grants or stage bounded source copies; detect modified/deleted desktop sources.
- [ ] Add upload draft/operation recovery to the SDK where needed. Resolve uncertain commits before retry, and document restart-only transfer behavior if block resume is unavailable.
- [ ] Compose timeline, folders, photo zoom/swipe, video player, offline controls, storage settings, accessibility, and error/waiting states.
- [ ] Android transfer host for explicit long jobs and deferrable updates, notifications, policy updates, process death, and reboot.
- [ ] Native memory-trim handling, lifecycle teardown, and FFI batch/copy profiling.

**Gate:** upload a mixed photo/video selection on each platform without whole-video allocation, survive an interrupted transfer and uncertain commit without silent duplication, and confirm the cloud revision. Android downloads a folder, survives eligible background interruptions, and opens completed items after a force-stop/relaunch in airplane mode. Document force-stop restrictions rather than expecting work to continue while stopped.

## M5 — Extended Photos features and format coverage

- [ ] Finish incremental SDK Photos pages and native Photos-album integration. The basic timeline adapter is implemented and belongs to the first-delivery gates.
- [ ] Route Photos-video revision opens and ancestor-key resolution correctly; prove partial playback and seeking rather than falling back to whole-photo downloads.
- [ ] Deduplicate asset identity across source memberships; preserve ordinary folders alongside Photos.
- [ ] Implement correct capture-time provenance and Photos upload metadata/duplicate handling.
- [ ] Validate JPEG/PNG/WebP and MP4/H.264 baseline on every target; test HEIC/HEIF/AVIF and HEVC before advertising original viewing/playback.
- [ ] Add bounded native/embedded preview fallbacks, EXIF/color tests, video posters from local/capped reads, and honest unsupported states.
- [ ] Keep shared albums, public links, live-photo pairing, and RAW support behind separate follow-up decisions if they threaten baseline reliability.

**Gate:** browse ordinary Drive folders and Photos without assuming they share a root, chronology, or upload route. Large libraries appear incrementally. Real phone fixtures behave predictably, and unsupported originals remain downloadable/exportable.

## M6 — Performance, recovery, and release

- [ ] Profile target hardware, tune budgets, and add regressions for the measured bottlenecks.
- [ ] Test Windows native builds and NTFS publication/open-handle behavior; Linux Wayland and X11; multiple real Android GPU/codec vendors.
- [ ] Add migration fixtures, database backup/recovery for durable user intent, corrupt-store repair, disk-full handling, and source/account removal races.
- [ ] Package Linux, Windows, and Android from independent clean checkouts with pinned dependencies and consistent app identity. Choose package formats using the existing release scripts as references.
- [ ] Validate local metadata disclosure, backups, export cleanup, credential-store absence/lock behavior, dependency licenses, and signed-release/update strategy.
- [ ] Provide user guidance for offline guarantees, storage, weak networks, codec limitations, and Android background restrictions.

**Release gate:** the baseline acceptance matrix passes on all three platforms; no offline badge without complete locally readable bytes; bounded memory during large scrolls; no credential leakage; no cloud mutation from unpin/cache-clear; no UI-blocking network or synchronous decode.

## Performance targets and measurement

Use a named baseline desktop (4 CPU cores, 8 GiB RAM, SSD) and midrange physical Android device (6 GiB RAM); record exact models, OS, GPU, thermal state, release profile, and cache state. Do not benchmark unoptimized crypto dependencies: the existing SDK optimizes dependency crates in development for a reason.

| Scenario | Proposed target | Measurement boundary |
| --- | --- | --- |
| Warm local launch | First useful cached grid within 500 ms desktop / 800 ms Android at p95 | OS process start to actual tiles; local key store already unlocked; no network dependency |
| Local page query, 100k assets | p95 under 30 ms, 200-row page | Rust query service including DB read and DTO construction; FFI measured separately |
| Cached photo open | Useful preview under 100 ms p95 | Input event to displayed image; original sharpening timed separately |
| Continuous scroll | 60 Hz target, p95 frame time at most 16.7 ms and fewer than 1% frames over 33 ms | End-to-end UI traces during indexing and thumbnail misses; report p99/max too |
| Cold visible thumbnails | Useful first screen under 2 s on reference link | After usable authenticated session, 20 visible existing server thumbnails, controlled 50 Mbps/80 ms link |
| Cold video start | First frame under 2.5 s at p95 on reference link for baseline clips | Input to first frame, reader not pre-opened; tail-index cases reported separately |
| Seek | First frame at target within 1 s warm / 2 s cold on reference link | Input to target-frame display, including demux and keyframe decode |
| Working-set memory | Desktop under 400 MiB and Android under 200 MiB for baseline grid; no growth with library size | Resident memory plus reported native/GPU allocations; player/huge-original cases separate |
| Retained media offline | Zero network dependency | Deny sockets, restart process, expire session, open and seek completed items |
| Pin already cached content | Zero duplicate downloads of committed blocks | Fake-source block request counters and real byte telemetry |
| Recovery | No falsely completed item; only missing/uncommitted work repeats | Fault injection at each publication/checkpoint boundary |

Targets are conditional on fixtures, not universal promises for all codecs, thumbnail availability, bitrates, or devices. Keep initial-login latency, first-ever indexing, warm launch, encrypted-store unlock, and cache-miss network latency as separate distributions. Offline launch should succeed even when credential refresh cannot.

Reference network profiles: 50 Mbps/80 ms; 10 Mbps/150 ms with jitter; 2 Mbps/250 ms; intermittent disconnect; 429 with `Retry-After`; expired block URLs; offline. Record p50/p95/p99, CPU time, memory peaks, cache-hit rates, bytes over the wire, requests before first useful image/frame, abandoned prefetch bytes, and time jobs wait behind other priorities.

Fixture library: 10k and 100k mixed metadata records; folders of 1k/10k items; irregular/short blocks; thousands of assets without thumbnails; high-resolution phone images and panoramas; corrupt EXIF; HEIC/AVIF; SDR/HDR video; MP4 with front/tail indexes; fragmented MP4; MKV/WebM with/without useful cues; high-bitrate HEVC; renamed/revised/removed assets. Use synthetic or explicitly consented media, with credentials absent from artifacts.

## High-value automated checks

- Range correctness against a byte oracle: irregular blocks, boundary crossings, EOF, zero-length reads, overflow/oversized requests, cache corruption, revision changes.
- Single-flight correctness: shared consumer cancellation, priority promotion, leader failure, teardown, and no orphan tasks/permits.
- Scheduler stress: thousands of replaced viewport requests while playing and pinning; bounded queues/memory and eventual background progress.
- Offline recovery: injected crashes before/after file sync, publication, DB commit, completion, GC, and replacement; local-only open with no remote descriptor fetch.
- Retention model: overlapping folders/direct requests, moves, traversal cycles, remote deletion, concurrent eviction, active reader leases, and storage exhaustion.
- Sync replay: mutations during scan, duplicate/out-of-order replay, expired cursor, crash after inbox insertion, crash before applied cursor commit, and incomplete scans.
- Upload recovery: local source changes, URI grant loss, cancel, retry after unknown commit, name collisions, and confirmed final revision.
- Platform integration: Android native/FFI lifecycle and task stop, Windows file handles, Linux minimized/unshown Wayland surface, credential stores locked or missing.

Use deterministic fakes for correctness and explicit opt-in test accounts for live Proton smoke tests. Keep live mutations inside disposable test scopes. Benchmark optimized builds; CI functional gates should be deterministic, while hardware performance gates use controlled runners.

## Deferred scope

Automatic camera backup, remote deletion/organization tools, photo editing, face recognition, semantic search, maps, transcoding, shared/public libraries, synchronized favorites/settings, iOS, TV, system services, portable/external offline stores, and general two-way synchronization. Preserve extensible contracts without implementing these before the core gallery/offline experience is reliable.

## Current desktop milestone (2026-11-18)

The approved Iced completion plan narrows this desktop milestone to account access,
Photos browsing and still-photo viewing. `pg-app` services and `pg-desktop` implement it;
Android's existing UI/UniFFI/platform work and `desktop/` Compose skeleton are preserved.
Implementation does not close the broader folder/video/offline/upload gates above.
See DESKTOP.md for supported interactions, request/cache/decode limits and commands;
VALIDATION.md records executed checks and the outstanding acceptance matrix.
