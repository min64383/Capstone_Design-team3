package hearspace.app.runtime

import android.content.Context
import android.media.AudioAttributes
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import hearspace.app.TAG
import hearspace.core.types.Config
import java.util.Locale

/** UI 스레드 전용. 콜백도 UI로 전달하며, 이전 발화의 늦은 콜백은 ID로 버린다. */
class AndroidTts(
    context: Context,
    config: Config,
    private val onDuckingChanged: (Boolean) -> Unit,
    private val onEvent: (Event) -> Unit = {},
    private val safety: TtsSafetyConfig = TtsSafetyConfig(),
    /** 줄마다 엔진이 첫 소리 조각을 내놓은 때(UI 스레드, M65 시작 지연 측정). onStart는 엔진에 따라 합성 시작 때 불린다. */
    private val onFirstAudio: (String) -> Unit = {},
) : AutoCloseable {
    enum class State(val label: String) {
        STARTED("읽는 중"), COMPLETED("읽기 완료"), INTERRUPTED("중단"), ERROR("오류")
    }

    data class Event(val id: String, val text: String, val state: State, val detail: String?)

    private enum class EngineState { LOADING, READY, FAILED, CLOSED }
    private class Line(
        val id: String, val text: String, val urgent: Boolean,
        val evidenceNs: Long?, val requestedMs: Long,
    ) {
        var submitted = false
        var started = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val maxAgeNs = (config.policy.maxInfoAgeMs * 1_000_000.0).toLong()
    private val voiceVolume = config.audio.ttsVolume
    private val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, voiceVolume) }
    private var engine: TextToSpeech? = null
    private var engineState = EngineState.LOADING
    private var current: Line? = null
    private var serial = 0L
    private var pendingTimeout: Runnable? = null
    private var completionTimeout: Runnable? = null
    private val initTimeout = Runnable {
        if (engineState == EngineState.LOADING) failInit("TTS 초기화 시간 초과")
    }

    var isDucked = false
        private set

    /** 지금 줄(받은 뒤 끝나기 전)의 id. [speak]가 참을 돌려준 직후 읽으면 그 줄의 id다(M65 말 내보내기가 이벤트와 맞춘다). */
    val currentId: String? get() = current?.id

    /** 초기화/발화 타임아웃은 구분한다. 일시적 발화 실패 뒤에는 다음 요청을 받을 수 있다. */
    val isAvailable: Boolean get() = engineState == EngineState.LOADING || engineState == EngineState.READY

    init {
        checkMain()
        main.postDelayed(initTimeout, safety.initTimeoutMs)
        try {
            engine = TextToSpeech(context.applicationContext) { status ->
                // 생성자 안의 동기 콜백도 engine 할당 뒤 처리한다.
                main.post { initialized(status) }
            }
        } catch (_: RuntimeException) {
            main.post { if (engineState == EngineState.LOADING) failInit("TTS 엔진 연결 실패") }
        }
    }

    /**
     * evidenceNs는 elapsedRealtimeNanos 기준 관측 시각. 상태 안내에는 생략한다.
     * replaceCurrent는 최신 상태 안내용: 이전 상태를 줄 세우지 않고 교체한다.
     * true는 요청 접수이지 실제 발화 성공이 아니다(최종 결과는 onEvent).
     */
    fun speak(
        text: String, urgent: Boolean = false, evidenceNs: Long? = null,
        replaceCurrent: Boolean = false,
    ): Boolean {
        checkMain()
        if (engineState == EngineState.CLOSED) return false
        val line = Line((++serial).toString(), text, urgent, evidenceNs, SystemClock.elapsedRealtime())
        val problem = when {
            text.isBlank() || text.length > TextToSpeech.getMaxSpeechInputLength() -> "문장 길이 오류"
            engineState == EngineState.FAILED -> "오프라인 한국어 TTS를 사용할 수 없습니다"
            else -> null
        }
        if (problem != null) { emit(line, State.ERROR, problem); return false }
        if (!fresh(line)) return false
        val previous = current
        if (previous != null && !replaceCurrent && (!urgent || previous.urgent)) return false
        current = line
        clearLineTimers()
        if (previous != null) {
            if (previous.submitted) stopEngine()
            duck(false)
            emit(previous, State.INTERRUPTED, "새 안내로 교체")
        }
        // 외부 콜백이 stop/close/speak를 재진입한 경우 새 상태를 덮어쓰지 않는다.
        if (current !== line || engineState == EngineState.CLOSED) return false
        armPending(line)
        return engineState == EngineState.LOADING || submit(line)
    }

    private fun initialized(status: Int) {
        if (engineState != EngineState.LOADING) return
        if (status != TextToSpeech.SUCCESS) { failInit("TTS 초기화 실패 ($status)"); return }
        val tts = engine ?: run { failInit("TTS 엔진이 없습니다"); return }
        try {
            if (tts.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) {
                failInit("한국어 음성 데이터를 설치해 주세요"); return
            }
            // 네트워크 음성으로 조용히 대체하지 않는다. 엔진은 OS에 설치된 제3자 서비스일 수 있다.
            fun offlineKorean(v: Voice) =
                isKorean(v.locale) && !v.isNetworkConnectionRequired &&
                    TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
            // 진단(M65 기기 확인): 기본 엔진과 한국어 음성의 네트워크 필요·설치 여부. 발화 원문은 아니다
            Log.i(TAG, "tts engine ${tts.defaultEngine}, voice ${tts.voice?.let { "${it.name} ${it.locale} net=${it.isNetworkConnectionRequired} features=${it.features}" }}, " +
                "engines ${tts.engines.joinToString { it.name }}, ko voices " +
                tts.voices.orEmpty().filter { isKorean(it.locale) }
                    .joinToString { "${it.name}(net=${it.isNetworkConnectionRequired}, notInstalled=${TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED in it.features.orEmpty()})" })
            if (tts.voice?.let(::offlineKorean) != true) {
                val voice = tts.voices.orEmpty().filter(::offlineKorean).sortedBy { it.name }.firstOrNull()
                if (voice == null || tts.setVoice(voice) != TextToSpeech.SUCCESS) {
                    failInit("오프라인 한국어 음성을 설치하고 선택해 주세요"); return
                }
            }
            if (tts.voice?.let(::offlineKorean) != true) {
                failInit("오프라인 한국어 음성을 확인할 수 없습니다"); return
            }
            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
            if (tts.setSpeechRate(1f) != TextToSpeech.SUCCESS ||
                tts.setPitch(1f) != TextToSpeech.SUCCESS ||
                tts.setAudioAttributes(attributes) != TextToSpeech.SUCCESS ||
                tts.setOnUtteranceProgressListener(listener) != TextToSpeech.SUCCESS) {
                failInit("TTS 출력 설정 실패"); return
            }
            main.removeCallbacks(initTimeout)
            engineState = EngineState.READY
            current?.let { submit(it) }
        } catch (_: RuntimeException) {
            failInit("TTS 설정 중 엔진 연결 실패")
        }
    }

    private fun failInit(detail: String) {
        if (engineState == EngineState.CLOSED || engineState == EngineState.FAILED) return
        engineState = EngineState.FAILED
        Log.w(TAG, "tts init failed: $detail") // 실패 이유만 남긴다(발화 원문은 남기지 않음)
        main.removeCallbacks(initTimeout)
        val failedLine = current
        stopEngine()
        runCatching { engine?.shutdown() }
        engine = null
        if (failedLine != null) finish(failedLine, State.ERROR, detail)
        else {
            clearLineTimers()
            duck(false)
            // 요청이 없어도 사용자 화면에 초기화 실패를 알린다.
            onEvent(Event("engine", "", State.ERROR, detail))
        }
    }

    private fun submit(line: Line): Boolean {
        if (current !== line || engineState != EngineState.READY) return false
        if (!fresh(line)) { finish(line, State.INTERRUPTED, "대기 또는 관측 만료"); return false }
        line.submitted = true
        armPending(line)
        val result = try {
            engine?.speak(line.text, TextToSpeech.QUEUE_FLUSH, params, line.id) ?: TextToSpeech.ERROR
        } catch (_: RuntimeException) { TextToSpeech.ERROR }
        if (result == TextToSpeech.SUCCESS) return true
        stopEngine()
        finish(line, State.ERROR, "재생 요청 실패 ($result)")
        return false
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            main.post {
                val line = current ?: return@post
                if (line.id != utteranceId || line.started) return@post
                if (!fresh(line)) { interrupt("대기 또는 관측 만료"); return@post }
                pendingTimeout?.let { main.removeCallbacks(it) }
                pendingTimeout = null
                line.started = true
                val timeout = Runnable {
                    if (current === line) {
                        stopEngine()
                        finish(line, State.ERROR, "발화 완료 시간 초과")
                    }
                }
                completionTimeout = timeout
                val waitMs = (safety.completionBaseMs + line.text.length.toLong() * safety.completionPerCharMs)
                    .coerceAtMost(safety.maxCompletionMs)
                main.postDelayed(timeout, waitMs)
                if (voiceVolume > 0f) duck(true)
                if (current === line) emit(line, State.STARTED)
            }
        }
        private var firstAudioId: String? = null // 바인더 스레드 전용
        override fun onAudioAvailable(utteranceId: String, audio: ByteArray) {
            if (utteranceId == firstAudioId) return
            firstAudioId = utteranceId
            main.post { onFirstAudio(utteranceId) }
        }
        override fun onDone(utteranceId: String) = end(utteranceId, State.COMPLETED)
        override fun onStop(utteranceId: String, interrupted: Boolean) = end(utteranceId, State.INTERRUPTED)
        @Deprecated("Android 이전 오류 콜백")
        override fun onError(utteranceId: String) = end(utteranceId, State.ERROR, "합성 또는 출력 실패")
        override fun onError(utteranceId: String, errorCode: Int) = end(utteranceId, State.ERROR, "오류 코드 $errorCode")
        private fun end(id: String, state: State, detail: String? = null) {
            main.post { current?.takeIf { it.id == id }?.let { finish(it, state, detail) } }
        }
    }

    private fun fresh(line: Line): Boolean {
        val waitMs = SystemClock.elapsedRealtime() - line.requestedMs
        if (!line.started && waitMs !in 0..safety.maxQueueWaitMs) return false
        val observed = line.evidenceNs ?: return true
        return SystemClock.elapsedRealtimeNanos() - observed in 0L..maxAgeNs
    }

    private fun armPending(line: Line) {
        pendingTimeout?.let { main.removeCallbacks(it) }
        val queueRemaining = safety.maxQueueWaitMs - (SystemClock.elapsedRealtime() - line.requestedMs)
        val evidenceRemainingMs = line.evidenceNs?.let {
            ((maxAgeNs - (SystemClock.elapsedRealtimeNanos() - it)).coerceAtLeast(0L) / 1_000_000L) + 1
        } ?: Long.MAX_VALUE
        val engineWaitMs = if (line.submitted) safety.startTimeoutMs else safety.initTimeoutMs
        val timeout = Runnable {
            if (current === line && !line.started) {
                stopEngine()
                val expiredEvidence = line.evidenceNs?.let {
                    SystemClock.elapsedRealtimeNanos() - it !in 0L..maxAgeNs
                } == true
                finish(line, if (expiredEvidence) State.INTERRUPTED else State.ERROR,
                    if (expiredEvidence) "관측 만료" else "발화 시작 대기 시간 초과")
            }
        }
        pendingTimeout = timeout
        main.postDelayed(timeout, minOf(queueRemaining + 1, evidenceRemainingMs, engineWaitMs).coerceAtLeast(1))
    }

    private fun clearLineTimers() {
        pendingTimeout?.let { main.removeCallbacks(it) }
        completionTimeout?.let { main.removeCallbacks(it) }
        pendingTimeout = null
        completionTimeout = null
    }

    private fun finish(line: Line, state: State, detail: String? = null) {
        if (current !== line) return
        current = null
        clearLineTimers()
        duck(false)
        emit(line, state, detail)
    }

    private fun emit(line: Line, state: State, detail: String? = null) {
        // 원문/관측 시각을 logcat에 기록하지 않는다. 원문은 화면 콜백 메모리로만 전달한다.
        Log.i(TAG, "tts id=${line.id} state=$state")
        onEvent(Event(line.id, line.text, state, detail))
    }

    private fun duck(value: Boolean) {
        if (isDucked == value) return
        isDucked = value
        onDuckingChanged(value)
    }

    private fun stopEngine() { runCatching { engine?.stop() } }

    private fun interrupt(reason: String) {
        val line = current
        stopEngine()
        if (line != null) finish(line, State.INTERRUPTED, reason)
        else { clearLineTimers(); duck(false) }
    }

    fun stop() { checkMain(); interrupt("안내 취소") }

    override fun close() {
        checkMain()
        if (engineState == EngineState.CLOSED) return
        engineState = EngineState.CLOSED
        main.removeCallbacksAndMessages(null)
        interrupt("화면 이탈")
        runCatching { engine?.shutdown() }
        engine = null
    }

    /** 한국어 음성인지. 삼성 TTS는 locale을 3글자 코드로 준다(기기 확인 2026-11-21: `kor_KOR_default`, 언어 `kor`). */
    private fun isKorean(l: Locale): Boolean =
        l.language == Locale.KOREAN.language || runCatching { l.isO3Language }.getOrNull() == Locale.KOREAN.isO3Language

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "AndroidTts must be called on the main thread"
    }
}
