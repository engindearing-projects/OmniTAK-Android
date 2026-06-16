package soy.engindearing.omnitak.mobile.data

import android.content.Context
import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.BufferedWriter
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Robust large-KML overlay support for Android — the port of the iOS
 * KMLVectorOverlay path. Streams a KML/KMZ into one on-disk GeoJSON and
 * renders it as a single MapLibre GeoJsonSource + vector layers, so a
 * ~50,000-feature statewide trails file imports and toggles smoothly where
 * a per-feature annotation approach would crash.
 */

@Serializable
data class KmlVectorOverlay(
    val id: String,
    val name: String,
    /** File name (relative to the overlays dir) of the GeoJSON. */
    val fileName: String,
    val colorHex: String,
    val visible: Boolean,
    val featureCount: Int,
    val minLat: Double,
    val minLon: Double,
    val maxLat: Double,
    val maxLon: Double,
    /** Stroke/fill opacity (0..1). Defaulted so older overlays.json still loads. */
    val opacity: Float = 0.9f,
    /** Base line-width multiplier. */
    val lineWidth: Float = 1.4f,
    val createdAt: Long = 0L,
)

/** Streaming KML → GeoJSON converter. Pure; safe to run off the main thread. */
object KmlGeoJsonConverter {
    data class Result(
        val featureCount: Int,
        val minLat: Double,
        val minLon: Double,
        val maxLat: Double,
        val maxLon: Double,
    )

    class EmptyKmlException : Exception("No map features found in that file.")

    fun convert(input: InputStream, out: File): Result =
        convert(input, out, Xml.newPullParser())

    /**
     * Overload that accepts a pre-built [XmlPullParser] — used by JVM unit
     * tests to inject a [XmlPullParserFactory]-backed parser instead of the
     * Android-only [android.util.Xml.newPullParser].
     */
    internal fun convert(input: InputStream, out: File, parser: XmlPullParser): Result {
        val writer = out.bufferedWriter()
        writer.use { w ->
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
            parser.setInput(input, null)

            var count = 0
            var minLat = 90.0; var minLon = 180.0; var maxLat = -90.0; var maxLon = -180.0

            var inPlacemark = false
            var placemarkName = "Placemark"
            var inLineString = false
            var inPoint = false
            var inPolygon = false
            var inOuter = false
            var inInner = false
            var inCoords = false
            var inName = false
            val text = StringBuilder()
            var outerRing: String? = null
            val innerRings = ArrayList<String>()

            fun track(lat: Double, lon: Double) {
                if (lat < minLat) minLat = lat; if (lat > maxLat) maxLat = lat
                if (lon < minLon) minLon = lon; if (lon > maxLon) maxLon = lon
            }

            // Real-world KML carries junk coords — (0,0) "Null Island" from
            // missing values, or out-of-range/NaN. A single 0,0 blows the
            // bounding box up to half the planet, so "zoom to overlay" frames
            // the whole globe. Drop them.
            fun isValid(lat: Double, lon: Double): Boolean =
                lat.isFinite() && lon.isFinite() &&
                    kotlin.math.abs(lat) <= 90 && kotlin.math.abs(lon) <= 180 &&
                    !(kotlin.math.abs(lat) < 0.0001 && kotlin.math.abs(lon) < 0.0001)

            // Returns the [lon,lat] JSON array string for a KML coord tuple
            // list, tracking bounds. KML tuples are "lon,lat[,alt]" separated
            // by whitespace.
            fun coordsJson(raw: String, minPts: Int): String? {
                val sb = StringBuilder("[")
                var n = 0
                for (tuple in raw.trim().split(Regex("\\s+"))) {
                    if (tuple.isEmpty()) continue
                    val parts = tuple.split(",")
                    if (parts.size < 2) continue
                    val lon = parts[0].toDoubleOrNull() ?: continue
                    val lat = parts[1].toDoubleOrNull() ?: continue
                    if (!isValid(lat, lon)) continue
                    if (n > 0) sb.append(",")
                    sb.append("[").append(fmt(lon)).append(",").append(fmt(lat)).append("]")
                    track(lat, lon)
                    n++
                }
                sb.append("]")
                return if (n >= minPts) sb.toString() else null
            }

            fun featureHeader() {
                if (count > 0) w.append(",")
                w.append("{\"type\":\"Feature\",\"properties\":{\"name\":")
                    .append(jsonString(placemarkName)).append("},\"geometry\":")
            }

            w.append("{\"type\":\"FeatureCollection\",\"features\":[")

            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "Placemark" -> { inPlacemark = true; placemarkName = "Placemark" }
                        "LineString" -> inLineString = true
                        "Point" -> inPoint = true
                        "Polygon" -> { inPolygon = true; outerRing = null; innerRings.clear() }
                        "outerBoundaryIs" -> inOuter = true
                        "innerBoundaryIs" -> inInner = true
                        "coordinates" -> { inCoords = true; text.setLength(0) }
                        "name" -> {
                            if (inPlacemark && !inLineString && !inPoint && !inPolygon) {
                                inName = true; text.setLength(0)
                            }
                        }
                    }
                    XmlPullParser.TEXT -> if (inCoords || inName) text.append(parser.text)
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "coordinates" -> {
                            inCoords = false
                            val raw = text.toString()
                            when {
                                inLineString -> coordsJson(raw, 2)?.let {
                                    featureHeader(); w.append("{\"type\":\"LineString\",\"coordinates\":").append(it).append("}}"); count++
                                }
                                inPoint -> {
                                    val one = raw.trim().split(Regex("\\s+")).firstOrNull()?.split(",")
                                    if (one != null && one.size >= 2) {
                                        val lon = one[0].toDoubleOrNull(); val lat = one[1].toDoubleOrNull()
                                        if (lon != null && lat != null && isValid(lat, lon)) {
                                            featureHeader(); w.append("{\"type\":\"Point\",\"coordinates\":[").append(fmt(lon)).append(",").append(fmt(lat)).append("]}}")
                                            track(lat, lon); count++
                                        }
                                    }
                                }
                                inPolygon && inOuter -> outerRing = coordsJson(raw, 3)
                                inPolygon && inInner -> coordsJson(raw, 3)?.let { innerRings.add(it) }
                            }
                        }
                        "name" -> if (inName) { placemarkName = text.toString().trim().ifEmpty { "Placemark" }; inName = false }
                        "LineString" -> inLineString = false
                        "Point" -> inPoint = false
                        "outerBoundaryIs" -> inOuter = false
                        "innerBoundaryIs" -> inInner = false
                        "Polygon" -> {
                            val ring = outerRing
                            if (ring != null) {
                                featureHeader(); w.append("{\"type\":\"Polygon\",\"coordinates\":[").append(ring)
                                for (inner in innerRings) w.append(",").append(inner)
                                w.append("]}}"); count++
                            }
                            inPolygon = false
                        }
                        "Placemark" -> inPlacemark = false
                    }
                }
                event = parser.next()
            }

            w.append("]}")
            if (count == 0) throw EmptyKmlException()
            return Result(count, minLat, minLon, maxLat, maxLon)
        }
    }

    private fun fmt(d: Double): String = String.format("%.6f", d)

    private fun jsonString(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) when (c) {
            '"' -> sb.append("\\\"")
            '\\' -> sb.append("\\\\")
            '\n', '\r', '\t' -> sb.append(' ')
            else -> if (c.code < 0x20) sb.append(' ') else sb.append(c)
        }
        sb.append("\"")
        return sb.toString()
    }
}

class KmlVectorOverlayStore(context: Context) {
    private val dir = File(context.filesDir, "kml_overlays").apply { mkdirs() }
    private val metaFile = File(dir, "overlays.json")
    private val json = Json { ignoreUnknownKeys = true }

    private val _overlays = MutableStateFlow(load())
    val overlays: StateFlow<List<KmlVectorOverlay>> = _overlays.asStateFlow()

    private val _isImporting = MutableStateFlow(false)
    val isImporting: StateFlow<Boolean> = _isImporting.asStateFlow()

    private val _status = MutableStateFlow("")
    val status: StateFlow<String> = _status.asStateFlow()

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()

    fun fileFor(overlay: KmlVectorOverlay): File = File(dir, overlay.fileName)

    /** Import a .kml/.kmz file: parse + stream to GeoJSON off the main thread. */
    suspend fun importKml(source: File, displayName: String) {
        _isImporting.value = true
        _lastError.value = null
        _status.value = "Parsing…"
        val id = UUID.randomUUID().toString()
        val outFile = File(dir, "$id.geojson")
        try {
            val result = withContext(Dispatchers.IO) {
                openKmlStream(source, displayName).use { stream ->
                    KmlGeoJsonConverter.convert(stream, outFile)
                }
            }
            val overlay = KmlVectorOverlay(
                id = id,
                name = displayName.substringBeforeLast("."),
                fileName = outFile.name,
                colorHex = PALETTE[_overlays.value.size % PALETTE.size],
                visible = true,
                featureCount = result.featureCount,
                minLat = result.minLat, minLon = result.minLon,
                maxLat = result.maxLat, maxLon = result.maxLon,
                createdAt = System.currentTimeMillis(),
            )
            _overlays.value = _overlays.value + overlay
            persist()
            _status.value = "Imported ${result.featureCount} features"
        } catch (e: KmlGeoJsonConverter.EmptyKmlException) {
            outFile.delete()
            _lastError.value = e.message
            _status.value = ""
        } catch (e: Exception) {
            outFile.delete()
            _lastError.value = "Import failed: ${e.message}"
            _status.value = ""
        } finally {
            _isImporting.value = false
        }
    }

    private fun openKmlStream(source: File, displayName: String): InputStream {
        if (displayName.lowercase().endsWith(".kmz")) {
            val zis = ZipInputStream(source.inputStream())
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && entry.name.lowercase().endsWith(".kml")) return zis
                entry = zis.nextEntry
            }
            zis.close()
            throw KmlGeoJsonConverter.EmptyKmlException()
        }
        return source.inputStream()
    }

    fun setVisible(id: String, visible: Boolean) {
        update(id) { it.copy(visible = visible) }
    }

    fun rename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        update(id) { it.copy(name = trimmed) }
    }

    fun setColor(id: String, colorHex: String) {
        update(id) { it.copy(colorHex = colorHex) }
    }

    fun setOpacity(id: String, value: Float) {
        update(id) { it.copy(opacity = value.coerceIn(0.05f, 1.0f)) }
    }

    fun setLineWidth(id: String, value: Float) {
        update(id) { it.copy(lineWidth = value.coerceIn(0.5f, 6.0f)) }
    }

    private fun update(id: String, transform: (KmlVectorOverlay) -> KmlVectorOverlay) {
        _overlays.value = _overlays.value.map { if (it.id == id) transform(it) else it }
        persist()
    }

    fun remove(id: String) {
        _overlays.value.firstOrNull { it.id == id }?.let { fileFor(it).delete() }
        _overlays.value = _overlays.value.filterNot { it.id == id }
        persist()
    }

    fun removeAll() {
        _overlays.value.forEach { fileFor(it).delete() }
        _overlays.value = emptyList()
        persist()
    }

    private fun persist() {
        runCatching { metaFile.writeText(json.encodeToString(_overlays.value)) }
    }

    private fun load(): List<KmlVectorOverlay> {
        if (!metaFile.exists()) return emptyList()
        return runCatching {
            json.decodeFromString<List<KmlVectorOverlay>>(metaFile.readText())
                .filter { File(dir, it.fileName).exists() }
        }.getOrDefault(emptyList())
    }

    companion object {
        private val PALETTE = listOf("#A78BFA", "#5AC8FA", "#34C759", "#FF9F0A", "#FF375F", "#FFD60A")
    }
}
