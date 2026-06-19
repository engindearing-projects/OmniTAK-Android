package soy.engindearing.omnitak.mobile.data.military

import soy.engindearing.omnitak.mobile.data.CotXml

/**
 * Military report → CoT (#154, ported from iOS `MilitaryReportCoT` +
 * MEDEVAC/SPOTREP models). Pure text formatting + CoT XML, so it unit-tests
 * directly; the Android layer fills the forms and ships the event over the
 * active TAK connection (the same path PPLI/markers use).
 */

/** CoT type per report — matches the iOS `ReportType` constants. */
enum class ReportType(val cot: String) {
    /** 9-line casevac request (ATAK Medline). */
    MEDEVAC("b-r-f-h-c"),

    /** SALUTE observation — renders as a spot-report point. */
    SALUTE("b-m-p-w"),

    /** SPOTREP — same spot-report point type as SALUTE. */
    SPOTREP("b-m-p-w"),
}

// --- MEDEVAC 9-line coded fields (standard MEDEVAC request codes) ---

enum class SpecialEquipment(val code: String, val label: String) {
    NONE("A", "None Required"),
    HOIST("B", "Hoist"),
    EXTRACTION("C", "Extraction Equipment"),
    VENTILATOR("D", "Ventilator"),
}

enum class PickupSecurity(val code: String, val label: String) {
    NO_ENEMY("N", "No Enemy Troops in Area"),
    POSSIBLE_ENEMY("P", "Possible Enemy Troops in Area"),
    ENEMY("E", "Enemy Troops in Area (Approach with Caution)"),
    ESCORT_REQUIRED("X", "Armed Escort Required"),
}

enum class MarkingMethod(val code: String, val label: String) {
    PANELS("A", "Panels"),
    PYROTECHNIC("B", "Pyrotechnic Signal"),
    SMOKE("C", "Smoke Signal"),
    NONE("D", "None"),
    OTHER("E", "Other"),
}

enum class PatientNationality(val code: String, val label: String) {
    US_MILITARY("A", "US Military"),
    US_CIVILIAN("B", "US Civilian"),
    NON_US_MILITARY("C", "Non-US Military"),
    NON_US_CIVILIAN("D", "Non-US Civilian"),
    EPW("E", "Enemy Prisoner of War"),
}

enum class CbrnContamination(val code: String, val label: String) {
    NONE("-", "None"),
    NUCLEAR("N", "Nuclear"),
    BIOLOGICAL("B", "Biological"),
    CHEMICAL("C", "Chemical"),
}

/** Nine-line MEDEVAC request (CoT type `b-r-f-h-c`). */
data class Medevac9Line(
    val locationGrid: String,
    val radioFreq: String,
    val callSign: String,
    val callSignSuffix: String = "",
    val patientsUrgent: Int = 0,
    val patientsPriority: Int = 0,
    val patientsRoutine: Int = 0,
    val specialEquipment: SpecialEquipment = SpecialEquipment.NONE,
    val patientsLitter: Int = 0,
    val patientsAmbulatory: Int = 0,
    val security: PickupSecurity = PickupSecurity.NO_ENEMY,
    val marking: MarkingMethod = MarkingMethod.NONE,
    val nationality: PatientNationality = PatientNationality.US_MILITARY,
    val cbrn: CbrnContamination = CbrnContamination.NONE,
) {
    fun formattedText(): String {
        val suffix = if (callSignSuffix.isBlank()) "" else "-$callSignSuffix"
        return buildString {
            appendLine("9-LINE MEDEVAC REQUEST")
            appendLine("LINE 1 - LOCATION: $locationGrid")
            appendLine("LINE 2 - FREQ/CALLSIGN: $radioFreq / $callSign$suffix")
            appendLine(
                "LINE 3 - PATIENTS BY PRECEDENCE: URGENT $patientsUrgent, " +
                    "PRIORITY $patientsPriority, ROUTINE $patientsRoutine",
            )
            appendLine("LINE 4 - SPECIAL EQUIPMENT: ${specialEquipment.code} - ${specialEquipment.label}")
            appendLine("LINE 5 - PATIENTS BY TYPE: LITTER $patientsLitter, AMBULATORY $patientsAmbulatory")
            appendLine("LINE 6 - SECURITY: ${security.code} - ${security.label}")
            appendLine("LINE 7 - MARKING: ${marking.code} - ${marking.label}")
            appendLine("LINE 8 - NATIONALITY: ${nationality.code} - ${nationality.label}")
            append("LINE 9 - CBRN: ${cbrn.code} - ${cbrn.label}")
        }
    }
}

/** SALUTE / SPOTREP observation report (CoT type `b-m-p-w`). */
data class SaluteReport(
    val size: String,
    val activity: String,
    val locationGrid: String,
    val unit: String,
    val time: String,
    val equipment: String,
    val title: String = "SALUTE REPORT",
) {
    fun formattedText(): String = buildString {
        appendLine(title)
        appendLine("S - SIZE: $size")
        appendLine("A - ACTIVITY: $activity")
        appendLine("L - LOCATION: $locationGrid")
        appendLine("U - UNIT: $unit")
        appendLine("T - TIME: $time")
        append("E - EQUIPMENT: $equipment")
    }
}

object MilitaryReportCoT {

    /** 1-hour stale, matching the iOS report envelope. */
    private const val STALE_MS = 3_600_000L

    /**
     * Wrap a formatted report in a CoT event: a point at the report location
     * carrying a `<contact>` and the report text in `<remarks>`. Matches the
     * iOS envelope (how=`h-g-i-g-o`, 1-hour stale). Field content is XML-escaped.
     */
    fun buildReportEvent(
        uid: String,
        type: ReportType,
        senderCallsign: String,
        lat: Double,
        lon: Double,
        reportText: String,
        nowMs: Long = System.currentTimeMillis(),
    ): String {
        val detail = buildString {
            append("<detail>")
            append("<contact callsign=\"").append(CotXml.escape(senderCallsign)).append("\"/>")
            append("<remarks>").append(CotXml.escape(reportText)).append("</remarks>")
            append("</detail>")
        }
        return CotXml.buildEvent(
            uid = uid,
            type = type.cot,
            how = "h-g-i-g-o",
            lat = lat,
            lon = lon,
            timeIso = CotXml.isoMillis(nowMs),
            startIso = CotXml.isoMillis(nowMs),
            staleIso = CotXml.isoMillis(nowMs + STALE_MS),
            detailXml = detail,
        )
    }
}
