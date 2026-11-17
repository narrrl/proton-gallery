#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
mode="${1:-debug}"
if [[ "$mode" == native ]]; then
    cargo build -p pg-mobile --features bindgen --locked
    cargo run -p pg-mobile --features bindgen --bin pg-bindgen --locked -- generate \
        --library target/debug/libpg_mobile.so --language kotlin \
        --no-format --out-dir android/app/build/generated/uniffi
    IFS=',' read -r -a abis <<< "${PG_ANDROID_ABIS:-arm64-v8a,x86_64}"
    targets=()
    for abi in "${abis[@]}"; do
        case "$abi" in arm64-v8a|x86_64) targets+=(-t "$abi") ;;
            *) echo "Unsupported PG_ANDROID_ABIS entry: $abi" >&2; exit 2 ;;
        esac
    done
    # Optimized native code: unoptimized async Rust is slow and uses far more
    # stack than the JVM threads that call into it provide.
    cargo ndk "${targets[@]}" --platform 31 \
        -o android/app/build/generated/jniLibs build -p pg-mobile --lib --locked --release
elif [[ "$mode" == debug || "$mode" == check ]]; then
    if [[ "$mode" == check ]]; then
        android/gradlew -p android testDebugUnitTest lintDebug assembleDebug
    else
        android/gradlew -p android assembleDebug
    fi
else
    echo "Usage: $0 [native|debug|check]" >&2
    exit 2
fi
