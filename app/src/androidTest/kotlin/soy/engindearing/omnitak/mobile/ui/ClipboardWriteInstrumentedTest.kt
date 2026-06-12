package soy.engindearing.omnitak.mobile.ui

import android.content.ClipDescription
import android.content.Context
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import soy.engindearing.omnitak.mobile.data.CoordClipboard
import soy.engindearing.omnitak.mobile.data.CoordFormat
import soy.engindearing.omnitak.mobile.data.CoordFormatter
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Issue #92 repro + regression — radial "Copy Coords" showed the
 * confirmation toast while a field report said third-party apps pasted
 * nothing. Repro on stock API 36 and Samsung One UI 16 could NOT fault
 * the write (the original `LocalClipboardManager.setText` path landed a
 * cross-app-pasteable clip every time, tap or press-and-hold), so
 * MapScreen moved to [CoordClipboard] — a platform write that's read
 * back before the toast claims success. These tests pin that contract
 * on a real device: the Boolean driving the toast must agree with what
 * the system clipboard — the surface third-party pastes coerce from —
 * actually holds.
 *
 * Instrumented because clipboard truth lives in the system server; JVM
 * tests can't observe what other apps would paste. Reads require window
 * focus on API 29+, so every assertion runs inside an ActivityScenario
 * after polling `hasWindowFocus()`.
 */
@RunWith(AndroidJUnit4::class)
class ClipboardWriteInstrumentedTest {

    /** Pre-write sentinel so a pass can't be a stale-clipboard false positive. */
    private val sentinel = "issue92-sentinel-${System.nanoTime()}"

    /**
     * The production write+verify, payload from the production formatter's
     * DMS readout — degree/minute/second glyphs cover the "written in an
     * incompatible form" theory with non-ASCII text.
     */
    @Test
    fun coordClipboardCopy_landsAsPlainTextInSystemClipboard() {
        // Taipei 101 — Gavin (the reporter) is the Taiwan-team user.
        val coord = CoordFormatter.position(25.033964, 121.564468, CoordFormat.LATLON_DMS)

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            awaitWindowFocus(scenario)
            seedSentinel(scenario)

            var claimed = false
            scenario.onActivity { claimed = CoordClipboard.copy(it, coord) }

            assertTrue("CoordClipboard.copy reported failure on a healthy device", claimed)
            assertSystemClipboardHolds(scenario, coord)
        }
    }

    /**
     * The reporter's gesture: press and HOLD the radial item, release.
     * RadialMenu items are plain `Modifier.clickable` (RadialMenu.kt:106),
     * which fires onClick on release no matter how long the press — so a
     * >long-press-timeout hold must still run the copy branch AND the
     * success the toast reports must match the system clipboard. Raw
     * MotionEvents go through the activity's decor, the same dispatch the
     * real radial sees, with the write inside onClick like MapScreen's
     * copy branch.
     */
    @Test
    fun heldClickRelease_runsCopyBranch_andClipLands() {
        val coord = CoordFormatter.position(25.033964, 121.564468, CoordFormat.MGRS)
        val clicked = CountDownLatch(1)
        val claimed = AtomicBoolean(false)
        val composed = CountDownLatch(1)

        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                activity.setContent {
                    val ctx = LocalContext.current.applicationContext
                    Box(
                        Modifier
                            .fillMaxSize()
                            // RadialMenu.kt:106 — items are plain clickable.
                            .clickable {
                                claimed.set(CoordClipboard.copy(ctx, coord))
                                clicked.countDown()
                            }
                    )
                }
                composed.countDown()
            }
            assertTrue("composition never happened", composed.await(5, TimeUnit.SECONDS))
            awaitWindowFocus(scenario)
            seedSentinel(scenario)

            // DOWN → hold well past the 400 ms long-press timeout → UP.
            val down = SystemClock.uptimeMillis()
            scenario.onActivity { a -> a.dispatchTouch(MotionEvent.ACTION_DOWN, down, down) }
            SystemClock.sleep(900)
            scenario.onActivity { a ->
                a.dispatchTouch(MotionEvent.ACTION_UP, down, SystemClock.uptimeMillis())
            }

            assertTrue(
                "held-then-released clickable never fired onClick",
                clicked.await(5, TimeUnit.SECONDS),
            )
            assertTrue("copy branch would have toasted failure", claimed.get())
            assertSystemClipboardHolds(scenario, coord)
        }
    }

    /** Dispatch a centered single-pointer touch event through the decor view. */
    private fun ComponentActivity.dispatchTouch(action: Int, downTime: Long, eventTime: Long) {
        val v = window.decorView
        val ev = MotionEvent.obtain(
            downTime, eventTime, action, v.width / 2f, v.height / 2f, 0,
        )
        dispatchTouchEvent(ev)
        ev.recycle()
    }

    /** API 29+ gates clipboard reads on window focus — wait for it. */
    private fun awaitWindowFocus(scenario: ActivityScenario<ComponentActivity>) {
        val deadline = SystemClock.uptimeMillis() + 10_000
        var focused = false
        while (!focused && SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { focused = it.hasWindowFocus() }
            if (!focused) SystemClock.sleep(100)
        }
        assertTrue("activity never gained window focus", focused)
    }

    private fun seedSentinel(scenario: ActivityScenario<ComponentActivity>) {
        scenario.onActivity {
            val cm = it.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("sentinel", sentinel))
        }
    }

    /**
     * The oracle: read back through the PLATFORM ClipboardManager —
     * the system-server truth a third-party paste target coerces from —
     * and require plain-text MIME plus an exact text match.
     */
    private fun assertSystemClipboardHolds(
        scenario: ActivityScenario<ComponentActivity>,
        expected: String,
    ) {
        var mimeOk = false
        var itemText: String? = null
        var coerced: String? = null
        scenario.onActivity {
            val cm = it.getSystemService(Context.CLIPBOARD_SERVICE)
                as android.content.ClipboardManager
            val clip = cm.primaryClip
            assertNotNull("system clipboard has no primary clip", clip)
            mimeOk = clip!!.description.hasMimeType(ClipDescription.MIMETYPE_TEXT_PLAIN)
            itemText = clip.getItemAt(0).text?.toString()
            coerced = clip.getItemAt(0).coerceToText(it).toString()
        }
        assertTrue("clip is not text/plain — unpasteable in plain-text targets", mimeOk)
        assertEquals("clip item text mangled", expected, itemText)
        assertEquals("coerceToText (what paste targets use) mangled", expected, coerced)
        assertTrue("clipboard still holds the pre-write sentinel", itemText != sentinel)
    }
}
