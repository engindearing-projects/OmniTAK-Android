#!/usr/bin/env bash
# Fire ATAK-canonical `tak://` deep links at a connected Android device
# to verify the new URL scheme handlers wired in v0.3.0 vc30.
#
# Prereqs:
#   - adb in PATH
#   - device connected (USB debugging on), `adb devices` shows it
#   - OmniTAK installed on the device (sideload the vc30 AAB or
#     install from Play closed testing)
#
# Run from anywhere: ./scripts/verify-deep-links.sh

set -euo pipefail

PACKAGE="soy.engindearing.omnitak.mobile"

if ! command -v adb >/dev/null; then
  echo "ERROR: adb not in PATH. Install platform-tools or open Android Studio once." >&2
  exit 1
fi

device_count=$(adb devices | awk 'NR>1 && $2=="device"' | wc -l | tr -d ' ')
if [[ "$device_count" -eq 0 ]]; then
  echo "ERROR: no Android device connected. Plug in via USB and accept the debug prompt." >&2
  exit 1
fi

echo "==> Device: $(adb shell getprop ro.product.model | tr -d '\r')"
echo "==> OmniTAK package: $PACKAGE"
echo ""

fire() {
  local label="$1"
  local url="$2"
  echo "▶ $label"
  echo "  $url"
  adb shell am start -a android.intent.action.VIEW -d "$url" "$PACKAGE" >/dev/null
  sleep 1.5
  echo ""
}

# 1. Connect (tak:// scheme parity — used to require atak://)
fire "tak://connect — TLS server" \
  "tak://com.atakmap.app/connect?host=tak.engindearing.soy&port=8089&proto=tls"

# 2. Preference write — single string
fire "tak://preference — set callsign" \
  "tak://com.atakmap.app/preference?key1=callsign&type1=string&value1=ENGIE-${RANDOM:0:3}"

# 3. Preference write — multiple, mixed types, including an ATAK alias
fire "tak://preference — multi-key with ATAK aliases" \
  "tak://com.atakmap.app/preference?key1=locationTeam&type1=string&value1=CYAN&key2=gridEnabled&type2=boolean&value2=true&key3=coord_display_format&type3=string&value3=MGRS"

# 4. Import a tiny public zip (won't be a real data package but exercises
#    the download path; the toast will say "download failed" on a non-zip).
fire "tak://import — exercise download path" \
  "tak://com.atakmap.app/import?url=https%3A%2F%2Fgithub.com%2Fengindearing-projects%2FOmniTAK-Android%2Farchive%2Frefs%2Fheads%2Fmain.zip"

# 5. Enroll — should toast "Enrollment staged" since Android CSR is GAP-081
fire "tak://enroll — verify stub message" \
  "tak://com.atakmap.app/enroll?host=tak.engindearing.soy&username=engie&token=demo-token-not-real"

echo "================================================================"
echo "  Done. Visual check on device:"
echo "   • Each verb should fire a toast naming the action."
echo "   • Settings → callsign should reflect step 2."
echo "   • Settings → MGRS grid should toggle on (step 3)."
echo "   • Servers tab should list tak.engindearing.soy."
echo "================================================================"
