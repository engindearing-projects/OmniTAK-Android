# SxS visual verification — 2026-05-11 SAR closeout

Branch: `feat/closed-test-feedback-may2026`
Android: `0.5.0` / `vc42`
iOS: `2.18.0` / build `26051104`

Both platforms installed from the merged-build artifacts:
- Android: `app-debug.apk` from the post-merge tree, installed on `engie_emulator`.
- iOS: Debug-config build of the merged tree, installed on iPhone 17 Pro sim (iOS 26.2).

## Map surface — `sxs-map.png`

Left (iOS): merged build launches on Map tab via `cliclick` after first
hitting the Mesh default. Default sim location is Washington DC; visible
elements include the `EE TAK Server` connection indicator (top-left),
PARKVIEW location label, the GPS-lock FAB column, and the new
`S2:quick-mark` MARK block is in source (`MapViewController.swift`,
verified live). The dark translucent FAB sits below the GPS-lock button
in the merged source and renders against the dark map.

Right (Android): merged build launches into Map after granting location
permission. Visible elements include the green `TAK_5.7_Android_Emu`
server-connected dot, full Spokane map, the left FAB stack with the
S1 PinDrop FAB (green pin) and S1 zoom + GPS-lock controls, the bottom-
right radial menu FAB, and the `Callsign: OMNI-1 / 47.66200, -117.42200`
identity card.

Pass: both platforms launch and render the merged Map surface with a
live server connection indicator and the cross-platform tab bar (Map
selected). No layout regressions vs. pre-merge.

## Android Settings — `android-2-settings.png`

Independent corroboration that the Android merge didn't break the
Settings stack: OPERATOR PERSONA segmented control (Tactical / Fire-
Rescue / SAR / Civilian), IDENTITY (OMNI-1, CYAN), UNITS (Metric),
COORDINATES (Lat/Lon), MAP TILES section header — all rendering
cleanly with the SAR terminology AppMode header intact.

## What was NOT visually verified

- iOS sim tap-driving from the host was flaky (System Events / cliclick
  hit the Simulator chrome but not always the embedded screen). The
  iOS Map tab switch worked once; the Settings tab switch did not.
- Drawing toolbar / Bullseye flow on either platform — would need a
  drawing-tool tap sequence that the host-side tap pipeline isn't
  reliable enough to drive. The drawing CoT marker block was source-
  verified in both `MapScreen.kt` and `MapViewController.swift`.
- Cross-platform drawing CoT round-trip — requires a real TAK server
  with both clients connected; deferred to the TAK 5.7 local server
  test pass tracked under `~/Projects/tak57-test/`.

J or the TestFlight cut step picks up the drawing-flow + Bullseye
verification with a real device tap pipeline before the Play Console
and TestFlight uploads.
