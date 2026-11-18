#!/usr/bin/env bash
# Render actual Gallery UI using the same public demo photographs as Drive.
set -euo pipefail
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
photos="${PG_SCREENSHOT_PHOTOS:-${XDG_CACHE_HOME:-$HOME/.cache}/pdfs-screenshots}"
if [[ "${1:-}" == --photos ]]; then
    photos=${2:?photo directory is required}
    shift 2
fi
if (($#)); then
    echo "Usage: scripts/screenshots.sh [--photos DIR]" >&2
    exit 2
fi
[[ -d "$photos" ]] || { echo "Missing demo photos: $photos. Provide --photos DIR or populate Drive's demo-photo cache first." >&2; exit 2; }
photos=$(realpath -- "$photos")
cd "$repo"
# UI-only capture: use the host library and generated bindings from an existing
# development build. Never rebuild device binaries or connect to a real account.
[[ -f target/debug/libpg_mobile.so && -d android/app/build/generated/uniffi ]] || {
    echo "Build Gallery's native library and bindings first (scripts/build-android.sh native)." >&2
    exit 2
}
android/gradlew --no-daemon --no-configuration-cache -p android \
    :app:testDebugUnitTest -x :app:buildRust "-Ppg.screenshot.photos=$photos" \
    --tests io.narl.protongallery.GalleryUiTest.phone_photos_show_what_is_not_backed_up_and_back_up_a_selection \
    --tests io.narl.protongallery.GalleryUiTest.drive_folders_open_as_nested_albums_and_back_returns_to_the_parent \
    --tests io.narl.protongallery.GalleryUiTest.the_backup_sheet_chooses_folders_and_conditions \
    --tests io.narl.protongallery.GalleryUiTest.backups_can_go_into_albums_per_folder_and_for_everything \
    --tests io.narl.protongallery.GalleryUiTest.the_photos_tab_lists_albums_and_adds_a_selection_to_one \
    --tests io.narl.protongallery.GalleryUiTest.hidden_albums_open_only_with_the_phones_lock
output="$repo/android/app/build/photo-screenshots"
mkdir -p "$output"
for name in phone-timeline-light nested-album-light backup-albums-light photo-albums-light hidden-unlocked-dark backup-sheet-dark; do
    source="$repo/android/app/build/screenshots/$name.png"
    [[ -s "$source" ]] || { echo "Missing photo-fixture capture: $source" >&2; exit 1; }
    cp "$source" "$output/$name.png"
done
for pair in phone-timeline-light:photos nested-album-light:drive backup-albums-light:backup photo-albums-light:albums; do
    cp "$output/${pair%:*}.png" "$repo/images/${pair#*:}.png"
done
echo "Photo-fixture captures are in android/app/build/photo-screenshots and images/."
