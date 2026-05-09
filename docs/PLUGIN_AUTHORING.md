# OmniTAK Android — Plugin SDK Authoring (RFC)

Status: **Draft / RFC**. Pairs with the iOS plugin SDK shipped in
`OmniTAK-iOS` (`OmniTAKPlugin` protocol + `PluginRegistry`). This document
proposes the Android-side equivalent. **No runtime SDK code lands in this
PR** — only the design.

## Goals

1. Mirror the iOS plugin shape closely enough that an author can port a
   plugin between platforms in a day.
2. Stay Play-Store-compliant. No DEX class loading, no remote code download.
3. Keep the registry in-process and dependency-light.
4. Make ADS-B (already a self-contained feature on Android) the canonical
   reference plugin once the SDK is implemented.

## Non-goals (for now)

- Runtime/downloadable plugins (Play policy gets dicey on dynamic code
  loading; revisit if there's user demand).
- Cross-process plugins (no AIDL boundary).
- Sandboxing — every plugin runs in the host process, with the host's
  permissions. Authoring guide will say so.

## Bundling model

Pick **option 3 — compile-time Kotlin modules with a registry pattern**
(matches the iOS architecture closest):

```
:app                       <- main application module
:plugins:plugin-sdk        <- public interfaces + PluginHost API
:plugins:example-adsb      <- reference plugin
:plugins:meshtastic        <- if/when we extract Meshtastic as a plugin
```

Plugins are Gradle modules under `:plugins:*`. The app module declares
`implementation(project(":plugins:example-adsb"))` for each plugin to
include. This gives:

- Clear module boundary (lint, compile-time enforcement of the SDK
  surface)
- Plugins can declare their own deps (e.g. ADS-B's OpenSky HTTP client)
  without polluting the main app's classpath
- Authors can fork the repo, drop a plugin module under `plugins/` and
  add one line to settings.gradle.kts and one line to app/build.gradle.kts

Future option (deferred): publish the SDK as a Maven artifact so authors
can build out-of-tree plugins and have us include them at app build time.

## Public SDK surface (proposed)

In `plugins/plugin-sdk/src/main/kotlin/soy/engindearing/omnitak/plugin/`:

```kotlin
/**
 * Implement this interface to ship a plugin. Mirrors iOS
 * OmniTAKPlugin protocol.
 */
interface OmniTAKPlugin {
    val pluginId: String              // reverse-DNS, e.g. "soy.engindearing.adsb"
    val displayName: String           // shown in PluginsListScreen
    val pluginVersion: String         // semver
    val pluginAuthor: String
    val pluginDescription: String

    /** Called once at process start, after the host is fully initialised.
     *  Plugins should register all their hooks via [host] here. */
    fun activate(host: PluginHost)

    /** Called when the user disables the plugin in PluginsListScreen.
     *  Plugins should unregister hooks and stop background work. */
    fun deactivate()

    /** Optional Compose-based settings UI shown in the plugin detail
     *  page. Returning null means "no settings to configure". */
    fun settingsContent(): (@Composable () -> Unit)? = null
}

/**
 * Plugins receive this in [activate] and use it to register hooks.
 * Mirrors iOS PluginHost.
 */
interface PluginHost {
    /** Compose layer that renders below the main TacticalMap chrome.
     *  Plugins typically draw their own GeoJsonSource + Layer here. */
    fun registerMapOverlay(overlay: @Composable () -> Unit)

    /** Adds an entry to the long-press radial menu. */
    fun registerRadialAction(action: RadialAction, onSelect: (LatLng) -> Unit)

    /** Called for every inbound CoT event after the core ContactStore
     *  ingests it. Return true to mark "consumed". */
    fun registerCoTHandler(handler: (CoTEvent) -> Boolean)

    /** Adds a row to the Settings screen, navigating to the plugin's
     *  [OmniTAKPlugin.settingsContent] when tapped. */
    fun registerSettingsRow(label: String, icon: ImageVector)
}
```

## Registry

`plugins/plugin-sdk/src/main/kotlin/.../PluginRegistry.kt`:

```kotlin
/**
 * Singleton populated at app start. App reads the
 * "plugin_<id>_enabled" SharedPreferences flag to decide whether to
 * activate(); user toggles it from PluginsListScreen.
 */
object PluginRegistry {
    private val plugins = mutableListOf<OmniTAKPlugin>()
    private val activated = mutableSetOf<String>()

    fun register(plugin: OmniTAKPlugin) { plugins += plugin }
    fun all(): List<OmniTAKPlugin> = plugins.toList()

    fun activateEnabled(host: PluginHost, prefs: SharedPreferences) {
        for (p in plugins) {
            val key = "plugin_${p.pluginId}_enabled"
            // Default true on first run so first-time users see plugin
            // features without hunting in Settings.
            if (prefs.getBoolean(key, true) && p.pluginId !in activated) {
                p.activate(host)
                activated += p.pluginId
            }
        }
    }

    fun deactivate(pluginId: String) {
        plugins.firstOrNull { it.pluginId == pluginId }?.deactivate()
        activated -= pluginId
    }
}
```

App entry (in `OmniTAKApp.onCreate()`) calls `loadBundledPlugins()`:

```kotlin
private fun loadBundledPlugins() {
    PluginRegistry.register(soy.engindearing.adsb.AdsbPlugin())
    // future: more bundled plugins
    PluginRegistry.activateEnabled(host = AppPluginHost(this), prefs = pluginPrefs)
}
```

## Reference plugin: extracting ADS-B

ADS-B today lives in `data/AdsbService.kt` + `ui/components/AircraftLayer.kt`.
To make it a plugin:

1. New module `plugins/example-adsb/`.
2. Move `AdsbService` + `AircraftLayer` + the OpenSky client into it.
3. Add an `AdsbPlugin: OmniTAKPlugin` that:
   - registers `AircraftLayer` as a map overlay via `host.registerMapOverlay`
   - exposes a Settings row "ADS-B" via `host.registerSettingsRow`
4. Remove the ADS-B-specific code from `:app` (just the imports and the
   tools-drawer action — the plugin re-adds those via the SDK).

This is the canonical example because:
- Self-contained (one HTTP client, one map layer, no cross-cutting deps)
- Already gated behind a user toggle (the tools-drawer "ADSB" button)
- Has its own Settings surface
- Mirrors what the iOS team did exactly

## What lands in this issue

This RFC. **No SDK code yet** — the next PR (and a separate issue) does:

- [ ] Add `:plugins:plugin-sdk` module with the interfaces above.
- [ ] Add `:plugins:example-adsb` and move ADS-B into it.
- [ ] Wire `loadBundledPlugins()` from `OmniTAKApp.onCreate()`.
- [ ] PluginsListScreen reading from `PluginRegistry`, toggling
      `plugin_<id>_enabled`.
- [ ] README pointer to this doc.

## Open questions

1. Should the plugin SDK module be published as a Maven artifact?
   (Probably yes once it's stable, but not in v0.4.)
2. How do plugins persist their own state? Suggest: each plugin owns
   its own DataStore, namespaced by `pluginId`. SDK doesn't need to
   provide one.
3. Do we need a plugin lifecycle on connection state? (The ADS-B
   plugin doesn't care, but a "auto-route to TAK" plugin might.) Defer
   until we hit a real plugin that needs it.

## Compatibility note

Authors writing both iOS and Android plugins should keep `pluginId`
identical across platforms. Settings tied to `pluginId` (e.g. "ADS-B
update interval") can then sync via TAK data packages or QR codes
without per-platform key remapping.
