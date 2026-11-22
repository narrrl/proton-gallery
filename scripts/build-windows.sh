#!/usr/bin/env bash
#
# Windows build and packaging of the desktop app, cross-compiled on Linux.
#
#   scripts/build-windows.sh               # exe, zip and msi
#   scripts/build-windows.sh --skip-build  # repackage dist/windows as staged
#
# Artifacts land in dist/:
#   dist/windows/                                  the portable payload
#   dist/proton-gallery-<version>-x86_64-windows.zip
#   dist/proton-gallery-<version>-x64.msi
#
# The target is x86_64-pc-windows-gnu: mingw-w64 is a real cross compiler and
# GStreamer publishes a mingw build, so everything links here with no Windows
# box. Video plays through that GStreamer, which ships inside the app in the
# layout of GStreamer's own installer:
#
#   bin\proton-gallery.exe and every DLL it loads
#   lib\gstreamer-1.0\       the plugins video playback needs, and only those
#   share\proton-gallery\    the Places basemap
#
# libgstreamer finds lib\gstreamer-1.0 relative to its own DLL, so the app sets
# no environment at all. gst-plugin-scanner.exe stays out on purpose: without
# it GStreamer reads the plugins' details in-process, once, rather than in a
# helper that would need its own copy of every DLL in bin.
#
# Prerequisites (Arch names; every distro has them):
#   mingw-w64-gcc msitools zip curl              pacman -S mingw-w64-gcc msitools zip
#   Debian/Ubuntu:                               apt install mingw-w64 msitools wixl zip
#   rustup target add x86_64-pc-windows-gnu
# The GStreamer runtime and development MSIs (~800 MB) are downloaded once into
# target/windows-deps, checked against pinned SHA-256s, and unpacked there.

set -euo pipefail

readonly ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
readonly DIST="$ROOT/dist"
readonly OUTDIR="$DIST/windows"
readonly PKG="$ROOT/packaging/windows"
readonly DEPS="$ROOT/target/windows-deps"
readonly TARGET="x86_64-pc-windows-gnu"
readonly PKGNAME="proton-gallery"

readonly GST_VERSION="1.26.10"
readonly GST_URL="https://gstreamer.freedesktop.org/data/pkg/windows/$GST_VERSION/mingw"
readonly GST_RUNTIME="gstreamer-1.0-mingw-x86_64-$GST_VERSION.msi"
readonly GST_RUNTIME_SHA256="709408361169e1f2a644ada0c5bf58625236e9d740e9ddf1afb5272fcce485ad"
readonly GST_DEVEL="gstreamer-1.0-devel-mingw-x86_64-$GST_VERSION.msi"
readonly GST_DEVEL_SHA256="73ec18a7e16d243e92b38eb1c3d9216f937da7f79dc729f93f85109208f40865"
readonly GST_ROOT="$DEPS/gstreamer-$GST_VERSION"

# What playbin needs to play a phone's or camera's video: containers, parsers,
# decoders (Media Foundation and Direct3D 11 for the hardware ones, libav for
# the rest), conversion into the RGBA the app draws, audio out, and videoflip
# for the rotation tag. Everything else in the runtime stays out.
readonly GST_PLUGINS=(
  coreelements app playback typefindfunctions
  videoconvertscale videofilter autodetect
  audioconvert audioresample volume
  isomp4 matroska videoparsersbad audioparsers
  libav mediafoundation d3d11 dav1d vpx opus ogg vorbis
  wasapi directsound
)

SKIP_BUILD=0
for arg in "$@"; do
  case "$arg" in
    --skip-build) SKIP_BUILD=1 ;;
    -h|--help) sed -n '2,31p' "$0"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

log()  { printf '\033[35m==>\033[0m %s\n' "$*"; }
die()  { printf '\033[31merror:\033[0m %s\n' "$*" >&2; exit 1; }
have() { command -v "$1" >/dev/null 2>&1; }

VERSION="$(sed -n 's/^version = "\(.*\)"/\1/p' "$ROOT/Cargo.toml" | head -1)"
[[ -n "$VERSION" ]] || die 'no version in Cargo.toml'

# ---------------------------------------------------------------- gstreamer ---

fetch() {
  local file="$1" sha256="$2"
  if [[ -f "$DEPS/$file" ]] && echo "$sha256  $DEPS/$file" | sha256sum --check --status; then
    return
  fi
  log "downloading $file"
  mkdir -p "$DEPS"
  curl --fail --location --silent --show-error --retry 3 -o "$DEPS/$file.part" "$GST_URL/$file"
  echo "$sha256  $DEPS/$file.part" | sha256sum --check --status \
    || die "$file does not match its pinned SHA-256"
  mv "$DEPS/$file.part" "$DEPS/$file"
}

gstreamer() {
  if [[ -f "$GST_ROOT/.unpacked" ]]; then return; fi
  have msiextract || die 'unpacking GStreamer needs msiextract (msitools)'
  fetch "$GST_RUNTIME" "$GST_RUNTIME_SHA256"
  fetch "$GST_DEVEL" "$GST_DEVEL_SHA256"
  log "unpacking GStreamer $GST_VERSION"
  rm -rf "$GST_ROOT" "$DEPS/unpack"
  mkdir -p "$DEPS/unpack"
  msiextract -C "$DEPS/unpack" "$DEPS/$GST_RUNTIME" >/dev/null
  msiextract -C "$DEPS/unpack" "$DEPS/$GST_DEVEL" >/dev/null
  # Both installers root everything at <drive>/gstreamer/1.0/mingw_x86_64.
  local unpacked
  unpacked="$(find "$DEPS/unpack" -type d -name mingw_x86_64 -path '*gstreamer/1.0/*' | head -1)"
  [[ -n "$unpacked" ]] || die 'no gstreamer/1.0/mingw_x86_64 in the unpacked MSIs'
  mv "$unpacked" "$GST_ROOT"
  rm -rf "$DEPS/unpack"
  # The .pc files name the install prefix on the build machine.
  sed -i "s|^prefix=.*|prefix=$GST_ROOT|" "$GST_ROOT"/lib/pkgconfig/*.pc
  # The devel package carries the mingw runtime it was built with. pkg-config
  # puts its lib directory on the link line, where these would shadow the
  # cross compiler's own and fail the link on `_assert` and friends.
  rm -f "$GST_ROOT"/lib/libmingw32.a "$GST_ROOT"/lib/libmingwex.a
  touch "$GST_ROOT/.unpacked"
}

# -------------------------------------------------------------------- build ---

build() {
  have x86_64-w64-mingw32-gcc || die 'no mingw-w64 cross compiler (pacman -S mingw-w64-gcc)'
  rustup target list --installed | grep -qx "$TARGET" \
    || die "rust target $TARGET not installed: rustup target add $TARGET"
  log "cargo build --release --target $TARGET"
  PKG_CONFIG_ALLOW_CROSS=1 \
  PKG_CONFIG_PATH="$GST_ROOT/lib/pkgconfig" \
  PKG_CONFIG_LIBDIR="$GST_ROOT/lib/pkgconfig" \
    cargo build --release --locked --target "$TARGET" -p pg-desktop --bin proton-gallery \
    --manifest-path "$ROOT/Cargo.toml"
}

# The DLLs `file` imports that GStreamer's bin directory has, and theirs, and
# so on. Whatever is not there is Windows' own.
dll_closure() {
  local -A seen=()
  local queue=("$@") file dll
  while ((${#queue[@]})); do
    file="${queue[0]}"; queue=("${queue[@]:1}")
    while read -r dll; do
      [[ -n "${seen[$dll]:-}" ]] && continue
      [[ -f "$GST_ROOT/bin/$dll" ]] || continue
      seen[$dll]=1
      queue+=("$GST_ROOT/bin/$dll")
    done < <(x86_64-w64-mingw32-objdump -p "$file" | sed -n 's/^\s*DLL Name: //p')
  done
  printf '%s\n' "${!seen[@]}"
}

stage() {
  log 'staging into dist/windows'
  local exe="$ROOT/target/$TARGET/release/proton-gallery.exe"
  [[ -f "$exe" ]] || die "proton-gallery.exe missing: drop --skip-build"
  rm -rf "$OUTDIR"
  mkdir -p "$OUTDIR/bin" "$OUTDIR/lib/gstreamer-1.0" "$OUTDIR/share/proton-gallery" \
    "$OUTDIR/licenses"

  install -m755 "$exe" "$OUTDIR/bin/"
  local plugins=() name
  for name in "${GST_PLUGINS[@]}"; do
    local plugin="$GST_ROOT/lib/gstreamer-1.0/libgst$name.dll"
    [[ -f "$plugin" ]] || die "GStreamer $GST_VERSION has no $name plugin"
    plugins+=("$plugin")
  done
  install -m644 "${plugins[@]}" "$OUTDIR/lib/gstreamer-1.0/"

  local dll
  while read -r dll; do
    install -m644 "$GST_ROOT/bin/$dll" "$OUTDIR/bin/"
  done < <(dll_closure "$exe" "${plugins[@]}")
  # GStreamer's DLLs keep their debug sections; they are most of the download.
  x86_64-w64-mingw32-strip --strip-unneeded "$OUTDIR"/bin/*.dll "$OUTDIR"/lib/gstreamer-1.0/*.dll
  x86_64-w64-mingw32-strip "$OUTDIR/bin/proton-gallery.exe"

  "$ROOT/scripts/fetch-basemap.sh" "$OUTDIR/share/proton-gallery/basemap-v1.pmtiles"

  install -m644 "$ROOT/README.md" "$ROOT/LICENSE" "$ROOT/NOTICE.md" "$OUTDIR/"
  install -m644 "$ROOT"/third-party/* "$OUTDIR/licenses/"
  # GStreamer and the libraries it brings are LGPL or more permissive; the
  # notice says which, and where their source is.
  install -m644 "$PKG/GSTREAMER.txt" "$OUTDIR/licenses/"
}

make_zip() {
  local out="$DIST/$PKGNAME-$VERSION-x86_64-windows.zip"
  log "zip -> $(basename "$out")"
  have zip || die 'no zip on PATH'
  rm -f "$out"
  (cd "$OUTDIR" && zip -qr9 "$out" .)
}

make_msi() {
  have wixl || die 'the .msi needs wixl (pacman -S msitools / apt install wixl)'
  have wixl-heat || die 'the .msi needs wixl-heat (msitools)'
  local out="$DIST/$PKGNAME-$VERSION-x64.msi"
  local payload="$DEPS/payload.wxs"
  log "msi -> $(basename "$out")"
  # Hundreds of files: one component each, generated from the staged tree.
  # wixl-heat derives each component's GUID from its path, so an upgrade
  # replaces the same components rather than stacking new ones.
  (cd "$OUTDIR" && find . -type f | sed 's|^\./||' | sort) |
    wixl-heat --prefix "" --var var.Payload --directory-ref INSTALLDIR \
      --component-group Payload --win64 >"$payload"
  rm -f "$out"
  wixl --arch x64 \
    -D "Version=$VERSION" \
    -D "Win64=yes" \
    -D "Payload=$OUTDIR" \
    -D "IconFile=$PKG/proton-gallery.ico" \
    -o "$out" "$PKG/proton-gallery.wxs" "$payload"
}

mkdir -p "$DIST"
gstreamer
if [[ $SKIP_BUILD -eq 0 ]]; then build; fi
stage
make_zip
make_msi
log 'done'
find "$DIST" -maxdepth 1 -type f -name "$PKGNAME-$VERSION*" -printf '    %10s bytes  %f\n'
