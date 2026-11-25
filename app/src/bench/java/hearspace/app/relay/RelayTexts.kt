package hearspace.app.relay

import hearspace.core.situation.Environment
import hearspace.core.situation.Signals
import hearspace.core.types.SituationConfig
import kotlin.math.ceil

/**
 * 데모 중계 말 소리 모음의 문장 목록(M67 설계 3.5절, 표 4). 앱이 낼 수 있는 말은 고정 문장과 정수 거리뿐이다.
 * 문장은 규칙 코드(StatusSpeechPolicy, UserModeActivity, core Situations)에 글자로 있다. 그 파일들은 병합하지 않은 팀원 TTS 브랜치가
 * 고치고 있어 상수로 옮기지 않고 여기에 옮겨 적는다(설계 검토 2026-11-23). 상태 말은 시험이 정책과 맞추고, 상황 말이 어긋나면
 * 서버가 없는 문장을 기록하고 채팅에 알린다(M66). 팀원 브랜치가 병합되면 새 문장을 더한다.
 */
object RelayTexts {
    /** 앱 시작 문장(UserModeActivity)과 상태 말(StatusSpeechPolicy). */
    val STATUS = listOf(
        "앱이 시작했습니다",
        "안내를 준비합니다.",
        "안내를 시작합니다.",
        "안내를 재개합니다.",
        "주변 확인을 재개합니다.",
        "안내를 일시정지합니다.",
        "바닥을 확인할 수 없습니다. 잠시 멈춰 주세요.",
        "주변을 확인할 수 없습니다. 잠시 멈춰 주세요.",
        "안내를 시작할 수 없습니다.",
        "오류로 안내를 멈췄습니다.",
        "안내를 종료합니다.",
    )

    /** 상황 말 중 거리가 붙지 않는 것(core Situations). 환경 말은 core [Environment]에서 읽는다. */
    val SITUATION = Environment.entries.mapNotNull { it.text } + listOf(
        "블록 오른쪽", "블록 왼쪽", "블록 갈림", "블록 오른쪽으로 꺾임", "블록 왼쪽으로 꺾임",
        "올라가는 계단", "방지턱", "차 다가옴", "사람 다가옴",
    )

    /** 신호 말(M71, core [Signals]의 상수를 그대로). */
    val SIGNAL = listOf(Signals.RED_TEXT, Signals.GREEN_TEXT)

    /** 거리가 붙는 상황 말(core Situations, 거리는 반올림·최소 1 m). */
    private val DISTANCE: List<(Int) -> String> = listOf(
        { "내려가는 곳, ${it}미터" }, { "블록 위 물체, ${it}미터" }, { "블록 끝, ${it}미터" }, { "내려가는 계단, ${it}미터" }, { "계단, ${it}미터" },
    )

    /** 전체 목록. 거리는 1 m부터, 거리 붙는 규칙이 말하는 가장 먼 범위(설정)까지. */
    fun all(c: SituationConfig): List<String> {
        val maxM = ceil(maxOf(c.gridMaxDistM, c.envAheadMaxM, c.endMaxM)).toInt()
        return STATUS + SITUATION + SIGNAL + DISTANCE.flatMap { f -> (1..maxM).map(f) }
    }
}
