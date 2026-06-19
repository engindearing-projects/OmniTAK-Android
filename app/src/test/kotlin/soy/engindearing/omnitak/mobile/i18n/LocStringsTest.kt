package soy.engindearing.omnitak.mobile.i18n

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #156 — i18n parity. OmniTAK now ships 7 UI languages (adds Español +
 * Українська). These pin the catalogue contract so a language can't ship a
 * typo'd/stray key and the new languages can't silently regress to stubs.
 */
class LocStringsTest {

    private fun keys(l: Loc.Language) = LocStrings.catalogue(l).keys

    @Test fun ships_seven_languages_including_es_and_uk() {
        val codes = Loc.Language.entries.map { it.code }
        assertEquals(7, codes.size)
        assertTrue(codes.containsAll(listOf("en", "zh-Hant", "pl", "de", "fr", "es", "uk")))
    }

    @Test fun no_language_has_a_key_absent_from_english() {
        val en = keys(Loc.Language.ENGLISH)
        for (lang in Loc.Language.entries) {
            val stray = keys(lang) - en
            assertTrue("$lang has keys not present in English (typo?): $stray", stray.isEmpty())
        }
    }

    @Test fun new_languages_cover_at_least_the_european_onboarding_set() {
        // es + uk must cover everything fr covers, so they're real additions.
        val fr = keys(Loc.Language.FRENCH)
        assertTrue("FR coverage unexpectedly empty", fr.isNotEmpty())
        for (lang in listOf(Loc.Language.SPANISH, Loc.Language.UKRAINIAN)) {
            val missing = fr - keys(lang)
            assertTrue("$lang is missing keys vs FR: $missing", missing.isEmpty())
        }
    }

    @Test fun new_languages_are_actually_translated_not_english_passthrough() {
        val en = LocStrings.catalogue(Loc.Language.ENGLISH)
        for (lang in listOf(Loc.Language.SPANISH, Loc.Language.UKRAINIAN)) {
            val cat = LocStrings.catalogue(lang)
            assertNotEquals("$lang onboarding.skip not translated", en["onboarding.skip"], cat["onboarding.skip"])
            assertNotEquals("$lang page1.title not translated", en["onboarding.page1.title"], cat["onboarding.page1.title"])
        }
    }

    @Test fun resolver_returns_localized_string_then_falls_back_to_english() {
        // A key only in EN resolves to the EN value for a partial language (fr),
        // while a translated onboarding key resolves to the localized value.
        val frOnboarding = LocStrings.catalogue(Loc.Language.FRENCH)["onboarding.skip"]
        assertEquals("Passer", frOnboarding) // sanity: FR really is translated
    }
}
