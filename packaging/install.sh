#!/usr/bin/env bash
# Shared file layout for makepkg, dpkg-deb and rpmbuild.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
dest=${1:?Usage: packaging/install.sh DESTDIR [BINARY]}
binary=${2:-$root/target/release/proton-gallery}
install -Dm755 "$binary" "$dest/usr/bin/proton-gallery"
install -Dm644 "$root/packaging/io.narl.proton-gallery.desktop" "$dest/usr/share/applications/io.narl.proton-gallery.desktop"
install -Dm644 "$root/packaging/io.narl.proton-gallery.policy" "$dest/usr/share/polkit-1/actions/io.narl.proton-gallery.policy"
install -Dm644 "$root/images/logo.svg" "$dest/usr/share/icons/hicolor/scalable/apps/io.narl.proton-gallery.svg"
install -Dm644 "$root/images/icon-512.png" "$dest/usr/share/icons/hicolor/512x512/apps/io.narl.proton-gallery.png"
# A direct PNG path also works with launchers holding a stale theme-name cache.
install -Dm644 "$root/images/icon-512.png" "$dest/usr/share/pixmaps/proton-gallery.png"
# The offline Places basemap, fetched by scripts/fetch-basemap.sh.
install -Dm644 "$root/data/basemap-v1.pmtiles" "$dest/usr/share/proton-gallery/basemap-v1.pmtiles"
install -Dm644 "$root/LICENSE" "$dest/usr/share/licenses/proton-gallery/LICENSE"
install -Dm644 "$root/third-party/NILS-PUKROPP-MIT.txt" "$dest/usr/share/licenses/proton-gallery/NILS-PUKROPP-MIT.txt"
for file in README.md NOTICE.md docs/DESKTOP.md packaging/README.md docs/images/desktop-gallery-light.png docs/images/desktop-gallery-dark.png; do
  install -Dm644 "$root/$file" "$dest/usr/share/doc/proton-gallery/$file"
done
