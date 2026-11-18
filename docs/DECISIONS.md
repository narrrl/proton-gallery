# Decision log

## Firm requirements

- Standalone gallery in `proton-gallery`, building on existing Proton work.
- Shared Rust core; Linux, Windows, and Android clients.
- Fast photo/video browsing and viewing without whole-video downloads first.
- Offline originals for individual photos/videos and recursive folders.
- Responsive interaction, bounded resource use, and durable interruption recovery.

## Proposed defaults

| Decision | Reason | Validation / reconsideration |
| --- | --- | --- |
| Local SQLite catalog with incremental updates | Remote listing must not gate repeat browsing | Measure 100k-record queries and scan/event convergence |
| Existing pure Rust Proton SDK | Existing authentication, Photos and revision streaming capabilities | Audit 0.9-to-0.12 differences and selected release availability |
| egui desktop, Compose Android | Maximum reuse of Rust desktop/player work and native Android lifecycle integration | M0 must prove grid, accessibility, DPI, GPU integration and Android bridge |
| libmpv desktop; Android backend after spike | Desktop integration exists; Android choice affects codec reach, battery and package size | Compare Media3 custom DataSource to current mpv/JNI using the same Rust bytes |
| Per-account encrypted media object store | Retained media should not become plaintext files by default; block encryption preserves seeking | Measure overhead and prove crash publication and key recovery on all targets |
| Plaintext app-private metadata catalog initially | Avoid unproven encrypted-database distribution/migration complexity | Disclose filename/date leakage locally; reconsider full catalog encryption before a vault claim |
| Original-quality offline retention | An offline badge must mean the whole original is locally readable | Network-denied restart tests including seeks and expired sessions |
| Folder policies follow future media | Matches persistent `keep offline` intent | Show discovery/update state; require robust reconciliation and space policy |
| Preserve completed local copies after remote removal | Do not silently destroy the user's downloaded original | Clearly label local-only copies; never imply revocation can erase downloaded data |
| One durable Rust job system | Desktop and Android must agree on completion, retries and retained bytes | OS task hosts lease Rust work; no second authoritative Kotlin download store |
| Single account in first UI, account-namespaced internals | Reduce first-release UI scope without cross-account identity/cache bugs | Add account switching later after logout and storage isolation tests |
| Basic upload in first release, camera backup later | Cover easy media upload without importing a sync daemon's scope | Prove uncertain-commit handling, source access lifetime and streaming memory use |
| No server transcoding | Stay standalone and preserve original privacy/bytes | Revisit only if measured low-bandwidth playback needs explicit proxy representations |

## Alternatives considered

**A FUSE-mounted gallery:** convenient on Linux but introduces daemon/mount latency, duplicates caching, and does not solve Windows/Android. Direct SDK access provides explicit priorities and shared byte reuse.

**A complete fork of `proton-stream`:** yields an existing player shell but imports film/series naming, metadata matching, watch sync, and Android-specific transfer ownership. Reuse selected mechanisms and tests around a gallery domain instead.

**Flutter, Tauri, or a single UI toolkit on all platforms:** remain viable if M0 rejects egui/Compose. Flutter adds a Dart layer and a new Rust bridge; a webview adds custom range/codec/rendering constraints; one Rust UI may reduce frontend duplication but needs proof of Android lifecycle, gestures, accessibility, and media integration. The user requirement is Rust core, not mandatory all-Rust UI. Do not restart the core if the UI changes.

**Full original download before every view:** simple but loses the primary responsiveness goal. Use server/local previews for stills and seekable ranges for video.

**Prefetch everything:** trades first-screen responsiveness, mobile battery, bandwidth and disk space for a cache that may never be used. Keep speculation small and demand-led; explicit folder retention is the way to request a full download.

**Separate stream cache and offline files:** simpler initial plumbing but wastes disk and network and creates inconsistent availability rules. Shared immutable blocks with durable retention references are worth implementing before broad offline rollout.

## Questions to settle with prototypes

1. Can egui meet the gallery's interaction/accessibility/color requirements on real Linux and Windows devices using the chosen player renderer?
2. Does Media3 cover enough actual uploaded video formats to justify the Android integration change? What is the measured cost of FFI copies versus mpv reuse?
3. Which SDK page, acknowledgement, verification, and upload-recovery APIs are missing and should be upstreamed first?
4. Can encrypted retained blocks meet seek targets on low-end flash without excessive small-file overhead? Start with simple block files; consider packfiles only after measurement, since compaction complicates crash safety.
5. Is a plaintext metadata catalog acceptable for the intended release, or should encrypted SQLite become a release requirement? Do not describe media encryption as full metadata protection.
6. What baseline HEIC/HEIF/AVIF and HEVC original-viewing support is practical for all targets? Discovery and offline byte retention should work even where viewing is unsupported.

These do not block starting the Rust foundation and performance spikes. Initial assumptions are stated in the architecture and roadmap; any changed decision must update its dependent acceptance criteria.


## 2026-11-17 — Photos first, nested Drive galleries, shared smart grid

The user's revised direction is authoritative: normal Proton Photos is the main view; explicitly selected Drive folders are separate galleries with recursive subalbums and mixed direct media. Device imports stay separate. Photos is part of the first delivery rather than a later M5 addition.

The justified-row algorithm is ported from proton-drive-linux into pg-core and called through UniFFI. This prevents desktop/Android from diverging into different layout rules. Source kinds and per-gallery parent edges preserve real namespace and folder relationships. Schema 4 adds ordered membership indexes so sparse album reads do not walk the global timeline. The [product document](PRODUCT.md) records implementation boundaries and the updated delivery gates.

## 2026-11-18 — Hidden photos and data at rest

Proton has no way to hide photos: no tag, and an album's `Hidden` flag is read-only in the API. Hiding is therefore a choice made on this phone. A photo is hidden while it is in any album marked hidden. It then leaves the timeline, the search and the system photo picker here (schema 10 tells the picker at once), while the album itself stays an ordinary Proton album that other Proton apps show. The app says so where an album is hidden. "Hide" asks which hidden album the photos go into, after the phone's lock confirms the user, or offers to create one (named Hidden by default) when there is none; phone-only photos are backed up first. Adding photos to a hidden album through "Add to album" hides them the same way; hidden albums appear there only while unlocked. The phone's copy stays in the phone's own gallery until the user deletes it.

The lock on hidden albums is the phone's own (biometric or screen lock) and gates the screen, not the data: nothing on disk is encrypted with it. What it does protect:

- Thumbnails of hidden albums stay in memory and are dropped when the albums lock. Originals decrypted for viewing are deleted at the lock; copies handed to another app at the lock after that.
- While hidden albums are open the window is secure: no screenshots, recordings, casting or recent-apps thumbnail.
- Hidden albums stay out of the album lists of other actions, and their names out of the backup settings, until unlocked.

Data at rest otherwise:

- The session (tokens and key passphrases) is AES-GCM encrypted under a non-exportable Android Keystore key. The key does not require an unlocked device, because backup runs while the phone is locked.
- The catalog stays plain SQLite (mode 600, app-private, under Android's file-based encryption). It holds decrypted names, album titles and the hidden flag. Signing out deletes everything of the account from it and rewrites the file (`VACUUM`), and deletes the thumbnail, picker and original caches. SQLCipher would add about 3 MB, an OpenSSL cross-build and a key migration; reconsider it before hidden albums are described as a vault.
- Decrypted originals in the cache expire after a day, checked whenever the app leaves the screen. Grid thumbnails of non-hidden media stay on disk (320 px WebP, 256 MB) until sign-out or the system clears the cache.
- Release builds drop verbose logs; warnings name the kind of failure only, never paths or file names.
