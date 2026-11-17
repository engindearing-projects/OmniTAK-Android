package soy.engindearing.omnitak.mobile.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * #155 - where the globe writes the name of a zone. The 2D map keeps a name with its zone while any of the zone
 * is on the screen ([GeofenceLabels.placed]); the page does the same on the globe (`_namePlace`, `_placeNames`),
 * and these tests run the page's own code under node ([PageScript]): the rule against the 2D map's on a few
 * hundred zones and screens, and the code that moves the names against a fake globe.
 */
class CesiumNamePlacementTest {

    private val names = PageScript.block("NAMES")

    // ---- the rule: the same answer as the 2D map's, for the same zone on the same screen

    private data class Case(val w: Float, val h: Float, val top: Float, val nameH: Float, val gap: Float, val margin: Float,
                            val x: Float, val y: Float, val box: FloatArray?)

    private fun cases(): List<Case> {
        val rnd = Random(155)
        fun r(from: Float, to: Float) = from + rnd.nextFloat() * (to - from)
        fun step(v: Float) = Math.round(v).toFloat() // whole pixels: the two sides then add the same numbers
        return List(500) { n ->
            val landscape = n % 2 == 1
            val w = if (landscape) 2400f else 1080f
            val h = if (landscape) 1080f else 2400f
            val left = step(r(-2500f, 2500f))
            val top = step(r(-2500f, 2500f))
            val box = if (n % 9 == 0) null else floatArrayOf(left, top, left + step(r(1f, 2500f)), top + step(r(1f, 2500f)))
            // The point a name is on: on the box when there is one (a circle's north edge, a polygon's middle), else anywhere.
            val x = if (box != null) step(r(box[0], box[2])) else step(r(-1500f, 3000f))
            val y = if (box != null) step(if (n % 3 == 0) box[1] else r(box[1], box[3])) else step(r(-1500f, 3000f))
            Case(w, h, 300f, 40f, 5f, 200f, x, y, box)
        }
    }

    @Test fun the_globe_and_the_2d_map_put_a_name_in_the_same_place_for_the_same_zone_and_screen() {
        val all = cases()
        val js = all.joinToString(",", "[", "]") { c ->
            val box = c.box?.let { "{left:${it[0]},top:${it[1]},right:${it[2]},bottom:${it[3]}}" } ?: "null"
            "{w:${c.w},h:${c.h},top:${c.top},nameH:${c.nameH},gap:${c.gap},margin:${c.margin},x:${c.x},y:${c.y},box:$box}"
        }
        val answers = PageScript.jsonArray(
            """
            $names
            const cases = $js;
            console.log(JSON.stringify(cases.map(c => _namePlace({ x: c.x, y: c.y }, c.box, c.w, c.h, c.top, c.nameH, c.gap, c.margin))));
            """.trimIndent(),
        )
        assertEquals(all.size, answers.length())
        var shown = 0
        var moved = 0
        var dropped = 0
        all.forEachIndexed { i, c ->
            val zone = c.box?.let { ScreenBox(it[0], it[1], it[2], it[3]) }
            val kotlin = GeofenceLabels.placed(
                listOf(ScreenLabel("n", c.x, c.y, "#FFFFFF", zone)), c.w, c.h, c.margin, topPx = c.top, nameHeightPx = c.nameH, gapPx = c.gap,
            ).singleOrNull()
            val page = answers.getJSONObject(i)
            val what = "case $i ($c, box ${c.box?.toList()})"
            if (kotlin == null) {
                assertFalse("$what: the 2D map drops the name, the globe shows it", page.getBoolean("show"))
                dropped++
            } else {
                assertTrue("$what: the 2D map shows the name, the globe drops it", page.getBoolean("show"))
                assertEquals("$what: x", kotlin.x.toDouble(), c.x + page.getDouble("dx"), 1e-3)
                assertEquals("$what: y", kotlin.y.toDouble(), c.y + page.getDouble("dy"), 1e-3)
                shown++
                if (kotlin.x != c.x || kotlin.y != c.y) moved++
            }
        }
        // The cases cover all three outcomes, so agreeing proves something.
        assertTrue("some names are kept where they are: $shown shown, $moved moved, $dropped dropped", shown - moved > 20)
        assertTrue("some names are moved: $moved", moved > 20)
        assertTrue("some names are dropped: $dropped", dropped > 20)
    }

    @Test fun a_name_whose_point_cannot_be_placed_is_left_alone() {
        // A point behind the camera has no window position: the globe leaves the name to Cesium.
        val r = PageScript.json(
            """
            $names
            console.log(JSON.stringify({ none: _namePlace(null, { left: 0, top: 0, right: 10, bottom: 10 }, 1080, 2400, 300, 20, 2, 100),
              nan: _namePlace({ x: NaN, y: 5 }, null, 1080, 2400, 300, 20, 2, 100) }));
            """.trimIndent(),
        )
        for (k in listOf("none", "nan")) {
            assertTrue(r.getJSONObject(k).getBoolean("show"))
            assertEquals(0.0, r.getJSONObject(k).getDouble("dx"), 0.0)
            assertEquals(0.0, r.getJSONObject(k).getDouble("dy"), 0.0)
        }
    }

    // ---- the box and the outline

    @Test fun the_box_is_round_the_points_that_are_numbers() {
        val r = PageScript.json(
            """
            $names
            console.log(JSON.stringify({ box: _boxOf([{ x: 10, y: 20 }, { x: 300, y: -40 }, { x: NaN, y: 5 }, { x: 120, y: 900 }]),
              none: _boxOf([]), nan: _boxOf([{ x: NaN, y: NaN }]) }));
            """.trimIndent(),
        )
        val box = r.getJSONObject("box")
        assertEquals(listOf(10.0, -40.0, 300.0, 900.0), listOf("left", "top", "right", "bottom").map { box.getDouble(it) })
        assertTrue(r.isNull("none"))
        assertTrue(r.isNull("nan"))
    }

    @Test fun a_zones_outline_is_thinned_to_sixteen_points_spread_all_round_it_the_first_and_the_last_kept() {
        val r = PageScript.json(
            """
            $names
            globalThis.Cesium = { Cartesian3: { fromDegrees: (lon, lat, h) => ({ lon, lat, h }) } };
            const ring = Array.from({ length: 65 }, (_, i) => [i * 2, i]);
            const few = [[1, 1], [2, 2], [3, 3]];
            console.log(JSON.stringify({ thin: _thinRing(ring), few: _thinRing(few), none: _thinRing([]) }));
            """.trimIndent(),
        )
        val thin = r.getJSONArray("thin")
        assertEquals(16, thin.length())
        assertEquals(0.0, thin.getJSONObject(0).getDouble("lon"), 0.0)
        assertEquals(128.0, thin.getJSONObject(15).getDouble("lon"), 0.0)
        assertEquals("on the ground", 0.0, thin.getJSONObject(5).getDouble("h"), 0.0)
        val lats = List(16) { thin.getJSONObject(it).getDouble("lat") }
        assertTrue("evenly spread: ${lats.zipWithNext { a, b -> b - a }}", lats.zipWithNext { a, b -> b - a }.all { it == 4.0 || it == 5.0 })
        assertEquals(3, r.getJSONArray("few").length())
        assertEquals(0, r.getJSONArray("none").length())
    }

    // ---- the page moves the names of the zones before each frame

    /** A fake globe: window positions are the world positions given, so a zone's place on the screen is what the test says. */
    private fun globe(body: String) = PageScript.json(
        """
        $names
        let projections = 0;
        globalThis.Cesium = {
          Cartesian2: function (x, y) { this.x = x; this.y = y; },
          SceneTransforms: { worldToWindowCoordinates: (scene, p) => { projections++; return p.behind ? undefined : { x: p.x, y: p.y }; } },
        };
        const _state = { drawings: new Map(), nameTop: 300, nameKey: '', namesDirty: true };
        const cam = { positionWC: { x: 1, y: 2, z: 3 }, directionWC: { x: 0, y: 0, z: -1 }, upWC: { x: 0, y: 1, z: 0 } };
        const v = { scene: { canvas: { clientWidth: 1080, clientHeight: 2400 } }, camera: cam, clock: { currentTime: 0 } };
        function zone(name, at, ring) {
          return { label: { show: true, pixelOffset: undefined }, position: { getValue: () => at }, zoneName: name, zoneRing: ring };
        }
        (() => { $body })();
        """.trimIndent(),
    )

    @Test fun a_name_whose_north_edge_is_above_the_screen_is_moved_down_to_the_top_while_the_zone_is_on_it() {
        val r = globe(
            """
            const e = zone('North gate', { x: 540, y: -800 }, [{ x: 100, y: -800 }, { x: 980, y: 700 }, { x: 540, y: 1500 }]);
            _state.drawings.set('a', e);
            _placeNames(v);
            console.log(JSON.stringify({ show: e.nameShown, offset: { x: e.label.pixelOffset.x, y: e.label.pixelOffset.y } }));
            """.trimIndent(),
        )
        assertTrue(r.getBoolean("show"))
        // Its point is at y = -800; the name goes where its bottom is 2 px over the first y that clears the 300 px of status bar and strip
        // with 20 px of name: 300 + 2 + 20 = 322, so it is moved 322 + 800 = 1122 px down, less the 2 px gap it always has.
        assertEquals(0.0, r.getJSONObject("offset").getDouble("x"), 0.0)
        assertEquals(-2.0 + 1122.0, r.getJSONObject("offset").getDouble("y"), 0.0)
    }

    @Test fun a_zone_with_none_of_it_on_the_screen_has_no_name_and_gets_it_back_when_it_comes_into_view() {
        val r = globe(
            """
            const e = zone('Far', { x: 540, y: -2000 }, [{ x: 100, y: -2500 }, { x: 980, y: -1800 }]);
            _state.drawings.set('a', e);
            _placeNames(v);
            const hidden = e.nameShown, hiddenLabel = e.label.show;
            e.position = { getValue: () => ({ x: 540, y: 900 }) };
            e.zoneRing = [{ x: 100, y: 900 }, { x: 980, y: 1500 }];
            _state.namesDirty = true;
            _placeNames(v);
            console.log(JSON.stringify({ hidden, hiddenLabel, shown: e.nameShown, offset: e.label.pixelOffset.y, labelShow: e.label.show }));
            """.trimIndent(),
        )
        assertFalse(r.getBoolean("hidden"))
        assertFalse("the name itself is hidden, not only noted as hidden", r.getBoolean("hiddenLabel"))
        assertTrue(r.getBoolean("shown"))
        assertTrue(r.getBoolean("labelShow"))
        assertEquals("on the screen and clear of the strip: where its point puts it", -2.0, r.getDouble("offset"), 0.0)
    }

    @Test fun nothing_is_worked_out_again_while_the_camera_the_window_and_the_shapes_are_still() {
        val r = globe(
            """
            const e = zone('Yard', { x: 540, y: 900 }, [{ x: 100, y: 900 }, { x: 980, y: 1500 }]);
            _state.drawings.set('a', e);
            _placeNames(v);
            const first = projections;
            _placeNames(v); _placeNames(v);
            const still = projections;
            cam.positionWC = { x: 1, y: 2, z: 4 };
            _placeNames(v);
            const moved = projections;
            _state.nameTop = 340; _placeNames(v);
            const topChanged = projections;
            v.scene.canvas.clientHeight = 1080; _placeNames(v);
            const resized = projections;
            console.log(JSON.stringify({ first, still, moved, topChanged, resized }));
            """.trimIndent(),
        )
        assertTrue(r.getInt("first") > 0)
        assertEquals("a still view costs nothing", r.getInt("first"), r.getInt("still"))
        assertTrue("a camera that moved is worked out", r.getInt("moved") > r.getInt("still"))
        assertTrue("a new strip height is", r.getInt("topChanged") > r.getInt("moved"))
        assertTrue("a window that changed size is", r.getInt("resized") > r.getInt("topChanged"))
    }

    @Test fun a_shape_without_a_name_and_a_name_whose_point_is_behind_the_camera_are_left_alone() {
        val r = globe(
            """
            const plain = { position: { getValue: () => ({ x: 1, y: 1 }) } };
            const behind = zone('Behind', { behind: true }, [{ x: 100, y: 900 }]);
            _state.drawings.set('p', plain);
            _state.drawings.set('b', behind);
            _placeNames(v);
            console.log(JSON.stringify({ plainTouched: Object.keys(plain).length, offset: behind.label.pixelOffset ? behind.label.pixelOffset.y : null,
              shown: behind.nameShown }));
            """.trimIndent(),
        )
        assertEquals("the shape without a name is not touched", 1, r.getInt("plainTouched"))
        assertTrue("the name with no window position stays shown", r.getBoolean("shown"))
        assertEquals("and where its point puts it", -2.0, r.getDouble("offset"), 0.0)
    }

    @Test fun nothing_to_do_without_any_zones() {
        val r = globe(
            """
            _placeNames(v);
            console.log(JSON.stringify({ projections }));
            """.trimIndent(),
        )
        assertEquals(0, r.getInt("projections"))
        assertNull(r.optString("nothing", null))
    }
}
