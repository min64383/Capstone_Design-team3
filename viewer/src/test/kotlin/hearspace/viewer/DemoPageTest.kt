package hearspace.viewer

import hearspace.core.relay.RelayBatch
import hearspace.core.relay.RelayScreen
import hearspace.core.relay.RelaySettings
import hearspace.core.screen.UserScreen
import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonNull
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.MapHealth
import hearspace.core.types.MiniJson
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.concurrent.TimeUnit

/**
 * 영상 데모(M73 설계 5절 ④): 중계 서버의 `/demo` 경로(보기 열쇠, 바이트 범위 206)와 데모 페이지가 영상 시각에 맞는 앱 화면·말을 고르는지.
 * 페이지 시험은 화면 없는 Edge·Chrome으로 연다(없으면 건너뜀).
 */
class DemoPageTest {
    @TempDir lateinit var tmp: File

    private val http = HttpClient.newHttpClient()
    private val video = ByteArray(1000) { it.toByte() }

    private fun server(): RelayServer {
        val demo = File(tmp, "demo").apply { mkdirs() }
        File(demo, "x.mp4").writeBytes(video)
        val none = UserScreen.of(true, null, false, GuidanceState.NORMAL, MapHealth.OK, null, null)
        val warn = UserScreen.of(true, null, false, GuidanceState.NORMAL, MapHealth.OK, Band.WARN, 12f)
        fun sc(t: Double, s: UserScreen) = JsonArray(listOf(JsonNumber(t), RelayBatch.screenJson(RelayScreen(0, s, null))))
        val tl = JsonObject(linkedMapOf<String, JsonValue>(
            "id" to JsonString("x"), "title" to JsonString("엑스"), "durationS" to JsonNumber(13.5),
            "screens" to JsonArray(listOf(sc(0.0, none), sc(8.1, warn), sc(12.6, none))),
            "speech" to JsonArray(listOf(JsonArray(listOf(JsonNumber(7.72), JsonNumber(9.1), JsonString("차 다가옴"), JsonNumber(1.0))))),
            "truth" to JsonArray(emptyList()),
        ))
        File(demo, "x.json").writeText(MiniJson.write(tl, pretty = false))
        File(demo, "scenes.json").writeText("""[{"id":"x","title":"엑스","page":"https://example.org/x"}]""")
        val s = RelaySettings.load(RelayFiles.base.readText(), listOf(
            """{"port": 0, "uploadKey": "u", "viewKey": "v", "clipDir": "${File(tmp, "c").invariantSeparatorsPath}", "demoDir": "${demo.invariantSeparatorsPath}"}"""))
        return RelayServer(s, null)
    }

    private fun get(s: RelayServer, path: String, range: String? = null): HttpResponse<ByteArray> =
        http.send(HttpRequest.newBuilder(URI("http://127.0.0.1:${s.port}$path")).GET().apply { range?.let { header("Range", it) } }.build(),
            HttpResponse.BodyHandlers.ofByteArray())

    @Test
    fun `demo files need the view key, answer byte ranges with 206, and stay inside the demo folder`() {
        server().use { s ->
            assertEquals(403, get(s, "/demo/x.mp4").statusCode())
            assertEquals(403, get(s, "/demo").statusCode())
            assertEquals(200, get(s, "/demo?k=v").statusCode())
            val whole = get(s, "/demo/x.mp4?k=v")
            assertEquals(200, whole.statusCode())
            assertArrayEquals(video, whole.body())
            assertEquals("bytes", whole.headers().firstValue("Accept-Ranges").orElse(""))
            val part = get(s, "/demo/x.mp4?k=v", "bytes=10-19")
            assertEquals(206, part.statusCode())
            assertEquals("bytes 10-19/1000", part.headers().firstValue("Content-Range").orElse(""))
            assertArrayEquals(video.copyOfRange(10, 20), part.body())
            val open = get(s, "/demo/x.mp4?k=v", "bytes=990-")
            assertEquals(206, open.statusCode())
            assertArrayEquals(video.copyOfRange(990, 1000), open.body())
            val tail = get(s, "/demo/x.mp4?k=v", "bytes=-5")
            assertArrayEquals(video.copyOfRange(995, 1000), tail.body())
            assertEquals(416, get(s, "/demo/x.mp4?k=v", "bytes=1000-").statusCode())
            assertEquals(404, get(s, "/demo/..%2Fx.mp4?k=v").statusCode())
            assertEquals(404, get(s, "/demo/y.mp4?k=v").statusCode())
            assertEquals(200, get(s, "/screen.js").statusCode())
        }
    }

    @Test
    fun `the demo page draws the app screen and speech caption of the video time`() {
        val exe = listOf(
            "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
            "C:/Program Files/Google/Chrome/Application/chrome.exe", "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
        ).map(::File).firstOrNull { it.isFile }
        assumeTrue(exe != null, "no Edge/Chrome for the page test")
        server().use { s ->
            val times = listOf(0.0, 8.0, 8.2, 9.5, 13.0)
            val url = "http://127.0.0.1:${s.port}/demo?k=v&test=1#" + URLEncoder.encode(times.toString(), Charsets.UTF_8).replace("+", "%20")
            val p = ProcessBuilder(exe!!.path, "--headless", "--disable-gpu", "--no-first-run", "--user-data-dir=${File(tmp, "profile").path}",
                "--virtual-time-budget=5000", "--dump-dom", url).redirectError(File(tmp, "browser.err")).start()
            val dom = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
            p.waitFor(60, TimeUnit.SECONDS)
            val raw = Regex("""<pre id="test-out"[^>]*>(.*?)</pre>""", RegexOption.DOT_MATCHES_ALL).find(dom)?.groupValues?.get(1)
                ?: error("no test output in the page:\n${dom.take(2000)}")
            val out = MiniJson.parse(raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")) as JsonObject
            assertEquals("demo/x.mp4?k=v", (out.fields["videoSrc"] as JsonString).value)
            val at = (out.fields["at"] as JsonArray).items.map { it as JsonObject }
            fun str(v: JsonValue?) = if (v == null || v is JsonNull) null else (v as JsonString).value
            assertEquals(listOf("None", "None", "Arrow", "Arrow", "None"), at.map { str(it.fields["visual"]) })
            assertEquals(listOf(null, null, "주의", "주의", null), at.map { str(it.fields["label"]) })
            assertEquals(listOf(null, "차 다가옴", "차 다가옴", null, null), at.map { str(it.fields["say"]) })
        }
    }
}
