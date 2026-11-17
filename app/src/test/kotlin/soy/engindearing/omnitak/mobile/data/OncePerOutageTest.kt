package soy.engindearing.omnitak.mobile.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A failure that repeats is reported once; a failure after something worked is a new one. */
class OncePerOutageTest {

    @Test fun the_first_failure_is_reported() {
        assertTrue(OncePerOutage().shouldReport())
    }

    @Test fun the_failures_after_it_are_not() {
        val outage = OncePerOutage()
        assertTrue(outage.shouldReport())
        repeat(50) { assertFalse(outage.shouldReport()) }
    }

    @Test fun a_success_makes_the_next_failure_a_new_one() {
        val outage = OncePerOutage()
        assertTrue(outage.shouldReport())
        assertFalse(outage.shouldReport())
        outage.succeeded()
        assertTrue(outage.shouldReport())
        assertFalse(outage.shouldReport())
    }

    @Test fun a_success_with_nothing_failed_changes_nothing() {
        val outage = OncePerOutage()
        outage.succeeded()
        assertTrue(outage.shouldReport())
    }
}
