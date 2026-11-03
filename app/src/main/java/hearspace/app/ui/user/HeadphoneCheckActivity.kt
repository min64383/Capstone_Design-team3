package hearspace.app.ui.user

import android.app.Activity
import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.Button
import android.widget.LinearLayout
import hearspace.app.AppConfig
import hearspace.app.TAG
import hearspace.app.loadHrtf
import hearspace.app.runtime.AudioFocus
import hearspace.app.runtime.BinauralOutput
import hearspace.core.audio.BinauralRenderer
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.Config
import hearspace.core.types.ConfigException
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.SoundKind
import java.util.concurrent.atomic.AtomicReference

/**
 * 헤드폰 확인(M45): 왼쪽·오른쪽·앞·뒤에서 장애물이 다가오는 소리를 [PLAY_S]초 들려준다.
 * 실제 안내와 같은 렌더러(core `BinauralRenderer`, HRTF)와 출력(`BinauralOutput`)을 쓴다. 거리는 소리 설정의 먼 끝
 * `sonify.farM`에서 가운데((farM + nearM)/2)로 줄어든다(다가오는 장애물). 개발 모드의 `AudioTestActivity`는 옛 단순 스테레오라 쓰지 않는다.
 */
class HeadphoneCheckActivity : Activity() {

    private lateinit var config: Config
    private lateinit var renderer: BinauralRenderer
    private lateinit var focus: AudioFocus
    private var output: BinauralOutput? = null

    /** 지금 들려줄 방향과 시작 시각(ns). UI가 쓰고 오디오 스레드가 읽는다(큐가 아니라 최신 값 교체). */
    private val playing = AtomicReference<Pair<Float, Long>?>(null)

    override fun attachBaseContext(newBase: Context) = super.attachBaseContext(ThemePref.wrap(newBase))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        config = try {
            AppConfig.get(this)
        } catch (e: ConfigException) {
            AppConfig.get(this, deviceOverride = false)
        }
        renderer = BinauralRenderer(config, loadHrtf(this))
        focus = AudioFocus(this)
        val body = userScreen("헤드폰 확인")
        body.paragraph(
            "헤드폰을 쓰고 단추를 누르면 그 방향에서 장애물이 다가오는 소리가 1.5초 동안 나요. 그 방향에서 들리면 헤드폰이 바르게 쓰인 거예요. " +
                "왼쪽과 오른쪽이 바뀌어 들리면 헤드폰을 바꿔 써 주세요. 앞과 뒤는 헤드폰 소리로 구별하기 어려울 수 있어요.",
        )
        for ((label, az) in listOf("왼쪽" to -90f, "오른쪽" to 90f, "앞" to 0f, "뒤" to 180f)) {
            body.addView(Button(this).apply {
                text = "$label 소리 듣기"
                textSize = 20f
                isAllCaps = false
                minHeight = dp(64)
                setOnClickListener { playing.set(az to System.nanoTime()) }
            }, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT).apply { setMargins(dp(20), dp(6), dp(20), dp(6)) })
        }
    }

    override fun onResume() {
        super.onResume()
        focus.request()
        output = runCatching { BinauralOutput(config.audio, ::render).also { it.start() } }
            .onFailure { Log.e(TAG, "headphone check: audio output failed", it) }
            .getOrNull()
    }

    override fun onPause() {
        super.onPause()
        playing.set(null)
        output?.close()
        output = null
        focus.abandon()
    }

    /** 오디오 스레드: 블록 하나. 들려줄 소리가 없으면 빈 명령으로 렌더해 이전 소리를 자연스럽게 끝낸다. */
    private fun render(buf: FloatArray) {
        val now = System.nanoTime()
        val cmd = playing.get()?.let { (az, startNs) ->
            val f = (now - startNs) / 1e9f / PLAY_S
            if (f >= 1f) return@let null
            val far = config.sonify.farM
            val mid = (config.sonify.farM + config.sonify.nearM) / 2
            AudioCmd(obstacleId = 1, azimuthDeg = az, distanceM = far + (mid - far) * f, band = Band.WARN, sound = SoundKind.FLOOR_PULSE, infoAgeMs = 0f)
        }
        renderer.render(GuidanceOutput(now, GuidanceState.NORMAL, listOfNotNull(cmd), 0f, null), buf)
    }

    companion object {
        /** 확인음 길이(s). 안내 값이 아니라 화면 시연 길이다. */
        private const val PLAY_S = 1.5f
    }
}
