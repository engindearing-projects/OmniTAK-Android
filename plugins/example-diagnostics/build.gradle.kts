plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "soy.engindearing.diagnostics"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // JVM unit tests (DiagnosticsPlugin counter/coordinate) call android.util.Log.
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// No repositories {} block — see plugin-sdk/build.gradle.kts. Deps resolve
// from the central google()/mavenCentral() in settings.gradle.kts.
dependencies {
    // The plugin contract. The Diagnostics plugin depends ONLY on the SDK +
    // Compose — it exercises the registerRadialAction + registerCoTHandler hooks
    // and needs no map engine, so (unlike example-adsb) there is no MapLibre dep.
    implementation(project(":plugins:plugin-sdk"))

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    // Icons.Filled.BugReport for the radial/settings icon.
    implementation("androidx.compose.material:material-icons-extended")

    testImplementation("junit:junit:4.13.2")
}
