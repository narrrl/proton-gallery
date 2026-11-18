#!/usr/bin/env bash
# Builds the Rust core for this computer (Linux or Windows, x86_64) and its
# Kotlin bindings, for the desktop Gradle build. Usage:
#   build-desktop-native.sh <generated-dir> <jna-prefix> <library-file>
set -euo pipefail
cd "$(dirname "$0")/.."
out="$1" prefix="$2" library="$3"
# Optimized: unoptimized async Rust is slow and needs far more stack.
cargo build -p pg-mobile --lib --locked --release
rm -rf "$out/uniffi" "$out/native"
# The release library is stripped, so read the bindings' metadata from a
# debug build, as scripts/build-android.sh does.
cargo build -p pg-mobile --features bindgen --locked
cargo run -p pg-mobile --features bindgen --bin pg-bindgen --locked -- generate \
    --library "target/debug/$library" --language kotlin \
    --no-format --out-dir "$out/uniffi"
mkdir -p "$out/native/$prefix"
cp "target/release/$library" "$out/native/$prefix/$library"
