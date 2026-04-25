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

### P2 — map basemap
- [x] **GAP-010 (interim)** iOS: default switched from `.satellite` to `.standard` (street map with labels) — closer to Android visually but still Apple Maps. Full MapLibre parity (MKMapView → MLNMapView swap) tracked as a follow-up.
- [ ] **GAP-010 (final)** iOS: swap MKMapView for MLNMapView throughout so iOS uses MapLibre OSM-style natively, matching Android's basemap pixel-for-pixel
- [ ] **GAP-011** Both: provide identical built-in basemap picker (OSM, OpenTopo, Satellite, Dark)
- [ ] **GAP-012** Both: persist last-selected basemap

### P3 — status bar
- [ ] **GAP-020** Decide canonical metric set: `connection state`, `↓bytes`, `↑bytes`, `±accuracy`, `time`
- [ ] **GAP-021** iOS: `↓0 ↑0` use plain text counters (Android style) OR Android adopts filled circles
- [ ] **GAP-022** Time format: 24h tactical (Android) — iOS adopts
- [ ] **GAP-023** Accuracy badge `±Xm` visible on both

### P4 — self-position display
- [ ] **GAP-030** Both: PPLI info card showing `Callsign / MGRS / MSL / km/h / ±accuracy`
- [ ] **GAP-031** Both: card position (bottom-right on iOS, decide for Android)
- [ ] **GAP-032** Both: ATAK-style self-marker icon (not platform default pin)

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
