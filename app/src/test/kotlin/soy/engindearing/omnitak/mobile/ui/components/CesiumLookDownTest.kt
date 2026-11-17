package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #155 - what the globe's page does for "Show on map" on a geofence. The page is told where to look and how
 * many metres to see across the screen (`GeofenceFraming.cameraFor(...).groundWidthMeters`); it works the camera
 * height out from its own frustum and flies there looking straight down, north up. Before, the globe was flown
 * to the middle of the zone 3 km up and looking 30 degrees below the horizon, which put the zone under the
 * camera and off the bottom of the screen.
 *
 * The page's own `_lookDownAgl`, `_lookDownGround` and `lookDown` run under node, with a fake camera and a fake
 * terrain service (see [PageScript]), so these are the page's real answers and not text it contains.
 */
class CesiumLookDownTest {

    private val functions = PageScript.block("LOOKDOWN")
    private val call = PageScript.block("LOOKDOWN-CALL")

    /** The prelude every flight scenario shares: the page's code, a camera that records its flights, and timers that wait to be fired. */
    private fun scenario(body: String): JSONObject = PageScript.json(
        """
        $functions
        const bridge = { $call };
        const _parse = x => typeof x === 'string' ? JSON.parse(x) : x;
        const flights = [], timers = [];
        globalThis.setTimeout = (fn, ms) => { timers.push({ fn, ms }); return timers.length; };
        globalThis.Cesium = {
          Cartesian3: { fromDegrees: (lon, lat, height) => ({ lon, lat, height }) },
          Cartographic: { fromDegrees: (lon, lat) => ({ lon, lat }) },
          sampleTerrainMostDetailed: undefined,
        };
        function viewer(aspectRatio, fovy, terrain) {
          return {
            camera: { frustum: { aspectRatio, fovy }, flyTo: o => flights.push(o) },
            terrainProvider: terrain ? {} : undefined,
          };
        }
        const tick = () => new Promise(r => setImmediate(r));
        var _state = { viewer: null };
        (async () => { $body })().then(r => console.log(JSON.stringify(r)));
        """.trimIndent(),
    )

    private fun JSONObject.flight(i: Int = 0): JSONObject = getJSONArray("flights").getJSONObject(i)

    // ---- the height: the width to see over twice the half-width the frustum sees

    @Test fun the_height_to_see_a_width_is_that_width_over_twice_the_half_width_the_frustum_sees() {
        val r = PageScript.json(
            """
            $functions
            console.log(JSON.stringify({
              square90: _lookDownAgl(1000, 1, Math.PI / 2),
              wide: _lookDownAgl(1000, 2, Math.PI / 2),
              portrait: _lookDownAgl(600, 0.45, 1.0),
              zeroWidth: _lookDownAgl(0, 1, 1),
              negativeWidth: _lookDownAgl(-5, 1, 1),
              noAspect: _lookDownAgl(600, 0, 1),
              noFov: _lookDownAgl(600, 1, 0),
            }));
            """.trimIndent(),
        )
        assertEquals("half = 1 * tan(45 degrees) = 1: 1000 m is seen from 500 m up", 500.0, r.getDouble("square90"), 1e-6)
        assertEquals("a wider screen sees twice as much from the same height, so needs half the height", 250.0, r.getDouble("wide"), 1e-6)
        assertEquals(600.0 / 2 / (0.45 * Math.tan(0.5)), r.getDouble("portrait"), 1e-6)
        for (nothing in listOf("zeroWidth", "negativeWidth", "noAspect", "noFov")) assertTrue("$nothing has no height", r.isNull(nothing))
    }

    @Test fun a_nonsense_ground_height_is_taken_as_sea_level_and_a_real_one_is_kept() {
        val r = PageScript.json(
            """
            $functions
            console.log(JSON.stringify({
              real: _lookDownGround(250), below: _lookDownGround(-999), high: _lookDownGround(9999),
              newYorkAnswerForSpokane: _lookDownGround(-39874), tooHigh: _lookDownGround(10000),
              nan: _lookDownGround(NaN), infinite: _lookDownGround(Infinity), text: _lookDownGround('250'), none: _lookDownGround(null),
            }));
            """.trimIndent(),
        )
        assertEquals(250.0, r.getDouble("real"), 0.0)
        assertEquals(-999.0, r.getDouble("below"), 0.0)
        assertEquals(9999.0, r.getDouble("high"), 0.0)
        for (nonsense in listOf("newYorkAnswerForSpokane", "tooHigh", "nan", "infinite", "text", "none")) {
            assertEquals("$nonsense is sea level", 0.0, r.getDouble(nonsense), 0.0)
        }
    }

    // ---- the call: straight down, north up, from the ground there plus the height

    @Test fun it_flies_straight_down_over_the_place_north_up_from_the_ground_there_plus_the_height() {
        val r = scenario(
            """
            let answer; Cesium.sampleTerrainMostDetailed = () => new Promise(res => { answer = res; });
            _state.viewer = viewer(0.5, 1.0, true);
            bridge.lookDown({ lat: 47.66, lon: -117.43, width: 600 });
            await tick();
            const before = flights.length;
            answer([{ height: 250 }]); await tick();
            return { before, flights, timer: timers.map(t => t.ms) };
            """.trimIndent(),
        )
        assertEquals("nothing flies until the ground is known", 0, r.getInt("before"))
        assertEquals(1, r.getJSONArray("flights").length())
        val f = r.flight()
        val agl = 600.0 / 2 / (0.5 * Math.tan(0.5))
        val at = f.getJSONObject("destination")
        assertEquals(47.66, at.getDouble("lat"), 0.0)
        assertEquals(-117.43, at.getDouble("lon"), 0.0)
        assertEquals("the ground there, 250 m, plus the height to see the width", 250.0 + agl, at.getDouble("height"), 1e-6)
        assertEquals("north up", 0.0, f.getJSONObject("orientation").getDouble("heading"), 0.0)
        assertEquals("straight down", -Math.PI / 2, f.getJSONObject("orientation").getDouble("pitch"), 1e-12)
        assertEquals("the service gets two seconds", listOf(2000), r.getJSONArray("timer").let { a -> List(a.length()) { a.getInt(it) } })
    }

    @Test fun with_no_answer_from_the_terrain_service_it_flies_at_sea_level_when_the_two_seconds_are_up() {
        val r = scenario(
            """
            Cesium.sampleTerrainMostDetailed = () => new Promise(() => {});
            _state.viewer = viewer(1, Math.PI / 2, true);
            bridge.lookDown({ lat: 10, lon: 20, width: 1000 });
            await tick();
            const beforeTimer = flights.length;
            timers[0].fn();
            return { beforeTimer, flights };
            """.trimIndent(),
        )
        assertEquals(0, r.getInt("beforeTimer"))
        assertEquals("sea level plus 500 m", 500.0, r.flight().getJSONObject("destination").getDouble("height"), 1e-6)
    }

    @Test fun an_answer_that_comes_after_the_time_is_up_does_not_fly_a_second_time() {
        val r = scenario(
            """
            let answer; Cesium.sampleTerrainMostDetailed = () => new Promise(res => { answer = res; });
            _state.viewer = viewer(1, Math.PI / 2, true);
            bridge.lookDown({ lat: 10, lon: 20, width: 1000 });
            timers[0].fn();
            answer([{ height: 800 }]); await tick();
            return { flights };
            """.trimIndent(),
        )
        assertEquals(1, r.getJSONArray("flights").length())
        assertEquals("it went at sea level, as it had to", 500.0, r.flight().getJSONObject("destination").getDouble("height"), 1e-6)
    }

    @Test fun a_failed_terrain_request_flies_at_sea_level_once() {
        val r = scenario(
            """
            Cesium.sampleTerrainMostDetailed = () => Promise.reject(new Error('offline'));
            _state.viewer = viewer(1, Math.PI / 2, true);
            bridge.lookDown({ lat: 10, lon: 20, width: 1000 });
            await tick(); timers[0].fn();
            return { flights };
            """.trimIndent(),
        )
        assertEquals(1, r.getJSONArray("flights").length())
        assertEquals(500.0, r.flight().getJSONObject("destination").getDouble("height"), 1e-6)
    }

    @Test fun a_nonsense_ground_in_the_answer_is_sea_level() {
        val r = scenario(
            """
            Cesium.sampleTerrainMostDetailed = () => Promise.resolve([{ height: -39874 }]);
            _state.viewer = viewer(1, Math.PI / 2, true);
            bridge.lookDown({ lat: 47.66, lon: -117.43, width: 1000 });
            await tick();
            return { flights };
            """.trimIndent(),
        )
        assertEquals(500.0, r.flight().getJSONObject("destination").getDouble("height"), 1e-6)
    }

    @Test fun with_no_terrain_service_it_flies_at_once_and_a_missing_width_is_600_metres() {
        val r = scenario(
            """
            _state.viewer = viewer(1, Math.PI / 2, false);
            bridge.lookDown({ lat: 1, lon: 2 });
            return { flights, timers: timers.length };
            """.trimIndent(),
        )
        assertEquals(1, r.getJSONArray("flights").length())
        assertEquals("600 m wide from 300 m up", 300.0, r.flight().getJSONObject("destination").getDouble("height"), 1e-6)
        assertEquals("no timer when there is no service to wait for", 0, r.getInt("timers"))
    }

    @Test fun it_goes_nowhere_without_a_place_a_viewer_or_a_height() {
        val r = scenario(
            """
            _state.viewer = viewer(1, Math.PI / 2, false);
            bridge.lookDown({ lon: 2 });
            bridge.lookDown({ lat: 1 });
            bridge.lookDown({ lat: '1', lon: 2 });
            bridge.lookDown(null);
            bridge.lookDown({ lat: 1, lon: 2, width: 0 });
            _state.viewer = null;
            bridge.lookDown({ lat: 1, lon: 2, width: 100 });
            return { flights };
            """.trimIndent(),
        )
        assertEquals(0, r.getJSONArray("flights").length())
    }

    @Test fun the_call_can_be_sent_as_a_json_string_the_way_the_app_sends_it() {
        val r = scenario(
            """
            _state.viewer = viewer(1, Math.PI / 2, false);
            bridge.lookDown('{"lat":47.5,"lon":-117.5,"width":1000}');
            return { flights };
            """.trimIndent(),
        )
        assertEquals(-117.5, r.flight().getJSONObject("destination").getDouble("lon"), 0.0)
    }
}
