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
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #215: the `contactMaxAgeMinutes` preference: whole minutes, default 30, 0 = Never.
 * Runs the real [UserPrefsStore] against a file-backed DataStore on the JVM.
 */
class UserPrefsContactMaxAgeTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dsScope: CoroutineScope
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var store: UserPrefsStore

    // The key as it sits on disk. Locked here: renaming it would silently reset every saved choice.
    private val key = intPreferencesKey("contact_max_age_minutes")

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

    @Test fun the_default_is_30_minutes() {
        assertEquals(30, UserPrefs().contactMaxAgeMinutes)
        assertEquals(30, ContactMaxAge.DEFAULT_MINUTES)
    }

    @Test fun a_fresh_install_reads_the_default() = runBlocking {
        assertEquals(30, store.prefs.first().contactMaxAgeMinutes)
    }

    @Test fun an_install_that_saved_other_settings_before_this_one_existed_reads_the_default() = runBlocking {
        store.update { it.copy(callsign = "ALPHA-1", team = "Red", stalenessOverlayEnabled = true) }
        // Take the key away again, as a saved file from the version before this setting would be.
        dataStore.edit { it.remove(key) }

        assertEquals(30, store.prefs.first().contactMaxAgeMinutes)
        assertEquals("ALPHA-1", store.prefs.first().callsign)
    }

    @Test fun every_choice_the_setting_offers_round_trips() = runBlocking {
        for (minutes in ContactMaxAge.CHOICES) {
            store.update { it.copy(contactMaxAgeMinutes = minutes) }
            assertEquals("$minutes", minutes, store.prefs.first().contactMaxAgeMinutes)
        }
    }

    @Test fun never_is_saved_as_0_and_is_not_replaced_by_the_default() = runBlocking {
        store.update { it.copy(contactMaxAgeMinutes = ContactMaxAge.NEVER) }

        assertEquals(0, store.prefs.first().contactMaxAgeMinutes)
        assertEquals(0, dataStore.data.first()[key])
    }

    @Test fun it_is_stored_as_whole_minutes_under_its_own_key() = runBlocking {
        store.update { it.copy(contactMaxAgeMinutes = 5) }

        assertEquals(5, dataStore.data.first()[key])
    }

    @Test fun a_value_that_is_not_one_of_the_choices_is_kept_as_it_is() = runBlocking {
        // A profile or a later build can carry any whole number of minutes.
        store.update { it.copy(contactMaxAgeMinutes = 45) }

        assertEquals(45, store.prefs.first().contactMaxAgeMinutes)
    }

    @Test fun a_negative_value_reads_as_never() = runBlocking {
        dataStore.edit { it[key] = -7 }

        assertEquals(0, store.prefs.first().contactMaxAgeMinutes)
    }

    @Test fun writing_other_settings_leaves_it_alone() = runBlocking {
        store.update { it.copy(contactMaxAgeMinutes = 10) }
        store.update { it.copy(callsign = "BRAVO-9", stalenessOverlayEnabled = true) }
        store.setMeshNodesLayerVisible(false)

        assertEquals(10, store.prefs.first().contactMaxAgeMinutes)
    }

    @Test fun writing_it_leaves_other_settings_alone() = runBlocking {
        store.update { it.copy(callsign = "BRAVO-9", team = "Red", stalenessOverlayEnabled = true) }
        store.update { it.copy(contactMaxAgeMinutes = 120) }

        val prefs = store.prefs.first()
        assertEquals("BRAVO-9", prefs.callsign)
        assertEquals("Red", prefs.team)
        assertEquals(true, prefs.stalenessOverlayEnabled)
        assertEquals(120, prefs.contactMaxAgeMinutes)
    }
}
