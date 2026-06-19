package soy.engindearing.omnitak.mobile.i18n

import android.content.Context
import android.content.res.Resources
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * Runtime language switching without an Activity restart — the Android
 * counterpart to the iOS `LocalizationManager`.
 *
 * Android's native per-locale resources (`values-zh-rTW/`) only take
 * effect on Activity recreation. This manager instead keeps the active
 * language in a Compose snapshot state and resolves keys against an
 * in-memory catalogue ([LocStrings]), so flipping the picker in Settings
 * re-renders every observing composable instantly — the same UX iOS gets
 * from its `@Published` language switch.
 *
 * ## Usage
 *     Text(Loc.t("settings.callsign"))
 *
 * Because [t] reads [current] (a snapshot state) during composition,
 * changing the language via [setLanguage] recomposes every caller. Non
 * composable callers (services, formatters) can call [t] too — they just
 * won't auto-refresh on a live switch, which is fine for one-shot strings.
 *
 * English is the base catalogue every other language falls back to for
 * keys it hasn't translated yet, matching the iOS fallback chain
 * (active → English → key itself).
 */
object Loc {

    /** Languages OmniTAK ships UI translations for. Mirrors the iOS
     *  `LocalizationManager.Language` cases we have Android catalogues
     *  for; adding a language is an enum case plus a [LocStrings] map. */
    enum class Language(val code: String, val displayName: String, val flag: String) {
        // displayName is the endonym — the language's own name, which is
        // what users scanning a language list expect to see.
        ENGLISH("en", "English", "🇬🇧"),
        TRADITIONAL_CHINESE("zh-Hant", "繁體中文", "🇹🇼"),
        POLISH("pl", "Polski", "🇵🇱"),
        GERMAN("de", "Deutsch", "🇩🇪"),
        FRENCH("fr", "Français", "🇫🇷"),
        SPANISH("es", "Español", "🇪🇸"),
        UKRAINIAN("uk", "Українська", "🇺🇦");

        companion object {
            fun fromCode(code: String?): Language? =
                entries.firstOrNull { it.code == code }
        }
    }

    private const val PREFS = "omnitak_locale"
    private const val KEY_LANGUAGE = "app_language"

    private var appContext: Context? = null

    /** The active language. Reads inside composition are tracked by the
     *  Compose snapshot system, so a change recomposes every view that
     *  resolved a string through [t]. */
    var current by mutableStateOf(Language.ENGLISH)
        private set

    /** Initialise once at process start (Application.onCreate), before any
     *  UI composes. Restores the persisted choice, else best-effort matches
     *  the device locale, else English. */
    fun init(context: Context) {
        val ctx = context.applicationContext
        appContext = ctx
        val stored = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_LANGUAGE, null)
        current = Language.fromCode(stored) ?: systemDefault()
    }

    /** Switch the app's language. Persists the choice; the snapshot-state
     *  write re-renders every observing composable immediately. */
    fun setLanguage(language: Language) {
        if (language == current) return
        current = language
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            ?.edit()
            ?.putString(KEY_LANGUAGE, language.code)
            ?.apply()
    }

    /** Resolve [key] against the active language, falling back to the
     *  English base catalogue, then to the key itself (so a missing string
     *  is visible in testing rather than rendering blank). */
    fun t(key: String): String {
        LocStrings.catalogue(current)[key]?.let { return it }
        if (current != Language.ENGLISH) {
            LocStrings.catalogue(Language.ENGLISH)[key]?.let { return it }
        }
        return key
    }

    /** Resolve a key carrying `String.format` placeholders (`%d`, `%s`). */
    fun t(key: String, vararg args: Any?): String =
        String.format(t(key), *args)

    /** Best-effort match of the device's preferred locales to a language we
     *  ship. A `zh-Hant` / `zh-TW` / `zh-HK` / `zh-MO` device resolves to
     *  Traditional Chinese; a Simplified (`zh-Hans` / `zh-CN`) device falls
     *  through to English since we don't ship Simplified. `pl`, `de`, and
     *  `fr` device locales map to their respective shipped catalogues. */
    private fun systemDefault(): Language {
        val locales = Resources.getSystem().configuration.locales
        for (i in 0 until locales.size()) {
            val locale = locales[i] ?: continue
            val lang = locale.language.lowercase()
            if (lang == "zh") {
                val isTraditional = locale.script.equals("Hant", ignoreCase = true) ||
                    locale.country.uppercase() in TRADITIONAL_REGIONS
                if (isTraditional) return Language.TRADITIONAL_CHINESE
                continue // Simplified Chinese — we don't ship it; keep scanning.
            }
            when (lang) {
                "pl" -> return Language.POLISH
                "de" -> return Language.GERMAN
                "fr" -> return Language.FRENCH
            }
            Language.fromCode(lang)?.let { return it }
        }
        return Language.ENGLISH
    }

    private val TRADITIONAL_REGIONS = setOf("TW", "HK", "MO")
}
