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

/** UI 스레드에서 만들고 speak/stop/close를 호출한다. 두 콜백도 UI 스레드에서 전달한다. */
class AndroidTts(
    context: Context,
    config: Config,
    private val onDuckingChanged: (Boolean) -> Unit,
    private val onEvent: (Event) -> Unit = {},
) : AutoCloseable {
    enum class State(val label: String) {
        STARTED("읽는 중"), COMPLETED("읽기 완료"), INTERRUPTED("중단"), ERROR("오류")
    }

    data class Event(val id: String, val text: String, val state: State, val detail: String?)

    private enum class EngineState { LOADING, READY, FAILED, CLOSED }
    private class Line(val id: String, val text: String, val urgent: Boolean, val evidenceNs: Long?) {
        var submitted = false
        var started = false
    }

    private val main = Handler(Looper.getMainLooper())
    private val maxAgeNs = (config.policy.maxInfoAgeMs * 1_000_000.0).toLong()
    private val voiceVolume = config.audio.ttsVolume
    private val params = Bundle().apply {
        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, voiceVolume)
    }
    private val engine: TextToSpeech
    private var engineState = EngineState.LOADING
    private var current: Line? = null
    private var serial = 0L

    var isDucked = false
        private set

    // TTS 엔진이 시작하기를 기다리는 사이 관측이 만료되면 제출했던 발화도 취소한다.
    private val expiry = Runnable {
        val line = current ?: return@Runnable
        if (!line.started) {
            if (fresh(line)) armExpiry(line) else interrupt("관측 만료")
        }
    }

    init {
        checkMain()
        engine = TextToSpeech(context.applicationContext) { status ->
            // 생성 중 초기화 콜백이 와도 engine이 할당된 다음에 쓴다.
            main.post { initialized(status) }
        }
    }

    /** evidenceNs는 실시간 관측의 elapsedRealtimeNanos 기준 시각. 시작 문장과 수동 시험은 생략한다.
     * 재생 요청을 받으면 true. 초기화 중에는 한 문장만 보관하며, 최종 결과는 onEvent로 보낸다. */
    fun speak(text: String, urgent: Boolean = false, evidenceNs: Long? = null): Boolean {
        checkMain()
        if (engineState == EngineState.CLOSED) return false
        val line = Line((++serial).toString(), text, urgent, evidenceNs)
        val problem = when {
            text.isBlank() || text.length > TextToSpeech.getMaxSpeechInputLength() -> "문장 길이 오류"
            engineState == EngineState.FAILED -> "한국어 TTS를 사용할 수 없습니다"
            else -> null
        }
        if (problem != null) {
            emit(line, State.ERROR, problem)
            return false
        }
        if (!fresh(line)) return false
        current?.let { if (!it.started && !fresh(it)) interrupt("관측 만료") }
        if (engineState == EngineState.CLOSED) return false
        val previous = current
        if (previous != null && (!urgent || previous.urgent)) return false

        current = line
        main.removeCallbacks(expiry)
        if (previous != null) {
            if (previous.submitted) engine.stop()
            // 긴급 문장이 시작하거나 실패할 때까지 기존 감쇠를 유지한다.
            emit(previous, State.INTERRUPTED, "긴급 안내로 교체")
        }
        if (current !== line) return false
        armExpiry(line)
        return engineState == EngineState.LOADING || submit(line)
    }

    private fun initialized(status: Int) {
        if (engineState != EngineState.LOADING) return
        if (status != TextToSpeech.SUCCESS) {
            failInit("TTS 초기화 실패 ($status)")
            return
        }
        if (engine.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) {
            failInit("한국어 음성 데이터를 확인해 주세요")
            return
        }
        // 기기가 선택한 한국어 음성을 우선하고, 네트워크가 필요하면 설치된 오프라인 음성을 찾는다.
        fun offlineKorean(v: Voice) =
            v.locale.language == Locale.KOREAN.language && !v.isNetworkConnectionRequired &&
                TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
        if (engine.voice?.let(::offlineKorean) != true) {
            engine.voices?.firstOrNull(::offlineKorean)?.let { engine.setVoice(it) }
        }
        engine.setSpeechRate(1f)
        engine.setPitch(1f)
        engine.setAudioAttributes(AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build())
        if (engine.setOnUtteranceProgressListener(listener) != TextToSpeech.SUCCESS) {
            failInit("발화 상태 리스너 등록 실패")
            return
        }
        engineState = EngineState.READY
        current?.let { submit(it) }
    }

    private fun failInit(detail: String) {
        engineState = EngineState.FAILED
        Log.e(TAG, detail)
        current?.let { finish(it, State.ERROR, detail) }
    }

    private fun submit(line: Line): Boolean {
        if (!fresh(line)) {
            finish(line, State.INTERRUPTED, "관측 만료")
            return false
        }
        line.submitted = true
        val result = engine.speak(line.text, TextToSpeech.QUEUE_FLUSH, params, line.id)
        if (result == TextToSpeech.SUCCESS) return true
        finish(line, State.ERROR, "재생 요청 실패 ($result)")
        return false
    }

    private val listener = object : UtteranceProgressListener() {
        override fun onStart(utteranceId: String) {
            main.post {
                val line = current ?: return@post
                if (line.id != utteranceId || line.started) return@post
                if (!fresh(line)) {
                    interrupt("관측 만료")
                    return@post
                }
                main.removeCallbacks(expiry)
                line.started = true
                if (voiceVolume > 0f) duck(true)
                if (current === line) emit(line, State.STARTED)
            }
        }

        override fun onDone(utteranceId: String) = end(utteranceId, State.COMPLETED)
        override fun onStop(utteranceId: String, interrupted: Boolean) = end(utteranceId, State.INTERRUPTED)
        @Deprecated("Android 이전 오류 콜백")
        override fun onError(utteranceId: String) = end(utteranceId, State.ERROR, "합성 또는 출력 실패")
        override fun onError(utteranceId: String, errorCode: Int) = end(utteranceId, State.ERROR, "오류 코드 $errorCode")

        private fun end(id: String, state: State, detail: String? = null) {
            main.post {
                // 교체된 문장의 늦은 onDone/onStop이 새 문장의 음량을 바꾸지 않게 한다.
                current?.takeIf { it.id == id }?.let { finish(it, state, detail) }
            }
        }
    }

    private fun fresh(line: Line): Boolean {
        val observed = line.evidenceNs ?: return true
        return SystemClock.elapsedRealtimeNanos() - observed in 0L..maxAgeNs
    }

    private fun armExpiry(line: Line) {
        val observed = line.evidenceNs ?: return
        val remaining = maxAgeNs - (SystemClock.elapsedRealtimeNanos() - observed)
        main.postDelayed(expiry, remaining.coerceAtLeast(0L) / 1_000_000L + 1L)
    }

    private fun finish(line: Line, state: State, detail: String? = null) {
        current = null
        main.removeCallbacks(expiry)
        duck(false)
        emit(line, state, detail)
    }

    private fun emit(line: Line, state: State, detail: String? = null) {
        val event = Event(line.id, line.text, state, detail)
        Log.i(TAG, "tts $event")
        onEvent(event)
    }

    private fun duck(value: Boolean) {
        if (isDucked == value) return
        isDucked = value
        onDuckingChanged(value)
    }

    private fun interrupt(reason: String) {
        val line = current ?: return
        if (line.submitted) engine.stop()
        finish(line, State.INTERRUPTED, reason)
    }

    fun stop() {
        checkMain()
        interrupt("안내 취소")
    }

    override fun close() {
        checkMain()
        if (engineState == EngineState.CLOSED) return
        engineState = EngineState.CLOSED
        interrupt("화면 이탈")
        main.removeCallbacksAndMessages(null)
        engine.shutdown()
    }

    private fun checkMain() = check(Looper.myLooper() == Looper.getMainLooper()) {
        "AndroidTts must be called on the main thread"
    }
}
