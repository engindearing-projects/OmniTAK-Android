package soy.engindearing.omnitak.mobile.ui.components

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceBanner
import soy.engindearing.omnitak.mobile.data.geofence.GeofenceEventType

/**
 * #155 - the geofence alert tells the map screen how tall it is so the pills under it start below it
 * ([belowGeofenceBanner]). The size node leaves the composition with the alert, and sends no last report, so
 * the last height it did report used to stay: after an alert the pills and the drone's banners sat 50 and 76 dp
 * lower than they rest, with no alert on screen, until the next one. The alert's content reports the zero itself
 * when it leaves.
 *
 * The real banner is composed, shown, hidden and taken away (see the Robolectric note in the build file).
 */
@RunWith(AndroidJUnit4::class)
// A plain Application: the app's own starts the map's native library, which has no JVM build.
@Config(sdk = [35], application = android.app.Application::class)
class GeofenceBannerHeightTest {

    @get:Rule val compose = createComposeRule()

    private val alert = GeofenceBanner("1", "ALPHA-2 entered North gate", GeofenceEventType.ENTRY, "g1")

    private fun settle() {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
    }

    @Test fun the_alert_reports_its_height_while_it_shows_and_zero_once_it_has_left() {
        val banner = mutableStateOf<GeofenceBanner?>(null)
        val reported = java.util.Collections.synchronizedList(mutableListOf<Int>())
        compose.setContent {
            GeofenceBannerView(banner = banner.value, onOpen = {}, onClose = {}, onHeightPx = { reported.add(it) })
        }
        settle()
        assertTrue("nothing to report while there is no alert: $reported", reported.none { it > 0 })

        banner.value = alert
        settle()
        assertTrue("a banner with text is taller than 0 px: $reported", reported.last() > 0)

        banner.value = null
        settle()
        assertEquals("nothing is under the alert once it has gone: $reported", 0, reported.last())
    }

    @Test fun the_pills_do_not_stay_pushed_down_when_the_alert_is_taken_away_while_it_shows() {
        // The map screen takes the alert out of its tree (the engine is switched, the screen is rebuilt) with it on
        // screen: its size node leaves without reporting 0, so only the alert's content can.
        val present = mutableStateOf(true)
        val banner = mutableStateOf<GeofenceBanner?>(alert)
        val reported = java.util.Collections.synchronizedList(mutableListOf<Int>())
        compose.setContent {
            if (present.value) {
                GeofenceBannerView(banner = banner.value, onOpen = {}, onClose = {}, onHeightPx = { reported.add(it) })
            }
        }
        settle()
        assertTrue("the alert is on screen and has a height: $reported", reported.last() > 0)

        present.value = false
        settle()
        assertEquals("the height it had must not stay behind: $reported", 0, reported.last())
    }

    @Test fun it_reports_zero_again_after_a_second_alert_has_come_and_gone() {
        val banner = mutableStateOf<GeofenceBanner?>(alert)
        val reported = java.util.Collections.synchronizedList(mutableListOf<Int>())
        compose.setContent {
            GeofenceBannerView(banner = banner.value, onOpen = {}, onClose = {}, onHeightPx = { reported.add(it) })
        }
        settle()
        banner.value = null
        settle()
        assertEquals(0, reported.last())

        reported.clear()
        banner.value = alert.copy(id = "2", sentence = "BRAVO-1 left North gate", type = GeofenceEventType.EXIT)
        settle()
        assertTrue("the second one has a height: $reported", reported.last() > 0)
        banner.value = null
        settle()
        assertEquals("the second one clears too: $reported", 0, reported.last())
    }
}
