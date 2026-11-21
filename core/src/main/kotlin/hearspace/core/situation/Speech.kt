package hearspace.core.situation

import hearspace.core.types.SituationConfig

/**
 * 상황 규칙이 이번 차례에 "지금 이 상황이 있다"고 내는 요청(M50 설계 표 5). 상황이 이어지는 동안 차례마다 다시 낸다.
 * [key]는 상황 하나(같은 물체·같은 자리)를 가리킨다. [level]이 오르면(예: 계단 → 내려가는 계단) 더 위험해진 것이다.
 * [evidenceNs]는 근거 관측의 촬영 시각이다.
 */
data class SpeechRequest(val key: String, val text: String, val priority: Int, val evidenceNs: Long, val level: Int = 0)

/** 실제로 낸 말. [interrupted]는 말하던 것을 끊었는지. */
data class Utterance(val tNs: Long, val key: String, val text: String, val priority: Int, val ageMs: Float, val interrupted: Boolean)

/**
 * 말하기 규칙(M50 설계 표 6). 대기열이 없다: 요청은 상황이 이어지는 동안 차례마다 다시 오므로, 지금 낼 수 없는 말은 버리고 다음 차례의 최신 요청을 본다
 * (최신 값 교체). 근거의 정보 나이가 [maxInfoAgeNs]를 넘으면 말하지 않는다.
 * - 한 상황(열쇠)은 이어지는 동안 한 번만. 끊겼다가 다시 오면 마지막으로 말한 지 [SituationConfig.repeatS]가 지나야 다시 말한다. [SpeechRequest.level]이 오르면 예외.
 * - 우선순위 1은 우선순위 2·3을 끊고 바로 말한다(1끼리는 끊지 않음). 2·3은 말하는 중이 아닐 때 하나만, 앞 2·3 말과 같거나 낮은 우선순위면 [SituationConfig.gapS]가 지난 뒤에.
 * - [SituationConfig.infoSpeech]가 false면 우선순위 3은 내지 않는다.
 *
 * 앱(M65)은 [tick]에 실제로 말하는 중인 줄의 등급을 넘긴다(0 = 쉼). 그러면 음절 수 어림 대신 그 값으로 말하는 중인지 보고,
 * 2·3순위 사이 간격은 실제로 끝난 시각부터 센다. PC 재생은 넘기지 않는다(출력 그대로).
 */
class Speech(private val cfg: SituationConfig, private val maxInfoAgeNs: Long) {
    private class Spoken(val tNs: Long, val level: Int)

    private val spoken = HashMap<String, Spoken>()
    private var active = emptySet<String>()
    /** 다시 나타날 때 [SituationConfig.repeatS]가 지나 이번에 말할 차례가 된 상황. 말하거나 사라질 때까지 남는다(첫 차례에 막혀도 이어지는 동안 말함). */
    private val owed = HashSet<String>()
    private var busyUntilNs = Long.MIN_VALUE
    private var busyPriority = 0
    private var lowEndNs = Long.MIN_VALUE
    private var lowPriority = 3
    /** 앱 등급 모드에서 마지막으로 낸 2·3순위 말이 아직 끝나지 않았는지(끝난 시각은 앱 값이 0이 된 차례). */
    private var lowSpeaking = false
    private var lowKey: String? = null

    /**
     * [outputPriority]: 앱에서 지금 말하는 중인 줄의 등급(1~3, 0 = 쉼, 말 내보내기가 줄을 받은 때부터 끝날 때까지). null이면 음절 수 어림.
     */
    fun tick(nowNs: Long, requests: List<SpeechRequest>, outputPriority: Int? = null): List<Utterance> {
        if (outputPriority == 0 && lowSpeaking) {
            lowEndNs = nowNs
            lowSpeaking = false
        }
        val prevActive = active
        active = requests.map { it.key }.toSet()
        owed.retainAll(active)
        for (r in requests) {
            val s = spoken[r.key] ?: continue
            if (r.key !in prevActive && nowNs - s.tNs >= (cfg.repeatS * 1e9).toLong()) owed.add(r.key)
        }
        val eligible = requests.filter { r ->
            if (r.priority == 3 && !cfg.infoSpeech) return@filter false
            if (nowNs - r.evidenceNs > maxInfoAgeNs) return@filter false
            val s = spoken[r.key] ?: return@filter true
            r.level > s.level || r.key in owed
        }
        val out = ArrayList<Utterance>()
        val busy = if (outputPriority != null) outputPriority > 0 else nowNs < busyUntilNs
        val busyLevel = outputPriority ?: busyPriority
        val urgent = eligible.filter { it.priority == 1 }.maxByOrNull { it.evidenceNs }
        if (urgent != null && !(busy && busyLevel == 1)) {
            out.add(say(nowNs, urgent, interrupted = busy))
            return out
        }
        if (busy) return out
        val next = eligible.filter { it.priority > 1 }.sortedWith(compareBy<SpeechRequest> { it.priority }.thenByDescending { it.evidenceNs }).firstOrNull() ?: return out
        // 간격은 같거나 낮은 우선순위만 막는다(정보 말 뒤에 길 사건이 2 s 밀리지 않게, M50 SP-9)
        if (next.priority >= lowPriority && nowNs < lowEndNs + (cfg.gapS * 1e9).toLong()) return out
        out.add(say(nowNs, next, interrupted = false))
        if (outputPriority != null) lowSpeaking = true else lowEndNs = busyUntilNs
        lowKey = next.key
        lowPriority = next.priority
        return out
    }

    /** 앱이 소리를 내지 못한 말(시작 전 정보 나이 초과·끊김·오류)을 말하지 않은 것으로 되돌린다. 상황이 이어지면 다음 차례에 새 근거로 다시 낸다. */
    fun unsay(key: String) {
        spoken.remove(key)
        owed.remove(key)
        if (key == lowKey) lowSpeaking = false // 소리 나지 않은 말은 간격을 만들지 않는다(앞 말의 끝 시각 그대로)
    }

    private fun say(nowNs: Long, r: SpeechRequest, interrupted: Boolean): Utterance {
        spoken[r.key] = Spoken(nowNs, r.level)
        owed.remove(r.key)
        busyUntilNs = nowNs + (syllables(r.text) / cfg.syllablesPerS * 1e9).toLong()
        busyPriority = r.priority
        return Utterance(nowNs, r.key, r.text, r.priority, (nowNs - r.evidenceNs) / 1e6f, interrupted)
    }

    companion object {
        /** 말 길이를 셀 음절 수: 한글 음절과 숫자 하나를 한 음절로(“3미터” = 3음절). */
        fun syllables(text: String): Int = text.count { it in '가'..'힣' || it.isDigit() }
    }
}
