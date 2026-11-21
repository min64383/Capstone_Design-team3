package hearspace.app.runtime

import android.os.SystemClock
import hearspace.core.session.RunLog
import hearspace.core.situation.SpeechRank
import hearspace.core.situation.Utterance

/**
 * 앱 말 내보내기(M65 설계 3.4절, 표 3). UI 스레드 전용. 상황 말(core)과 상태 말(팀원 [hearspace.app.ui.user.StatusSpeechPolicy])을
 * core [SpeechRank] 등급 표로 골라 팀원 [AndroidTts]로 낸다. 등급 판단은 core, 여기는 연결·기록만 한다.
 * - 받은 줄의 등급을 파이프라인 `speechLevel`에 받은 때부터 끝날 때까지 둔다(core 말하기 규칙이 실제 말하는 중을 본다).
 * - 소리가 나지 않은 상황 말(등급 표가 받지 않음, 시작 전 근거 나이 초과·끊김·오류)은 core에 되돌린다: 상황이 이어지면 새 근거로 다시 낸다.
 * - 상태 말은 최신 하나만 들고 있다가 받을 수 있게 되면 낸다(줄 세우지 않음).
 * [clockNs]는 파이프라인 시계(실시간은 elapsedRealtime, 재생은 첫 프레임에 맞춘 시계)다.
 */
class SpeechOut(private val tts: AndroidTts, private val clockNs: () -> Long) {
    private class Line(val id: String, val kind: SpeechRank.Kind, val key: String, val rank: Int, val requestNs: Long, val evidenceNs: Long?) {
        var started = false
    }

    private var current: Line? = null
    private var pendingStatus: Pair<String, Boolean>? = null

    /** 안내 중인 파이프라인(실행마다 붙였다 뗀다). */
    var pipeline: LivePipeline? = null
    var logger: RunLogger? = null

    /** 상황 말 하나(느린 경로가 낸 것, UI 스레드로 넘겨 부른다). */
    fun situation(u: Utterance) {
        if (!tts.isAvailable) { // TTS를 쓸 수 없으면 기록 없이 되돌린다(화면이 오류를 알린다)
            pipeline?.unsay(u.key)
            pipeline?.speechLevel?.set(current?.rank ?: 0)
            return
        }
        val rank = SpeechRank.rank(SpeechRank.Kind.SITUATION, u.priority)
        val evidence = u.tNs - (u.ageMs * 1e6).toLong()
        if (!speak(SpeechRank.Kind.SITUATION, u.key, u.text, rank, evidence)) pipeline?.unsay(u.key)
    }

    /** 상태 말 하나([safety]면 등급 1, 아니면 사용자 조작에 대한 답 등급 2). 지금 받을 수 없으면 최신 하나만 들고 있는다. */
    fun status(text: String, safety: Boolean) {
        pendingStatus = null
        val kind = if (safety) SpeechRank.Kind.STATUS_SAFETY else SpeechRank.Kind.STATUS_ACTION
        if (!speak(kind, kind.name, text, SpeechRank.rank(kind), null)) pendingStatus = text to safety
    }

    /** 상태가 바뀌었을 때: 들고 있던 상태 말을 버리고, 지금 말하는 것이 상태 말이면 멈춘다(상황 말은 끊지 않는다). */
    fun cancelStatus() {
        pendingStatus = null
        if (current?.kind != SpeechRank.Kind.SITUATION && current != null) tts.stop()
    }

    /** 팀원 어댑터의 이벤트(UI 스레드). */
    fun onEvent(e: AndroidTts.Event) {
        val line = current?.takeIf { it.id == e.id } ?: return
        val now = clockNs()
        if (e.state == AndroidTts.State.STARTED) {
            line.started = true
            log(line, "STARTED", (now - line.requestNs) / 1e6f, line.evidenceNs?.let { (now - it) / 1e6f }, null)
            return
        }
        log(line, e.state.name, null, null, e.detail)
        finish(line)
        current = null
        pipeline?.speechLevel?.set(0)
        pendingStatus?.let { (text, safety) -> status(text, safety) }
    }

    private fun speak(kind: SpeechRank.Kind, key: String, text: String, rank: Int, evidenceNs: Long?): Boolean {
        val now = clockNs()
        val cur = current
        if (!SpeechRank.admits(cur?.rank ?: 0, kind, rank)) {
            log(Line("", kind, key, rank, now, evidenceNs), "REJECTED", null, null, "rank ${cur?.rank}")
            return false
        }
        // 팀원 어댑터의 근거 나이 검사는 elapsedRealtime 기준이다
        val evidenceElapsed = evidenceNs?.let { it + (SystemClock.elapsedRealtimeNanos() - now) }
        // 호출 중 어댑터가 앞 줄의 INTERRUPTED를 바로 내므로, 앞 줄은 잠시 비워 두고 호출 뒤 어댑터의 지금 줄로 끊겼는지 본다
        current = null
        val ok = tts.speak(text, urgent = rank == 1, evidenceNs = evidenceElapsed, replaceCurrent = true)
        if (cur != null) {
            if (tts.currentId == cur.id) current = cur else { log(cur, "INTERRUPTED", null, null, "rank $rank"); finish(cur) }
        }
        if (!ok) {
            log(Line("", kind, key, rank, now, evidenceNs), "REJECTED", null, null, "engine")
            pipeline?.speechLevel?.set(current?.rank ?: 0)
            return false
        }
        current = Line(tts.currentId ?: "", kind, key, rank, now, evidenceNs)
        pipeline?.speechLevel?.set(rank)
        return true
    }

    /** 소리가 나지 않은 상황 말은 core에 되돌린다. */
    private fun finish(line: Line) {
        if (line.kind == SpeechRank.Kind.SITUATION && !line.started) pipeline?.unsay(line.key)
    }

    private fun log(line: Line, result: String, startDelayMs: Float?, ageAtStartMs: Float?, detail: String?) {
        logger?.tts(RunLog.ttsLine(line.requestNs, line.kind.name, line.key, line.rank, result, startDelayMs, ageAtStartMs, detail))
    }
}
