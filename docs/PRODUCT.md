# Photos and Drive galleries

This guide describes the library, gallery, and visibility behavior in v0.2.0.

## Main navigation

**Photos** shows the Proton Photos timeline, capture dates, images, and videos.
Android also includes phone media and previews backed-up copies from the phone
when available. The **Albums** view opens native Proton Photos albums, and
**Favorites** follows Proton’s Favorite tag for cloud media.

**Drive** shows explicitly added folder galleries. Choose a folder in **My files**
and browse its direct media and subfolders. Every subfolder is an album, so mixed
folders can show both media and nested albums. Android can customize the album
name, cover, description, tags, and pin.

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

Android's **On this device** view exposes files explicitly imported into Gallery.
The regular Android Photos timeline reads eligible phone media through MediaStore.
Drive adds remote folders through its My files chooser.

## Cross-device settings and sharing

Gallery stores selected Drive roots, album presentation settings, and hidden
visibility in an encrypted settings device in the Proton account. Each upgraded
client applies changes after a successful check. Gallery can skip settings reads
when the Proton event feeds and the local outbox are idle.

Hidden albums unlock with Android device authentication or a desktop Gallery
passphrase. Their media is excluded from the ordinary Gallery library and its
cloud picker. Cloud originals remain accessible in Proton Photos or Drive and
through existing shares. For an upgrade, refresh Android first to import prior
phone settings, then refresh the other clients.

Owned Drive folders support public viewer links with optional passwords and
expiry. Gallery can copy and revoke them. Proton Photos album invitations and
shared-album discovery are future work.
[Settings sync design](DECISIONS.md) · [Android controls](ANDROID.md) · [Desktop controls](DESKTOP.md)

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

Stored media shapes come from known dimensions or decoded previews. Android
also probes a reduced image decode or rotated video metadata for device imports.
The layout can use a placeholder ratio while a shape is unknown.

## Rust data model

`SourceKind` separates Photos, Drive, Device, and diagnostic sources. Asset identity remains account + volume + node, with revision-specific bytes stored separately. Source membership is independent of asset identity, so overlapping galleries do not duplicate the original asset.

`album_nodes` stores `(gallery, source, parent)`. A folder may be a child in more than one selected gallery without losing either parent edge. Root nodes have no parent; children must reference an existing parent in the same gallery. Rust rejects cycles, cross-account/cross-volume edges, invalid roots, and excessive depth. Direct media membership is independent of child album membership.

`media_shapes` persists width and height without rewriting remote metadata.
`memberships_timeline` provides source-specific keyset seeks. Timestamp changes
propagate to every membership. Schema 12 includes the settings journal and
settings event cursors.

Discovery commits the current album before walking its children. A failed/cancelled crawl retains committed metadata and per-folder scan checkpoints. Current traversal restarts discovery from the selected root on a new invocation; it is not yet a leased, durable background job. Absence during an incomplete scan is never a deletion signal.

## Implemented and remaining

| Area | Current implementation | Remaining |
| --- | --- | --- |
| Photos | Account-scoped timeline, server capture dates, album browsing, favorites, Android album editing | Incremental timeline pages and durable event replay |
| Drive | Remote folder chooser, recursive metadata crawl, nested galleries, public viewer links | Media reconciliation from events and durable background crawl jobs |
| Grid | Shared Rust geometry, virtualized rows, adjustable size, preview shape learning | More device and accessibility measurements |
| Viewing | Still previews with original fallback, Android Media3 and Linux GStreamer range playback | Windows playback and broader real-account codec verification |
| Offline | Local metadata and disposable caches, encrypted block primitives in the CLI | Encrypted persistent previews and user-facing offline retention |
| Backup | Android manual, selection, and automatic backup to Proton Photos | Desktop uploads and user-selected Drive upload destinations |

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

The pinned SDK still materializes complete child and timeline ID lists before
yielding metadata batches. The CLI scan commands index metadata. Video
playback uses application services documented in the platform guides.

## Next delivery gates

1. Make event-driven media reconciliation durable across interrupted scans and moves.
2. Add encrypted persistent previews and recursive offline retention.
3. Validate Photos and Drive video range reads, codec coverage, and seek behavior
   on real accounts and devices.
4. Expand Windows runtime, desktop accessibility, and Android background validation.
