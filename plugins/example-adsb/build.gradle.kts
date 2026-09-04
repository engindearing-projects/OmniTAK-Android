plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "soy.engindearing.adsb"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

    buildFeatures { compose = true }

    // JVM unit tests (AdsbRecenterTest, OpenSky parser) call android.util.Log.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// No repositories {} block — see plugin-sdk/build.gradle.kts. Deps resolve
// from the central google()/mavenCentral() in settings.gradle.kts.
dependencies {
    // The plugin contract. A plugin depends ONLY on the SDK + whatever it
    // needs to do its job — here, MapLibre to render aircraft. It does NOT
    // depend on :app, so the dependency graph stays acyclic.
    implementation(project(":plugins:plugin-sdk"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Icons.Filled.Flight for the radial/settings icon.
    implementation("androidx.compose.material:material-icons-extended")

    // MapLibre — AircraftLayer feeds the GeoJSON source baked into the host's
    // tactical style, the exact GL path chosen for Adreno 610 / SwiftShader.
    // The plugin casts the host-provided LocalMapEngineHandle to MapLibreMap.
    implementation("org.maplibre.gl:android-sdk:11.8.0")

    // org.json is provided by the Android platform at runtime; needed only as
    // a real impl for JVM tests (the android.jar stub returns nulls).
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
