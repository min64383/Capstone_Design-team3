package walkassist.app.ui.dev

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import com.google.ar.core.Session
import com.google.ar.core.exceptions.CameraNotAvailableException
import walkassist.app.AppConfig
import walkassist.app.BuildConfig
import walkassist.app.TAG
import walkassist.app.ar.ArSessionManager
import walkassist.app.ar.FrameAdapter
import walkassist.app.ar.Recorder
import walkassist.app.render.BackgroundRenderer
import walkassist.app.runtime.ThermalMonitor
import walkassist.core.geometry.Vec3
import walkassist.core.session.ArcoreInfo
import walkassist.core.session.CameraInfo
import walkassist.core.session.DepthInfo
import walkassist.core.session.DeviceInfo
import walkassist.core.session.SessionFormat
import walkassist.core.session.SessionMeta
import walkassist.core.types.Config
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 개발 모드 녹화 화면(§11.3): 카메라 미리보기, 추적 상태, 깊이 지원 여부, 저장 FPS, 경과 시간, 장면 ID, 파지 오프셋, 시작·정지.
 * 시작·정지 요청은 GL 스레드가 다음 프레임에서 처리한다(ARCore 세션을 한 스레드에서만 다루기 위해).
 */
class RecordScreen : Activity(), GLSurfaceView.Renderer {

    private data class StartRequest(val sceneId: String, val gripOffsetM: Vec3)

    /** GL 스레드 → UI 스레드로 넘기는 최신 상태 1개. */
    private data class LiveState(val tracking: String, val failure: String, val recording: Boolean)

    private lateinit var config: Config
    private lateinit var ar: ArSessionManager
    private lateinit var thermal: ThermalMonitor
    private lateinit var glView: GLSurfaceView
    private lateinit var status: TextView
    private lateinit var sceneSpinner: Spinner
    private lateinit var offsetInputs: List<EditText>
    private lateinit var startStop: Button

    private val background = BackgroundRenderer()
    private val ui = Handler(Looper.getMainLooper())
    private val pendingStart = AtomicReference<StartRequest?>(null)
    private val pendingStop = AtomicReference<Boolean>(false)
    private val live = AtomicReference(LiveState("-", "-", false))

    @Volatile
    private var session: Session? = null

    @Volatile
    private var recorder: Recorder? = null
    private val glTiming = GlTiming()
    private var textureBound: Session? = null // GL 스레드 전용
    private var viewportChanged = false // GL 스레드 전용
    private var viewportW = 0
    private var viewportH = 0
    private var recordStartMs = 0L
    private var arStatusMessage: String? = null
    private var lastRate: Pair<Long, Long>? = null // (시각 ms, 저장된 깊이 수)
    private var depthFps = 0f

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = AppConfig.get(this)
        ar = ArSessionManager(this)
        thermal = ThermalMonitor(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildLayout()
    }

    private fun buildLayout() {
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@RecordScreen)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            setWillNotDraw(false)
        }
        status = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 14f
            setPadding(24, 24, 24, 24)
        }
        sceneSpinner = Spinner(this).apply {
            // 반투명 검은 배경 위에서 선택값이 보이도록 닫힌 상태의 글자만 흰색으로
            adapter = object : ArrayAdapter<String>(this@RecordScreen, android.R.layout.simple_spinner_item, SCENE_IDS) {
                override fun getView(position: Int, convertView: View?, parent: ViewGroup): View =
                    (super.getView(position, convertView, parent) as TextView).apply { setTextColor(Color.WHITE) }
            }.apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
            setSelection(SCENE_IDS.indexOf("S01"))
            contentDescription = "장면 ID"
        }
        val defaultOffset = config.head.offsetFromCameraM
        offsetInputs = listOf("x" to defaultOffset.x, "y" to defaultOffset.y, "z" to defaultOffset.z).map { (axis, v) ->
            EditText(this).apply {
                hint = "offset $axis (m)"
                contentDescription = "파지 오프셋 $axis 미터"
                setText(v.toString())
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
                setTextColor(Color.WHITE)
                setHintTextColor(Color.LTGRAY)
            }
        }
        startStop = Button(this).apply {
            text = "녹화 시작"
            setOnClickListener { onStartStop() }
        }
        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0x99000000.toInt())
            setPadding(24, 16, 24, 24)
            addView(TextView(context).apply { text = "장면 ID / 파지 오프셋(카메라→머리, 월드 수평, m)"; setTextColor(Color.WHITE) })
            addView(sceneSpinner)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                offsetInputs.forEach { addView(it, LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)) }
            })
            addView(startStop, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        setContentView(FrameLayout(this).apply {
            addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(status, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(controls, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        })
    }

    override fun onResume() {
        super.onResume()
        arStatusMessage = ar.resume()
        session = ar.session
        glView.onResume()
        ui.post(uiTick)
    }

    override fun onPause() {
        super.onPause()
        ui.removeCallbacks(uiTick)
        glView.onPause() // GL 스레드가 멈춘 뒤에는 UI 스레드에서 세션을 만져도 겹치지 않는다
        recorder?.let { finishRecording(it) } // setAutoStopOnPause로 MP4도 멈춘다
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
            Toast.makeText(this, "카메라 권한 없이 녹화할 수 없습니다", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun onStartStop() {
        if (recorder != null) {
            pendingStop.set(true)
            startStop.isEnabled = false
            return
        }
        val values = offsetInputs.map { it.text.toString().trim().toFloatOrNull() }
        if (values.any { it == null }) {
            Toast.makeText(this, "파지 오프셋은 숫자(m)로 입력하세요", Toast.LENGTH_SHORT).show()
            return
        }
        pendingStart.set(StartRequest(sceneSpinner.selectedItem as String, Vec3(values[0]!!, values[1]!!, values[2]!!)))
        startStop.isEnabled = false
    }

    // ---- GL 스레드 ----

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
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
        val t0 = SystemClock.elapsedRealtimeNanos()
        val frame = try {
            s.update()
        } catch (e: CameraNotAvailableException) {
            Log.e(TAG, "camera not available during update", e)
            return
        }
        val t1 = SystemClock.elapsedRealtimeNanos()
        background.draw(frame)
        val camera = frame.camera

        pendingStart.getAndSet(null)?.let { req ->
            try {
                recorder = startRecording(s, camera, req)
            } catch (e: Exception) {
                Log.e(TAG, "failed to start recording", e)
                ui.post { Toast.makeText(this, "녹화 시작 실패: ${e.message}", Toast.LENGTH_LONG).show() }
            }
            ui.post { updateButton() }
        }
        val t2 = SystemClock.elapsedRealtimeNanos()
        recorder?.onFrame(frame, camera)
        val t3 = SystemClock.elapsedRealtimeNanos()
        glTiming.add(t0, t1, t2, t3, frame.timestamp)
        if (pendingStop.getAndSet(false)) recorder?.let { finishRecording(it) }

        live.set(LiveState(camera.trackingState.name, camera.trackingFailureReason.name, recorder != null))
    }

    private fun startRecording(s: Session, camera: com.google.ar.core.Camera, req: StartRequest): Recorder {
        val created = Date()
        val id = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(created) + "_" + req.sceneId
        val dir = File(sessionsRoot(this), id)
        check(dir.mkdirs()) { "cannot create $dir" }
        val depth = ar.depthSupport!!
        val cc = s.cameraConfig
        val meta = SessionMeta(
            formatVersion = SessionFormat.VERSION,
            sessionId = id,
            sceneId = req.sceneId,
            createdAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(created),
            device = DeviceInfo(
                Build.MANUFACTURER, Build.MODEL,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Build.SOC_MODEL else null,
                Build.VERSION.RELEASE, Build.VERSION.SDK_INT,
            ),
            arcore = ArcoreInfo(BuildConfig.ARCORE_SDK_VERSION, arcoreApkVersion()),
            depth = DepthInfo(depth.automatic, depth.rawDepthOnly, depth.modeUsed.name, null, null),
            camera = CameraInfo(
                FrameAdapter.toIntrinsics(camera.imageIntrinsics),
                FrameAdapter.toIntrinsics(camera.textureIntrinsics),
                displayRotationDeg(),
                cc.fpsRange.lower, cc.fpsRange.upper,
            ),
            gripOffsetM = req.gripOffsetM,
            elapsedMinusMonotonicNs = SystemClock.elapsedRealtimeNanos() - System.nanoTime(),
            depthEveryN = config.record.depthEveryN,
            rgbEveryN = config.record.rgbEveryN,
            conventions = CONVENTIONS,
            stats = null,
        )
        return Recorder(dir, meta, config.record, thermal).also {
            it.start(s)
            recordStartMs = SystemClock.elapsedRealtime()
            lastRate = null
        }
    }

    /** GL 스레드 또는 GL 스레드가 멈춘 뒤의 UI 스레드에서 호출. */
    private fun finishRecording(r: Recorder) {
        recorder = null
        r.stop(session) { dir ->
            ui.post {
                Toast.makeText(this, "저장 완료: ${dir.name}", Toast.LENGTH_LONG).show()
                updateButton()
            }
        }
        ui.post { updateButton() }
    }

    // ---- UI 스레드 ----

    private val uiTick = object : Runnable {
        override fun run() {
            renderStatus()
            ui.postDelayed(this, UI_REFRESH_MS)
        }
    }

    private fun updateButton() {
        startStop.text = if (recorder != null) "녹화 정지" else "녹화 시작"
        startStop.isEnabled = true
        sceneSpinner.isEnabled = recorder == null
        offsetInputs.forEach { it.isEnabled = recorder == null }
    }

    private fun renderStatus() {
        val l = live.get()
        val d = ar.depthSupport
        val sb = StringBuilder()
        arStatusMessage?.let { sb.append("⚠ ").append(it).append('\n') }
        sb.append("추적: ").append(l.tracking)
        if (l.failure != "NONE") sb.append(" (").append(l.failure).append(')')
        sb.append('\n')
        sb.append("깊이 지원(F1): AUTOMATIC=").append(d?.automatic ?: "?")
            .append(" RAW=").append(d?.rawDepthOnly ?: "?")
            .append(" → ").append(d?.modeUsed ?: "?").append('\n')
        val r = recorder
        if (r != null) {
            val st = r.stats()
            val now = SystemClock.elapsedRealtime()
            lastRate?.let { (t0, n0) -> if (now > t0) depthFps = (st.nDepthSaved - n0) * 1000f / (now - t0) }
            lastRate = now to st.nDepthSaved
            val el = (now - recordStartMs) / 1000
            sb.append("● REC ").append("%02d:%02d".format(el / 60, el % 60)).append("  ").append(r.sessionDir.name).append('\n')
            sb.append("프레임 ").append(st.nFrames)
                .append(" · 깊이 ").append(st.nDepthSaved).append(" (%.1f fps)".format(depthFps))
                .append(" · 원시 ").append(st.nRawDepthSaved)
                .append(" · RGB ").append(st.nRgbSaved).append('\n')
            sb.append("건너뜀 ").append(st.nFileJobsSkipped).append(" · 쓰기 오류 ").append(st.nWriteErrors)
            sb.append(" · 발열 ").append(thermal.thermalName(st.lastDevice?.thermalStatus))
            st.lastDevice?.batteryPct?.let { sb.append(" · 배터리 ").append(it).append('%') }
        } else {
            sb.append("대기 중")
        }
        status.text = sb
    }

    private fun displayRotation(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            display!!.rotation
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.rotation
        }

    private fun displayRotationDeg(): Int = when (displayRotation()) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }

    private fun arcoreApkVersion(): String? = try {
        packageManager.getPackageInfo("com.google.ar.core", 0).versionName
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        private const val UI_REFRESH_MS = 250L

        /** 부록 A 장면 ID. */
        val SCENE_IDS = listOf("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08", "S09", "S10", "T01")

        /** 세션 폴더의 부모: `<앱 전용 외부 저장소>/sessions` (§8.1). */
        fun sessionsRoot(activity: Activity): File = File(activity.getExternalFilesDir(null), "sessions")

        private val CONVENTIONS = linkedMapOf(
            "pose" to "Camera.getPose(): physical camera pose, ARCore GL camera (+X right, +Y up, -Z forward), world +Y up; stored raw",
            "displayPose" to "Camera.getDisplayOrientedPose(): rotated about camera Z to the display orientation (F2 check)",
            "tNs" to "Frame.getTimestamp() ns; time base not defined by ARCore",
            "sysElapsedNs" to "SystemClock.elapsedRealtimeNanos() when the GL thread received the frame",
            "depth" to "Frame.acquireDepthImage16Bits(): uint16 mm, 0 = invalid, saved as 16-bit grayscale PNG; only new depth timestamps saved",
            "rawDepth" to "Frame.acquireRawDepthImage16Bits(): uint16 mm, 0 = invalid, 16-bit PNG",
            "depthConf" to "Frame.acquireRawDepthConfidenceImage(): uint8 0-255, 8-bit PNG, same frame as rawDepth",
            "rgb" to "Frame.acquireCameraImage() YUV_420_888 -> JPEG, sensor orientation (not rotated)",
            "intrinsics" to "Camera.getImageIntrinsics()/getTextureIntrinsics(): unrotated sensor orientation",
            "device.tNs" to "SystemClock.elapsedRealtimeNanos()",
        )
    }
}

/** GL 스레드 구간별 소요 시간을 1초마다 logcat에 요약한다(병목 확인용, §14-7). GL 스레드 전용. */
private class GlTiming {
    private var windowStartNs = 0L
    private var n = 0
    private var sum = LongArray(3)
    private var max = LongArray(3)
    private var gapMax = 0L
    private var lastEndNs = 0L

    /** t0: update 전, t1: update 후, t2: 그리기 후, t3: 녹화 처리 후. */
    fun add(t0: Long, t1: Long, t2: Long, t3: Long, frameTNs: Long) {
        if (windowStartNs == 0L) windowStartNs = t0
        val parts = longArrayOf(t1 - t0, t2 - t1, t3 - t2)
        for (i in 0..2) {
            sum[i] += parts[i]
            if (parts[i] > max[i]) max[i] = parts[i]
        }
        if (lastEndNs != 0L && t0 - lastEndNs > gapMax) gapMax = t0 - lastEndNs
        lastEndNs = t3
        n++
        if (t3 - windowStartNs >= 1_000_000_000L) {
            fun ms(v: Long) = "%.1f".format(v / 1e6)
            Log.i(
                TAG,
                "gl timing n=$n update avg ${ms(sum[0] / n)} max ${ms(max[0])} | draw avg ${ms(sum[1] / n)} max ${ms(max[1])} | " +
                    "record avg ${ms(sum[2] / n)} max ${ms(max[2])} | idle gap max ${ms(gapMax)} | lag(sys-frame) ${ms(t3 - frameTNs)}",
            )
            windowStartNs = 0L
            n = 0
            sum = LongArray(3)
            max = LongArray(3)
            gapMax = 0L
        }
    }
}
