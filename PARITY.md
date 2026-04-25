# OmniTAK iOS ↔ Android parity tracker

This file lives identically in both [OmniTAK-iOS](https://github.com/engindearing-projects/OmniTAK-iOS) and [OmniTAK-Android](https://github.com/engindearing-projects/OmniTAK-Android). It tracks visual and behavioral gaps between the two clients so they converge on a single OmniTAK design language.

## Design decisions (locked)

These are the architectural calls made up front. Don't reopen them in PRs without raising it as an issue first.

| Decision | Choice | Reasoning |
|---|---|---|
| **Primary navigation** | Bottom tab bar | TAK users are often gloved / one-handed. Matches iTAK convention. Android already there. |
| **Secondary actions** | Long-press radial menu on the map | Already implemented on both platforms; keep it. |
| **Color palette** | Tactical dark by default | Both already lean dark. Extract shared tokens. |
| **License** | Apache-2.0 on both | |

## Open parity gaps

Tracked as a single checklist; tick off when both platforms match. Each item should land as **paired commits** — one PR per repo, referencing the same gap ID.

### P1 — primary navigation
- [x] **GAP-001** iOS: replace hamburger drawer with bottom tab bar (Map / Chat / Servers / Mesh / Settings) — done in `RootTabView.swift`
- [x] **GAP-002** Android: bottom tab bar already in place — keep it
- [x] **GAP-003** Both: identical tab order, labels, icons (SF Symbols ↔ Material icons mapped 1:1)
- [x] **GAP-004** Android: replace flat NavigationBar with floating Liquid Glass capsule matching iOS 26 aesthetic — `LiquidGlassNavBar.kt`. Per-tab brand colors, tinted halo on selection, drop shadow, translucent surface.

### P2 — map basemap
- [x] **GAP-010 (interim)** iOS: default switched from `.satellite` to `.standard` (street map with labels)
- [x] **GAP-010-android-dark** Android basemap upgraded to CartoDB Dark Matter — pure tactical look, both clients now dark by default
- [ ] **GAP-010 (final)** iOS: optional swap MKMapView → MLNMapView to render the same MapLibre style natively. Lower priority now that both look tactical.
- [ ] **GAP-011** Both: provide identical built-in basemap picker (OSM, OpenTopo, Satellite, Dark)
- [ ] **GAP-012** Both: persist last-selected basemap

### P3 — status bar
- [x] **GAP-020** Canonical metric set locked: `dot · server-name · ↓ · ↑ · ±accuracy · time · ☰`
- [x] **GAP-021** iOS adopted text `↓` / `↑` arrows (cyan / orange), matching Android ATAKStatusBar
- [x] **GAP-022** iOS time formatter switched to 24h `HH:mm`
- [x] **GAP-023** Android `±Nm` accuracy badge wired through ATAKStatusBar (stubbed value pending GAP-030b)

### P4 — self-position display
- [x] **GAP-030** PPLI card visible on both — iOS already had it; Android added `SelfPositionCard.kt`
- [x] **GAP-030c** Hide-from-Layers toggle on both. Long-press → Radial → Layers → Callsign Card switch. Mirrors operator complaints that the panel was covering map data.
- [ ] **GAP-030b** Wire real telemetry on Android: FusedLocationProviderClient flow + UserPrefsStore callsign (currently stubbed)
- [ ] **GAP-031** Card position: iOS floats at user location, Android docks bottom-right above bottom nav. Pick one canonical position and align.
- [ ] **GAP-032** Android: replace MapLibre default location marker with ATAK-style self-marker icon (matches iOS red triangle)

### P5 — map controls
- [ ] **GAP-040** Both: same overlay buttons in same positions
- [ ] **GAP-041** Both: scale bar visible by default
- [ ] **GAP-042** Both: zoom +/− buttons (currently iOS has, Android missing)
- [ ] **GAP-043** Both: locator FAB returns to user position

### P6 — long-press radial menu
- [x] **GAP-050** iOS: RadialMenuView already implemented
- [x] **GAP-051** Android: RadialMenu.kt already implemented
- [ ] **GAP-052** Both: same set of radial actions, same icons, same color tokens

### P7 — design tokens
- [ ] **GAP-060** Define shared color palette doc — primary, secondary, success, warning, danger, surface variants
- [ ] **GAP-061** iOS: extract to `Color+Tactical.swift`
- [ ] **GAP-062** Android: extract to `ui/theme/Color.kt` (refactor existing)
- [ ] **GAP-063** Typography scale shared across both

### P8 — onboarding
- [ ] **GAP-070** iOS has FirstTimeOnboarding flow — Android has none, decide if we add it
- [ ] **GAP-071** If yes, identical copy and visuals

### P9 — feature gaps (Android-side)
Features iOS has that Android doesn't yet. Triage which need parity vs which can wait.

- [ ] **GAP-080** Data Package import (.zip) — iOS has, Android missing
- [ ] **GAP-081** CSR enrollment (port 8446) — iOS has, Android missing
- [ ] **GAP-082** Video feeds (HLS / RTSP / SRT) — iOS has, Android missing
- [ ] **GAP-083** Photo attachments with EXIF — iOS has, Android missing
- [ ] **GAP-084** Plugin system — iOS has, Android missing
- [ ] **GAP-085** ADS-B traffic display — iOS has, Android has scaffolding (`AdsbService.kt`)

### P10 — feature gaps (iOS-side)
Features Android has that iOS may benefit from. Same triage.

- [ ] **GAP-090** None known yet — to be filled in as discovered

## How to work a parity gap

1. Pick an unchecked GAP that's not blocked by a higher-priority one
2. Open an issue in **both** repos titled `parity: GAP-NNN — short description` and cross-link
3. Implement on the platform that's behind. If both need work, decide source of truth first
4. Open paired PRs. Reference the same GAP ID in both PR titles
5. Both PRs merge together (or close together — within a week)
6. Tick the box in this file in **both repos**
7. Update PARITY.md in the other repo to match

## Owners

- **iOS lead:** OmniTAK-iOS contributors
- **Android lead:** OmniTAK-Android contributors
- **Design source of truth:** this document. Conflicts? Open an issue, don't just diverge.
