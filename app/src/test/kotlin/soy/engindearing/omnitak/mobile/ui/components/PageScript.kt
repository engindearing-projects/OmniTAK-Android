package soy.engindearing.omnitak.mobile.ui.components

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs the globe page's own code under node, so what the page does is tested and not only what it says. The
 * page marks the parts that need nothing from the browser or from Cesium with `// NAME-BEGIN` and
 * `// NAME-END` comment lines ([block]); a test puts a few fakes round them and runs them ([run]).
 *
 * With no node on the machine a test is skipped; on CI (`CI=true`, where node is installed) it fails instead, so
 * a missing node cannot quietly turn the tests off there.
 */
internal object PageScript {

    private val pageFile: File by lazy {
        listOf("src/main/assets/cesium_scene.html", "app/src/main/assets/cesium_scene.html").map(::File).first { it.exists() }
    }

    val page: String by lazy { pageFile.readText() }

    /** The page's text between the lines `// [name]-BEGIN` and `// [name]-END`. */
    fun block(name: String): String {
        val begin = page.indexOf("// $name-BEGIN")
        val end = page.indexOf("// $name-END")
        require(begin >= 0 && end > begin) { "the page has no $name block" }
        return page.substring(page.indexOf('\n', begin) + 1, end)
    }

    private val node: String? by lazy {
        val dirs = (System.getenv("PATH") ?: "").split(File.pathSeparatorChar) + listOf("/opt/homebrew/bin", "/usr/local/bin", "/usr/bin")
        dirs.map { File(it, "node") }.firstOrNull { it.canExecute() }?.path
    }

    /** Runs [js] in node and returns what it printed (the last line is what tests parse). */
    fun run(js: String): String {
        val exe = node
        if (exe == null) {
            if (System.getenv("CI") == "true") fail("node is not installed on CI: the page tests need it")
            assumeTrue("node is not installed", false)
        }
        val builder = ProcessBuilder(exe, "-").redirectErrorStream(true)
        // A node preload set up for another tool (NODE_OPTIONS) must not run in here.
        builder.environment().remove("NODE_OPTIONS")
        val process = builder.start()
        process.outputStream.use { it.write(js.toByteArray()) }
        val output = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(60, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            fail("node did not finish: $output")
        }
        assertEquals("node failed:\n$output", 0, process.exitValue())
        return output.trim()
    }

    /** Runs [js], whose last line printed is JSON, and returns it as an object. */
    fun json(js: String): JSONObject = JSONObject(run(js).lines().last())

    /** Runs [js], whose last line printed is a JSON array. */
    fun jsonArray(js: String): JSONArray = JSONArray(run(js).lines().last())
}
