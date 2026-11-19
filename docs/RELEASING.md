# Releasing Proton Gallery

Published versions use annotated tags such as `v0.2.0`. The Android
workflow coordinates publication of the signed Android APK, x86_64 Linux
`.deb`, signed x86_64 `.rpm`, and RPM public key on GitHub Releases.
Arch users build from the tagged checkout. Windows users build from source.

## Prepare the version

1. Update `workspace.package.version` in `Cargo.toml` and run
   `cargo update --workspace` to refresh the workspace members in `Cargo.lock`.
2. Set the same `versionName` in `android/app/build.gradle.kts`.
   Raise `versionCode` above the previous Android release to allow in-place updates.
3. Match the version in `packaging/PKGBUILD`, the RPM spec fallback, and the
   experimental Compose desktop packaging metadata. Set the Linux package
   release to 1 for a new upstream version.
4. Add a `## X.Y.Z` heading and release entry to
   [CHANGELOG.md](../CHANGELOG.md). The tagged workflow uses that entry as
   the release notes and appends GitHub's generated change summary.
5. Run the Rust checks and the Android build checks, then push the release
   commit to `main`. Check the Android, Desktop, and Linux packages workflows
   for that commit before tagging.

## Signing

The Android APK uses the production package ID `io.narl.protongallery` and the
same signing certificate as earlier releases. GitHub Actions uses
`RELEASE_KEYSTORE_BASE64`, `RELEASE_STORE_PASSWORD`, `RELEASE_KEY_PASSWORD`,
and `RELEASE_KEY_ALIAS`. Keep the production key and its backup outside the
checkout. The separate Android testing APK uses a different key and package ID.
[Local signed builds](ANDROID.md#signed-builds)

The release workflow imports `RPM_SIGNING_SUBKEY` into a temporary keyring,
unlocks it using `RPM_SIGNING_PASSPHRASE`, signs the RPM, and verifies its
signature against the checked-in public key before publishing. The standalone
Linux packages job produces unsigned test artifacts.
[RPM key and installation](../packaging/README.md#debian-ubuntu-and-fedora)

## Tag and publish

```sh
version=0.2.0
git tag -a "v$version" -m "Release Proton Gallery $version"
git push origin "refs/tags/v$version"
gh run list --workflow android.yml --branch "v$version"
```

The tagged run checks the version and registry dependencies, builds both
Android ABIs and the Linux packages, then publishes after verification.
If a job fails for a temporary reason, rerun the failed jobs. A source fix
requires a tag that points at the fixed commit. Published tags identify
the exact source of their artifacts and should stay in place.

After the run succeeds, check the published version and its asset list:

```sh
gh release view "v$version" --json tagName,isDraft,isPrerelease,assets,url
```

Use `apksigner verify --print-certs` to compare the downloaded APK with the
previous release certificate. Verify the RPM against the accompanying key
and check the Debian package with `dpkg-deb --info`.
