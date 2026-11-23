package hearspace.viewer

import hearspace.core.relay.RelayBatch
import hearspace.core.relay.RelayEvent
import hearspace.core.relay.RelaySample
import hearspace.core.relay.RelayScreen
import hearspace.core.relay.RelaySettings
import hearspace.core.relay.RelayStart
import hearspace.core.screen.UserScreen
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.JsonBool
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.MapHealth
import hearspace.core.types.MiniJson
import hearspace.core.types.SoundKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.math.sin

/** 데모 중계 서버(M66 설계 표 8의 ②③⑤⑥⑩). localhost에서 실제 HTTP로 올리고 받는다. */
class RelayServerTest {
    @TempDir lateinit var tmp: File

    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
    private val layers = RelayFiles.appLayers()

    private fun settings(): RelaySettings = RelaySettings.load(RelayFiles.base.readText(), listOf(
        """{"port": 0, "uploadKey": "up-key", "viewKey": "view-key", "clipDir": "${File(tmp, "clips").invariantSeparatorsPath}"}"""))

    private fun url(s: RelayServer, path: String) = "http://127.0.0.1:${s.port}$path"

    private fun post(s: RelayServer, path: String, body: String): Int =
        http.send(HttpRequest.newBuilder(URI(url(s, path))).POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding()).statusCode()

    private fun open(s: RelayServer, path: String): HttpResponse<InputStream> =
        http.send(HttpRequest.newBuilder(URI(url(s, path))).GET().build(), HttpResponse.BodyHandlers.ofInputStream())

    /** 업로더 대역: 0.2 s마다 20 ms 표본(멈춤 음원 하나, 방위가 움직임)과 바뀐 화면을 올린다. S10 시계 = 이 PC의 nanoTime. */
    private inner class Uploader(val s: RelayServer, val seconds: Double, val events: (Long) -> List<RelayEvent> = { emptyList() }) : AutoCloseable {
        val start = System.nanoTime()
        @Volatile var stop = false
        val t = thread(name = "FakeUploader") {
            var k = 0
            var next = start
            var lastScreen: UserScreen? = null
            while (!stop && System.nanoTime() - start < seconds * 1e9) {
                k++
                Thread.sleep(maxOf(0L, (start + k * 200_000_000L - System.nanoTime()) / 1_000_000))
                val now = System.nanoTime()
                val samples = ArrayList<RelaySample>()
                val screens = ArrayList<RelayScreen>()
                while (next <= now) {
                    val az = 40f * sin((next - start) / 1e9f * 3f)
                    samples += RelaySample(next, GuidanceState.NORMAL, 0f, false, listOf(AudioCmd(1, az, 0.8f, Band.STOP, SoundKind.FLOOR_PULSE, 250f, true, -1f)))
                    // 앱은 화면을 200 ms마다 다시 그린다(UI_REFRESH_MS)
                    if ((next - start) % 200_000_000L < 20_000_000L) {
                        val sc = UserScreen.of(true, null, false, GuidanceState.NORMAL, MapHealth.OK, Band.STOP, az.toInt().toFloat())
                        if (sc != lastScreen) { lastScreen = sc; screens += RelayScreen(next, sc, null) }
                    }
                    next += 20_000_000L
                }
                val b = RelayBatch("t", k.toLong(), now, if (k == 1) RelayStart("test", layers) else null, samples, events(now), screens)
                runCatching { post(s, "/upload?k=up-key", b.toJson()) }
            }
        }
        override fun close() { stop = true; t.join() }
    }

    /** SSE 읽기: 사건(이름, JSON)과 받은 시각(ns)을 모은다. [keep]이 false면 세기만 한다(10분 부하 시험에서 힙을 채우지 않게). */
    private inner class Sse(s: RelayServer, val keep: Boolean = true) : AutoCloseable {
        @Volatile var count = 0L
        val got: MutableList<Triple<String, JsonObject, Long>> = Collections.synchronizedList(ArrayList())
        private val res = open(s, "/events?k=view-key")
        private val t = thread(name = "SseReader") {
            runCatching {
                res.body().bufferedReader().use { rd ->
                    var name = ""
                    while (true) {
                        val line = rd.readLine() ?: break
                        when {
                            line.startsWith("event: ") -> name = line.removePrefix("event: ")
                            line.startsWith("data: ") -> { count++; if (keep) got += Triple(name, MiniJson.parse(line.removePrefix("data: ")) as JsonObject, System.nanoTime()) }
                        }
                    }
                }
            }
        }
        fun chats() = synchronized(got) { got.filter { it.first == "chat" } }
        fun screens() = synchronized(got) { got.filter { it.first == "screen" } }
        override fun close() { res.body().close(); t.join(2000) }
    }

    /** 소리 읽기: [seconds]만큼의 바이트와 첫 프레임 번호, 읽기 사이 가장 긴 간격. */
    private class AudioRead(val startFrame: Long, val bytes: ByteArray, val maxGapMs: Double)

    private fun readAudio(s: RelayServer, seconds: Double): AudioRead {
        val res = open(s, "/audio?k=view-key")
        assertEquals(200, res.statusCode())
        val start = res.headers().firstValue("X-Start-Frame").get().toLong()
        val want = (seconds * 48_000 * 4).toInt()
        val buf = ByteArray(want)
        var have = 0
        var last = System.nanoTime()
        var maxGap = 0L
        res.body().use { ins ->
            while (have < want) {
                val r = ins.read(buf, have, want - have)
                if (r < 0) break
                val now = System.nanoTime()
                if (have > 0) maxGap = maxOf(maxGap, now - last)
                last = now
                have += r
            }
        }
        return AudioRead(start, buf.copyOf(have), maxGap / 1e6)
    }

    private fun waitUntil(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(20) }
        return cond()
    }

    @Test
    fun `말 소리 표본율이 서버와 다르면 읽을 때 바꾼다(M67, S10 TTS 파일)`() {
        val dir = File(tmp, "clips24").apply { mkdirs() }
        val n = 24_000
        val pcm = ByteArray(2 * n).also { b ->
            for (i in 0 until n) {
                val v = (0.5 * sin(2 * Math.PI * 440 * i / 24_000) * 32767).toInt()
                b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
            }
        }
        val fmt = javax.sound.sampled.AudioFormat(24_000f, 16, 1, true, false)
        javax.sound.sampled.AudioSystem.write(javax.sound.sampled.AudioInputStream(pcm.inputStream(), fmt, n.toLong()),
            javax.sound.sampled.AudioFileFormat.Type.WAVE, File(dir, RelayClips.name("안내를 시작합니다.") + ".wav"))
        val clips = RelayClips.load(dir, 48_000)
        val c = clips.getValue(RelayClips.name("안내를 시작합니다."))
        assertEquals(2 * n, c.size)
        assertEquals(0.5, c.slice(1000 until c.size - 1000).maxOf { kotlin.math.abs(it) }.toDouble(), 0.01)
    }

    @Test
    fun `열쇠가 틀리면 403이고 페이지에는 설정 값이 들어간다`() {
        RelayServer(settings(), null).use { s ->
            assertEquals(403, open(s, "/?k=wrong").statusCode())
            assertEquals(403, post(s, "/upload?k=view-key", "{}"))
            val page = open(s, "/?k=view-key").body().readAllBytes().toString(Charsets.UTF_8)
            assertTrue("const S = {\"rate\":48000" in page, page.take(200))
            assertEquals(400, post(s, "/upload?k=up-key", "not json"))
            assertEquals(409, post(s, "/upload?k=up-key", RelayBatch("x", 0, 0, null, emptyList(), emptyList(), emptyList()).toJson()))
        }
    }

    @Test
    fun `② 다른 때 붙은 청취자 셋이 받은 바이트는 같은 프레임에서 모두 같다`() {
        RelayServer(settings(), null).use { s ->
            Uploader(s, 4.0).use {
                Thread.sleep(800) // 묵힘(0.6 s) 뒤 소리가 나기 시작한 다음에 붙는다
                val reads = Collections.synchronizedList(ArrayList<AudioRead>())
                val ts = listOf(0L, 300L, 600L).map { d -> thread { Thread.sleep(d); reads += readAudio(s, 1.5) } }
                ts.forEach { it.join() }
                val from = reads.maxOf { it.startFrame }
                val to = reads.minOf { it.startFrame + it.bytes.size / 4 }
                assertTrue(to - from >= 48_000 / 2, "overlap ${(to - from) / 48.0} ms")
                val slices = reads.map { r -> r.bytes.copyOfRange(((from - r.startFrame) * 4).toInt(), ((to - r.startFrame) * 4).toInt()) }
                assertTrue(slices.all { it.contentEquals(slices[0]) })
                assertTrue(slices[0].any { it != 0.toByte() }, "overlap must contain sound")
                val gap = reads.maxOf { it.maxGapMs }
                println("RELAY SAME-BYTES: overlap ${(to - from) / 48.0} ms, max gap between reads ${"%.1f".format(gap)} ms")
                assertTrue(gap < 250, "chunks are held back: $gap ms") // VERIFY 확인: 청크가 바로 나간다
            }
        }
    }

    @Test
    fun `⑤ 채팅은 모든 페이지에 1초 안에 닿고, 늦게 온 페이지는 최근 50개를 받고, 길거나 잦은 글은 거부한다`() {
        RelayServer(settings(), null).use { s ->
            val pages = List(3) { Sse(s) }
            Thread.sleep(300)
            val sent = System.nanoTime()
            assertEquals(204, post(s, "/chat?k=view-key", """{"client": "c1", "nick": "평가자", "text": "기둥 앞에서 멈췄다가 왼쪽으로"}"""))
            assertTrue(waitUntil(2000) { pages.all { p -> p.chats().any { (it.second.fields["text"] as JsonString).value.startsWith("기둥") } } })
            val worst = pages.maxOf { p -> p.chats().first { (it.second.fields["text"] as JsonString).value.startsWith("기둥") }.third - sent } / 1e6
            println("RELAY CHAT: slowest page got the message in ${"%.1f".format(worst)} ms")
            assertTrue(worst < 1000)
            assertEquals(429, post(s, "/chat?k=view-key", """{"client": "c1", "nick": "평가자", "text": "또"}"""))
            assertEquals(400, post(s, "/chat?k=view-key", """{"client": "c2", "nick": "a", "text": "${"가".repeat(201)}"}"""))
            assertEquals(204, post(s, "/chat?k=view-key", """{"client": "c3", "nick": "a", "text": "${"가".repeat(200)}"}"""))
            assertEquals(400, post(s, "/chat?k=view-key", """{"client": "c4", "nick": "", "text": "x"}"""))
            for (i in 0 until 55) assertEquals(204, post(s, "/chat?k=view-key", """{"client": "m$i", "nick": "n", "text": "m$i"}"""))
            pages.forEach { it.close() }
            Sse(s).use { late ->
                assertTrue(waitUntil(2000) { late.chats().size >= 50 })
                Thread.sleep(200)
                val texts = late.chats().map { (it.second.fields["text"] as JsonString).value }
                assertEquals((5 until 55).map { "m$it" }, texts)
            }
        }
    }

    @Test
    fun `⑥ 업로드가 끊기면 끊김 사건이 오고, ③ 소리 모음에 없는 문장은 기록하고 채팅에 알린다`() {
        val run = File(tmp, "run")
        RelayServer(settings(), run).use { s ->
            Sse(s).use { page ->
                var said = false
                Uploader(s, 1.2) { now -> if (!said) { said = true; listOf(RelayEvent.SayStart(now - 100_000_000L, "소리 모음에 없는 문장")) } else emptyList() }.use { Thread.sleep(1300) }
                val stopped = System.nanoTime()
                assertTrue(waitUntil(2000) { page.screens().any { (it.second.fields["live"] as JsonBool).value } }, "live screen first")
                assertTrue(waitUntil(4500) { page.screens().any { !(it.second.fields["live"] as JsonBool).value && it.third > stopped } }, "lost after uploads stop")
                val lostAfter = (page.screens().first { !(it.second.fields["live"] as JsonBool).value && it.third > stopped }.third - stopped) / 1e9
                println("RELAY LOST: shown ${"%.2f".format(lostAfter)} s after the last upload (hold 0.6 s + lost 2 s)")
                assertTrue(lostAfter > 2.0)
                assertTrue(page.chats().any { (it.second.fields["sys"] as JsonBool).value })
            }
        }
        assertEquals("소리 모음에 없는 문장\n", File(run, "missing.txt").readText())
        assertTrue(File(run, "stats.csv").readLines().size > 3)
        // M68: 업로드마다 한 줄, 번호는 올린 차례대로
        val up = File(run, "uploads.csv").readLines()
        assertEquals("arrivalNs,session,seq,sentNs,firstTNs,lastTNs,samples,code,sampleUs", up[0])
        val rows = up.drop(1).map { it.split(',') }
        assertTrue(rows.size >= 4, "${rows.size} upload rows")
        assertEquals((1..rows.size).map { it.toString() }, rows.map { it[2] })
        assertTrue(rows.all { it[7] == "200" && it[6].toInt() > 0 && it[5].toLong() >= it[4].toLong() })
        // 표본 시각(첫 표본부터 µs): 개수가 맞고 끝 값이 끝 표본 시각과 같다
        assertTrue(rows.all { r -> r[8].split(';').let { us -> us.size == r[6].toInt() && us.last().toLong() == (r[5].toLong() - r[4].toLong()) / 1000 } })
    }

    @Test
    fun `⑩ 모의 페이지 20개가 소리와 사건을 받는 동안 송출이 끊기지 않는다`() {
        val seconds = System.getProperty("relay.loadS", "5").toDouble()
        RelayServer(settings(), null).use { s ->
            Uploader(s, seconds + 5).use {
                val ready = CountDownLatch(20)
                val result = Collections.synchronizedList(ArrayList<Triple<Long, Long, Double>>()) // (받은 바이트, 붙어 있던 ns, 가장 긴 간격 ms)
                val stop = java.util.concurrent.atomic.AtomicBoolean(false)
                val events = java.util.concurrent.atomic.AtomicLong()
                val pages = (0 until 20).map { i ->
                    thread(name = "Page$i") {
                        Sse(s, keep = false).use { sse ->
                            val res = open(s, "/audio?k=view-key")
                            ready.countDown()
                            val t0 = System.nanoTime()
                            var bytes = 0L
                            var last = t0
                            var gap = 0L
                            val buf = ByteArray(16384)
                            res.body().use { ins ->
                                while (!stop.get()) {
                                    val r = ins.read(buf)
                                    if (r < 0) break
                                    val now = System.nanoTime()
                                    gap = maxOf(gap, now - last)
                                    last = now
                                    bytes += r
                                }
                            }
                            result += Triple(bytes, last - t0, gap / 1e6)
                            events.addAndGet(sse.count)
                        }
                    }
                }
                assertTrue(ready.await(10, TimeUnit.SECONDS))
                val end = System.nanoTime() + (seconds * 1e9).toLong()
                var i = 0
                while (System.nanoTime() < end) {
                    post(s, "/chat?k=view-key", """{"client": "load$i", "nick": "청중", "text": "부하 시험 $i"}""")
                    i++
                    Thread.sleep(minOf(5000L, maxOf(1L, (end - System.nanoTime()) / 1_000_000)))
                }
                stop.set(true)
                pages.forEach { it.join(5000) }
                val worstDeficitS = result.maxOf { (b, ns, _) -> ns / 1e9 - b / (48_000.0 * 4) }
                val worstGap = result.maxOf { it.third }
                println("RELAY LOAD: ${result.size} pages × ${"%.0f".format(seconds)} s, listener skips ${s.listenerSkips.get()}, worst deficit ${"%.3f".format(worstDeficitS)} s, worst gap ${"%.1f".format(worstGap)} ms, chats $i, SSE events received ${events.get()}")
                assertEquals(20, result.size)
                assertEquals(0L, s.listenerSkips.get())
                assertTrue(worstDeficitS < 0.5, "a page fell behind real time by $worstDeficitS s")
            }
        }
    }
}
