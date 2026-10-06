package soy.engindearing.omnitak.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #261 - the Meshtastic TCP gateway the operator last connected to, and whether
 * they want that link up, against a real file-backed DataStore. [restart] closes
 * the store and opens the same file again, which is what a process restart does,
 * so these show the values surviving a restart and not only a read-back in the
 * same session.
 */
class UserPrefsMeshTcpTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dsScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: UserPrefsStore

    private fun open() {
        dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dsScope,
            produceFile = { File(tmp.root, "user_prefs.preferences_pb") },
        )
        store = UserPrefsStore(dataStore)
    }

    /** Close the DataStore and open the same file again. */
    private fun restart() {
        runBlocking { dsScope.coroutineContext.job.cancelAndJoin() }
        open()
    }

    @Before fun setUp() = open()

    @After fun tearDown() {
        dsScope.cancel()
    }

    // region defaults

    @Test fun `the old built-in address is the default`() {
        assertEquals("192.168.1.100", DEFAULT_MESH_TCP_HOST)
        assertEquals(4403, DEFAULT_MESH_TCP_PORT)
        val fresh = UserPrefs()
        assertEquals("192.168.1.100", fresh.meshTcpHost)
        assertEquals(4403, fresh.meshTcpPort)
        assertFalse("nobody has asked for a link yet", fresh.meshTcpWanted)
    }

    @Test fun `a store that was never written gives the old gateway`() = runBlocking {
        val p = store.prefs.first()
        assertEquals("192.168.1.100", p.meshTcpHost)
        assertEquals(4403, p.meshTcpPort)
        assertFalse(p.meshTcpWanted)
    }

    @Test fun `a store written before these keys existed gives the old gateway`() = runBlocking {
        // An install that upgrades: other keys are on disk, the three new ones are absent.
        dataStore.edit { it[stringPreferencesKey("callsign")] = "ALPHA-1" }
        restart()
        val p = store.prefs.first()
        assertEquals("the existing key is untouched", "ALPHA-1", p.callsign)
        assertEquals("192.168.1.100", p.meshTcpHost)
        assertEquals(4403, p.meshTcpPort)
        assertFalse(p.meshTcpWanted)
    }

    // endregion

    // region Connect and Disconnect

    @Test fun `connect keeps the host the port and the wanted flag across a restart`() = runBlocking {
        // A port that is not the default, so a port that is not saved shows up.
        store.rememberMeshTcpGateway("127.0.0.1", 14403)
        restart()
        val p = store.prefs.first()
        assertEquals("127.0.0.1", p.meshTcpHost)
        assertEquals(14403, p.meshTcpPort)
        assertTrue("Connect marks the link as wanted", p.meshTcpWanted)
    }

    @Test fun `disconnect clears the wanted flag and keeps the saved gateway`() = runBlocking {
        store.rememberMeshTcpGateway("127.0.0.1", 14403)
        store.setMeshTcpWanted(false)
        restart()
        val p = store.prefs.first()
        assertFalse("Disconnect is the operator ending the link", p.meshTcpWanted)
        assertEquals("the fields keep their text", "127.0.0.1", p.meshTcpHost)
        assertEquals(14403, p.meshTcpPort)
    }

    @Test fun `connecting again after a disconnect wants the link with the new gateway`() = runBlocking {
        store.rememberMeshTcpGateway("127.0.0.1", 14403)
        store.setMeshTcpWanted(false)
        store.rememberMeshTcpGateway("10.9.8.7", 4404)
        restart()
        val p = store.prefs.first()
        assertEquals("10.9.8.7", p.meshTcpHost)
        assertEquals(4404, p.meshTcpPort)
        assertTrue(p.meshTcpWanted)
    }

    @Test fun `the host is saved without the spaces around it`() = runBlocking {
        store.rememberMeshTcpGateway("  10.1.2.3 ", 4403)
        assertEquals("10.1.2.3", store.prefs.first().meshTcpHost)
    }

    @Test fun `a blank host is not saved and does not want the link`() = runBlocking {
        store.rememberMeshTcpGateway("   ", 14403)
        val p = store.prefs.first()
        assertEquals("192.168.1.100", p.meshTcpHost)
        assertEquals(4403, p.meshTcpPort)
        assertFalse(p.meshTcpWanted)
    }

    @Test fun `a blank host leaves an earlier gateway alone`() = runBlocking {
        store.rememberMeshTcpGateway("10.1.2.3", 14403)
        store.rememberMeshTcpGateway("", 4403)
        val p = store.prefs.first()
        assertEquals("10.1.2.3", p.meshTcpHost)
        assertEquals(14403, p.meshTcpPort)
        assertTrue(p.meshTcpWanted)
    }

    // endregion

    // The app writes prefs all the time (self fix, camera, toggles), each write
    // through update(), which reads every key and writes every key back. If the
    // gateway were not read back, the first such write would reset it.
    @Test fun `writing unrelated prefs keeps the gateway and the wanted flag`() = runBlocking {
        store.rememberMeshTcpGateway("10.1.2.3", 14403)
        store.update { it.copy(callsign = "ALPHA-1", broadcastOverMesh = false) }
        store.setLastCamera(47.6, -117.4, 11.0)
        restart()
        val p = store.prefs.first()
        assertEquals("10.1.2.3", p.meshTcpHost)
        assertEquals(14403, p.meshTcpPort)
        assertTrue(p.meshTcpWanted)
        assertEquals("ALPHA-1", p.callsign)
    }

    @Test fun `the gateway does not touch the selected mesh framework`() = runBlocking {
        store.setSelectedMeshFramework(MeshFramework.MESHCORE)
        store.rememberMeshTcpGateway("10.1.2.3", 14403)
        store.setMeshTcpWanted(false)
        assertEquals(MeshFramework.MESHCORE, store.prefs.first().selectedMeshFramework)
    }
}
