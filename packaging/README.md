# Arch Linux local package

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

The package installs the desktop binary, menu entry, existing SVG/PNG branding,
license, notices and desktop documentation. It does not install the Android app
or the diagnostic CLI. Package removal leaves per-user app data untouched.

A working Wayland or X11 session, graphics driver and fonts are needed. Saved
sessions require a running Secret Service provider, such as GNOME Keyring or
KWallet with Secret Service enabled. Without one, the app offers a temporary
session. `xdg-utils` provides the browser-opening command used by CAPTCHA sign-in.
See [desktop behavior and acceptance gaps](../docs/DESKTOP.md).
