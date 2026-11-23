package hearspace.app.relay

import hearspace.core.relay.RelayBatch
import hearspace.core.relay.RelayEvent
import hearspace.core.relay.RelaySample
import hearspace.core.relay.RelayScreen
import hearspace.core.relay.RelaySettings
import hearspace.core.relay.RelayStart
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 데모 중계 업로더(M67 설계 3.3절, 그림 2). Android를 쓰지 않아 PC 시험이 된다(Android 쪽은 [RelayTap]).
 * - 표본 스레드: `sampleHz`마다 [sample]로 표본 하나(앞 표본과 시각이 같으면 넣지 않음), `batchMs`마다 묶음 하나.
 * - 보내기 스레드: 묶음 하나를 POST. 앞 묶음을 보내는 중이면 새 묶음의 표본은 버린다(대기열 없음). 버린 묶음의 사건은 다음 묶음에 싣는다.
 * - 사건(알림·말 시작·끝)은 잠금 없는 모음, 화면은 최신 하나를 묶음마다 싣는다(서버는 시각 열쇠로 저장해 겹쳐도 하나).
 * - 시작 칸은 세션 첫 묶음과 409 뒤에 붙이고, 시작 칸을 실은 묶음이 200을 받으면 뗀다.
 */
class RelayUploader(
    private val settings: RelaySettings,
    private val session: String,
    private val start: RelayStart,
    private val clockNs: () -> Long,
    private val sample: () -> RelaySample?,
    /** 두 스레드가 처음에 부른다(Android에서 스레드 몫을 낮춤). */
    private val threadInit: () -> Unit = {},
    private val log: (String) -> Unit = {},
) : AutoCloseable {
    private val events = ConcurrentLinkedQueue<RelayEvent>()
    private val screen = AtomicReference<RelayScreen?>(null)
    private val needStart = AtomicBoolean(true)
    private val busy = AtomicBoolean(false)
    private val url = URI(settings.serverUrl.trimEnd('/') + "/upload?k=" + URLEncoder.encode(settings.uploadKey, "UTF-8")).toURL()
    private val perBatch = maxOf(1, settings.batchMs * settings.sampleHz / 1000)

    val samples = AtomicLong()
    val sent = AtomicLong()
    val dropped = AtomicLong()
    val failed = AtomicLong()
    val restarts = AtomicLong()

    // 표본 스레드만 쓰는 상태
    private val pending = ArrayList<RelaySample>()
    private var carried = ArrayList<RelayEvent>()
    private var ticks = 0L
    private var seq = 0L
    private var lastT = Long.MIN_VALUE
    private var lastLogNs = Long.MIN_VALUE
    @Volatile private var lastProblem: String? = null // 보내기 스레드가 쓰고 표본 스레드가 기록에 읽는다

    private fun factory(name: String) = { r: Runnable -> Thread({ threadInit(); r.run() }, name).apply { isDaemon = true } }
    private val sender = Executors.newSingleThreadExecutor(factory("RelaySend"))
    private val sampler = Executors.newSingleThreadScheduledExecutor(factory("RelaySample"))

    init {
        sampler.scheduleAtFixedRate(::tick, 0, 1_000_000L / settings.sampleHz, TimeUnit.MICROSECONDS)
    }

    /** 아무 스레드: 알림·말 사건. 잃지 않도록 모은다. */
    fun event(e: RelayEvent) {
        events.add(e)
    }

    /** 아무 스레드: 최신 화면으로 바꾼다. */
    fun screen(s: RelayScreen) = screen.set(s)

    private fun tick() {
        try { // 예외가 나가면 예약 실행이 멈추므로 모두 잡는다
            sample()?.let { if (it.tNs != lastT) { lastT = it.tNs; pending += it; samples.incrementAndGet() } }
            if (++ticks % perBatch == 0L) flush()
            val now = clockNs()
            if (now - lastLogNs >= LOG_INTERVAL_NS) {
                lastLogNs = now
                log("relay: session $session sent ${sent.get()} dropped ${dropped.get()} failed ${failed.get()} 409 ${restarts.get()}" +
                    (lastProblem?.let { " last $it" } ?: ""))
            }
        } catch (e: Exception) {
            log("relay: sample tick failed: $e")
        }
    }

    private fun flush() {
        val evs = carried
        while (true) evs += events.poll() ?: break
        val b = RelayBatch(session, seq++, clockNs(), if (needStart.get()) start else null, ArrayList(pending), evs, listOfNotNull(screen.get()))
        pending.clear()
        if (!busy.compareAndSet(false, true)) {
            dropped.incrementAndGet()
            carried = evs // 사건은 버리지 않고 다음 묶음에(서버는 늦은 사건 규칙으로 섞는다)
            return
        }
        carried = ArrayList()
        sender.execute {
            try { post(b) } finally { busy.set(false) }
        }
    }

    private fun post(b: RelayBatch) {
        val body = b.toJson().toByteArray(Charsets.UTF_8)
        var c: HttpURLConnection? = null
        try {
            c = url.openConnection() as HttpURLConnection
            c.connectTimeout = settings.uploadTimeoutMs
            c.readTimeout = settings.uploadTimeoutMs
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.setFixedLengthStreamingMode(body.size)
            c.outputStream.use { it.write(body) }
            val code = c.responseCode
            // 응답 몸을 다 읽고 닫아야 연결을 다시 쓴다
            (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() }
            when (code) {
                200 -> { sent.incrementAndGet(); if (b.start != null) needStart.set(false) }
                409 -> { restarts.incrementAndGet(); needStart.set(true) }
                else -> { failed.incrementAndGet(); lastProblem = "HTTP $code" }
            }
        } catch (e: IOException) {
            failed.incrementAndGet()
            lastProblem = e.javaClass.simpleName
            c?.disconnect()
        }
    }

    override fun close() {
        sampler.shutdownNow()
        sender.shutdownNow() // 보내는 중인 요청은 시간 제한 안에 끝난다(데몬 스레드)
    }

    companion object {
        private const val LOG_INTERVAL_NS = 10_000_000_000L
    }
}
