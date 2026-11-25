package hearspace.core.screen

import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.MapHealth
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 작동 화면에 그릴 것(M45, `docs/ui/ui-mockup.html` §3). 안내 판단은 하지 않고 지금 상태를 화면 요소로 바꾸기만 한다.
 * 앱과 데모 중계의 PC 녹화 재생기가 같이 쓰도록 core에 둔다(M66, Android를 쓰지 않음).
 * 무음(SILENT)이나 명령 없음은 가운데를 비운다: "장애물 없음"으로 보이게 하지 않는다(무음 ≠ 안전).
 * 주의와 멈춤은 색만으로 나누지 않는다: 멈춤은 고리를 두른 화살표 + "멈춤", 주의는 화살표 + "주의".
 * M72: 화살표가 가리키는 음원을 글로 푼 정보 줄([detail])과 지금 말하는 문장([speech])을 더한다. 화면은 칸마다 높이가 고정이라
 * 이 둘이 나타나고 사라져도 다른 글자·기호는 움직이지 않는다(milepost demo-relay/M72-design 3.2~3.4절).
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
    /** 정보 줄: 화살표가 가리키는 음원의 방향·거리·높이. 화살표가 없으면 null("장애물 없음"으로 보이지 않게). */
    val detail: Detail? = null,
    /** 말 줄: 지금 TTS가 말하는 문장. 안내 중일 때만. */
    val speech: String? = null,
) {
    /**
     * 정보 줄 세 조각. 그리는 쪽은 [distance]를 가운데에 고정하고 [direction]은 그 왼쪽(오른쪽 맞춤), [height]는 그 오른쪽(왼쪽 맞춤)에 둔다.
     * 그러면 방향 글이 "1시"→"11시"로 길어져도 거리·높이 글은 움직이지 않는다.
     */
    data class Detail(val direction: String, val distance: String, val height: String?)

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
         * [mapHealth]: 마지막 지도 상태, [cmd]: 화살표로 보일 음원(빠른 경로 출력의 첫 음원 명령), [speech]: 지금 말하는 문장,
         * [heightLowM]·[heightHighM]: 높이 차를 소리 높낮이로 바꾸는 범위(`sonify`). 정보 줄의 높이 낱말을 이 범위의 세 구간으로 정한다.
         */
        fun of(
            running: Boolean, notice: Notice?, paused: Boolean, state: GuidanceState?, mapHealth: MapHealth?,
            cmd: AudioCmd?, speech: String?, heightLowM: Float, heightHighM: Float,
        ): UserScreen {
            if (!running) {
                return if (notice != null) {
                    UserScreen(notice.title, notice.reason, Tone.PLAIN, Visual.Notice, null, Tone.PLAIN,
                        listOf("두 번 탭" to "다시 시작"), idle = true, spoken = notice.reason)
                } else {
                    // 한 줄: 기호 글 칸(48)에 든다(M72)
                    UserScreen(null, null, Tone.PLAIN, Visual.Logo, "두 번 탭하여 시작", Tone.PLAIN, emptyList(), idle = true, spoken = "시작 전")
                }
            }
            val said = speech?.takeIf { it.isNotBlank() }
            if (paused || state == GuidanceState.PAUSED) {
                return UserScreen("일시정지", "소리 안내를 멈췄어요", Tone.PLAIN, Visual.Paused, null, Tone.PLAIN,
                    listOf("두 번 탭" to "재개", "길게 누르기" to "안내 종료"), idle = false, spoken = "일시정지", speech = said)
            }
            if (state == null || state == GuidanceState.UNKNOWN) {
                return UserScreen("준비 중", "주변을 확인하고 있어요", Tone.PLAIN, Visual.Preparing, null, Tone.PLAIN, GUIDING, idle = false,
                    spoken = "준비 중", speech = said)
            }
            val band = cmd?.band
            val arrow = cmd?.takeIf { band == Band.WARN || band == Band.STOP }?.let { Visual.Arrow(it.azimuthDeg, band == Band.STOP) }
            val label = arrow?.let { if (it.stop) "멈춤" else "주의" }
            val labelTone = if (arrow?.stop == true) Tone.STOP else Tone.CAUTION
            val detail = if (arrow != null && cmd != null) detailOf(cmd, heightLowM, heightHighM) else null
            return if (mapHealth == MapHealth.DEGRADED) {
                // 바닥을 잃어 새 물체를 볼 수 없다(M40, 대기음과 함께). 남은 음원이 있으면 그 방향을 먼저 보인다
                UserScreen("바닥 확인 중", if (arrow == null) "지금은 물체를 볼 수 없어요" else null, Tone.CAUTION, arrow ?: Visual.FloorCheck,
                    label, labelTone, GUIDING, idle = false, spoken = "바닥 확인 중", detail = detail, speech = said)
            } else {
                UserScreen("안내 중", null, Tone.GUIDE, arrow ?: Visual.None, label, labelTone, GUIDING, idle = false, spoken = "안내 중",
                    detail = detail, speech = said)
            }
        }

        /**
         * 정보 줄(M72 설계 표 4). 방향: 머리 기준 방위각(오른쪽 +)을 30°마다 한 시인 시계 방향으로(12시 = 정면, 보행 훈련의 표현, 경계는 반올림).
         * 거리: 수평 거리 0.1 m. 높이: 높이 차를 [lowM, highM]의 세 구간으로(낱말은 폰을 가슴에 단다는 가정), 값이 없으면 뺀다.
         */
        fun detailOf(cmd: AudioCmd, lowM: Float, highM: Float): Detail {
            val hour = Math.floorMod((cmd.azimuthDeg / 30f).roundToInt(), 12).let { if (it == 0) 12 else it }
            val third = (highM - lowM) / 3f
            val h = cmd.heightDeltaM
            val height = when {
                !h.isFinite() -> null
                h < lowM + third -> "허리 아래"
                h > highM - third -> "머리 높이"
                else -> "가슴 높이"
            }
            return Detail("${hour}시 방향", String.format(Locale.ROOT, "%.1f m", cmd.distanceM), height)
        }
    }
}
