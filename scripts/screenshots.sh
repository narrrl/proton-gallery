#!/usr/bin/env bash
# Render actual Gallery UI using public demo photographs from Drive’s screenshot source.
set -euo pipefail
repo="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
photos="${PG_SCREENSHOT_PHOTOS:-}"
if [[ "${1:-}" == --photos ]]; then
    photos=${2:?photo directory is required}
    shift 2
fi
if (($#)); then
    echo "Usage: scripts/screenshots.sh [--photos DIR]" >&2
    exit 2
fi
# Fixed Picsum IDs, as in Drive. Interleave color, scale and orientation
# so the first screen reads as a collection even on a narrow phone.
if [[ -z "$photos" ]]; then
    photos="${XDG_CACHE_HOME:-$HOME/.cache}/pg-screenshot-photos"
    mkdir -p "$photos"
    ids=(1016 1069 1039 417 1080 1015 1043 835 593 1036 815 1040 1024 1035 866 292 1067 1084 568 306 1033 1018 487 1042)
    for i in "${!ids[@]}"; do
        id=${ids[i]}
        printf -v name '%02d-%s.jpg' "$i" "$id"
        [[ -s "$photos/$name" ]] && continue
        case "$id" in
            1039|417|1043|835|1035) size=840/1140 ;;
            *) size=1000/720 ;;
        esac
        curl -fsSL --retry 2 "https://picsum.photos/id/$id/$size.jpg" -o "$photos/$name.part"
        mv "$photos/$name.part" "$photos/$name"
    done
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
    --tests 'io.narl.protongallery.GalleryUiTest.capture_gallery_*' \
    --tests io.narl.protongallery.GalleryUiTest.phone_photos_show_what_is_not_backed_up_and_back_up_a_selection \
    --tests io.narl.protongallery.GalleryUiTest.drive_folders_open_as_nested_albums_and_back_returns_to_the_parent \
    --tests io.narl.protongallery.GalleryUiTest.the_backup_sheet_chooses_folders_and_conditions \
    --tests io.narl.protongallery.GalleryUiTest.backups_can_go_into_albums_per_folder_and_for_everything \
    --tests io.narl.protongallery.GalleryUiTest.the_photos_tab_lists_albums_and_adds_a_selection_to_one \
    --tests io.narl.protongallery.GalleryUiTest.hidden_albums_open_only_with_the_phones_lock
output="$repo/android/app/build/photo-screenshots"
mkdir -p "$output"
for name in phone-timeline-light nested-album-light backup-albums-light photo-albums-light hidden-unlocked-dark backup-sheet-dark gallery-viewer gallery-places gallery-albums gallery-drive; do
    source="$repo/android/app/build/screenshots/$name.png"
    [[ -s "$source" ]] || { echo "Missing photo-fixture capture: $source" >&2; exit 1; }
    cp "$source" "$output/$name.png"
done
for pair in phone-timeline-light:photos gallery-drive:drive backup-albums-light:backup gallery-albums:albums gallery-viewer:viewer gallery-places:places; do
    cp "$output/${pair%:*}.png" "$repo/images/${pair#*:}.png"
done
echo "Photo-fixture captures are in android/app/build/photo-screenshots and images/."
