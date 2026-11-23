# Native Android gallery

Kotlin/Compose calls `pg-mobile` through generated UniFFI bindings. Rust owns the catalog, ordering, paging, content identity, durable import publication, and metadata. No sibling repository or companion service is needed.

## Implemented experience

- One slim top bar holds a **Photos | Drive** switch (the screen title and the navigation in one control) and the account button. It slides away while scrolling down and returns on the way up. Inside an album or the device library it shows back, the title, and that place's action (edit album, add photos).
- **Photos** is the default: one timeline of Proton Photos and the photos and videos on this phone. A photo that is in both shows once (the Proton copy, previewed from the phone file). Media only on the phone carries a crossed-out cloud. Long-press selects; **Back up** uploads the selected phone-only items and **Share** sends them to another app (Proton originals are downloaded and decrypted first).
- **Drive** reads selected gallery roots and nested folder albums from the Rust catalog. An album shows its item and album counts, description and tags above its albums and media.
- A shared Rust smart grid lays out portraits and landscapes in justified, edge-to-edge rows with 2dp gaps and square corners. Pinch with two fingers to zoom (target row height 48–240dp, default 120dp, saved). Headings follow the zoom like the Linux app: days for large tiles, months below 104dp, years below 64dp. The first visible photo stays in view while zooming.
- Pull down to refresh Photos, or the Drive gallery on screen.
- Photos and Albums show a loading state while their local results arrive. An empty-library
  refresh action appears after loading settles. Active background work appears under
  the top bar and in the account sheet while it runs. Manual and selection backups
  also report when a request is queued, running, or finished.
- The viewer shows the phone file, the local file, or Proton's large preview (the decrypted original when there is none); Proton videos stream: Media3 reads byte ranges through `ProtonDataSource`, which Rust (`open_video`) serves from the revision's blocks, decrypted in memory. About six blocks (24 MiB) stay cached around the play position and two are fetched ahead; nothing is written to disk, including for hidden albums. Range reads skip the whole-file manifest signature check that a full download does; each block is still decrypted and checked. If a stream can't be opened, the viewer falls back to downloading the whole file first. One tap hides the bars and the system bars; another brings them back.
- Other apps can pick photos and videos here (`GET_CONTENT`, `PICK`), Proton-only ones included: the app opens in a picking mode and returns content URIs with read access, decrypting Proton originals into its cache first. Copies older than a day are removed.
- The account button opens one sheet: account, refresh, **Backup**, and theme (device, light, dark). Backup chooses phone folders to upload automatically, with Wi‑Fi-only (default) and charging-only conditions, and frees up space.
- Native adaptive and monochrome launcher icons accompany the SVG/PNG mark.

Native sign-in supports SRP passwords, authenticator codes, separate mailbox passwords, and Proton-hosted CAPTCHA. Sessions resume without a network round trip; Photos sync starts separately. Android Keystore protects the token/derived-key blob with AES-256-GCM and atomic writes under `noBackupFilesDir`. Login/mailbox passwords are not persisted. Sign-out cancels work, clears the saved session, hides that account’s catalog, drops remote preview caches, and attempts server revocation with a short timeout. Imported device media remains available.

Android can sync Proton Photos and load server thumbnails. The SDK still enumerates
timeline IDs before returning metadata pages. Each committed page becomes queryable
while later pages decrypt. Persistent encrypted previews and full remote offline
retention are future work. The app uses HTTPS for Proton requests and allows
specific cleartext certificate-revocation hosts.

The sign-in dialog blocks screenshots and keeps password/code input out of saved instance state. Proton CAPTCHA runs separately from credential input, with origin-scoped WebView messaging limited to `https://verify.proton.me`. Security-key-only accounts and email/SMS verification are reported as unsupported. An invalid authenticator attempt restarts sign-in because Proton may consume its pending session.

### Phone library and backup

The app reads the system media index (`READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`, or Android 14's selected-photos access) and passes metadata in batches of 500 to the Rust catalog (schema v6: `phone_media`, `phone_backup`, `backup_folders`). Files are never copied; thumbnails come from `ContentResolver.loadThumbnail`, originals and videos from content URIs. A complete scan forgets media the phone no longer has; an interrupted scan removes nothing, and only media last seen by an older scan is forgotten, so overlapping scans never drop each other's results. Scans run one at a time, on start, on return to the app, and two seconds after the media store reports a change; they are skipped when the media store's version, generation and item count are unchanged since the last complete scan. After the first complete scan, later scans are quick: they list identifiers only (`phone_reconcile` forgets removed items and names identifiers the catalog does not know, such as photos shared later under partial access) and read full metadata only for those and for media whose `GENERATION_MODIFIED` is newer than the last scan. A rebuilt media store (new version) brings back a full scan.

Matching with Proton Photos uses the SDK's duplicate check, like the Proton Drive Linux takeout import: one name-collision request per batch of names, then the plaintext SHA-1 of only the same-named files (`find_duplicates_many`). Until a same-named file is compared it stays hidden behind the Proton photo of that name, so a backed-up library never shows every photo twice during the first check. A changed file is compared again, and a file found missing by name alone is compared again once its name appears in Proton Photos (for example after another app uploaded it). Results are kept as they come, so an interrupted check continues where it stopped; three files are hashed at a time.

Uploads (`upload_phone`) hash the file, skip it when Proton already holds the same photo, and pick a free name such as `IMG_1 (1).jpg` when a different photo has the same name. They send a 512 px, at most 60 KB JPEG thumbnail, the capture date, video and screenshot tags and the favorite mark. The file streams to Rust in 256 KB chunks. Uploaded photos appear in the timeline immediately. WorkManager runs backups: every six hours, shortly after new media appears, on **Back up now**, and for selections (any network). A notification shows progress and can pause. The screens and the workers share one engine and its locks: one scan, one check and one upload at a time. Chosen items never wait for the check of the whole library; each upload checks its own file with Proton first. Automatic backup does not wait either: uploads start as soon as the check marks something missing, and the check continues beside them. Files the phone cannot read are passed over for the rest of the session instead of stalling the check. Upload failures name their cause (connection, rate limit, storage quota, or Proton's own message).

**Free up space** (in the Backup sheet) removes phone copies of backed-up media older than a chosen period (30 days, 3 months by default, or a year), so recent photos stay in the camera roll and in other apps' photo pickers. Candidates must be marked backed up, have a known SHA-1, and have their Proton copy in the synced Photos library; right before deleting, each one's name and SHA-1 are confirmed with Proton again (`free_up_verified`), and an incomplete answer cancels everything. Android's own `createDeleteRequest` dialog then asks the user, in batches of up to 2,000.

### Proton Photos albums and deleting

After each Photos sync the app lists the account's Proton albums with their photos (including photos kept only in an album) and stores each as a source of kind `album` (schema v9), a one-level gallery in `album_nodes`, so covers, settings, hiding and paging work as for Drive albums. The Photos tab shows them in a row above the timeline with a **New album** card. Selected photos can be added to an album or a new one (photos not backed up yet stay out); inside an album a selection can be removed. The album's edit sheet renames it in Proton and deletes it; deleting first saves photos kept only in the album to the timeline. Every change updates the catalog at once.

**Delete** (selection bar and viewer) moves items to the trash after a confirmation that names the trash: phone files to the system trash (`createTrashRequest`, which asks again), Proton items to the Proton trash. An item on both goes from both, so backup never brings it back. A complete Photos sync also removes photos Proton no longer lists, so deletions made elsewhere show here.

### Android's system photo picker

On Android 13 and later, `PickerProvider` (a `CloudMediaProvider`, authority `<package>.picker`) puts Proton Photos into the system photo picker that every app uses, beside the phone's own media. The picker keeps its own copy of the library's metadata and asks only for what changed after the last generation it saw: catalog schema v7 gives every new or changed member of a Photos source the next number (`picker_changes`), including when its phone copy appears or goes away. Proton photos whose file is also on the phone carry its `MEDIA_STORE_URI`, so the picker shows them once. The collection identifier is random per catalog and includes the Photos source, so a rebuilt catalog or another account makes the picker read everything again. Photos that leave the Photos source (trashed in this app, or gone from Proton at the next complete sync) stay in `picker_changes` marked deleted (schema v8) and are reported through `onQueryDeletedMedia`. Thumbnails and large previews come from Proton and are kept in `cache/picker` (up to 256 MB); a picked file is the decrypted original in `cache/originals`. The app tells the picker about changes after a Photos sync, a backup run and a phone scan that removed files. Albums, search and video previews inside the picker are not offered.

Android lists only the cloud apps the phone maker allows, so this needs one adb command first (the account sheet shows the right one and a copy button):

```sh
# Android 15 and later; Android 14 uses `put` instead of `override`.
adb shell device_config override mediaprovider allowed_cloud_providers io.narl.protongallery
# Android 13 additionally reads the authority from another namespace, and only at boot:
adb shell device_config put storage_native_boot allowed_cloud_providers io.narl.protongallery.picker
```

These commands use the regular release package. For **Proton Gallery Testing**,
substitute `io.narl.protongallery.testing` and `io.narl.protongallery.testing.picker`.
The value is the whole list, so include other cloud apps, comma-separated
(for example `com.google.android.apps.photos,io.narl.protongallery`).
`adb shell device_config get mediaprovider allowed_cloud_providers` shows the
current value. Restart the phone if the app does not show up, then choose it
in the picker's settings (`MediaStore.ACTION_PICK_IMAGES_SETTINGS`). The account
sheet links there. On Android 13 and 14 a server flag sync can reset a `put`
value. Some Android 16 builds also need `cloud_media_feature_enabled true` in
both namespaces. To test, run
`adb shell am start -a android.provider.action.PICK_IMAGES -t 'image/*'`.

Device dates mean **added to this app**. Photos chronology uses the server's capture date; ordinary Drive currently uses remote modification time. Identical device bytes deduplicate, and reimport repairs the stored copy while preserving its original title/date. Unsupported images can import but fail to decode; viewing reports that failure.

### Drive galleries and album settings

Drive → + (Add gallery) opens a folder chooser over My files. The chosen folder becomes a gallery; every folder inside it, at any depth, becomes an album. Adding or refreshing a gallery reads metadata and server thumbnails only. Removing a gallery forgets its album tree in this app; nothing is deleted from Drive.

Photos sync and Drive gallery work run independently, each with its own lock and progress counter in `pg-mobile` (`sync_progress`, `drive_progress`) and its own job in the view model. A gallery can be added while a large Photos library is still syncing.

Album presentation settings, selected Drive roots, and hiding sync through
**Proton Gallery settings**, a dedicated encrypted device in your Proton account.
Refresh the upgraded Android app first to import settings from your phone, then
refresh your other Gallery devices. Background checks use Proton event cursors
to skip settings reconciliation when there is no change. Manual Photos and
Drive media refreshes still scan their respective scopes.

The **Public link** action on an owned Drive folder can create, copy, and revoke
a viewer link. Gallery checks for hidden contents before creating it. Existing
public links can still be revoked from an unlocked hidden folder.

- Name and description. The folder keeps its Drive name.
- Cover: a chosen photo or video from the album or one of its subalbums. By default, the album uses its newest item.
- Tags (up to 20, case-insensitive). The Drive tab filters by tag across all galleries.
- Pin or hide an album. Open hidden albums using Android’s fingerprint, face
  or screen lock (`BiometricPrompt`, `BIOMETRIC_WEAK | DEVICE_CREDENTIAL`).
  They lock when the app leaves the screen. Their photos leave Gallery's
  timeline and cloud picker after sync. **Hide** asks which hidden album to use
  or creates one. Originals remain accessible in Proton Photos or Drive and
  through existing shares. Local phone copies remain subject to Android's own
  gallery and picker rules.

The interface shows everything newest first, and the grid's zoom sets the date headings. The catalog still stores grouping and order per album (`album_settings.grouping`, `sort_order`, with a case-insensitive name index), but the app no longer offers them.

Sign-in uses the app version `external-drive-protongallery@…`. The API splits that value on `-`. The earlier `external-drive-proton-gallery` was refused with `2064 Invalid section name`, which the app showed as "Couldn’t reach Proton". Rust log records go to logcat with the tag `pg-rust` (Info and above). Transport failures log their full cause chain there; the interface still shows a short message.

Certificate revocation: Proton's API certificate comes from Let's Encrypt, which no longer runs OCSP. Android's revocation check then has to fetch the CA's revocation list (CRL), and CRLs are served over plain HTTP. The app used to forbid all cleartext traffic, so the check failed. rustls-platform-verifier 0.6.2 reports that as `invalid peer certificate: Revoked`, and sign-in showed "Couldn’t reach Proton". `res/xml/network_security_config.xml` is copied from rustls-platform-verifier (it ships there from 0.7.1). It allows cleartext only to CA revocation-list hosts; every other host stays HTTPS-only. Remove it once the app moves to 0.7.1 or later. That needs a reqwest that uses 0.7, because reqwest and the app must initialize the same verifier copy.

Connection check: after a failed sign-in, the account screen offers **Test connection**. It reports, as copyable text, whether the certificate verifier started, then name lookup, the TCP connect for each address family, and an unauthenticated `tests/ping` through the app's own HTTP client. It also lists the last 30 Rust warnings. The report has no credentials. `cargo test -p pg-mobile -- --ignored live` runs the same check, plus a sign-in with a made-up account, from the host.

Crash report: `CrashLog` keeps the stack trace of an uncaught Kotlin exception and, on the next start, also reads Android's record of a native crash or freeze (`ApplicationExitInfo`). From a native tombstone it keeps only frame and abort strings, never raw memory. A Rust panic hook writes the last panic to `gallery/last-panic.txt`, which is added to the report. The app shows the report once, with a Copy button. Background tasks in the view model run under a `CoroutineExceptionHandler`, so an unexpected failure shows a message instead of closing the app.

Native code: calls into Rust run on a pool of 8 threads with 8 MB stacks, because each call blocks on async Rust and Android's pooled threads have about 1 MB. The Android library is built with `--release`.

### Places

Places shows where Proton Photos were taken on an offline map. The basemap
(`basemap-v1.pmtiles`, about 25 MB) is fetched at build time by
`scripts/fetch-basemap.sh`, checked against its pinned SHA-256, and stored
uncompressed in the APK. Rust reads it straight from the APK while Places is
open and renders tiles with `proton-map-rs` on two background threads, nearest
the centre first; a parent tile stands in until a tile is ready. Rendered
tiles are kept up to 64 MB. Markers come from the same layout as the desktop
app (`proton-gallery-rs` clustering and label placement) and show the newest
photo of each place through the grid's thumbnail loader. Without the basemap
the map draws coarse land outlines.

## Visual direction and icon

The theme is the suite's, shared with Proton Stream, Proton Drive for Linux and their websites: violet actions, lavender surfaces, plum text and plum-black dark chrome, with copper as a small highlight. It is inspired by [Proton's visual identity](https://proton.me/blog/new-visual-universe); the custom gallery mark is related to the user's Proton Stream icon, not an official Proton logo. Proton Stream's `docs/DESIGN-TOKENS.md` holds the full token table.

| Token | Light | Dark |
| --- | --- | --- |
| Canvas | `#FAF9FC` | `#100E17` |
| Surface / card | `#FFFFFF` / `#F1EEF8` | `#17141F` / `#1E1A29` |
| Text | `#1B1340` | `#F4F2FA` |
| Accent | `#6D4AFF` | `#8A6DFF` |
| Accent surface | `#ECE7FF` | `#372580` |
| Muted text | `#615970` | `#AAA4BD` |
| Copper | `#B47456` | `#D69B77` |

Dark mode's accent is violet 400 rather than 500, because Material also writes text in `primary`. Type is Inter (`res/font`, under the OFL), with Inter Display for the largest sizes; a script Inter does not draw falls back to the system font glyph by glyph, so native language coverage is unchanged. Font scaling remains enabled. Corners follow the suite's scale (`GalleryShapes.kt`): 6 for a badge, 8 for a small control, 12 for album cards, 16 for sheets and floating panels, 24 for what floats over a photo, and pills for buttons and the section switch. Photo tiles have square corners and 2dp gutters, so the grid reads as one sheet of photographs. Row heights adapt to photo shapes, width and zoom.

The violet-to-orchid gradient of the icons' faces appears in three places only: the chosen section, the sign-in button, and the activity bar under the top bar. Everything else is solid, so the photographs stay the brightest thing on screen. Copper marks a photo that is only on this phone. The app menu opens with the mark and the name.

The icon uses frontal violet–pink photo plates with shallow metallic copper depth, simple mountains, and a copper sun. Its canonical [SVG](../images/logo.svg) and [512px PNG](../images/icon-512.png) share the same vector artwork as the Android account-screen logo, adaptive foreground, and themed monochrome layer. Run `python scripts/render-branding.py` to regenerate them together, or add `--check` to verify the outputs. Earlier artwork is preserved in `images/branding-archive/`; the [branding guide](../images/BRANDING.md) describes platform sizing. Attribution is in [NOTICE](../NOTICE.md).

```text
( Photos | Drive )                 [+] (N)
Sep 12, 2026
[  landscape  ][portrait][ landscape ]
[portrait][     landscape     ]

<- Japan                              [edit]
24 items · 2 albums
Albums: Kyoto, Tokyo
Direct photos and videos in justified rows
```

See [current product direction](PRODUCT.md) for the distinction between native Photos, Drive albums, and device imports.

## Performance and storage boundaries

The README shows captures of the Android interface with a demo library.
[Android screenshots](../README.md#screenshots)

- Paging requests 90 records, prefetches 18, and targets a 360-record working set. Both directions use Rust/SQLite keyset seeks. Paging can discard/reload old pages; its size bound is best effort during in-flight work.
- Storage/query FFI calls run on `Dispatchers.IO`; pure Rust layout runs on `Dispatchers.Default`. Rust uses the bounded SQLite actor, with batched paths/shapes. Only server thumbnails (at most 4MiB) cross FFI; originals stay outside this bridge. Source-specific indexes avoid scanning the global timeline for an album.
- One active paging session is retained across configuration changes and cancelled on navigation, preventing stale device/album pages appearing beneath another source header. Android plans within 90-item page boundaries to reduce reflow during eviction; a page tail can be short.
- Grid decoding has two workers, a 384px target, and a 20MiB memory cache. Device thumbnails have a 128MiB disk budget trimmed after batches of writes. Proton thumbnails remain in memory only, keyed by asset/revision; they never enter the plaintext device thumbnail directory. Learned preview shapes feed subsequent smart-grid queries. Disk use can temporarily overshoot between trims.
- Only twelve additional icon vectors are vendored, with their Apache license and provenance; the complete extended icon collection is not dexed into the app.
- The viewer shows a thumbnail before a maximum-2560px decode. Zoom is limited to 4x. Neighbor pages use thumbnails; only the active photo requests a larger decode. Full-resolution editing and animated-image playback are not implemented.
- Picker streams copy with a 64KiB buffer and cancellation checks. Rust streams a second copy/hash, fsyncs it, atomically publishes the original, then commits metadata/membership. Import temporarily needs another original-sized file; reservations and background import jobs remain future work.
- Originals/catalog are below `noBackupFilesDir`; regenerated thumbnails are below `cacheDir`. Backup is disabled. Device imports are app-private plaintext protected by Android's sandbox/device storage, **not** encrypted Proton blocks. This path must not be used to retain decrypted Proton originals.
- Interrupted imports may leave unreferenced originals or staging files, which never appear as successful imports. Reference-aware cleanup and deletion controls remain to be implemented.
- The viewer captures the bounded loaded window. Continuous paging beyond that snapshot remains integration work.

## Build

Requirements: Linux host for the script, JDK 21, Rust 1.96+, `cargo-ndk` 4.x, SDK platform 36/build tools 37.0.0, NDK 30.0.16248370, and Rust targets `aarch64-linux-android` and `x86_64-linux-android`. Minimum Android API is 31. Windows host script support remains to be added; Windows is also a separate desktop target.

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --version 4.1.2 --locked
export JAVA_HOME=/path/to/jdk-21
export ANDROID_HOME=/path/to/android-sdk
export ANDROID_NDK_HOME=/path/to/android-ndk
bash scripts/build-android.sh debug
```

The wrapper pins Gradle 8.14.5. Gradle's `buildRust` task generates Kotlin from the host library and builds both native ABIs under `android/app/build/generated`. Rust sources are tracked for incremental builds. No manual bindings or sibling patches are required.

APK: `android/app/build/outputs/apk/debug/app-debug.apk`. This is a development build.

```bash
bash scripts/build-android.sh check
```

This builds the APK and runs Android lint and Robolectric interaction/layout tests. Procedural fixture images are test-only. Screenshots are under `android/app/build/screenshots`. The signed variant has been installed and launched on a Pixel 6/API 37. Further device checks remain necessary for picker providers, gestures, codecs, background lifecycle, and scrolling/frame times.

## Next integration gates

1. Live-account acceptance: password, TOTP, CAPTCHA, mailbox-password, refresh rotation, restart, offline sign-out, and expired-session recovery. The native implementation exists; real-account validation requires user sign-in.
2. Encrypted persistent revision-specific preview delivery. The Drive folder chooser, Photos sync, and an on-disk WebP thumbnail cache are implemented; very large-library behavior remains to be measured.
3. Shared priorities and byte budgets; cancel obsolete viewport requests.
4. Media3 `DataSource` over Rust ranges is implemented; measure start and seek times on slow networks, check airplane-mode behavior, and confirm range reads for Photos-volume videos on real accounts.
5. Durable item/folder offline jobs, references, quotas, and background execution.
6. Further backup recovery, accessibility, device performance, and release validation. Uploads, capture dates, deletion controls, and release signing are implemented.

## Primary references

- [Android Keystore](https://developer.android.com/privacy-and-security/keystore)
- [Origin-scoped WebView messaging](https://developer.android.com/reference/androidx/webkit/WebViewCompat#addWebMessageListener(android.webkit.WebView,java.lang.String,java.util.Set,androidx.webkit.WebViewCompat.WebMessageListener))
- [Proton verification broadcast protocol](https://github.com/ProtonMail/WebClients/blob/main/applications/verify/src/app/broadcast.ts)
- [Android photo picker](https://developer.android.com/training/data-storage/shared/photo-picker)
- [Compose performance practices](https://developer.android.com/develop/ui/compose/performance/bestpractices)
- [Paging](https://developer.android.com/jetpack/androidx/releases/paging)
- [Media3](https://developer.android.com/jetpack/androidx/releases/media3)
- [UniFFI Kotlin/Gradle integration](https://mozilla.github.io/uniffi-rs/latest/kotlin/gradle.html)

The limits above describe the implementation, not Android device measurements.


## Website screenshots

The website and README use the same public demo photographs as Drive’s photo captures,
with synthetic Gallery metadata. No private account is opened. Ordinary UI tests retain
procedural images; the screenshot job opts into the photo fixtures explicitly.

After a development build has produced the host native library and generated bindings:

```sh
JAVA_HOME=/path/to/jdk-21 scripts/screenshots.sh --photos ~/.cache/pdfs-screenshots
```

The photo directory is the cache populated by Drive’s `scripts/screenshots.sh`, or any
explicitly supplied directory of demo JPEGs. The helper runs the relevant Compose UI tests
and snapshots the photo captures to `android/app/build/photo-screenshots/`, separately
from ordinary test output. The websites’ sync scripts then
copy those captures and regenerate their social previews. The photographs are test inputs
and website assets; they are not bundled with the Android app.

## Signed builds

### Production APK

The production signing profile belongs outside the checkout. With the local
`~/Keys/proton-gallery/release.json` profile and its matching PKCS#12 keystore:

```bash
JAVA_HOME=/path/to/jdk-21 python3 scripts/build-signed-android.py --release
adb install -r android/app/build/outputs/apk/release/app-release.apk
```

The minified build uses package ID `io.narl.protongallery`. Android preserves
app data during an in-place update when the signing certificate matches and
the version code is eligible. To install over Wi-Fi, pair the device in
Android's **Wireless debugging** settings and select its serial with
`adb -s SERIAL install -r` if more than one device is connected.
The default APK contains arm64-v8a and x86_64. Use `--abis arm64-v8a` for a
phone-only build.

Tagged builds use the production signing profile in CI.
[Release workflow and signing](RELEASING.md)

### Separate device-testing APK

A dedicated variant uses the persistent key in `~/Keys/proton-gallery/testing.p12` and private profile `~/Keys/proton-gallery/testing.json`. The alias is `proton-gallery-testing`. The directory is mode 700; both files are mode 600. Credentials are not stored in the checkout, printed, or passed on command lines.

```bash
JAVA_HOME=/path/to/jdk-21 python3 scripts/build-signed-android.py
```

The helper also accepts `--signing-config /path/to/private/profile.json`. Its private JSON keys are `keystore` (relative to the profile), `alias`, `store_password`, and `key_password`. The Android Gradle config reads credentials only from the helper's environment; native build tools do not inherit the passwords. The helper disables Gradle configuration caching and long-lived compiler/build daemons for this invocation.

Output: `android/app/build/outputs/apk/device/app-device.apk`. The launcher label is **Proton Gallery Testing**, package `io.narl.protongallery.testing`, so it can coexist with the ordinary development package. This is a debug-based development build, not an optimized production release. The default includes arm64-v8a and x86_64; `--abis arm64-v8a` produces a smaller phone-only update. Android 12/API 31 or later is required.

Keep the keystore and profile together to sign future updates with the same certificate. Use Photos → Allow access to see the phone's photos, and Sign in to Proton to connect your account.

## Isolated device checks

`python3 scripts/build-signed-android.py --abis arm64-v8a --instrumentation` also builds the testing-variant instrumentation APK. `SessionStorageDeviceTest` uses a fresh probe alias, blob, and temporary catalog; it never opens the account blob or user media. It checks actual Keystore encryption, fresh GCM nonces, tamper rejection, atomic reopening, Kotlin callbacks into Rust, and offline SDK session reconstruction. No real credentials or account requests are involved.

TLS uses rustls-platform-verifier 0.6.2 and its matching Android 0.1.1 AAR, resolved from Cargo metadata. The single JNI bootstrap passes the application context before constructing SDK clients; all product methods continue to use UniFFI.

For faster phone iteration, set `PG_ANDROID_ABIS=arm64-v8a` for check/debug builds, or pass `--abis arm64-v8a` to the signing helper. The default remains arm64 plus x86_64; the chosen list controls both native compilation and APK packaging.
