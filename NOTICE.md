# Source and design provenance

The revision-pinned range mapping, block-level reuse, and cancellation boundaries
build on the MIT Rust work in [proton-stream](https://github.com/narrrl/proton-stream)
and [proton-drive-linux](https://github.com/narrrl/proton-drive-linux), by Nils Pukropp
and contributors. The gallery implements its own domain model, SQLite schema,
bounded catalog worker, and authenticated local object format.

The shared justified-grid math is adapted from `pdfs-gui`'s Photos page in
proton-drive-linux. The gallery icon now uses an original vector photo stack
within the suite's violet–pink and metallic copper identity. The earlier icon,
which referenced the gradient tile and segmented rhythm of proton-stream's MIT
`images/logo.svg`, is retained in `images/branding-archive/`.
Copyright (c) 2026 Nils Pukropp. The full upstream MIT notice is retained in
[third-party/NILS-PUKROPP-MIT.txt](third-party/NILS-PUKROPP-MIT.txt).

Proton protocol, authentication, and remote cryptography are supplied by the MIT
[proton-sdk-rs](https://github.com/narrrl/proton-sdk-rs) crates, pinned to 0.12.0.
Dependency licenses remain their respective authors' licenses. No Android/iOS
application source or native player distribution has been copied into this slice.

The Android UI includes twelve Apache 2.0 Material icon vectors from the Android
Open Source Project. Original headers, a bundled license, source version/hash,
and the package relocation are recorded in [icon provenance](android/ICON_PROVENANCE.md).

This is an unofficial project and is not affiliated with Proton AG.
