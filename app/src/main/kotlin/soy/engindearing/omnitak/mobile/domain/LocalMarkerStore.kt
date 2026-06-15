package soy.engindearing.omnitak.mobile.domain

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import soy.engindearing.omnitak.mobile.data.CoTEvent

/**
 * #119 — Persists locally-dropped point markers across process death.
 *
 * Locally-dropped markers (uid starts with "local-") live only in-memory
 * in [ContactStore] and are lost on process kill. This store writes the
 * local-only subset to a DataStore preferences blob (JSON) so they can be
 * re-ingested into [ContactStore] on cold start via [OmniTAKApp.onCreate].
 *
 * The codec — [encode] / [decode] — is exposed on the companion object so
 * unit tests can exercise serialisation without needing an Android context
 * or a real DataStore instance.
 */
class LocalMarkerStore(context: Context) {

    private val dataStore = context.localMarkerDataStore

    /** Persist [markers] to DataStore. Only entries whose uid starts with
     *  "local-" are written; callers are responsible for pre-filtering, but
     *  this method applies the guard defensively as well. */
    suspend fun persist(markers: List<CoTEvent>) {
        val localOnly = markers.filter { it.uid.startsWith("local-") }
        dataStore.edit { prefs ->
            prefs[KEY_MARKERS] = encode(localOnly)
        }
    }

    /** Load the persisted local markers. Returns an empty list when nothing
     *  has been stored yet or when the JSON blob is corrupt. */
    suspend fun load(): List<CoTEvent> {
        val prefs = dataStore.data.first()
        val raw = prefs[KEY_MARKERS] ?: return emptyList()
        return runCatching { decode(raw) }.getOrElse { emptyList() }
    }

    companion object {
        private val KEY_MARKERS = stringPreferencesKey("local_markers_json")

        /** Serialise [markers] to a compact JSON string. */
        fun encode(markers: List<CoTEvent>): String =
            Json.encodeToString(markers)

        /** Deserialise a JSON string produced by [encode] back to a list.
         *  Throws on malformed JSON — callers should wrap in runCatching. */
        fun decode(json: String): List<CoTEvent> =
            Json.decodeFromString(json)
    }
}

// One DataStore per application process — extension property keeps the
// singleton guarantee DataStore requires.
private val Context.localMarkerDataStore by preferencesDataStore(
    name = "local_markers",
)
