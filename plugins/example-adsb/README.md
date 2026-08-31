# example-adsb: the ADS-B reference plugin

`:plugins:example-adsb` is the canonical OmniTAK plugin. It shows the minimal
real-world shape: one HTTP client, one map layer, its own settings, toggle-gated.

It implements [`OmniTAKPlugin`](../plugin-sdk/src/main/kotlin/soy/engindearing/omnitak/plugin/OmniTAKPlugin.kt)
and depends on **only** `:plugins:plugin-sdk` + MapLibre, never on `:app`.

## What's inside

| File | Role |
|------|------|
| `AdsbPlugin.kt` | The `OmniTAKPlugin`. `activate()` registers a map overlay + a Settings row. Owns one `AdsbService`. |
| `AdsbService.kt` | OpenSky Network poller (the single `HttpURLConnection`). Pure coroutines + `org.json`. |
| `Aircraft.kt` | The aircraft state-vector data class. |
| `AircraftLayer.kt` | Feeds the `aircraft-src` GeoJSON source baked into the host's tactical style (the GL render path chosen for Adreno 610 / SwiftShader). |
| `AdsbGeoJsonFeeder.kt` | Self-contained copy of the host's GeoJSON push/clear helpers, so the module is portable. |
| `AdsbMapOverlay.kt` | The registered map overlay. Reads `LocalMapEngineHandle`, casts to `MapLibreMap`, and drives `AircraftLayer.update`. No-ops on the Cesium globe (handle is null). |
| `AdsbSettingsContent.kt` | The on/off toggle + readouts, reached via Settings → Plugins → ADS-B. |

## The hooks it uses

`AdsbPlugin.activate(host)` uses exactly two of the four host hooks:

```kotlin
host.registerMapOverlay { AdsbMapOverlay(service) }
host.registerSettingsRow("ADS-B", Icons.Filled.Flight)
```

(The radial-action and CoT-handler hooks are exercised by a probe plugin + unit
tests in `:app` so the whole host surface is covered.)

## Behavior parity

This plugin is a refactor, not a rewrite. Aircraft render identically on the
MapLibre engine, never render on the globe, and OpenSky polling + camera-follow
recenter are unchanged from the pre-plugin in-app feature. The only relocation
is the on/off control: it moved from the Tools drawer to the plugin's settings.

## Style-infrastructure contract

The `aircraft-src` source and the `aircraft-circle`/`aircraft-label` layers live
in **`:app`'s** embedded tactical style JSON (`TacticalMap.buildTacticalStyle`).
They are MapLibre style infrastructure, not ADS-B logic. This plugin feeds the
source via the live map handle; if the host ever removes the source,
`AircraftLayer.update` silently no-ops. See the contract comments in
`TacticalMap.kt` and `AircraftLayer.kt`.

## Tests

`src/test/kotlin/soy/engindearing/adsb/AdsbRecenterTest.kt` covers the OpenSky
query-box recenter logic. Run with:

```
./gradlew :plugins:example-adsb:testDebugUnitTest
```
