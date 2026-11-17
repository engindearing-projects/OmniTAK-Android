package soy.engindearing.omnitak.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #214 - the screen rotation preference, round-tripped through the real store on a
 * file-backed DataStore: the default, every mode, a stored value that is not a mode, and the
 * shortcut's cycle. The key name and the stored strings are part of the contract (the iOS
 * build stores the same three strings under the same name).
 */
class UserPrefsScreenRotationTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dsScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: UserPrefsStore

    // The stored name, spelled out here on purpose.
    private val rawKey = stringPreferencesKey("screen_rotation")

    @Before fun setUp() {
        dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        dataStore = PreferenceDataStoreFactory.create(
            scope = dsScope,
            produceFile = { File(tmp.root, "user_prefs.preferences_pb") },
        )
        store = UserPrefsStore(dataStore)
    }

    @After fun tearDown() {
        dsScope.cancel()
    }

    private suspend fun stored(): String? = dataStore.data.first()[rawKey]

    private suspend fun writeRaw(value: String) {
        dataStore.edit { it[rawKey] = value }
    }

    @Test fun a_store_with_nothing_written_reads_auto() = runBlocking {
        assertSame(ScreenRotation.AUTO, store.prefs.first().screenRotation)
        assertNull("nothing is stored until the operator picks a mode", stored())
    }

    @Test fun every_mode_round_trips() = runBlocking {
        for (mode in ScreenRotation.entries) {
            store.update { it.copy(screenRotation = mode) }
            assertSame("read back $mode", mode, store.prefs.first().screenRotation)
        }
    }

    @Test fun each_mode_is_stored_as_its_string_under_screen_rotation() = runBlocking {
        store.update { it.copy(screenRotation = ScreenRotation.AUTO) }
        assertEquals("auto", stored())
        store.update { it.copy(screenRotation = ScreenRotation.PORTRAIT) }
        assertEquals("portrait", stored())
        store.update { it.copy(screenRotation = ScreenRotation.LANDSCAPE) }
        assertEquals("landscape", stored())
    }

    @Test fun a_stored_value_that_is_not_a_mode_reads_as_auto() = runBlocking {
        for (raw in listOf("sideways", "", "PORTRAIT", "Landscape", "6", "auto,portrait")) {
            writeRaw(raw)
            assertSame("stored <$raw>", ScreenRotation.AUTO, store.prefs.first().screenRotation)
        }
    }

    @Test fun a_stored_value_that_is_not_a_mode_is_replaced_by_a_valid_one_on_the_next_write() = runBlocking {
        writeRaw("sideways")
        store.update { it.copy(callsign = "ALPHA-1") }
        assertEquals("auto", stored())
    }

    @Test fun changing_the_mode_leaves_other_prefs_alone() = runBlocking {
        store.update { it.copy(callsign = "BRAVO-2", team = "Red", keepScreenOn = true) }
        store.update { it.copy(screenRotation = ScreenRotation.LANDSCAPE) }
        val p = store.prefs.first()
        assertSame(ScreenRotation.LANDSCAPE, p.screenRotation)
        assertEquals("BRAVO-2", p.callsign)
        assertEquals("Red", p.team)
        assertEquals(true, p.keepScreenOn)
    }

    @Test fun changing_other_prefs_keeps_the_mode() = runBlocking {
        store.update { it.copy(screenRotation = ScreenRotation.PORTRAIT) }
        store.update { it.copy(callsign = "ALPHA-1", keepScreenOn = true) }
        assertSame(ScreenRotation.PORTRAIT, store.prefs.first().screenRotation)
    }

    // --- the shortcut's cycle ------------------------------------------------

    @Test fun the_cycle_goes_portrait_landscape_auto_and_stores_each_step() = runBlocking {
        assertSame(ScreenRotation.PORTRAIT, store.cycleScreenRotation())
        assertEquals("portrait", stored())
        assertSame(ScreenRotation.LANDSCAPE, store.cycleScreenRotation())
        assertEquals("landscape", stored())
        assertSame(ScreenRotation.AUTO, store.cycleScreenRotation())
        assertEquals("auto", stored())
        assertSame(ScreenRotation.PORTRAIT, store.cycleScreenRotation())
    }

    @Test fun the_cycle_starts_from_the_stored_mode() = runBlocking {
        store.update { it.copy(screenRotation = ScreenRotation.LANDSCAPE) }
        assertSame(ScreenRotation.AUTO, store.cycleScreenRotation())
    }

    @Test fun the_cycle_from_a_stored_value_that_is_not_a_mode_starts_from_auto() = runBlocking {
        writeRaw("sideways")
        assertSame(ScreenRotation.PORTRAIT, store.cycleScreenRotation())
    }

    @Test fun the_cycle_leaves_other_prefs_alone() = runBlocking {
        store.update { it.copy(callsign = "CHARLIE-3", stalenessOverlayEnabled = true) }
        store.cycleScreenRotation()
        val p = store.prefs.first()
        assertEquals("CHARLIE-3", p.callsign)
        assertEquals(true, p.stalenessOverlayEnabled)
    }

    @Test fun quick_taps_each_step_the_mode_once_none_start_from_a_stale_mode() = runBlocking {
        // Make the file first: a read that overlaps the very first write is a separate,
        // known DataStore problem (it is what the self uid test trips), not what this tests.
        store.update { it.copy(callsign = "ALPHA-1") }
        val taps = 9
        val returned = (1..taps).map {
            async(Dispatchers.Default) { store.cycleScreenRotation() }
        }.awaitAll()
        // Nine steps from Auto: each of the three modes comes back exactly three times and
        // the stored mode is nine steps along, which is Auto again. A tap that read the mode
        // before an earlier tap had written would repeat one and skip another.
        assertEquals(3, returned.count { it == ScreenRotation.PORTRAIT })
        assertEquals(3, returned.count { it == ScreenRotation.LANDSCAPE })
        assertEquals(3, returned.count { it == ScreenRotation.AUTO })
        assertSame(ScreenRotation.AUTO, store.prefs.first().screenRotation)
    }
}
