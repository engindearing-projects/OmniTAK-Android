package soy.engindearing.omnitak.mobile.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #205 - the wire identity must be minted exactly once. Several first-run
 * callers can ask for it at the same moment (the PPLI broadcaster when a link
 * comes up, the chat screen, the map's marker drop, mission sync). The old
 * ensureSelfUid read the uid outside the DataStore edit and wrote it
 * unconditionally, so two callers could each mint an ANDROID-<uuid>, each
 * return its own, and leave only the last writer's uid on disk - the first
 * caller's messages then went out under an identity the device no longer
 * claimed. These run the real store against a file-backed DataStore.
 */
class UserPrefsStoreSelfUidTest {

    @get:Rule val tmp = TemporaryFolder()

    private lateinit var dsScope: CoroutineScope
    private lateinit var store: UserPrefsStore

    @Before fun setUp() {
        dsScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        store = UserPrefsStore(
            PreferenceDataStoreFactory.create(
                scope = dsScope,
                produceFile = { File(tmp.root, "user_prefs.preferences_pb") },
            ),
        )
    }

    @After fun tearDown() {
        dsScope.cancel()
    }

    @Test fun first_call_mints_an_ANDROID_uid_and_persists_it() = runBlocking {
        val uid = store.ensureSelfUid()
        assertTrue("uid must use the ANDROID- prefix, was $uid", uid.startsWith("ANDROID-"))
        assertEquals("the minted uid must be the persisted one", uid, store.prefs.first().selfUid)
    }

    @Test fun later_calls_return_the_same_uid() = runBlocking {
        val first = store.ensureSelfUid()
        assertEquals(first, store.ensureSelfUid())
        assertEquals(first, store.ensureSelfUid())
        assertEquals(first, store.prefs.first().selfUid)
    }

    @Test fun existing_uid_is_never_replaced() = runBlocking {
        store.update { it.copy(selfUid = "ANDROID-existing") }
        assertEquals("ANDROID-existing", store.ensureSelfUid())
        assertEquals("ANDROID-existing", store.prefs.first().selfUid)
    }

    @Test fun concurrent_first_run_callers_all_get_the_persisted_uid() = runBlocking {
        // Eight callers on real threads, nothing minted yet. Every one of
        // them must come back with one and the same uid, and it must be the
        // one that ended up on disk.
        val returned = (1..8).map {
            async(Dispatchers.Default) { store.ensureSelfUid() }
        }.awaitAll()
        val persisted = store.prefs.first().selfUid
        assertTrue("a uid must have been persisted", persisted.startsWith("ANDROID-"))
        assertEquals(
            "every first-run caller must see the persisted uid, got $returned vs $persisted",
            List(8) { persisted },
            returned,
        )
    }

    @Test fun ensureSelfUid_leaves_other_prefs_alone() = runBlocking {
        store.update { it.copy(callsign = "ALPHA-1", team = "Red", broadcastOverMesh = false) }
        store.ensureSelfUid()
        val prefs = store.prefs.first()
        assertEquals("ALPHA-1", prefs.callsign)
        assertEquals("Red", prefs.team)
        assertEquals(false, prefs.broadcastOverMesh)
    }
}
