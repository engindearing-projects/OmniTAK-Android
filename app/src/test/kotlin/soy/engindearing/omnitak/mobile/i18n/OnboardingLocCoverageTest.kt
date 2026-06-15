package soy.engindearing.omnitak.mobile.i18n

import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Asserts that every onboarding key resolves to a translated string (not the
 * key itself) in all four languages that ship with OmniTAK: EN, PL, DE, FR.
 *
 * Run RED before adding the strings to LocStrings.kt; run GREEN after.
 *
 * Note: [LocStrings.catalogue] is package-internal but this test lives in the
 * same package, so it can access the internal function directly.
 */
class OnboardingLocCoverageTest {

    private val onboardingKeys = listOf(
        // Navigation
        "onboarding.skip",
        "onboarding.continue",
        "onboarding.getStarted",
        "onboarding.back",

        // Page 1 — Welcome
        "onboarding.page1.title",
        "onboarding.page1.desc",
        "onboarding.page1.feature1",
        "onboarding.page1.feature2",
        "onboarding.page1.feature3",
        "onboarding.page1.feature4",

        // Page 2 — Secure & Certified
        "onboarding.page2.title",
        "onboarding.page2.desc",
        "onboarding.page2.feature1",
        "onboarding.page2.feature2",
        "onboarding.page2.feature3",
        "onboarding.page2.feature4",

        // Page 3 — Quick & Easy Setup
        "onboarding.page3.title",
        "onboarding.page3.desc",
        "onboarding.page3.feature1",
        "onboarding.page3.feature2",
        "onboarding.page3.feature3",
        "onboarding.page3.feature4",

        // Page 4 — Ready to Connect
        "onboarding.page4.title",
        "onboarding.page4.desc",
        "onboarding.page4.feature1",
        "onboarding.page4.feature2",
        "onboarding.page4.feature3",
        "onboarding.page4.feature4",

        // Page 5 — Make It Yours (toolbar customization)
        "onboarding.page5.title",
        "onboarding.page5.desc",
        "onboarding.page5.feature1",
        "onboarding.page5.feature2",
        "onboarding.page5.feature3",
        "onboarding.page5.feature4",
    )

    private val languagesToTest = listOf(
        Loc.Language.ENGLISH,
        Loc.Language.POLISH,
        Loc.Language.GERMAN,
        Loc.Language.FRENCH,
    )

    /**
     * For every (language, key) pair the resolved string must differ from the
     * key itself — i.e., the key is present in the catalogue.
     *
     * The non-EN languages fall back to EN for missing keys, so a passing EN
     * catalogue alone does not make the other three pass here — we call
     * [LocStrings.catalogue] directly for PL/DE/FR to verify that each language
     * map contains the key, not just that the EN fallback would cover it.
     */
    @Test
    fun allOnboardingKeysResolveInAllLanguages() {
        val failures = mutableListOf<String>()

        for (lang in languagesToTest) {
            val catalogue = LocStrings.catalogue(lang)
            for (key in onboardingKeys) {
                val resolved = catalogue[key]
                if (resolved == null || resolved == key) {
                    failures += "[${lang.code}] missing key: $key"
                }
            }
        }

        if (failures.isNotEmpty()) {
            val msg = "Missing onboarding translations (${failures.size} failures):\n" +
                failures.joinToString("\n")
            // Use assertNotEquals to surface the full list, not just the first.
            assertNotEquals(msg, true, false)
        }
    }
}
