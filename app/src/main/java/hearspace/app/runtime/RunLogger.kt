package hearspace.app.runtime

import android.util.Log
import hearspace.app.TAG
import hearspace.core.pipeline.ClusterDebug
import hearspace.core.pipeline.ClusterDebugCsv
import hearspace.core.session.DeviceCsv
import hearspace.core.session.RunLog
import hearspace.core.session.SessionFormat
import java.io.BufferedWriter
import java.io.File
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 실행 로그(§10.1) 4종을 [dir]에 쓴다. 파일 쓰기는 전용 스레드에서만 한다.
 * 로그는 파이프라인 데이터가 아니라 기록이므로 최신 값 교체가 아니라 큐로 모두 쓴다(줄을 버리면 분석이 틀어진다).
 * 큐가 [MAX_PENDING]줄을 넘거나(쓰기가 막힌 경우) 쓰기 오류가 나면 새 줄을 버리고 [nDropped]에 센다: 실시간 스레드를 막지 않고,
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

    private val queue = LinkedBlockingQueue<Pair<Kind, String>>()
    val nDropped = AtomicLong()

    /** 쓰기 오류로 로그를 멈췄다(이후 줄은 [nDropped]). */
    @Volatile
    var failed = false
        private set

    @Volatile
    private var running = true
    private val writer = Thread(::loop, "RunLogger").apply { start() }

    fun slowPath(line: String) = put(Kind.SLOW, line)
    fun guidance(lines: List<String>) = lines.forEach { put(Kind.GUIDANCE, it) }
    fun obstacles(lines: List<String>) = lines.forEach { put(Kind.OBSTACLES, it) }
    fun clusters(items: List<ClusterDebug>) = RunLog.clusterDebugLines(items).forEach { put(Kind.CLUSTERS, it) }
    fun device(line: String) = put(Kind.DEVICE, line)

    private fun put(k: Kind, line: String) {
        if (failed || queue.size >= MAX_PENDING) nDropped.incrementAndGet() else queue.offer(k to line)
    }

    private fun loop() {
        val out = LinkedHashMap<Kind, BufferedWriter>()
        try {
            if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create $dir")
            for (k in Kind.entries) out[k] = File(dir, k.file).bufferedWriter().also { it.write(k.header); it.newLine() }
            while (running || queue.isNotEmpty()) {
                val (k, line) = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.getValue(k).apply { write(line); newLine() }
            }
        } catch (e: IOException) {
            failed = true
            nDropped.addAndGet(queue.size.toLong())
            queue.clear()
            Log.e(TAG, "run log write failed, logging stopped: $dir", e)
        } finally {
            out.values.forEach { runCatching { it.close() } }
            Log.i(TAG, "run log closed: $dir (dropped ${nDropped.get()}, failed $failed)")
        }
    }

    /** 남은 줄을 모두 쓰고 닫는다. */
    override fun close() {
        running = false
        writer.join(5000)
    }

    companion object {
        private const val MAX_PENDING = 100_000
    }
}
