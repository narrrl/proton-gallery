//! `cfg(playback)`: in-app video through GStreamer, on Linux and Windows.
//! On Windows, also the executable's icon and version resource.

fn main() {
    println!("cargo::rustc-check-cfg=cfg(playback)");
    println!("cargo::rerun-if-changed=build.rs");
    let os = std::env::var("CARGO_CFG_TARGET_OS").unwrap_or_default();
    if matches!(os.as_str(), "linux" | "windows") {
        println!("cargo::rustc-cfg=playback");
    }
    if os == "windows" {
        let icon = "../../packaging/windows/proton-gallery.ico";
        println!("cargo::rerun-if-changed={icon}");
        let mut resource = winresource::WindowsResource::new();
        resource
            .set_icon(icon)
            .set("ProductName", "Proton Gallery")
            .set("FileDescription", "Proton Gallery")
            .set("CompanyName", "narl.io");
        if let Err(error) = resource.compile() {
            // A missing resource compiler costs the icon, not the build.
            println!("cargo::warning=no Windows resources embedded: {error}");
        }
    }
}
