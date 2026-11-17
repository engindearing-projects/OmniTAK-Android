package soy.engindearing.omnitak.mobile.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #155 - when the app asks for permission to show notifications (when the first geofence is saved,
 * never at launch), which notification ids an alert gets, and how the app knows it is behind.
 */
class GeofenceNotifierTest {

    @Test fun the_question_is_asked_on_android_13_and_later_when_not_granted_and_not_asked_before() {
        assertTrue(GeofenceNotificationPermission.shouldAsk(sdkInt = 33, granted = false, alreadyAsked = false))
        assertTrue(GeofenceNotificationPermission.shouldAsk(sdkInt = 36, granted = false, alreadyAsked = false))
    }

    @Test fun it_is_not_asked_where_notifications_need_no_permission() {
        assertFalse(GeofenceNotificationPermission.shouldAsk(sdkInt = 32, granted = false, alreadyAsked = false))
        assertFalse(GeofenceNotificationPermission.shouldAsk(sdkInt = 26, granted = false, alreadyAsked = false))
    }

    @Test fun it_is_not_asked_when_it_is_already_allowed() {
        assertFalse(GeofenceNotificationPermission.shouldAsk(sdkInt = 36, granted = true, alreadyAsked = false))
    }

    @Test fun it_is_not_asked_twice() {
        assertFalse(GeofenceNotificationPermission.shouldAsk(sdkInt = 36, granted = false, alreadyAsked = true))
    }

    @Test fun every_alert_gets_its_own_notification_up_to_a_limit_then_the_oldest_is_replaced() {
        val ids = (0 until 20).map { GeofenceNotifier.notificationId(it) }
        assertEquals("twenty distinct ids", 20, ids.toSet().size)
        assertEquals("the twenty-first replaces the first", ids[0], GeofenceNotifier.notificationId(20))
        assertEquals(ids[3], GeofenceNotifier.notificationId(23))
    }

    @Test fun the_ids_stay_clear_of_the_other_notifications_of_the_app() {
        // 1001 / 1002 are the connection notice, 2000 + hash the mesh chat.
        val ids = (0 until 100).map { GeofenceNotifier.notificationId(it) }
        assertTrue(ids.all { it in 3000..3019 })
    }

    @Test fun the_channel_has_its_own_id() {
        assertEquals("geofence_alerts", GeofenceNotifier.CHANNEL_ID)
        assertTrue(GeofenceNotifier.CHANNEL_ID != MeshChatNotifier.CHANNEL_ID)
    }

    // ------------------------------------------------------------------ in front or behind

    @Test fun the_app_is_in_front_while_any_activity_is_started() {
        val tracker = ForegroundTracker()
        assertFalse("nothing has started yet", tracker.inForeground)
        tracker.activityStarted()
        assertTrue(tracker.inForeground)
        tracker.activityStopped()
        assertFalse(tracker.inForeground)
    }

    @Test fun a_second_activity_starting_before_the_first_stops_keeps_the_app_in_front() {
        // The system starts the new one before it stops the old one (an Activity rebuilt, one opened over another).
        val tracker = ForegroundTracker()
        tracker.activityStarted()
        tracker.activityStarted()
        tracker.activityStopped()
        assertTrue(tracker.inForeground)
        tracker.activityStopped()
        assertFalse(tracker.inForeground)
    }

    @Test fun a_stop_with_no_start_does_not_leave_the_count_below_zero() {
        val tracker = ForegroundTracker()
        tracker.activityStopped()
        tracker.activityStarted()
        assertTrue(tracker.inForeground)
    }
}
