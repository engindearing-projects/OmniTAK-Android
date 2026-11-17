package soy.engindearing.omnitak.mobile.data.geofence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #155 - the saved file: what is written comes back, and a file that has gone wrong costs as little
 * as possible and is never destroyed by the next save.
 */
class GeofenceStorageTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(): GeofenceFile = GeofenceFile.inFilesDir(tmp.root)
    private fun path(): File = File(File(tmp.root, GeofenceFile.DIR_NAME), GeofenceFile.FILE_NAME)
    private fun dir(): File = checkNotNull(path().parentFile)
    private fun aside(): File = File(dir(), GeofenceFile.FILE_NAME + GeofenceFile.CORRUPT_SUFFIX)

    private fun circle(id: String, name: String = "Gate $id") = Geofence(
        id = id, name = name, type = GeofenceType.CIRCLE, color = GeofenceColor.CYAN,
        centerLat = 47.6588, centerLon = -117.4260, radiusMeters = 200.0, createdAtMs = 1_800_000_000_000L,
    )

    private fun yard(id: String) = Geofence(
        id = id, name = "Yard $id", type = GeofenceType.POLYGON, color = GeofenceColor.GREEN, dwellMinutes = 10,
        watchSelf = false, alertOnExit = false,
        points = listOf(LatLon(47.65, -117.43), LatLon(47.65, -117.42), LatLon(47.66, -117.42), LatLon(47.66, -117.43)),
    )

    private fun event(id: String, atMs: Long = 1L) = GeofenceEvent(
        id = id, geofenceId = "a", geofenceName = "Gate a", type = GeofenceEventType.ENTRY, atMs = atMs,
        lat = 47.6588, lon = -117.4260, subjectId = "bravo", subjectName = "BRAVO", byOperator = false,
    )

    // ------------------------------------------------------------------ round trip

    @Test fun a_missing_file_is_an_empty_snapshot() {
        assertEquals(GeofenceSnapshot(), file().load())
    }

    @Test fun what_is_saved_comes_back_exactly() {
        val snapshot = GeofenceSnapshot(
            geofences = listOf(circle("a"), yard("b"), circle("c").copy(isActive = false, dwellMinutes = 5)),
            events = listOf(event("e2", 2L), event("e1", 1L).copy(type = GeofenceEventType.DWELL, stayMs = 300_000, byOperator = true)),
        )
        assertTrue(file().save(snapshot))
        assertEquals(snapshot, file().load())
    }

    @Test fun it_is_saved_where_the_chat_log_is_kept_in_the_private_files_directory() {
        assertTrue(file().save(GeofenceSnapshot(geofences = listOf(circle("a")))))
        assertEquals(File(File(tmp.root, "geofences"), "geofences.json"), path())
        assertTrue(path().isFile)
    }

    @Test fun a_save_leaves_no_temporary_file_behind() {
        file().save(GeofenceSnapshot(geofences = listOf(circle("a"))))
        file().save(GeofenceSnapshot(geofences = listOf(circle("a"), circle("b"))))
        assertEquals(listOf(GeofenceFile.FILE_NAME), dir().list()!!.toList())
    }

    @Test fun the_history_keeps_the_newest_hundred_events() {
        val many = (1..150).map { event("e$it", it.toLong()) }
        file().save(GeofenceSnapshot(events = many))
        val back = file().load().events
        assertEquals(GeofenceSnapshot.MAX_EVENTS, back.size)
        assertEquals("e1", back.first().id)
        assertEquals("e100", back.last().id)
    }

    @Test fun two_geofences_with_the_same_id_are_one() {
        file().save(GeofenceSnapshot(geofences = listOf(circle("a", "First"), circle("a", "Second"))))
        assertEquals(listOf("First"), file().load().geofences.map { it.name })
    }

    // ------------------------------------------------------------------ corrupt-file recovery

    @Test fun an_empty_file_is_an_empty_snapshot_and_nothing_is_set_aside() {
        dir().mkdirs()
        path().writeText("")
        assertEquals(GeofenceSnapshot(), file().load())
        assertFalse(aside().exists())
    }

    @Test fun a_truncated_file_starts_empty_and_the_file_is_kept_aside() {
        file().save(GeofenceSnapshot(geofences = listOf(circle("a"), circle("b"))))
        val whole = path().readText()
        path().writeText(whole.take(whole.length / 2))

        assertEquals(GeofenceSnapshot(), file().load())
        assertFalse("moved aside, not left to be written over", path().exists())
        assertEquals(whole.take(whole.length / 2), aside().readText())

        // The next save works on a clean path, and the damaged copy is still there.
        assertTrue(file().save(GeofenceSnapshot(geofences = listOf(circle("z")))))
        assertEquals(listOf("z"), file().load().geofences.map { it.id })
        assertTrue(aside().isFile)
    }

    @Test fun something_that_is_not_json_at_all_starts_empty_and_is_kept_aside() {
        dir().mkdirs()
        path().writeText("this is not a geofence file")
        assertEquals(GeofenceSnapshot(), file().load())
        assertEquals("this is not a geofence file", aside().readText())
    }

    @Test fun json_of_some_other_kind_is_not_taken_for_ours() {
        dir().mkdirs()
        path().writeText("""{"conversations":[],"messages":[]}""")
        assertEquals(GeofenceSnapshot(), file().load())
        assertTrue(aside().isFile)
    }

    @Test fun one_damaged_geofence_does_not_take_the_others_with_it() {
        dir().mkdirs()
        path().writeText(
            """
            {"version":1,"geofences":[
              {"id":"a","name":"Good","type":"CIRCLE","centerLat":47.0,"centerLon":-122.0,"radiusMeters":100.0},
              {"id":"b","name":"Broken","type":"TRIANGLE"},
              {"name":"No id","type":"CIRCLE"},
              {"id":"d","name":"Also good","type":"POLYGON","points":[{"lat":0.0,"lon":0.0},{"lat":0.0,"lon":1.0},{"lat":1.0,"lon":1.0}]}
            ],"events":[]}
            """.trimIndent(),
        )
        assertEquals(listOf("Good", "Also good"), file().load().geofences.map { it.name })
        assertTrue("a copy of what could not be read is kept", aside().isFile)
        assertTrue("the file itself is still there", path().isFile)
    }

    @Test fun one_damaged_event_does_not_take_the_others_with_it() {
        dir().mkdirs()
        path().writeText(
            """
            {"version":1,"geofences":[],"events":[
              {"id":"e1","geofenceId":"a","geofenceName":"Gate","type":"ENTRY","atMs":5,"lat":1.0,"lon":2.0,"subjectId":"x"},
              {"id":"e2","type":"SIDEWAYS"},
              {"id":"e3","geofenceId":"a","geofenceName":"Gate","type":"EXIT","atMs":9,"lat":1.0,"lon":2.0,"subjectId":"x"}
            ]}
            """.trimIndent(),
        )
        assertEquals(listOf("e1", "e3"), file().load().events.map { it.id })
    }

    @Test fun a_file_from_a_newer_build_with_fields_this_one_does_not_know_still_loads() {
        dir().mkdirs()
        path().writeText(
            """
            {"version":7,"someFutureList":[1,2],"geofences":[
              {"id":"a","name":"New one","type":"CIRCLE","centerLat":47.0,"centerLon":-122.0,"radiusMeters":100.0,"shiny":true,"color":"MAGENTA"}
            ]}
            """.trimIndent(),
        )
        val g = file().load().geofences.single()
        assertEquals("New one", g.name)
        assertEquals("a colour this build does not have falls back to the default", GeofenceColor.YELLOW, g.color)
        assertFalse("it was readable, so nothing is set aside", aside().exists())
    }

    @Test fun a_file_far_larger_than_it_can_be_is_not_read_and_is_set_aside() {
        dir().mkdirs()
        path().writeText("x".repeat(100))
        // Not 8 MB of data in a test: the cap is checked against the length, so shrink the cap by growing the file with a sparse tail.
        java.io.RandomAccessFile(path(), "rw").use { it.setLength(GeofenceFile.MAX_FILE_BYTES + 1) }
        assertEquals(GeofenceSnapshot(), file().load())
        assertTrue(aside().isFile)
    }
}
