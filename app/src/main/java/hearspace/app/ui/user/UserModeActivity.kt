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
import android.text.TextUtils
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
import hearspace.app.runtime.AndroidTts
import hearspace.app.runtime.AudioFocus
import hearspace.app.runtime.Haptics
import hearspace.app.runtime.RunSession
import hearspace.app.runtime.TtsBench
import hearspace.app.runtime.ThermalMonitor
import hearspace.core.audio.Hrtf
import hearspace.core.audio.ObjectSpeechConfig
import hearspace.core.audio.ObjectSpeechFrame
import hearspace.core.audio.ObjectSpeechPolicy
import hearspace.core.situation.LiveSituationSpeech
import hearspace.core.situation.SpeechPriority
import hearspace.core.situation.SpeechRequest
import hearspace.core.situation.chooseSpeech
import hearspace.core.situation.obstacleSpeechLine
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
    private lateinit var spatialDetailText: TextView
    private lateinit var speechPanel: LinearLayout
    private lateinit var speechCaptionText: TextView
    private lateinit var speechWave: SpeechWaveView
    private lateinit var hintText: TextView
    private lateinit var noticeText: TextView
    private lateinit var settingsButton: ImageButton

    private val ui = Handler(Looper.getMainLooper())
    private var run: RunSession? = null
    private var tts: AndroidTts? = null
    private lateinit var objectSpeechConfig: ObjectSpeechConfig
    private lateinit var objectSpeech: ObjectSpeechPolicy
    private lateinit var spatialConfig: SpatialUiConfig
    private lateinit var spatialFeedback: SpatialFeedback
    private val speechVisual = SpeechVisualState()
    private var announceLaunch = false
    private val speechPolicy = StatusSpeechPolicy()
    private lateinit var walkingSpeech: LiveSituationSpeech
    private var pendingStatus: StatusSpeechPolicy.Announcement? = null
    private var activeSpeech: SpokenLine? = null
    private var speechSerial = 0L
    private data class SpokenLine(
        val key: String,
        val text: String,
        val priority: SpeechPriority,
        val evidenceNs: Long? = null,
        val walking: SpeechRequest? = null,
        val epochNs: Long? = null,
        val obstacle: ObjectSpeechPolicy.Request? = null,
        val status: Boolean = false,
    )

    private var lastSpeechStatus: StatusSpeechPolicy.Status? = null
    private var foreground = false
    private var endedByUser = false
    private var ttsFailure: String? = null
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
            AppConfig.get(this, deviceOverride = false)
        }
        overrideNotice = AppConfig.overrideNotice(this)
        objectSpeechConfig = assets.open("config/object-speech.json").bufferedReader().use {
            ObjectSpeechConfig.parse(it.readText())
        }
        if (objectSpeechConfig.enabled && objectSpeechConfig.useSemanticLabels) {
            config = config.copy(vision = config.vision.copy(semantics = true))
        }
        objectSpeech = ObjectSpeechPolicy(objectSpeechConfig,
            (config.policy.maxInfoAgeMs * 1_000_000.0).toLong())
        walkingSpeech = LiveSituationSpeech(config.situation,
            (config.policy.maxInfoAgeMs * 1_000_000.0).toLong())
        spatialConfig = assets.open("config/spatial-ui.json").bufferedReader().use {
            SpatialUiConfig.parse(it.readText())
        }
        spatialFeedback = SpatialFeedback(spatialConfig,
            (config.policy.maxInfoAgeMs * 1_000_000.0).toLong(), objectSpeechConfig.minLabelFraction)
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
        objectSpeech.reset()
        walkingSpeech.reset()
        pendingStatus = null
        activeSpeech = null
        spatialFeedback.reset()
        speechVisual.clear()
        ttsFailure = null
        speechPolicy.reset()
        lastSpeechStatus = null
        endedByUser = false
        screenReaderActive = a11y.isTouchExplorationEnabled
        shown = null
        spokenLine = null
        tts = AndroidTts(this, config, onDuckingChanged = { active ->
            run?.pipeline?.speechActive = active
        }, onEvent = { event ->
            if (foreground) {
                if (event.state == AndroidTts.State.STARTED) speechVisual.started(event.id, event.text)
                else speechVisual.finished(event.id)
                renderSpeech()
            }
            event.requestKey?.let { key ->
                val nowNs = feeder.nowNs()
                val active = activeSpeech?.takeIf { it.key == key }
                if (active != null && event.state != AndroidTts.State.STARTED) {
                    activeSpeech = null
                    active.walking?.let {
                        walkingSpeech.finished(it, nowNs, event.state == AndroidTts.State.COMPLETED,
                            event.state == AndroidTts.State.ERROR)
                    }
                }
                when (event.state) {
                    AndroidTts.State.STARTED -> objectSpeech.onStarted(key, nowNs)
                    AndroidTts.State.COMPLETED -> objectSpeech.onFinished(key, nowNs, completed = true)
                    AndroidTts.State.ERROR, AndroidTts.State.INTERRUPTED ->
                        objectSpeech.onFinished(key, nowNs, completed = false)
                }
            }
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
        if (announceLaunch) {
            announceLaunch = false
            if (!screenReaderActive) tts?.speak("앱이 시작했습니다", requestKey = "status-launch")
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
        guide = GuideView(this).apply {
            spatialConfig = this@UserModeActivity.spatialConfig
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        labelText = TextView(this).apply {
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.1f)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        spatialDetailText = TextView(this).apply {
            textSize = 26f
            setTextColor(white)
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(4), dp(12), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            visibility = View.GONE
        }
        speechWave = SpeechWaveView(this).apply {
            config = spatialConfig
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val speechTitle = TextView(this).apply {
            text = "음성 안내 중"
            textSize = 18f
            setTextColor(white)
            gravity = Gravity.CENTER_VERTICAL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        speechCaptionText = TextView(this).apply {
            textSize = 18f
            setTextColor(white)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        speechPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(18), dp(8), dp(18), dp(4))
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
            visibility = View.GONE
            addView(LinearLayout(context).apply {
                gravity = Gravity.CENTER
                addView(speechWave, LinearLayout.LayoutParams(dp(40), dp(28)).apply { rightMargin = dp(10) })
                addView(speechTitle, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT))
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(speechCaptionText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { topMargin = dp(4) })
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
            addView(spatialDetailText, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(speechPanel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
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
        objectSpeech.reset()
        walkingSpeech.reset()
        pendingStatus = null
        activeSpeech = null
        spatialFeedback.reset()
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
            RunSession(this, config, hrtf, File(runs, stamp), feeder::nowNs, objectSpeechConfig)
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
            // 상태·물체·보행상황은 announce()의 같은 선택기로 넘긴다.
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
            feeder.surface = it.surface
        }
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
        tts?.stop()
        objectSpeech.reset()
        walkingSpeech.reset()
        pendingStatus = null
        activeSpeech = null
        spatialFeedback.reset()
        speechVisual.clear()
        renderSpeech()
        ui.removeCallbacks(uiTick)
        feeder.pipeline = null
        feeder.surface = null
        run?.close()
        run = null
        focus.abandon()
        glView.onPause()
        ar.pause()
    }

    override fun onPause() {
        foreground = false
        speechVisual.clear()
        renderSpeech()
        ui.removeCallbacks(uiTick)
        super.onPause()
        tts?.close()
        tts = null
        // 앱을 떠나면(화면 잠금·전화) 안내를 멈춘다. 돌아오면 다시 두 번 탭으로 시작. 설정 화면은 안내 중이 아닐 때만 열린다
        // VERIFY: 화면이 꺼지는 중(onPause)에도 진동이 전달되는지(Android 13+ 백그라운드 진동 제한) 기기에서 확인
        if (run != null) stopWithNotice("화면을 떠나 안내를 멈췄습니다", haptics::exit) else stop()
        render()
    }

    override fun onDestroy() {
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
        val nowNs = feeder.nowNs()
        // 같은 블록에서 사용한 출력과 스냅샷을 함께 읽는다.
        val frame = r?.pipeline?.lastObjectSpeech?.get()
        val g = frame?.output?.takeIf {
            nowNs - it.tBlockNs in 0L..(config.policy.maxInfoAgeMs * 1_000_000.0).toLong()
        }
        val cue = if (foreground && r != null && !r.pipeline.paused) spatialFeedback.update(frame, nowNs)
            else { spatialFeedback.reset(); null }
        val speechStatus = when {
            r == null && notice != null -> if (notice?.title == "시작하지 못했어요")
                StatusSpeechPolicy.Status.START_FAILED else StatusSpeechPolicy.Status.STOPPED
            r == null -> if (endedByUser) StatusSpeechPolicy.Status.ENDED else StatusSpeechPolicy.Status.IDLE
            r.pipeline.paused || g?.state == GuidanceState.PAUSED -> StatusSpeechPolicy.Status.PAUSED
            g == null || feeder.nowNs() - g.tBlockNs !in 0L..(config.policy.maxInfoAgeMs * 1_000_000.0).toLong() ||
                g.state == GuidanceState.UNKNOWN -> StatusSpeechPolicy.Status.PREPARING
            frame?.snapshot?.mapHealth == MapHealth.DEGRADED -> StatusSpeechPolicy.Status.FLOOR_CHECK
            else -> StatusSpeechPolicy.Status.GUIDING
        }
        announce(speechStatus, frame, nowNs)
        val s = UserScreen.of(
            running = r != null,
            notice = notice,
            paused = r?.pipeline?.paused == true,
            state = g?.state,
            mapHealth = frame?.snapshot?.mapHealth,
            band = cue?.band,
            azimuthDeg = cue?.azimuthDeg,
            spatial = cue?.takeIf { spatialConfig.enabled },
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
        renderSpeech()
    }

    /** Select one line across status, obstacle and walking situation candidates. */
    private fun announce(status: StatusSpeechPolicy.Status, frame: ObjectSpeechFrame?, nowNs: Long) {
        if (!foreground || (hearspace.app.BuildConfig.DEV_TOOLS &&
            intent.getIntExtra(EXTRA_TTS_BENCH, 0) > 0 && !benchDone)) return
        val reader = a11y.isTouchExplorationEnabled
        if (reader != screenReaderActive) {
            screenReaderActive = reader
            if (activeSpeech?.status == true) tts?.stop()
            speechPolicy.reset()
            objectSpeech.reset()
            walkingSpeech.reset()
            pendingStatus = null
            lastSpeechStatus = null
        }
        root.accessibilityLiveRegion = if (reader) View.ACCESSIBILITY_LIVE_REGION_POLITE else View.ACCESSIBILITY_LIVE_REGION_NONE
        val player = tts ?: return
        if (!player.isAvailable) return

        val guiding = status == StatusSpeechPolicy.Status.GUIDING
        if (lastSpeechStatus != status) {
            if (lastSpeechStatus != null && (!guiding || activeSpeech?.status == true)) player.stop()
            pendingStatus = null
            lastSpeechStatus = status
        }
        val announcement = speechPolicy.update(status, SystemClock.elapsedRealtime())
        if (reader) pendingStatus = null else announcement?.let { pendingStatus = it }

        val situations = run?.pipeline?.lastSituationSpeech?.get().takeIf { guiding }
        val currentWalking = walkingSpeech.observe(situations, frame?.epochNs, nowNs)
        val speaking = activeSpeech
        speaking?.walking?.let { active ->
            if (speaking.epochNs != frame?.epochNs ||
                currentWalking.none { it.key == active.key && it.level == active.level }) player.stop()
        }

        val objectDecision = objectSpeech.update(frame.takeIf { guiding }, nowNs)
        if (objectDecision.cancelKey != null && player.currentRequestKey == objectDecision.cancelKey) player.stop()

        val candidates = buildList {
            pendingStatus?.let {
                add(SpokenLine("status-${++speechSerial}", it.text,
                    if (it.urgent) SpeechPriority.UNAVAILABLE else SpeechPriority.STATUS, status = true))
            }
            objectDecision.request?.let { request ->
                val line = obstacleSpeechLine(request, currentWalking)
                add(SpokenLine(request.key, line.text,
                    if (request.urgent) SpeechPriority.IMMEDIATE else SpeechPriority.OBSTACLE,
                    line.evidenceNs, obstacle = request))
            }
            walkingSpeech.next(currentWalking, objectSpeechConfig.enabled, nowNs)?.let { request ->
                val priority = when (request.priority) {
                    1 -> SpeechPriority.IMMEDIATE
                    2 -> SpeechPriority.ROUTE
                    else -> SpeechPriority.INFORMATION
                }
                val text = if (request.priority == 1) "멈추세요. ${request.text}." else request.text
                add(SpokenLine("walking-${++speechSerial}", text, priority, request.evidenceNs,
                    walking = request, epochNs = frame?.epochNs))
            }
        }
        val active = if (player.hasRequest) activeSpeech?.priority ?: SpeechPriority.STATUS else null
        val next = chooseSpeech(active, candidates) { it.priority } ?: return
        if (next.status) pendingStatus = null
        val accepted = player.speak(next.text, urgent = next.priority <= SpeechPriority.IMMEDIATE,
            evidenceNs = next.evidenceNs, replaceCurrent = true, requestKey = next.key)
        if (accepted) {
            activeSpeech = next
            next.obstacle?.let(objectSpeech::onAccepted)
        } else {
            next.obstacle?.let { objectSpeech.onRejected(it, nowNs) }
            next.walking?.let { walkingSpeech.finished(it, nowNs, completed = false, failed = true) }
        }
    }

    private fun show(s: UserScreen) {
        shown = s
        titleText.visibility = if (s.title == null) View.GONE else View.VISIBLE
        titleText.text = s.title
        titleText.setTextColor(getColor(if (s.titleTone == UserScreen.Tone.CAUTION) R.color.run_caution else R.color.run_text))
        titleText.setCompoundDrawablesRelativeWithIntrinsicBounds(if (s.titleTone == UserScreen.Tone.GUIDE) guideDot else null, null, null, null)
        val detail = listOfNotNull(s.sub, ttsFailure).joinToString("\n")
        subText.visibility = if (detail.isEmpty()) View.GONE else View.VISIBLE
        subText.text = detail
        guide.visual = s.visual
        val cue = (s.visual as? UserScreen.Visual.Arrow)?.spatial
        spatialDetailText.visibility = if (cue == null) View.GONE else View.VISIBLE
        spatialDetailText.text = cue?.detailText.orEmpty()
        // 그림 칸: 시안의 가장 큰 그림(화살표 230 dp)이 들어가는 높이, 시작 전 로고(150 dp)는 글자와 가깝게
        val guideHeight = dp(when {
            s.visual == UserScreen.Visual.Logo -> LOGO_DP
            spatialConfig.enabled -> (resources.configuration.screenHeightDp * 0.30f /
                resources.configuration.fontScale.coerceAtLeast(1f)).toInt().coerceIn(140, GUIDE_DP)
            else -> GUIDE_DP
        })
        if (guide.layoutParams.height != guideHeight) guide.layoutParams = guide.layoutParams.apply { height = guideHeight }
        labelText.visibility = if (s.label == null) View.GONE else View.VISIBLE
        labelText.text = s.label
        labelText.textSize = when (s.labelTone) {
            UserScreen.Tone.STOP -> 36f
            UserScreen.Tone.CAUTION -> 30f
            else -> 34f
        }
        labelText.setTextColor(if (cue != null) spatialColor(this, cue.tint) else getColor(when {
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

    private fun renderSpeech() {
        val line = speechVisual.line?.takeIf { foreground && spatialConfig.enabled && !screenReaderActive }
        speechPanel.visibility = if (line == null) View.GONE else View.VISIBLE
        speechCaptionText.text = line?.text.orEmpty()
        speechWave.active = line != null
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
        private const val GUIDE_DP = 240
        private const val LOGO_DP = 176
        private const val PREFS = "user_mode"
        private const val PREF_NOTICE_READ = "arcoreNoticeRead"
        /** E1 인자(되풀이 수)와 안내 시작 뒤 측정까지 기다리는 시간(ARCore 추적이 자리 잡게, 측정 도구 값). */
        const val EXTRA_TTS_BENCH = "ttsBench"
        private const val BENCH_WARMUP_MS = 3000L
    }
}

