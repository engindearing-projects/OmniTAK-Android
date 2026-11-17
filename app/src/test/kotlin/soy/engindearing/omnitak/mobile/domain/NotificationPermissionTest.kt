package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * When the app asks for permission to show notifications. MeshChatNotifier posts a notification for every
 * incoming chat message but never asked: on Android 13 and later a notification from an app that has not
 * been allowed is dropped, so chat was silent unless the operator had saved a geofence first (the geofence
 * code was the only place that asked). One rule now covers every place that asks, with one "asked" flag
 * between them, so a person is asked once: when they open the Chat tab, or save their first geofence,
 * whichever comes first. Never before onboarding is done (the Welcome page and the pages after it), and
 * never at launch.
 */
class NotificationPermissionTest {

    private fun ask(sdkInt: Int = 36, granted: Boolean = false, alreadyAsked: Boolean = false, onboardingComplete: Boolean = true) =
        NotificationPermission.shouldAsk(sdkInt, granted, alreadyAsked, onboardingComplete)

    @Test fun it_asks_on_android_13_and_later_when_not_allowed_and_not_asked_before() {
        assertTrue(ask(sdkInt = 33))
        assertTrue(ask(sdkInt = 36))
    }

    @Test fun it_does_not_ask_where_notifications_need_no_permission() {
        assertFalse(ask(sdkInt = 32))
        assertFalse(ask(sdkInt = 26))
    }

    @Test fun it_does_not_ask_when_it_is_already_allowed() {
        assertFalse(ask(granted = true))
    }

    @Test fun it_does_not_ask_twice_so_a_person_who_said_no_in_the_chat_tab_is_not_asked_again_at_their_first_geofence() {
        assertFalse(ask(alreadyAsked = true))
    }

    @Test fun it_never_asks_before_onboarding_is_done_the_welcome_page_and_the_pages_after_it() {
        for (sdk in 26..36) {
            for (granted in listOf(false, true)) {
                for (asked in listOf(false, true)) {
                    assertFalse("sdk $sdk granted $granted asked $asked", ask(sdk, granted, asked, onboardingComplete = false))
                }
            }
        }
    }

    @Test fun finishing_onboarding_does_not_use_up_the_question() {
        // Nothing was asked while onboarding was open, so the first screen that asks afterwards still gets to.
        assertFalse(ask(onboardingComplete = false))
        assertTrue(ask(onboardingComplete = true))
    }

    @Test fun the_permission_string_is_the_one_android_13_defines() {
        assertEquals("android.permission.POST_NOTIFICATIONS", NotificationPermission.PERMISSION)
    }

    @Test fun only_the_chat_tab_and_the_geofence_save_ask() {
        // The question is asked from takeTurnToAsk and nowhere else, so nothing at launch (MainActivity, the
        // application) and nothing in the onboarding pages can ask: the callers are the Chat tab and the
        // geofence form on the map screen, which the app shows only after onboarding.
        val root = listOf("src/main/kotlin", "app/src/main/kotlin").map(::File).first { it.exists() }
        val callers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("takeTurnToAsk(") }
            .map { it.name }
            .toSortedSet()
        assertEquals(sortedSetOf("ChatScreen.kt", "MapScreen.kt", "NotificationPermission.kt"), callers)
        // ... and the system prompt is launched from those two screens only.
        val launchers = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readText().contains("NotificationPermission.PERMISSION") }
            .map { it.name }
            .toSortedSet()
        assertEquals(sortedSetOf("ChatScreen.kt", "MapScreen.kt"), launchers)
    }
}
