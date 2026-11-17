#!/usr/bin/env python3
"""Build the device-testing variant with credentials kept outside the checkout."""
import argparse
import json
import os
from pathlib import Path
import subprocess


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--signing-config", type=Path,
                        default=Path.home() / "Keys/proton-gallery/testing.json")
    parser.add_argument("--instrumentation", action="store_true", help="Also build isolated on-device checks")
    parser.add_argument("--abis", choices=["arm64-v8a", "x86_64", "arm64-v8a,x86_64"],
                        default="arm64-v8a,x86_64", help="Native architectures to build and package")
    parser.add_argument("--check", action="store_true", help="Run host Android interaction/bridge tests and debug lint")
    args = parser.parse_args()
    profile = args.signing_config.expanduser().resolve()
    if not profile.is_file():
        parser.error(f"Signing profile does not exist: {profile}")
    if os.name == "posix" and profile.stat().st_mode & 0o077:
        parser.error("Signing profile must be readable only by its owner (mode 600)")
    config = json.loads(profile.read_text())
    store = (profile.parent / config["keystore"]).resolve()
    if not store.is_file():
        parser.error(f"Keystore does not exist: {store}")
    if os.name == "posix" and store.stat().st_mode & 0o077:
        parser.error("Keystore must be readable only by its owner (mode 600)")
    env = os.environ.copy()
    env.update({
        "PG_ANDROID_ABIS": args.abis,
        "PG_ANDROID_KEYSTORE": str(store),
        "PG_ANDROID_STORE_PASSWORD": config["store_password"],
        "PG_ANDROID_KEY_ALIAS": config["alias"],
        "PG_ANDROID_KEY_PASSWORD": config["key_password"],
    })
    root = Path(__file__).resolve().parent.parent
    # No secrets on the command line, no configuration-cache serialization,
    # and no long-lived Gradle daemon retaining the signing environment.
    tasks = [":app:assembleDevice", ":app:lintDevice"]
    if args.check:
        tasks.extend([":app:testDebugUnitTest", ":app:lintDebug"])
    if args.instrumentation:
        tasks.append(":app:assembleDeviceAndroidTest")
    subprocess.run([str(root / "android/gradlew"), "--no-daemon", "--no-configuration-cache",
                    "-Pkotlin.compiler.execution.strategy=in-process",
                    "-p", "android", *tasks],
                   cwd=root, env=env, check=True)
    print(f"Signed testing APK: {root / 'android/app/build/outputs/apk/device/app-device.apk'}")


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        raise SystemExit(error.returncode) from None
