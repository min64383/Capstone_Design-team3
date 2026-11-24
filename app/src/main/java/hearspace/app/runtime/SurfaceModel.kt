package hearspace.app.runtime

import android.content.Context
import android.os.Process
import android.util.Log
import hearspace.app.TAG
import hearspace.app.ar.Nv21Copy
import hearspace.core.geometry.Mat4
import hearspace.core.session.SessionReader
import hearspace.core.situation.SurfaceIO
import hearspace.core.types.Config
import hearspace.core.types.Intrinsics
import hearspace.core.types.LabelImage
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport

/**
 * M50 노면 분할 모델을 앱에서 돌리는 스레드(M65 설계 3.3절). 전달은 모두 최신 값 하나 교체다.
 * - GL 스레드: [wants]가 참일 때만 CPU 영상(NV21)을 복사해 [offer]로 넘긴다(마지막 영상 뒤 [Config.situation] `surfaceIntervalMs`가 지났고 모델이 쉼).
 * - 이 스레드: NV21 → RGB → core [SurfaceIO] 앞처리 → LiteRT(GPU 대리자는 이 스레드에서 만들고 돌린다) → 뒤처리 → [latest].
 * 모델 파일(`surface.tflite`, git 밖 `data/models/app/`에서 빌드 때 자산으로)이 없거나 열 수 없으면 [available]이 거짓이고 노면 영상을 내지 않는다.
 */
class SurfaceModel(
    private val context: Context,
    config: Config,
    private val logger: RunLogger?,
    private val clockNs: () -> Long,
) : AutoCloseable {
    private class Job(val image: Nv21Copy, val worldFromCam: Mat4, val imageK: Intrinsics, val offerNs: Long)

    private val cfg = config.situation
    private val intervalNs = cfg.surfaceIntervalMs * 1_000_000L
    private val slot = AtomicReference<Job?>(null)

    /** 가장 최근 노면 영상(느린 경로가 읽는다). */
    val latest = AtomicReference<LabelImage?>(null)

    /** 모델을 열었는지. 열기 전·실패면 거짓. */
    @Volatile
    var available = false
        private set

    /** 모델을 열지 못한 이유(화면·로그용). */
    @Volatile
    var failure: String? = null
        private set

    /** GPU 대리자로 도는지(거짓이면 CPU). */
    @Volatile
    var gpu = false
        private set

    @Volatile
    private var busy = false

    @Volatile
    private var running = true
    private var lastOfferTNs = Long.MIN_VALUE // GL 스레드 전용
    private val worker = Thread(::loop, "SurfaceModel").apply { start() }

    /** GL 스레드: 이 프레임 시각의 CPU 영상을 넘길 차례인지. */
    fun wants(frameTNs: Long): Boolean =
        available && !busy && slot.get() == null && (lastOfferTNs == Long.MIN_VALUE || frameTNs - lastOfferTNs >= intervalNs)

    /** GL 스레드: 복사한 CPU 영상과 그 프레임의 자세, CPU 영상 내부 행렬을 넘긴다. */
    fun offer(image: Nv21Copy, worldFromCam: Mat4, imageK: Intrinsics) {
        lastOfferTNs = image.tNs
        slot.set(Job(image, worldFromCam, imageK, clockNs()))
        LockSupport.unpark(worker)
    }

    private fun loop() {
        Process.setThreadPriority(LivePipeline.BACKGROUND_WORK_PRIORITY)
        val interpreter = open() ?: return
        val delegateCleanup = interpreter.second
        try {
            run(interpreter.first)
        } finally {
            interpreter.first.close()
            delegateCleanup?.close()
        }
    }

    /** 모델 열기(이 스레드에서, GPU 대리자는 만든 스레드에서만 돈다). */
    private fun open(): Pair<Interpreter, GpuDelegate?>? = try {
        val model = context.assets.openFd(modelAsset(cfg.surfaceInputW, cfg.surfaceInputH)).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { it.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }
        val options = Interpreter.Options()
        // VERIFY: LiteRT 1.4.2 Interpreter API의 CompatibilityList·GpuDelegate(org.tensorflow.lite.gpu) — 공식 GPU 안내 페이지의 예시대로
        val compat = CompatibilityList()
        val delegate = if (compat.isDelegateSupportedOnThisDevice) GpuDelegate(compat.bestOptionsForThisDevice) else null
        if (delegate != null) options.addDelegate(delegate) else options.setNumThreads(4)
        val it = Interpreter(model, options)
        gpu = delegate != null
        available = true
        Log.i(TAG, "surface model ready (gpu=$gpu)")
        it to delegate
    } catch (e: Exception) {
        failure = "${e.javaClass.simpleName}: ${e.message}"
        Log.w(TAG, "surface model unavailable: $failure")
        null
    }

    private fun run(interpreter: Interpreter) {
        // 앞처리는 CPU 영상 크기에 묶이므로 첫 영상(또는 크기가 바뀐 영상)에서 만든다
        var io: SurfaceIO? = null
        var rgb = ByteArray(0)
        val input = FloatArray(cfg.surfaceInputH * cfg.surfaceInputW * 4)
        val logits = FloatArray(cfg.surfaceInputH * cfg.surfaceInputW * hearspace.core.situation.SurfaceClass.COUNT)
        val inBuf = ByteBuffer.allocateDirect(input.size * 4).order(ByteOrder.nativeOrder())
        val outBuf = ByteBuffer.allocateDirect(logits.size * 4).order(ByteOrder.nativeOrder())
        while (running) {
            val job = slot.getAndSet(null)
            if (job == null) {
                LockSupport.park(this)
                continue
            }
            busy = true
            try {
                val img = job.image
                val p = io?.takeIf { it.srcW == img.width && it.srcH == img.height }
                    ?: SurfaceIO(img.width, img.height, cfg.surfaceCropTop, cfg.surfaceInputW, cfg.surfaceInputH, cfg.surfaceOutW, cfg.surfaceOutH)
                        .also { io = it; rgb = ByteArray(img.width * img.height * 3) }
                val t0 = System.nanoTime()
                SurfaceIO.nv21ToRgb(img.bytes, img.width, img.height, rgb)
                val t1 = System.nanoTime()
                p.preprocess(rgb, input)
                inBuf.clear(); inBuf.asFloatBuffer().put(input)
                val t2 = System.nanoTime()
                outBuf.clear()
                interpreter.run(inBuf, outBuf)
                val t3 = System.nanoTime()
                outBuf.rewind(); outBuf.asFloatBuffer().get(logits)
                val labels = ByteArray(cfg.surfaceOutW * cfg.surfaceOutH)
                p.postprocess(logits, labels)
                val k = SessionReader.scaleTextureK(job.imageK, cfg.surfaceOutW, cfg.surfaceOutH)
                latest.set(LabelImage(img.tNs, labels, k, job.worldFromCam))
                val t4 = System.nanoTime()
                logger?.surface(img.tNs, job.offerNs, clockNs(), t1 - t0, t2 - t1, t3 - t2, t4 - t3)
                save(img.tNs, labels)
            } catch (e: Exception) {
                Log.e(TAG, "surface model run failed", e)
            } finally {
                busy = false
            }
        }
    }

    private var firstTNs = Long.MIN_VALUE

    /** 측정용 저장(설정 `surfaceSaveS` > 0): 첫 영상부터 그 시간 동안 실행 폴더 `surface/<촬영 시각>.bin`(센서 방향 8비트 라벨 그대로). */
    private fun save(tNs: Long, labels: ByteArray) {
        val dir = logger?.dir ?: return
        if (cfg.surfaceSaveS <= 0f) return
        if (firstTNs == Long.MIN_VALUE) firstTNs = tNs
        if (tNs - firstTNs > (cfg.surfaceSaveS * 1e9).toLong()) return
        java.io.File(dir, "surface").apply { mkdirs() }.resolve("$tNs.bin").writeBytes(labels)
    }

    override fun close() {
        running = false
        LockSupport.unpark(worker)
        worker.join(2000)
    }

    companion object {
        const val MODEL_ASSET = "surface.tflite"

        /**
         * 입력 크기에 묶인 모델 자산(M69): 학습 입력 320×256은 [MODEL_ASSET], 그 밖은 `surface_<폭>x<높이>.tflite`(같은 가중치를 그 크기로 내보낸 것,
         * `tools/surface/convert_tflite.py --size`). 세로 영상 아래 60%(480×384)를 줄이지 않으면 먼 가로 블록 선이 칠해진다.
         */
        fun modelAsset(inW: Int, inH: Int): String = if (inW == 320 && inH == 256) MODEL_ASSET else "surface_${inW}x${inH}.tflite"
    }
}
