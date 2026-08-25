# R8 keep rules for OmniTAK Android.
#
# Keep policy is conservative: minify only what's clearly safe, leave
# anything reflection-touched alone. If you remove a -keep from this
# file, smoke-test a release APK against the closed-test build before
# uploading — silent NoSuchMethodError at runtime is the failure mode.

# --- kotlinx.serialization ----------------------------------------------
# Generated $serializer companions are looked up reflectively. Keep them
# along with the @Serializable data class itself.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.AnnotationsKt

-keepclassmembers @kotlinx.serialization.Serializable class * {
    static <1>$Companion Companion;
    public static <1>$$serializer INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclasseswithmembers class **$$serializer { *; }

# Our @Serializable model classes live under .data — keep their fields
# so DataStore JSON round-trips don't lose properties.
-keep class soy.engindearing.omnitak.mobile.data.** { *; }

# --- MapLibre -----------------------------------------------------------
# MapLibre ships consumer rules but its native bridge methods are looked
# up by name. Belt-and-suspenders keep on the public package.
-keep class org.maplibre.android.** { *; }
-keep class org.maplibre.geojson.** { *; }

# --- Compose ------------------------------------------------------------
# AGP applies Compose's consumer rules automatically. No app-side rules
# needed beyond the defaults in proguard-android-optimize.txt.

# --- JNI / native -------------------------------------------------------
# No app-owned JNI today. Library natives (MapLibre, datastore-shared-
# counter, androidx.graphics.path) are covered by their consumer rules.
-keepclasseswithmembernames class * {
    native <methods>;
}

# --- MAVLink (io.dronefleet.mavlink) -----------------------------------
# The MAVLink Java codec uses reflection-heavy message dispatch — every
# message class has builder + accessor methods looked up by name. Stripping
# any of them breaks parse/serialize at runtime, and R8 can't see the
# reflective edges. Keep the whole codec.
-keep class io.dronefleet.mavlink.** { *; }
-keep enum io.dronefleet.mavlink.** { *; }
-keepattributes Signature, *Annotation*, InnerClasses, EnclosingMethod
-dontwarn io.dronefleet.mavlink.**

# --- BouncyCastle ------------------------------------------------------
# BC's provider self-registers classes by name via reflection.
-keep class org.bouncycastle.** { *; }
-dontwarn org.bouncycastle.**

# --- Play services location: false-positive R8 warning about a removed
# Companion in com.google.android.gms.internal.location.zze — ignore.
-dontwarn com.google.android.gms.internal.location.**

# --- WebView JS bridge --------------------------------------------------
# The Cesium 3D globe (CesiumMapView) exposes onReady/onMapEvent to the
# scene's JavaScript via @JavascriptInterface. R8 would otherwise strip or
# rename these (they're never called from Kotlin), breaking the bridge in
# release builds — entities wouldn't push and taps wouldn't route.
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# --- Misc ---------------------------------------------------------------
# Suppress notes about packages R8 sees but doesn't act on; clean output.
-dontwarn org.jetbrains.annotations.**
-dontwarn javax.annotation.**

# --- Tink crypto: its optional remote KeysDownloader references the Google
# HTTP client and Joda-Time, which we don't bundle (no network key fetch).
# R8 full mode errors on the missing classes; suppress (not used at runtime).
-dontwarn com.google.api.client.**
-dontwarn org.joda.time.**

# --- Ditto peer mesh -----------------------------------------------------
# The Ditto SDK resolves native (JNI) callbacks by reflection into
# com.ditto.** — R8 renaming breaks them only at runtime, in release builds,
# on device. Rules per Ditto's install guide; the rustls keep preserves TLS
# certificate verification in minified builds.
-keep class com.ditto.** { *; }
-keep, includedescriptorclasses class org.rustls.platformverifier.** { *; }
