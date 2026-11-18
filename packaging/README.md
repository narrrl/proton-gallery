# Linux packages

The packages target x86_64 and install `proton-gallery`, the **Proton Gallery**
launcher and PNG and SVG icons. The launcher uses the packaged PNG directly,
including in sessions with a cached icon theme. Package removal leaves per-user
app data intact.

## Arch Linux

Build and install the desktop app from this checkout, following the local-build
package convention used by Proton Drive for Linux and Proton Stream:

```sh
cd packaging
makepkg -fi
```

If makepkg reports missing dependencies, use `makepkg -sfi` to install them through
pacman, then build and install. Arch's `base-devel` package group and a Rust stable
toolchain at least as new as the workspace minimum (1.96) are required. The PKGBUILD
selects `stable` when using rustup. Cargo may download locked dependencies; no
sibling SDK checkout, Android SDK, JDK or Gradle is needed.

The package builds the current working tree in release mode using `Cargo.lock`
and reuses the repository's `target/` directory. Its version comes from the root
`Cargo.toml`. This is a local package recipe, not an AUR source-fetch package.
Run makepkg as your normal user; pacman asks for privileges to install the result.

Launch **Proton Gallery** from your application menu, or run:

```sh
proton-gallery
proton-gallery --demo 100000
```

## Debian, Ubuntu and Fedora

The Linux packages workflow builds `.deb` and `.rpm` files on Ubuntu 24.04.
Download the matching package from a GitHub Actions run or a release that includes
Linux assets. Install it through the native package manager so it can resolve
dependencies:

```sh
# Ubuntu 24.04 or Debian 13
sudo apt install ./proton-gallery_*_amd64.deb

# Fedora. Compare the downloaded public key's fingerprint before importing.
gpg --show-keys --fingerprint RPM-GPG-KEY-proton-gallery
sudo rpmkeys --import RPM-GPG-KEY-proton-gallery
rpmkeys --checksig ./proton-gallery-*.x86_64.rpm
sudo dnf install ./proton-gallery-*.x86_64.rpm
```

The release key fingerprint is `1E28 F055 A4B3 EC28 7675 F101 28B9 DC0E 6A5C 9C52`.
Tagged releases include the public key beside the RPM. Standalone workflow
artifacts and local builds are unsigned. The release workflow signs and verifies
the RPM with `RPM_SIGNING_SUBKEY` and `RPM_SIGNING_PASSPHRASE` from Actions
secrets. Keep the primary private key outside CI. When rotating the signing
subkey, update the checked-in public key and signer fingerprint before the next
release.

From the repository root, build both archives with `scripts/package-linux.sh`.
Pass `deb` or `rpm` to build one format. The script needs the normal Rust build
dependencies, `readelf` from binutils, and `dpkg-deb` or `rpmbuild` for each
requested format. Pass `--binary path/to/proton-gallery` to package an existing
x86_64 Linux build. Output defaults to `dist/`. Packages built locally inherit
the binary's minimum glibc version.

## Runtime requirements

A working Wayland or X11 session, graphics driver and fonts are needed. Saved
sessions require a running Secret Service provider, such as GNOME Keyring or
KWallet with Secret Service enabled. Without one, the app offers a temporary
session. `xdg-utils` provides the browser-opening command used by CAPTCHA sign-in.
See [desktop behavior and acceptance gaps](../docs/DESKTOP.md).
