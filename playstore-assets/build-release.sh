#!/usr/bin/env bash
# Build a signed release AAB at the next monotonic versionCode and
# stage it under playstore-assets/. versionName comes from gradle as-is —
# bump it by hand when you want the user-facing label to change.
#
# Convention: versionName = semver (0.1.10, 0.1.11, …); versionCode = a
# strictly monotonic integer that never resets, because Play Console
# rejects any AAB whose vc has been used before regardless of versionName.
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

# Highest vc ever staged in playstore-assets/ — that's our floor.
# Filenames follow OmniTAK-<versionName>-vc<N>.aab. nullglob keeps the
# pipeline from aborting under `set -euo pipefail` when no AAB has been
# staged yet (e.g. first run in a fresh worktree).
shopt -s nullglob
staged_aabs=(playstore-assets/*.aab)
shopt -u nullglob
if (( ${#staged_aabs[@]} > 0 )); then
  max_staged_vc=$(printf '%s\n' "${staged_aabs[@]}" \
    | sed -E 's/.*-vc([0-9]+)\.aab$/\1/' \
    | sort -n | tail -1)
fi
max_staged_vc=${max_staged_vc:-0}

new_vc=$(( max_staged_vc > current_vc ? max_staged_vc + 1 : current_vc + 1 ))

echo ">> versionCode: gradle=$current_vc, max staged=$max_staged_vc -> next=$new_vc"
echo ">> versionName: $current_vn (unchanged — edit $GRADLE_FILE by hand to bump)"
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
