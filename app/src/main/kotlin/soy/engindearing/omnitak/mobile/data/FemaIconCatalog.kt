package soy.engindearing.omnitak.mobile.data

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.Groups
import androidx.compose.material.icons.filled.House
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.LocalFireDepartment
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.MedicalServices
import androidx.compose.material.icons.filled.Restaurant
import androidx.compose.material.icons.filled.Vaccines
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector

/**
 * FEMA / Incident Command symbology catalog for the SAR / disaster
 * response marker palette (closes #29).
 *
 * Why this exists
 * ---------------
 * K9Blue SAR feedback (5/10/26 via TAK Discord): pre-planned + hasty
 * searches need IC, medical, LZ, vehicle, and support-station markers
 * that MIL-STD-2525 can't express in civil-response vocabulary.
 *
 * CoT type convention
 * -------------------
 * FEMA / ICS markers don't have canonical MIL-STD-2525 unit types, so
 * we emit them as friendly ground installations (`a-f-G-I-*`) and rely
 * on the `<usericon iconsetpath="COT_MAPPING_FEMA/...">` detail
 * extension to carry the FEMA glyph. Receivers without the FEMA set
 * still get a plausible friendly-installation fallback rendered by
 * ATAK/iTAK.
 *
 * Mirrors OmniTAK-iOS `FEMAIconCatalog.swift` byte-for-byte on the
 * Kind raw values + cotType strings so a marker dropped on one platform
 * round-trips to the other through CoT.
 *
 * References:
 *   * NIMS / ICS-237 Resource Typing — symbology categories used here
 *   * TAK ATAK-CIV iconsetpath convention (`COT_MAPPING_<SET>/<group>/<icon>`)
 */
object FemaIconCatalog {

    /** SAR / incident-command grouping shown as palette sections. */
    enum class Category(val displayName: String, val accent: Color, val accentArgb: Int) {
        INCIDENT_COMMAND("Command", Color(0xFFFF9800), 0xFFFF9800.toInt()),  // orange
        MEDICAL("Medical", Color(0xFFF44336), 0xFFF44336.toInt()),           // red
        AVIATION("Aviation", Color(0xFF00BCD4), 0xFF00BCD4.toInt()),         // cyan
        VEHICLES("Vehicles", Color(0xFFFFEB3B), 0xFFFFEB3B.toInt()),         // yellow
        SUPPORT("Support", Color(0xFF4CAF50), 0xFF4CAF50.toInt()),           // green
    }

    /**
     * Individual FEMA / IC icons. The [raw] values are embedded in
     * `<usericon iconsetpath="COT_MAPPING_FEMA/<category>/<raw>">` —
     * stable identifier, never rename once a peer ships with one.
     */
    enum class Kind(val raw: String) {
        COMMAND_POST("command_post"),
        STAGING_AREA("staging_area"),
        TRIAGE_STATION("triage_station"),
        MEDICAL_SUPPLY("medical_supply"),
        HELICOPTER_LZ("helicopter_lz"),
        FIRE_TRUCK("fire_truck"),
        AMBULANCE("ambulance"),
        SEARCH_TEAM("search_team"),
        BASE_CAMP("base_camp"),
        FOOD_WATER("food_water"),
        GENERATOR("generator"),
        COMMUNICATIONS_HUB("communications_hub"),
    }

    data class FemaIcon(
        val kind: Kind,
        val category: Category,
        val label: String,
        val image: ImageVector,
        val cotType: String,
    ) {
        // Stable wire identifier for inbound parsers + the `<usericon
        // iconsetpath="...">` emission in CotBuilders.
        val iconsetPath: String
            get() = "COT_MAPPING_FEMA/${category.raw}/${kind.raw}"
    }

    private val entries: Map<Kind, FemaIcon> = mapOf(
        // ── Incident Command ────────────────────────────────────────
        Kind.COMMAND_POST to FemaIcon(
            kind = Kind.COMMAND_POST,
            category = Category.INCIDENT_COMMAND,
            label = "Command Post",
            image = Icons.Filled.Flag,
            cotType = "a-f-G-I-U-T",
        ),
        Kind.STAGING_AREA to FemaIcon(
            kind = Kind.STAGING_AREA,
            category = Category.INCIDENT_COMMAND,
            label = "Staging Area",
            image = Icons.Filled.Inventory2,
            cotType = "a-f-G-I-B-A",
        ),
        // ── Medical ─────────────────────────────────────────────────
        Kind.TRIAGE_STATION to FemaIcon(
            kind = Kind.TRIAGE_STATION,
            category = Category.MEDICAL,
            label = "Triage Station",
            image = Icons.Filled.MedicalServices,
            cotType = "a-f-G-I-M-T",
        ),
        Kind.MEDICAL_SUPPLY to FemaIcon(
            kind = Kind.MEDICAL_SUPPLY,
            category = Category.MEDICAL,
            label = "Medical Supply",
            image = Icons.Filled.Vaccines,
            cotType = "a-f-G-I-M-S",
        ),
        // ── Aviation ────────────────────────────────────────────────
        Kind.HELICOPTER_LZ to FemaIcon(
            kind = Kind.HELICOPTER_LZ,
            category = Category.AVIATION,
            label = "Helicopter LZ",
            image = Icons.Filled.FlightTakeoff,
            cotType = "a-f-G-I-X-H",
        ),
        // ── Vehicles ────────────────────────────────────────────────
        Kind.FIRE_TRUCK to FemaIcon(
            kind = Kind.FIRE_TRUCK,
            category = Category.VEHICLES,
            label = "Fire Engine",
            image = Icons.Filled.LocalFireDepartment,
            cotType = "a-f-G-E-V-F-R",
        ),
        Kind.AMBULANCE to FemaIcon(
            kind = Kind.AMBULANCE,
            category = Category.VEHICLES,
            label = "Ambulance",
            image = Icons.Filled.LocalHospital,
            cotType = "a-f-G-E-V-M-A",
        ),
        Kind.SEARCH_TEAM to FemaIcon(
            kind = Kind.SEARCH_TEAM,
            category = Category.VEHICLES,
            label = "Search Team",
            image = Icons.Filled.Groups,
            cotType = "a-f-G-U-U-S",
        ),
        // ── Support ─────────────────────────────────────────────────
        Kind.BASE_CAMP to FemaIcon(
            kind = Kind.BASE_CAMP,
            category = Category.SUPPORT,
            label = "Base Camp",
            image = Icons.Filled.House,
            cotType = "a-f-G-I-B-C",
        ),
        Kind.FOOD_WATER to FemaIcon(
            kind = Kind.FOOD_WATER,
            category = Category.SUPPORT,
            label = "Food / Water",
            image = Icons.Filled.Restaurant,
            cotType = "a-f-G-I-B-F",
        ),
        Kind.GENERATOR to FemaIcon(
            kind = Kind.GENERATOR,
            category = Category.SUPPORT,
            label = "Generator",
            image = Icons.Filled.Bolt,
            cotType = "a-f-G-I-U-E",
        ),
        Kind.COMMUNICATIONS_HUB to FemaIcon(
            kind = Kind.COMMUNICATIONS_HUB,
            category = Category.SUPPORT,
            label = "Comms Hub",
            image = Icons.Filled.CellTower,
            cotType = "a-f-G-I-U-C",
        ),
    )

    /** All icons, in stable enum-declaration order. */
    val all: List<FemaIcon> get() = Kind.entries.mapNotNull(entries::get)

    /** Icons filtered to a single category, declaration-order preserved. */
    fun iconsIn(category: Category): List<FemaIcon> = all.filter { it.category == category }

    /** Lookup by enum. */
    fun iconFor(kind: Kind): FemaIcon? = entries[kind]

    /** Reverse lookup from a stored raw key (e.g. `"command_post"`). */
    fun iconByRaw(raw: String): FemaIcon? =
        Kind.entries.firstOrNull { it.raw == raw }?.let(entries::get)

    /**
     * Reverse lookup from a CoT `<usericon iconsetpath="...">` value.
     * Returns null when the path doesn't point at the FEMA catalog (or
     * the kind isn't one this build knows about).
     */
    fun iconByIconsetPath(path: String): FemaIcon? {
        val parts = path.split('/')
        if (parts.size < 3 || parts[0] != "COT_MAPPING_FEMA") return null
        return iconByRaw(parts[2])
    }

    private val Category.raw: String
        get() = when (this) {
            Category.INCIDENT_COMMAND -> "incidentCommand"
            Category.MEDICAL -> "medical"
            Category.AVIATION -> "aviation"
            Category.VEHICLES -> "vehicles"
            Category.SUPPORT -> "support"
        }
}
