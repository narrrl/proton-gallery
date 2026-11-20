# Changelog

## Unreleased

- The catalog, Proton adapter and app services moved to the
  [proton-rs](https://github.com/narrrl/proton-rs) workspace as
  `proton-gallery-rs`, `proton-gallery-drive-rs` and `proton-gallery-app-rs`,
  shared with Proton Drive Linux through crates.io. No behaviour change.

- The desktop app keeps its sign-in through `proton-auth-rs`, the keyring store
  shared with Proton Drive Linux and Proton Stream. It reconnects when the
  Secret Service restarts, and on Linux a refreshed sign-in replaces its
  keyring item instead of editing it, so oo7 before 0.7.0 no longer loses it
  on reboot. Existing saved sign-ins keep working.

- Unlock Hidden albums on desktop with your system sign-in through polkit on
  Linux or Windows Hello on Windows. Existing hidden settings are preserved.

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
