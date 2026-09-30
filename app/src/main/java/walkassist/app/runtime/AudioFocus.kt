package walkassist.app.runtime

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.util.Log
import walkassist.app.TAG

/**
 * 안내음 오디오 포커스(§9.4). 다른 앱이 "줄여서 들려 달라"(일시 포커스·ducking 허용)를 요청하면 Android 8+가
 * 우리 소리를 자동으로 낮춘다(`setWillPauseWhenDucked(false)`). 안전 안내이므로 포커스를 잃어도 멈추지 않고 기록만 한다.
 * // VERIFY: TalkBack 음성이 일시 포커스(ducking 허용)를 요청해 자동 감쇠가 일어나는지 — M9 기기 확인에서 logcat으로 본다.
 */
class AudioFocus(context: Context) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .setWillPauseWhenDucked(false)
        .setOnAudioFocusChangeListener { change -> Log.i(TAG, "audio focus change $change") }
        .build()

    fun request() {
        Log.i(TAG, "audio focus request -> ${am.requestAudioFocus(request)}")
    }

    fun abandon() {
        am.abandonAudioFocusRequest(request)
    }
}
