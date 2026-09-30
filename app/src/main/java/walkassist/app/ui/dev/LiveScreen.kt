package walkassist.app.ui.dev

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import com.google.ar.core.PlaybackStatus
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import walkassist.app.AppConfig
import walkassist.app.TAG
import walkassist.app.ar.ArSessionManager
import walkassist.app.ar.FrameAdapter
import walkassist.app.render.BackgroundRenderer
import walkassist.app.runtime.BinauralOutput
import walkassist.app.runtime.LivePipeline
import walkassist.app.runtime.RunLogger
import walkassist.app.runtime.ThermalMonitor
import walkassist.core.audio.Hrtf
import walkassist.core.session.DeviceCsv
import walkassist.core.session.SessionFormat
import walkassist.core.session.SessionReader
import walkassist.core.types.Config
import walkassist.core.types.DepthFrame
import walkassist.core.types.DepthSource
import walkassist.core.types.PoseFrame
import walkassist.core.types.TrackingState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

/**
 * 실시간·재생 모드(§9.2): 카메라(또는 녹화 MP4) → 파이프라인 → 음향 + 디버그 오버레이 + 실행 로그.
 * 두 모드는 입력만 다르고 같은 코드 경로다. 실행 로그 위치: 실시간 `runs/<시각>/`, 재생 `sessions/<id>/run_log/<시각>/`.
 *
 * 시계: 실시간은 `elapsedRealtimeNanos()`(ARCore 프레임 시각과 같은 기준, M1 `lag(sys-frame)` 125~170 ms로 확인).
 * 재생은 프레임 시각이 녹화 당시 값이므로 첫 프레임에서 (수신 시각 − 프레임 시각)을 빼 맞춘다(F8에서 확인).
 */
class LiveScreen : Activity(), GLSurfaceView.Renderer {

    private lateinit var config: Config
    private lateinit var hrtf: Hrtf
    private lateinit var ar: ArSessionManager
    private lateinit var thermal: ThermalMonitor
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: DebugOverlay

    private var sessionDir: File? = null
    private val replay get() = sessionDir != null
    private val background = BackgroundRenderer()
    private val ui = Handler(Looper.getMainLooper())

    @Volatile
    private var session: Session? = null

    @Volatile
    private var pipeline: LivePipeline? = null
    private var output: BinauralOutput? = null
    private var logger: RunLogger? = null

    /** 자세 시계 = elapsedRealtime − 이 값. 재생은 첫 프레임에서 정한다. */
    @Volatile
    private var clockOffsetNs: Long? = null

    @Volatile
    private var tracking = "-"

    @Volatile
    private var playbackFinished = false

    // GL 스레드 전용
    private var textureBound: Session? = null
    private var viewportChanged = false
    private var viewportW = 0
    private var viewportH = 0
    private var lastFrameTNs = Long.MIN_VALUE
    private var lastDepthTNs = Long.MIN_VALUE
    private val recentPoses = ArrayDeque<PoseFrame>()

    // UI 스레드 전용
    private var lastSlowCount = 0L
    private var lastTickMs = 0L
    private var slowHz = 0f
    private var lastDeviceMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig.get(this)
        hrtf = assets.open(HRTF_ASSET).use { Hrtf.parse(it.readBytes()) }
        sessionDir = intent.getStringExtra(EXTRA_SESSION_DIR)?.let(::File)
        ar = ArSessionManager(this, sessionDir?.let { File(it, SessionFormat.MP4_FILE) })
        thermal = ThermalMonitor(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@LiveScreen)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        }
        overlay = DebugOverlay(this, config)
        setContentView(FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(this@LiveScreen.overlay, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        })
    }

    override fun onResume() {
        super.onResume()
        ar.resume()?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        session = ar.session
        if (session != null) startRun()
        glView.onResume()
        ui.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(uiTick)
        glView.onPause()
        stopRun()
        ar.pause()
    }

    override fun onDestroy() {
        ar.close()
        session = null
        super.onDestroy()
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == ArSessionManager.CAMERA_PERMISSION_REQUEST &&
            grantResults.firstOrNull() != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(this, "카메라 권한이 필요합니다", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startRun() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val dir = sessionDir?.let { File(it, "run_log/$stamp") } ?: File(getExternalFilesDir(null), "runs/$stamp")
        clockOffsetNs = if (replay) null else 0L
        playbackFinished = false
        val log = RunLogger(dir)
        val p = LivePipeline(config, hrtf, log) { SystemClock.elapsedRealtimeNanos() - (clockOffsetNs ?: 0L) }
        val out = BinauralOutput(config.audio, p::renderBlock)
        out.start()
        logger = log
        pipeline = p
        output = out
        Log.i(TAG, "run started (${if (replay) "replay ${sessionDir!!.name}" else "live"}): $dir")
    }

    /** 오디오 → 파이프라인 → 로그 순으로 멈춘다(앞 단계가 뒤 단계에 쓰므로). */
    private fun stopRun() {
        output?.close()
        pipeline?.close()
        logger?.close()
        logger?.let { Log.i(TAG, "run log: ${it.dir}") }
        output = null
        pipeline = null
        logger = null
    }

    // ---- GL 스레드 ----

    override fun onSurfaceCreated(gl: GL10?, cfg: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        background.createOnGlThread()
        textureBound = null
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        viewportW = width
        viewportH = height
        viewportChanged = true
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s = session ?: return
        if (textureBound !== s) {
            s.setCameraTextureName(background.textureId)
            textureBound = s
            viewportChanged = true
        }
        if (viewportChanged) {
            s.setDisplayGeometry(displayRotation(), viewportW, viewportH)
            viewportChanged = false
        }
        val frame = try {
            s.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "camera not available during update", e)
            return
        }
        background.draw(frame)
        if (replay && s.playbackStatus == PlaybackStatus.FINISHED) playbackFinished = true
        if (frame.timestamp == lastFrameTNs) return // 새 카메라 프레임이 아니다
        lastFrameTNs = frame.timestamp
        if (clockOffsetNs == null) clockOffsetNs = SystemClock.elapsedRealtimeNanos() - frame.timestamp

        val camera = frame.camera
        tracking = camera.trackingState.name
        val pose = PoseFrame(
            frame.timestamp,
            FrameAdapter.toCore(camera.trackingState),
            SessionReader.toWorldFromCv(FrameAdapter.toPoseGl(camera.pose)),
        )
        recentPoses.addLast(pose)
        if (recentPoses.size > RECENT_POSES) recentPoses.removeFirst()
        val p = pipeline ?: return
        p.publishPose(pose)

        val raw = config.depth.source == DepthSource.RAW
        val d = FrameAdapter.readDepth(frame, raw) { it != lastDepthTNs } ?: return
        val mm = d.mm ?: return
        lastDepthTNs = d.tNs
        // 깊이 시각에 가장 가까운, 이미 받은 자세(미래 자세 없음, SessionReader와 같은 규칙)
        val dp = recentPoses.minBy { abs(it.tCaptureNs - d.tNs) }
        if (dp.tracking != TrackingState.TRACKING) return
        val conf = if (raw) FrameAdapter.readConfidence(frame)?.values?.takeIf { it.size == mm.size } else null
        p.publishDepth(
            DepthFrame(
                tCaptureNs = d.tNs,
                depthMm = mm,
                confidence = conf,
                K = SessionReader.scaleTextureK(FrameAdapter.toIntrinsics(camera.textureIntrinsics), d.width, d.height),
                worldFromCam = dp.worldFromCam,
                source = if (raw) "arcore_raw_depth" else "arcore_depth",
            ),
        )
    }

    // ---- UI 스레드 ----

    private val uiTick = object : Runnable {
        override fun run() {
            tick()
            ui.postDelayed(this, UI_REFRESH_MS)
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val p = pipeline
        val out = output
        if (p != null && now - lastTickMs >= 1000) {
            val n = p.nSlow.get()
            if (lastTickMs > 0) slowHz = (n - lastSlowCount) * 1000f / (now - lastTickMs)
            lastSlowCount = n
            lastTickMs = now
        }
        if (out != null && now - lastDeviceMs >= config.record.deviceLogIntervalS * 1000) {
            lastDeviceMs = now
            logger?.device(DeviceCsv.format(thermal.sample().copy(audioOutputLatencyMs = out.latencyMs)))
        }
        val g = p?.lastOutput?.get()
        val snap = p?.snapshot?.get()
        val cmd = g?.commands?.firstOrNull()
        val lines = listOf(
            (if (replay) "재생 ${sessionDir!!.name}" else "실시간") + (if (playbackFinished) " · 재생 끝" else ""),
            "추적 $tracking · 상태 ${g?.state ?: "-"} · 진행 %.0f°".format(g?.headingDeg ?: Float.NaN),
            cmd?.let { "음원 #${it.obstacleId} %+.0f° %.2fm ${it.band} ${it.sound} 나이 %.0fms".format(it.azimuthDeg, it.distanceM, it.infoAgeMs) }
                ?: "음원 -",
            "느린 경로 %.1f Hz · 물체 ${snap?.obstacles?.size ?: "-"} · 바닥 ${snap?.floorY?.let { "%.2f".format(it) } ?: "?"}".format(slowHz),
            "발열 ${thermal.thermalName(thermal.sample().thermalStatus)} · 출력 지연 ${out?.latencyMs?.let { "%.0fms".format(it) } ?: "?"}" +
                " · 끊김 ${out?.underruns ?: 0} · 로그 누락 ${logger?.nDropped?.get() ?: 0}",
        )
        overlay.update(DebugOverlay.State(p?.lastHead?.get(), g, snap, p?.voxelCentersW?.get().orEmpty(), lines))
        if (playbackFinished && logger != null) {
            stopRun()
            Toast.makeText(this, "재생 끝: 로그 저장", Toast.LENGTH_LONG).show()
        }
    }

    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display!!.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    companion object {
        private const val EXTRA_SESSION_DIR = "sessionDir"
        private const val HRTF_ASSET = "hrtf/sadie2_d1_48k.hrir"
        private const val UI_REFRESH_MS = 100L
        private const val RECENT_POSES = 30

        /** 실시간 모드([sessionDir] = null) 또는 세션 재생 모드로 연다. */
        fun intent(context: Context, sessionDir: File?) = Intent(context, LiveScreen::class.java).apply {
            sessionDir?.let { putExtra(EXTRA_SESSION_DIR, it.absolutePath) }
        }
    }
}
