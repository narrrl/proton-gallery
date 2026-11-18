#!/usr/bin/env bash
set -euo pipefail

if (( $# == 0 )); then
    echo "Usage: bash scripts/sign-rpm.sh PACKAGE.rpm [...]" >&2
    exit 2
fi

: "${RPM_SIGNING_SUBKEY:?RPM release signing subkey is required}"
: "${RPM_SIGNING_PASSPHRASE:?RPM release signing passphrase is required}"

public_key="$(dirname "${BASH_SOURCE[0]}")/../packaging/RPM-GPG-KEY-proton-gallery"
signer=F747B5053FBEF210B3FA0F073006460ECBCCC6DC
primary=1E28F055A4B3EC287675F10128B9DC0E6A5C9C52
umask 077
scratch="$(mktemp -d)"
export GNUPGHOME="$scratch/gnupg"
cleanup() {
    gpgconf --kill gpg-agent
    rm -rf -- "$scratch"
}
trap cleanup EXIT
mkdir "$GNUPGHOME"
printf '%s\n' "$RPM_SIGNING_PASSPHRASE" > "$scratch/passphrase"
printf '%s\n' 'batch' 'pinentry-mode loopback' "passphrase-file $scratch/passphrase" > "$GNUPGHOME/gpg.conf"
printf '%s\n' "$RPM_SIGNING_SUBKEY" | gpg --quiet --import
unset RPM_SIGNING_SUBKEY RPM_SIGNING_PASSPHRASE

rpmsign --define '_openpgp_sign gpg' --define "__gpg $(command -v gpg)" --define "_gpg_name $signer!" \
    --define '_gpg_digest_algo sha256' --addsign "$@"
rpmkeys --dbpath "$scratch/rpmdb" --import "$public_key"
for package in "$@"; do
    result="$(rpmkeys --dbpath "$scratch/rpmdb" --checksig --verbose "$package")"
    printf '%s\n' "$result"
    # Unsigned RPMs can pass the digest checks. Require a verified signature too.
    grep -Eiq "Signature.*(${signer: -8}|${primary}): OK" <<< "$result"
done
