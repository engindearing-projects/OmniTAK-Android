package soy.engindearing.omnitak.mobile.data.symbology

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #98 Phase 2 — unit tests for [IconsetPackParser], the pure-JVM/Kotlin
 * parser for ATAK's `iconset.xml` format.
 *
 * ATAK iconset.xml structure:
 *
 * ```xml
 * <?xml version="1.0" encoding="UTF-8"?>
 * <iconset uid="abc-123" defaultFriendlyGroup="…" defaultHostileGroup="…"
 *          defaultNeutralGroup="…" defaultUnknownGroup="…" name="My Pack"
 *          skipResize="false" version="1">
 *   <icon name="Ambulance" filename="Ground/Ambulance.png"/>
 *   <icon name="Police Car" filename="Ground/PoliceCar.png"/>
 * </iconset>
 * ```
 *
 * The parser is the core unit-testable contract — all path/name logic lives
 * here, with zero Android-framework dependencies. Bitmap loading is handled
 * separately by [IconPackRegistry] (needs a Context, covered on-device).
 */
class IconsetPackParserTest {

    // ── Well-formed iconset.xml fixtures ──────────────────────────────────

    private val minimalXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <iconset uid="test-uid-001" name="Test Pack" version="1">
          <icon name="Ambulance" filename="Ground/Ambulance.png"/>
          <icon name="Police Car" filename="Ground/PoliceCar.png"/>
        </iconset>
    """.trimIndent()

    private val fullAtakXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <iconset uid="f47ac10b-58cc-4372-a567-0e02b2c3d479"
                 defaultFriendlyGroup="Ground"
                 defaultHostileGroup="Ground"
                 defaultNeutralGroup="Ground"
                 defaultUnknownGroup="Ground"
                 name="ATAK Military"
                 skipResize="false"
                 version="1">
          <icon name="Ambulance"     filename="Ground/Ambulance.png"/>
          <icon name="Police Car"    filename="Ground/PoliceCar.png"/>
          <icon name="Helicopter"    filename="Air/Helicopter.png"/>
          <icon name="Infantry"      filename="Ground/Infantry.png"/>
          <icon name="Armored"       filename="Ground/Armored.png"/>
        </iconset>
    """.trimIndent()

    /** ATAK sometimes nests icons under a `<group>` element. */
    private val groupedXml = """
        <?xml version="1.0" encoding="UTF-8"?>
        <iconset uid="grouped-uid" name="Grouped Pack" version="1">
          <group name="Ground">
            <icon name="Tank" filename="Ground/Tank.png"/>
            <icon name="Jeep" filename="Ground/Jeep.png"/>
          </group>
          <group name="Air">
            <icon name="Drone" filename="Air/Drone.png"/>
          </group>
          <icon name="Unknown" filename="Unknown.png"/>
        </iconset>
    """.trimIndent()

    // ── Parser construction and basic attribute parsing ───────────────────

    @Test
    fun `parse minimal iconset xml returns correct uid and name`() {
        val result = IconsetPackParser.parse(minimalXml)
        assertNotNull(result)
        assertEquals("test-uid-001", result!!.uid)
        assertEquals("Test Pack", result.name)
    }

    @Test
    fun `parse full atak iconset xml returns correct uid and name`() {
        val result = IconsetPackParser.parse(fullAtakXml)
        assertNotNull(result)
        assertEquals("f47ac10b-58cc-4372-a567-0e02b2c3d479", result!!.uid)
        assertEquals("ATAK Military", result.name)
    }

    @Test
    fun `parse iconset version attribute is captured`() {
        val result = IconsetPackParser.parse(fullAtakXml)
        assertNotNull(result)
        assertEquals(1, result!!.version)
    }

    // ── Icon entry parsing ────────────────────────────────────────────────

    @Test
    fun `parse minimal xml returns all icon entries`() {
        val result = IconsetPackParser.parse(minimalXml)!!
        assertEquals(2, result.icons.size)
    }

    @Test
    fun `parse icon name and filename are correct`() {
        val result = IconsetPackParser.parse(minimalXml)!!
        val ambulance = result.icons.first { it.name == "Ambulance" }
        assertEquals("Ground/Ambulance.png", ambulance.filename)
    }

    @Test
    fun `parse full atak xml returns all five icons`() {
        val result = IconsetPackParser.parse(fullAtakXml)!!
        assertEquals(5, result.icons.size)
    }

    @Test
    fun `every icon in full xml has a non-empty filename`() {
        val result = IconsetPackParser.parse(fullAtakXml)!!
        for (icon in result.icons) {
            assertTrue("icon '${icon.name}' has empty filename", icon.filename.isNotEmpty())
        }
    }

    // ── Grouped icon support ──────────────────────────────────────────────

    @Test
    fun `parse grouped iconset xml flattens icons from all groups`() {
        val result = IconsetPackParser.parse(groupedXml)!!
        // 2 (Ground) + 1 (Air) + 1 (top-level) = 4 total
        assertEquals(4, result.icons.size)
    }

    @Test
    fun `icon filenames from grouped xml are as authored`() {
        val result = IconsetPackParser.parse(groupedXml)!!
        val filenames = result.icons.map { it.filename }.toSet()
        assertTrue("Ground/Tank.png" in filenames)
        assertTrue("Air/Drone.png" in filenames)
        assertTrue("Unknown.png" in filenames)
    }

    // ── Icon resolution by name ───────────────────────────────────────────

    @Test
    fun `iconByName resolves an exact match case-sensitively`() {
        val pack = IconsetPackParser.parse(fullAtakXml)!!
        val icon = pack.iconByName("Ambulance")
        assertNotNull(icon)
        assertEquals("Ground/Ambulance.png", icon!!.filename)
    }

    @Test
    fun `iconByName returns null for unknown name`() {
        val pack = IconsetPackParser.parse(fullAtakXml)!!
        assertNull(pack.iconByName("SpaceShip"))
    }

    @Test
    fun `iconByNameInsensitive resolves case-insensitively`() {
        val pack = IconsetPackParser.parse(fullAtakXml)!!
        assertNotNull(pack.iconByNameInsensitive("ambulance"))
        assertNotNull(pack.iconByNameInsensitive("HELICOPTER"))
    }

    // ── iconsetPath resolution (ATAK wire format) ─────────────────────────
    //
    // ATAK `usericon iconsetpath` for custom packs is:
    //   "<uid>/<filename-no-extension>"  e.g. "f47ac10b-…/Ground/Ambulance"
    // or sometimes the basename only: "f47ac10b-…/Ambulance"
    // We resolve both forms so inbound CoT from ATAK peers works.

    @Test
    fun `iconByIconsetPath resolves full path including subdirectory`() {
        val pack = IconsetPackParser.parse(fullAtakXml)!!
        val icon = pack.iconByIconsetPath("f47ac10b-58cc-4372-a567-0e02b2c3d479/Ground/Ambulance")
        assertNotNull(icon)
        assertEquals("Ground/Ambulance.png", icon!!.filename)
    }

    @Test
    fun `iconByIconsetPath resolves path with png extension`() {
        val pack = IconsetPackParser.parse(fullAtakXml)!!
        val icon = pack.iconByIconsetPath("f47ac10b-58cc-4372-a567-0e02b2c3d479/Ground/Ambulance.png")
        assertNotNull(icon)
        assertEquals("Ground/Ambulance.png", icon!!.filename)
    }

    @Test
    fun `iconByIconsetPath resolves basename-only path`() {
        val pack = IconsetPackParser.parse(minimalXml)!!
        val icon = pack.iconByIconsetPath("test-uid-001/Ambulance")
        assertNotNull(icon)
    }

    @Test
    fun `iconByIconsetPath returns null when uid doesnt match`() {
        val pack = IconsetPackParser.parse(minimalXml)!!
        val icon = pack.iconByIconsetPath("wrong-uid/Ambulance")
        assertNull(icon)
    }

    @Test
    fun `iconByIconsetPath returns null for unknown token in correct uid`() {
        val pack = IconsetPackParser.parse(minimalXml)!!
        assertNull(pack.iconByIconsetPath("test-uid-001/SpaceShip"))
    }

    // ── iconsetPath generation ────────────────────────────────────────────

    @Test
    fun `iconsetPath for an icon matches ATAK canonical form uid-slash-filename-no-ext`() {
        val pack = IconsetPackParser.parse(fullAtakXml)!!
        val icon = pack.icons.first { it.name == "Ambulance" }
        val expected = "f47ac10b-58cc-4372-a567-0e02b2c3d479/Ground/Ambulance"
        assertEquals(expected, pack.iconsetPathFor(icon))
    }

    @Test
    fun `iconsetPath round-trips through the resolver`() {
        val pack = IconsetPackParser.parse(fullAtakXml)!!
        for (icon in pack.icons) {
            val path = pack.iconsetPathFor(icon)
            val resolved = pack.iconByIconsetPath(path)
            assertNotNull("iconsetPath '$path' failed to resolve back", resolved)
            assertEquals(icon.filename, resolved!!.filename)
        }
    }

    // ── Malformed input handling ──────────────────────────────────────────

    @Test
    fun `parse returns null for completely empty string`() {
        assertNull(IconsetPackParser.parse(""))
    }

    @Test
    fun `parse returns null for non-xml garbage`() {
        assertNull(IconsetPackParser.parse("this is not xml at all }{]["))
    }

    @Test
    fun `parse returns null when iconset element is missing`() {
        val xml = """
            <?xml version="1.0"?>
            <root><something/></root>
        """.trimIndent()
        assertNull(IconsetPackParser.parse(xml))
    }

    @Test
    fun `parse returns null when uid attribute is missing`() {
        val xml = """
            <?xml version="1.0"?>
            <iconset name="No UID Pack" version="1">
              <icon name="X" filename="x.png"/>
            </iconset>
        """.trimIndent()
        assertNull(IconsetPackParser.parse(xml))
    }

    @Test
    fun `parse tolerates missing name attribute and uses uid as fallback`() {
        val xml = """
            <?xml version="1.0"?>
            <iconset uid="no-name-uid" version="1">
              <icon name="X" filename="x.png"/>
            </iconset>
        """.trimIndent()
        val result = IconsetPackParser.parse(xml)
        assertNotNull(result)
        // Name should fall back to uid when absent
        assertEquals("no-name-uid", result!!.name)
    }

    @Test
    fun `parse with zero icons returns an empty icon list not null`() {
        val xml = """
            <?xml version="1.0"?>
            <iconset uid="empty-uid" name="Empty Pack" version="1">
            </iconset>
        """.trimIndent()
        val result = IconsetPackParser.parse(xml)
        assertNotNull(result)
        assertTrue(result!!.icons.isEmpty())
    }

    @Test
    fun `parse skips icon entries with missing filename`() {
        val xml = """
            <?xml version="1.0"?>
            <iconset uid="partial-uid" name="Partial" version="1">
              <icon name="Good" filename="good.png"/>
              <icon name="Bad"/>
            </iconset>
        """.trimIndent()
        val result = IconsetPackParser.parse(xml)!!
        assertEquals(1, result.icons.size)
        assertEquals("good.png", result.icons.first().filename)
    }

    @Test
    fun `parse skips icon entries with missing name attribute`() {
        val xml = """
            <?xml version="1.0"?>
            <iconset uid="partial-uid-2" name="Partial2" version="1">
              <icon name="Good" filename="good.png"/>
              <icon filename="noname.png"/>
            </iconset>
        """.trimIndent()
        val result = IconsetPackParser.parse(xml)!!
        assertEquals(1, result.icons.size)
    }

    // ── TakIconRegistry integration ───────────────────────────────────────
    //
    // An imported pack's iconset path must flow through the registry's
    // `handles()` / `styleImageId()` so the ContactSymbolLayer renders it.

    @Test
    fun `importedPack iconsetPath passes TakIconRegistry handles check`() {
        // Simulate: an imported pack is registered; a received CoT carries its
        // iconsetpath; the registry must acknowledge it belongs to an imported pack.
        val pack = IconsetPackParser.parse(fullAtakXml)!!
        val icon = pack.icons.first { it.name == "Ambulance" }
        val path = pack.iconsetPathFor(icon)

        // The stub pack integration in TakIconRegistry — Phase 2 wires this.
        // For the unit test, we assert the path structure is correct so the
        // registry CAN look it up; the registry-side wiring is in IconPackRegistry.
        assertTrue(path.startsWith(pack.uid))
        assertTrue(path.contains("Ambulance"))
    }
}
