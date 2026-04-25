# OmniTAK-Android

Open-source TAK (Team Awareness Kit) client for Android, built with Kotlin + Jetpack Compose.

OmniTAK speaks Cursor-on-Target (CoT) over TLS to any TAK Server, supports tactical map rendering via MapLibre, ADS-B traffic display, Meshtastic radios, drawing tools, and more — designed for search-and-rescue, civil defense, and outdoor operations.

> **Bring your own TAK Server.** OmniTAK is a client. Stand up [TAK Server](https://tak.gov) (community CIV edition) or [FreeTAKServer](https://github.com/FreeTAKTeam/FreeTakServer) and point OmniTAK at it.

## Features

- **TAK Server connectivity** — TCP / TLS / mTLS with client-certificate enrollment
- **Cursor-on-Target** — full CoT XML parser, marker rendering, server messaging
- **Tactical map** — MapLibre Native Android with custom layers (contacts, drawing, aircraft, mesh nodes, grid, measurement)
- **ADS-B traffic** — aircraft overlay with bring-your-own provider
- **Meshtastic** — TCP connection to Meshtastic mesh radios
- **Drawing tools** — points, lines, polygons, range/bearing, measurement
- **Multi-server management** — connect to multiple TAK servers
- **Radial menu** — quick actions on map long-press
- **Material 3 dark theme** — tactical color palette

## Requirements

- Android 8.0 (API 26) or later
- Android Studio Ladybug or later (for development)
- JDK 17
- A TAK Server you can reach (BYO — see above)

## Getting started

```bash
git clone https://github.com/engindearing-projects/OmniTAK-Android.git
cd OmniTAK-Android
./gradlew assembleDebug
```

The debug APK lands at `app/build/outputs/apk/debug/app-debug.apk`.

To install on a connected device:

```bash
./gradlew installDebug
```

Or open the project in Android Studio and run normally — debug builds work out-of-the-box without any signing key configuration.

### Release builds (your own signing key)

To produce a release APK signed with your own upload key:

1. Generate an upload keystore:
   ```bash
   keytool -genkey -v -keystore my-upload.jks -keyalg RSA -keysize 2048 -validity 10000 -alias upload
   ```
2. Copy `keystore.properties.example` to `keystore.properties` (gitignored) and fill in your values
3. Build:
   ```bash
   ./gradlew assembleRelease
   ```

If `keystore.properties` is absent, release builds gracefully fall back to the debug signing config so the project always builds.

## Architecture

```
app/src/main/kotlin/soy/engindearing/omnitak/mobile/
├── data/            # Models + persistence (TAKServer, CoTEvent, ChatMessage, …)
├── domain/          # State stores (ServerManager, ChatStore, ContactStore, …)
└── ui/
    ├── screens/     # Top-level screens (Map, Servers, Chat, Meshtastic, Settings, …)
    ├── components/  # Reusable layers and widgets (TacticalMap, RadialMenu, …)
    ├── navigation/  # Compose Navigation graph
    └── theme/       # Material 3 theme + tactical colors
```

The app is pure Kotlin + Compose with no native bridge. A future release will integrate the shared OmniTAK Rust core via JNI — its source is being prepared for separate open-source release as **OmniTAK-Core**.

## Permissions

| Permission | Why |
|------------|-----|
| `INTERNET` | TAK Server connectivity |
| `ACCESS_NETWORK_STATE` | Detect connectivity changes |
| `ACCESS_FINE_LOCATION` | Self-location reporting (PPLI), GPS-aware tools |
| `ACCESS_COARSE_LOCATION` | Fallback for users who deny precise location |

No tracking, no analytics, no third-party SDKs.

## Security & privacy

- All TAK Server connections are TLS 1.2+ by default
- Client certificates are stored in Android Keystore
- No outbound traffic except to user-configured TAK Servers and ADS-B providers

Found a vulnerability? See [SECURITY.md](SECURITY.md) for responsible disclosure.

## Contributing

Contributions welcome. See [CONTRIBUTING.md](CONTRIBUTING.md). For larger changes, please open an issue first.

## License

Apache License 2.0. See [LICENSE](LICENSE).

OmniTAK-Android uses the following open-source components:

- [MapLibre Native Android](https://github.com/maplibre/maplibre-native) — BSD 2-Clause
- [AndroidX](https://developer.android.com/jetpack/androidx) — Apache 2.0
- [kotlinx.serialization](https://github.com/Kotlin/kotlinx.serialization) — Apache 2.0
- [Jetpack Compose](https://developer.android.com/jetpack/compose) — Apache 2.0

## Acknowledgments

Built by [Engindearing](https://engindearing.soy). Inspired by [ATAK](https://github.com/deptofdefense/AndroidTacticalAssaultKit-CIV), iTAK, [FreeTAKServer](https://github.com/FreeTAKTeam/FreeTakServer), and the broader TAK community.

The companion iOS client is [OmniTAK-iOS](https://github.com/engindearing-projects/OmniTAK-iOS).

OmniTAK is not affiliated with or endorsed by the U.S. Department of Defense, the TAK Product Center, or any other organization.
