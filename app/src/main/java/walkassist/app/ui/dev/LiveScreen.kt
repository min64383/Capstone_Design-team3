package walkassist.app.ui.dev

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import walkassist.app.AppConfig
import walkassist.app.ar.ArFeeder
import walkassist.app.ar.ArSessionManager
import walkassist.app.ar.displayRotationCompat
import walkassist.app.loadHrtf
import walkassist.app.runtime.RunSession
import walkassist.app.runtime.ThermalMonitor
import walkassist.core.audio.Hrtf
import walkassist.core.session.SessionFormat
import walkassist.core.types.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 실시간·재생 모드(§9.2): 카메라(또는 녹화 MP4) → 파이프라인 → 음향 + 디버그 오버레이 + 실행 로그.
 * 두 모드는 입력만 다르고 같은 코드 경로다. 실행 로그 위치: 실시간 `runs/<시각>/`, 재생 `sessions/<id>/run_log/<시각>/`.
 */
class LiveScreen : Activity(), GLSurfaceView.Renderer {

    private lateinit var config: Config
    private lateinit var hrtf: Hrtf
    private lateinit var ar: ArSessionManager
    private lateinit var feeder: ArFeeder
    private lateinit var thermal: ThermalMonitor
    private lateinit var glView: GLSurfaceView
    private lateinit var overlay: DebugOverlay

    private var sessionDir: File? = null
    private val replay get() = sessionDir != null
    private val ui = Handler(Looper.getMainLooper())
    private var run: RunSession? = null

    /** GL 스레드 → UI: 카메라 화면에 투영한 물체 대표점. */
    private val markers = AtomicReference<List<DebugOverlay.Marker>>(emptyList())
    private val viewM = FloatArray(16)
    private val projM = FloatArray(16)
    private val vp = FloatArray(16)
    private val clip = FloatArray(4)

    // UI 스레드 전용
    private var lastSlowCount = 0L
    private var lastTickMs = 0L
    private var slowHz = 0f
    private var lastDeviceMs = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig.get(this)
        hrtf = loadHrtf(this)
        sessionDir = intent.getStringExtra(EXTRA_SESSION_DIR)?.let(::File)
        ar = ArSessionManager(this, sessionDir?.let { File(it, SessionFormat.MP4_FILE) })
        feeder = ArFeeder(config, replay, drawCamera = true)
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
        feeder.session = ar.session
        if (ar.session != null) startRun()
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
        feeder.session = null
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
        feeder.resetClock()
        run = RunSession(config, hrtf, dir, feeder::nowNs).also { feeder.pipeline = it.pipeline }
    }

    private fun stopRun() {
        feeder.pipeline = null
        run?.close()
        run = null
    }

    // ---- GL 스레드 ----

    override fun onSurfaceCreated(gl: GL10?, cfg: EGLConfig?) = feeder.onSurfaceCreated()

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = feeder.onSurfaceChanged(width, height)

    override fun onDrawFrame(gl: GL10?) {
        feeder.onDrawFrame(displayRotationCompat())?.let { markers.set(projectMarkers(it)) }
    }

    /**
     * 최신 스냅샷의 대표점(월드)을 이번 카메라 화면에 투영한다(§11.3 "선택 대표점 표시"). GL 스레드.
     * ARCore 월드와 core 월드 W는 같은 좌표계다(카메라 규약만 다름, §5).
     */
    private fun projectMarkers(camera: com.google.ar.core.Camera): List<DebugOverlay.Marker> {
        val p = feeder.pipeline ?: return emptyList()
        val snap = p.snapshot.get() ?: return emptyList()
        val selected = p.lastOutput.get()?.commands?.map { it.obstacleId }?.toSet().orEmpty()
        camera.getViewMatrix(viewM, 0)
        camera.getProjectionMatrix(projM, 0, 0.1f, 100f)
        android.opengl.Matrix.multiplyMM(vp, 0, projM, 0, viewM, 0)
        return snap.obstacles.mapNotNull { o ->
            val w = o.repPointW
            android.opengl.Matrix.multiplyMV(clip, 0, vp, 0, floatArrayOf(w.x, w.y, w.z, 1f), 0)
            if (clip[3] <= 0f) return@mapNotNull null // 카메라 뒤
            val x = (clip[0] / clip[3] + 1f) / 2f * feeder.viewportW
            val y = (1f - clip[1] / clip[3]) / 2f * feeder.viewportH
            DebugOverlay.Marker(x, y, o.id, o.heightClass, o.id in selected)
        }
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
        val r = run
        val p = r?.pipeline
        val out = r?.output
        if (p != null && now - lastTickMs >= 1000) {
            val n = p.nSlow.get()
            if (lastTickMs > 0) slowHz = (n - lastSlowCount) * 1000f / (now - lastTickMs)
            lastSlowCount = n
            lastTickMs = now
        }
        if (r != null && now - lastDeviceMs >= config.record.deviceLogIntervalS * 1000) {
            lastDeviceMs = now
            r.logDevice(thermal)
        }
        val g = p?.lastOutput?.get()
        val snap = p?.snapshot?.get()
        val cmd = g?.commands?.firstOrNull()
        val lines = listOf(
            (if (replay) "재생 ${sessionDir!!.name}" else "실시간") + (if (feeder.playbackFinished) " · 재생 끝" else ""),
            "추적 ${feeder.tracking} · 상태 ${g?.state ?: "-"} · 진행 %.0f°".format(g?.headingDeg ?: Float.NaN),
            cmd?.let { "음원 #${it.obstacleId} %+.0f° %.2fm ${it.band} ${it.sound} 나이 %.0fms".format(it.azimuthDeg, it.distanceM, it.infoAgeMs) }
                ?: "음원 -",
            "느린 경로 %.1f Hz · 물체 ${snap?.obstacles?.size ?: "-"} · 바닥 ${snap?.floorY?.let { "%.2f".format(it) } ?: "?"}".format(slowHz),
            "발열 ${thermal.thermalName(thermal.sample().thermalStatus)} · 출력 지연 ${out?.latencyMs?.let { "%.0fms".format(it) } ?: "?"}" +
                " · 끊김 ${out?.underruns ?: 0} · 로그 누락 ${r?.logger?.nDropped?.get() ?: 0}",
        )
        overlay.update(DebugOverlay.State(p?.lastHead?.get(), g, snap, p?.voxelCentersW?.get().orEmpty(), lines, markers.get()))
        if (feeder.playbackFinished && run != null) {
            stopRun()
            Toast.makeText(this, "재생 끝: 로그 저장", Toast.LENGTH_LONG).show()
        }
    }

    companion object {
        private const val EXTRA_SESSION_DIR = "sessionDir"
        private const val UI_REFRESH_MS = 100L

        /** 실시간 모드([sessionDir] = null) 또는 세션 재생 모드로 연다. */
        fun intent(context: Context, sessionDir: File?) = Intent(context, LiveScreen::class.java).apply {
            sessionDir?.let { putExtra(EXTRA_SESSION_DIR, it.absolutePath) }
        }
    }
}
