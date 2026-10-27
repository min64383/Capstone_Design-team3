package hearspace.app.ui.user

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.FrameLayout
import android.widget.TextView
import hearspace.app.AppConfig
import hearspace.app.TAG
import hearspace.app.ar.ArFeeder
import hearspace.app.ar.ArSessionManager
import hearspace.app.ar.displayRotationCompat
import hearspace.app.loadHrtf
import hearspace.app.runtime.AudioFocus
import hearspace.app.runtime.Haptics
import hearspace.app.runtime.RunSession
import hearspace.app.runtime.ThermalMonitor
import hearspace.core.audio.Hrtf
import hearspace.core.types.AlertKind
import hearspace.core.types.Band
import hearspace.core.types.Config
import hearspace.core.types.ConfigException
import hearspace.core.types.GuidanceState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 사용자 모드(§11.2): 화면을 보지 않고 쓴다. 화면 전체가 버튼 하나.
 * - 두 번 탭: 시작 / 일시정지 / 재개. 길게 누르기: 종료. TalkBack이 켜져 있으면 TalkBack의 두 번 탭(클릭)·두 번 탭 후 유지(길게 클릭).
 * - 모든 상태 전이는 알림음 + 진동. 상태 한 줄은 TalkBack이 바뀔 때 읽는다(live region).
 * - 저시력: 검은 배경, 고대비 큰 글씨, 가장 가까운 장애물 방향 화살표. 배터리: 밝기 최소, 화면 켜짐 유지.
 * 입력은 [onCommand] 하나로 모은다: 나중에 음성 명령(§17)도 같은 곳으로 넣는다.
 * 흐름·알림음·진동 패턴은 가설이며 사용자 평가에서 검증한다.
 */
class UserModeActivity : Activity(), GLSurfaceView.Renderer {

    /** 사용자 명령. 터치와 (나중의) 음성이 같은 명령으로 바뀐다. */
    enum class UserCommand { TOGGLE, EXIT }

    private lateinit var config: Config
    private lateinit var hrtf: Hrtf
    private lateinit var ar: ArSessionManager
    private lateinit var feeder: ArFeeder
    private lateinit var thermal: ThermalMonitor
    private lateinit var haptics: Haptics
    private lateinit var focus: AudioFocus
    private lateinit var a11y: AccessibilityManager
    private lateinit var glView: GLSurfaceView
    private lateinit var root: FrameLayout
    private lateinit var stateText: TextView
    private lateinit var arrow: ArrowView

    private lateinit var noticeText: TextView

    private val ui = Handler(Looper.getMainLooper())
    private var run: RunSession? = null
    private var lastDeviceMs = 0L

    /** 안내 중이 아닐 때 상태 줄 대신 보일 사유(시작 실패·중단). 다음 시작에서 지운다. */
    private var message: String? = null

    /** 기기 설정 덮어쓰기 표시(적용 중이거나 형식 오류로 버림). 없으면 null. */
    private var overrideNotice: String? = null

    /** ARCore 고지를 TalkBack으로 읽힌 뒤 처음 시작했는지(그 뒤로는 화면에만 둔다). */
    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = try {
            AppConfig.get(this)
        } catch (e: ConfigException) {
            // 기기 덮어쓰기 파일이 잘못됐으면 버리고 앱 기본 설정으로 안내한다(A1 FA2: 실행마다 죽지 않게). 앱 자산 설정은 테스트가 검사한다
            Log.e(TAG, "device config override rejected", e)
            overrideNotice = "기기 설정 파일 오류: 기본 설정으로 안내합니다"
            AppConfig.get(this, deviceOverride = false)
        }
        if (overrideNotice == null && AppConfig.deviceOverrideFile(this)?.isFile == true) overrideNotice = "기기 설정 덮어쓰기 적용 중"
        hrtf = loadHrtf(this)
        ar = ArSessionManager(this)
        feeder = ArFeeder(config, replay = false, drawCamera = false)
        thermal = ThermalMonitor(this)
        haptics = Haptics(this, config.haptics)
        focus = AudioFocus(this)
        a11y = getSystemService(AccessibilityManager::class.java)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildLayout()
        render()
    }

    private fun buildLayout() {
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@UserModeActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        stateText = TextView(this).apply {
            setTextColor(Color.YELLOW)
            textSize = 44f
            gravity = Gravity.CENTER
            setPadding(32, 96, 32, 32)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // 버튼(root)이 읽는다
        }
        arrow = ArrowView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        noticeText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 32, 32, 48)
            text = listOfNotNull(overrideNotice, ARCORE_NOTICE).joinToString("\n\n")
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO // 버튼(root)이 읽는다
        }
        val detector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            // 두 번째 탭을 뗄 때 처리: 누를 때 처리하면 시작(약 0.6 s)이 UI를 막는 동안 길게 누르기로 오인된다(M9 실측)
            override fun onDoubleTapEvent(e: MotionEvent): Boolean {
                if (e.actionMasked == MotionEvent.ACTION_UP) onCommand(UserCommand.TOGGLE)
                return true
            }
            override fun onLongPress(e: MotionEvent) = onCommand(UserCommand.EXIT)
        })
        root = object : FrameLayout(this) {
            override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(info)
                info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_CLICK, if (run == null) "시작" else "일시정지 또는 재개"))
                info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "종료"))
            }
        }.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isLongClickable = true
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            // TalkBack: 두 번 탭 = 클릭, 두 번 탭 후 유지 = 길게 클릭. TalkBack이 없으면 제스처(한 번 탭은 무시).
            setOnClickListener { if (a11y.isTouchExplorationEnabled) onCommand(UserCommand.TOGGLE) }
            setOnLongClickListener {
                if (a11y.isTouchExplorationEnabled) onCommand(UserCommand.EXIT)
                true
            }
            setOnTouchListener { _, ev -> !a11y.isTouchExplorationEnabled && detector.onTouchEvent(ev) }
            addView(glView, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT)) // ARCore 입력용, 검은색만 그린다
            addView(stateText, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.TOP))
            addView(arrow, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(noticeText, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        setContentView(root)
    }

    /** 모든 사용자 입력이 모이는 곳(터치, 나중에 음성). */
    fun onCommand(cmd: UserCommand) {
        Log.i(TAG, "user command $cmd (running=${run != null})")
        when (cmd) {
            UserCommand.TOGGLE -> {
                val r = run
                if (r == null) start() else r.pipeline.paused = !r.pipeline.paused
            }
            UserCommand.EXIT -> {
                haptics.exit()
                stop()
                finish()
            }
        }
        render()
    }

    private fun start() {
        message = null
        val reason = try {
            ar.resume()
        } catch (e: Exception) {
            Log.e(TAG, "ARCore start failed", e)
            "ARCore를 시작할 수 없습니다"
        }
        if (reason != null) {
            // 권한 창이 뜬 경우는 그 창이 안내한다. 나머지 실패는 긴 진동 + 상태 줄(TalkBack)로 알린다(A1 TR1: 소리 출력은 아직 없다)
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) haptics.on(AlertKind.UNKNOWN)
            message = reason
            return
        }
        feeder.session = ar.session
        feeder.resetClock()
        focus.request()
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val runs = File(getExternalFilesDir(null), RunSession.RUNS_DIR)
        val r = try {
            RunSession(config, hrtf, File(runs, stamp), feeder::nowNs)
        } catch (e: Exception) {
            Log.e(TAG, "run start failed", e) // 오디오 출력을 열지 못함(A1 FA3)
            stop()
            haptics.on(AlertKind.UNKNOWN)
            message = "소리 출력을 시작할 수 없습니다"
            return
        }
        thread(name = "RunsTrim") { RunSession.trimRuns(runs, config.record.maxRuns) }
        run = r.also {
            it.pipeline.onAlert = { kind ->
                // 오디오 스레드에서 불린다: 진동(바인더 호출)·로그는 UI 스레드로 넘긴다(A1 CO5)
                ui.post {
                    haptics.on(kind)
                    Log.i(TAG, "alert $kind")
                    render()
                }
            }
            it.pipeline.requestAlert(AlertKind.START)
            feeder.pipeline = it.pipeline
        }
        prefs.edit().putBoolean(PREF_NOTICE_READ, true).apply()
        glView.onResume()
        ui.post(uiTick)
    }

    /** 안내 중에 멈췄음을 알린다: 진동 + 상태 줄(TalkBack). 소리 출력이 없거나 곧 닫히므로 진동으로 낸다(A1 TR2·TR3). */
    private fun stopWithNotice(reason: String, vibrate: () -> Unit) {
        vibrate()
        stop()
        message = reason
        render()
    }

    private fun stop() {
        ui.removeCallbacks(uiTick)
        feeder.pipeline = null
        run?.close()
        run = null
        focus.abandon()
        glView.onPause()
        ar.pause()
    }

    override fun onPause() {
        super.onPause()
        // 앱을 떠나면(화면 잠금·전화) 안내를 멈춘다. 돌아오면 다시 두 번 탭으로 시작
        // VERIFY: 화면이 꺼지는 중(onPause)에도 진동이 전달되는지(Android 13+ 백그라운드 진동 제한) 기기에서 확인
        if (run != null) stopWithNotice("화면을 떠나 안내를 멈췄습니다", haptics::exit) else stop()
        render()
    }

    override fun onDestroy() {
        ar.close()
        feeder.session = null
        super.onDestroy()
    }

    @Deprecated("Activity API")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != ArSessionManager.CAMERA_PERMISSION_REQUEST) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            start()
        } else {
            haptics.on(AlertKind.UNKNOWN) // A1 TR1: 거부도 알린다
            message = "카메라 권한이 없어 시작할 수 없습니다"
        }
        render()
    }

    /** 상태 한 줄 + 버튼 설명(TalkBack이 읽는 문장) + 화살표. */
    private fun render() {
        val g = run?.pipeline?.lastOutput?.get()
        val line = when {
            run == null -> message ?: "시작 전"
            run!!.pipeline.paused -> "일시정지"
            g == null || g.state == GuidanceState.UNKNOWN -> "준비 중"
            g.state == GuidanceState.PAUSED -> "일시정지"
            else -> "안내 중"
        }
        if (stateText.text != line) {
            stateText.text = line
            val hint = if (run == null) "두 번 탭하여 시작, 길게 눌러 종료" else "두 번 탭하여 일시정지 또는 재개, 길게 눌러 종료"
            // 처음 시작하기 전에는 ARCore 고지(TR7)를 함께 읽는다. 기기 설정 덮어쓰기는 늘 읽는다(TR4)
            val notice = listOfNotNull(
                overrideNotice,
                ARCORE_NOTICE.takeIf { run == null && !prefs.getBoolean(PREF_NOTICE_READ, false) },
            ).joinToString("") { "$it. " }
            root.contentDescription = "HEARSPACE, $line. $notice$hint"
        }
        val cmd = g?.commands?.firstOrNull()?.takeIf { it.band != Band.SILENT && g.state != GuidanceState.UNKNOWN }
        arrow.show(cmd?.azimuthDeg, cmd?.band == Band.STOP)
    }

    private val uiTick = object : Runnable {
        override fun run() {
            if (run?.output?.failed == true) { // 오디오 출력이 죽음(다시 만들기도 실패): 소리 없이 "안내 중"으로 남지 않는다(A1 TR2)
                stopWithNotice("소리 출력이 멈춰 안내를 멈췄습니다") { haptics.on(AlertKind.UNKNOWN) }
                return
            }
            render()
            val now = SystemClock.elapsedRealtime()
            val r = run
            if (r != null && now - lastDeviceMs >= config.record.deviceLogIntervalS * 1000) {
                lastDeviceMs = now
                r.logDevice(thermal)
            }
            ui.postDelayed(this, UI_REFRESH_MS)
        }
    }

    // ---- GL 스레드 ----

    override fun onSurfaceCreated(gl: GL10?, cfg: EGLConfig?) = feeder.onSurfaceCreated()

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) = feeder.onSurfaceChanged(width, height)

    override fun onDrawFrame(gl: GL10?) {
        feeder.onDrawFrame(displayRotationCompat())
    }

    /** 가장 가까운 장애물 방향 화살표(저시력용). 음원이 없으면 아무것도 그리지 않는다("없음" 표시 안 함: 무음 ≠ 안전). */
    private class ArrowView(context: Context) : View(context) {
        private var azimuthDeg: Float? = null
        private var stop = false
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        fun show(az: Float?, isStop: Boolean) {
            if (az == azimuthDeg && isStop == stop) return
            azimuthDeg = az
            stop = isStop
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val az = azimuthDeg ?: return
            paint.color = if (stop) Color.RED else Color.YELLOW
            val cx = width / 2f
            val cy = height * 0.55f
            val r = width * 0.35f
            canvas.save()
            canvas.rotate(az, cx, cy)
            canvas.drawPath(Path().apply {
                moveTo(cx, cy - r)
                lineTo(cx - r * 0.45f, cy - r * 0.35f)
                lineTo(cx - r * 0.15f, cy - r * 0.35f)
                lineTo(cx - r * 0.15f, cy + r * 0.6f)
                lineTo(cx + r * 0.15f, cy + r * 0.6f)
                lineTo(cx + r * 0.15f, cy - r * 0.35f)
                lineTo(cx + r * 0.45f, cy - r * 0.35f)
                close()
            }, paint)
            canvas.restore()
        }
    }

    companion object {
        private const val UI_REFRESH_MS = 200L
        private const val PREFS = "user_mode"
        private const val PREF_NOTICE_READ = "arcoreNoticeRead"

        /** ARCore Additional Terms의 고지(docs/LICENSES.md). 문구는 정해져 있지 않고 "ARCore 기능 포함 + Google 약관·개인정보처리방침 적용"을 알리면 된다. */
        private const val ARCORE_NOTICE = "이 앱은 Google의 ARCore 기능을 포함합니다. ARCore 사용에는 Google 서비스 약관(policies.google.com/terms)과 " +
            "Google 개인정보처리방침(google.com/policies/privacy)의 최신 버전이 적용됩니다"
    }
}
