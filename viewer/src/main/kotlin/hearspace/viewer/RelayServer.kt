package hearspace.viewer

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import hearspace.core.audio.Resample
import hearspace.core.relay.RelayBatch
import hearspace.core.relay.RelayPlayback
import hearspace.core.relay.RelayScreen
import hearspace.core.relay.RelaySettings
import hearspace.core.types.JsonBool
import hearspace.core.types.JsonNull
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.JsonValue
import hearspace.core.types.MiniJson
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.security.MessageDigest
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import kotlin.concurrent.withLock

/**
 * 최근 [cap]개를 번호와 함께 담는 고리(M66 설계 3.5절). 쓰는 쪽은 하나, 읽는 쪽마다 자기 다음 번호부터 기다려 가져간다.
 * 줄을 세우지 않는다: [cap]보다 뒤처진 읽는 쪽은 남아 있는 가장 오래된 것으로 건너뛰고 건너뛴 수를 받는다.
 */
class Ring<T>(private val cap: Int) {
    private val items = arrayOfNulls<Any?>(cap)
    private var next = 0L
    private val lock = ReentrantLock()
    private val more = lock.newCondition()

    fun publish(x: T) = lock.withLock {
        items[(next % cap).toInt()] = x
        next++
        more.signalAll()
    }

    /** 다음에 쓸 번호. */
    fun head(): Long = lock.withLock { next }

    /** [from]부터 있는 것을 [waitMs]까지 기다려 (다음 번호, 항목, 건너뛴 수). 시간이 지나도 없으면 빈 목록. */
    fun take(from: Long, waitMs: Long): Triple<Long, List<T>, Long> = lock.withLock {
        var left = waitMs * 1_000_000L
        while (next <= from) {
            if (left <= 0) return Triple(from, emptyList(), 0L)
            left = more.awaitNanos(left)
        }
        val start = maxOf(from, next - cap)
        @Suppress("UNCHECKED_CAST")
        Triple(next, (start until next).map { items[(it % cap).toInt()] as T }, start - from)
    }
}

/** 말 소리 모음(M66 설계 3.4절): 폴더 하나, 문장마다 `<SHA-1(UTF-8 문장)>.wav`(16-bit PCM 모노, 서버 표본율). */
object RelayClips {
    fun name(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** [dir]의 소리를 읽는다. 표본율이 [rate]가 아니면 바꾼다(M67: S10 TTS는 엔진이 표본율을 정함). 16-bit 모노가 아닌 파일은 건너뛰고 알린다. 폴더가 없으면 빈 모음. */
    fun load(dir: File, rate: Int): Map<String, FloatArray> = (dir.listFiles { f -> f.name.endsWith(".wav") } ?: emptyArray()).mapNotNull { f ->
        try {
            AudioSystem.getAudioInputStream(f).use { s ->
                val fm = s.format
                val fromHz = fm.sampleRate.toInt()
                if (fm.encoding != AudioFormat.Encoding.PCM_SIGNED || fm.sampleSizeInBits != 16 || fm.channels != 1 || fm.isBigEndian || fromHz <= 0) {
                    println("relay: clip skipped (need 16-bit mono): ${f.name} $fm")
                    null
                } else {
                    val b = s.readAllBytes()
                    val x = FloatArray(b.size / 2) { ((b[2 * it + 1].toInt() shl 8) or (b[2 * it].toInt() and 255)).toShort() / 32768f }
                    f.name.removeSuffix(".wav") to Resample.to(x, fromHz, rate)
                }
            }
        } catch (e: Exception) {
            println("relay: clip unreadable: ${f.name} ${e.message}")
            null
        }
    }.toMap()
}

/** 중계 설정 파일: bench 자원 `relay.json` 위에 git 밖 `data/relay/server.json`(열쇠), 그 위에 [extra]. */
object RelayFiles {
    val base: File get() = File(Repo.root, "app/src/bench/assets/relay.json")
    val local: File get() = File(Repo.root, "data/relay/server.json")
    fun settings(extra: File? = null): RelaySettings =
        RelaySettings.load(base.readText(), listOfNotNull(local.takeIf { it.isFile }, extra).map { it.readText() })
    /** 앱이 합치는 순서의 설정 층(AppConfig, 기기 층 없음): 재생기가 시작 칸에 넣는다. */
    fun appLayers(): List<String> = listOf("default.json", "risk-continuous.override.json", "harness.override.json", "situation.override.json", "semantic-combo.override.json")
        .map { File(Repo.root, "app/src/main/assets/config/$it").readText() }
}

/**
 * 데모 중계 서버(M66 설계 3.5~3.7절, 표 4). JDK 내장 HTTP 서버로 업로드를 받고, 렌더 스레드 하나가 서버 시계에 맞춰 [RelayPlayback]으로
 * 블록을 만들어 소리 고리에 넣는다. 모든 청취자는 같은 고리를 읽으므로 같은 번호의 바이트가 같다. 화면·끊김·채팅은 SSE 사건 고리로 보낸다.
 * 알고리즘(재생 규칙)은 core에 있고 여기는 받고 흘리기만 한다. [runDir]이 null이면 기록을 남기지 않는다(시험).
 */
class RelayServer(
    private val settings: RelaySettings,
    private val runDir: File?,
    private val clock: () -> Long = System::nanoTime,
    private val serverCommit: String = System.getProperty("hearspace.gitHash") ?: "unknown",
) : AutoCloseable {
    private val baseConfig = Repo.loadConfig("{}")
    private val clips = RelayClips.load(File(settings.clipDir).let { if (it.isAbsolute) it else File(Repo.root, settings.clipDir) }, baseConfig.audio.sampleRate)
    val playback = RelayPlayback(ReplayRunner.loadHrtf(), settings, baseConfig) { clips[RelayClips.name(it)] }
    private val n = playback.blockSize
    private val rate = playback.sampleRate
    private val audio = Ring<ByteArray>(maxOf(1, (settings.listenerLagMs.toLong() * rate / 1000 / n).toInt()))
    private val events = Ring<String>(256)
    private val recentChat = ArrayDeque<String>()
    @Volatile private var currentScreen = screenFrame(0, false, null)
    private val audioPages = AtomicInteger()
    private val ssePages = AtomicInteger()
    private val lastChatMs = ConcurrentHashMap<String, Long>()
    /** 2 s 넘게 뒤처져 건너뛴 블록 수(모든 청취자 합). */
    val listenerSkips = AtomicLong()
    private val missingSeen = ConcurrentHashMap.newKeySet<String>()
    private val warnedCommit = ConcurrentHashMap.newKeySet<String>()
    @Volatile private var running = true
    private val pool: ExecutorService = Executors.newCachedThreadPool { r -> Thread(r, "RelayHttp").apply { isDaemon = true } }
    private val http: HttpServer = HttpServer.create(InetSocketAddress(settings.bindHost, settings.port), 0)
    private val page: String = javaClass.getResource("/relay/index.html")!!.readText().replace("/*SETTINGS*/null", MiniJson.write(obj(
        "rate" to JsonNumber(rate.toDouble()), "clientBufferMs" to num(settings.clientBufferMs), "clientMaxBufferMs" to num(settings.clientMaxBufferMs),
        "lostMs" to num(settings.lostMs), "chatMaxChars" to num(settings.chatMaxChars), "nickMaxChars" to num(settings.nickMaxChars),
        "chatKeep" to num(settings.chatKeep), "clientStatsS" to num(settings.clientStatsS),
    ), pretty = false))
    private val worklet: ByteArray = javaClass.getResource("/relay/worklet.js")!!.readBytes()
    private val logo = File(Repo.root, "app/src/main/res/drawable-xxhdpi/logo_run.png")
    private val stats = runDir?.let { it.mkdirs(); File(it, "stats.csv").bufferedWriter() }
        ?.apply { appendLine("wallS,live,heldAgeMs,audioPages,ssePages,lateEvents,lateAlertsDropped,listenerSkips,missingTexts"); flush() }
    private val clients = runDir?.let { File(it, "clients.csv").bufferedWriter() }
        ?.apply { appendLine("wallS,client,bufferedMs,underruns,drops,played,ua"); flush() }
    /** 업로드마다 한 줄(M68 설계 3.3절): 묵힘을 다시 계산하는 데 쓴다. 시각은 서버 시계(t0부터 ns)와 S10 시계(ns). 번호가 빈 곳 = S10이 버리거나 못 보낸 묶음. */
    private val uploads = runDir?.let { File(it, "uploads.csv").bufferedWriter() }
        ?.apply { appendLine("arrivalNs,session,seq,sentNs,firstTNs,lastTNs,samples,code"); flush() }
    private val t0 = clock()
    private val renderThread = Thread(::renderLoop, "RelayRender")

    /** 실제로 연 포트(설정이 0이면 비어 있는 아무 포트). */
    val port: Int get() = http.address.port

    init {
        require(settings.uploadKey.isNotBlank() && settings.viewKey.isNotBlank()) {
            "relay keys are empty: set uploadKey and viewKey in ${RelayFiles.local}"
        }
        http.executor = pool // 정하지 않으면 모든 요청이 스레드 하나에서 차례로 돈다(JDK HttpServer)
        http.createContext("/") { ex -> serve(ex) }
        http.start()
        renderThread.start()
        println("relay: ${clips.size} speech clips, block $n @ $rate Hz, hold ${settings.holdMs} ms, run log ${runDir ?: "-"}")
    }

    // ---- 렌더 스레드 ----

    private fun renderLoop() {
        val out = FloatArray(2 * n)
        var k = 0L
        var lastState: Pair<Boolean, RelayScreen?>? = null
        var nextStatNs = t0
        var last: RelayPlayback.Block? = null
        while (running) {
            val now = clock()
            while (running && t0 + k * n * 1_000_000_000L / rate <= now) {
                val b = playback.render(t0 + k * n * 1_000_000_000L / rate, out)
                audio.publish(pcm16(out))
                val state = b.live to b.screen
                if (state != lastState) {
                    lastState = state
                    currentScreen = screenFrame(k * n, b.live, b.screen)
                    events.publish(currentScreen)
                }
                for (text in b.missing) if (missingSeen.add(text)) {
                    runDir?.let { File(it, "missing.txt").appendText(text + "\n") }
                    println("relay: speech clip missing: $text")
                    publishChat(chatFrame("", "말 한 줄이 소리 모음에 없어 빠졌어요", sys = true))
                }
                last = b
                k++
            }
            if (now >= nextStatNs) {
                nextStatNs += 1_000_000_000L
                stats?.run {
                    val b = last
                    val age = if (b?.pNs != null && b.sampleTNs != null) "%.1f".format((b.pNs!! - b.sampleTNs!!) / 1e6) else ""
                    appendLine("%.3f,%s,%s,%d,%d,%d,%d,%d,%d".format((now - t0) / 1e9, b?.live ?: false, age, audioPages.get(), ssePages.get(),
                        playback.lateEvents, playback.lateAlertsDropped, listenerSkips.get(), missingSeen.size))
                    flush()
                }
            }
            Thread.sleep(2)
        }
    }

    private fun pcm16(out: FloatArray): ByteArray {
        val b = ByteArray(out.size * 2)
        for (i in out.indices) {
            val s = (out[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt() // AudioPlayer·SonificationExport와 같은 변환
            b[2 * i] = s.toByte()
            b[2 * i + 1] = (s shr 8).toByte()
        }
        return b
    }

    // ---- HTTP ----

    private fun serve(ex: HttpExchange) {
        try {
            val q = query(ex)
            val view = ok(q["k"], settings.viewKey)
            when ("${ex.requestMethod} ${ex.requestURI.path}") {
                "GET /" -> if (view) send(ex, 200, "text/html; charset=utf-8", page.toByteArray()) else send(ex, 403)
                "GET /worklet.js" -> send(ex, 200, "text/javascript; charset=utf-8", worklet)
                "GET /logo.png" -> if (logo.isFile) send(ex, 200, "image/png", logo.readBytes()) else send(ex, 404)
                "GET /audio" -> if (view) streamAudio(ex) else send(ex, 403)
                "GET /events" -> if (view) streamEvents(ex) else send(ex, 403)
                "POST /chat" -> if (view) chat(ex) else send(ex, 403)
                "POST /stats" -> if (view) clientStats(ex) else send(ex, 403)
                "POST /upload" -> if (ok(q["k"], settings.uploadKey)) upload(ex) else send(ex, 403)
                else -> send(ex, 404)
            }
        } catch (e: IOException) {
            // 페이지가 연결을 끊음
        } catch (e: Exception) {
            println("relay: ${ex.requestURI.path}: $e")
            runCatching { send(ex, 500) }
        } finally {
            ex.close()
        }
    }

    private fun streamAudio(ex: HttpExchange) {
        if (audioPages.incrementAndGet() > settings.maxPages) { audioPages.decrementAndGet(); send(ex, 503); return }
        try {
            var k = audio.head()
            ex.responseHeaders.add("Content-Type", "application/octet-stream")
            ex.responseHeaders.add("Cache-Control", "no-store")
            ex.responseHeaders.add("X-Start-Frame", (k * n).toString())
            ex.sendResponseHeaders(200, 0) // 길이 0 = 청크 응답, 끝없이 흘림
            val os = ex.responseBody
            while (running) {
                val (next, blocks, skipped) = audio.take(k, 1000)
                if (skipped > 0) listenerSkips.addAndGet(skipped)
                for (b in blocks) os.write(b)
                os.flush()
                k = next
            }
        } finally {
            audioPages.decrementAndGet()
        }
    }

    private fun streamEvents(ex: HttpExchange) {
        if (ssePages.incrementAndGet() > settings.maxPages) { ssePages.decrementAndGet(); send(ex, 503); return }
        try {
            var k = events.head()
            ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
            ex.responseHeaders.add("Cache-Control", "no-store")
            ex.sendResponseHeaders(200, 0)
            val os = ex.responseBody
            val hello = StringBuilder(currentScreen)
            synchronized(recentChat) { recentChat.forEach { hello.append(it) } }
            os.write(hello.toString().toByteArray()); os.flush()
            while (running) {
                val (next, frames, _) = events.take(k, settings.sseKeepaliveS * 1000L)
                os.write((if (frames.isEmpty()) ": keepalive\n\n" else frames.joinToString("")).toByteArray())
                os.flush()
                k = next
            }
        } finally {
            ssePages.decrementAndGet()
        }
    }

    private fun chat(ex: HttpExchange) {
        val body = body(ex, 4096) ?: return send(ex, 413)
        val o = runCatching { MiniJson.parse(String(body, Charsets.UTF_8)) as JsonObject }.getOrNull() ?: return send(ex, 400)
        fun field(k: String) = (o.fields[k] as? JsonString)?.value?.replace(Regex("[\\p{Cntrl}]"), " ")?.trim()
        val client = field("client")
        val nick = field("nick")
        val text = field("text")
        if (client.isNullOrEmpty() || client.length > 64 || nick == null || text == null) return send(ex, 400)
        if (nick.codePointCount(0, nick.length) !in 1..settings.nickMaxChars || text.codePointCount(0, text.length) !in 1..settings.chatMaxChars) return send(ex, 400)
        val nowMs = clock() / 1_000_000
        val prev = lastChatMs[client]
        if (prev != null && nowMs - prev < settings.chatMinIntervalMs) return send(ex, 429)
        lastChatMs[client] = nowMs
        publishChat(chatFrame(nick, text, sys = false))
        send(ex, 204)
    }

    private fun publishChat(frame: String) {
        synchronized(recentChat) {
            recentChat.addLast(frame)
            while (recentChat.size > settings.chatKeep) recentChat.removeFirst()
        }
        events.publish(frame)
    }

    private fun clientStats(ex: HttpExchange) {
        val body = body(ex, 4096) ?: return send(ex, 413)
        val o = runCatching { MiniJson.parse(String(body, Charsets.UTF_8)) as JsonObject }.getOrNull() ?: return send(ex, 400)
        fun v(k: String) = when (val x = o.fields[k]) {
            is JsonNumber -> if (x.value == Math.rint(x.value)) x.value.toLong().toString() else "%.1f".format(x.value)
            is JsonString -> x.value.replace(Regex("[,\\p{Cntrl}]"), " ").take(160)
            else -> ""
        }
        clients?.run {
            synchronized(this) { appendLine("%.1f,%s,%s,%s,%s,%s,%s".format((clock() - t0) / 1e9, v("client"), v("bufferedMs"), v("underruns"), v("drops"), v("played"), v("ua"))); flush() }
        }
        send(ex, 204)
    }

    private fun upload(ex: HttpExchange) {
        val body = body(ex, settings.uploadMaxBytes) ?: return send(ex, 413)
        val batch = try {
            RelayBatch.parse(String(body, Charsets.UTF_8))
        } catch (e: Exception) {
            return send(ex, 400)
        }
        val now = clock()
        val code = when (playback.accept(batch, now)) {
            RelayPlayback.Accept.OK -> {
                val c = batch.start?.appCommit
                if (c != null && c != serverCommit && warnedCommit.add(batch.session)) println("relay: WARNING app commit $c != server commit $serverCommit")
                200
            }
            RelayPlayback.Accept.NEED_START -> 409
            RelayPlayback.Accept.BAD -> 400
        }
        uploads?.run {
            val s = batch.samples
            synchronized(this) { appendLine("${now - t0},${batch.session.replace(',', ' ')},${batch.seq},${batch.sentNs},${s.firstOrNull()?.tNs ?: ""},${s.lastOrNull()?.tNs ?: ""},${s.size},$code"); flush() }
        }
        send(ex, code)
    }

    private fun body(ex: HttpExchange, limit: Int): ByteArray? = ex.requestBody.readNBytes(limit + 1).takeIf { it.size <= limit }

    private fun send(ex: HttpExchange, code: Int, type: String? = null, body: ByteArray? = null) {
        type?.let { ex.responseHeaders.add("Content-Type", it) }
        ex.responseHeaders.add("Cache-Control", "no-store")
        ex.responseHeaders.add("Referrer-Policy", "no-referrer")
        if (body == null || body.isEmpty()) ex.sendResponseHeaders(code, -1) else {
            ex.sendResponseHeaders(code, body.size.toLong())
            ex.responseBody.write(body)
        }
    }

    private fun query(ex: HttpExchange): Map<String, String> = ex.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
        val kv = it.split('=', limit = 2)
        URLDecoder.decode(kv[0], Charsets.UTF_8) to URLDecoder.decode(kv.getOrElse(1) { "" }, Charsets.UTF_8)
    }

    /** 열쇠 비교는 시간이 일정한 비교로. */
    private fun ok(given: String?, want: String) = given != null && MessageDigest.isEqual(given.toByteArray(), want.toByteArray())

    override fun close() {
        running = false
        http.stop(0)
        pool.shutdownNow()
        renderThread.join(2000)
        stats?.close()
        clients?.close()
        uploads?.let { synchronized(it) { it.close() } }
    }

    companion object {
        private fun obj(vararg p: Pair<String, JsonValue?>) = JsonObject(LinkedHashMap<String, JsonValue>().apply { for ((k, v) in p) put(k, v ?: JsonNull) })
        private fun num(x: Int) = JsonNumber(x.toDouble())

        /** 화면 사건: 그 블록의 프레임 번호, 끊김 여부, 화면 요소(없으면 null). */
        fun screenFrame(frame: Long, live: Boolean, screen: RelayScreen?) = "event: screen\ndata: " + MiniJson.write(obj(
            "frame" to JsonNumber(frame.toDouble()), "live" to JsonBool(live), "screen" to screen?.let(RelayBatch::screenJson)), pretty = false) + "\n\n"

        fun chatFrame(nick: String, text: String, sys: Boolean) = "event: chat\ndata: " + MiniJson.write(obj(
            "nick" to JsonString(nick), "text" to JsonString(text), "sys" to JsonBool(sys)), pretty = false) + "\n\n"
    }
}

/** `./gradlew :viewer:relay [-PrelayConfig=<덮어쓰기 JSON>]`. 열쇠는 git 밖 `data/relay/server.json`. */
fun main(args: Array<String>) {
    val s = RelayFiles.settings(args.firstOrNull()?.let(::File))
    val dir = File(Repo.root, "data/relay/runs/" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")))
    val server = RelayServer(s, dir)
    Runtime.getRuntime().addShutdownHook(Thread { server.close() })
    println("relay: page http://${s.bindHost}:${server.port}/?k=${s.viewKey}")
    Thread.currentThread().join()
}
