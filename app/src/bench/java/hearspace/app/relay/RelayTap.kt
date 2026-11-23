package hearspace.app.relay

import android.content.Context
import android.os.Process
import android.util.Log
import hearspace.app.BuildConfig
import hearspace.app.TAG
import hearspace.app.runtime.AndroidTts
import hearspace.app.runtime.LivePipeline
import hearspace.app.runtime.RunTap
import hearspace.core.relay.RelayEvent
import hearspace.core.relay.RelaySample
import hearspace.core.relay.RelayScreen
import hearspace.core.relay.RelaySettings
import hearspace.core.relay.RelayStart
import hearspace.core.screen.UserScreen
import hearspace.core.types.AlertKind
import hearspace.core.types.GuidanceState
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

/**
 * 데모 중계 고리의 bench 구현(M67 설계 3.3~3.6절). 작동 화면이 앞에 나올 때 기기 파일 [DEVICE_FILE]이 있으면 업로더를 켜고, 없으면 아무것도 하지 않는다.
 * 기기 파일은 bench 자원 `relay.json` 위에 얹는 덮어쓰기(서버 주소·업로드 열쇠)다. 앱 화면에는 아무것도 보이지 않고 logcat에만 남긴다.
 * 표본: 안내 중이면 파이프라인의 최신 안내 출력, 아니면(안내 전후·첫 자세 전) "음원 없음" 표본. 그래야 페이지가 끊김 대신 앱 시작 화면을 보인다.
 */
class RelayTap(private val context: Context) : RunTap {
    @Volatile private var up: RelayUploader? = null
    @Volatile private var pipeline: LivePipeline? = null
    @Volatile private var clock: () -> Long = { System.nanoTime() }
    @Volatile private var notice: String? = null
    /** 말 시작을 보낸 TTS 줄(UI 스레드만). */
    private var speaking: String? = null

    override fun resumed(clockNs: () -> Long, configLayers: List<String>, notice: String?) {
        paused()
        val file = context.getExternalFilesDir(null)?.let { File(it, DEVICE_FILE) }
        if (file == null || !file.isFile) return
        val s = try {
            RelaySettings.load(context.assets.open("relay.json").bufferedReader().use { it.readText() }, listOf(file.readText()))
        } catch (e: Exception) {
            Log.w(TAG, "relay: device file rejected: ${e.message}")
            return
        }
        if (s.serverUrl.isBlank() || s.uploadKey.isBlank()) {
            Log.w(TAG, "relay: serverUrl or uploadKey is empty, relay off")
            return
        }
        clock = clockNs
        this.notice = notice
        val session = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_%04x".format(Random.nextInt(0x10000))
        up = try {
            RelayUploader(s, session, RelayStart(BuildConfig.GIT_HASH, configLayers), clockNs, ::sample,
                threadInit = { Process.setThreadPriority(LivePipeline.BACKGROUND_WORK_PRIORITY) }, log = { Log.i(TAG, it) })
        } catch (e: Exception) {
            Log.w(TAG, "relay: cannot start: $e")
            null
        }
        if (up != null) Log.i(TAG, "relay: on, session $session -> ${s.serverUrl}") // 열쇠는 남기지 않는다
    }

    override fun run(pipeline: LivePipeline?) {
        this.pipeline = pipeline
    }

    override fun screen(screen: UserScreen) {
        up?.screen(RelayScreen(clock(), screen, notice))
    }

    override fun speech(event: AndroidTts.Event) {
        val u = up ?: return
        if (event.state == AndroidTts.State.STARTED) {
            speaking = event.id
            u.event(RelayEvent.SayStart(clock(), event.text))
        } else if (event.id == speaking) {
            speaking = null
            u.event(RelayEvent.SayEnd(clock()))
        }
    }

    override fun alert(kind: AlertKind) {
        up?.event(RelayEvent.Alert(clock(), kind))
    }

    override fun paused() {
        up?.close()
        up = null
        pipeline = null
        speaking = null
    }

    private fun sample(): RelaySample {
        val p = pipeline
        val g = p?.lastOutput?.get()
        return if (p != null && g != null) RelaySample(g.tBlockNs, g.state, g.headingDeg, p.speechActive, g.commands)
        else RelaySample(clock(), GuidanceState.UNKNOWN, Float.NaN, false, emptyList())
    }

    companion object {
        /** `/sdcard/Android/data/<패키지>/files/relay.override.json`. */
        const val DEVICE_FILE = "relay.override.json"
    }
}
