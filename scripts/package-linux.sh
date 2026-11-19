#!/usr/bin/env bash
# Like proton-drive-linux: dpkg-deb staging plus a prebuilt rpmbuild spec.
set -euo pipefail
root=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
format=all
case "${1:-}" in
  deb|rpm|all) format=$1; shift;;
esac
binary=""
out="$root/dist"
while (($#)); do
  case "$1" in
    --binary) binary=$(realpath "${2:?--binary needs a file}"); shift 2;;
    --out-dir) out=$(realpath -m "${2:?--out-dir needs a directory}"); shift 2;;
    *) echo 'Usage: scripts/package-linux.sh [deb|rpm|all] [--binary FILE] [--out-dir DIR]' >&2; exit 2;;
  esac
done
[[ $(uname -m) == x86_64 ]] || { echo 'Only x86_64 is currently supported.' >&2; exit 2; }
if [[ $format != rpm ]]; then command -v dpkg-deb >/dev/null || { echo 'Install dpkg (dpkg-deb) to build .deb packages.' >&2; exit 1; }; fi
if [[ $format != deb ]]; then command -v rpmbuild >/dev/null || { echo 'Install rpm-build (rpmbuild) to build .rpm packages.' >&2; exit 1; }; fi
version=$(sed -n 's/^version = "\([^"]*\)"/\1/p' "$root/Cargo.toml")
release=$(sed -n 's/^pkgrel=\([0-9]*\)$/\1/p' "$root/packaging/PKGBUILD")
[[ $version =~ ^[0-9]+\.[0-9]+\.[0-9]+$ && -n $release ]] || {
  echo 'Expected a numeric Cargo version and a package release in the PKGBUILD.' >&2
  exit 2
}
if [[ -z $binary ]]; then
  (cd "$root" && cargo build --release --locked -p pg-desktop --bin proton-gallery)
  binary="$root/target/release/proton-gallery"
fi
[[ -x $binary ]] || { echo "Not an executable: $binary" >&2; exit 1; }
if ! LC_ALL=C readelf -h "$binary" | grep -q 'Machine:.*X86-64'; then
  echo "Not an x86_64 Linux executable: $binary" >&2
  exit 1
fi
mkdir -p "$out"
stage=$(mktemp -d "$out/.package-XXXXXX")
trap 'rm -rf -- "$stage"' EXIT
if [[ $format != rpm ]]; then
  bash "$root/packaging/install.sh" "$stage/deb" "$binary"
  cat "$root/LICENSE" "$root/NOTICE.md" "$root/third-party/NILS-PUKROPP-MIT.txt" \
    > "$stage/deb/usr/share/doc/proton-gallery/copyright"
  mkdir -p "$stage/deb/DEBIAN"
  # Record the actual binary's glibc floor, even when packaging outside Ubuntu CI.
  glibc=$(LC_ALL=C readelf --version-info "$binary" | sed -n 's/.*Name: GLIBC_\([0-9.]*\).*/\1/p' | sort -Vu | tail -1)
  [[ -n $glibc ]] || { echo 'Could not determine the executable glibc requirement.' >&2; exit 1; }
  cat > "$stage/deb/DEBIAN/control" <<CONTROL
Package: proton-gallery
Version: $version-$release
Section: graphics
Priority: optional
Architecture: amd64
Maintainer: Nils Pukropp <contact@narl.io>
Depends: libc6 (>= $glibc), libgcc-s1, libdbus-1-3, libfontconfig1, libxkbcommon0, libxkbcommon-x11-0, libx11-6, libxcursor1, libxi6, libxrandr2, libwayland-client0, libwayland-cursor0, libwayland-egl1, libegl1, libgl1, libvulkan1, hicolor-icon-theme, xdg-utils, ca-certificates, gstreamer1.0-plugins-base, gstreamer1.0-plugins-good, gstreamer1.0-libav
Suggests: gnome-keyring
Installed-Size: $(du -sk "$stage/deb/usr" | cut -f1)
Homepage: https://github.com/narrrl/proton-gallery
Description: Unofficial native Proton Photos browser
 Browse Proton Photos and Drive galleries with a native photo and video viewer.
 Unofficial and not affiliated with Proton AG.
CONTROL
  install -m755 "$root/packaging/deb/postinst" "$stage/deb/DEBIAN/postinst"
  install -m755 "$root/packaging/deb/postrm" "$stage/deb/DEBIAN/postrm"
  dpkg-deb --root-owner-group --build "$stage/deb" "$out/proton-gallery_${version}-${release}_amd64.deb"
fi
if [[ $format != deb ]]; then
  mkdir -p "$stage/rpm"/{BUILD,RPMS,SOURCES,SPECS,SRPMS,BUILDROOT,db,tmp}
  rpmbuild -bb --nodeps --define "_topdir $stage/rpm" --define "_dbpath $stage/rpm/db" \
    --define "_tmppath $stage/rpm/tmp" \
    --define "git_dir $root" --define "binary $binary" --define 'prebuilt 1' \
    --define "version $version" --define "package_release $release" "$root/packaging/proton-gallery.spec"
  cp "$stage/rpm/RPMS/x86_64/"*.rpm "$out/"
fi
