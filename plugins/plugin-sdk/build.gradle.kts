plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "soy.engindearing.omnitak.plugin"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true }

    // JVM unit tests (PluginRegistry default-true semantics) call into
    // android.* stubs — return defaults instead of "Method not mocked".
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// NOTE: no repositories {} block here. settings.gradle.kts sets
// repositoriesMode = FAIL_ON_PROJECT_REPOS; every dependency below resolves
// from the central google()/mavenCentral() declared there. Adding a module
// repositories block would fail the build.
dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    // The SDK is intentionally dependency-light. It needs Compose (overlay /
    // settings content are @Composable) + Material3 (ImageVector for radial /
    // settings-row icons). It deliberately does NOT depend on :app or on
    // MapLibre — the map handle crosses the SDK boundary as Any? via
    // LocalMapEngineHandle so the SDK stays engine-agnostic and there is no
    // circular dependency with the host.
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
