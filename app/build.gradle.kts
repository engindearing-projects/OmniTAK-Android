import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    kotlin("plugin.serialization") version "2.0.21"
}

android {
    namespace = "soy.engindearing.omnitak.mobile"
    compileSdk = 35
    // Pin to the locally-installed NDK so AGP can find llvm-objcopy when it
    // needs to (re)strip overlaid prebuilt libs from app/src/main/jniLibs/
    // and extract their debug info into BUNDLE-METADATA. Without this AGP
    // logs "Unable to strip the following libraries" and ships the
    // unstripped binaries — a ~750 MB AAB instead of ~38 MB.
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "soy.engindearing.omnitak.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 42
        versionName = "0.5.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
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

    buildFeatures { compose = true }

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
                // BouncyCastle 1.78 ships duplicate OSGi manifests across
                // bcprov / bcpkix / bcutil — exclude them so mergeJavaRes
                // doesn't choke. None of these are runtime-loaded.
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
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

    // MapLibre Android (open-source fork of Mapbox) — parallel to iOS
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    // NGA's tested MGRS / UTM converter. Operators rely on grid coords
    // for navigation; rolling our own would risk silent miscalculation.
    // Pure-Java, ~250 KB, no Android resources.
    implementation("mil.nga.mgrs:mgrs-android:2.2.3")

    // Nordic Semiconductor BLE library — used by MeshtasticBleClient.
    // Wraps the platform GATT API in a queueable, callback-driven manager
    // with built-in MTU negotiation, bond handling, and reconnection.
    implementation("no.nordicsemi.android:ble:2.8.0")
    implementation("no.nordicsemi.android:ble-ktx:2.8.0")

    // GAP-030b — FusedLocationProviderClient for real GPS fixes.
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // GAP-081 — Bouncy Castle for CSR (PKCS#10) generation and PKCS#12
    // keystore assembly during TAK Server / OpenTAKserver CSR enrollment.
    // Android ships an older BC that's hidden; pin our own.
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")

    // == S1:offline-tiles BEGIN ==
    // OkHttp powers the offline tile downloader (TileDownloader) and the
    // read-through interceptor we hand to MapLibre via HttpRequestUtil
    // so map tiles inside a downloaded region are served from disk even
    // when the operator drops off cellular mid-search.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // == S1:offline-tiles END ==

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // Local JVM unit tests — protobuf parser, CoT converter, etc.
    testImplementation("junit:junit:4.13.2")
    // runTest / StandardTestDispatcher for coroutine-driven tests
    // (e.g. MeshtasticCoTBridge enabled toggle).
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
}
