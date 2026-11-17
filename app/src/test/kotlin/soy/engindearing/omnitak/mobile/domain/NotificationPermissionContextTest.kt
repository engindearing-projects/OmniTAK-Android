package soy.engindearing.omnitak.mobile.domain

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import soy.engindearing.omnitak.mobile.ui.onboarding.OnboardingManager

/**
 * [NotificationPermission.isGranted] and [NotificationPermission.takeTurnToAsk] against a phone, at the Android
 * versions the rule is about: below 13 there is no permission to ask for, from 13 there is, and the question is
 * used up only by the screen that really asks. [NotificationPermissionTest] covers the rule itself; this is what
 * reads the phone and writes the "asked" flag.
 */
@RunWith(AndroidJUnit4::class)
// A plain Application: the app's own starts the map's native library, which has no JVM build.
@Config(application = Application::class)
class NotificationPermissionContextTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    private fun allow() = shadowOf(context as Application).grantPermissions(NotificationPermission.PERMISSION)

    // ---- isGranted

    @Test @Config(sdk = [32]) fun before_android_13_notifications_need_no_permission_so_it_counts_as_granted_with_none_given() {
        assertTrue(NotificationPermission.isGranted(context))
    }

    @Test @Config(sdk = [32]) fun before_android_13_it_counts_as_granted_even_with_notifications_switched_off_in_settings() {
        // There is no permission to ask for there: the switch in the system settings is the person's, not a question
        // the app can put (the support library would answer "denied" for it, and the app must not).
        shadowOf(context.getSystemService(android.app.NotificationManager::class.java)).setNotificationsEnabled(false)
        assertTrue(NotificationPermission.isGranted(context))
        OnboardingManager.markComplete(context)
        assertFalse("and there is still nothing to ask", NotificationPermission.takeTurnToAsk(context))
    }

    @Test @Config(sdk = [33]) fun on_android_13_it_is_not_granted_until_the_phone_says_so() {
        assertFalse(NotificationPermission.isGranted(context))
        allow()
        assertTrue(NotificationPermission.isGranted(context))
    }

    @Test @Config(sdk = [35]) fun the_same_on_a_later_android() {
        assertFalse(NotificationPermission.isGranted(context))
        allow()
        assertTrue(NotificationPermission.isGranted(context))
    }

    // ---- takeTurnToAsk

    @Test @Config(sdk = [34]) fun the_first_screen_to_ask_after_onboarding_is_told_to_and_is_the_only_one() {
        OnboardingManager.markComplete(context)
        assertFalse("nobody has asked yet", NotificationPermission.wasAsked(context))
        assertTrue("the chat tab, say, is told to show the prompt", NotificationPermission.takeTurnToAsk(context))
        assertTrue("and it is recorded", NotificationPermission.wasAsked(context))
        assertFalse("the geofence form after it is not: a person is asked once", NotificationPermission.takeTurnToAsk(context))
    }

    @Test @Config(sdk = [34]) fun a_person_who_said_no_is_not_asked_again_and_nothing_resets_the_flag() {
        OnboardingManager.markComplete(context)
        assertTrue(NotificationPermission.takeTurnToAsk(context))
        // ... the system prompt was refused: still not granted.
        repeat(3) { assertFalse(NotificationPermission.takeTurnToAsk(context)) }
        assertTrue(NotificationPermission.wasAsked(context))
    }

    @Test @Config(sdk = [34]) fun during_onboarding_it_does_not_ask_and_the_question_is_not_used_up() {
        assertFalse("the Welcome page is not a place to ask", NotificationPermission.takeTurnToAsk(context))
        assertFalse("and nothing is recorded", NotificationPermission.wasAsked(context))
        OnboardingManager.markComplete(context)
        assertTrue("so the first screen after onboarding still gets to", NotificationPermission.takeTurnToAsk(context))
    }

    @Test @Config(sdk = [34]) fun when_it_is_already_allowed_it_neither_asks_nor_uses_the_question_up() {
        OnboardingManager.markComplete(context)
        allow()
        assertFalse(NotificationPermission.takeTurnToAsk(context))
        assertFalse(NotificationPermission.wasAsked(context))
    }

    @Test @Config(sdk = [32]) fun before_android_13_it_never_asks_and_never_records_a_question() {
        OnboardingManager.markComplete(context)
        assertFalse(NotificationPermission.takeTurnToAsk(context))
        assertFalse(NotificationPermission.wasAsked(context))
    }

    @Test @Config(sdk = [34]) fun the_flag_is_shared_by_every_place_that_asks() {
        OnboardingManager.markComplete(context)
        NotificationPermission.markAsked(context)
        assertEquals(false, NotificationPermission.takeTurnToAsk(context))
    }
}
