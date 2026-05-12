package soy.engindearing.omnitak.mobile.data

/**
 * Applies `tak://com.atakmap.app/preference?keyN=…&typeN=…&valueN=…`
 * payloads to [UserPrefs]. Lives next to UserPrefs so the canonical key
 * mapping is co-located with the schema.
 *
 * ATAK ships hundreds of preference keys; OmniTAK exposes a curated
 * subset documented in PREFERENCES.md. Anything outside the supported
 * set is silently dropped (logged by the caller) so a configuration
 * portal that targets full ATAK can still safely push a packet at us
 * without breaking the import.
 *
 * Pure: takes the parsed entries + a starting UserPrefs, returns the
 * mutated copy. The DataStore write lives in [UserPrefsStore.update]
 * which already serialises against concurrent edits.
 */
object PreferenceUriApplier {

    /**
     * Apply a batch of [DeepLinkAction.PreferenceEntry]s to [base]. The
     * returned pair carries the new prefs and the keys that were honoured
     * vs ignored so the caller can surface a useful toast.
     */
    fun apply(
        base: UserPrefs,
        entries: List<DeepLinkAction.PreferenceEntry>,
    ): ApplyResult {
        var next = base
        val applied = mutableListOf<String>()
        val ignored = mutableListOf<String>()

        for (entry in entries) {
            val mutated = applyOne(next, entry)
            if (mutated != null) {
                next = mutated
                applied += entry.key
            } else {
                ignored += entry.key
            }
        }
        return ApplyResult(next, applied, ignored)
    }

    data class ApplyResult(
        val prefs: UserPrefs,
        val applied: List<String>,
        val ignored: List<String>,
    )

    private fun applyOne(prefs: UserPrefs, entry: DeepLinkAction.PreferenceEntry): UserPrefs? {
        // Lowercase the OmniTAK-side key but keep the upstream ATAK names
        // as aliases. ATAK's `locationCallsign` / `locationTeam` /
        // `coord_display_format` map to our OmniTAK fields.
        return when (entry.key) {
            "callsign", "locationCallsign" -> prefs.copy(callsign = entry.value.trim())
            "team", "locationTeam" -> coerceTeam(entry.value)?.let { prefs.copy(team = it) }
            "coordFormat", "coord_display_format" -> coerceCoord(entry.value)?.let { prefs.copy(coordFormat = it) }
            "distanceUnit", "rangeSystem" -> coerceDistance(entry.value)?.let { prefs.copy(distanceUnit = it) }
            "mapProvider" -> coerceMap(entry.value)?.let { prefs.copy(mapProvider = it) }
            "customTileUrl" -> prefs.copy(customTileUrl = entry.value.trim())
            "autoPublishMeshToTak" -> coerceBool(entry.value)?.let { prefs.copy(autoPublishMeshToTak = it) }
            "meshNodesLayerVisible" -> coerceBool(entry.value)?.let { prefs.copy(meshNodesLayerVisible = it) }
            "callsignCardVisible" -> coerceBool(entry.value)?.let { prefs.copy(callsignCardVisible = it) }
            "gridEnabled" -> coerceBool(entry.value)?.let { prefs.copy(gridEnabled = it) }
            "drawingsVisible" -> coerceBool(entry.value)?.let { prefs.copy(drawingsVisible = it) }
            "aircraftVisible" -> coerceBool(entry.value)?.let { prefs.copy(aircraftVisible = it) }
            "contactsVisible" -> coerceBool(entry.value)?.let { prefs.copy(contactsVisible = it) }
            "followMeActive" -> coerceBool(entry.value)?.let { prefs.copy(followMeActive = it) }
            "configBundleUrl" -> prefs.copy(configBundleUrl = entry.value.trim())
            // mata's strategic ask: in-app reporting interval pushable from
            // a portal config. ATAK names two variants of this in its prefs
            // schema — accept either as an alias so a config bundle
            // generated against ATAK targets us too.
            "pliIntervalSecs", "dispatchLocationCotInterval", "locationReportingInterval" ->
                entry.value.trim().toIntOrNull()
                    ?.takeIf { it in 5..600 }
                    ?.let { prefs.copy(pliIntervalSecs = it) }
            "hideSelfFromMeshContacts" -> coerceBool(entry.value)?.let { prefs.copy(hideSelfFromMeshContacts = it) }
            "autoSyncCallsignToMesh" -> coerceBool(entry.value)?.let { prefs.copy(autoSyncCallsignToMesh = it) }
            // Step 2 of unified identity. No ATAK alias — TAK_TRACKER role
            // is the upstream equivalent and lives on the radio, not in
            // app prefs.
            "preferMeshFixForSelfPpli" -> coerceBool(entry.value)?.let { prefs.copy(preferMeshFixForSelfPpli = it) }
            "selfMeshFixFreshnessSecs" ->
                entry.value.trim().toIntOrNull()
                    ?.takeIf { it in 5..600 }
                    ?.let { prefs.copy(selfMeshFixFreshnessSecs = it) }
            "appMode" -> AppMode.fromWire(entry.value).let { prefs.copy(appMode = it) }
            else -> null
        }
    }

    private fun coerceBool(v: String): Boolean? = when (v.trim().lowercase()) {
        "true", "1", "yes", "on" -> true
        "false", "0", "no", "off" -> false
        else -> null
    }

    private val TEAM_COLORS = setOf(
        "WHITE", "YELLOW", "ORANGE", "MAGENTA", "RED", "MAROON", "PURPLE",
        "DARK BLUE", "BLUE", "CYAN", "TEAL", "GREEN", "DARK GREEN", "BROWN",
    )

    private fun coerceTeam(v: String): String? {
        val upper = v.trim().uppercase()
        return if (upper in TEAM_COLORS) upper else null
    }

    private fun coerceCoord(v: String): CoordFormat? = runCatching {
        CoordFormat.valueOf(v.trim().uppercase())
    }.getOrNull()

    private fun coerceDistance(v: String): DistanceUnit? = runCatching {
        DistanceUnit.valueOf(v.trim().uppercase())
    }.getOrNull()

    private fun coerceMap(v: String): MapProvider? = runCatching {
        MapProvider.valueOf(v.trim().uppercase())
    }.getOrNull()
}
