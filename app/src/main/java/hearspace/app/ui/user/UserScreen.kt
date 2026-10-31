package hearspace.app.ui.user

import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.MapHealth

/**
 * 작동 화면에 그릴 것(M45, `docs/ui/ui-mockup.html` §3). 안내 판단은 하지 않고 지금 상태를 화면 요소로 바꾸기만 한다.
 * 무음(SILENT)이나 명령 없음은 가운데를 비운다: "장애물 없음"으로 보이게 하지 않는다(무음 ≠ 안전).
 * 주의와 멈춤은 색만으로 나누지 않는다: 멈춤은 고리를 두른 화살표 + "멈춤", 주의는 화살표 + "주의".
 */
data class UserScreen(
    /** 위 상태 줄. 시작 전에는 없다(가운데 [label]이 대신한다). */
    val title: String?,
    val sub: String?,
    val titleTone: Tone,
    val visual: Visual,
    /** 가운데 그림 아래 글자: 시작 안내 또는 "주의"·"멈춤". */
    val label: String?,
    val labelTone: Tone,
    /** 아래 조작 안내: (동작, 뜻). */
    val hints: List<Pair<String, String>>,
    /** 안내 중이 아님: 설정 단추와 고지를 보인다(걷는 중 잘못 눌리지 않게 안내 중에는 숨긴다). */
    val idle: Boolean,
    /** TalkBack이 읽는 상태 낱말(M9부터 같은 말). */
    val spoken: String,
) {
    enum class Tone { PLAIN, GUIDE, CAUTION, STOP }

    sealed interface Visual {
        data object Logo : Visual
        data object Preparing : Visual
        data object FloorCheck : Visual
        data object None : Visual
        data object Paused : Visual
        data object Notice : Visual
        data class Arrow(val azimuthDeg: Float, val stop: Boolean) : Visual
    }

    /** 안내 중이 아닐 때의 알림: 제목과 사유(사유는 TalkBack이 읽는다). */
    data class Notice(val title: String, val reason: String) {
        companion object {
            fun startFailed(reason: String) = Notice("시작하지 못했어요", reason)
            fun stopped(reason: String) = Notice("안내를 멈췄어요", reason)
        }
    }

    companion object {
        /** 길게 누르기는 안내만 끝내고 앱에 남는다(시작 전에는 할 일이 없어 안내하지 않는다). */
        private val GUIDING = listOf("두 번 탭" to "일시정지", "길게 누르기" to "안내 종료")

        /**
         * [running]: 안내 세션이 있음, [paused]: 사용자가 일시정지함, [state]: 마지막 안내 출력의 상태(없으면 null),
         * [mapHealth]: 마지막 지도 상태, [band]·[azimuthDeg]: 가장 가까운 음원의 구간과 방위(오른쪽 +).
         */
        fun of(running: Boolean, notice: Notice?, paused: Boolean, state: GuidanceState?, mapHealth: MapHealth?, band: Band?, azimuthDeg: Float?): UserScreen {
            if (!running) {
                return if (notice != null) {
                    UserScreen(notice.title, notice.reason, Tone.PLAIN, Visual.Notice, null, Tone.PLAIN,
                        listOf("두 번 탭" to "다시 시작"), idle = true, spoken = notice.reason)
                } else {
                    UserScreen(null, null, Tone.PLAIN, Visual.Logo, "두 번 탭하여\n시작", Tone.PLAIN, emptyList(), idle = true, spoken = "시작 전")
                }
            }
            if (paused || state == GuidanceState.PAUSED) {
                return UserScreen("일시정지", "소리 안내를 멈췄어요", Tone.PLAIN, Visual.Paused, null, Tone.PLAIN,
                    listOf("두 번 탭" to "재개", "길게 누르기" to "안내 종료"), idle = false, spoken = "일시정지")
            }
            if (state == null || state == GuidanceState.UNKNOWN) {
                return UserScreen("준비 중", "주변을 확인하고 있어요", Tone.PLAIN, Visual.Preparing, null, Tone.PLAIN, GUIDING, idle = false, spoken = "준비 중")
            }
            val arrow = azimuthDeg?.takeIf { band == Band.WARN || band == Band.STOP }?.let { Visual.Arrow(it, band == Band.STOP) }
            val label = arrow?.let { if (it.stop) "멈춤" else "주의" }
            val labelTone = if (arrow?.stop == true) Tone.STOP else Tone.CAUTION
            return if (mapHealth == MapHealth.DEGRADED) {
                // 바닥을 잃어 새 물체를 볼 수 없다(M40, 대기음과 함께). 남은 음원이 있으면 그 방향을 먼저 보인다
                UserScreen("바닥 확인 중", if (arrow == null) "지금은 물체를 볼 수 없어요" else null, Tone.CAUTION, arrow ?: Visual.FloorCheck,
                    label, labelTone, GUIDING, idle = false, spoken = "바닥 확인 중")
            } else {
                UserScreen("안내 중", null, Tone.GUIDE, arrow ?: Visual.None, label, labelTone, GUIDING, idle = false, spoken = "안내 중")
            }
        }
    }
}
