package soy.engindearing.omnitak.mobile.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #213 - the Label size preference, round-tripped through the real store on a
 * file-backed DataStore: the default, every choice, and a stored value that is
 * not one of the choices. The key name and type are part of the contract (the
 * iOS build stores the same whole percent under the same name).
 */
class UserPrefsLabelScaleTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dsScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: UserPrefsStore

    // The stored name and type, spelled out here on purpose.
    private val rawKey = intPreferencesKey("label_scale_percent")

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

    private suspend fun stored(): Int? = dataStore.data.first()[rawKey]

    private suspend fun writeRaw(value: Int) {
        dataStore.edit { it[rawKey] = value }
    }

    @Test fun the_default_is_100() {
        assertEquals(100, UserPrefs().labelScalePercent)
    }

    @Test fun a_store_with_nothing_written_reads_100() = runBlocking {
        assertEquals(100, store.prefs.first().labelScalePercent)
        assertNull("nothing is stored until the operator picks a size", stored())
    }

    @Test fun every_choice_round_trips() = runBlocking {
        for (choice in LabelSize.CHOICES) {
            store.update { it.copy(labelScalePercent = choice) }
            assertEquals("read back $choice", choice, store.prefs.first().labelScalePercent)
            assertEquals("stored $choice", choice, stored())
        }
    }

    @Test fun the_choice_is_stored_as_a_whole_percent_under_label_scale_percent() = runBlocking {
        store.update { it.copy(labelScalePercent = 140) }
        assertEquals(140, dataStore.data.first()[rawKey])
    }

    @Test fun a_stored_value_that_is_not_a_choice_reads_as_the_nearest_choice() = runBlocking {
        val expected = mapOf(
            0 to 80, -40 to 80, 79 to 80, 85 to 80,
            90 to 100, 99 to 100, 110 to 120, 119 to 120,
            125 to 120, 135 to 140, 155 to 160, 161 to 160, 999 to 160, Int.MAX_VALUE to 160,
        )
        for ((raw, want) in expected) {
            writeRaw(raw)
            assertEquals("stored $raw", want, store.prefs.first().labelScalePercent)
        }
    }

    @Test fun a_value_that_is_not_a_choice_is_corrected_when_written_through_update() = runBlocking {
        store.update { it.copy(labelScalePercent = 999) }
        assertEquals(160, stored())
        assertEquals(160, store.prefs.first().labelScalePercent)

        store.update { it.copy(labelScalePercent = -5) }
        assertEquals(80, stored())
        assertEquals(80, store.prefs.first().labelScalePercent)
    }

    @Test fun changing_other_prefs_keeps_the_label_size() = runBlocking {
        store.update { it.copy(labelScalePercent = 120) }
        store.update { it.copy(callsign = "ALPHA-1", stalenessOverlayEnabled = true) }
        val p = store.prefs.first()
        assertEquals(120, p.labelScalePercent)
        assertEquals("ALPHA-1", p.callsign)
    }

    @Test fun changing_the_label_size_leaves_other_prefs_alone() = runBlocking {
        store.update { it.copy(callsign = "BRAVO-2", team = "Red", stalenessOverlayEnabled = true) }
        store.update { it.copy(labelScalePercent = 160) }
        val p = store.prefs.first()
        assertEquals(160, p.labelScalePercent)
        assertEquals("BRAVO-2", p.callsign)
        assertEquals("Red", p.team)
        assertEquals(true, p.stalenessOverlayEnabled)
    }
}
