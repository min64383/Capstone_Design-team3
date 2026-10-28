package hearspace.app.runtime

import android.util.Log
import hearspace.app.TAG
import hearspace.core.pipeline.ClusterDebug
import hearspace.core.pipeline.ClusterDebugCsv
import hearspace.core.session.DeviceCsv
import hearspace.core.session.RunLog
import hearspace.core.session.SessionFormat
import hearspace.core.types.GuidanceOutput
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * 실행 로그(§10.1) 4종을 [dir]에 쓴다. 문자열 만들기와 파일 쓰기는 전용 스레드에서만 한다.
 * 로그는 파이프라인 데이터가 아니라 기록이므로 최신 값 교체가 아니라 큐로 모두 쓴다(줄을 버리면 분석이 틀어진다).
 * 오디오 스레드는 잠금 없는 큐에 넣기만 하고(A1 CO5: 쓰기 스레드와 잠금을 나누지 않는다), 쓰기 스레드가 [DRAIN_MS]마다 비운다.
 * 큐가 [MAX_PENDING]건을 넘거나(쓰기가 막힌 경우) 쓰기 오류가 나면 새 건을 버리고 [nDropped]에 센다: 실시간 스레드를 막지 않고,
 * 로그 오류로 앱(안내)을 죽이지 않는다(A1 FA4).
 */
class RunLogger(val dir: File) : AutoCloseable {

    private enum class Kind(val file: String, val header: String) {
        SLOW(RunLog.SLOW_PATH_FILE, RunLog.header(RunLog.SLOW_PATH_HEADER)),
        GUIDANCE(RunLog.GUIDANCE_FILE, RunLog.header(RunLog.GUIDANCE_HEADER)),
        OBSTACLES(RunLog.OBSTACLES_FILE, RunLog.header(RunLog.OBSTACLES_HEADER)),
        CLUSTERS(RunLog.CLUSTER_DEBUG_FILE, ClusterDebugCsv.HEADER),
        DEVICE(SessionFormat.DEVICE_FILE, DeviceCsv.headerLine()),
    }

    private val queue = ConcurrentLinkedQueue<Pair<Kind, () -> List<String>>>()
    private val pending = AtomicInteger() // ConcurrentLinkedQueue.size는 O(n)이라 따로 센다
    val nDropped = AtomicLong()

    /** 쓰기 오류로 로그를 멈췄다(이후 건은 [nDropped]). */
    @Volatile
    var failed = false
        private set

    @Volatile
    private var running = true
    private val writer = Thread(::loop, "RunLogger").apply { start() }

    fun slowPath(line: String) = put(Kind.SLOW) { listOf(line) }

    /** 오디오 스레드: 블록 하나의 안내 출력. 줄 만들기는 쓰기 스레드에서 한다. */
    fun guidance(g: GuidanceOutput, poseTNs: Long, snapshotTNs: Long?) = put(Kind.GUIDANCE) { RunLog.guidanceLines(g, poseTNs, snapshotTNs) }
    fun obstacles(lines: List<String>) = put(Kind.OBSTACLES) { lines }
    fun clusters(items: List<ClusterDebug>) = put(Kind.CLUSTERS) { RunLog.clusterDebugLines(items) }
    fun device(line: String) = put(Kind.DEVICE) { listOf(line) }

    private fun put(k: Kind, lines: () -> List<String>) {
        if (failed || pending.get() >= MAX_PENDING) {
            nDropped.incrementAndGet()
            return
        }
        pending.incrementAndGet()
        queue.add(k to lines)
    }

    private fun loop() {
        val out = LinkedHashMap<Kind, BufferedWriter>()
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
            for (k in Kind.entries) out[k] = File(dir, k.file).bufferedWriter().also { it.write(k.header); it.newLine() }
            while (true) {
                val stop = !running // 멈춤을 본 뒤 한 번 더 비우고 끝낸다
                while (true) {
                    val (k, lines) = queue.poll() ?: break
                    pending.decrementAndGet()
                    val w = out.getValue(k)
                    for (line in lines()) { w.write(line); w.newLine() }
                }
                if (stop) break
                Thread.sleep(DRAIN_MS)
            }
        } catch (e: IOException) {
            failed = true
            nDropped.addAndGet(pending.getAndSet(0).toLong())
            queue.clear()
            Log.e(TAG, "run log write failed, logging stopped: $dir", e)
        } finally {
            out.values.forEach { runCatching { it.close() } }
            Log.i(TAG, "run log closed: $dir (dropped ${nDropped.get()}, failed $failed)")
        }
    }

    /** 남은 건을 모두 쓰고 닫는다. */
    override fun close() {
        running = false
        writer.join(5000)
    }

    companion object {
        private const val MAX_PENDING = 100_000

        /** 쓰기 스레드가 큐를 비우는 간격(ms). 로그 지연일 뿐 안내에는 영향이 없다. */
        private const val DRAIN_MS = 200L
    }
}
