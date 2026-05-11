# OmniTAK preferences reference

Canonical list of preference keys OmniTAK reads on each platform, what
can set them, and which ATAK aliases route to each one. Lives in both
[OmniTAK-iOS](https://github.com/engindearing-projects/OmniTAK-iOS/blob/main/PREFERENCES.md)
and [OmniTAK-Android](https://github.com/engindearing-projects/OmniTAK-Android/blob/main/PREFERENCES.md).

## How to set a preference

Three on-ramps, all of them work today on both platforms:

1. **Settings screen** — every key below is exposed in the in-app
   Settings UI.
2. **`tak://` deep link** — paste or scan a QR of the form
   `tak://com.atakmap.app/preference?key1=…&type1=…&value1=…`. Indexed
   `keyN`/`typeN`/`valueN` groups, walked until the first missing
   `keyN`. ATAK / iTAK / TAKaware speak the same scheme so any portal
   already generating these for ATAK works against OmniTAK.
3. **Data package `.zip`** — drop a `MANIFEST.xml` + `server.pref` +
   `.p12` bundle into the app via Files (iOS) / share-sheet or
   `<app>/files/import/` (Android).

> Server-pushed remote config (`OpenTAKserver` → many clients) is **on
> the roadmap as GAP-108** but not shipped on either platform yet.
> Today every preference write goes through one of the three on-ramps
> above.

`tak://` URL scheme verb summary:

| Verb | Android | iOS | Notes |
|---|---|---|---|
| `/connect` (or bare query) | ✅ | ✅ | `host`, `port`, `proto`/`protocol`, `username`, `password`, `name`. |
| `/import?url=…` | ✅ | ✅ | http(s) only. Downloads zip, runs data-package importer. |
| `/preference?keyN=…&typeN=…&valueN=…` | ✅ | ✅ | Indexed groups. Unknown keys silently dropped. |
| `/enroll?host=…&username=…&token=…` | partial (stages server stub) | ✅ full CSR flow | Full Android CSR enrollment is GAP-081, in flight. |

## Android keys (`app/.../data/UserPrefs.kt`)

Persisted via DataStore. ATAK-side aliases route to the same OmniTAK
field — anything outside this table is ignored.

| OmniTAK key | Type | Default | ATAK aliases honoured |
|---|---|---|---|
| `callsign` | string | `OMNI-1` | `locationCallsign` |
| `team` | string (CYAN / one of 14 ATAK canonical colors) | `CYAN` | `locationTeam` |
| `coordFormat` | enum (`LATLON_DECIMAL`, `LATLON_DMS`, `MGRS`, `UTM`) | `LATLON_DECIMAL` | `coord_display_format` |
| `distanceUnit` | enum (`METRIC`, `IMPERIAL`) | `METRIC` | `rangeSystem` |
| `mapProvider` | enum (`OSM_RASTER`, `SATELLITE_HINT`, `TOPO_HINT`, `WMTS_CUSTOM`) | `TOPO_HINT` | — |
| `customTileUrl` | string (XYZ URL template `https://host/{z}/{x}/{y}.png`) | `""` | — |
| `autoPublishMeshToTak` | boolean | `true` | — |
| `meshNodesLayerVisible` | boolean | `true` | — |
| `callsignCardVisible` | boolean | `true` | — |
| `gridEnabled` | boolean | `false` | — |
| `drawingsVisible` | boolean | `true` | — |
| `aircraftVisible` | boolean | `true` | — |
| `contactsVisible` | boolean | `true` | — |
| `followMeActive` | boolean | `false` | — |

### Android Meshtastic device-config keys

Stored in `MeshDeviceConfigStore` (DataStore), pushed to the radio
via the Mesh tab → **Push to device** button:

| Key | Type | Notes |
|---|---|---|
| `longName` | string | Device long name |
| `shortName` | string | Device short name (max 4 chars) |
| `role` | enum (`CLIENT`, `CLIENT_MUTE`, `ROUTER`, `ROUTER_CLIENT`, `REPEATER`, `TRACKER`, `TAK`) | Meshtastic role |
| `positionBroadcastSecs` | int | **PLI broadcast interval** (15 / 30 / 60 / 120 / 300 s quick-pick) |
| `channel0Name` | string | Primary channel name |
| `channel0Preset` | enum (`LONG_FAST`, `LONG_SLOW`, `MEDIUM_FAST`, `MEDIUM_SLOW`, `SHORT_FAST`, `SHORT_SLOW`) | LoRa modem preset |

These are *not* yet writable via `tak://preference` — they're
device-side config, not app prefs. If the user need is "push PLI
interval to a whole event from the portal," that lands as part of
GAP-108 (server-driven config).

## iOS keys (`@AppStorage` in `Features/Settings/Views/SettingsView.swift`)

Backed by `UserDefaults`. Same ATAK-side aliases honoured.

| OmniTAK key | Type | Default | ATAK aliases honoured |
|---|---|---|---|
| `userCallsign` | string | `ALPHA-1` | `callsign`, `locationCallsign` |
| `userName` | string | `Operator` | `operator` |
| `unitSystem` | string (`Metric`, `Imperial`) | `Metric` | `distanceUnit`, `rangeSystem` |
| `mgrsGridEnabled` | boolean | `false` | `gridEnabled` |
| `mgrsGridDensity` | string (`100km`, `10km`, `1km`) | `1km` | — |
| `showMGRSLabels` | boolean | `true` | — |
| `coordinateDisplayFormat` | string (`DD`, `DM`, `DMS`, `MGRS`, `UTM`, `BNG`) | `MGRS` | `coordFormat`, `coord_display_format` |
| `breadcrumbTrailsEnabled` | boolean | `true` | — |
| `trailMaxLength` | int (10–500) | `100` | — |
| `trailColorName` | string (`cyan`, `green`, `orange`, `red`, `blue`) | `cyan` | — |
| `appMode` | string (`tactical`, `fire_rescue`, `sar`, `civilian`) | `tactical` | — |

### iOS Meshtastic device-config keys

Stored in `MeshDeviceConfigStore` (UserDefaults), pushed via the same
**Push to device** flow:

| UserDefaults key | Type | Notes |
|---|---|---|
| `mesh.device.longName` | string | Device long name |
| `mesh.device.shortName` | string | Short name (max 4 chars) |
| `mesh.device.role` | enum | `client`, `router`, `tracker`, `tak`, … |
| `mesh.device.pliSecs` | int | PLI broadcast interval |
| `mesh.device.ch0.name` | string | Primary channel name |
| `mesh.device.ch0.preset` | enum | LoRa modem preset |

## Cross-platform notes

- **Parity is the design intent.** Where naming differs between
  `UserPrefs` (Android Kotlin) and `@AppStorage` (iOS Swift), the ATAK
  alias column above is the canonical wire name — use those in any
  portal-generated QR code so it works against both.
- **Boolean coercion is permissive.** `true`/`1`/`yes`/`on` all parse
  truthy; `false`/`0`/`no`/`off` all parse falsy.
- **Unknown keys are silent.** A `tak://preference?key1=displayRed&…`
  payload generated for full ATAK still works — OmniTAK accepts the
  link, applies what it knows, and drops the rest.
- **Server credentials are not preferences.** Use `/connect`, `/enroll`,
  or a data package to onboard a server. The `host`/`port`/`cert`
  fields live with `TAKServer`, not `UserPrefs` / `@AppStorage`.

## Roadmap

| Gap | What it adds |
|---|---|
| GAP-081 | Full CSR enrollment on Android (port 8446). iOS has it today; Android currently stages the server stub when `/enroll` fires. |
| GAP-108 | Server-pushed remote config — operator publishes PLI intervals, basemap defaults, callsign rules, etc. from OpenTAKserver to every connected EUD. |
| GAP-109a (iOS) | Push-to-device write path for Meshtastic admin messages. Shipped on Android; iOS pending. |
