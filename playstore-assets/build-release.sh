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

# Fail fast on a full disk. R8 (minifyReleaseWithR8) + bundling need several GB
# of scratch; otherwise the build dies ~4 min in with
# "R8: java.io.IOException: No space left on device".
avail_kb=$(df -k . | awk 'NR==2{print $4}')
if [[ "${avail_kb:-0}" -lt 5242880 ]]; then
  echo "ERROR: only $(( ${avail_kb:-0} / 1024 / 1024 ))GB free here; the release build needs ~5GB+." >&2
  echo "  Free space first, e.g.:  for d in /tmp/wt-*/app/build; do rm -rf \"\$d\"; done && ./gradlew --stop" >&2
  exit 1
fi

GRADLE_FILE="app/build.gradle.kts"

current_vc=$(awk '/versionCode = /{print $3; exit}' "$GRADLE_FILE")
current_vn=$(awk -F'"' '/versionName = /{print $2; exit}' "$GRADLE_FILE")

if [[ -z "$current_vc" || -z "$current_vn" ]]; then
  echo "ERROR: could not parse versionCode / versionName from $GRADLE_FILE" >&2
  exit 1
fi

# Highest vc ever staged in playstore-assets/ — that's our floor.
# Filenames follow OmniTAK-<versionName>-vc<N>.aab. Guard the glob: under
# `set -euo pipefail`, a no-match `ls` aborts the whole script (so a clean
# worktree with no staged .aab would exit before bumping anything). Capture
# the listing first with `|| true`, then parse only when non-empty.
staged_aabs=$(ls playstore-assets/*.aab 2>/dev/null || true)
if [[ -n "$staged_aabs" ]]; then
  max_staged_vc=$(printf '%s\n' "$staged_aabs" \
    | sed -E 's/.*-vc([0-9]+)\.aab$/\1/' \
    | sort -n | tail -1)
else
  max_staged_vc=0
fi

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
