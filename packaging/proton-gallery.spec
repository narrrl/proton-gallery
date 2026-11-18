# In-tree build, or pass --define 'prebuilt 1' after a locked release build.
%global debug_package %{nil}
%global _lto_cflags %{nil}
%{!?version: %global version 0.1.0}
%{!?package_release: %global package_release 2}
%{!?git_dir: %global git_dir %{getenv:PWD}}
%{!?binary: %global binary %{git_dir}/target/release/proton-gallery}
Name: proton-gallery
Version: %{version}
Release: %{package_release}%{?dist}
Summary: Unofficial native Proton Photos browser
License: MIT
URL: https://github.com/narrrl/proton-gallery
ExclusiveArch: x86_64
BuildRequires: cargo
BuildRequires: rust >= 1.96
BuildRequires: gcc
BuildRequires: pkgconf-pkg-config
BuildRequires: dbus-devel
Requires: dbus-libs
Requires: fontconfig
Requires: libxkbcommon
Requires: libxkbcommon-x11
Requires: libX11
Requires: libXcursor
Requires: libXi
Requires: libXrandr
Requires: libwayland-client
Requires: libwayland-cursor
Requires: libwayland-egl
Requires: libglvnd-glx
Requires: libglvnd-egl
Requires: vulkan-loader
Requires: hicolor-icon-theme
Requires: xdg-utils
Requires: ca-certificates
Suggests: gnome-keyring

%description
Browse Proton Photos in a native desktop timeline with account access, albums,
favorites and still-photo viewing. Unofficial and not affiliated with Proton AG.

%prep
test -f "%{git_dir}/Cargo.toml"

%build
%if 0%{?prebuilt}
test -x "%{binary}"
%else
cd "%{git_dir}"
cargo build --release --locked -p pg-desktop --bin proton-gallery
%endif

%install
bash "%{git_dir}/packaging/install.sh" "%{buildroot}" "%{binary}"

%files
/usr/bin/proton-gallery
/usr/share/applications/io.narl.proton-gallery.desktop
/usr/share/icons/hicolor/scalable/apps/io.narl.proton-gallery.svg
/usr/share/icons/hicolor/512x512/apps/io.narl.proton-gallery.png
/usr/share/pixmaps/proton-gallery.png
%license /usr/share/licenses/proton-gallery/*
%doc /usr/share/doc/proton-gallery/
