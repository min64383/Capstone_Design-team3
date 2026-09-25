package walkassist.app.ar

import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.google.ar.core.Camera
import com.google.ar.core.Frame
import com.google.ar.core.RecordingConfig
import com.google.ar.core.Session
import com.google.ar.core.exceptions.RecordingFailedException
import walkassist.app.TAG
import walkassist.app.runtime.ThermalMonitor
import walkassist.core.session.DeviceCsv
import walkassist.core.session.DeviceRow
import walkassist.core.session.FrameRow
import walkassist.core.session.FramesCsv
import walkassist.core.session.GrayImage
import walkassist.core.session.Png16
import walkassist.core.session.SessionFormat
import walkassist.core.session.SessionMeta
import walkassist.core.session.SessionStats
import walkassist.core.types.RecordConfig
import java.io.BufferedWriter
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/** 녹화 중 화면에 보여 줄 누적 통계(스레드 안전하게 읽힌다). */
data class RecorderStats(
    val nFrames: Long,
    val nDepthSaved: Long,
    val nRawDepthSaved: Long,
    val nRgbSaved: Long,
    val nFileJobsSkipped: Long,
    val nWriteErrors: Long,
    val lastDevice: DeviceRow?,
)

/**
 * 녹화 모드(§8.1): ARCore Recording API로 `arcore.mp4`, 저장 스레드로 `frames.csv`·깊이·이미지·`device.csv`·`meta.json`.
 *
 * 스레드: [start]·[onFrame]·[stop]은 GL 스레드(또는 GL 스레드가 멈춘 뒤의 UI 스레드)에서만 호출한다.
 * - 자세 행: 모든 프레임을 기록해야 하므로(§8.1) 저장 스레드로 큐 전달. 행은 작아서 밀려도 부담이 작다.
 * - 파일 작업: 슬롯 1개. 슬롯이 비어 있을 때만 이번 프레임의 파일을 복사해 넣고, 차 있으면 이번 파일을 건너뛴다.
 *   건너뛴 파일은 행에 이름을 쓰지 않으므로, 행에 적힌 파일은 모두 실제로 저장된다(오류 시 nWriteErrors).
 */
class Recorder(
    private val dir: File,
    private val initialMeta: SessionMeta,
    private val cfg: RecordConfig,
    private val thermal: ThermalMonitor,
) {
    private class FileJob(
        val frameIndex: Long,
        val depth: DepthCopy?,
        val rawDepth: DepthCopy?,
        val conf: ConfidenceCopy?,
        val rgb: Nv21Copy?,
    )

    private val rows = ConcurrentLinkedQueue<FrameRow>()
    private val slot = AtomicReference<FileJob?>(null)
    private val wake = Semaphore(0)

    @Volatile
    private var stopping = false
    private var writer: Thread? = null

    // GL 스레드 전용 상태
    private var frameIndex = 0L
    private var lastDepthTNs = Long.MIN_VALUE
    private var lastRawDepthTNs = Long.MIN_VALUE
    private var newDepthCount = 0L
    private var startElapsedNs = 0L
    private var depthWidth: Int? = null
    private var depthHeight: Int? = null

    // 통계 (저장 스레드가 증가, UI가 읽음)
    private val nDepthSaved = AtomicLong()
    private val nRawDepthSaved = AtomicLong()
    private val nRgbSaved = AtomicLong()
    private val nSkipped = AtomicLong()
    private val nWriteErrors = AtomicLong()
    private val nFrames = AtomicLong()
    private val lastDevice = AtomicReference<DeviceRow?>(null)

    /** 세션 폴더 경로. */
    val sessionDir: File get() = dir

    /** 현재 통계. */
    fun stats() = RecorderStats(
        nFrames.get(), nDepthSaved.get(), nRawDepthSaved.get(), nRgbSaved.get(),
        nSkipped.get(), nWriteErrors.get(), lastDevice.get(),
    )

    /** 폴더를 만들고 MP4 녹화와 저장 스레드를 시작한다. MP4 녹화는 세션 실행 중 언제든 시작할 수 있다(공식 샘플 주석). */
    fun start(session: Session) {
        for (d in listOf(SessionFormat.DEPTH_DIR, SessionFormat.RAW_DEPTH_DIR, SessionFormat.DEPTH_CONF_DIR, SessionFormat.RGB_DIR)) {
            File(dir, d).mkdirs()
        }
        File(dir, SessionFormat.META_FILE).writeText(initialMeta.toJson())
        val mp4 = Uri.fromFile(File(dir, SessionFormat.MP4_FILE))
        session.startRecording(RecordingConfig(session).setMp4DatasetUri(mp4).setAutoStopOnPause(true))
        startElapsedNs = SystemClock.elapsedRealtimeNanos()
        writer = Thread(::writerLoop, "RecorderWriter").apply { start() }
        Log.i(TAG, "recording started: $dir")
    }

    /** GL 스레드에서 프레임마다 호출. */
    fun onFrame(frame: Frame, camera: Camera) {
        if (stopping) return
        val idx = frameIndex++
        val sysNs = SystemClock.elapsedRealtimeNanos()
        val slotFree = slot.get() == null // 슬롯을 비우는 쪽은 저장 스레드뿐이므로 여기서 비어 있으면 아래 set까지 비어 있다.
        var wantedFiles = false

        val depth = FrameAdapter.readDepth(frame, raw = false) { tNs ->
            if (tNs == lastDepthTNs) return@readDepth false // 같은 깊이의 재투영 반복은 저장하지 않는다
            lastDepthTNs = tNs
            val due = newDepthCount++ % cfg.depthEveryN == 0L
            wantedFiles = wantedFiles || due
            due && slotFree
        }
        val rawDepth = FrameAdapter.readDepth(frame, raw = true) { tNs ->
            if (tNs == lastRawDepthTNs) return@readDepth false
            lastRawDepthTNs = tNs
            wantedFiles = true
            slotFree
        }
        val conf = if (rawDepth?.mm != null) FrameAdapter.readConfidence(frame) else null
        val rgbDue = idx % cfg.rgbEveryN == 0L
        wantedFiles = wantedFiles || rgbDue
        val rgb = if (rgbDue && slotFree) FrameAdapter.readCameraNv21(frame) else null

        if (depth != null && depthWidth == null) {
            depthWidth = depth.width
            depthHeight = depth.height
        }

        val job = FileJob(idx, depth?.takeIf { it.mm != null }, rawDepth?.takeIf { it.mm != null }, conf, rgb)
        val hasFiles = job.depth != null || job.rawDepth != null || job.conf != null || job.rgb != null
        if (hasFiles) slot.set(job) else if (wantedFiles && !slotFree) nSkipped.incrementAndGet()

        rows.add(
            FrameRow(
                frameIndex = idx,
                tNs = frame.timestamp,
                sysElapsedNs = sysNs,
                tracking = FrameAdapter.toCore(camera.trackingState),
                trackingFailure = camera.trackingFailureReason.name,
                pose = FrameAdapter.toPoseGl(camera.pose),
                displayPose = FrameAdapter.toPoseGl(camera.displayOrientedPose),
                depthTNs = depth?.tNs,
                depthFile = job.depth?.let { SessionFormat.depthFile(idx) },
                rawDepthTNs = rawDepth?.tNs,
                rawDepthFile = job.rawDepth?.let { SessionFormat.rawDepthFile(idx) },
                confFile = job.conf?.let { SessionFormat.confFile(idx) },
                rgbFile = job.rgb?.let { SessionFormat.rgbFile(idx) },
            ),
        )
        nFrames.incrementAndGet()
        wake.release()
    }

    /** MP4 녹화를 멈추고 저장 스레드가 남은 작업을 마친 뒤 meta.json에 통계를 쓰게 한다. [onDone]은 저장 스레드에서 불린다. */
    fun stop(session: Session?, onDone: (File) -> Unit) {
        if (stopping) return
        try {
            session?.stopRecording()
        } catch (e: RecordingFailedException) {
            Log.e(TAG, "stopRecording failed", e)
        }
        val durationS = (SystemClock.elapsedRealtimeNanos() - startElapsedNs) / 1e9f
        val finalMeta = initialMeta.copy(
            depth = initialMeta.depth.copy(width = depthWidth, height = depthHeight),
            stats = null,
        )
        pendingFinish = { writeFinalMeta(finalMeta, durationS); onDone(dir) }
        stopping = true
        wake.release()
    }

    @Volatile
    private var pendingFinish: (() -> Unit)? = null

    private fun writeFinalMeta(meta: SessionMeta, durationS: Float) {
        val s = stats()
        val done = meta.copy(
            stats = SessionStats(s.nFrames, s.nDepthSaved, s.nRawDepthSaved, s.nRgbSaved, s.nFileJobsSkipped, s.nWriteErrors, durationS),
        )
        File(dir, SessionFormat.META_FILE).writeText(done.toJson())
        Log.i(TAG, "recording finished: $dir stats=${done.stats}")
    }

    private fun writerLoop() {
        val intervalNs = (cfg.deviceLogIntervalS * 1e9).toLong()
        var nextDeviceNs = 0L
        File(dir, SessionFormat.FRAMES_FILE).bufferedWriter().use { frames ->
            File(dir, SessionFormat.DEVICE_FILE).bufferedWriter().use { device ->
                frames.line(FramesCsv.headerLine())
                device.line(DeviceCsv.headerLine())
                while (true) {
                    wake.tryAcquire(WAKE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    wake.drainPermits()
                    val finishing = stopping
                    slot.getAndSet(null)?.let(::writeFiles)
                    while (true) frames.line(FramesCsv.format(rows.poll() ?: break))
                    val now = SystemClock.elapsedRealtimeNanos()
                    if (now >= nextDeviceNs) {
                        val row = thermal.sample()
                        lastDevice.set(row)
                        device.line(DeviceCsv.format(row))
                        nextDeviceNs = now + intervalNs
                    }
                    if (finishing) break
                }
                frames.flush()
                device.flush()
            }
        }
        pendingFinish?.invoke()
    }

    private fun writeFiles(job: FileJob) {
        fun save(name: String, counter: AtomicLong, bytes: () -> ByteArray) {
            try {
                File(dir, name).writeBytes(bytes())
                counter.incrementAndGet()
            } catch (e: Exception) {
                nWriteErrors.incrementAndGet()
                Log.e(TAG, "write failed: $name", e)
            }
        }
        val i = job.frameIndex
        job.depth?.let { d -> save(SessionFormat.depthFile(i), nDepthSaved) { Png16.encode(GrayImage.of16(d.width, d.height, d.mm!!)) } }
        job.rawDepth?.let { d -> save(SessionFormat.rawDepthFile(i), nRawDepthSaved) { Png16.encode(GrayImage.of16(d.width, d.height, d.mm!!)) } }
        job.conf?.let { c -> save(SessionFormat.confFile(i), AtomicLong()) { Png16.encode(GrayImage.of8(c.width, c.height, c.values)) } }
        job.rgb?.let { r ->
            save(SessionFormat.rgbFile(i), nRgbSaved) {
                val out = java.io.ByteArrayOutputStream()
                YuvImage(r.bytes, ImageFormat.NV21, r.width, r.height, null).compressToJpeg(Rect(0, 0, r.width, r.height), JPEG_QUALITY, out)
                out.toByteArray()
            }
        }
    }

    private fun BufferedWriter.line(s: String) {
        write(s)
        newLine()
    }

    private companion object {
        const val WAKE_TIMEOUT_MS = 100L

        /** 디버그·분석용 RGB 품질. 알고리즘 임계값이 아닌 인코딩 선택이라 상수로 둔다. */
        const val JPEG_QUALITY = 80
    }
}
