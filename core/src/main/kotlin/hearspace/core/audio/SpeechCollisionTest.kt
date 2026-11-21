package hearspace.core.audio

import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.JsonString
import hearspace.core.types.MiniJson

/** 가상 청취 시험 전용. 시간 기준은 elapsedRealtime(ms)이다. */
class SpeechCollisionTest(
    private val cfg: Config,
    val mode: Mode,
    private val startedMs: Long,
    private val protectUrgent: Boolean,
) {
    data class Config(
        val urgentAfterStartMs: Long,
        val statusAfterUrgentStartMs: Long,
        val recoveryListenMs: Long,
        val timeoutMs: Long,
        val normalText: String,
        val startStatusText: String,
        val urgentText: String,
        val recoveryStatusText: String,
    ) {
        init {
            require(urgentAfterStartMs > 0 && statusAfterUrgentStartMs > 0 && recoveryListenMs > 0)
            require(timeoutMs > urgentAfterStartMs && timeoutMs > statusAfterUrgentStartMs && timeoutMs > recoveryListenMs)
            val texts = listOf(normalText, startStatusText, urgentText, recoveryStatusText)
            require(texts.all { it.isNotBlank() } && texts.toSet().size == texts.size)
        }
        companion object {
            fun parse(text: String): Config {
                val obj = MiniJson.parse(text) as? JsonObject ?: error("collision config: object required")
                fun ms(key: String): Long {
                    val value = (obj.fields[key] as? JsonNumber)?.value ?: error("missing $key")
                    require(value.isFinite() && value >= 1 && value <= Long.MAX_VALUE.toDouble() / 2 && value == value.toLong().toDouble())
                    return value.toLong()
                }
                fun line(key: String) = (obj.fields[key] as? JsonString)?.value ?: error("missing $key")
                return Config(ms("urgentAfterStartMs"), ms("statusAfterUrgentStartMs"),
                    ms("recoveryListenMs"), ms("timeoutMs"), line("normalText"),
                    line("startStatusText"), line("urgentText"), line("recoveryStatusText"))
            }
        }
    }

    enum class Mode { INTERRUPT, STATUS_COLLISION }
    enum class Role { NORMAL, START_STATUS, URGENT, RECOVERY_STATUS }
    enum class Event { STARTED, COMPLETED, INTERRUPTED, ERROR }
    data class Line(val role: Role, val text: String, val urgent: Boolean, val replaceCurrent: Boolean)
    data class Attempt(val line: Line, val allowed: Boolean)
    data class Result(val passed: Boolean, val detail: String)

    private val initial = if (mode == Mode.INTERRUPT) Role.NORMAL else Role.START_STATUS
    private val lines = listOf(
        Line(Role.NORMAL, cfg.normalText, false, false),
        Line(Role.START_STATUS, cfg.startStatusText, false, true),
        Line(Role.URGENT, cfg.urgentText, true, false),
        Line(Role.RECOVERY_STATUS, cfg.recoveryStatusText, false, true),
    ).associateBy { it.role }
    private val offered = mutableSetOf<Role>()
    private val submitted = mutableSetOf<Role>()
    private val ids = mutableMapOf<Role, String>()
    private var active: Line? = null
    private var initialStartedMs: Long? = null
    private var urgentStartedMs: Long? = null
    private var urgentDoneMs: Long? = null
    private var initialInterrupted = false
    private var statusBlocked = false
    val urgentIssued: Boolean get() = Role.URGENT in offered
    var result: Result? = null
        private set

    /** 다음 시도는 발화 시작 콜백을 기준으로 생성한다. 완료 시간은 추정하지 않는다. */
    fun next(nowMs: Long): Attempt? {
        if (result != null) return null
        if (nowMs < startedMs || nowMs - startedMs >= cfg.timeoutMs) {
            fail("시계 역행 또는 시험 시간 초과")
            return null
        }
        urgentDoneMs?.let { done ->
            if (initialInterrupted && (mode == Mode.INTERRUPT || statusBlocked) &&
                nowMs - done >= cfg.recoveryListenMs) {
                result = Result(true, "일반 발화 중단·긴급 발화 완료" +
                    if (mode == Mode.STATUS_COLLISION) "·일반 상태 안내 차단 확인" else " 확인")
                return null
            }
        }
        val role = when {
            initial !in offered -> initial
            Role.URGENT !in offered && initialStartedMs?.let { nowMs - it >= cfg.urgentAfterStartMs } == true -> Role.URGENT
            mode == Mode.STATUS_COLLISION && Role.RECOVERY_STATUS !in offered &&
                urgentStartedMs?.let { nowMs - it >= cfg.statusAfterUrgentStartMs } == true -> Role.RECOVERY_STATUS
            else -> return null
        }
        if (role == Role.RECOVERY_STATUS && active?.role != Role.URGENT) {
            fail("상태 충돌 전에 긴급 발화가 끝남: 시험 간격을 줄여 주세요")
            return null
        }
        offered.add(role)
        val line = lines.getValue(role)
        val allow = canSubmit(active, line, protectUrgent)
        if (allow) {
            submitted.add(role)
            active = line // speak가 동기적으로 이전 중단 콜백을 보내기 전에 교체한다.
        }
        return Attempt(line, allow)
    }

    fun onAdmission(attempt: Attempt, accepted: Boolean) {
        if (result != null) return
        if (!attempt.allowed && !accepted && attempt.line.role == Role.RECOVERY_STATUS) {
            statusBlocked = true
        } else if (!attempt.allowed || !accepted) fail("요청 미접수 또는 정책과 엔진 동작 불일치")
    }

    fun onEvent(id: String, text: String, event: Event, nowMs: Long) {
        if (result != null) return
        if (event == Event.ERROR && text.isBlank()) { fail("TTS 엔진 초기화 오류"); return }
        val line = lines.values.firstOrNull { it.text == text } ?: return
        if (line.role !in submitted) return
        val knownId = ids[line.role]
        if (knownId != null && knownId != id) return
        ids[line.role] = id
        if (line.role == initial && initialInterrupted) return // 교체된 문장의 늦은 완료는 무시한다.
        when (event) {
            Event.STARTED -> {
                if (line.role == initial && initialStartedMs == null) initialStartedMs = nowMs
                if (line.role == Role.URGENT && urgentStartedMs == null) urgentStartedMs = nowMs
            }
            Event.INTERRUPTED -> {
                if (line.role == initial && urgentIssued) initialInterrupted = true
                else if (line.role == initial) fail("긴급 요청 전에 일반 발화가 중단됨")
                else if (line.role == Role.URGENT) fail("긴급 경고가 다른 발화로 중단됨")
            }
            Event.COMPLETED -> {
                if (line.role == initial) fail("긴급 요청 전에 일반 발화가 끝남: 시험 간격을 줄여 주세요")
                if (line.role == Role.URGENT) {
                    if (urgentStartedMs == null || !initialInterrupted) fail("시작·중단 콜백 순서 불일치")
                    else urgentDoneMs = nowMs
                }
            }
            Event.ERROR -> fail("TTS 발화 오류: ${line.role}")
        }
        if (event != Event.STARTED && active?.role == line.role) active = null
    }

    fun cancel() = fail("사용자가 시험을 중단함")
    private fun fail(detail: String) { if (result == null) result = Result(false, detail) }

    companion object {
        /** 일반 상태 안내의 replaceCurrent가 긴급 발화를 덮어쓰지 않게 한다. */
        fun canSubmit(current: Line?, next: Line, protectUrgent: Boolean): Boolean {
            if (protectUrgent && current?.urgent == true && !next.urgent) return false
            return current == null || next.replaceCurrent || (next.urgent && !current.urgent)
        }
    }
}
