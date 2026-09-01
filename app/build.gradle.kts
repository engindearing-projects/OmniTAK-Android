import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.0.21"
}

android {
    namespace = "soy.engindearing.omnitak.mobile"
    compileSdk = 36
    // Pin to the locally-installed NDK so AGP can find llvm-objcopy when it
    // needs to (re)strip overlaid prebuilt libs from app/src/main/jniLibs/
    // and extract their debug info into BUNDLE-METADATA. Without this AGP
    // logs "Unable to strip the following libraries" and ships the
    // unstripped binaries — a ~750 MB AAB instead of ~38 MB.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "soy.engindearing.omnitak.mobile"
        minSdk = 26
        targetSdk = 36
        versionCode = 107
        versionName = "0.42.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Cesium Ion token lives in local.properties (gitignored) — NEVER in
        // a committed asset; this is a public repo. CesiumMapView injects it
        // into cesium_scene.html at WebView load time. The empty default
        // keeps clean checkouts / CI green (the 3D globe runs tokenless with
        // degraded Ion imagery instead of failing the build).
        val localProps = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { load(it) }
        }
        buildConfigField(
            "String",
            "CESIUM_ION_TOKEN",
            "\"${localProps.getProperty("CESIUM_ION_TOKEN") ?: ""}\"",
        )
    }

    // Upload keystore lives outside source control. Set the four props in
    // ~/.gradle/gradle.properties (user-global) OR keystore.properties next
    // to this file (gitignored). Release build falls back to the debug
    // signing config if no real keystore is configured, so local builds
    // keep working without secrets on disk.
    val keystoreProps = Properties().apply {
        val f = rootProject.file("keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }

    signingConfigs {
        create("release") {
            val ksPath = keystoreProps.getProperty("storeFile")
                ?: providers.gradleProperty("OMNITAK_KEYSTORE").orNull
            if (ksPath != null) {
                storeFile = rootProject.file(ksPath)
                storePassword = keystoreProps.getProperty("storePassword")
                    ?: providers.gradleProperty("OMNITAK_KEYSTORE_PW").orNull
                keyAlias = keystoreProps.getProperty("keyAlias")
                    ?: providers.gradleProperty("OMNITAK_KEY_ALIAS").orNull
                keyPassword = keystoreProps.getProperty("keyPassword")
                    ?: providers.gradleProperty("OMNITAK_KEY_PW").orNull
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = true
            isShrinkResources = true
            // mgrs-android ships template consumer rules containing
            // -dontobfuscate plus a global `-keep public class *` — one
            // library's file silently turning off renaming (and most
            // shrinking) for the ENTIRE app; Play Console flags the result
            // as "Obfuscation 0%". Ignore that library's rules — it's
            // pure-Java math we only call directly, so R8's call tracing
            // keeps what's used without any of its keeps.
            optimization {
                keepRules {
                    ignoreFrom("mil.nga.mgrs:mgrs-android")
                }
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Bundles unstripped .so files into the AAB so Play Console can
            // symbolicate native crashes without a separate symbols upload.
            ndk { debugSymbolLevel = "FULL" }
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // Pull in the existing icon/color/theme resources that already live under
    // app_assets/android/ so we don't duplicate them.
    sourceSets {
        getByName("main").res.srcDirs("src/main/res", "../app_assets/android")
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "/META-INF/{AL2.0,LGPL2.1}",
                // BouncyCastle ships an OSGi MANIFEST in three jars
                // (bcprov, bcpkix, bcutil) — pick first wins.
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            )
        }
    }

    // Let JVM unit tests call android.util.Log without "Method not mocked"
    // RuntimeExceptions. Default values mean Log.* returns 0 / "" / false.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    // Plugin SDK + bundled plugins. The SDK carries the OmniTAKPlugin contract
    // and the registry; example-adsb is the ADS-B reference plugin extracted
    // out of :app; example-diagnostics is a second reference plugin (radial +
    // CoT hooks, OFF by default) for parity with iOS's DiagnosticsPlugin.
    // Compile-time modules only — no dynamic/remote code, so the app stays
    // Play-Store compliant.
    implementation(project(":plugins:plugin-sdk"))
    implementation(project(":plugins:example-adsb"))
    implementation(project(":plugins:example-diagnostics"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // EncryptedSharedPreferences for TAK server passwords + .p12 passphrases
    // (Keystore-backed AES256-GCM). The server-list JSON in DataStore keeps
    // only non-secret fields; see SecureCredentialStore.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // MapLibre Android (open-source fork of Mapbox) — parallel to iOS
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    // OkHttp — used ONLY to install a custom HTTP client into MapLibre via
    // HttpRequestUtil.setOkHttpClient(), so we can send an identifying
    // User-Agent on tile requests. OpenStreetMap (and OpenTopoMap) serve a
    // "403 Access blocked" placeholder tile to clients with a generic UA;
    // an app-identifying UA is required by their tile usage policy (#139).
    // MapLibre 11.x already bundles OkHttp 4.x; we pin the same major so the
    // resolved version stays compatible.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // NGA's tested MGRS / UTM converter. Operators rely on grid coords
    // for navigation; rolling our own would risk silent miscalculation.
    // Pure-Java, ~250 KB, no Android resources.
    implementation("mil.nga.mgrs:mgrs-android:2.2.3")

    // NGA's pure-Java TIFF reader — decodes GeoTIFF rasters (uncompressed,
    // LZW, Deflate, PackBits) that Android's BitmapFactory can't. We parse the
    // GeoTIFF geo-tags ourselves (GeoTIFFParser) and use this only for the
    // pixel raster → Bitmap. Apache-2.0, ~150 KB, no Android resources.
    implementation("mil.nga:tiff:3.0.0")

    // Nordic Semiconductor BLE library — used by MeshtasticBleClient.
    // Wraps the platform GATT API in a queueable, callback-driven manager
    // with built-in MTU negotiation, bond handling, and reconnection.
    implementation("no.nordicsemi.android:ble:2.8.0")
    implementation("no.nordicsemi.android:ble-ktx:2.8.0")

    // GAP-030b — FusedLocationProviderClient for real GPS fixes.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // AndroidSVG — renders MIL-STD-2525 symbols (assets/milstd/*.svg) to
    // Bitmaps for the MapLibre Marker Icon pipeline. Apache-2.0, stable.
    // baseProfile="tiny" SVGs convert in under 2 ms each on a Pixel 6.
    implementation("com.caverock:androidsvg-aar:1.4")

    // BouncyCastle — PKCS#10 CSR construction for TAK Server Quick Connect
    // enrollment (GAP-081). Android's bundled BC provider is stripped and
    // old, so we ship the full provider + PKIX. Used by CSRGenerator only.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // ZXing core — QR code generation for the config-profile QR sync feature.
    // Pure Java, Apache-2.0, ~300 KB, no Android resources. Used only for
    // encoding (ProfileQrGenerator); MLKit barcode-scanning handles decode
    // so we don't pull in the full ZXing Android Embedded UI stack.
    implementation("com.google.zxing:core:3.5.3")

    // MLKit barcode scanning — QR decode in the in-app CameraX scanner.
    // Runs on-device (no network), bundled model ~900 KB. Apache-2.0.
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // CameraX — camera preview + frame analysis for the QR scanner UI.
    // Consistent on API 26+ (our minSdk), replaces the fragmented Camera2 API.
    val cameraxVersion = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // MAVLink Java codec for UAS Quick Connect drone control. MIT-licensed,
    // pure Java, MAVLink 2 support per the RAS-A IOP v1.2 (the DoD
    // interoperability standard the official ATAK UAS Tool 13.0 implements).
    // We use this for parsing/serializing MAVLink frames; UDP transport is
    // our own (datagram socket on port 14550).
    implementation("io.dronefleet.mavlink:mavlink:1.1.11")

    // AndroidX Media3 / ExoPlayer with the RTSP source — drone-video PIP.
    // Pure Java, Apache-2.0, RTSP (RFC 7826) + RTP support live in the
    // exoplayer-rtsp module. ~3 MB to the APK after R8 shrink.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-rtsp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Local JVM unit tests — protobuf parser, CoT converter, etc.
    testImplementation("junit:junit:4.13.2")
    // runTest / StandardTestDispatcher for coroutine-driven tests
    // (e.g. MeshtasticCoTBridge enabled toggle).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Real org.json impl so JVM tests can exercise JSONObject/JSONArray
    // (e.g. CesiumEntityJson serialization). Android stubs them otherwise.
    testImplementation("org.json:json:20240303")
    // Real XmlPullParser impl (same trick as org.json above) so JVM tests
    // exercise the production CoTParser/ChatXml path — the mockable
    // android.jar makes XmlPullParserFactory.newInstance() return null.
    testImplementation("net.sf.kxml:kxml2:2.3.0")

    // Instrumented tests — symbology validation runs on a real device or
    // emulator because MapLibre's SurfaceView renders through native GL
    // and can't be captured by JVM/Compose unit tests.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
}
