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
- [x] **GAP-032** Android: replaced MapLibre default blue pulse with tactical-accent ATAK bullseye drawable + bearing chevron — `ic_self_marker.xml` / `ic_self_marker_bearing.xml`. iOS still uses MKMapView default blue dot; matching iOS to this style is filed as GAP-032b.
- [x] **GAP-032b** iOS: primary MapViewController delegate now returns a configured MKAnnotationView with `SelfPositionMarkerImage.bullseye` for MKUserLocation. Same hex / proportions as Android `ic_self_marker.xml`. Other map delegates (Map3D, RadialMenuMapOverlay, MeasurementService) still default — adopt later.

### P5 — map controls
- [ ] **GAP-040** Both: same overlay buttons in same positions
- [ ] **GAP-041** Both: scale bar visible by default. iOS already has one; Android needs camera-change listener + meters/pixel calc — deferred.
- [x] **GAP-042** Android: stacked zoom +/− FABs added at BottomStart, paired with locator. `MapControlFab` helper. TacticalMap accepts `zoomInTrigger` / `zoomOutTrigger` Int counters.
- [ ] **GAP-043** Both: locator FAB returns to user position

### P6 — long-press radial menu
- [x] **GAP-050** iOS: RadialMenuView already implemented
- [x] **GAP-051** Android: RadialMenu.kt already implemented
- [ ] **GAP-052** Both: same set of radial actions, same icons, same color tokens

### P7 — design tokens
- [x] **GAP-060** Shared design-tokens spec lives at [DESIGN_TOKENS.md](DESIGN_TOKENS.md) — color palette (surface / brand / affiliation / status / nav-tab tints / text), typography scale, spacing scale, shape radii, elevation tiers. Identical file in both repos.
- [ ] **GAP-061** iOS: extract to `Color+Tactical.swift` referencing DESIGN_TOKENS.md hexes
- [ ] **GAP-062** Android: expand `ui/theme/Color.kt` to cover every DESIGN_TOKENS token
- [ ] **GAP-063** Typography scale extracted into shared types referenced by both

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

### P11 — Android closed-test feedback (P-E, May 2026)
Real practitioner feedback from Android closed test. Some are bugs to fix, some are features to add. iOS may have the same issues — audit during port.

- [~] **GAP-100** Callsign on main screen stuck at hardcoded `"OMNI-1"` — `MapScreen.kt` was passing a literal string instead of `userPrefs.callsign`. **Code shipped — awaiting SxS verification on emulator before final tick.**
- [~] **GAP-101** Map tile picker (Settings) didn't switch the basemap — `TacticalMap` defaulted to CARTO Dark and `MapScreen` never overrode it. Now wires `MapProvider` enum to a per-provider style JSON (OSM standard / OpenTopoMap / Esri World Imagery / CARTO Dark) and re-applies via `DisposableEffect(styleJson)`. Settings copy updated. **Code shipped — awaiting SxS.**
- [~] **GAP-102** Top-left + top-right hamburger menus on main screen — were wired to empty `Slice 6:` lambdas. Now route via existing `onOpenTab`: server icon → Servers tab; menu icon → Settings tab. **Code shipped — awaiting SxS.**
- [~] **GAP-103** Settings text fields jumpy — DataStore round-trip on every keystroke. Local `mutableStateOf` draft now insulates the field from re-emission; remember-key re-syncs on external changes. Fixed for Callsign (Team is now a dropdown — see GAP-104). **Code shipped — awaiting SxS.**
- [~] **GAP-104** Team field replaced with ATAK standard color dropdown — 14 canonical colors with swatches matching CoT spec (White, Yellow, Orange, Magenta, Red, Maroon, Purple, Dark Blue, Blue, Cyan, Teal, Green, Dark Green, Brown). **Code shipped — awaiting SxS. iOS port pending.**
- [~] **GAP-105** Server auth menu — server icon on the status bar opens Servers tab (GAP-102); Add Server form has username + password; **deep-link import landed** — `atak://`, `omnitak://`, and `https://?host=…` URIs add a server in one tap. Phone QR scanners deep-link into us natively. Cert / data-package zip handling deferred. **Code shipped — awaiting SxS.**
- [~] **GAP-106** UTM added to the coordinate-format picker. Display still pending GAP-030b (real position telemetry) — same status as MGRS. **Code shipped — awaiting SxS. iOS port pending.**
- [~] **GAP-107** Custom WMTS basemap — fourth picker option ("Custom") reveals a tile-URL field. Anything XYZ-templated (`https://host/{z}/{x}/{y}.png`) works. Falls back to OSM if blank/invalid. **Code shipped — awaiting SxS.**
- [~] **GAP-109** Meshtastic device settings UI — `MeshDeviceSettingsScreen.kt` reachable via "Device settings" on the Mesh tab. Covers long/short name, role (CLIENT / CLIENT_MUTE / ROUTER / ROUTER_CLIENT / REPEATER / TRACKER / TAK / etc.), PLI broadcast interval (with 15/30/60/120/300 s quick-pick), and primary channel name + LoRa preset (LONG_FAST / LONG_SLOW / etc.). Backed by `MeshDeviceConfigStore` (DataStore-backed draft). iOS parity pending. References: [meshsat-android](https://github.com/cubeos-app/meshsat-android), [columba](https://github.com/torlando-tech/columba). **Code shipped — awaiting SxS.**
- [~] **GAP-109a** Push-to-device write path landed. New `AdminMessageSerializer` hand-rolls the four admin-port submessages we need (`set_owner`, `set_config{device.role}`, `set_config{position.position_broadcast_secs}`, `set_channel{channel0.name}`, `set_config{lora.modem_preset}`) and wraps each in a `ToRadio` with portnum 6 / `want_response = true`. `MeshtasticManager.pushDeviceConfig(...)` dispatches them sequentially over the active TCP or BLE transport and reports how many landed. Settings screen now shows a real **"Push to device"** button when a radio is connected; falls back to the prior copy when offline. Field numbers / enum ordinals taken from the canonical Meshtastic firmware `.proto` set; if upstream reshuffles them we follow. **Code shipped — awaiting SxS.**
- [~] **GAP-110** Several main-screen toggles (Layers panel — callsign card, grid, drawings, aircraft, contacts visibility — and Follow Me) didn't survive a relaunch because they lived in `var ... by remember { mutableStateOf(...) }` instead of DataStore. Six new boolean fields on `UserPrefs`; reads alias from `userPrefs`, writes go through `mutatePref { it.copy(...) }`. **Code shipped — awaiting SxS.**
- [x] **GAP-111** Dead-route audit — every clickable in the UI tree was checked for empty lambdas / TODO callbacks. Clean after GAP-102 wired the only two offenders. No further changes.

### P12 — Roadmap (bigger asks)
- [ ] **GAP-108** Server-pushed app config / data package settings. Operator pushes settings (PLI intervals, default basemap, server URL, callsign rules) to clients via OpenTAKserver, config file, or `.zip` data package. Real differentiator vs ATAK / iTAK / TAKaware. Source of complaint: 80-node airsoft event needing centralised PLI intervals.
- [ ] **GAP-109b** Meshtastic admin-message acks — surface routing/ack frames from the radio in the UI so the operator sees per-message success or "radio rejected this field". Today `pushDeviceConfig` just reports how many writes landed at the wire layer; whether the radio applied them is invisible until protobuf decode of `FromRadio.routing` ships.

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
