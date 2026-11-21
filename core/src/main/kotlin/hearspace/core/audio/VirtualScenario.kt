package hearspace.core.audio

import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.MiniJson
import java.util.Locale
import kotlin.math.sin

/** 개발자 화면의 합성 입력 설정. 실제 안내 설정은 변경하지 않는다. */
data class VirtualScenarioConfig(
    val durationS: Float,
    val farM: Float,
    val warnM: Float,
    val stopM: Float,
    val approachStartS: Float,
    val warnReachS: Float,
    val warnHoldEndS: Float,
    val stopReachS: Float,
    val repeatAmplitudeM: Float,
    val repeatAngularSpeedRadps: Float,
    val normalConfirmMs: Long,
    val urgentConfirmMs: Long,
    val tickMs: Long,
    val retryMs: Long,
    val maxFrameAgeMs: Float,
    val bodyHeightDeltaM: Float,
    val headHeightDeltaM: Float,
    val comparisonHoldS: Float,
) {
    init {
        require(listOf(durationS, farM, warnM, stopM, approachStartS, warnReachS,
            warnHoldEndS, stopReachS, repeatAmplitudeM, repeatAngularSpeedRadps,
            maxFrameAgeMs, bodyHeightDeltaM, headHeightDeltaM, comparisonHoldS).all { it.isFinite() })
        require(stopM > 0f && stopM < warnM && warnM < farM)
        require(approachStartS >= 0f && approachStartS < warnReachS &&
            warnReachS < warnHoldEndS && warnHoldEndS < stopReachS && stopReachS < durationS)
        require(repeatAmplitudeM > 0f && repeatAmplitudeM < warnM - stopM &&
            repeatAmplitudeM < farM - warnM && repeatAngularSpeedRadps > 0f)
        require(normalConfirmMs > 0 && urgentConfirmMs > 0 && tickMs > 0 && retryMs > 0)
        require(maxFrameAgeMs > 0f && comparisonHoldS >= durationS)
        require(bodyHeightDeltaM < headHeightDeltaM)
    }

    companion object {
        fun parse(text: String): VirtualScenarioConfig {
            val obj = MiniJson.parse(text) as? JsonObject
                ?: throw IllegalArgumentException("virtual-scenario: expected object")
            fun number(key: String): Double = (obj.fields[key] as? JsonNumber)?.value
                ?.takeIf { it.isFinite() }
                ?: throw IllegalArgumentException("virtual-scenario: invalid $key")
            fun ms(key: String): Long {
                val value = number(key)
                require(value >= 1 && value <= Long.MAX_VALUE / 1_000_000L && value == value.toLong().toDouble()) {
                    "virtual-scenario: invalid $key"
                }
                return value.toLong()
            }
            fun f(key: String) = number(key).toFloat()
            return VirtualScenarioConfig(f("durationS"), f("farM"), f("warnM"), f("stopM"),
                f("approachStartS"), f("warnReachS"), f("warnHoldEndS"), f("stopReachS"),
                f("repeatAmplitudeM"), f("repeatAngularSpeedRadps"),
                ms("normalConfirmMs"), ms("urgentConfirmMs"), ms("tickMs"), ms("retryMs"),
                f("maxFrameAgeMs"), f("bodyHeightDeltaM"), f("headHeightDeltaM"), f("comparisonHoldS"))
        }
    }
}

/** 가상 입력·발화 확인용. 인식 결과를 만들거나 실제 안내 정책을 바꾸지 않는다. */
class VirtualScenario(
    private val cfg: VirtualScenarioConfig,
    private val repeatTest: Boolean,
    private val target: Target,
) {
    enum class Target { BODY, HEAD, OUTSIDE }
    data class Frame(val observedNs: Long, val elapsedS: Float, val distanceM: Float,
        val inCorridor: Boolean, val head: Boolean)
    data class Cue(val level: Int, val text: String, val urgent: Boolean)

    private val accepted = mutableSetOf<Int>()
    private val texts = mutableMapOf<String, Int>()
    private var pendingLevel = 0
    private var pendingSinceNs = 0L
    val durationS: Float get() = cfg.durationS

    /** 계획한 궤적의 현재 위치를 계산한다. 과거·미래 인식 프레임을 보간하지 않는다. */
    fun sample(elapsedS: Float, nowNs: Long): Frame {
        require(elapsedS.isFinite() && elapsedS >= 0f)
        val distanceM = if (repeatTest) {
            cfg.warnM + cfg.repeatAmplitudeM * sin(elapsedS * cfg.repeatAngularSpeedRadps)
        } else when {
            elapsedS < cfg.approachStartS -> cfg.farM
            elapsedS < cfg.warnReachS -> cfg.farM + (cfg.warnM - cfg.farM) *
                (elapsedS - cfg.approachStartS) / (cfg.warnReachS - cfg.approachStartS)
            elapsedS < cfg.warnHoldEndS -> cfg.warnM
            elapsedS < cfg.stopReachS -> cfg.warnM + (cfg.stopM - cfg.warnM) *
                (elapsedS - cfg.warnHoldEndS) / (cfg.stopReachS - cfg.warnHoldEndS)
            else -> cfg.stopM
        }
        return Frame(nowNs, elapsedS, distanceM, target != Target.OUTSIDE, target == Target.HEAD)
    }

    fun nextCue(frame: Frame, nowNs: Long): Cue? {
        val ageNs = nowNs - frame.observedNs
        val fresh = ageNs >= 0L && ageNs <= (cfg.maxFrameAgeMs * 1_000_000.0).toLong()
        val level = when {
            !fresh || !frame.inCorridor || !frame.distanceM.isFinite() || frame.distanceM <= 0f -> 0
            frame.distanceM <= cfg.stopM -> 2
            frame.distanceM <= cfg.warnM -> 1
            else -> 0
        }
        if (level != pendingLevel) {
            pendingLevel = level
            pendingSinceNs = frame.observedNs
        }
        if (level == 0 || level in accepted) return null
        val confirmMs = if (level == 2) cfg.urgentConfirmMs else cfg.normalConfirmMs
        if (frame.observedNs - pendingSinceNs < confirmMs * 1_000_000L) return null
        val name = if (frame.head) "머리 높이 장애물" else "장애물"
        val meters = if (level == 2) cfg.stopM else cfg.warnM
        val distance = String.format(Locale.ROOT, "%.2f", meters).trimEnd('0').trimEnd('.')
        val suffix = if (level == 2) " 잠시 멈춰 주세요." else ""
        return Cue(level, "정면, $name, ${distance}미터.$suffix", level == 2)
    }

    fun onAccepted(cue: Cue) {
        accepted.add(cue.level)
        texts[cue.text] = cue.level
    }

    /** 읽지 못한 접수 요청은 최신 관측으로 다시 시도한다. */
    fun onFailed(text: String) {
        texts.remove(text)?.let { accepted.remove(it) }
    }
}
