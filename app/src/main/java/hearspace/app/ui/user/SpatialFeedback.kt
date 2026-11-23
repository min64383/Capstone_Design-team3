package hearspace.app.ui.user

import hearspace.core.audio.ObjectSpeechFrame
import hearspace.core.types.Band
import hearspace.core.types.GuidanceState
import hearspace.core.types.HeightClass
import hearspace.core.types.JsonBool
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.MiniJson
import hearspace.core.types.SemanticLabel
import java.util.Locale
import kotlin.math.abs

/** 표시만 바꾼다. 거리 구간·음원 선택·TTS 안내 판단은 기존 core 결과를 따른다. */
internal data class SpatialUiConfig(
    val enabled: Boolean,
    val nearM: Float,
    val middleM: Float,
    val hysteresisM: Float,
    val frontHalfAngleDeg: Float,
    val pulsePeriodMs: Long,
    val frameIntervalMs: Long,
    val colorTransitionMs: Long,
) {
    init {
        require(nearM.isFinite() && nearM > 0f)
        require(middleM.isFinite() && middleM > nearM)
        require(hysteresisM.isFinite() && hysteresisM >= 0f && hysteresisM < middleM - nearM)
        require(frontHalfAngleDeg.isFinite() && frontHalfAngleDeg > 0f && frontHalfAngleDeg < 90f)
        require(pulsePeriodMs > 0 && frameIntervalMs > 0 && frameIntervalMs <= pulsePeriodMs)
        require(colorTransitionMs >= 0)
    }

    companion object {
        fun parse(text: String): SpatialUiConfig {
            val root = MiniJson.parse(text) as? JsonObject ?: error("spatial-ui must be an object")
            fun number(key: String): Double = (root.fields[key] as? JsonNumber)?.value
                ?.takeIf { it.isFinite() } ?: error("Invalid spatial-ui.$key")
            fun millis(key: String): Long = number(key).let {
                require(it >= 0 && it < Long.MAX_VALUE.toDouble() && it == it.toLong().toDouble())
                it.toLong()
            }
            return SpatialUiConfig(
                (root.fields["enabled"] as? JsonBool)?.value ?: error("Invalid spatial-ui.enabled"),
                number("nearM").toFloat(), number("middleM").toFloat(), number("hysteresisM").toFloat(),
                number("frontHalfAngleDeg").toFloat(), millis("pulsePeriodMs"),
                millis("frameIntervalMs"), millis("colorTransitionMs"),
            )
        }
    }
}

enum class DistanceTint { NEAR, MIDDLE, FAR }

data class SpatialCue(
    val obstacleId: Int,
    val azimuthDeg: Float,
    val distanceM: Float,
    val band: Band,
    val height: HeightClass,
    val name: String,
    val direction: String,
    val tint: DistanceTint,
) {
    val heightText: String get() = when (height) {
        HeightClass.HEAD -> "머리 높이"
        HeightClass.BODY -> "몸통 높이"
        HeightClass.FLOOR -> "발 근처"
    }
    val distanceText: String get() = if (distanceM < 0.1f) "0.1m 이내" else
        String.format(Locale.ROOT, "%.1f", distanceM).removeSuffix(".0") + "m"
    val detailText: String get() = "$direction $distanceText\n$name · $heightText"
}

/** 같은 블록의 AudioCmd와 스냅샷을 물체 ID로 대응시켜 화면 데이터로만 변환한다. */
internal class SpatialFeedback(
    private val config: SpatialUiConfig,
    private val maxInfoAgeNs: Long,
    private val minLabelFraction: Float,
) {
    private var epoch: Long? = null
    private var lastId: Int? = null
    private var lastTint: DistanceTint? = null

    fun reset() {
        epoch = null
        lastId = null
        lastTint = null
    }

    fun update(frame: ObjectSpeechFrame?, nowNs: Long): SpatialCue? {
        if (frame == null) return clear()
        if (epoch != frame.epochNs) { reset(); epoch = frame.epochNs }
        val snapshot = frame.snapshot ?: return clear()
        val output = frame.output
        if (output.state != GuidanceState.NORMAL && output.state != GuidanceState.DEGRADED) return clear()
        if (!fresh(output.tBlockNs, nowNs) || !fresh(snapshot.tCaptureNs, nowNs) ||
            snapshot.tCaptureNs > output.tBlockNs || snapshot.tCaptureNs <= frame.epochNs) return clear()
        val byId = snapshot.obstacles.associateBy { it.id }
        val command = output.commands.asSequence().filter { cmd ->
            val obstacle = byId[cmd.obstacleId]
            cmd.band != Band.SILENT && cmd.distanceM.isFinite() && cmd.distanceM > 0f &&
                cmd.azimuthDeg.isFinite() && cmd.azimuthDeg in -180f..180f &&
                cmd.infoAgeMs.isFinite() && cmd.infoAgeMs >= 0f &&
                cmd.infoAgeMs * 1_000_000.0 <= maxInfoAgeNs && obstacle != null &&
                obstacle.lastSeenNs <= snapshot.tCaptureNs && fresh(obstacle.lastSeenNs, nowNs)
        }.sortedWith(compareByDescending<hearspace.core.types.AudioCmd> { it.band == Band.STOP }
            .thenBy { it.distanceM }).firstOrNull() ?: return clear()
        val obstacle = byId.getValue(command.obstacleId)
        val classification = obstacle.classification?.takeIf {
            it.observedNs <= snapshot.tCaptureNs && fresh(it.observedNs, nowNs) &&
                it.supportFraction.isFinite() && it.supportFraction in minLabelFraction..1f
        }
        val name = when (classification?.label) {
            SemanticLabel.PERSON -> "사람"
            SemanticLabel.VEHICLE -> "차량"
            SemanticLabel.TREE -> "나무"
            SemanticLabel.STRUCTURE -> "구조물"
            else -> "장애물"
        }
        val previous = lastTint.takeIf { lastId == command.obstacleId }
        val distance = command.distanceM
        val tint = when {
            command.band == Band.STOP -> DistanceTint.NEAR
            distance <= config.nearM -> DistanceTint.NEAR
            previous == DistanceTint.NEAR && distance <= config.nearM + config.hysteresisM -> DistanceTint.NEAR
            distance <= config.middleM -> DistanceTint.MIDDLE
            previous == DistanceTint.MIDDLE && distance <= config.middleM + config.hysteresisM -> DistanceTint.MIDDLE
            else -> DistanceTint.FAR
        }
        lastId = command.obstacleId
        lastTint = tint
        val angle = command.azimuthDeg
        val direction = when {
            abs(angle) <= config.frontHalfAngleDeg -> "정면"
            abs(angle) >= 180f - config.frontHalfAngleDeg -> "뒤쪽"
            angle < -90f -> "왼쪽 뒤"
            angle > 90f -> "오른쪽 뒤"
            angle < 0f -> "왼쪽"
            else -> "오른쪽"
        }
        return SpatialCue(command.obstacleId, angle, distance, command.band, obstacle.heightClass,
            name, direction, tint)
    }

    private fun clear(): SpatialCue? {
        lastId = null
        lastTint = null
        return null
    }

    private fun fresh(timeNs: Long, nowNs: Long) = nowNs - timeNs in 0L..maxInfoAgeNs
}

/** 실제 onStart가 온 문장만 표시한다. 이전 발화의 늦은 완료 콜백은 무시한다. */
internal class SpeechVisualState {
    data class Line(val id: String, val text: String)
    var line: Line? = null
        private set
    fun started(id: String, text: String) { line = Line(id, text) }
    fun finished(id: String) { if (line?.id == id) line = null }
    fun clear() { line = null }
}
