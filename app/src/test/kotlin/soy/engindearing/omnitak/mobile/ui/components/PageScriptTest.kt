package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** The harness that runs the globe page's code under node ([PageScript]): a node that never finishes must fail a test, not hang the build. */
class PageScriptTest {

    @Test(timeout = 30_000) fun a_node_that_never_finishes_fails_the_test_when_the_time_is_up() {
        val started = System.nanoTime()
        try {
            PageScript.run("console.log('started'); setInterval(function () {}, 1000);", timeoutSeconds = 2)
            fail("a script that never ends cannot have finished")
        } catch (e: AssertionError) {
            assertTrue("the failure says why: ${e.message}", e.message.orEmpty().contains("did not finish"))
            assertTrue("and keeps what the script printed before it hung: ${e.message}", e.message.orEmpty().contains("started"))
        }
        val seconds = (System.nanoTime() - started) / 1e9
        assertTrue("it gave up at the limit, not long after: $seconds s", seconds < 20)
    }

    @Test(timeout = 30_000) fun a_script_that_prints_a_lot_is_not_held_up_by_a_full_pipe() {
        // Output the size of a few pipes. Read only after the process ends, it would block node on a full pipe and look like a hang.
        val out = PageScript.run("const line = 'x'.repeat(1000); for (let i = 0; i < 400; i++) console.log(line); console.log('done');", timeoutSeconds = 20)
        assertEquals("done", out.lines().last())
        assertTrue("all of it came back: ${out.length}", out.length > 400_000)
    }

    @Test(timeout = 30_000) fun a_script_that_finishes_returns_what_it_printed_and_one_that_fails_says_so() {
        assertEquals("42", PageScript.run("console.log(6 * 7);"))
        try {
            PageScript.run("console.log('before'); process.exit(3);")
            fail("a failing script must fail the test")
        } catch (e: AssertionError) {
            assertTrue(e.message.orEmpty().contains("node failed"))
            assertTrue(e.message.orEmpty().contains("before"))
        }
    }
}
