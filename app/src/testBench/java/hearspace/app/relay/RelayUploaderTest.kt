package hearspace.app.relay

import com.sun.net.httpserver.HttpServer
import hearspace.core.relay.RelayBatch
import hearspace.core.relay.RelayEvent
import hearspace.core.relay.RelaySample
import hearspace.core.relay.RelayScreen
import hearspace.core.relay.RelaySettings
import hearspace.core.relay.RelayStart
import hearspace.core.screen.UserScreen
import hearspace.core.types.AlertKind
import hearspace.core.types.GuidanceState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URLEncoder
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * 데모 중계 업로더(M67 설계 표 7의 ②, PC 쪽). JDK HTTP 서버를 가짜 중계 서버로 두고 실제 HTTP로 올린다.
 * 묶음 간격·시작 칸·사건·화면, 그리고 느린 응답·서버 없음에서도 표본이 계속 쌓이고 예외 없이 버리는지 본다.
 */
class RelayUploaderTest {
    private val base = File("src/bench/assets/relay.json").readText() // 단위 시험은 app 모듈 폴더에서 돈다

    private fun settings(url: String, timeoutMs: Int = 300) =
        RelaySettings.load(base, listOf("""{"serverUrl": "$url", "uploadKey": "k e/y", "uploadTimeoutMs": $timeoutMs}"""))

    private val start = RelayStart("test", listOf("{}"))
    private val screen = UserScreen.of(false, null, false, null, null, null, null, -1f, 1f)

    /** 가짜 중계 서버: 받은 묶음을 모으고, [respond]가 정한 코드로 [delayMs] 뒤에 답한다. */
    private class Fake : AutoCloseable {
        val batches: MutableList<RelayBatch> = Collections.synchronizedList(ArrayList())
        val queries: MutableList<String?> = Collections.synchronizedList(ArrayList())
        @Volatile var respond: (Int) -> Int = { 200 }
        @Volatile var delayMs = 0L
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = Executors.newCachedThreadPool()
            createContext("/upload") { ex ->
                val b = RelayBatch.parse(String(ex.requestBody.readAllBytes(), Charsets.UTF_8))
                queries += ex.requestURI.rawQuery
                val i = synchronized(batches) { batches += b; batches.size - 1 }
                if (delayMs > 0) Thread.sleep(delayMs)
                ex.sendResponseHeaders(respond(i), -1)
                ex.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }

    private class Source {
        val calls = AtomicInteger()
        fun next(): RelaySample = RelaySample(System.nanoTime(), GuidanceState.NORMAL, 0f, false, emptyList()).also { calls.incrementAndGet() }
    }

    private val logs: MutableList<String> = Collections.synchronizedList(ArrayList())

    @Test
    fun `묶음은 0_2 s마다 표본 10개 안팎이고, 시작 칸은 첫 묶음과 409 뒤에만, 사건은 한 번씩, 화면은 묶음마다 실린다`() {
        Fake().use { fake ->
            fake.respond = { i -> if (i == 3) 409 else 200 }
            val src = Source()
            val up = RelayUploader(settings(fake.url), "s1", start, System::nanoTime, src::next, log = { logs += it })
            up.screen(RelayScreen(System.nanoTime(), screen, "고지"))
            val sent = ArrayList<RelayEvent>()
            for (k in 0 until 6) {
                Thread.sleep(150)
                val e = if (k % 2 == 0) RelayEvent.Alert(System.nanoTime(), AlertKind.READY) else RelayEvent.SayStart(System.nanoTime(), "안내를 시작합니다.")
                sent += e
                up.event(e)
            }
            Thread.sleep(700)
            up.close()
            val bs = synchronized(fake.batches) { ArrayList(fake.batches) }
            println("RELAY uploader: batches ${bs.size}, samples ${up.samples.get()}, sent ${up.sent.get()}, 409 ${up.restarts.get()}, dropped ${up.dropped.get()}")
            assertTrue(bs.size in 6..10, "batches ${bs.size}")
            assertNotNull(bs[0].start, "first batch carries start")
            assertNull(bs[1].start)
            assertNotNull(bs[4].start, "batch after 409 carries start")
            assertNull(bs[5].start)
            assertTrue(bs.all { it.screens.size == 1 && it.samples.size <= 11 }, "one screen per batch, about 10 samples")
            assertEquals(sent.toSet(), bs.flatMap { it.events }.toSet())
            assertEquals(sent.size, bs.sumOf { it.events.size }, "each event once")
            val ts = bs.flatMap { b -> b.samples.map { it.tNs } }
            assertTrue(ts.zipWithNext().all { (a, b) -> b > a }, "samples in time order, no repeats")
            assertTrue(fake.queries.all { it == "k=" + URLEncoder.encode("k e/y", "UTF-8") })
            assertTrue(logs.none { "failed" in it && "tick" in it })
        }
    }

    @Test
    fun `느린 응답에도 표본은 20 ms마다 쌓이고 묶음은 예외 없이 버리며, 응답이 돌아오면 다시 붙는다`() {
        Fake().use { fake ->
            fake.delayMs = 1000 // 시간 제한 300 ms보다 길게
            val src = Source()
            val up = RelayUploader(settings(fake.url), "s2", start, System::nanoTime, src::next, log = { logs += it })
            Thread.sleep(1200)
            fake.delayMs = 0
            Thread.sleep(900)
            up.close()
            println("RELAY slow: samples ${up.samples.get()} (calls ${src.calls.get()}), sent ${up.sent.get()}, failed ${up.failed.get()}, dropped ${up.dropped.get()}")
            assertTrue(up.samples.get() >= 80, "sampler kept 50 Hz: ${up.samples.get()} in 2.1 s")
            assertTrue(up.failed.get() >= 1 && up.dropped.get() >= 1)
            assertTrue(up.sent.get() >= 1, "recovered after the server answered again")
            assertTrue(logs.none { "tick failed" in it })
        }
    }

    @Test
    fun `서버가 없어도 표본은 계속 쌓이고 실패만 센다`() {
        val port = ServerSocket(0).use { it.localPort } // 닫힌 포트
        val src = Source()
        val up = RelayUploader(settings("http://127.0.0.1:$port"), "s3", start, System::nanoTime, src::next, log = { logs += it })
        Thread.sleep(1000)
        up.close()
        println("RELAY no server: samples ${up.samples.get()}, failed ${up.failed.get()}, dropped ${up.dropped.get()}")
        assertTrue(up.samples.get() >= 40)
        assertTrue(up.failed.get() >= 3)
        assertEquals(0, up.sent.get())
        assertTrue(logs.none { "tick failed" in it })
    }
}
