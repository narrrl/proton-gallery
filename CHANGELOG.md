# Changelog

## Unreleased

- Desktop Drive galleries and subfolders use cover cards in the same scroll area as their media.
- Android can choose an album cover from its subfolders.

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
