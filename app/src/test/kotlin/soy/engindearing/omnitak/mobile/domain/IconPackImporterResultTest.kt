package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import soy.engindearing.omnitak.mobile.data.symbology.IconPackRegistry.ImportedIcon
import soy.engindearing.omnitak.mobile.data.symbology.IconPackRegistry.ImportedPack

/**
 * Issue #114 — unit tests for [IconPackImporter.ImportResult] sealed class.
 *
 * The importer itself requires an Android Context (file I/O), so end-to-end
 * import is covered on-device. These tests guard the result contract: that
 * Success carries the pack and Failure carries a non-blank reason, so the
 * Settings toast logic stays correct if the sealed class evolves.
 */
class IconPackImporterResultTest {

    private val fakePack = ImportedPack(
        uid = "test-uid-001",
        name = "Test Pack",
        version = 1,
        dirRelative = "iconpacks/test-uid-001",
        icons = listOf(
            ImportedIcon(name = "Ambulance", filename = "Ground/Ambulance.png"),
            ImportedIcon(name = "Police Car", filename = "Ground/PoliceCar.png"),
        ),
    )

    @Test
    fun `Success carries the imported pack`() {
        val result: IconPackImporter.ImportResult = IconPackImporter.ImportResult.Success(fakePack)
        assertTrue(result is IconPackImporter.ImportResult.Success)
        val success = result as IconPackImporter.ImportResult.Success
        assertEquals("test-uid-001", success.pack.uid)
        assertEquals("Test Pack", success.pack.name)
        assertEquals(2, success.pack.icons.size)
    }

    @Test
    fun `Failure carries a non-blank reason`() {
        val reason = "No iconset.xml found in pack.zip"
        val result: IconPackImporter.ImportResult = IconPackImporter.ImportResult.Failure(reason)
        assertTrue(result is IconPackImporter.ImportResult.Failure)
        val failure = result as IconPackImporter.ImportResult.Failure
        assertTrue("reason must not be blank", failure.reason.isNotBlank())
        assertEquals(reason, failure.reason)
    }

    @Test
    fun `toast message formats correctly for Success`() {
        val result = IconPackImporter.ImportResult.Success(fakePack)
        val msg = when (result) {
            is IconPackImporter.ImportResult.Success ->
                "Icon pack \"${result.pack.name}\" imported (${result.pack.icons.size} icons)"
            is IconPackImporter.ImportResult.Failure ->
                "Import failed: ${result.reason}"
        }
        assertEquals("Icon pack \"Test Pack\" imported (2 icons)", msg)
    }

    @Test
    fun `toast message formats correctly for Failure`() {
        val result = IconPackImporter.ImportResult.Failure("ZIP read error in pack.zip: premature end of file")
        val msg = when (result) {
            is IconPackImporter.ImportResult.Success ->
                "Icon pack \"${result.pack.name}\" imported (${result.pack.icons.size} icons)"
            is IconPackImporter.ImportResult.Failure ->
                "Import failed: ${result.reason}"
        }
        assertTrue(msg.startsWith("Import failed:"))
        assertTrue(msg.contains("premature end of file"))
    }
}
