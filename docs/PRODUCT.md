# Photos and Drive galleries

This is the current product direction. It supersedes the earlier folder-first MVP and Photos-as-a-later-milestone ordering in the original planning documents.

## Main navigation

**Photos** is the normal Proton Photos library, with its timeline, capture dates, images, and videos. It does not show a union of selected Drive folders or device imports. Native Proton Photos albums can later be exposed here through their own SDK album APIs; they are not Drive folders.

**Drive** is a collection of explicitly added folder galleries. Select a folder in the signed-in account, give it an optional display name, and browse that folder as a gallery. Each subfolder is an album, and every album may contain more albums and direct images/videos. Mixed folders are valid at every level; the app must not flatten them or hide their direct media.

```text
Photos                         Drive
  Proton Photos timeline         Travel                  [added folder]
  Native Photos albums             2026                  [subalbum]
                                     Japan               [subalbum]
                                       Kyoto             [images + videos]
                                       Tokyo             [images + videos]
                                     Arrival.mp4         [direct media]
                                 Family                  [another added folder]
```

Device imports remain a separate **On this device** view under the appearance/menu sheet. They are useful as local media and future upload sources; they must never masquerade as Proton Photos or a Drive gallery. An Android filesystem picker is not a substitute for selecting a folder in the Proton account.

## Shared smart grid

The reference is the justified gallery in `proton-drive-linux/crates/pdfs-gui/src/app/pages/photos.rs`, specifically `tile_ratio`, `plan_justified`, and `size_row`. Its MIT attribution is preserved in [NOTICE](../NOTICE.md) and the [license](../third-party/NILS-PUKROPP-MIT.txt).

The port lives in `pg-core::grid`, with typed UniFFI records for Android. Desktop clients can call the same Rust math directly. The UI virtualizes rows; it does not maintain a second independent Kotlin layout algorithm.

- Preserve normal image proportions; portraits are narrower than landscapes.
- Clamp tile aspect ratios to 0.5–2.5, cropping only extreme shapes. Unknown/invalid ratios temporarily use 1.0.
- Choose row breaks near the requested height; compact mode changes the target row height.
- Fill closed rows exactly, including gutters and cumulative pixel rounding.
- Keep sparse final rows at their target height rather than stretching a couple of photos across the screen.
- Keep date groups and media ordering intact. Image and video tiles use the same layout.
- Plan bounded windows on a background dispatcher. Android preserves breaks at 90-item page boundaries to reduce reflow when Paging evicts or prepends pages. This can leave a short row at a page boundary.

Stored media shapes come from known dimensions or decoded previews. Device import currently probes a small orientation-correct image decode, or video metadata with rotation. Remote thumbnail dimension learning still needs to be connected to the Android preview service. Layout never requires downloading an original just to discover its shape.

## Rust data model

`SourceKind` separates Photos, Drive, Device, and diagnostic sources. Asset identity remains account + volume + node, with revision-specific bytes stored separately. Source membership is independent of asset identity, so overlapping galleries do not duplicate the original asset.

`album_nodes` stores `(gallery, source, parent)`. A folder may be a child in more than one selected gallery without losing either parent edge. Root nodes have no parent; children must reference an existing parent in the same gallery. Rust rejects cycles, cross-account/cross-volume edges, invalid roots, and excessive depth. Direct media membership is independent of child album membership.

`media_shapes` persists width/height without rewriting remote metadata. `memberships_timeline` provides source-specific keyset seeks. Timestamp changes propagate to every membership; sparse albums do not scan the full Photos timeline. Schema migrations are forward-only through version 4.

Discovery commits the current album before walking its children. A failed/cancelled crawl retains committed metadata and per-folder scan checkpoints. Current traversal restarts discovery from the selected root on a new invocation; it is not yet a leased, durable background job. Absence during an incomplete scan is never a deletion signal.

## Implemented and remaining

| Area | Current implementation | Remaining |
| --- | --- | --- |
| Branding | Proton-style violet/plum themes and a segmented gallery icon related to Proton Stream | Final platform/device visual pass |
| Main Photos | Distinct SDK Photos adapter, server capture chronology, Android sign-in/sync, account-scoped local queries, memory-only server thumbnails | Incremental SDK timeline pages, persistent encrypted previews, original viewer |
| Drive galleries | Recursive metadata crawler, persisted nested/overlapping album trees, CLI addition, Android catalog browsing and breadcrumbs | Android remote folder picker, event reconciliation, background crawl jobs |
| Smart grid | Shared Rust geometry, Android lazy rows, known dimensions, compact density | Remote preview shape learning, real-device scroll/anchor measurements |
| Viewing | Local photo zoom/swipe and local Media3 videos | Photos-aware remote original/range access, encrypted Android media delivery |
| Offline | Shared revision/block retention primitives | User-facing item/folder policies, recursive references, quota/eviction, durable platform execution |
| Upload | Local device import, kept separate from cloud libraries | SDK Photos upload for Photos destinations; normal file upload for Drive albums |

The Android main screen opens **Photos**. Native sign-in/challenges, Keystore session storage, Photos metadata sync, and memory-only server thumbnails are wired through Rust. Cached queries are scoped to the selected account. The Drive folder chooser, persistent encrypted previews, and remote original/video viewer remain unfinished. Fixture screenshots do not demonstrate a signed-in Android account.

## Current CLI paths

```bash
# Interactive hidden password/TOTP prompts; credentials stay in memory.
cargo run -p pg-cli -- scan-photos --username YOU
cargo run -p pg-cli -- drive-folders --username YOU
cargo run -p pg-cli -- scan-gallery --username YOU --folder 'VOLUME~NODE'

# These read only the local catalog.
cargo run -p pg-cli -- albums
cargo run -p pg-cli -- albums --gallery GALLERY_ID --parent ALBUM_SOURCE_ID
cargo run -p pg-cli -- list --source ALBUM_SOURCE_ID
```

SDK 0.12 still materializes complete child/timeline ID lists before yielding metadata batches. The Photos source advertises `revision_reads=false` until Photos-specific random reads are verified. It uses Photos-aware metadata/thumbnail methods and does not silently fall back to whole-video download.

## Next delivery gates

1. Validate the implemented Rust session service, Android Keystore vault, refresh/logout, and native sign-in/challenge UI with a real account.
2. Normal Photos metadata and encrypted preview batches in the default tab. Cached content must remain browsable before authentication or network refresh completes.
3. A real Drive folder chooser and add-gallery action, indexing selected roots before descendants and retaining the nested structure offline.
4. Range-backed viewing and media-format coverage for both namespaces, proving video header/tail seeks without full downloads.
5. Recursive offline retention, upload destinations, events/moves/renames, background execution, and real-device performance/accessibility validation.

Photos support is part of the first end-to-end delivery. It is no longer scheduled behind the rest of the gallery.
