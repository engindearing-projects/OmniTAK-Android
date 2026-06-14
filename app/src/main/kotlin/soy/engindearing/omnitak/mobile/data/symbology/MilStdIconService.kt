package soy.engindearing.omnitak.mobile.data.symbology

import android.content.Context
import android.util.Log
import kotlinx.serialization.json.Json

/**
 * Maps CoT types to MIL-STD-2525 SIDC codes and resolves the SVG
 * asset path for the matching symbol.
 *
 * Mirrors the iOS `MilStdIconService` so both platforms agree on
 * which SIDC any given CoT type renders as. The hardcoded floor here
 * is what tests assert against and what the app uses when no broader
 * catalogue has been loaded. The full ~108-entry catalogue lives in
 * `assets/cot_types.json` (Phase C) and is installed at app startup
 * via [loadFromAssets] — same file is mirrored verbatim into the iOS
 * bundle so neither platform drifts.
 *
 * ## Lookup rules
 * 1. Exact match on the full CoT type.
 * 2. Progressive truncation — strip the trailing `-segment`
 *    repeatedly until a known entry is found or we hit < 3 chars.
 *    Lets a specific type like `a-f-G-U-C-I-X` fall back to its
 *    parent `a-f-G-U-C-I`.
 * 3. Per-affiliation fallback SIDC. Always returns *something*; the
 *    map should never render a missing icon.
 */
object MilStdIconService {

    private const val TAG = "MilStdIconService"

    /** SVG asset directory under `app/src/main/assets/`. */
    private const val ASSETS_DIR = "milstd"

    /** Filename of the canonical catalogue under `app/src/main/assets/`. */
    private const val CATALOG_ASSET = "cot_types.json"

    private val fallbackSidc: Map<Affiliation, String> = mapOf(
        Affiliation.FRIENDLY to "SFGPU------",
        Affiliation.HOSTILE to "SHGPU------",
        Affiliation.NEUTRAL to "SNGPU------",
        Affiliation.UNKNOWN to "SUGPU------",
    )

    /**
     * Hardcoded floor — what tests assert against and what the app
     * uses when no broader catalogue has been loaded. Matches the
     * iOS hardcoded list verbatim so the contract holds even when
     * the JSON catalogue is missing or unreadable.
     */
    private val hardcodedFloor: List<CoTTypeDefinition> = listOf(
        // Friendly Ground Units
        CoTTypeDefinition("a-f-G-U", "SFGPU------.svg", "Friendly Ground - Generic", "Generic friendly marker", "friendly"),
        CoTTypeDefinition("a-f-G-U-C-I", "SFGPUCI----.svg", "Friendly Infantry", "Friendly ground infantry unit", "friendly"),
        CoTTypeDefinition("a-f-G-U-C-A", "SFGPUCA----.svg", "Friendly Armor", "Friendly ground armored unit", "friendly"),
        CoTTypeDefinition("a-f-G-U-C-S", "SFGPUCS----.svg", "Friendly Combat Support", "Friendly combat support unit", "friendly"),
        CoTTypeDefinition("a-f-G-U-U-L-C", "SFGPUULC----.svg", "Law Enforcement", "Police or law enforcement", "friendly"),
        CoTTypeDefinition("a-f-G-U-S-M", "SFGPUSM----.svg", "Medical", "Ambulance or medical vehicle", "friendly"),
        CoTTypeDefinition("a-f-G-E-V-U", "SFGPEVU----.svg", "Utility Vehicle", "Utility or service vehicle", "friendly"),
        CoTTypeDefinition("a-f-G-E-V-M", "SFGPEVM----.svg", "Civilian Vehicle", "Civilian motor vehicle", "friendly"),
        CoTTypeDefinition("a-f-G-E-V-F", "SFGPEVF----.svg", "Full Track Vehicle", "Full track armored vehicle", "friendly"),
        CoTTypeDefinition("a-f-G-E-V-L", "SFGPEVL----.svg", "Light Vehicle", "Light armored vehicle", "friendly"),
        CoTTypeDefinition("a-f-F-G-S", "SFFP-------.svg", "Special Forces", "Special operations forces", "friendly"),

        // Friendly Air Units
        CoTTypeDefinition("a-f-A", "SFAP-------.svg", "Friendly Air", "Friendly air unit", "friendly"),
        CoTTypeDefinition("a-f-A-M-F", "SFAP-------.svg", "Fixed Wing", "Friendly fixed-wing aircraft", "friendly"),
        CoTTypeDefinition("a-f-A-M-h", "SFAPMh------.svg", "Rotary Wing", "Friendly helicopter", "friendly"),
        CoTTypeDefinition("a-f-A-C-F", "SFAPACF----.svg", "Fighter Aircraft", "Fighter/interceptor aircraft", "friendly"),
        CoTTypeDefinition("a-f-A-C-R", "SFAPACR----.svg", "Reconnaissance Aircraft", "Reconnaissance aircraft", "friendly"),

        // Friendly Maritime
        CoTTypeDefinition("a-f-S", "SFSP-------.svg", "Friendly Maritime", "Friendly naval vessel", "friendly"),

        // Hostile Units
        CoTTypeDefinition("a-h-G-U", "SHGPU------.svg", "Hostile Ground - Generic", "Generic hostile marker", "hostile"),
        CoTTypeDefinition("a-h-G-U-C-I", "SHGPUCI----.svg", "Hostile Infantry", "Hostile infantry unit", "hostile"),
        CoTTypeDefinition("a-h-G-U-C-A", "SHGPUCA----.svg", "Hostile Armor", "Hostile armored vehicle", "hostile"),
        CoTTypeDefinition("a-h-G-U-C-C", "SHGPUCC----.svg", "Hostile Cavalry", "Hostile cavalry/recon unit", "hostile"),
        CoTTypeDefinition("a-h-G-E-V-C", "SHGPEVC----.svg", "Hostile Civil Vehicle", "Hostile civilian vehicle", "hostile"),

        // Neutral Units
        CoTTypeDefinition("a-n-G-U", "SNGPU------.svg", "Neutral Ground", "Neutral ground unit", "neutral"),
        CoTTypeDefinition("a-n-A-M-F", "SNAP-------.svg", "Neutral Air", "Neutral aircraft", "neutral"),
        CoTTypeDefinition("a-n-S", "SNSP-------.svg", "Neutral Maritime", "Neutral vessel", "neutral"),

        // Unknown Units
        CoTTypeDefinition("a-u-G-U", "SUGPU------.svg", "Unknown Ground", "Unknown ground unit", "unknown"),
        CoTTypeDefinition("a-u-G-U-C-I", "SUGPUCI----.svg", "Unknown Infantry", "Unknown infantry unit", "unknown"),
        CoTTypeDefinition("a-u-A", "SUA---------.svg", "Unknown Air", "Unknown aircraft", "unknown"),
        // Unknown UAS — the default targets for FAA Remote ID broadcasts.
        // Multirotor and fixed-wing UAS pre-loaded in the floor so RID
        // tracks render with the correct multirotor / fixed-wing glyph
        // even before the JSON catalogue loads, and the unit tests on the
        // CesiumEntityJson bridge are stable without Application.onCreate.
        CoTTypeDefinition("a-u-A-M-H-Q", "SUAPMHQ----.svg", "Unknown UAS Multirotor", "Multirotor UAS detected via FAA Remote ID", "unknown"),
        CoTTypeDefinition("a-u-A-M-F-Q", "SUAPMFQ----.svg", "Unknown UAS Fixed Wing", "Fixed-wing UAS detected via FAA Remote ID", "unknown"),
    )

    /**
     * Active catalogue. Starts at the hardcoded floor; [load] replaces
     * the contents at startup with the JSON-parsed catalogue. Reads
     * are lock-free; the [load] swap is a single volatile assignment.
     */
    @Volatile
    private var activeDefinitions: List<CoTTypeDefinition> = hardcodedFloor

    @Volatile
    private var cotTypeMap: Map<String, CoTTypeDefinition> =
        hardcodedFloor.associateBy { it.value }

    @Volatile
    private var sidcToCotMap: Map<String, CoTTypeDefinition> =
        hardcodedFloor.associateBy { it.sidcCode }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Replace the active catalogue. Call once at app startup with the
     * full Phase C list. Subsequent calls overwrite — tests can pass
     * an empty list to reset to the floor explicitly.
     */
    fun load(definitions: List<CoTTypeDefinition>) {
        // Layer over the hardcoded floor — anything in the JSON wins,
        // but a test or partial catalogue can't accidentally lose the
        // entries the rest of the app depends on.
        val merged = LinkedHashMap<String, CoTTypeDefinition>()
        hardcodedFloor.forEach { merged[it.value] = it }
        definitions.forEach { merged[it.value] = it }
        val finalList = merged.values.toList()
        activeDefinitions = finalList
        cotTypeMap = finalList.associateBy { it.value }
        sidcToCotMap = finalList.associateBy { it.sidcCode }
    }

    /**
     * Parse `assets/cot_types.json` and call [load]. Call once from
     * `Application.onCreate`. Silent on missing/unreadable asset —
     * the hardcoded floor stays active. Returns the number of
     * entries actually loaded so the caller can log.
     */
    fun loadFromAssets(context: Context): Int {
        return try {
            context.assets.open(CATALOG_ASSET).use { stream ->
                val text = stream.bufferedReader().readText()
                val parsed = json.decodeFromString<List<CoTTypeDefinition>>(text)
                load(parsed)
                Log.i(TAG, "loaded ${parsed.size} CoT type definitions from $CATALOG_ASSET")
                parsed.size
            }
        } catch (t: Throwable) {
            Log.w(TAG, "failed to load $CATALOG_ASSET; staying on hardcoded floor (${hardcodedFloor.size})", t)
            0
        }
    }

    /**
     * Resolve the SIDC code for a CoT type. Exact match → progressive
     * truncation → per-affiliation fallback. Never returns null.
     */
    fun getSidc(cotType: String): String {
        cotTypeMap[cotType]?.let { return it.sidcCode }

        var searchType = cotType
        while (searchType.length > 3) {
            cotTypeMap[searchType]?.let { return it.sidcCode }
            val lastHyphen = searchType.lastIndexOf('-')
            if (lastHyphen < 0) break
            searchType = searchType.substring(0, lastHyphen)
        }

        val affiliation = Affiliation.fromCotType(cotType)
        return fallbackSidc[affiliation] ?: "SUGPU------"
    }

    /** Asset-relative SVG filename for a CoT type (e.g. `SFGPUCI----.svg`). */
    fun getSvgFileName(cotType: String): String = "${getSidc(cotType)}.svg"

    /** Asset-relative path callers feed to `AssetManager.open(...)`. */
    fun getAssetPath(cotType: String): String = "$ASSETS_DIR/${getSvgFileName(cotType)}"

    /** Lookup by exact CoT type; null when the type isn't in the catalogue. */
    fun getDefinition(cotType: String): CoTTypeDefinition? = cotTypeMap[cotType]

    /** Lookup by SIDC code (without `.svg`); null when not found. */
    fun getDefinitionBySidc(sidcCode: String): CoTTypeDefinition? = sidcToCotMap[sidcCode]

    fun getAffiliation(cotType: String): Affiliation = Affiliation.fromCotType(cotType)

    /**
     * Re-affiliate a CoT type by swapping its affiliation segment (the
     * second token after `a-`) to [code] while keeping the battle
     * dimension and function tail intact — e.g. `a-h-G-U-C-A` with code
     * `f` → `a-f-G-U-C-A`. Lets the marker-edit affiliation chips retrofit
     * an already-picked specific symbol onto a new side without forcing the
     * operator back into the icon picker.
     *
     * Falls back to the generic per-affiliation ground unit when [cotType]
     * is too short to carry an affiliation segment, so callers always get
     * a renderable type back.
     */
    fun withAffiliation(cotType: String, code: Char): String {
        val parts = cotType.split('-').toMutableList()
        if (parts.size < 2 || !parts[0].equals("a", ignoreCase = true)) {
            return "a-$code-G-U-C"
        }
        parts[1] = code.toString()
        return parts.joinToString("-")
    }

    fun getBattleDimension(cotType: String): BattleDimension =
        BattleDimension.fromCotType(cotType)

    /** Every definition in the active catalogue. */
    fun getAllDefinitions(): List<CoTTypeDefinition> = activeDefinitions

    /** Definitions filtered by affiliation, in catalogue order. */
    fun getDefinitions(forAffiliation: Affiliation): List<CoTTypeDefinition> =
        activeDefinitions.filter { it.affiliation == forAffiliation }
}
