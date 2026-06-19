package soy.engindearing.omnitak.mobile.data.military

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #154 — Military report → CoT builders (ported from iOS MilitaryReportCoT +
 * MEDEVAC/SPOTREP models). Pure formatting + XML, so it unit-tests directly.
 */
class MilitaryReportsTest {

    private val medevac = Medevac9Line(
        locationGrid = "18SUJ2348907654",
        radioFreq = "30.55", callSign = "DUSTOFF", callSignSuffix = "6",
        patientsUrgent = 2, patientsPriority = 1, patientsRoutine = 0,
        specialEquipment = SpecialEquipment.HOIST,
        patientsLitter = 2, patientsAmbulatory = 1,
        security = PickupSecurity.ENEMY,
        marking = MarkingMethod.SMOKE,
        nationality = PatientNationality.US_MILITARY,
        cbrn = CbrnContamination.NONE,
    )

    @Test fun medevac_text_has_all_nine_lines_and_codes() {
        val t = medevac.formattedText()
        assertTrue(t.startsWith("9-LINE MEDEVAC REQUEST"))
        assertTrue(t.contains("LINE 1 - LOCATION: 18SUJ2348907654"))
        assertTrue(t.contains("LINE 2 - FREQ/CALLSIGN: 30.55 / DUSTOFF-6"))
        assertTrue(t.contains("URGENT 2") && t.contains("PRIORITY 1") && t.contains("ROUTINE 0"))
        assertTrue(t.contains("LINE 4 - SPECIAL EQUIPMENT: B - Hoist"))
        assertTrue(t.contains("LITTER 2") && t.contains("AMBULATORY 1"))
        assertTrue(t.contains("LINE 6 - SECURITY: E -"))
        assertTrue(t.contains("LINE 7 - MARKING: C - Smoke Signal"))
        assertTrue(t.contains("LINE 9 - CBRN:"))
        for (n in 1..9) assertTrue("missing LINE $n", t.contains("LINE $n -"))
    }

    @Test fun salute_text_has_all_six_elements() {
        val t = SaluteReport(
            size = "Squad (8-12)", activity = "Digging in", locationGrid = "18SUJ23480765",
            unit = "Unknown infantry", time = "211530ZJUN26", equipment = "2x PKM, 1x RPG",
        ).formattedText()
        assertTrue(t.contains("S - SIZE: Squad (8-12)"))
        assertTrue(t.contains("A - ACTIVITY: Digging in"))
        assertTrue(t.contains("L - LOCATION: 18SUJ23480765"))
        assertTrue(t.contains("U - UNIT: Unknown infantry"))
        assertTrue(t.contains("T - TIME: 211530ZJUN26"))
        assertTrue(t.contains("E - EQUIPMENT: 2x PKM, 1x RPG"))
    }

    @Test fun report_cot_types_match_ios() {
        assertEquals("b-r-f-h-c", ReportType.MEDEVAC.cot)
        assertEquals("b-m-p-w", ReportType.SPOTREP.cot)
        assertEquals("b-m-p-w", ReportType.SALUTE.cot)
    }

    @Test fun cot_event_wraps_type_point_contact_and_remarks() {
        val xml = MilitaryReportCoT.buildReportEvent(
            uid = "MEDEVAC-abc", type = ReportType.MEDEVAC,
            senderCallsign = "OMNI-1", lat = 47.6, lon = -122.3,
            reportText = medevac.formattedText(), nowMs = 1_700_000_000_000L,
        )
        assertTrue(xml.contains("<event"))
        assertTrue(xml.contains("uid=\"MEDEVAC-abc\""))
        assertTrue(xml.contains("type=\"b-r-f-h-c\""))
        assertTrue(xml.contains("lat=\"47.6\"") && xml.contains("lon=\"-122.3\""))
        assertTrue(xml.contains("<contact callsign=\"OMNI-1\"/>"))
        assertTrue(xml.contains("<remarks>") && xml.contains("9-LINE MEDEVAC REQUEST"))
        assertTrue(xml.contains("how=\"h-g-i-g-o\""))
    }

    @Test fun cot_event_xml_escapes_callsign_and_remarks() {
        val xml = MilitaryReportCoT.buildReportEvent(
            uid = "SPOTREP-1", type = ReportType.SPOTREP,
            senderCallsign = "A&B", lat = 0.0, lon = 0.0,
            reportText = "remark <hostile> & \"danger\"", nowMs = 1_700_000_000_000L,
        )
        assertTrue("callsign & must be escaped", xml.contains("callsign=\"A&amp;B\""))
        assertTrue("remarks < must be escaped", xml.contains("&lt;hostile&gt;"))
        assertFalse("no raw unescaped <hostile> in output", xml.contains("<hostile>"))
    }
}
