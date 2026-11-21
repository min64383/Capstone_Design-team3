package hearspace.app.runtime

import android.os.Handler
import android.os.Looper
import android.util.Log
import hearspace.app.TAG
import hearspace.core.session.RunLog

/**
 * M65 실험 E1(개발 도구 빌드 전용): 안내를 돌리는 중에 고정 문구를 차례로 말해 `speak` → `onStart` 시간을 [RunLogger] `tts.csv`(kind BENCH)에 남긴다.
 * 한 문구가 끝나면(또는 [GAP_MS] 뒤) 다음 문구. [beforeEach]가 있으면 말하기 [LEAD_MS] 전에 부른다(TalkBack이 말하는 중의 지연을 재려고 화면 알림을 낸다).
 * UI 스레드 전용. 근거 나이는 넘기지 않는다(엔진 지연만 잰다).
 */
class TtsBench(
    private val tts: AndroidTts,
    private val logger: RunLogger,
    private val clockNs: () -> Long,
    private val reps: Int,
    private val beforeEach: (() -> Unit)?,
    private val onDone: () -> Unit,
) {
    private val ui = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque((1..reps).flatMap { phrases() })
    private var currentId: String? = null
    private var requestNs = 0L
    private var text = ""

    fun start() = next()

    /** 팀원 어댑터의 첫 소리 조각(onAudioAvailable 첫 호출). */
    fun onFirstAudio(id: String) {
        if (id != currentId) return
        logger.tts(RunLog.ttsLine(requestNs, "BENCH", text, 0, "AUDIO", (clockNs() - requestNs) / 1e6f, null, null))
    }

    /** 팀원 어댑터의 이벤트. */
    fun onEvent(e: AndroidTts.Event) {
        if (e.id != currentId) return
        val now = clockNs()
        if (e.state == AndroidTts.State.STARTED) {
            logger.tts(RunLog.ttsLine(requestNs, "BENCH", text, 0, "STARTED", (now - requestNs) / 1e6f, null, null))
            return
        }
        logger.tts(RunLog.ttsLine(requestNs, "BENCH", text, 0, e.state.name, null, null, e.detail))
        currentId = null
        ui.postDelayed(::next, GAP_MS)
    }

    private fun next() {
        val t = queue.removeFirstOrNull() ?: run { Log.i(TAG, "tts bench done"); onDone(); return }
        val say: () -> Unit = {
            text = t
            requestNs = clockNs()
            if (tts.speak(t, replaceCurrent = true)) currentId = tts.currentId
            else { logger.tts(RunLog.ttsLine(requestNs, "BENCH", t, 0, "REJECTED", null, null, null)); ui.postDelayed(::next, GAP_MS) }
        }
        val pre = beforeEach
        if (pre == null) say() else { pre(); ui.postDelayed(say, LEAD_MS) }
    }

    companion object {
        private const val GAP_MS = 700L
        private const val LEAD_MS = 150L

        /** 상황 말 30개(references/31 표 3: 거리 없는 말 7 + 거리 붙은 말 23)와 상태 말 11개. */
        fun phrases(): List<String> {
            val fixed = listOf("블록 오른쪽", "블록 왼쪽", "블록 오른쪽으로 꺾임", "블록 왼쪽으로 꺾임", "블록 갈림", "올라가는 계단", "차 다가옴")
            val dist = (1..3).map { "블록 위 물체, ${it}미터" } + (2..5).map { "블록 끝, ${it}미터" } +
                (1..8).map { "내려가는 계단, ${it}미터" } + (1..8).map { "계단, ${it}미터" }
            val status = listOf(
                "앱이 시작했습니다", "안내를 준비합니다.", "안내를 시작합니다.", "안내를 일시정지합니다.", "안내를 재개합니다.",
                "주변을 확인할 수 없습니다. 잠시 멈춰 주세요.", "바닥을 확인할 수 없습니다. 잠시 멈춰 주세요.", "주변 확인을 재개합니다.",
                "안내를 시작할 수 없습니다.", "오류로 안내를 멈췄습니다.", "안내를 종료합니다.",
            )
            return fixed + dist + status
        }
    }
}
