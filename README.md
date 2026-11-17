# Proton Gallery

A standalone, local-first photo and video gallery for Proton Drive, with a shared Rust core and clients for Linux, Windows, and Android.

**Status: early Rust core, CLI, and native Android gallery, 2026-11-17.** The app now follows **Photos / Drive** navigation: normal Proton Photos first, selected Drive folders as galleries with nested albums, and the shared Rust smart grid from `proton-drive-linux`. Android has native sign-in, Android Keystore session storage, Photos metadata sync, and memory-only remote thumbnails. Remote original viewing/video streaming, encrypted persistent previews, and the Drive folder chooser remain unfinished. See the [current product direction](docs/PRODUCT.md), [Android development](docs/ANDROID.md), and [implementation status](docs/DEVELOPMENT.md).

```bash
cargo run -p pg-cli -- --data-dir /tmp/proton-gallery-demo demo --count 10000
cargo run -p pg-cli -- --data-dir /tmp/proton-gallery-demo list --limit 20
```

## The experience to build

Open the app and see the library immediately from a local index. Scroll through cached thumbnails without waiting for Proton. Open a photo into an immediate preview that sharpens when the original arrives. Start and seek videos using only the file blocks needed for playback. Keep individual photos, videos, and entire folders available offline, with explicit progress and reliable recovery after interruption.

Use existing uploads in ordinary Drive folders and, through a separate adapter, Proton Photos and albums. Support simple uploads from desktop and Android. The app runs independently of `proton-drive-linux` and `proton-stream`: no mount, installed sibling app, external server, or shared live database is required.

## Architecture decision

**Rust owns the product's data and behavior:** Proton sessions, source discovery, indexing, queries, request scheduling, range reads, preview policy, cache storage, offline retention, uploads, and recovery. Desktop clients call Rust directly. Android uses a small UniFFI bridge and Kotlin/Compose for UI and operating-system integration.

Proposed initial frontends are egui/eframe on Linux and Windows and Compose on Android. Reuse libmpv integration on desktop; evaluate Media3 against the existing Android mpv integration before selecting the Android player. Both must read through the same Rust range and cache service. These frontend choices have explicit prototype gates; the Rust boundary is firm.

## What makes it fast

1. **Browse locally.** A persistent SQLite index answers every gallery query; startup never waits for login refresh or a full remote scan.
2. **Render progressively.** Layout and cached thumbnails appear first; server thumbnails, larger previews, and originals follow only as needed.
3. **Fetch by demand.** Visible tiles and active playback outrank speculative prefetch, indexing, and offline transfers. Every stage has bounded work and memory.
4. **Read videos by range.** Reuse the SDK's independently decrypted revision blocks and truthful block sizes. A seek abandons obsolete read-ahead.
5. **Share bytes.** Viewing and offline downloads use one revision-aware block store. Pinning promotes existing cached blocks instead of fetching them again.
6. **Keep promises.** An offline badge means all required bytes and local metadata are durable and readable without Proton. Preview-only availability is labeled separately.

Partial video reads are already supported by the existing Rust SDK. Universal partial photo decoding is not: some formats require the complete original. The gallery must hide that latency with previews, rather than promise impossible instant full-resolution reads. Cold network access and high-bitrate video remain limited by connection speed and Proton response time.

## Plan documents

| Document | Purpose |
| --- | --- |
| [Current product direction](docs/PRODUCT.md) | Photos-first navigation, nested Drive galleries, smart grid, and delivery order |
| [Architecture](docs/ARCHITECTURE.md) | Rust boundaries, schema, scheduling, synchronization, platforms, privacy |
| [Media pipeline](docs/MEDIA_PIPELINE.md) | Grid responsiveness, image previews, video ranges, format support, uploads |
| [Offline and storage](docs/OFFLINE_AND_STORAGE.md) | Folder policies, durable jobs, crash safety, eviction, space accounting |
| [Implementation plan](docs/IMPLEMENTATION_PLAN.md) | Dependency-ordered milestones, acceptance criteria, performance targets, tests |
| [Existing code and research](docs/EXISTING_CODE_AND_RESEARCH.md) | Inspected source, reusable pieces, known gaps, official references |
| [Decision log](docs/DECISIONS.md) | Proposed choices, alternatives, and questions to resolve through prototypes |
| [Development](docs/DEVELOPMENT.md) | Implemented behavior, commands, tests, and current limitations |
| [Validation](docs/VALIDATION.md) | Test results, cross-target checks, and measured local query latency |
| [Android](docs/ANDROID.md) | Native app, visual direction, Rust bridge, build, and remaining integration work |

## First delivery

Build one thin vertical slice before expanding the product: sign in, show the normal Proton Photos timeline, add a Drive folder gallery with nested albums, index and show the first pages, open a photo using its thumbnail, play and seek a video without downloading it completely, pin one item, restart in airplane mode, and open that item again. Run an Android integration slice early so desktop assumptions cannot harden into the core.

The following milestones add robust folder downloads, uploads, extended Photos features, and release hardening. See the [implementation plan](docs/IMPLEMENTATION_PLAN.md) for exact gates. Basic uploads belong in the first release; automatic camera backup, editing, face recognition, and cross-device gallery preferences are later work.
