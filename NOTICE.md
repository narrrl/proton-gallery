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

Places uses land and border data from [Natural Earth](https://www.naturalearthdata.com/about/terms-of-use/)
(public domain) and a trimmed [GeoNames](https://www.geonames.org/) cities dataset
under [CC BY 4.0](https://creativecommons.org/licenses/by/4.0/). The bundled
datasets and their conversion scripts originated in Proton Drive Linux.
The desktop Places map draws an offline basemap (`basemap-v1.pmtiles`, built
by the `basemap` tool in [proton-rs](https://github.com/narrrl/proton-rs) and
downloaded by `scripts/fetch-basemap.sh`) from Natural Earth 10m data (public
domain) and GeoNames towns (CC BY 4.0). Its labels use Inter, bundled with
`proton-map-rs` under the SIL Open Font License.
Desktop uses [Inter](https://rsms.me/inter/) under the bundled
[SIL Open Font License](crates/pg-desktop/fonts/Inter-OFL.txt).

This is an unofficial project and is not affiliated with Proton AG.
