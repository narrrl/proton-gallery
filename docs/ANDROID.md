# Native Android gallery

Kotlin/Compose calls `pg-mobile` through generated UniFFI bindings. Rust owns the catalog, ordering, paging, content identity, durable import publication, and metadata. No sibling repository or companion service is needed.

## Implemented experience

- One slim top bar holds a **Photos | Drive** switch (the screen title and the navigation in one control) and the account button. It slides away while scrolling down and returns on the way up. Inside an album or the device library it shows back, the title, and that place's action (edit album, add photos).
- **Photos** is the default and queries only the normal Proton Photos source.
- **Drive** reads selected gallery roots and nested folder albums from the Rust catalog. An album shows its item and album counts, description and tags above its albums and media.
- A shared Rust smart grid lays out portraits and landscapes in justified, edge-to-edge rows with 2dp gaps and square corners. Pinch with two fingers to zoom (target row height 48–240dp, default 120dp, saved). Headings follow the zoom like the Linux app: days for large tiles, months below 104dp, years below 64dp. The first visible photo stays in view while zooming.
- Pull down to refresh Photos, or the Drive gallery on screen. The account button shows a ring while anything is being read.
- The account button opens one sheet: account, refresh, **On this device**, and theme (device, light, dark). On this device keeps its system picker, private copies, local photo zoom/swipe, and Media3 playback.
- Native adaptive and monochrome launcher icons accompany the SVG/PNG mark.

Native sign-in supports SRP passwords, authenticator codes, separate mailbox passwords, and Proton-hosted CAPTCHA. Sessions resume without a network round trip; Photos sync starts separately. Android Keystore protects the token/derived-key blob with AES-256-GCM and atomic writes under `noBackupFilesDir`. Login/mailbox passwords are not persisted. Sign-out cancels work, clears the saved session, hides that account’s catalog, drops remote preview caches, and attempts server revocation with a short timeout. Imported device media remains available.

Android can sync normal Proton Photos and load small server thumbnails without originals. The SDK still enumerates timeline IDs before returning metadata pages; each committed page becomes queryable while later pages decrypt. A persistent encrypted remote preview cache, original viewing, and range-backed remote video playback remain unfinished. Remote items are not marked available offline. The app requests Internet access with cleartext traffic disabled.

The sign-in dialog blocks screenshots and keeps password/code input out of saved instance state. Proton CAPTCHA runs separately from credential input, with origin-scoped WebView messaging limited to `https://verify.proton.me`. Security-key-only accounts and email/SMS verification are reported as unsupported. An invalid authenticator attempt restarts sign-in because Proton may consume its pending session.

Device dates mean **added to this app**. Photos chronology uses the server's capture date; ordinary Drive currently uses remote modification time. Identical device bytes deduplicate, and reimport repairs the stored copy while preserving its original title/date. Unsupported images can import but fail to decode; viewing reports that failure.

### Drive galleries and album settings

Drive → + (Add gallery) opens a folder chooser over My files. The chosen folder becomes a gallery; every folder inside it, at any depth, becomes an album. Adding or refreshing a gallery reads metadata and server thumbnails only. Removing a gallery forgets its album tree in this app; nothing is deleted from Drive.

Photos sync and Drive gallery work run independently, each with its own lock and progress counter in `pg-mobile` (`sync_progress`, `drive_progress`) and its own job in the view model. A gallery can be added while a large Photos library is still syncing.

Each album has local settings (catalog schema v5, `album_settings` and `album_tags`). None of them is written back to Drive:

- Name and description. The folder keeps its Drive name.
- Cover: a chosen photo or video from the album or one of its subalbums. By default, the album uses its newest item.
- Tags (up to 20, case-insensitive). The Drive tab filters by tag across all galleries.
- Pin to top, and hide from the album list (with a "Show hidden albums" toggle).

The interface shows everything newest first, and the grid's zoom sets the date headings. The catalog still stores grouping and order per album (`album_settings.grouping`, `sort_order`, with a case-insensitive name index), but the app no longer offers them.

Sign-in uses the app version `external-drive-protongallery@…`. The API splits that value on `-`. The earlier `external-drive-proton-gallery` was refused with `2064 Invalid section name`, which the app showed as "Couldn’t reach Proton". Rust log records go to logcat with the tag `pg-rust` (Info and above). Transport failures log their full cause chain there; the interface still shows a short message.

Certificate revocation: Proton's API certificate comes from Let's Encrypt, which no longer runs OCSP. Android's revocation check then has to fetch the CA's revocation list (CRL), and CRLs are served over plain HTTP. The app used to forbid all cleartext traffic, so the check failed. rustls-platform-verifier 0.6.2 reports that as `invalid peer certificate: Revoked`, and sign-in showed "Couldn’t reach Proton". `res/xml/network_security_config.xml` is copied from rustls-platform-verifier (it ships there from 0.7.1). It allows cleartext only to CA revocation-list hosts; every other host stays HTTPS-only. Remove it once the app moves to 0.7.1 or later. That needs a reqwest that uses 0.7, because reqwest and the app must initialize the same verifier copy.

Connection check: after a failed sign-in, the account screen offers **Test connection**. It reports, as copyable text, whether the certificate verifier started, then name lookup, the TCP connect for each address family, and an unauthenticated `tests/ping` through the app's own HTTP client. It also lists the last 30 Rust warnings. The report has no credentials. `cargo test -p pg-mobile -- --ignored live` runs the same check, plus a sign-in with a made-up account, from the host.

Crash report: `CrashLog` keeps the stack trace of an uncaught Kotlin exception and, on the next start, also reads Android's record of a native crash or freeze (`ApplicationExitInfo`). From a native tombstone it keeps only frame and abort strings, never raw memory. A Rust panic hook writes the last panic to `gallery/last-panic.txt`, which is added to the report. The app shows the report once, with a Copy button. Background tasks in the view model run under a `CoroutineExceptionHandler`, so an unexpected failure shows a message instead of closing the app.

Native code: calls into Rust run on a pool of 8 threads with 8 MB stacks, because each call blocks on async Rust and Android's pooled threads have about 1 MB. The Android library is built with `--release`.

Build with JDK 21 (`JAVA_HOME=/usr/lib/jvm/java-21-openjdk`). Gradle 8.14 does not run on JDK 27 and fails with only the version string `27.0.1.0.3` as its message.

Emulator: a separate SDK with an emulator and an Android 15 x86_64 image is in `~/.local/share/proton-gallery-emulator` (AVD `pgtest`), because `/opt/android-sdk` is root-owned and has no emulator. Build with `--abis arm64-v8a,x86_64` to run the testing APK there.

## Visual direction and icon

The Proton-style theme uses violet actions, lavender surfaces, plum text/dark chrome, and quiet photo gutters. It is inspired by [Proton's visual identity](https://proton.me/blog/new-visual-universe); the custom gallery mark is related to the user's Proton Stream icon, not an official Proton logo.

| Token | Light | Dark |
| --- | --- | --- |
| Canvas | `#FAF9FC` | `#171324` |
| Text | `#1B1340` | `#F4F0FF` |
| Accent | `#6D4AFF` | `#BDA9FF` |
| Accent surface | `#ECE7FF` | `#372580` |
| Muted text | `#615970` | `#BFB5D1` |
| Placeholder | `#F0EDF7` | `#30283F` |

Android's sans-serif family supplies native language coverage. Font scaling remains enabled. Photo tiles have square corners and 2dp gutters, so the grid reads as one sheet of photographs; album cards keep 12dp corners. Row heights adapt to photo shapes, width and zoom. Solid action colors keep photographs prominent; gradients are reserved for the app mark.

The icon uses Proton Stream's violet tile, darker lower edge, pale glyph, and three-piece rhythm. The original gallery glyph is a landscape/photo frame cut into three strips. Its canonical [SVG](../images/logo.svg) and [512px PNG](../images/icon-512.png) can be reused by desktop packaging. Android has a vector in-app icon, safe-zone foreground/background layers, and a themed monochrome layer. Attribution is in [NOTICE](../NOTICE.md).

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

Reviewed screenshots use procedural test images, not a signed-in Proton account: [phone library, light theme](images/android-library-light.png) and [appearance sheet, dark theme](images/android-appearance-dark.png). The fixtures are not bundled with the application. The [nested Drive album](images/android-nested-album-light.png) illustrates the same smart grid and breadcrumbs.

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

Requirements: Linux host for the script, JDK 17, Rust 1.96+, `cargo-ndk` 4.x, SDK platform 36/build tools 37.0.0, NDK 30.0.16248370, and Rust targets `aarch64-linux-android` and `x86_64-linux-android`. Minimum Android API is 31. Windows host script support remains to be added; Windows is also a separate desktop target.

```bash
rustup target add aarch64-linux-android x86_64-linux-android
cargo install cargo-ndk --locked
export JAVA_HOME=/path/to/jdk-17
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
2. A real Drive folder chooser and encrypted persistent revision-specific preview delivery. Photos metadata and memory-only thumbnails are wired up; very large-library behavior remains to be measured.
3. Shared priorities and byte budgets; cancel obsolete viewport requests.
4. Media3 `DataSource` over Rust ranges, followed by real seek/airplane-mode checks before selecting the final Proton player.
5. Durable item/folder offline jobs, references, quotas, and background execution.
6. Upload queue, capture-date extraction, deletion controls, accessibility/device performance, and release signing.

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


## Signed device-testing build

A dedicated variant uses the persistent key in `~/Keys/proton-gallery/testing.p12` and private profile `~/Keys/proton-gallery/testing.json`. The alias is `proton-gallery-testing`. The directory is mode 700; both files are mode 600. Credentials are not stored in the checkout, printed, or passed on command lines.

```bash
JAVA_HOME=/path/to/jdk-17 python3 scripts/build-signed-android.py
```

The helper also accepts `--signing-config /path/to/private/profile.json`. Its private JSON keys are `keystore` (relative to the profile), `alias`, `store_password`, and `key_password`. The Android Gradle config reads credentials only from the helper's environment; native build tools do not inherit the passwords. The helper disables Gradle configuration caching and long-lived compiler/build daemons for this invocation.

Output: `android/app/build/outputs/apk/device/app-device.apk`. The launcher label is **Proton Gallery Testing**, package `io.narl.protongallery.testing`, so it can coexist with the ordinary development package. This is a debug-based development build, not an optimized production release. The default includes arm64-v8a and x86_64; `--abis arm64-v8a` produces a smaller phone-only update. Android 12/API 31 or later is required.

Keep the keystore and profile together to sign future updates with the same certificate. For local viewing tests, open the account button → On this device → Choose photos. The main Photos/Drive structure remains separate. Use Photos → Sign in to Proton to connect your account.

## Isolated device checks

`python3 scripts/build-signed-android.py --abis arm64-v8a --instrumentation` also builds the testing-variant instrumentation APK. `SessionStorageDeviceTest` uses a fresh probe alias, blob, and temporary catalog; it never opens the account blob or user media. It checks actual Keystore encryption, fresh GCM nonces, tamper rejection, atomic reopening, Kotlin callbacks into Rust, and offline SDK session reconstruction. No real credentials or account requests are involved.

TLS uses rustls-platform-verifier 0.6.2 and its matching Android 0.1.1 AAR, resolved from Cargo metadata. The single JNI bootstrap passes the application context before constructing SDK clients; all product methods continue to use UniFFI.

For faster phone iteration, set `PG_ANDROID_ABIS=arm64-v8a` for check/debug builds, or pass `--abis arm64-v8a` to the signing helper. The default remains arm64 plus x86_64; the chosen list controls both native compilation and APK packaging.
