package hearspace.app.ui.dev

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import hearspace.app.AppConfig
import hearspace.app.loadHrtf
import hearspace.app.runtime.AndroidTts
import hearspace.app.runtime.AudioFocus
import hearspace.app.runtime.BinauralOutput
import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.SpeechCollisionTest
import hearspace.core.audio.VirtualScenario
import hearspace.core.audio.VirtualScenarioConfig
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.Config
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.SoundKind
import hearspace.core.types.SonifyMapping
import hearspace.core.types.SonifyMode
import java.util.Locale
import java.util.concurrent.atomic.AtomicReference

class VirtualScenarioTestActivity : Activity() {
    private lateinit var config: Config
    private lateinit var testConfig: VirtualScenarioConfig
    private lateinit var collisionConfig: SpeechCollisionTest.Config
    private var collisionTest: SpeechCollisionTest? = null
    private var protectUrgent = true
    private lateinit var renderer: BinauralRenderer
    private lateinit var focus: AudioFocus
    private var output: BinauralOutput? = null
    private var tts: AndroidTts? = null
    private val main = Handler(Looper.getMainLooper())
    private val latest = AtomicReference<VirtualScenario.Frame?>(null)
    @Volatile private var speaking = false
    @Volatile private var audioEnabled = true
    @Volatile private var duckEnabled = true
    private var scenario: VirtualScenario? = null
    private var startedNs = 0L
    private var lastAttemptNs = 0L
    private var ttsEnabled = true
    private lateinit var status: TextView
    private lateinit var events: TextView
    private lateinit var target: Spinner
    private val lines = ArrayList<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val base = AppConfig.get(this)
        testConfig = assets.open("config/virtual-scenario.json").bufferedReader().use {
            VirtualScenarioConfig.parse(it.readText())
        }
        collisionConfig = assets.open("config/speech-collision-test.json").bufferedReader().use {
            SpeechCollisionTest.Config.parse(it.readText())
        }
        // 이 화면에서만 연속음·높이 매핑을 사용한다. 저장된 설정은 바꾸지 않는다.
        config = base.copy(sonify = base.sonify.copy(
            mode = SonifyMode.RISK_CONTINUOUS,
            mapping = SonifyMapping.DISTANCE_HEIGHT,
            onceS = maxOf(base.sonify.onceS, testConfig.comparisonHoldS, collisionConfig.timeoutMs / 1000f),
        ))
        focus = AudioFocus(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            val p = (20 * resources.displayMetrics.density).toInt()
            setPadding(p, p, p, p)
        }
        setContentView(ScrollView(this).apply { addView(root) })
        root.addView(TextView(this).apply {
            text = "가상 장애물 TTS 테스트\n카메라 없이 ${testConfig.durationS.toInt()}초 동안 재생합니다.\n" +
                "비교를 위해 정지한 장애물의 소리도 계속 재생합니다. 대상은 시작할 때 선택됩니다."
            textSize = 22f
        })
        target = Spinner(this).apply {
            adapter = ArrayAdapter(this@VirtualScenarioTestActivity,
                android.R.layout.simple_spinner_dropdown_item,
                listOf("정면 몸통 장애물", "정면 머리 높이 장애물", "경로 밖 장애물"))
        }
        root.addView(target)
        fun option(label: String, checked: Boolean, change: (Boolean) -> Unit) {
            root.addView(CheckBox(this).apply {
                text = label
                isChecked = checked
                setOnCheckedChangeListener { _, value -> change(value) }
            })
        }
        option("비언어음 재생", true) { audioEnabled = it }
        option("TTS 재생", true) {
            ttsEnabled = it
            if (!it) {
                if (collisionTest != null) stopScenario() else tts?.stop()
            }
        }
        option("TTS 중 비언어음 감쇠", true) { duckEnabled = it }
        option("긴급 발화 보호 (충돌 시험 시작 시 적용)", true) { protectUrgent = it }
        fun button(label: String, action: () -> Unit) {
            root.addView(Button(this).apply {
                text = label
                setAllCaps(false)
                setOnClickListener { action() }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }
        button("접근 테스트: ${testConfig.farM}m → ${testConfig.warnM}m → ${testConfig.stopM}m") { startScenario(false) }
        button("반복 방지 테스트: ${testConfig.warnM}m 부근 왕복") { startScenario(true) }
        button("긴급 끼어들기 테스트") { startCollision(SpeechCollisionTest.Mode.INTERRUPT) }
        button("상태 안내 충돌 테스트") { startCollision(SpeechCollisionTest.Mode.STATUS_COLLISION) }
        button("정지") { stopScenario() }
        status = TextView(this).apply { textSize = 18f; text = "대기 중" }
        events = TextView(this).apply { textSize = 15f }
        root.addView(status)
        root.addView(events)
    }

    override fun onResume() {
        super.onResume()
        renderer = BinauralRenderer(config, loadHrtf(this))
        focus.request()
        output = runCatching {
            BinauralOutput(config.audio, ::render).also { it.start() }
        }.onFailure { log("오디오 출력 오류: ${it.message}") }.getOrNull()
        tts = AndroidTts(this, config,
            onDuckingChanged = { speaking = it },
            onEvent = { event ->
                log("${event.state.label}: ${event.text} ${event.detail.orEmpty()}")
                collisionTest?.onEvent(event.id, event.text, when (event.state) {
                    AndroidTts.State.STARTED -> SpeechCollisionTest.Event.STARTED
                    AndroidTts.State.COMPLETED -> SpeechCollisionTest.Event.COMPLETED
                    AndroidTts.State.INTERRUPTED -> SpeechCollisionTest.Event.INTERRUPTED
                    AndroidTts.State.ERROR -> SpeechCollisionTest.Event.ERROR
                }, SystemClock.elapsedRealtime())
                if (event.state == AndroidTts.State.ERROR ||
                    event.state == AndroidTts.State.INTERRUPTED) {
                    scenario?.onFailed(event.text)
                }
            },
        )
    }

    private fun startScenario(repeat: Boolean) {
        stopScenario()
        startedNs = SystemClock.elapsedRealtimeNanos()
        lines.clear()
        scenario = VirtualScenario(testConfig, repeat, VirtualScenario.Target.values()[target.selectedItemPosition])
        lastAttemptNs = 0L
        log("시작 / TTS 음량 ${config.audio.ttsVolume}, 감쇠 이득 ${config.audio.ttsObstacleGain}")
        main.post(tick)
    }

    private fun startCollision(mode: SpeechCollisionTest.Mode) {
        if (!ttsEnabled) {
            log("TTS 재생을 켠 다음 시험해 주세요")
            return
        }
        startScenario(false)
        // 충돌 시험은 정면 몸통의 비교음으로 고정한다.
        target.setSelection(0)
        scenario = VirtualScenario(testConfig, false, VirtualScenario.Target.BODY)
        collisionTest = SpeechCollisionTest(collisionConfig, mode,
            SystemClock.elapsedRealtime(), protectUrgent)
        log("${mode.name} / 긴급 보호 ${if (protectUrgent) "ON" else "OFF"}")
    }

    private fun collisionStep(test: SpeechCollisionTest, nowNs: Long) {
        val nowMs = SystemClock.elapsedRealtime()
        test.next(nowMs)?.let { attempt ->
            val line = attempt.line
            if (!attempt.allowed) {
                log("정책 차단: ${line.text} / 긴급 경고 유지")
                test.onAdmission(attempt, false)
            } else {
                log("발화 요청: ${line.role} / ${line.text}")
                val evidenceNs = if (line.urgent) {
                    scenario?.sample(testConfig.stopReachS, nowNs)?.also { latest.set(it) }?.observedNs
                } else null
                val accepted = tts?.speak(
                    text = line.text,
                    urgent = line.urgent,
                    // 첫 시험 문장은 초기화 대기를 허용한다. 긴급 요청은 최신 가상 관측을 쓴다.
                    evidenceNs = evidenceNs,
                    replaceCurrent = line.replaceCurrent,
                ) == true
                test.onAdmission(attempt, accepted)
                if (!accepted) log("엔진 요청 미접수")
            }
        }
        test.result?.let { result ->
            log("${if (result.passed) "PASS" else "FAIL"}: ${result.detail}")
            stopScenario()
            status.text = "${if (result.passed) "PASS" else "FAIL"}\n${result.detail}"
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            val test = scenario ?: return
            val now = SystemClock.elapsedRealtimeNanos()
            val elapsed = (now - startedNs) / 1e9f
            val collision = collisionTest
            if (collision == null && elapsed >= test.durationS) {
                stopScenario()
                return
            }
            val sceneTimeS = if (collision != null) {
                if (collision.urgentIssued) testConfig.stopReachS else testConfig.warnReachS
            } else elapsed
            val frame = test.sample(sceneTimeS, now)
            latest.set(frame)
            val cue = if (collision == null) test.nextCue(frame, now) else null
            if (ttsEnabled && cue != null && now - lastAttemptNs >= testConfig.retryMs * 1_000_000L) {
                lastAttemptNs = now
                log("발화 요청: ${cue.text}")
                // 가상 관측 생성 시각을 그대로 전달한다. 초기화 중 만료되면 다음 관측으로 재시도한다.
                if (tts?.speak(cue.text, urgent = cue.urgent, evidenceNs = frame.observedNs) == true) {
                    test.onAccepted(cue)
                } else log("요청 미접수 / 다음 관측에서 재시도")
            }
            val gain = if (speaking && duckEnabled) config.audio.ttsObstacleGain else 1f
            status.text = String.format(Locale.KOREA,
                "%.1f초 / %.2fm / %s\nTTS %s / 비언어음 이득 %.2f",
                elapsed, frame.distanceM, if (frame.inCorridor) "경로 안" else "경로 밖",
                if (speaking) "읽는 중" else "대기", if (audioEnabled) gain else 0f)
            if (output?.failed == true) {
                log("오디오 출력 중단됨: 화면에 다시 들어와 재시도하세요")
                stopScenario()
                return
            }
            if (collision != null) {
                collisionStep(collision, now)
                if (scenario == null) return
            }
            main.postDelayed(this, testConfig.tickMs)
        }
    }

    /** 오디오 스레드. UI가 멈춰 오래된 가상 관측이 남으면 소리를 버린다. */
    private fun render(buf: FloatArray) {
        val frame = latest.get()
        val ageMs = frame?.let {
            (SystemClock.elapsedRealtimeNanos() - it.observedNs) / 1e6f
        } ?: Float.POSITIVE_INFINITY
        val valid = frame != null && ageMs >= 0f && ageMs <= minOf(testConfig.maxFrameAgeMs, config.policy.maxInfoAgeMs)
        val commands = if (valid && frame != null && frame.inCorridor && audioEnabled) {
            listOf(AudioCmd(
                obstacleId = 1,
                azimuthDeg = 0f,
                distanceM = frame.distanceM,
                band = if (frame.distanceM <= testConfig.stopM) Band.STOP else Band.WARN,
                sound = if (frame.head) SoundKind.HEAD_TONE else SoundKind.FLOOR_PULSE,
                infoAgeMs = ageMs,
                inCorridor = true,
                heightDeltaM = if (frame.head) testConfig.headHeightDeltaM else testConfig.bodyHeightDeltaM,
            ))
        } else emptyList()
        renderer.renderWithSpeech(
            GuidanceOutput(System.nanoTime(),
                if (valid) GuidanceState.NORMAL else GuidanceState.UNKNOWN,
                commands, 0f, null),
            buf, speechActive = speaking && duckEnabled,
        )
        if (!audioEnabled || !valid) buf.fill(0f)
    }

    private fun stopScenario() {
        collisionTest?.let {
            if (it.result == null) { it.cancel(); log("시험 중단 / PASS 판정 없음") }
        }
        collisionTest = null
        scenario = null
        main.removeCallbacks(tick)
        latest.set(null)
        tts?.stop()
        speaking = false
        status.text = "정지 / 다시 시작하면 처음부터 재생합니다."
    }

    private fun log(text: String) {
        val elapsed = if (startedNs == 0L) 0f
            else (SystemClock.elapsedRealtimeNanos() - startedNs) / 1e9f
        lines.add(String.format(Locale.KOREA, "%.1fs  %s", elapsed, text))
        if (lines.size > 32) lines.removeAt(0)
        events.text = lines.joinToString("\n")
    }

    override fun onPause() {
        stopScenario()
        tts?.close()
        tts = null
        output?.close()
        output = null
        focus.abandon()
        super.onPause()
    }
}

