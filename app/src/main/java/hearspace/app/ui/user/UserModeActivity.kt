package hearspace.app.ui.user

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.opengl.GLSurfaceView
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import hearspace.app.AppConfig
import hearspace.app.R
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
import hearspace.core.types.Config
import hearspace.core.types.ConfigException
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 사용자 모드(§11.2): 화면을 보지 않고 쓴다. 화면 전체가 버튼 하나.
 * - 두 번 탭: 시작 / 일시정지 / 재개. 길게 누르기: 안내 종료(앱에 남아 시작 화면으로). TalkBack이 켜져 있으면 TalkBack의 두 번 탭(클릭)·두 번 탭 후 유지(길게 클릭).
 * - 모든 상태 전이는 알림음 + 진동. 상태 한 줄은 TalkBack이 바뀔 때 읽는다(live region).
 * - 화면(M45 시안 §3): 늘 검은 배경, 위 상태 줄 + 가운데 그림([GuideView]) + 아래 조작 안내. 무엇을 그릴지는 [UserScreen.of].
 *   설정 단추는 안내 중이 아닐 때만 보인다. 배터리: 화면 켜짐 유지.
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
    private lateinit var titleText: TextView
    private lateinit var subText: TextView
    private lateinit var guide: GuideView
    private lateinit var labelText: TextView
    private lateinit var hintText: TextView
    private lateinit var noticeText: TextView
    private lateinit var settingsButton: ImageButton

    private val ui = Handler(Looper.getMainLooper())
    private var run: RunSession? = null
    private var lastDeviceMs = 0L

    /** 안내 중이 아닐 때 보일 알림(시작 실패·중단). 다음 시작에서 지운다. */
    private var notice: UserScreen.Notice? = null

    /** 마지막으로 그린 화면(같으면 다시 그리지 않는다)과 TalkBack 문장의 상태 낱말. */
    private var shown: UserScreen? = null
    private var spokenLine: String? = null

    /** 기기 설정 덮어쓰기 표시(적용 중이거나 형식 오류로 버림). 없으면 null. */
    private var overrideNotice: String? = null

    /** ARCore 고지를 TalkBack으로 읽힌 뒤 처음 시작했는지(그 뒤로는 화면에만 둔다). */
    private val prefs get() = getSharedPreferences(PREFS, MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(R.style.Theme_Hearspace_Run) // 시작 창(스플래시) 테마에서 작동 화면 테마로
        super.onCreate(savedInstanceState)
        config = try {
            AppConfig.get(this)
        } catch (e: ConfigException) {
            // 기기 덮어쓰기 파일이 잘못됐으면 버리고 앱 기본 설정으로 안내한다(A1 FA2: 실행마다 죽지 않게). 앱 자산 설정은 테스트가 검사한다
            Log.e(TAG, "device config override rejected", e)
            AppConfig.get(this, deviceOverride = false)
        }
        overrideNotice = AppConfig.overrideNotice(this)
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
        if (savedInstanceState == null) holdSplash()
    }

    /**
     * 스플래시를 최소 [SPLASH_MIN_MS] 보인다(M45). 시작이 빨라(첫 그리기 약 0.1 s) 시스템 스플래시가 로고를 보이기 전에 사라지므로
     * 첫 그리기를 그만큼 미룬다(Android 12+는 첫 그리기까지 스플래시, 11 이하는 시작 창 배경이 남는다).
     */
    private fun holdSplash() {
        val until = SystemClock.uptimeMillis() + SPLASH_MIN_MS
        root.viewTreeObserver.addOnPreDrawListener(object : ViewTreeObserver.OnPreDrawListener {
            override fun onPreDraw(): Boolean {
                val wait = until - SystemClock.uptimeMillis()
                if (wait > 0) {
                    root.postDelayed({ root.invalidate() }, wait)
                    return false
                }
                root.viewTreeObserver.removeOnPreDrawListener(this)
                return true
            }
        })
    }

    private fun buildLayout() {
        val white = getColor(R.color.run_text)
        val gray = getColor(R.color.run_text2)
        glView = GLSurfaceView(this).apply {
            preserveEGLContextOnPause = true
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8, 8, 8, 8, 16, 0)
            setRenderer(this@UserModeActivity)
            renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // 아래 글들은 모두 버튼(root)의 설명으로 읽힌다
        titleText = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            compoundDrawablePadding = dp(12)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        subText = TextView(this).apply {
            textSize = 18f
            setTextColor(gray)
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(6), dp(28), 0)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        guide = GuideView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        labelText = TextView(this).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        hintText = TextView(this).apply {
            textSize = 18f
            setTextColor(gray)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setPadding(dp(24), dp(16), dp(24), dp(28))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        noticeText = TextView(this).apply {
            textSize = 12.5f
            setTextColor(gray)
            setPadding(dp(26), 0, dp(26), dp(20))
            text = listOfNotNull(overrideNotice, UserTexts.ARCORE_NOTICE).joinToString("\n\n")
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // 설정 단추: 안내 중이 아닐 때만. TalkBack에서는 화면 버튼과 따로 잡힌다
        settingsButton = ImageButton(this).apply {
            setImageResource(R.drawable.ic_settings)
            imageTintList = ColorStateList.valueOf(white)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), getColor(R.color.run_outline))
            }
            contentDescription = "설정"
            setOnClickListener { startActivity(Intent(this@UserModeActivity, SettingsActivity::class.java)) }
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(titleText, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { topMargin = dp(44) })
            addView(subText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            // 그림과 그 아래 글자를 한 덩어리로 가운데에(위아래 빈칸이 남는 자리를 나눠 가짐). 그림 높이는 show()에서
            addView(Space(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(guide, LinearLayout.LayoutParams(MATCH_PARENT, dp(GUIDE_DP)))
            addView(labelText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(Space(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(hintText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(noticeText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
                if (run != null) info.addAction(AccessibilityAction(AccessibilityNodeInfo.ACTION_LONG_CLICK, "안내 종료"))
            }
        }.apply {
            setBackgroundColor(getColor(R.color.run_bg))
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
            addView(column, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(settingsButton, FrameLayout.LayoutParams(dp(56), dp(56), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })
            padForSystemBars()
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
                // 안내만 끝내고 앱에 남는다(시작 화면으로). 시작 전에는 할 일이 없다. 앱은 시스템 뒤로·홈으로 나간다(사용자 요청 2026-10-30)
                if (run == null) return
                haptics.exit()
                stop()
            }
        }
        render()
    }

    private fun start() {
        notice = null
        val reason = try {
            ar.resume()
        } catch (e: Exception) {
            Log.e(TAG, "ARCore start failed", e)
            "ARCore를 시작할 수 없습니다"
        }
        if (reason != null) {
            // 권한 창이 뜬 경우는 그 창이 안내한다. 나머지 실패는 긴 진동 + 상태 줄(TalkBack)로 알린다(A1 TR1: 소리 출력은 아직 없다)
            if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) haptics.on(AlertKind.UNKNOWN)
            notice = UserScreen.Notice.startFailed(reason)
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
            notice = UserScreen.Notice.startFailed("소리 출력을 시작할 수 없습니다")
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
        notice = UserScreen.Notice.stopped(reason)
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
        // 앱을 떠나면(화면 잠금·전화) 안내를 멈춘다. 돌아오면 다시 두 번 탭으로 시작. 설정 화면은 안내 중이 아닐 때만 열린다
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
            notice = UserScreen.Notice.startFailed("카메라 권한이 없어 시작할 수 없습니다")
        }
        render()
    }

    /** 지금 상태를 화면([UserScreen])으로 바꿔 그리고, 버튼 설명(TalkBack이 읽는 문장)을 갱신한다. */
    private fun render() {
        val r = run
        val g = r?.pipeline?.lastOutput?.get()
        val cmd = g?.commands?.firstOrNull()
        val s = UserScreen.of(
            running = r != null,
            notice = notice,
            paused = r?.pipeline?.paused == true,
            state = g?.state,
            mapHealth = r?.pipeline?.snapshot?.get()?.mapHealth,
            band = cmd?.band,
            azimuthDeg = cmd?.azimuthDeg,
        )
        if (s.spoken != spokenLine) {
            spokenLine = s.spoken
            val hint = if (r == null) "두 번 탭하여 시작" else "두 번 탭하여 일시정지 또는 재개, 길게 눌러 안내 종료"
            // 처음 시작하기 전에는 ARCore 고지(TR7)를 함께 읽는다. 기기 설정 덮어쓰기는 늘 읽는다(TR4)
            val read = listOfNotNull(
                overrideNotice,
                UserTexts.ARCORE_NOTICE.takeIf { r == null && !prefs.getBoolean(PREF_NOTICE_READ, false) },
            ).joinToString("") { "$it. " }
            root.contentDescription = "HEARSPACE, ${s.spoken}. $read$hint"
        }
        if (s != shown) show(s)
    }

    private fun show(s: UserScreen) {
        shown = s
        titleText.visibility = if (s.title == null) View.GONE else View.VISIBLE
        titleText.text = s.title
        titleText.setTextColor(getColor(if (s.titleTone == UserScreen.Tone.CAUTION) R.color.run_caution else R.color.run_text))
        titleText.setCompoundDrawablesRelativeWithIntrinsicBounds(if (s.titleTone == UserScreen.Tone.GUIDE) guideDot else null, null, null, null)
        subText.visibility = if (s.sub == null) View.GONE else View.VISIBLE
        subText.text = s.sub
        guide.visual = s.visual
        // 그림 칸: 시안의 가장 큰 그림(화살표 230 dp)이 들어가는 높이, 시작 전 로고(150 dp)는 글자와 가깝게
        val guideHeight = dp(if (s.visual == UserScreen.Visual.Logo) LOGO_DP else GUIDE_DP)
        if (guide.layoutParams.height != guideHeight) guide.layoutParams = guide.layoutParams.apply { height = guideHeight }
        labelText.visibility = if (s.label == null) View.GONE else View.VISIBLE
        labelText.text = s.label
        labelText.textSize = when (s.labelTone) {
            UserScreen.Tone.STOP -> 36f
            UserScreen.Tone.CAUTION -> 30f
            else -> 34f
        }
        labelText.setTextColor(getColor(when {
            s.label == null || s.visual !is UserScreen.Visual.Arrow -> R.color.run_text
            s.labelTone == UserScreen.Tone.STOP -> R.color.run_stop
            else -> R.color.run_caution
        }))
        // "두 번 탭 · 일시정지": 동작은 흰 굵은 글, 뜻은 회색
        hintText.visibility = if (s.hints.isEmpty()) View.INVISIBLE else View.VISIBLE
        hintText.text = SpannableStringBuilder().apply {
            s.hints.forEachIndexed { i, (gesture, meaning) ->
                if (i > 0) append("\n")
                val at = length
                append(gesture)
                setSpan(StyleSpan(Typeface.BOLD), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                setSpan(ForegroundColorSpan(getColor(R.color.run_text)), at, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                append(" · $meaning")
            }
        }
        settingsButton.visibility = if (s.idle) View.VISIBLE else View.GONE
        noticeText.visibility = if (s.idle && s.visual == UserScreen.Visual.Logo) View.VISIBLE else View.GONE
    }

    /** "안내 중" 앞의 청록 점(앱이 동작 중임만 보인다). */
    private val guideDot by lazy {
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(getColor(R.color.run_guide))
            setSize(dp(14), dp(14))
        }
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

    companion object {
        private const val UI_REFRESH_MS = 200L
        /** 스플래시 최소 시간(ms): 로고가 보일 만큼(직접 정한 값). */
        private const val SPLASH_MIN_MS = 800L
        private const val GUIDE_DP = 240
        private const val LOGO_DP = 176
        private const val PREFS = "user_mode"
        private const val PREF_NOTICE_READ = "arcoreNoticeRead"
    }
}
