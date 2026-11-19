# Media pipeline and responsiveness

This is the design for the shared media pipeline. Current desktop decode and
cache bounds, and Android storage and playback behavior, are documented in
the platform guides. [Desktop](DESKTOP.md#work-and-memory-bounds) ·
[Android](ANDROID.md#performance-and-storage-boundaries)

## 1. Grid first

The default is the shared Rust justified grid adapted from the user's Proton Drive app. Both Photos and Drive albums use it. Preserve normal aspect ratios, clamp extremes to 0.5–2.5, keep date groups, and do not stretch a sparse final row. See [current product direction](PRODUCT.md).

The UI reads a bounded local page and lays out stable tiles before issuing remote thumbnail requests. Use known dimensions when present; otherwise use a stable placeholder aspect ratio until metadata arrives. A failed or slow asset never blocks sibling tiles.

Maintain a viewport generation with visible and near-visible IDs. Request visible thumbnails first, a small directional screen ahead second, and stop requesting far behind. During a fast fling, show cached thumbnails and defer speculative decode until the viewport settles. Preserve scroll position across sync and orientation changes.

Thumbnail resolution follows rendered size and display scale. Initial buckets: 256 px and 512 px; viewer previews around 1600–2048 px, generated only when the actual source resolution permits. These are local derivative sizes, not assumptions about Proton's available thumbnail variants. Do not upscale a tiny server thumbnail and label it a high-resolution preview.

Load representations in this order:

```text
decoded-memory/GPU hit
  -> encrypted local thumbnail
  -> Proton thumbnail, fetched in bounded visible batches
  -> local derivative from already available original
  -> selected-item or explicit background fallback extraction
```

The SDK supports thumbnail lookup batches of up to 30 remote IDs, grouped by volume, and returns per-file outcomes. Match results by identity, not array order. Keep batches small enough that hidden tiles do not delay the current viewport. The current API collects results; measure whether a streamed-result API is needed to avoid one slow thumbnail delaying the batch.

Track `unknown`, `ready`, `absent_for_revision`, `retry_at`, and `unsupported_by_pipeline_version` separately. A transient failure is not permanent absence. Revision or decoder-version changes invalidate the relevant negative result. Never loop on a genuinely missing thumbnail.

Do not fetch thousands of originals because their thumbnails are missing. A grid tile may remain a useful icon with filename/date/duration while a background preview is unavailable. A visible click authorizes the selected original fetch; bulk fallback preview generation is opt-in and constrained by network/power policy.

## 2. Images: preview immediately, original when needed

On open, show the best cached representation with orientation applied. Ask for a larger server preview if one actually exists. Otherwise fetch the original through the Rust block reader in the background, decode to the screen target, and replace the preview without changing zoom or orientation. The UI should show when it is still displaying a preview.

Prefetch only previews of the previous/next one or two assets. Promote the next asset on deliberate navigation; do not fetch every adjacent original while the user swipes quickly. Cancel obsolete consumers while preserving shared cache work. Zoom requests higher resolution only when required.

| Format class | Planned behavior | Limitation / gate |
| --- | --- | --- |
| JPEG | Server thumbnail first, bounded decode with reduced-resolution output; use embedded EXIF preview when available | Baseline JPEG often needs complete image data; incremental display requires a capable decoder and still needs whole Proton blocks |
| PNG / WebP | Thumbnail then screen-sized decoded original | A prefix is not a general-purpose preview; enforce decoded-pixel limits |
| HEIC / HEIF / AVIF | Discover and show thumbnails; original viewer via validated platform/decoder support | First-release full-resolution support must pass real phone fixtures on each target; codec build and licensing are explicit gates |
| GIF / animated WebP | Static grid preview; animate only active viewer | Bound frame count, dimensions, memory, and animation CPU |
| RAW / TIFF / huge panorama | Embedded preview if available; original download/export remains possible | Full editing/RAW pipeline and multiresolution tiling are later work |
| Live/motion photos | Preserve component identity when metadata permits; show still plus explicit motion action | Pairing and playback are staged after basic still/video support |

Range access helps extract embedded metadata/previews, but does not guarantee partial decoding of arbitrary images. A few header bytes may still cost a complete encrypted block. Cap metadata probe bytes/requests and fall back to a known thumbnail or deliberate full fetch rather than scanning an entire remote file implicitly.

Generate local grid/viewer derivatives after an original has already been fetched for viewing, upload, or pinning. Cache by `(asset, revision, variant, pipeline_version, orientation/color_policy)` so corrected transforms cannot reuse incorrect output. Decoders need byte, dimension, pixel, frame, time, and output limits, including overflow checks before allocation. Use bounded workers and isolate native decoder failures where practical.

Avoid expanding a 48 MP image to a full RGBA bitmap merely to draw a small grid tile. A single 48 MP RGBA surface is about 183 MiB before extra copies. Prefer decoder downsampling, apply ICC/EXIF correctly, and release intermediate surfaces before GPU upload. Native Android bitmap and desktop texture budgets are separately tracked; compressed cache size is not a measure of texture memory.

MVP displays stills in SDR with correct orientation and a tested color conversion path. HDR/Ultra HDR presentation requires explicit platform validation; preserve original bytes regardless of preview conversion. Do not silently apply arbitrary server transcoding or modify originals.

## 3. Video: seekable bytes, no full-file staging

```text
open asset
  -> show poster and controls immediately
  -> resolve one immutable revision descriptor
  -> player reads requested byte ranges
  -> block map resolves intersecting blocks
  -> memory / encrypted local store / remote block fetch
  -> SDK decrypts complete blocks
  -> return exact requested byte slices to demuxer
```

Use the SDK's actual plaintext block-size vector and checked prefix sums. Never divide by a guessed 4 MiB. Clamp at EOF, reject impossible maps, and bound each requested range and returned buffer. Fetching a range means fetching the complete overlapping encrypted blocks, then slicing plaintext.

The existing SDK can pin `open_revision_at` to a revision. Keep that revision throughout playback and refresh only expiring storage URLs for it. If it is no longer accessible, surface a recoverable error or explicitly reopen the new revision; never combine old and new blocks.

MP4 metadata may be at the end of the file. A seekable demuxer must be able to request tail blocks before it can play the beginning. MKV/WebM cues and keyframes create other nonsequential requests. Allow bounded demuxer probing, prioritize those reads, and measure bytes-before-first-frame. Do not build a hand-written container parser or download the entire video to repair a tail-index file. Missing indexes and unsupported codecs may still limit seeking.

Let the player own the main playback buffering policy. The Rust block layer deduplicates and caches; initially turn its independent sequential read-ahead off beneath a player that already buffers. A separate small header/tail warm-up is experimental and must improve measured startup without redundant traffic.

When seeking, increment read generation, detach old demand, stop obsolete speculation, and elevate the new range. Cache only within a bounded window and keep it smaller than its memory budget. A slow obsolete block cannot hold every transfer permit. Under weak connections, stop gallery prefetch and bulk jobs before playback underruns. An app cannot sustain an original whose bitrate exceeds available bandwidth indefinitely; expose buffering and allow an offline download.

There is no server transcoder in this architecture. Alternative lower-bitrate video representations would require creating, uploading, indexing, and retaining explicit derivatives, with substantial CPU/storage/privacy cost. Defer that feature until original playback has been measured on weak networks.

Desktop: adapt `pstr-player`'s custom stream callbacks and opaque handle registry. Do not put cloud identifiers or credentials in player URLs. Keep player callbacks off UI threads, enforce read timeouts, and test teardown with reads in progress.

Android: compare Media3 using a custom `DataSource` over a Rust reader against the existing mpv/JNI path. Media3 exposes position-based opens and reads through its [DataSource interface](https://developer.android.com/reference/androidx/media3/datasource/DataSource), and documents container/device capability boundaries in [supported formats](https://developer.android.com/media/media3/exoplayer/supported-formats). Select on measured seeking, hardware decoding, battery, codec fixtures, packaging, and licensing. Ship one backend first, with a capability interface that permits another later.

## 4. Initial resource budgets

Starting experiment values, not established safe defaults:

| Resource | Desktop | Android |
| --- | --- | --- |
| Decoded grid images / textures | 128 MiB | 32 MiB, shrink on memory pressure |
| Plaintext block memory | 64 MiB | 24 MiB |
| Image decode workers | 2 | 1–2, based on memory class |
| Adjacent viewer preview look-ahead | 2 items | 1 item |
| Opportunistic media disk cache | 2 GiB | 512 MiB |
| Thumbnail disk allocation | 512 MiB | 256 MiB |

These are sub-budgets within a global process target, not additive guarantees. Also account for HTTP ciphertext, decrypt scratch, native player buffers, original decode surfaces, Java heap, GPU textures, queued FFI buffers, SQLite, and encryption writes. Reserve memory based on expected bytes before starting work; task-count limits alone do not bound memory. Account for large nonstandard remote blocks and reject/degrade gracefully above configured limits. Android memory-trim signals drop reconstructible textures and speculative work first.

## 5. Uploads that feel immediate

MVP supports desktop file picker and drag/drop, Android picker/share intent, a destination folder, and visible per-file progress. Camera background backup and two-way folder synchronization are outside the initial scope.

1. Persist an upload intent and establish a readable local source. Android uses a persistable URI grant when supported; otherwise copy to a bounded private staging area before promising background completion. Detect desktop source mutation or disappearance.
2. Extract bounded metadata and generate a local thumbnail before starting the network transfer. Show a pending tile immediately, clearly labeled uploading.
3. Stream source bytes through the SDK upload interface, never a whole-video `Vec<u8>`. Obey the same resource and network policy as downloads. Separate account folder uploads from Photos uploads with Photos-specific metadata/duplicate handling.
4. Persist draft and operation identity when supported. Checkpoint only capabilities the SDK actually offers. If block-resume is unavailable, describe retry as restart rather than promising resumability.
5. After uncertain completion, resolve/reconcile the draft or committed node before retrying. A timeout after remote commit must not blindly create a duplicate. Add SDK operation/draft recovery support if needed.
6. Mark complete only after a committed remote revision is confirmed. Reconcile the local pending tile into the stable remote asset without a duplicate. Reuse its thumbnail, while associating cached original blocks only after confirming revision identity and block map.

Default name collisions preserve existing files and offer rename/skip; replacement requires an explicit user action. Cancelling a local upload is distinct from trashing an already committed remote object. Keep recoverable source/staging data until remote commit is confirmed, and explain storage use. Local upload preview does not by itself mean the cloud copy is complete or retained offline.
