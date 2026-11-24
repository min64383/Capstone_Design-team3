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
import android.text.TextUtils
import android.util.TypedValue
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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import android.widget.TextView
import hearspace.app.AppConfig
import hearspace.app.R
import hearspace.app.TAG
import hearspace.core.screen.UserScreen
import hearspace.app.ar.ArFeeder
import hearspace.app.ar.ArSessionManager
import hearspace.app.ar.displayRotationCompat
import hearspace.app.loadHrtf
import hearspace.app.runtime.AndroidTts
import hearspace.app.runtime.AudioFocus
import hearspace.app.runtime.Haptics
import hearspace.app.runtime.RunSession
import hearspace.app.runtime.RunTap
import hearspace.app.runtime.SpeechOut
import hearspace.app.runtime.TtsBench
import hearspace.app.runtime.ThermalMonitor
import hearspace.core.audio.Hrtf
import hearspace.core.types.AlertKind
import hearspace.core.types.Config
import hearspace.core.types.ConfigException
import hearspace.core.types.GuidanceState
import hearspace.core.types.MapHealth
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
    /** [config]가 기기 설정 덮어쓰기를 썼는지(형식 오류로 버렸으면 false). 데모 중계 고리에 같은 설정 층을 넘긴다(M67). */
    private var configDeviceOverride = true
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
    private lateinit var detailDir: TextView
    private lateinit var detailDist: TextView
    private lateinit var detailHeight: TextView
    private lateinit var detailSep1: TextView
    private lateinit var detailSep2: TextView
    private lateinit var speechIcon: ImageView
    private lateinit var speechText: TextView
    private lateinit var hintText: TextView
    private lateinit var noticeText: TextView
    private lateinit var settingsButton: ImageButton

    private val ui = Handler(Looper.getMainLooper())
    private var run: RunSession? = null
    private var tts: AndroidTts? = null
    /** 상황 말·상태 말을 등급 표로 골라 [tts]로 낸다(M65). [tts]와 함께 만들고 닫는다. */
    private var speechOut: SpeechOut? = null
    private var announceLaunch = false
    private val speechPolicy = StatusSpeechPolicy()
    private var lastSpeechStatus: StatusSpeechPolicy.Status? = null
    private var foreground = false
    private var endedByUser = false
    private var ttsFailure: String? = null
    /** 지금 말하는 TTS 줄(id, 문장): 시작 사건부터 같은 줄의 끝·끊김·오류 사건까지(M72 말 줄). UI 스레드만. */
    private var speaking: Pair<String, String>? = null
    private var screenReaderActive = false
    private var lastDeviceMs = 0L
    /** M65 실험 E1(개발 도구 빌드, 인자 `ttsBench` = 되풀이 수): 안내를 켠 채 고정 문구의 TTS 시작 지연을 잰다. */
    private var bench: TtsBench? = null
    private var benchDone = false

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
            configDeviceOverride = false
            AppConfig.get(this, deviceOverride = false)
        }
        overrideNotice = AppConfig.overrideNotice(this)
        hrtf = loadHrtf(this)
        ar = ArSessionManager(this, sensorUsage = config.depth.sensorUsage, semantics = config.vision.semantics)
        feeder = ArFeeder(config, replay = false, drawCamera = false)
        thermal = ThermalMonitor(this)
        haptics = Haptics(this, config.haptics)
        focus = AudioFocus(this)
        a11y = getSystemService(AccessibilityManager::class.java)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildLayout()
        render()
        announceLaunch = savedInstanceState == null
        if (announceLaunch) holdSplash()
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

    override fun onResume() {
        super.onResume()
        foreground = true
        ttsFailure = null
        speechPolicy.reset()
        lastSpeechStatus = null
        endedByUser = false
        screenReaderActive = a11y.isTouchExplorationEnabled
        shown = null
        spokenLine = null
        // 데모 중계(M67): bench 빌드만 고리가 채워져 있다. 일반 빌드는 비어 있어 아무것도 하지 않는다
        RunTap.current?.resumed(feeder::nowNs, AppConfig.layers(this, configDeviceOverride), noticeText.text?.toString())
        tts = AndroidTts(this, config, onDuckingChanged = { active ->
            run?.pipeline?.speechActive = active
        }, onEvent = { event ->
            // UI 스레드(AndroidTts가 main으로 넘겨 부른다)
            if (event.state == AndroidTts.State.STARTED) speaking = event.id to event.text
            else if (speaking?.first == event.id) speaking = null
            if (foreground) ui.post { if (foreground) render() }
            RunTap.current?.speech(event)
            speechOut?.onEvent(event)
            bench?.onEvent(event)
            if (foreground && event.state == AndroidTts.State.STARTED && ttsFailure != null) {
                ttsFailure = null
                shown = null
                spokenLine = null
                ui.post { if (foreground) render() }
            }
            if (foreground && event.state == AndroidTts.State.ERROR && ttsFailure == null) {
                // TTS 자체로 오류를 읽으려 하지 않는다. 화면/TalkBack/진동은 계속 제공한다.
                ttsFailure = "음성 안내 오류가 발생했습니다. 반복되면 오프라인 한국어 음성 설정을 확인해 주세요."
                haptics.on(AlertKind.UNKNOWN)
                shown = null
                spokenLine = null
                ui.post { if (foreground) render() }
            }
        }, onFirstAudio = { id -> bench?.onFirstAudio(id) })
        speechOut = tts?.let { SpeechOut(it, feeder::nowNs) }
        if (announceLaunch) {
            announceLaunch = false
            if (!screenReaderActive) speechOut?.status("앱이 시작했습니다", safety = false)
        }
        render()
        ui.removeCallbacks(uiTick)
        ui.post(uiTick)
        if (hearspace.app.BuildConfig.DEV_TOOLS && intent.getIntExtra(EXTRA_TTS_BENCH, 0) > 0 && run == null && !benchDone) {
            ui.postDelayed({ if (foreground && run == null) { start(); startBench() } }, 1000)
        }
    }

    /** E1: 안내를 켠 뒤 ARCore가 자리 잡을 때까지 기다렸다가 문구를 차례로 말한다. 측정 중에는 상황 말·상태 말을 내지 않는다. */
    private fun startBench() {
        val r = run ?: return
        val t = tts ?: return
        speechOut = null
        r.pipeline.onSpeech = null
        val talkBack = a11y.isTouchExplorationEnabled
        Log.i(TAG, "tts bench: reps ${intent.getIntExtra(EXTRA_TTS_BENCH, 0)}, talkBack $talkBack")
        ui.postDelayed({
            if (run !== r) return@postDelayed
            bench = TtsBench(t, r.logger, feeder::nowNs, intent.getIntExtra(EXTRA_TTS_BENCH, 0),
                if (talkBack) ({ @Suppress("DEPRECATION") root.announceForAccessibility("시험 알림 문장입니다") }) else null,
            ) { bench = null; benchDone = true; stop(); render() }.also { it.start() }
        }, BENCH_WARMUP_MS)
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
        // 아래 글들은 모두 버튼(root)의 설명으로 읽힌다.
        // M72 칸 고정 배치(milepost demo-relay/M72-design 표 3): 칸마다 높이가 정해져 있고(8 dp 격자) 내용이 없어도 자리를 지킨다.
        // 그래서 정보 줄·말 줄이 나타나고 사라져도 다른 글자·기호가 위아래로도 좌우로도 움직이지 않는다. 중계 페이지 index.html과 같은 칸
        titleText = TextView(this).apply {
            textSize = 40f
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            compoundDrawablePadding = dp(12)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        subText = TextView(this).apply {
            setTextColor(gray)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(4), dp(28), 0)
            // 두 줄(18)까지. 음성 오류 글처럼 길면 칸을 늘리지 않고 글자를 줄인다
            setAutoSizeTextTypeUniformWithConfiguration(12, 18, 1, TypedValue.COMPLEX_UNIT_SP)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        guide = GuideView(this).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO }
        labelText = TextView(this).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            maxLines = 1
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        // 정보 줄: 거리를 가운데 고정하고 방향은 그 왼쪽(오른쪽 맞춤), 높이는 그 오른쪽(왼쪽 맞춤). 숫자는 폭이 같은 숫자
        fun detailCell(g: Int, color: Int) = TextView(this).apply {
            textSize = 22f
            setTextColor(color)
            gravity = g or Gravity.CENTER_VERTICAL
            maxLines = 1
            fontFeatureSettings = "tnum"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        detailDir = detailCell(Gravity.END, white)
        detailDist = detailCell(Gravity.CENTER_HORIZONTAL, white)
        detailHeight = detailCell(Gravity.START, white)
        detailSep1 = detailCell(Gravity.CENTER_HORIZONTAL, gray).apply { text = "·" }
        detailSep2 = detailCell(Gravity.CENTER_HORIZONTAL, gray).apply { text = "·" }
        val detailRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), 0, dp(12), 0)
            addView(detailDir, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
            addView(detailSep1, LinearLayout.LayoutParams(dp(22), MATCH_PARENT))
            addView(detailDist, LinearLayout.LayoutParams(WRAP_CONTENT, MATCH_PARENT))
            addView(detailSep2, LinearLayout.LayoutParams(dp(22), MATCH_PARENT))
            addView(detailHeight, LinearLayout.LayoutParams(0, MATCH_PARENT, 1f))
        }
        // 말 줄: 말 표시를 칸 위 가운데에 고정하고 문장은 그 아래(두 줄까지). 표시를 문장 옆에 두면 문장 길이에 따라 옆으로 움직인다
        speechIcon = ImageView(this).apply {
            setImageResource(R.drawable.ic_speaking)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        speechText = TextView(this).apply {
            textSize = 21f
            setTextColor(white)
            gravity = Gravity.CENTER_HORIZONTAL
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val speechRow = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(28), dp(4), dp(28), 0)
            addView(speechIcon, LinearLayout.LayoutParams(dp(26), dp(22)))
            addView(speechText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(6) })
        }
        hintText = TextView(this).apply {
            textSize = 18f
            setTextColor(gray)
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            setLineSpacing(0f, 1.3f)
            setPadding(dp(24), dp(12), dp(24), 0)
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
            setImageResource(R.drawable.ic_menu)
            imageTintList = ColorStateList.valueOf(white)
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.TRANSPARENT)
                setStroke(dp(2), getColor(R.color.run_outline))
            }
            contentDescription = "설정"
            setOnClickListener { startActivity(Intent(this@UserModeActivity, SettingsActivity::class.java)) }
        }
        // 아래 두 칸(말 줄 + 조작 안내). 시작 전에는 이 자리에 고지 글을 겹쳐 보인다(그때 두 칸은 비어 있다)
        val bottom = FrameLayout(this).apply {
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(speechRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(SPEECH_DP)))
                addView(hintText, LinearLayout.LayoutParams(MATCH_PARENT, dp(HINTS_DP)))
            }, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
            addView(noticeText, FrameLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT, Gravity.BOTTOM))
        }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 폭은 글자에 맞춰 가운데에: 안내 중 점(compound drawable)은 칸의 왼쪽 끝에 붙으므로 칸이 화면 폭이면 점이 화면 왼쪽 끝으로 간다
            addView(titleText, LinearLayout.LayoutParams(WRAP_CONTENT, dp(56)).apply { topMargin = dp(32); gravity = Gravity.CENTER_HORIZONTAL })
            addView(subText, LinearLayout.LayoutParams(MATCH_PARENT, dp(56)))
            addView(Space(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(guide, LinearLayout.LayoutParams(MATCH_PARENT, dp(GUIDE_DP)))
            addView(labelText, LinearLayout.LayoutParams(MATCH_PARENT, dp(48)))
            addView(detailRow, LinearLayout.LayoutParams(MATCH_PARENT, dp(40)))
            addView(Space(context), LinearLayout.LayoutParams(MATCH_PARENT, 0, 1f))
            addView(bottom, LinearLayout.LayoutParams(MATCH_PARENT, dp(SPEECH_DP + HINTS_DP)))
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
                if (r == null) {
                    start()
                } else {
                    r.pipeline.paused = !r.pipeline.paused
                    if (r.pipeline.paused) tts?.stop()
                }
            }
            UserCommand.EXIT -> {
                // 안내만 끝내고 앱에 남는다(시작 화면으로). 시작 전에는 할 일이 없다. 앱은 시스템 뒤로·홈으로 나간다(사용자 요청 2026-10-30)
                if (run == null) return
                haptics.exit()
                stop()
                endedByUser = true
            }
        }
        render()
    }

    private fun start() {
        notice = null
        endedByUser = false
        speechPolicy.reset()
        lastSpeechStatus = null
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
            RunSession(this, config, hrtf, File(runs, stamp), feeder::nowNs)
        } catch (e: Exception) {
            Log.e(TAG, "run start failed", e) // 오디오 출력을 열지 못함(A1 FA3)
            stop()
            haptics.on(AlertKind.UNKNOWN)
            notice = UserScreen.Notice.startFailed("소리 출력을 시작할 수 없습니다")
            return
        }
        thread(name = "RunsTrim") { RunSession.trimRuns(runs, config.record.maxRuns) }
        run = r.also {
            it.pipeline.speechActive = tts?.isDucked == true
            speechOut?.pipeline = it.pipeline
            speechOut?.logger = it.logger
            // 상황 말(M65): 느린 경로 → UI 스레드 → 말 내보내기. 내보내기가 없으면(TTS 닫힘) 되돌린다
            val p = it.pipeline
            p.onSpeech = { said ->
                ui.post {
                    val so = speechOut
                    if (so != null && so.pipeline === p) said.forEach(so::situation)
                    else { said.forEach { u -> p.unsay(u.key) }; p.speechLevel.set(0) }
                }
            }
            it.pipeline.onAlert = { kind ->
                // 오디오 스레드에서 불린다: 진동(바인더 호출)·로그는 UI 스레드로 넘긴다(A1 CO5)
                RunTap.current?.alert(kind)
                ui.post {
                    haptics.on(kind)
                    Log.i(TAG, "alert $kind")
                    render()
                }
            }
            it.pipeline.requestAlert(AlertKind.START)
            feeder.pipeline = it.pipeline
            feeder.surface = it.surface
            feeder.signals = it.signals
        }
        RunTap.current?.run(r.pipeline)
        prefs.edit().putBoolean(PREF_NOTICE_READ, true).apply()
        glView.onResume()
        ui.removeCallbacks(uiTick)
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
        RunTap.current?.run(null)
        tts?.stop()
        speaking = null
        speechOut?.pipeline = null
        speechOut?.logger = null
        ui.removeCallbacks(uiTick)
        feeder.pipeline = null
        feeder.surface = null
        feeder.signals = null
        run?.close()
        run = null
        focus.abandon()
        glView.onPause()
        ar.pause()
    }

    override fun onPause() {
        foreground = false
        ui.removeCallbacks(uiTick)
        super.onPause()
        speechOut = null
        tts?.close()
        tts = null
        speaking = null
        // 앱을 떠나면(화면 잠금·전화) 안내를 멈춘다. 돌아오면 다시 두 번 탭으로 시작. 설정 화면은 안내 중이 아닐 때만 열린다
        // VERIFY: 화면이 꺼지는 중(onPause)에도 진동이 전달되는지(Android 13+ 백그라운드 진동 제한) 기기에서 확인
        if (run != null) stopWithNotice("화면을 떠나 안내를 멈췄습니다", haptics::exit) else stop()
        render()
        RunTap.current?.paused()
    }

    override fun onDestroy() {
        speechOut = null
        tts?.close()
        tts = null
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
        val speechStatus = when {
            r == null && notice != null -> if (notice?.title == "시작하지 못했어요")
                StatusSpeechPolicy.Status.START_FAILED else StatusSpeechPolicy.Status.STOPPED
            r == null -> if (endedByUser) StatusSpeechPolicy.Status.ENDED else StatusSpeechPolicy.Status.IDLE
            r.pipeline.paused || g?.state == GuidanceState.PAUSED -> StatusSpeechPolicy.Status.PAUSED
            g == null || feeder.nowNs() - g.tBlockNs !in 0L..(config.policy.maxInfoAgeMs * 1_000_000.0).toLong() ||
                g.state == GuidanceState.UNKNOWN -> StatusSpeechPolicy.Status.PREPARING
            r.pipeline.snapshot.get()?.mapHealth == MapHealth.DEGRADED -> StatusSpeechPolicy.Status.FLOOR_CHECK
            else -> StatusSpeechPolicy.Status.GUIDING
        }
        announceStatus(speechStatus)
        val s = UserScreen.of(
            running = r != null,
            notice = notice,
            paused = r?.pipeline?.paused == true,
            state = g?.state,
            mapHealth = r?.pipeline?.snapshot?.get()?.mapHealth,
            cmd = cmd,
            speech = speaking?.second,
            heightLowM = config.sonify.heightLowM,
            heightHighM = config.sonify.heightHighM,
        )
        if (s.spoken != spokenLine) {
            spokenLine = s.spoken
            val hint = if (r == null) "두 번 탭하여 시작" else "두 번 탭하여 일시정지 또는 재개, 길게 눌러 안내 종료"
            // 처음 시작하기 전에는 ARCore 고지(TR7)를 함께 읽는다. 기기 설정 덮어쓰기는 늘 읽는다(TR4)
            val read = listOfNotNull(
                overrideNotice,
                UserTexts.ARCORE_NOTICE.takeIf { r == null && !prefs.getBoolean(PREF_NOTICE_READ, false) },
            ).joinToString("") { "$it. " }
            val voiceNotice = ttsFailure?.let { "$it. " }.orEmpty()
            root.contentDescription = "HEARSPACE, ${s.spoken}. $voiceNotice$read$hint"
        }
        if (s != shown) show(s)
    }

    /** TalkBack이 터치 탐색을 쓰면 자동 상태 낭독은 TalkBack에 맡긴다. 설명/액션은 보존한다. */
    private fun announceStatus(status: StatusSpeechPolicy.Status) {
        if (!foreground) return
        val reader = a11y.isTouchExplorationEnabled
        if (reader != screenReaderActive) {
            screenReaderActive = reader
            speechOut?.cancelStatus()
            speechPolicy.reset()
            lastSpeechStatus = null
        }
        root.accessibilityLiveRegion = if (reader) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
        if (lastSpeechStatus != status) {
            // 첫 IDLE은 시작 문장을 유지한다. 이후 변화는 안정화 대기 중에도 이전 발화를 취소한다.
            if (lastSpeechStatus != null) speechOut?.cancelStatus() // M65: 상태 말만 멈춘다(위험 상황 말은 끊지 않음)
            lastSpeechStatus = status
        }
        val announcement = speechPolicy.update(status, SystemClock.elapsedRealtime()) ?: return
        // 상태 말은 TalkBack이 화면 설명으로 읽으므로 생략한다. 상황 말은 TalkBack을 켜도 말 내보내기가 낸다(M65)
        if (!reader && tts?.isAvailable == true) {
            speechOut?.status(announcement.text, safety = announcement.urgent)
        }
    }

    private fun show(s: UserScreen) {
        shown = s
        RunTap.current?.screen(s)
        // 칸은 늘 자리를 지키고 글만 바꾼다(M72): 숨길 때도 GONE을 쓰지 않는다
        titleText.text = s.title.orEmpty()
        titleText.setTextColor(getColor(if (s.titleTone == UserScreen.Tone.CAUTION) R.color.run_caution else R.color.run_text))
        titleText.setCompoundDrawablesRelativeWithIntrinsicBounds(if (s.titleTone == UserScreen.Tone.GUIDE && s.title != null) guideDot else null, null, null, null)
        subText.text = listOfNotNull(s.sub, ttsFailure).joinToString("\n")
        guide.visual = s.visual
        val start = s.idle && s.visual == UserScreen.Visual.Logo
        labelText.text = s.label.orEmpty()
        labelText.textSize = if (start) 28f else 34f
        labelText.setTextColor(getColor(when {
            s.label == null || s.visual !is UserScreen.Visual.Arrow -> R.color.run_text
            s.labelTone == UserScreen.Tone.STOP -> R.color.run_stop
            else -> R.color.run_caution
        }))
        val d = s.detail
        detailDir.text = d?.direction.orEmpty()
        detailDist.text = d?.distance.orEmpty()
        detailHeight.text = d?.height.orEmpty()
        detailSep1.visibility = if (d != null) View.VISIBLE else View.INVISIBLE
        detailSep2.visibility = if (d?.height != null) View.VISIBLE else View.INVISIBLE
        speechIcon.visibility = if (s.speech != null) View.VISIBLE else View.INVISIBLE
        speechText.text = s.speech.orEmpty()
        // "두 번 탭 · 일시정지": 동작은 흰 굵은 글, 뜻은 회색
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
        noticeText.visibility = if (start) View.VISIBLE else View.INVISIBLE
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
            if (!foreground) return
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
        /** 작동 화면 칸 높이(dp, M72 설계 표 3): 기호 240(시안의 가장 큰 그림 화살표 230이 드는 높이), 말 줄 88, 조작 안내 88. */
        private const val GUIDE_DP = 240
        private const val SPEECH_DP = 88
        private const val HINTS_DP = 88
        private const val PREFS = "user_mode"
        private const val PREF_NOTICE_READ = "arcoreNoticeRead"
        /** E1 인자(되풀이 수)와 안내 시작 뒤 측정까지 기다리는 시간(ARCore 추적이 자리 잡게, 측정 도구 값). */
        const val EXTRA_TTS_BENCH = "ttsBench"
        private const val BENCH_WARMUP_MS = 3000L
    }
}
