# Changelog

## Unreleased

## 0.5.0

### Install

- Android 12 or later. Download the signed `proton-gallery-v0.5.0.apk` for
  arm64 or x86_64. It updates the regular Proton Gallery app in place.
- Windows 10 and 11 (x86_64). Download `proton-gallery-0.5.0-x64.msi`, or the
  portable `proton-gallery-0.5.0-x86_64-windows.zip`.
- Ubuntu 24.04 and Debian 13. Download the x86_64 `.deb`.
- Fedora. Download the signed x86_64 `.rpm` and the accompanying public key.
- Arch Linux. Check out this tag and run `cd packaging && makepkg -fi`.

### Windows

- Windows 10 and 11 (x86_64) get a release download: an installer
  (`proton-gallery-<version>-x64.msi`) and a portable zip. Both include
  everything the app needs, with the Places basemap and GStreamer for video.
- Videos play on Windows as they do on Linux, with seeking and volume. Hardware
  decoding goes through Media Foundation and Direct3D 11. Other formats are
  decoded by FFmpeg.
- The Windows app has its own icon, and a release build no longer opens a
  console window next to it.

## 0.4.0

### Install

- Android 12 or later. Download the signed `proton-gallery-v0.4.0.apk` for
  arm64 or x86_64. It updates the regular Proton Gallery app in place.
- Ubuntu 24.04 and Debian 13. Download the x86_64 `.deb`.
- Fedora. Download the signed x86_64 `.rpm` and the accompanying public key.
- Arch Linux. Check out this tag and run `cd packaging && makepkg -fi`.
  Windows users can build the desktop app from source.

### Places

- Places draws an offline basemap on desktop and Android: seas by depth,
  lakes, rivers, built-up areas, borders and town names, in light and dark,
  rendered tile by tile in the background through `proton-map-rs`. The map
  works offline, and opening Places sends no request.
- Markers show a photo from each place with its count, never overlap, and
  only name a place where the name fits. Zoom and fit sit in a floating pill,
  with a scale bar and the map credits on the map.
- Choosing a place opens its photos: a side panel on desktop, a sheet on
  Android. Progress shows as a chip with a bar while photos are checked.
- The basemap ships inside the APK (about 25 MB). The Linux packages fetch it
  with `scripts/fetch-basemap.sh`, which checks it against a pinned SHA-256.
  Without the file the map falls back to coarse outlines.

### Thumbnails

- Grid thumbnails load faster. A running sync no longer reloads the desktop
  timeline every few seconds or drops thumbnails in flight, and on Android a
  tile that scrolls away frees its download slot at once. Each thumbnail
  needs fewer requests to Proton (proton-sdk 0.12.3).
- The desktop grid keeps thumbnails on disk (256 MiB, least recently used
  dropped first), so a new launch shows the library without downloading them
  again. Hidden-album thumbnails are never written to disk, and signing out
  deletes the kept ones.
- Android shows thumbnails for Proton photos that were uploaded without one,
  such as Google Photos imports from older Proton Drive Linux releases. The
  app downloads the original once (images up to 40 MB, or the first second of
  a video), makes the thumbnail on the phone and keeps it in the thumbnail
  cache. At most two such downloads run at a time, only for tiles on screen,
  and never after a failed fetch. Photos Android cannot decode are
  remembered and not downloaded again.

### Desktop

- Unlock Hidden albums with your system sign-in through polkit on Linux or
  Windows Hello on Windows. Existing hidden settings are preserved.
- HD videos no longer flicker. Frames go straight into one GPU texture
  instead of being handed over as new images, one redraw late.
- A video that needs a codec which isn't installed now says which one. The
  Linux packages pull in GStreamer's bad plugins (H.264/H.265 parsers,
  hardware decoding).

### Sign-in and shared code

- Sign-in runs the same password, second-factor and mailbox-password steps
  as Proton Drive Linux and Proton Stream, through `proton_sdk::login`.
  Saved sign-ins and messages are unchanged.
- The desktop app keeps its sign-in through `proton-auth-rs`, the keyring
  store shared with Proton Drive Linux and Proton Stream. It reconnects when
  the Secret Service restarts, and on Linux a refreshed sign-in replaces its
  keyring item instead of editing it, so oo7 before 0.7.0 no longer loses it
  on reboot.
- The catalog, Proton adapter and app services moved to the
  [proton-rs](https://github.com/narrrl/proton-rs) workspace as
  `proton-gallery-rs`, `proton-gallery-drive-rs` and `proton-gallery-app-rs`,
  shared with Proton Drive Linux through crates.io. No behaviour change.

## 0.3.0

### Install

- Android 12 or later. Download the signed `proton-gallery-v0.3.0.apk` for
  arm64 or x86_64. It updates the regular Proton Gallery app in place.
- Ubuntu 24.04 and Debian 13. Download the x86_64 `.deb`.
- Fedora. Download the signed x86_64 `.rpm` and the accompanying public key.
- Arch Linux. Check out this tag and run `cd packaging && makepkg -fi`.
  Windows users can build the desktop app from source.

### Desktop and Drive

- Browse and manage Drive galleries inline in Proton Drive Linux Photos, with
  album covers, nested folders, public links, and gated access to hidden albums.
- Desktop gallery zoom uses Ctrl+mousewheel and keeps the pointer anchored.
- Drive gallery folders use cover cards in the same scroll area as their media.
- Browse Proton Photos with incremental sync, complete album loading state, and
  more responsive photo grids.

### Android

- Background Photos sync uses incremental Proton volume event cursors, with
  improved thumbnails and clearer loading and deletion updates.
- Hidden galleries can use covers from nested folders without exposing their
  contents in the public album list.
- Choose Android album covers from nested folders.

## 0.2.0

### Install

- Android 12 or later. Download the signed `proton-gallery-v0.2.0.apk` for
  arm64 or x86_64. It updates the regular Proton Gallery app in place.
- Ubuntu 24.04 and Debian 13. Download the x86_64 `.deb`.
- Fedora. Download the signed x86_64 `.rpm` and the accompanying public key.
  [Linux installation and signature verification](https://github.com/narrrl/proton-gallery/blob/v0.2.0/packaging/README.md)
- Arch Linux. Check out this tag and run `cd packaging && makepkg -fi`.
  Windows users can build the desktop app from source.
  [Desktop build guide](https://github.com/narrrl/proton-gallery/blob/v0.2.0/docs/DESKTOP.md)

### Desktop

- Browse Proton Photos albums and favorites in the redesigned Iced desktop
  app. Adjust grid density and open still photos with keyboard, zoom, and pan controls.
- Add Drive folders from **My files**, browse their media and nested albums,
  and unlock hidden albums with a local Gallery passphrase.
- Play and seek supported Proton videos on Linux with GStreamer. Windows
  displays a still preview for video.
- Install Linux launchers and their PNG and SVG icons through the native package manager.

### Android

- Photos and Albums now show a loading state while their contents arrive.
  Refresh becomes available after an empty result is confirmed, and progress
  indicators clear when their work completes.
- Manual and selection backups show when work is queued, running, or finished.

### Sync and sharing

- Gallery synchronizes selected Drive folders, album presentation settings,
  and hidden visibility through an encrypted Proton Drive device called
  **Proton Gallery settings**. Background settings checks use Proton volume
  event cursors. Media-library refresh still scans the selected scope.
- Create, copy, and revoke public viewer links for owned Drive folders.
  A link can have a password and expiry. Proton Photos albums use their own
  sharing model, and Gallery's public-link action is available in Drive.

**Upgrading with hidden albums?** Refresh the upgraded Android app first to
import settings already on your phone, then refresh your other Gallery devices.
Hiding controls visibility inside Gallery and its Android cloud picker.
The originals remain accessible in Proton Photos or Drive and through existing shares.

## 0.1.0

- Initial signed Android release with a combined phone and Proton Photos
  timeline, Proton Photos albums and favorites, automatic phone-folder backup,
  nested Drive galleries, and Android photo-picker integration.
- Included the source build for the Iced Photos browser and a local Arch recipe.
