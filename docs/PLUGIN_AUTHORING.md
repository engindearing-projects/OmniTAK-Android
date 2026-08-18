# OmniTAK Android: Plugin SDK Authoring

Status: **Shipped (v0.35)**. The `OmniTAKPlugin` SDK and the ADS-B reference
plugin are real, compile-time Gradle modules in this repo. A matching SDK ships
on `OmniTAK-iOS` in the same release, keep `pluginId` identical across
platforms and a plugin ports in roughly a day.

## What shipped

| Module | Purpose |
|--------|---------|
| `:plugins:plugin-sdk` | Public contract: `OmniTAKPlugin`, `PluginHost`, `PluginRegistry`, the SDK value types, and `LocalMapEngineHandle`. Depends only on Compose + Material3, **not** on `:app` or MapLibre. |
| `:plugins:example-adsb` | The ADS-B reference plugin. Owns its `AdsbService` (OpenSky poller), its map overlay (`AircraftLayer` GL feeder), and its settings. Depends on `:plugins:plugin-sdk` + MapLibre only. |

`:app` declares `implementation(project(":plugins:plugin-sdk"))` and
`implementation(project(":plugins:example-adsb"))`. The dependency direction is
strictly **`:app → {plugin-sdk, example-adsb} → maplibre`**, never backwards.
There is no circular dependency.

## Security model: read this

Plugins run **in the host process with the host's permissions**. There is no
sandbox and no AIDL boundary. A plugin can do anything the app can do (network,
location, files). Only bundle plugins you trust. The Plugins UI says so too.

## Store compliance

Plugins are **compile-time Kotlin/Gradle modules** linked into the app at build
time. There is **no** DEX class loading, no dylib/`.so` loading, and **no remote
code download**. The registry is populated at app start from statically-linked
classes (`OmniTAKApp.loadBundledPlugins()`). This is what keeps OmniTAK
Play-Store compliant, and it is non-negotiable.

## The contract

`plugins/plugin-sdk/src/main/kotlin/soy/engindearing/omnitak/plugin/`:

```kotlin
interface OmniTAKPlugin {
    val pluginId: String          // reverse-DNS, e.g. "soy.engindearing.adsb"
    val displayName: String
    val pluginVersion: String     // semver
    val pluginAuthor: String
    val pluginDescription: String

    fun activate(host: PluginHost)   // register hooks here, once
    fun deactivate()                 // stop background work
    fun settingsContent(): (@Composable () -> Unit)? = null
}

interface PluginHost {
    fun registerMapOverlay(overlay: @Composable () -> Unit)
    fun registerRadialAction(action: PluginRadialAction, onSelect: (PluginLatLng) -> Unit)
    fun registerCoTHandler(handler: (PluginCoTEvent) -> Boolean)
    fun registerSettingsRow(label: String, icon: ImageVector)
}
```

### SDK value types (no `:app` / MapLibre leakage)

The SDK owns its own value types so it never depends on the app's internal CoT
model or on MapLibre's `LatLng`:

```kotlin
data class PluginRadialAction(val id: String, val icon: ImageVector, val label: String)
data class PluginLatLng(val latitude: Double, val longitude: Double)
data class PluginCoTEvent(
    val uid: String, val lat: Double, val lon: Double,
    val callsign: String?, val type: String?, val rawXml: String?,
)
```

The host adapts core types ⇄ SDK types at the seam (`AppPluginHost.dispatchCoT`
maps the app's `CoTEvent` → `PluginCoTEvent`).

### The map-handle pattern (`LocalMapEngineHandle`)

`registerMapOverlay` takes a plain `@Composable () -> Unit`. The overlay reads
the live map engine handle from the SDK's `CompositionLocal`:

```kotlin
val LocalMapEngineHandle = compositionLocalOf<Any?> { null }
```

It is `Any?` so the SDK stays MapLibre-free. The host provides the live
`MapLibreMap` on the 2D engine and `null` on the Cesium 3D globe. A plugin that
can only draw on MapLibre casts and no-ops on the globe:

```kotlin
@Composable
fun MyOverlay(...) {
    val map = LocalMapEngineHandle.current as? MapLibreMap ?: return  // null on Cesium
    // ... feed your GeoJSON source / layer via `map`
}
```

The host invokes the overlay loop in **both** engine branches (a future
Cesium-capable plugin isn't silently dropped on the globe, the documented
VC77 "dead on the globe" bug class).

## Registry & app wiring

`PluginRegistry` is populated at app start. The enable flag is a per-plugin
`plugin_<id>_enabled` boolean in SharedPreferences (`"omnitak_plugins"`),
**default true on first run** so first-time users see plugin features without
hunting in Settings, and existing testers see no change after the refactor.

```kotlin
// OmniTAKApp.onCreate()
private fun loadBundledPlugins() {
    PluginRegistry.register(adsbPlugin)
    PluginRegistry.activateEnabled(
        host = pluginHost.asPluginHost(),
        prefs = pluginPrefs(),                       // getSharedPreferences("omnitak_plugins", MODE_PRIVATE)
        onActivating = { id, activate -> pluginHost.withActivating(id) { activate() } },
    )
}
```

`AppPluginHost` (in `:app`) implements `PluginHost`, holds the four registration
lists as `SnapshotStateList`s (so Compose recomposes when a plugin
activates/deactivates), tags each registration with the activating plugin's id,
and provides `clearForPlugin(id)` to remove a plugin's hooks on disable.

### The four seams

| Hook | Where it's consumed |
|------|---------------------|
| `registerMapOverlay` | `MapScreen` renders `pluginHost.mapOverlays` inside `CompositionLocalProvider(LocalMapEngineHandle provides mapboxMap)` in **both** the MapLibre and Cesium branches. |
| `registerRadialAction` | `MapScreen`'s long-press radial menu appends `pluginHost.radialActions`; the `onSelect` fallback dispatches with the long-press coordinate. |
| `registerCoTHandler` | `ServerManager` calls `pluginCoTDispatch(event)` **after** `contactStore.ingest(event)`, handlers run after the core store ingests (consumed is advisory in v1). |
| `registerSettingsRow` | `SettingsScreen`'s Plugins section renders `pluginHost.settingsRows`; tapping opens `PluginDetailScreen` (the plugin's `settingsContent`). |

## Reference plugin: ADS-B

ADS-B is `:plugins:example-adsb` (`soy.engindearing.adsb`). It is the canonical
example: one HTTP client (`AdsbService` → OpenSky), one map layer
(`AircraftLayer` GeoJSON feeder), its own settings, already toggle-gated.

- `AdsbPlugin.activate(host)` calls exactly the two hooks ADS-B needs:
  `registerMapOverlay { AdsbMapOverlay(service) }` and
  `registerSettingsRow("ADS-B", Icons.Filled.Flight)`.
- `AdsbMapOverlay` reads `LocalMapEngineHandle`, casts to `MapLibreMap`, and on
  every aircraft/active change calls `AircraftLayer.update(map, ...)`, the exact
  GL render path the pre-plugin code used (chosen for Adreno 610 / SwiftShader).
- The `aircraft-src` source + `aircraft-circle`/`aircraft-label` layers stay in
  `:app`'s embedded tactical style JSON, they are MapLibre **style
  infrastructure**, not ADS-B logic (see the contract comment in
  `TacticalMap.kt` and `AircraftLayer.kt`).
- On Cesium the handle is `null`, so the overlay no-ops: identical to the
  pre-plugin behavior (aircraft were never on the globe).
- The on/off toggle that used to live in the Tools drawer now lives in the
  plugin's `settingsContent`, reached via Settings → Plugins → ADS-B. Same
  OpenSky polling, same "center on the camera target else self" box seeding
  (via the host's camera-center provider), same camera-follow recenter.

The `:app` layer-visibility pref `aircraftVisible` ("Aircraft (ADSB)" in the map
Layers dialog) is orthogonal and stays in `:app`, it gates the layer's
visibility independently of the plugin's enabled state.

## Authoring a new plugin

1. Create `plugins/<your-plugin>/` with a `build.gradle.kts` applying
   `com.android.library` + `org.jetbrains.kotlin.android` +
   `org.jetbrains.kotlin.plugin.compose`, `namespace` of your choice,
   `implementation(project(":plugins:plugin-sdk"))`, and whatever else you need.
   **Do not** add a `repositories {}` block, `settings.gradle.kts` sets
   `FAIL_ON_PROJECT_REPOS`; you inherit the central `google()`/`mavenCentral()`.
2. Add one line to `settings.gradle.kts`: `include(":plugins:<your-plugin>")`.
3. Add one line to `app/build.gradle.kts` deps:
   `implementation(project(":plugins:<your-plugin>"))`.
4. Implement `OmniTAKPlugin` and register it in `OmniTAKApp.loadBundledPlugins()`
   (`PluginRegistry.register(YourPlugin())`).
5. Keep `pluginId` identical to the iOS plugin so settings sync across platforms.

That's it, compile-time, store-compliant, no dynamic loading.

## Open questions (deferred)

1. Publish `:plugins:plugin-sdk` as a Maven artifact for out-of-tree plugins?
   Probably once it's stable; not in v0.35.
2. Plugin-owned persistence: each plugin owns its own DataStore namespaced by
   `pluginId`. The SDK doesn't provide one.
3. A connection-state lifecycle hook (ADS-B doesn't need it). Defer until a real
   plugin does.
