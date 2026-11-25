package hearspace.viewer

import hearspace.core.relay.RelayBatch
import hearspace.core.relay.RelayScreen
import hearspace.core.relay.RelaySettings
import hearspace.core.screen.UserScreen
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.JsonArray
import hearspace.core.types.JsonBool
import hearspace.core.types.JsonNull
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.MapHealth
import hearspace.core.types.MiniJson
import hearspace.core.types.SoundKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 중계 페이지(M66 설계 표 8의 ④와 ⑤ 일부): 화면 없는 Edge·Chrome으로 페이지를 열어(`--dump-dom`), 앱 화면 요소의 모든 갈래를
 * 그린 결과가 앱 `UserModeActivity.show()` 규칙(설계 표 5)과 같은지, 채팅 글이 HTML로 해석되지 않는지 본다. 브라우저가 없으면 건너뛴다.
 */
class RelayPageTest {
    @TempDir lateinit var tmp: File

    private fun browser(): File? = listOf(
        "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe", "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
        "C:/Program Files/Google/Chrome/Application/chrome.exe", "/usr/bin/google-chrome", "/usr/bin/chromium", "/usr/bin/chromium-browser",
    ).map(::File).firstOrNull { it.isFile }

    private val notice = "이 앱은 Google의 ARCore 기능을 포함합니다"
    private fun cmd(band: Band, az: Float, distM: Float = 1.84f, heightM: Float = 0f) = AudioCmd(1, az, distM, band, SoundKind.FLOOR_PULSE, 0f, true, heightM)
    private fun run(paused: Boolean, state: GuidanceState?, map: MapHealth?, c: AudioCmd? = null, speech: String? = null) =
        UserScreen.of(true, null, paused, state, map, c, speech, -1f, 1f)
    private val idleCases: List<UserScreen> = listOf(
        UserScreen.of(false, null, false, null, null, null, null, -1f, 1f),
        UserScreen.of(false, UserScreen.Notice.stopped("소리 출력이 멈춰 안내를 멈췄습니다"), false, null, null, null, null, -1f, 1f),
    )
    /** 안내 중인 화면: 칸 위치가 모두 같아야 한다(M72 설계 표 8 ①). 말·정보 줄 있음·없음, 방향 한 자리·두 자리, 높이 없음. */
    private val runCases: List<UserScreen> = listOf(
        run(true, GuidanceState.NORMAL, MapHealth.OK),
        run(true, GuidanceState.NORMAL, MapHealth.OK, speech = "안내를 일시정지합니다."),
        run(false, GuidanceState.UNKNOWN, null),
        run(false, GuidanceState.UNKNOWN, null, speech = "안내를 준비합니다."),
        run(false, GuidanceState.NORMAL, MapHealth.OK),
        run(false, GuidanceState.NORMAL, MapHealth.OK, cmd(Band.SILENT, 10f)),
        run(false, GuidanceState.NORMAL, MapHealth.OK, cmd(Band.WARN, 30f)),
        run(false, GuidanceState.NORMAL, MapHealth.OK, cmd(Band.WARN, 30f), "블록 위 물체, 2미터"),
        run(false, GuidanceState.NORMAL, MapHealth.OK, cmd(Band.STOP, -20.5f, 0.76f, 0.6f)),
        run(false, GuidanceState.NORMAL, MapHealth.OK, cmd(Band.STOP, -45f, 2.4f, Float.NaN), "바닥을 확인할 수 없습니다. 잠시 멈춰 주세요."),
        run(false, GuidanceState.NORMAL, MapHealth.DEGRADED),
        run(false, GuidanceState.DEGRADED, MapHealth.DEGRADED, cmd(Band.STOP, 45f, 1.2f, -0.6f)),
    )
    private val cases = idleCases + runCases

    private val white = "rgb(255, 255, 255)"
    private val caution = "rgb(234, 180, 49)" // run_caution #EAB431
    private val stop = "rgb(242, 106, 95)" // run_stop #F26A5F

    /** 앱 show()와 GuideView 규칙으로 기대하는 페이지 결과. */
    private fun expected(s: UserScreen): Map<String, Any?> {
        val arrow = s.visual as? UserScreen.Visual.Arrow
        val start = s.idle && s.visual == UserScreen.Visual.Logo
        fun js(x: Float) = if (x == Math.rint(x.toDouble()).toFloat()) x.toInt().toString() else x.toString()
        return mapOf(
            "title" to s.title, "titleColor" to if (s.titleTone == UserScreen.Tone.CAUTION) caution else white, "dot" to (s.titleTone == UserScreen.Tone.GUIDE),
            "sub" to s.sub, "visual" to (if (arrow != null) "Arrow" else s.visual.toString()), "az" to (arrow?.let { js(it.azimuthDeg) } ?: ""),
            "stop" to (arrow?.stop?.toString() ?: ""),
            // 페이지에서 할 수 없는 조작 글은 없다: 안내 줄 비움, 시작 전 큰 글은 TalkBack 문장으로
            "label" to if (start) s.spoken else s.label,
            "labelSize" to if (start) "28px" else "34px",
            "labelColor" to when { s.label == null || arrow == null -> white; s.labelTone == UserScreen.Tone.STOP -> stop; else -> caution },
            "hints" to "", "hintsBold" to emptyList<String>(),
            "notice" to if (start) notice else null,
            "detail" to (s.detail?.let { listOf(it.direction, it.distance, it.height ?: "").joinToString("|") } ?: "||"),
            "speech" to s.speech, "spk" to (s.speech != null),
        )
    }

    private fun plain(v: JsonValue?): Any? = when (v) {
        null, JsonNull -> null
        is JsonString -> v.value
        is JsonBool -> v.value
        is JsonArray -> v.items.map(::plain)
        else -> v.toString()
    }

    @Test
    fun `④ 페이지는 앱 화면 요소의 모든 갈래를 앱 규칙대로 그리고 설정 단추가 없으며, ⑤ 채팅 글은 글자로만 보인다`() {
        val exe = browser()
        assumeTrue(exe != null, "no Edge/Chrome for the page test")
        val settings = RelaySettings.load(RelayFiles.base.readText(), listOf("""{"port": 0, "uploadKey": "u", "viewKey": "v", "clipDir": "${File(tmp, "c").invariantSeparatorsPath}"}"""))
        RelayServer(settings, null).use { server ->
            val fixture = JsonObject(linkedMapOf(
                "screens" to JsonArray(cases.map { RelayBatch.screenJson(RelayScreen(0, it, notice)) }),
                "chats" to JsonArray(listOf(
                    JsonObject(linkedMapOf("nick" to JsonString("청중"), "text" to JsonString("<b>굵게</b><img src=x><script>bad()</script>"), "sys" to JsonBool(false))),
                    JsonObject(linkedMapOf("nick" to JsonString(""), "text" to JsonString("말 한 줄이 소리 모음에 없어 빠졌어요"), "sys" to JsonBool(true))),
                )),
            ))
            val url = "http://127.0.0.1:${server.port}/?k=v&test=1#" + URLEncoder.encode(MiniJson.write(fixture, pretty = false), Charsets.UTF_8).replace("+", "%20")
            val p = ProcessBuilder(exe!!.path, "--headless", "--disable-gpu", "--no-first-run", "--user-data-dir=${File(tmp, "profile").path}",
                "--window-size=390,844", "--virtual-time-budget=5000", "--dump-dom", url).redirectError(File(tmp, "browser.err")).start()
            val dom = p.inputStream.readAllBytes().toString(Charsets.UTF_8)
            p.waitFor(60, TimeUnit.SECONDS)
            val raw = Regex("""<pre id="test-out"[^>]*>(.*?)</pre>""", RegexOption.DOT_MATCHES_ALL).find(dom)?.groupValues?.get(1)
                ?: error("no test output in the page:\n${dom.take(2000)}")
            val out = MiniJson.parse(raw.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&")) as JsonObject
            assertFalse((out.fields["settingsButton"] as JsonBool).value, "settings button must not exist")
            val drawn = (out.fields["screens"] as JsonArray).items
            assertEquals(cases.size, drawn.size)
            cases.forEachIndexed { i, s ->
                val got = (drawn[i] as JsonObject).fields.mapValues { plain(it.value) }
                for ((k, want) in expected(s)) assertEquals(want, got[k], "case $i ${s.spoken} field $k")
            }
            // ① 자리 고정(M72): 안내 중인 모든 화면에서 칸 일곱의 왼쪽·위·폭·높이가 같고, 말 표시와 거리·높이 글의 자리도 같다
            val run = drawn.drop(idleCases.size).map { it as JsonObject }
            fun field(o: JsonObject, k: String) = plain(o.fields[k]).toString()
            assertEquals(1, run.map { field(it, "slots") }.toSet().size, "slot boxes differ: ${run.map { field(it, "slots") }}")
            val withDetail = run.filterIndexed { i, _ -> runCases[i].detail != null }
            assertTrue(withDetail.size >= 4)
            assertEquals(1, withDetail.map { field(it, "dist") }.toSet().size, "distance text moved")
            assertEquals(1, withDetail.filterIndexed { i, _ -> runCases.filter { it.detail != null }[i].detail?.height != null }.map { field(it, "hgt") }.toSet().size, "height text moved")
            assertEquals(1, run.map { field(it, "spkAt") }.toSet().size, "speech mark moved")
            assertEquals(listOf("청중: <b>굵게</b><img src=x><script>bad()</script>", "말 한 줄이 소리 모음에 없어 빠졌어요"), plain(out.fields["chatText"]))
            assertEquals(0.0, (out.fields["chatElements"] as hearspace.core.types.JsonNumber).value, "chat text must not become elements")
        }
    }
}
