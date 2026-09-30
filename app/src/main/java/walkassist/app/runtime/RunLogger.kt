package walkassist.app.runtime

import android.util.Log
import walkassist.app.TAG
import walkassist.core.session.DeviceCsv
import walkassist.core.session.RunLog
import walkassist.core.session.SessionFormat
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * 실행 로그(§10.1) 4종을 [dir]에 쓴다. 파일 쓰기는 전용 스레드에서만 한다.
 * 로그는 파이프라인 데이터가 아니라 기록이므로 최신 값 교체가 아니라 큐로 모두 쓴다(줄을 버리면 분석이 틀어진다).
 * 큐가 [MAX_PENDING]줄을 넘으면(쓰기가 막힌 경우) 새 줄을 버리고 [nDropped]에 센다: 실시간 스레드를 막지 않는다.
 */
class RunLogger(val dir: File) : AutoCloseable {

    private enum class Kind(val file: String, val header: String) {
        SLOW(RunLog.SLOW_PATH_FILE, RunLog.header(RunLog.SLOW_PATH_HEADER)),
        GUIDANCE(RunLog.GUIDANCE_FILE, RunLog.header(RunLog.GUIDANCE_HEADER)),
        OBSTACLES(RunLog.OBSTACLES_FILE, RunLog.header(RunLog.OBSTACLES_HEADER)),
        DEVICE(SessionFormat.DEVICE_FILE, DeviceCsv.headerLine()),
    }

    private val queue = LinkedBlockingQueue<Pair<Kind, String>>()
    val nDropped = AtomicLong()

    @Volatile
    private var running = true
    private val writer = Thread(::loop, "RunLogger").apply { start() }

    fun slowPath(line: String) = put(Kind.SLOW, line)
    fun guidance(lines: List<String>) = lines.forEach { put(Kind.GUIDANCE, it) }
    fun obstacles(lines: List<String>) = lines.forEach { put(Kind.OBSTACLES, it) }
    fun device(line: String) = put(Kind.DEVICE, line)

    private fun put(k: Kind, line: String) {
        if (queue.size >= MAX_PENDING) nDropped.incrementAndGet() else queue.offer(k to line)
    }

    private fun loop() {
        check(dir.isDirectory || dir.mkdirs()) { "cannot create $dir" }
        val out: Map<Kind, BufferedWriter> = Kind.entries.associateWith { k ->
            File(dir, k.file).bufferedWriter().also { it.write(k.header); it.newLine() }
        }
        try {
            while (running || queue.isNotEmpty()) {
                val (k, line) = queue.poll(200, TimeUnit.MILLISECONDS) ?: continue
                out.getValue(k).apply { write(line); newLine() }
            }
        } finally {
            out.values.forEach { runCatching { it.close() } }
            Log.i(TAG, "run log closed: $dir (dropped ${nDropped.get()})")
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
