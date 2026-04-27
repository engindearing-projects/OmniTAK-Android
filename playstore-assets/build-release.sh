#!/usr/bin/env bash
# Bump versionCode by 1, build a signed release AAB, stage it under
# playstore-assets/ with the version in the filename.
#
# Usage: ./playstore-assets/build-release.sh
#
# Run from the repo root.

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

GRADLE_FILE="app/build.gradle.kts"

current_vc=$(awk '/versionCode = /{print $3; exit}' "$GRADLE_FILE")
current_vn=$(awk -F'"' '/versionName = /{print $2; exit}' "$GRADLE_FILE")

if [[ -z "$current_vc" || -z "$current_vn" ]]; then
  echo "ERROR: could not parse versionCode / versionName from $GRADLE_FILE" >&2
  exit 1
fi

new_vc=$((current_vc + 1))

echo ">> bumping versionCode $current_vc -> $new_vc (versionName stays $current_vn)"
sed -i.bak "s/versionCode = $current_vc/versionCode = $new_vc/" "$GRADLE_FILE"
rm "$GRADLE_FILE.bak"

echo ">> ./gradlew bundleRelease"
./gradlew --no-daemon bundleRelease

OUT_NAME="OmniTAK-${current_vn}-vc${new_vc}.aab"
OUT_PATH="playstore-assets/$OUT_NAME"
cp "app/build/outputs/bundle/release/app-release.aab" "$OUT_PATH"

echo ""
echo "================================================================"
echo "  Done. Upload this file to Play Console:"
echo "    $OUT_PATH"
echo ""
echo "  Version: $current_vn  (versionCode $new_vc)"
echo "  Size:    $(ls -lh "$OUT_PATH" | awk '{print $5}')"
echo "================================================================"
