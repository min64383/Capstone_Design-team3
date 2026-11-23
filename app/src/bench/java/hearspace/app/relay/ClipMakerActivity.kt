package hearspace.app.relay

import android.app.Activity
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import android.util.Log
import android.view.WindowManager
import android.widget.TextView
import hearspace.app.AppConfig
import hearspace.app.TAG
import java.io.File
import java.security.MessageDigest
import java.util.Locale

/**
 * 데모 중계 말 소리 만들기(M67 설계 3.5절, bench 빌드에만, 앱 아이콘 없음). `tools/relay/s10.ps1 -Clips`가 adb로 띄운다.
 * 앱과 같은 TTS 엔진·음성·속도·높이·음량으로 [RelayTexts] 문장마다 `synthesizeToFile`을 부르고, 이름은 문장(UTF-8)의 SHA-1 + `.wav`.
 * 끝나면 `texts.txt`(이름·문장), `voice.txt`(엔진·음성), `done.txt`를 쓴다. 실패하면 `failed.txt`. 표본율은 서버가 읽을 때 바꾼다.
 */
class ClipMakerActivity : Activity() {
    private lateinit var status: TextView
    private var tts: TextToSpeech? = null
    private val dir by lazy { File(getExternalFilesDir(null), OUT_DIR) }
    /** 작동 화면과 같은 설정(기기 덮어쓰기가 잘못됐으면 앱 기본 설정). */
    private val config by lazy { runCatching { AppConfig.get(this) }.getOrElse { AppConfig.get(this, deviceOverride = false) } }
    private val texts by lazy { RelayTexts.all(config.situation) }
    private var next = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        status = TextView(this).apply { textSize = 20f; setPadding(48, 96, 48, 48) }
        setContentView(status)
        dir.deleteRecursively()
        dir.mkdirs()
        show("TTS 준비 중")
        tts = TextToSpeech(applicationContext) { code -> runOnUiThread { ready(code) } }
    }

    private fun ready(code: Int) {
        val t = tts ?: return
        if (code != TextToSpeech.SUCCESS || t.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) return fail("TTS 초기화 실패 ($code)")
        // AndroidTts와 같은 음성 규칙(팀원 코드를 고치지 않으려고 옮겨 씀): 오프라인 한국어 음성, 아니면 이름순 첫 오프라인 한국어 음성
        fun korean(l: Locale) = l.language == Locale.KOREAN.language || runCatching { l.isO3Language }.getOrNull() == Locale.KOREAN.isO3Language
        fun offline(v: Voice) = korean(v.locale) && !v.isNetworkConnectionRequired && TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED !in v.features.orEmpty()
        if (t.voice?.let(::offline) != true) {
            val v = t.voices.orEmpty().filter(::offline).sortedBy { it.name }.firstOrNull()
            if (v == null || t.setVoice(v) != TextToSpeech.SUCCESS) return fail("오프라인 한국어 음성이 없습니다")
        }
        if (t.setSpeechRate(1f) != TextToSpeech.SUCCESS || t.setPitch(1f) != TextToSpeech.SUCCESS) return fail("TTS 설정 실패")
        t.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = runOnUiThread { synthesizeNext() }
            @Deprecated("Android 이전 오류 콜백")
            override fun onError(utteranceId: String) = runOnUiThread { fail("합성 실패: $utteranceId") }
            override fun onError(utteranceId: String, errorCode: Int) = runOnUiThread { fail("합성 실패: $utteranceId 오류 $errorCode") }
        })
        File(dir, "voice.txt").writeText("engine ${t.defaultEngine}\nvoice ${t.voice?.name} ${t.voice?.locale}\n")
        Log.i(TAG, "relay clips: ${texts.size} texts, engine ${t.defaultEngine}, voice ${t.voice?.name}")
        synthesizeNext()
    }

    private fun synthesizeNext() {
        val t = tts ?: return
        if (next == texts.size) {
            File(dir, "texts.txt").writeText(texts.joinToString("") { "${sha1(it)}\t$it\n" })
            File(dir, "done.txt").writeText("${texts.size}\n")
            show("완료: ${texts.size}개\n${dir.path}")
            return
        }
        val text = texts[next++]
        val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, config.audio.ttsVolume) } // AndroidTts와 같은 음량
        if (t.synthesizeToFile(text, params, File(dir, sha1(text) + ".wav"), "clip$next") != TextToSpeech.SUCCESS) return fail("합성 요청 실패: $text")
        show("말 소리 만드는 중 $next/${texts.size}")
    }

    private fun show(s: String) {
        status.text = s
    }

    private fun fail(why: String) {
        Log.e(TAG, "relay clips: $why")
        File(dir, "failed.txt").writeText("$why\n")
        show("실패: $why")
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    companion object {
        const val OUT_DIR = "relay-clips"
        fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }
}
