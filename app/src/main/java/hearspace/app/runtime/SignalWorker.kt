package hearspace.app.runtime

import android.os.Process
import android.util.Log
import hearspace.app.TAG
import hearspace.app.ar.Nv21Copy
import hearspace.core.geometry.Mat4
import hearspace.core.situation.ColorImage
import hearspace.core.situation.SignalSpeech
import hearspace.core.situation.Signals
import hearspace.core.types.Config
import hearspace.core.types.Intrinsics
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * 보행 신호 판단 스레드(M71 설계 표 7). 노면 모델([SurfaceModel])과 같은 방식이고 전달은 모두 최신 값 하나 교체다.
 * - GL 스레드: [wants]가 참일 때만 CPU 영상(NV21)을 복사해 [offer]로 넘긴다(마지막 영상 뒤 `signal.intervalMs`가 지났고 이 스레드가 쉼).
 * - 이 스레드: NV21 그대로 core [Signals](색차로 거른 2×2 칸만 RGB로 계산) → [latest](상황 판단 스레드가 다음 차례에 읽음).
 * 처리 시간은 `signal_timing.csv`(완료 기준 ④)에 남긴다.
 */
class SignalWorker(config: Config, private val logger: RunLogger?) : AutoCloseable {
    private class Job(val image: Nv21Copy, val worldFromCam: Mat4, val imageK: Intrinsics)

    private val intervalNs = config.signal.intervalMs * 1_000_000L
    private val signals = Signals(config.signal)
    private val slot = AtomicReference<Job?>(null)

    /** 가장 최근 신호 말(상황 판단 스레드가 읽는다). */
    val latest = AtomicReference<List<SignalSpeech>>(emptyList())

    @Volatile
    private var busy = false
    private val resetRequested = java.util.concurrent.atomic.AtomicBoolean(false)

    @Volatile
    private var running = true
    private var lastOfferTNs = Long.MIN_VALUE // GL 스레드 전용
    private val worker = Thread(::loop, "Signals").apply { start() }

    /** 월드 좌표 RESET(오디오 스레드): 다음 장 전에 track을 비우고, 그때까지 신호 말을 내지 않는다. */
    fun requestReset() {
        resetRequested.set(true)
        latest.set(emptyList())
    }

    /** GL 스레드: 이 프레임 시각의 CPU 영상을 넘길 차례인지. */
    fun wants(frameTNs: Long): Boolean =
        !busy && slot.get() == null && (lastOfferTNs == Long.MIN_VALUE || frameTNs - lastOfferTNs >= intervalNs)

    /** GL 스레드: 추적 중인 프레임의 CPU 영상과 그 자세, CPU 영상 내부 행렬을 넘긴다. */
    fun offer(image: Nv21Copy, worldFromCam: Mat4, imageK: Intrinsics) {
        lastOfferTNs = image.tNs
        slot.set(Job(image, worldFromCam, imageK))
        LockSupport.unpark(worker)
    }

    private fun loop() {
        Process.setThreadPriority(LivePipeline.BACKGROUND_WORK_PRIORITY)
        while (running) {
            val job = slot.getAndSet(null)
            if (job == null) {
                LockSupport.park(this)
                continue
            }
            busy = true
            try {
                if (resetRequested.getAndSet(false)) signals.reset()
                val img = job.image
                val t0 = System.nanoTime()
                val out = signals.process(ColorImage(img.tNs, job.imageK, job.worldFromCam, nv21 = img.bytes))
                latest.set(out.speech)
                logger?.signalTiming(img.tNs, System.nanoTime() - t0, out.blobs.size, out.tracks.size)
            } catch (e: Exception) {
                Log.e(TAG, "signal run failed", e)
            } finally {
                busy = false
            }
        }
    }

    override fun close() {
        running = false
        LockSupport.unpark(worker)
        worker.join(2000)
    }
}
