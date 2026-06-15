package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #77 — add / update / remove diff that lets the contact SymbolManager
 * mutate only what changed instead of recreating every Symbol on each CoT tick.
 */
class ContactSymbolDiffTest {

    private fun spec(
        uid: String,
        lat: Double = 1.0,
        lon: Double = 2.0,
        imageId: String = "milstd-SFGP",
        callsign: String = uid,
        teamColorArgb: Int = 0xFF00FFFF.toInt(),
    ) = ContactSymbolSpec(uid, lat, lon, imageId, callsign, teamColorArgb)

    @Test fun `empty to empty is a no-op`() {
        val r = ContactSymbolDiff.compute(current = emptyMap(), target = emptyList())
        assertTrue(r.isEmpty)
    }

    @Test fun `new uids are added`() {
        val r = ContactSymbolDiff.compute(
            current = emptyMap(),
            target = listOf(spec("a"), spec("b")),
        )
        assertEquals(listOf("a", "b"), r.toAdd.map { it.uid })
        assertTrue(r.toUpdate.isEmpty())
        assertTrue(r.toRemove.isEmpty())
    }

    @Test fun `disappeared uids are removed`() {
        val r = ContactSymbolDiff.compute(
            current = mapOf("a" to spec("a"), "b" to spec("b")),
            target = listOf(spec("a")),
        )
        assertEquals(listOf("b"), r.toRemove)
        assertTrue(r.toAdd.isEmpty())
        assertTrue(r.toUpdate.isEmpty())
    }

    @Test fun `identical spec produces no update (no needless re-push)`() {
        val s = spec("a")
        val r = ContactSymbolDiff.compute(current = mapOf("a" to s), target = listOf(s))
        assertTrue("unchanged contact must not churn the SymbolManager", r.isEmpty)
    }

    @Test fun `moved contact is an update, not add+remove`() {
        val r = ContactSymbolDiff.compute(
            current = mapOf("a" to spec("a", lat = 1.0, lon = 2.0)),
            target = listOf(spec("a", lat = 9.0, lon = 8.0)),
        )
        assertEquals(listOf("a"), r.toUpdate.map { it.uid })
        assertEquals(9.0, r.toUpdate.single().lat, 0.0)
        assertTrue(r.toAdd.isEmpty())
        assertTrue(r.toRemove.isEmpty())
    }

    @Test fun `changed callsign is an update`() {
        val r = ContactSymbolDiff.compute(
            current = mapOf("a" to spec("a", callsign = "Old")),
            target = listOf(spec("a", callsign = "New")),
        )
        assertEquals("New", r.toUpdate.single().callsign)
    }

    @Test fun `changed team color is an update (team reassignment)`() {
        val r = ContactSymbolDiff.compute(
            current = mapOf("a" to spec("a", teamColorArgb = 0xFF00FFFF.toInt())),
            target = listOf(spec("a", teamColorArgb = 0xFFFF0000.toInt())),
        )
        assertEquals(0xFFFF0000.toInt(), r.toUpdate.single().teamColorArgb)
    }

    @Test fun `changed icon image is an update (type change re-symbolizes)`() {
        val r = ContactSymbolDiff.compute(
            current = mapOf("a" to spec("a", imageId = "milstd-SFGP")),
            target = listOf(spec("a", imageId = "milstd-SHGP")),
        )
        assertEquals("milstd-SHGP", r.toUpdate.single().imageId)
    }

    @Test fun `mixed add update remove in one pass`() {
        val r = ContactSymbolDiff.compute(
            current = mapOf(
                "keep" to spec("keep"),
                "move" to spec("move", lat = 0.0),
                "drop" to spec("drop"),
            ),
            target = listOf(
                spec("keep"),                  // unchanged → skipped
                spec("move", lat = 5.0),       // moved → update
                spec("add"),                   // new → add
            ),
        )
        assertEquals(listOf("add"), r.toAdd.map { it.uid })
        assertEquals(listOf("move"), r.toUpdate.map { it.uid })
        assertEquals(listOf("drop"), r.toRemove)
        assertFalse(r.isEmpty)
    }

    @Test fun `duplicate uid in target collapses to one with last wins`() {
        // A contact store should not hold duplicate uids, but if it does we must
        // emit exactly one Symbol per uid (last spec wins), never two adds.
        val r = ContactSymbolDiff.compute(
            current = emptyMap(),
            target = listOf(spec("a", callsign = "First"), spec("a", callsign = "Last")),
        )
        assertEquals(1, r.toAdd.size)
        assertEquals("Last", r.toAdd.single().callsign)
    }
}
