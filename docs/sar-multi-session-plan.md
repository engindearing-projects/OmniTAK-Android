# SAR feedback closeout — 3-session parallel plan

K9Blue's TROP feedback drove a list of gaps. After vc40 we shipped
Android one-tap mark, AppMode + SAR terminology, and Mission KML
export. The remaining work is too big for one session and partitions
cleanly across three terminals. This doc is the briefing for the
lead agent in each session.

## Operating model per terminal

Each terminal runs **one Claude Code session** that acts as a **lead
agent**. The lead reads its session brief below, executes the work,
and dispatches sub-agents via `Agent` (Explore / general-purpose /
Plan) for parallel research or isolated chunks. There is no
cross-session communication during execution — coordination happens
through the file-ownership rules in this doc.

## Repos + branches

- Android: `~/Projects/OmniTAK-Android/`
- iOS: `~/Projects/OmniTAK-iOS/`
- Each session works on its **own feature branch** off
  `feat/closed-test-feedback-may2026` (the current branch with vc40).
- Use **git worktrees** so the three sessions don't share a working
  tree:
  ```bash
  cd ~/Projects/OmniTAK-Android
  git worktree add ../OmniTAK-Android-S1 feat/sar-s1-field-tools
  git worktree add ../OmniTAK-Android-S2 feat/sar-s2-ios-catchup
  git worktree add ../OmniTAK-Android-S3 feat/sar-s3-drawing-cot
  # And the same for iOS:
  cd ~/Projects/OmniTAK-iOS
  git worktree add ../OmniTAK-iOS-S2 feat/sar-s2-ios-catchup
  git worktree add ../OmniTAK-iOS-S3 feat/sar-s3-drawing-cot
  ```
- Sessions S2 and S3 work in BOTH repos via their respective worktrees.

## File ownership map (the conflict-prevention contract)

Each cell names which session is **allowed to write** to that path or
section. If you're in a different session, treat it as read-only —
escalate to the integration step rather than editing.

### Android

| Path | S1 | S2 | S3 |
|---|---|---|---|
| `data/Track*.kt` (new) | **own** | — | — |
| `domain/TrackRecording*.kt` (new) | **own** | — | — |
| `data/OfflineTile*.kt`, `domain/Offline*.kt` (new) | **own** | — | — |
| `data/Bullseye.kt`, `data/DrawingCoT.kt` (new) | — | — | **own** |
| `domain/DrawingBroadcaster.kt` (new) | — | — | **own** |
| `data/UserPrefs.kt` | append-only via marker | — | append-only via marker |
| `ui/screens/SettingsScreen.kt` | append section via marker | — | append section via marker |
| `ui/screens/MapScreen.kt` | append FAB via marker | — | append draw-toggle via marker |
| `ui/screens/MapScreen.kt` Mark-Position FAB | — | — | — (S1 done in vc40) |
| `domain/TAKConnection.kt` receive parser | — | — | extend via marker |
| `OmniTAKApp.kt` lazy singletons | append via marker | — | append via marker |
| `playstore-assets/build-release.sh` outputs | each branch builds its own AAB; no commit collisions |

### iOS

| Path | S1 | S2 | S3 |
|---|---|---|---|
| `Features/Map/Services/QuickMark*.swift` (new) | — | **own** | — |
| `Features/Mission/MissionExporter.swift` (new) | — | **own** | — |
| `Features/Drawing/Services/Bullseye*.swift` (new) | — | — | **own** |
| `Features/Drawing/Services/DrawingCoT*.swift` (new) | — | — | **own** |
| `Features/Map/Controllers/MapViewController.swift` | — | append FAB via marker | append toggle via marker |
| `Features/Settings/Views/SettingsView.swift` | — | append export via marker | — |
| `Core/App/OmniTAKMobileApp.swift` | — | append init() call via marker | append init() call via marker |
| `Features/Networking/Services/TAKService.swift` receive | — | — | extend via marker |
| `OmniTAKMobile.xcodeproj/project.pbxproj` | — | own via `scripts/add_*.rb` | own via `scripts/add_*.rb` |

### Shared-file edit protocol — marker comments

When two sessions both need to edit the same file (UserPrefs,
SettingsScreen, MapScreen, OmniTAKApp, MapViewController,
OmniTAKMobileApp), each session puts its additions inside a clearly
labelled block:

```kotlin
// == S1:track-recording BEGIN ==
val trackRecordingEnabled: Boolean = false,
// == S1:track-recording END ==
```

```swift
// MARK: - S2:quick-mark BEGIN
private func quickMarkButton() -> some View { … }
// MARK: - S2:quick-mark END
```

The marker comments make `git diff` + manual conflict resolution
trivial during integration. **Never modify another session's
marker block.** If you need a peer's data field, file an item in
the integration backlog at the bottom of this doc and treat it as
a follow-up.

## Session 1 — Android field tools

**Branch:** `feat/sar-s1-field-tools` (worktree at
`~/Projects/OmniTAK-Android-S1`)

**Scope (≈ 9 hours):**

1. **Android track recording** — port iOS `TrackRecordingService.swift`
   to Kotlin. New files under
   `app/src/main/kotlin/.../domain/tracking/`:
   - `TrackPoint.kt` (lat/lon/alt/timestamp/accuracy/speed)
   - `Track.kt` (id, name, points, started/ended, totalDistanceM)
   - `TrackRecorder.kt` — observes `LocationProvider.fix`, samples
     every 1 s and ≥ 5 m, force-records every 60 s on stationary,
     persists to JSON in `<filesDir>/tracks/`
   - `TrackKmlExporter.kt` + `TrackGpxExporter.kt` — KML 2.2
     LineString and GPX 1.1 trkseg
   - **Tests:** sampling rule, distance threshold, exporter envelope
2. **Android offline tile prefetch** — port iOS `OfflineMapManager`
   pattern. New files under `app/src/main/kotlin/.../domain/offline/`:
   - `TileRegion.kt` (id, name, bbox, zoomMin/Max, tileCount)
   - `TileDownloader.kt` — XYZ tile URL templating + concurrent
     fetcher with progress callback
   - `OfflineTileStore.kt` — disk cache under `<filesDir>/tiles/<region-id>/`
   - `OfflineTileCache.kt` — MapLibre OkHttp interceptor that serves
     cached tiles when offline
   - **Tests:** bbox→tileset math, URL templating, cache hit/miss
3. **Android Settings UI** — three new sections via markers:
   - "Tracks" — list recorded tracks, start/stop button, export to
     GPX/KML via SAF (mirror MissionKmlExporter pattern from vc40)
   - "Offline maps" — list regions, "Download region around current
     position…" button (radius + zoom-range picker)
   - "Mission" already exists from vc40 — leave alone
4. **MapScreen** — add Record / Pause FAB to the BottomStart stack
   inside the marker block.
5. **AAB:** bump versionCode, build, write release notes file under
   `playstore-assets/release-notes/vcN.txt`.
6. **PR target:** `feat/closed-test-feedback-may2026` after rebase.

**Don't touch:** iOS code, drawing layer, CoT serialization, server
manager.

**Pre-flight reading:**
- `OmniTAK-iOS/OmniTAKMobile/Features/Tracking/Services/TrackRecordingService.swift`
- `OmniTAK-iOS/OmniTAKMobile/Features/OfflineMaps/Services/OfflineMapManager.swift`
- `OmniTAK-Android/app/src/main/kotlin/.../data/MissionKmlExporter.kt` (to follow conventions)

---

## Session 2 — iOS catch-up

**Branch:** `feat/sar-s2-ios-catchup` (worktrees at
`~/Projects/OmniTAK-iOS-S2` and — for cross-platform docs only —
`~/Projects/OmniTAK-Android-S2`)

**Scope (≈ 6 hours):**

1. **iOS Mark-My-Position FAB** — port the Android vc40
   `PinDrop` FAB into the iOS map controls stack. Belongs in
   `MapViewController.swift` near the GPS-lock button.
   - New file: `Features/Map/Services/QuickMarkService.swift` —
     drops a CoT marker at `LocationManager.location` with auto-
     callsign `"MyPos-HHMM"`, ingests + broadcasts via
     `TAKService.shared.sendCoT(_:)`.
   - Wire a SwiftUI button into `MapViewController.swift` inside the
     `// MARK: - S2:quick-mark` block.
2. **iOS Mission KML export** — bundle markers + drawings + recorded
   tracks into one KML, mirroring Android `MissionKmlExporter.kt`.
   - New file: `Features/Mission/MissionExporter.swift` — pure
     formatter, takes the same shape inputs.
   - Settings → Mission → "Export mission as KML" → share-sheet via
     `UIActivityViewController`.
   - Bonus: per-track and per-drawing export already exists; the
     mission bundle wraps them.
3. **App version bump:** MARKETING_VERSION 2.16.0 → 2.17.0,
   CURRENT_PROJECT_VERSION 26051102 → 26051103.
4. **xcodeproj registration:** new files via
   `scripts/add_quickmark_service.rb` + `scripts/add_mission_exporter.rb`
   (clone the existing `scripts/add_*.rb` pattern).

**Don't touch:** Android code, drawing CoT broadcast, server manager.

**Pre-flight reading:**
- `OmniTAK-Android/app/src/main/kotlin/.../data/MarkerCoT.kt`
- `OmniTAK-Android/app/src/main/kotlin/.../data/MissionKmlExporter.kt`
- `OmniTAK-Android/app/src/main/kotlin/.../ui/screens/MapScreen.kt`
  (the PinDrop block is the iOS reference implementation)
- `OmniTAK-iOS/OmniTAKMobile/Features/Tracking/Services/TrackRecordingService.swift`
  (read its export functions to feed the bundle)

---

## Session 3 — Cross-platform drawing CoT broadcast

**Branch:** `feat/sar-s3-drawing-cot` (worktrees at both repos)

**Scope (≈ 7 hours):**

1. **Drawing → CoT serialization, both platforms.** Closes K9's
   "Bullseye could not be shared within a mission" — and any line /
   polygon / circle the operator draws should reach every connected
   server's clients.
   - Android: `data/DrawingCoT.kt` — pure builder. CoT types:
     - Line: `u-d-f` with `<link><Link>` chain
     - Polygon: `u-d-f` closed path
     - Circle: `u-d-c-c` with center point + `<shape><ellipse>` or
       `<remarks>radius=Nm</remarks>`
     - Bullseye: encode as multiple concentric circles or one circle
       with `<bullseye>` detail extension
   - iOS: `Features/Drawing/Services/DrawingCoT.swift` — same shape.
2. **Bullseye primitive, both platforms.**
   - Add `DrawingKind.BULLSEYE` (Android) / equivalent enum case (iOS).
   - Render concentric circles at 100 m / 500 m / 1 km by default
     (configurable in the drawing-finish sheet).
3. **Broadcast on save, both platforms.**
   - Android: extend `MapScreen.kt` drawing-finish block (look for
     the existing `if (drawingPoints.size >= minPts)` save site);
     after `drawingStore.add(...)`, build CoT XML and call
     `app.serverManager.sendCoT(xml)`.
   - iOS: equivalent in `DrawingToolsManager` save site, calls
     `TAKService.shared.sendCoT(_:)`.
4. **Receive incoming drawings, both platforms.**
   - Android: extend `TAKConnection.received.collect` parser in
     `ServerManager.connect` (vc40 already has the chat-vs-contact
     branching) — add a third branch for `u-d-*` events that ingest
     into `drawingStore`.
   - iOS: same in `TAKService` receive pipeline.
5. **Tests:**
   - Android: `DrawingCoTTest` covers line/polygon/circle/bullseye
     XML envelope + round-trip parser.
   - iOS: equivalent in `OmniTAKMobileTests`.

**Don't touch:** Track recording, offline tiles, Mark-My-Position,
mission exporter (those are S1 + S2's territory).

**Pre-flight reading:**
- `OmniTAK-Android/app/src/main/kotlin/.../data/MarkerCoT.kt` (XML
  conventions)
- `OmniTAK-Android/app/src/main/kotlin/.../data/Drawing.kt`
- `OmniTAK-Android/app/src/main/kotlin/.../domain/SelfPositionBroadcaster.kt`
  (for stale-time conventions)
- ATAK CoT extension docs for `u-d-*` types — search the
  `mil.darpa.tak` references on GitHub if needed.

---

## Sub-agent dispatch hints (per session)

Each lead agent should reach for sub-agents when:

- **`Explore` agent** — to map an unfamiliar code area before editing.
  Hand it a single concrete question ("show me every place that calls
  `serverManager.sendCoT`"). Don't spawn one for things you can answer
  with a single grep.
- **`general-purpose` agent** — for an isolated, self-contained chunk
  (e.g. "implement TileDownloader.kt with these specs"). Bound by a
  written contract (function signatures + tests). Don't spawn for
  open-ended design.
- **`Plan` agent** — for architectural choices that span multiple
  files (e.g. "how should the offline tile cache integrate with
  MapLibre's OkHttp client?"). One-shot, then act on the plan
  yourself.

Run sub-agents **in parallel where independent** — single Agent message
with multiple tool uses.

## Definition of done — per session

A session is done when:

1. All scoped features compile cleanly on its target platform(s).
2. New unit tests are green; full test suite passes.
3. AAB / TestFlight build cut on the feature branch.
4. Release notes file written.
5. PR description drafted referencing this doc + listing which K9
   feedback items it closes.
6. Branch pushed (or kept local for J to review — confirm with J).

## Integration step — after all 3 sessions complete

J or a designated "merge agent" runs:

```bash
cd ~/Projects/OmniTAK-Android
git checkout feat/closed-test-feedback-may2026
git merge --no-ff feat/sar-s1-field-tools
git merge --no-ff feat/sar-s3-drawing-cot
# (S2 is iOS-only on Android side — no merge needed there)

cd ~/Projects/OmniTAK-iOS
git checkout feat/closed-test-feedback-may2026
git merge --no-ff feat/sar-s2-ios-catchup
git merge --no-ff feat/sar-s3-drawing-cot
```

Conflicts are expected only inside marker blocks and are resolved by
keeping both regions. After merge:
- Bump versionCode + versionName one final time so the merged build
  has a single coherent version number.
- Run full test suite on each platform.
- Build final AAB + iOS archive.
- Write a consolidated release notes file (`vcN.txt`) summarising the
  full SAR closeout.

## What we explicitly defer

These are **not** in the 3-session plan. Filed for after:

- **GeoPDF overlay import** — needs a georeferenced-PDF parsing lib
  (no good Kotlin/Swift option off-the-shelf). ≈ 1.5 days when picked
  up. Add to next planning round.
- **Real DEM data** — USGS NED tile overlay + elevation profile
  service. ≈ 1 day. Add to next planning round.
- **FEMA ICS icon glyphs** — sourcing the artwork is the long pole;
  the labelling pipeline is already in place via `AppMode.terminology`.

## Integration backlog

Items a session discovers it can't do without another's data —
record them here and pick up post-merge.

- **(S1, 2026-05-11)** `playstore-assets/build-release.sh` aborts on
  `set -euo pipefail` when no prior AAB is staged in the worktree
  (the `ls playstore-assets/*.aab 2>/dev/null | …` pipeline exits 1
  on empty match). Workaround used in S1: ran `./gradlew bundleRelease`
  directly and copied the AAB by hand. Fix the script during
  integration: `shopt -s nullglob` before the glob, or `|| true` on
  the assignment.
- **(S1, 2026-05-11)** MapLibre 11.x ships without OkHttp by default;
  S1 added `com.squareup.okhttp3:okhttp:4.12.0` to `app/build.gradle.kts`
  and wires `HttpRequestUtil.setOkHttpClient(...)` reflectively in
  `OmniTAKApp.wireOfflineTileCacheToMapLibre()`. If MapLibre's
  `org.maplibre.gl:okhttp` module is added to deps later, the
  reflection call still works — no migration needed.
