package hearspace.app.runtime

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * 앱 실행 시 한국어 시작 문장을 한 번 읽는 TTS.
 */
class AndroidTts(context: Context) : AutoCloseable {

    private val handler = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    private var closed = false

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            // 생성자에서 tts 할당이 끝난 뒤 초기화 결과를 처리한다.
            handler.post {
                if (!closed) {
                    onInitialized(status)
                }
            }
        }
    }

    private fun onInitialized(status: Int) {
        val engine = tts ?: return

        if (status != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS 초기화 실패: $status")
            return
        }

        val languageResult = engine.setLanguage(Locale.KOREAN)

        if (languageResult == TextToSpeech.LANG_MISSING_DATA ||
            languageResult == TextToSpeech.LANG_NOT_SUPPORTED
        ) {
            Log.e(TAG, "한국어 음성 데이터가 없거나 지원되지 않습니다")
            return
        }

        // 사용 가능한 한국어 오프라인 음성이 있으면 선택한다.
        val offlineVoice = engine.voices?.firstOrNull {
            it.locale.language == Locale.KOREAN.language &&
                !it.isNetworkConnectionRequired
        }

        if (offlineVoice != null) {
            engine.setVoice(offlineVoice)
        }

        engine.setSpeechRate(1.0f)
        engine.setPitch(1.0f)

        val result = engine.speak(
            "앱이 시작했습니다",
            TextToSpeech.QUEUE_FLUSH,
            null,
            "app_started"
        )

        if (result == TextToSpeech.ERROR) {
            Log.e(TAG, "시작 음성 요청 실패")
        }
    }

    fun stop() {
        tts?.stop()
    }

    override fun close() {
        closed = true
        handler.removeCallbacksAndMessages(null)
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val TAG = "HearspaceTts"
    }
}