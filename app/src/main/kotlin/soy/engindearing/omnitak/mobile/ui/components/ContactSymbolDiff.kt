package soy.engindearing.omnitak.mobile.ui.components

/**
 * Issue #77 — pure add / update / remove diff for the contact
 * [org.maplibre.android.plugins.annotation.SymbolManager].
 *
 * Recreating every [org.maplibre.android.plugins.annotation.Symbol] on each
 * contact-store change (delete-all + re-add) churns the annotation source on
 * every CoT tick and makes taps race the rebuild. Instead we diff the desired
 * [ContactSymbolSpec] set against what is currently on the map, keyed by
 * [ContactSymbolSpec.uid], and emit the minimal mutation set.
 *
 * This is the testable heart of the fix: no MapLibre / Android types are in
 * scope, so the whole add/update/remove decision is verified in pure JVM. The
 * live [ContactSymbolManager] is then a thin adapter that applies these ops to
 * the real SymbolManager.
 */
object ContactSymbolDiff {

    /**
     * @param current spec currently rendered for each live symbol, keyed by uid.
     *   ([ContactSymbolManager] holds the matching uid→Symbol handles.)
     * @param target  spec the contact store now wants on the map.
     */
    data class Result(
        /** uids present in [target] but not [current] — create a new Symbol. */
        val toAdd: List<ContactSymbolSpec>,
        /** uids whose spec changed (position / icon / callsign / color) — restyle
         *  the existing Symbol in place rather than add+remove. */
        val toUpdate: List<ContactSymbolSpec>,
        /** uids present in [current] but gone from [target] — remove the Symbol. */
        val toRemove: List<String>,
    ) {
        /** True when nothing changed — lets the caller skip a SymbolManager
         *  round-trip (and the implicit source re-upload) entirely. */
        val isEmpty: Boolean
            get() = toAdd.isEmpty() && toUpdate.isEmpty() && toRemove.isEmpty()
    }

    /**
     * Compute the minimal mutation set to take the map from [current] to [target].
     *
     * Update detection is a structural equality check on [ContactSymbolSpec]:
     * any change to position, icon image, callsign, team color, or tint flag
     * yields an update; an unchanged spec is omitted so we don't re-push a
     * Symbol that already matches. If a uid appears more than once in [target]
     * the last occurrence wins (mirrors a `Map` collapse — a contact store
     * should not hold duplicate uids, but we must not emit two adds for one).
     */
    fun compute(
        current: Map<String, ContactSymbolSpec>,
        target: Collection<ContactSymbolSpec>,
    ): Result {
        // Collapse duplicate uids (last wins) and preserve first-seen order so
        // the add/update output is deterministic for tests and stable on screen.
        val targetByUid = LinkedHashMap<String, ContactSymbolSpec>(target.size)
        for (spec in target) targetByUid[spec.uid] = spec

        val toAdd = ArrayList<ContactSymbolSpec>()
        val toUpdate = ArrayList<ContactSymbolSpec>()
        for ((uid, spec) in targetByUid) {
            val existing = current[uid]
            when {
                existing == null -> toAdd.add(spec)
                existing != spec -> toUpdate.add(spec)
                // else: identical — no-op, intentionally skipped.
            }
        }

        val toRemove = ArrayList<String>()
        for (uid in current.keys) {
            if (uid !in targetByUid) toRemove.add(uid)
        }

        return Result(toAdd = toAdd, toUpdate = toUpdate, toRemove = toRemove)
    }
}
