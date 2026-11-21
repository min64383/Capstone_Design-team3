package hearspace.app.ui.user

/** 화면 상태를 말로 바꾸는 순수 정책. 장애물 유무/위험도는 판단하지 않는다. */
class StatusSpeechPolicy(private val config: Config = Config()) {
    data class Config(val recoveryStableMs: Long = 400, val unavailableStableMs: Long = 800) {
        init { require(recoveryStableMs >= 0 && unavailableStableMs >= 0) }
    }
    enum class Status { IDLE, PREPARING, GUIDING, PAUSED, FLOOR_CHECK, UNAVAILABLE, START_FAILED, STOPPED, ENDED }
    data class Announcement(val text: String, val urgent: Boolean)

    private var pending: Status? = null
    private var sinceMs = 0L
    private var announced: Status? = null
    private var guided = false
    private var resumePending = false

    fun reset() {
        pending = null; announced = null; sinceMs = 0
        guided = false; resumePending = false
    }

    /** 최신 상태 하나만 유지. 연속 같은 상태는 읽지 않고, 짧은 추적 흔들림은 기다린다. */
    fun update(input: Status, nowMs: Long): Announcement? {
        val status = if (input == Status.PREPARING && guided) Status.UNAVAILABLE else input
        if (pending != status || nowMs < sinceMs) { pending = status; sinceMs = nowMs }
        if (status == announced) return null
        val stableMs = when (status) {
            Status.GUIDING -> config.recoveryStableMs
            Status.FLOOR_CHECK, Status.UNAVAILABLE -> config.unavailableStableMs
            else -> 0L
        }
        if (nowMs - sinceMs < stableMs) return null
        val wasGuided = guided
        announced = status
        return when (status) {
            Status.IDLE -> null
            Status.PREPARING -> Announcement("안내를 준비합니다.", false)
            Status.GUIDING -> {
                val text = when {
                    resumePending -> "안내를 재개합니다."
                    wasGuided -> "주변 확인을 재개합니다."
                    else -> "안내를 시작합니다."
                }
                guided = true; resumePending = false
                Announcement(text, false)
            }
            Status.PAUSED -> {
                resumePending = true
                Announcement("안내를 일시정지합니다.", false)
            }
            Status.FLOOR_CHECK -> Announcement("바닥을 확인할 수 없습니다. 잠시 멈춰 주세요.", true)
            Status.UNAVAILABLE -> Announcement("주변을 확인할 수 없습니다. 잠시 멈춰 주세요.", true)
            Status.START_FAILED -> Announcement("안내를 시작할 수 없습니다.", true)
            Status.STOPPED -> Announcement("오류로 안내를 멈췄습니다.", true)
            Status.ENDED -> Announcement("안내를 종료합니다.", false)
        }
    }
}
