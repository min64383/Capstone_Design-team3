package hearspace.core.relay

import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.screen.UserScreen
import hearspace.core.types.AlertKind
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.ConfigException
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.MapHealth
import hearspace.core.types.SoundKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.sin

/** 데모 중계 재생 시계(M66 설계 표 8의 ①③⑥)와 묶음 형식·중계 설정. 시각은 합성(S10 시계와 서버 시계를 따로 둠). */
class RelayPlaybackTest {
    private val assets = File(System.getProperty("hearspace.hrtfAsset")).parentFile.parentFile
    /** 앱이 합치는 순서와 같은 설정 층(AppConfig, 기기 층 없음). */
    private val layers = listOf("default.json", "risk-continuous.override.json", "harness.override.json", "situation.override.json", "semantic-combo.override.json")
        .map { File(assets, "config/$it").readText() }
    private val hrtf = Hrtf.parse(File(System.getProperty("hearspace.hrtfAsset")).readBytes())
    private val settings = RelaySettings.load(File(System.getProperty("hearspace.relaySettings")).readText())
    private val config = ConfigLoader.load(layers[0], layers.drop(1))
    private val n = config.audio.blockSize
    private val sr = config.audio.sampleRate
    private val holdNs = settings.holdMs * 1_000_000L
    private val stepNs = 1_000_000_000L / settings.sampleHz
    private val batchNs = settings.batchMs * 1_000_000L

    private val s10T0 = 5_000_000_000_000L // S10 시계(부팅 뒤 약 83분)
    private val wallOffset = 123_456_789_000L // 서버 시계 = S10 시계 + 이것 + 망 지연

    private fun wall(k: Int, w0: Long) = w0 + k.toLong() * n * 1_000_000_000L / sr

    private fun cmd(id: Int, az: Float, dist: Float, band: Band, age: Float) =
        AudioCmd(id, az, dist, band, SoundKind.FLOOR_PULSE, age, inCorridor = true, heightDeltaM = -1f)

    /** 상태가 바뀌고(준비 → 안내 → 바닥 확인 → 안내) 음원 셋 중 하나는 늘 나이를 넘는 표본 열. */
    private fun richSample(i: Int): RelaySample {
        val t = s10T0 + i * stepNs
        val sec = i * stepNs / 1e9
        val state = when {
            sec < 0.3 -> GuidanceState.UNKNOWN
            sec in 1.5..1.8 -> GuidanceState.DEGRADED
            else -> GuidanceState.NORMAL
        }
        val cmds = if (state == GuidanceState.UNKNOWN) emptyList() else listOf(
            cmd(1, -60f + 40f * sin(i * 0.1f), 0.8f, Band.STOP, 250f),
            cmd(2, 30f, 2.5f, Band.WARN, 300f),
            cmd(3, 0f, 1.2f, Band.STOP, 600f), // 나이 초과: 렌더러가 버린다
        )
        return RelaySample(t, state, 10f, speechActive = sec in 2.0..2.4, cmds)
    }

    /** 표본 열을 0.2 s 묶음으로 나눠 (서버 도착 시각, 묶음)으로. 묶음 i의 망 지연은 [delayMs](i). */
    private fun batches(samples: List<RelaySample>, delayMs: (Int) -> Long, events: List<RelayEvent> = emptyList(), session: String = "s1"): List<Pair<Long, RelayBatch>> {
        val out = ArrayList<Pair<Long, RelayBatch>>()
        var from = samples.first().tNs
        var i = 0
        while (from <= samples.last().tNs) {
            val to = from + batchNs
            val sent = to
            val b = RelayBatch(session, i.toLong(), sent, if (i == 0) RelayStart("test", layers) else null,
                samples.filter { it.tNs in from until to }, events.filter { it.tNs in from until to }, emptyList())
            out += (sent + wallOffset + delayMs(i) * 1_000_000L) to b
            from = to
            i++
        }
        return out.sortedBy { it.first }
    }

    /** 서버처럼: 블록마다 그때까지 도착한 묶음을 받고 렌더한다. */
    private fun run(play: RelayPlayback, arrivals: List<Pair<Long, RelayBatch>>, blocks: Int, w0: Long, onBlock: (Int, Long, FloatArray, RelayPlayback.Block) -> Unit) {
        var next = 0
        val out = FloatArray(2 * n)
        for (k in 0 until blocks) {
            val w = wall(k, w0)
            while (next < arrivals.size && arrivals[next].first <= w) {
                assertEquals(RelayPlayback.Accept.OK, play.accept(arrivals[next].second, arrivals[next].first))
                next++
            }
            onBlock(k, w, out, play.render(w, out))
        }
    }

    private fun w0() = s10T0 + wallOffset // 첫 블록 = 첫 표본의 서버 시각(묵힘 때문에 처음 0.6 s는 표본 없음)

    @Test
    fun `① 재생 시계 출력은 같은 규칙으로 고른 안내 출력을 렌더러에 직접 넣은 것과 바이트까지 같다`() {
        val samples = (0 until 175).map(::richSample) // 3.5 s
        val arrivals = batches(samples, delayMs = { listOf(40L, 0L, 120L, 75L, 150L, 10L)[it % 6] })
        val ref = BinauralRenderer(config, hrtf)
        val refOut = FloatArray(2 * n)
        var energy = 0.0
        var accepted = 0
        var off = Long.MAX_VALUE
        run(RelayPlayback(hrtf, settings, config) { null }, arrivals, blocks = 900, w0 = w0()) { _, w, out, _ ->
            // 같은 규칙을 이 시험 안에서 따로: 도착한 묶음의 (도착 − 보낸 시각) 최솟값, P = w − 시계 차 − 묵힘, P 이하 마지막 표본
            while (accepted < arrivals.size && arrivals[accepted].first <= w) { off = minOf(off, arrivals[accepted].first - arrivals[accepted].second.sentNs); accepted++ }
            if (off == Long.MAX_VALUE) refOut.fill(0f) else {
                val p = w - off - holdNs
                val held = arrivals.take(accepted).flatMap { it.second.samples }.filter { it.tNs <= p }.maxByOrNull { it.tNs }
                val g = if (held == null) GuidanceOutput(p, GuidanceState.UNKNOWN, emptyList(), Float.NaN, null)
                else GuidanceOutput(p, held.state, held.commands.map { it.copy(infoAgeMs = it.infoAgeMs + (p - held.tNs) / 1e6f) }, held.headingDeg, null)
                ref.renderWithSpeech(g, refOut, held?.speechActive ?: false)
            }
            assertTrue(out.contentEquals(refOut), "block at $w differs")
            for (v in out) energy += v * v
        }
        assertTrue(energy > 1.0, "the test sequence must make sound: $energy")
    }

    @Test
    fun `⑥ 묶음 순서가 바뀌어도 재생 시각 뒤 표본을 쓰지 않고, 묵힘 안이면 늘 최신 표본을 쓴다`() {
        val samples = (0 until 250).map(::richSample)
        // 0.2 s 간격보다 큰 흔들림(최대 0.35 s)으로 도착 순서가 바뀐다(시작 칸이 든 첫 묶음은 먼저 도착: 아니면 서버가 409로 다시 달라고 한다). 0.2 + 0.35 < 0.6 s라 늦은 표본은 없어야 한다
        val arrivals = batches(samples, delayMs = { if (it == 0) 0L else listOf(350L, 0L, 300L, 20L, 340L, 100L, 0L)[it % 7] })
        assertTrue(arrivals.zipWithNext().any { (a, b) -> a.second.seq > b.second.seq }, "arrival order must be shuffled")
        var future = 0
        var maxLagNs = 0L
        val play = RelayPlayback(hrtf, settings, config) { null }
        run(play, arrivals, blocks = 1000, w0 = w0()) { _, _, _, b ->
            val p = b.pNs ?: return@run
            val t = b.sampleTNs ?: return@run
            if (t > p) future++
            if (p <= samples.last().tNs) maxLagNs = maxOf(maxLagNs, p - t)
        }
        assertEquals(0, future)
        assertTrue(maxLagNs < stepNs, "held sample lag ${maxLagNs / 1e6} ms")
        assertEquals(0L, play.lateEvents)
    }

    @Test
    fun `⑥ 나이가 허용치를 넘은 음원은 소리가 0이고, 표본이 끊김 기준보다 늙으면 끊김이다`() {
        val samples = (0 until 50).map { i -> RelaySample(s10T0 + i * stepNs, GuidanceState.NORMAL, 0f, false, listOf(cmd(1, 20f, 0.8f, Band.STOP, 600f))) }
        val lastT = samples.last().tNs
        var sawLive = false
        var sawLost = false
        run(RelayPlayback(hrtf, settings, config) { null }, batches(samples, { 0L }), blocks = 800, w0 = w0()) { _, _, out, b ->
            assertTrue(out.all { it == 0f }, "aged source must be silent")
            val p = b.pNs ?: return@run
            if (p < s10T0) return@run
            val expectLive = p - lastT <= settings.lostMs * 1_000_000L
            assertEquals(expectLive, b.live, "live at p − last = ${(p - lastT) / 1e6} ms")
            if (b.live) sawLive = true else sawLost = true
        }
        assertTrue(sawLive && sawLost)
    }

    /** 소리 없는 표본(준비 중, 음원 없음): 렌더러 출력이 0이라 섞인 말 소리만 남는다. */
    private fun quiet(count: Int) = (0 until count).map { RelaySample(s10T0 + it * stepNs, GuidanceState.UNKNOWN, Float.NaN, false, emptyList()) }

    @Test
    fun `③ 말 소리는 시작 사건이 지난 블록부터 섞이고, 끝에서 멈추고, 새 말이 앞 말을 끊고, 없는 문장은 알린다`() {
        val hello = FloatArray(4800) { (it + 1) / 10_000f }
        val other = FloatArray(4800) { -(it + 1) / 20_000f }
        val clips = mapOf("안녕" to hello, "다음" to other)
        val ms = 1_000_000L
        val events = listOf(
            RelayEvent.SayStart(s10T0 + 500 * ms, "안녕"), RelayEvent.SayEnd(s10T0 + 550 * ms),
            RelayEvent.SayStart(s10T0 + 700 * ms, "안녕"), RelayEvent.SayStart(s10T0 + 720 * ms, "다음"),
            RelayEvent.SayStart(s10T0 + 900 * ms, "없는 문장"),
        )
        val rows = ArrayList<Pair<Long, FloatArray>>()
        val missing = ArrayList<String>()
        run(RelayPlayback(hrtf, settings, config) { clips[it] }, batches(quiet(60), { 0L }, events), blocks = 600, w0 = w0()) { _, _, out, b ->
            b.pNs?.let { rows += it to out.copyOf() }
            missing += b.missing
        }
        assertEquals(listOf("없는 문장"), missing)
        fun check(start: Long, end: Long, clip: FloatArray) {
            val k0 = rows.indexOfFirst { it.first >= start }
            for (k in k0 until rows.size) {
                val (p, out) = rows[k]
                if (p >= end) break
                for (i in 0 until n) {
                    val pos = (k - k0) * n + i
                    val want = if (pos < clip.size) clip[pos] * config.audio.ttsVolume else 0f // S10이 재생할 때 곱하는 말 음량(M70)
                    assertEquals(want, out[2 * i], "L at block $k")
                    assertEquals(want, out[2 * i + 1], "R at block $k")
                }
            }
        }
        check(s10T0 + 500 * ms, s10T0 + 550 * ms, hello)
        val kEnd = rows.indexOfFirst { it.first >= s10T0 + 550 * ms }
        assertTrue(rows[kEnd].second.all { it == 0f }, "clip must stop at the end event")
        check(s10T0 + 700 * ms, s10T0 + 720 * ms, hello)
        check(s10T0 + 720 * ms, s10T0 + 900 * ms, other)
    }

    @Test
    fun `③ 늦게 온 말은 지난 만큼 건너뛴 위치부터 섞는다`() {
        val clip = FloatArray(48_000) { (it + 1) / 100_000f }
        val play = RelayPlayback(hrtf, settings, config) { clip }
        val arrivals = batches(quiet(100), { 0L }).toMutableList()
        val sayT = s10T0 + 300_000_000L
        // 말 시작이 든 묶음이 재생 시각을 지난 뒤(1.2 s 뒤) 도착
        arrivals += (sayT + wallOffset + 1_200_000_000L) to RelayBatch("s1", 99, sayT, null, emptyList(), listOf(RelayEvent.SayStart(sayT, "늦은 말")), emptyList())
        arrivals.sortBy { it.first }
        var first: Pair<Long, Float>? = null
        run(play, arrivals, blocks = 500, w0 = w0()) { _, _, out, b ->
            if (first == null && out[0] != 0f) first = b.pNs!! to out[0]
        }
        val (p, v) = first!!
        val skip = ((p - sayT) * sr / 1_000_000_000L).toInt()
        assertEquals(clip[skip] * config.audio.ttsVolume, v)
        assertEquals(1L, play.lateEvents)
    }

    @Test
    fun `늦은 알림은 허용 나이 안이면 섞고 넘으면 버린다`() {
        val play = RelayPlayback(hrtf, settings, config) { null }
        val arrivals = batches(quiet(100), { 0L }).toMutableList()
        val t = s10T0 + 300_000_000L
        arrivals += (t + wallOffset + 1_600_000_000L) to RelayBatch("s1", 98, t, null, emptyList(), listOf(RelayEvent.Alert(t, AlertKind.READY)), emptyList())
        arrivals.sortBy { it.first }
        run(play, arrivals, blocks = 500, w0 = w0()) { _, _, _, _ -> }
        assertEquals(1L, play.lateAlertsDropped)
    }

    @Test
    fun `처음 보는 세션은 시작 칸이 있어야 받고, 블록 크기가 다른 설정은 받지 않는다`() {
        val play = RelayPlayback(hrtf, settings, config) { null }
        val b = RelayBatch("x", 0, 0, null, emptyList(), emptyList(), emptyList())
        assertEquals(RelayPlayback.Accept.NEED_START, play.accept(b, 0))
        val other = b.copy(start = RelayStart("c", layers + """{"audio": {"blockSize": 512}}"""))
        assertEquals(RelayPlayback.Accept.BAD, play.accept(other, 0))
        assertEquals(RelayPlayback.Accept.OK, play.accept(b.copy(start = RelayStart("c", layers)), 0))
        assertEquals(RelayPlayback.Accept.OK, play.accept(b.copy(seq = 1), 1))
    }

    @Test
    fun `묶음 JSON은 쓴 그대로 읽힌다`() {
        val screen = UserScreen.of(true, null, false, GuidanceState.NORMAL, MapHealth.OK,
            AudioCmd(3, -20.5f, 0.84f, Band.STOP, SoundKind.FLOOR_PULSE, 12f, true, 0.5f), "블록 끝, 2미터", -1f, 1f)
        val b = RelayBatch("세션", 7, s10T0, RelayStart("abc123", layers.take(2)),
            listOf(richSample(30), richSample(31), RelaySample(s10T0, GuidanceState.PAUSED, Float.NaN, true, emptyList())),
            listOf(RelayEvent.Alert(s10T0, AlertKind.START), RelayEvent.SayStart(s10T0 + 1, "블록 오른쪽으로 꺾임"), RelayEvent.SayEnd(s10T0 + 2)),
            listOf(RelayScreen(s10T0, screen, null), RelayScreen(s10T0 + 5, UserScreen.of(false, null, false, null, null, null, null, -1f, 1f), "고지 \"글\"\n둘째 줄")))
        assertEquals(b, RelayBatch.parse(b.toJson()))
        assertThrows(IllegalArgumentException::class.java) { RelayBatch.parse("""{"v": 2}""") }
    }

    @Test
    fun `중계 설정은 모르는 키와 잘못된 값을 거부하고 덮어쓰기를 얹는다`() {
        val base = File(System.getProperty("hearspace.relaySettings")).readText()
        assertEquals("", settings.viewKey)
        assertEquals("k", RelaySettings.load(base, listOf("""{"viewKey": "k"}""")).viewKey)
        assertThrows(ConfigException::class.java) { RelaySettings.load(base, listOf("""{"viewkey": "k"}""")) }
        assertThrows(ConfigException::class.java) { RelaySettings.load(base, listOf("""{"holdMs": 0}""")) }
        assertThrows(ConfigException::class.java) { RelaySettings.load(base, listOf("""{"clientMaxBufferMs": 100}""")) }
        assertFalse(settings.holdMs <= 0)
    }
}
