package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import soy.engindearing.omnitak.mobile.domain.LassoSelectionService

/**
 * The three things one screen asks another to do: open Go to Coordinate on the
 * map, enter edit mode on the toolbar, start a lasso on the map. Each is a
 * [soy.engindearing.omnitak.mobile.domain.OneShotRequest] that the asker fires
 * and the screen listens to, and these pin that the asker and the listener are
 * the same request, taken once, and still there for a screen that is built
 * after the ask (Tools asks and switches tabs in the same frame). The rules of
 * the request itself are in OneShotRequestTest.
 *
 * The buses are process-wide singletons, so each test starts by clearing
 * whatever an earlier one left behind.
 */
class ScreenRequestsTest {

    @Before fun start_with_nothing_pending() {
        CoordinateEntryEvents.open.consume()
        ToolbarEditBus.edit.consume()
    }

    @Test fun go_to_coordinate_asked_from_tools_reaches_the_map_once() {
        assertFalse(CoordinateEntryEvents.open.pending)

        CoordinateEntryEvents.requestOpen()

        assertTrue("the map has a request to act on", CoordinateEntryEvents.open.consume())
        assertFalse(
            "a map built later must not find the same request again (#286)",
            CoordinateEntryEvents.open.consume(),
        )
    }

    @Test fun go_to_coordinate_can_be_asked_again_after_it_was_used() {
        repeat(2) {
            CoordinateEntryEvents.requestOpen()
            assertTrue(CoordinateEntryEvents.open.consume())
        }
    }

    @Test fun customize_toolbar_asked_from_settings_reaches_the_bar_once() {
        assertFalse(ToolbarEditBus.edit.pending)

        ToolbarEditBus.requestEdit()

        assertTrue(ToolbarEditBus.edit.consume())
        assertFalse(
            "the bar rebuilt with the Activity must not enter edit mode again",
            ToolbarEditBus.edit.consume(),
        )
    }

    @Test fun a_lasso_asked_before_the_map_is_built_is_waiting_for_it() {
        val lasso = LassoSelectionService()
        assertFalse(lasso.activation.pending)

        lasso.requestActivate()

        assertTrue("the map built a frame later acts on it", lasso.activation.consume())
        assertFalse(lasso.activation.consume())
    }

    @Test fun the_three_requests_do_not_share_one_flag() {
        val lasso = LassoSelectionService()
        CoordinateEntryEvents.requestOpen()

        assertFalse(ToolbarEditBus.edit.pending)
        assertFalse(lasso.activation.pending)
        assertTrue(CoordinateEntryEvents.open.consume())
    }
}
