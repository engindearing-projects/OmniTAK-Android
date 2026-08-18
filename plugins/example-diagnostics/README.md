# example-diagnostics: the Diagnostics reference plugin

`:plugins:example-diagnostics` is the **second** bundled OmniTAK plugin and the
Android counterpart of iOS's `DiagnosticsPlugin`. Where `:plugins:example-adsb`
exercises the map-overlay + settings-row hooks, this one exercises the OTHER two
host hooks so the entire SDK surface is demonstrated by real bundled plugins:

- `registerRadialAction`: a "Diagnostics" map long-press action that logs and
  records the tapped coordinate.
- `registerCoTHandler`: observes every inbound CoT event, counts it, and
  returns **false** (does NOT consume) so the core pipeline is untouched.

It implements [`OmniTAKPlugin`](../plugin-sdk/src/main/kotlin/soy/engindearing/omnitak/plugin/OmniTAKPlugin.kt)
and depends on **only** `:plugins:plugin-sdk` + Compose, never on `:app`, and
(unlike ADS-B) not even on MapLibre, since it needs no map engine.

## Off by default

This plugin ships **DISABLED by default**, matching iOS's
`registeredDisabledByDefault`. `OmniTAKApp.loadBundledPlugins()` seeds its
`plugin_<id>_enabled` flag to `false` on first run (the id is in
`OmniTAKApp.DISABLED_BY_DEFAULT`), so the Plugins list shows it as a second
entry that is OFF. It never affects shipping users, it only proves the surface
works and gives plugin authors a second, simpler template.

## What's inside

| File | Role |
|------|------|
| `DiagnosticsPlugin.kt` | The `OmniTAKPlugin`. `activate()` registers a radial action, a CoT handler, and a Settings row. |
| `DiagnosticsState.kt` | Tiny observable state (StateFlows): inbound-CoT count + last radial coordinate. |
| `DiagnosticsSettingsContent.kt` | Read-only live readouts, reached via Settings → Plugins → Diagnostics. |

## The hooks it uses

```kotlin
host.registerRadialAction(PluginRadialAction("soy.engindearing.diagnostics.probe", Icons.Filled.BugReport, "Diagnostics")) { coordinate ->
    // logs + records the long-press coordinate
}
host.registerCoTHandler { event ->
    state.recordCoTEvent()
    false   // observe only, never consume
}
host.registerSettingsRow("Diagnostics", Icons.Filled.BugReport)
```

## Tests

`src/test/kotlin/soy/engindearing/diagnostics/DiagnosticsPluginTest.kt` exercises
the radial + CoT hooks against the real plugin (parity with `:app`'s
PluginHostSurfaceTest probe). Run with:

```
./gradlew :plugins:example-diagnostics:testDebugUnitTest
```
