package hearspace.app.ui.user

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.util.Log
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Toast
import hearspace.app.AppConfig
import hearspace.app.TAG
import hearspace.core.types.ConfigException
import java.util.Locale

/** 글 화면 하나(M45): 사용 방법(소리로 듣기 단추), ARCore 고지, 오픈소스 라이선스. TalkBack은 글을 그대로 읽는다. */
class InfoActivity : Activity() {

    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pending: String? = null

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemePref.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val kind = intent.getStringExtra(EXTRA_KIND)
        val (title, text) = when (kind) {
            HOW_TO -> "사용 방법" to howTo()
            ARCORE -> "ARCore 고지" to UserTexts.ARCORE_NOTICE
            else -> "오픈소스 라이선스" to UserTexts.LICENSES_INTRO + asset("hrtf/NOTICE.txt") + "\n\n" + asset("hrtf/LICENSE-Apache-2.0.txt")
        }
        val body = userScreen(title)
        if (kind == HOW_TO) {
            body.addView(Button(this).apply {
                this.text = "소리로 듣기"
                textSize = 18f
                isAllCaps = false
                minHeight = dp(56)
                setOnClickListener { speak(text) }
            }, LinearLayout.LayoutParams(WRAP_CONTENT, WRAP_CONTENT).apply { setMargins(dp(20), dp(4), dp(20), dp(4)) })
        }
        body.paragraph(text)
    }

    /** 사용 방법의 소리 설명은 지금 설정의 소리 방식을 따른다(기기 덮어쓰기가 잘못됐으면 앱 기본 설정, 사용자 모드와 같게). */
    private fun howTo(): String {
        val config = try {
            AppConfig.get(this)
        } catch (e: ConfigException) {
            AppConfig.get(this, deviceOverride = false)
        }
        return UserTexts.howTo(config.sonify.mode, config.sonify.mapping)
    }

    private fun asset(path: String) = assets.open(path).bufferedReader().use { it.readText() }

    /** 시스템 음성(TTS)으로 읽는다. 처음 누를 때 엔진을 열고, 준비되면 읽는다. 한국어 음성이 없으면 알린다. */
    private fun speak(text: String) {
        val t = tts
        if (t == null) {
            pending = text
            tts = TextToSpeech(this) { status -> onTtsInit(status) }
            return
        }
        if (ttsReady) t.speak(text, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE) else pending = text
    }

    private fun onTtsInit(status: Int) {
        val t = tts ?: return
        // setLanguage: 0 이상이면 쓸 수 있음(LANG_AVAILABLE 등), 음수면 데이터 없음·지원 안 함
        if (status != TextToSpeech.SUCCESS || t.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) {
            Log.w(TAG, "TTS unavailable: status=$status")
            t.shutdown()
            tts = null
            Toast.makeText(this, "이 기기에서는 한국어 음성으로 읽을 수 없어요. 기기 설정의 텍스트 음성 변환에서 한국어를 받아 주세요", Toast.LENGTH_LONG).show()
            return
        }
        ttsReady = true
        pending?.let { t.speak(it, TextToSpeech.QUEUE_FLUSH, null, UTTERANCE) }
        pending = null
    }

    override fun onStop() {
        super.onStop()
        tts?.stop()
    }

    override fun onDestroy() {
        tts?.shutdown()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_KIND = "kind"
        const val HOW_TO = "howTo"
        const val ARCORE = "arcore"
        const val LICENSES = "licenses"
        private const val UTTERANCE = "howTo"
    }
}
