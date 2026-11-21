package hearspace.core.audio

import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.HeightClass
import hearspace.core.types.MapHealth
import hearspace.core.types.Obstacle
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.SemanticLabel
import java.util.Locale
import kotlin.math.abs

/** 한 오디오 블록에서 함께 읽은 출력·스냅샷. UI에는 최신 한 개만 전달한다. */
data class ObjectSpeechFrame(val output: GuidanceOutput, val snapshot: ObstacleSnapshot?, val epochNs: Long)

class ObjectSpeechPolicy(private val config: ObjectSpeechConfig, private val maxInfoAgeNs: Long) {
    data class Signature(val obstacleId: Int, val name: String, val direction: String, val urgent: Boolean)
    data class Request(val key: String, val signature: Signature, val text: String, val evidenceNs: Long) {
        val urgent: Boolean get() = signature.urgent
    }
    data class Decision(val cancelKey: String? = null, val request: Request? = null)
    private data class Candidate(val signature: Signature, val text: String, val evidenceNs: Long, val distanceM: Float)
    private data class Spoken(val request: Request, val startedNs: Long)

    private var active: Request? = null
    private var pending: Signature? = null
    private var pendingSinceNs = 0L
    private var epoch: Long? = null
    private var serial = 0L
    private var nextAllowedNs = Long.MIN_VALUE
    private val spoken = HashMap<Int, Spoken>()
    private val retryAfter = HashMap<Signature, Long>()

    fun reset() {
        active = null
        pending = null
        epoch = null
        nextAllowedNs = Long.MIN_VALUE
        spoken.clear()
        retryAfter.clear()
    }

    fun update(frame: ObjectSpeechFrame?, nowNs: Long): Decision {
        if (frame != null && epoch != frame.epochNs) {
            val oldKey = active?.key
            reset()
            epoch = frame.epochNs
            if (oldKey != null) return Decision(cancelKey = oldKey)
        }
        val candidates = candidates(frame, nowNs)
        val current = active
        val currentObservation = candidates.firstOrNull { it.signature.obstacleId == current?.signature?.obstacleId }
        val changed = current != null && currentObservation?.signature != current.signature
        val escalating = currentObservation?.signature?.urgent == true && current?.urgent == false
        val cancelKey = current?.key?.takeIf { changed && !escalating }
        if (cancelKey != null) {
            spoken.entries.removeAll { it.value.request.key == cancelKey }
            active = null
        }
        spoken.entries.removeAll { nowNs - it.value.startedNs > config.repeatMs * 1_000_000L }
        retryAfter.entries.removeAll { nowNs >= it.value }
        val next = candidates
            .filter { nowNs >= retryAfter.getOrDefault(it.signature, Long.MIN_VALUE) }
            .filter { candidate ->
                val last = spoken[candidate.signature.obstacleId]
                last == null || (candidate.signature.urgent && !last.request.urgent) ||
                    (candidate.signature.name != last.request.signature.name ||
                        candidate.signature.direction != last.request.signature.direction) &&
                    nowNs - last.startedNs >= config.gapMs * 1_000_000L
            }
            .sortedWith(compareByDescending<Candidate> { it.signature.urgent }.thenBy { it.distanceM })
            .firstOrNull()
        if (next == null || active?.urgent == true || (active != null && !next.signature.urgent)) {
            pending = null
            return Decision(cancelKey)
        }
        if (pending != next.signature) {
            pending = next.signature
            pendingSinceNs = next.evidenceNs
        }
        val confirmMs = if (next.signature.urgent) config.urgentConfirmMs else config.normalConfirmMs
        if (next.evidenceNs - pendingSinceNs < confirmMs * 1_000_000L ||
            (nowNs < nextAllowedNs && !next.signature.urgent)) return Decision(cancelKey)
        return Decision(cancelKey, Request("object-" + ++serial, next.signature, next.text, next.evidenceNs))
    }

    /** 엔진이 요청을 접수했을 때만 현재 발화로 보관한다. */
    fun onAccepted(request: Request) {
        active = request
        pending = null
    }

    fun onRejected(request: Request, nowNs: Long) {
        retryAfter[request.signature] = nowNs + config.gapMs * 1_000_000L
    }

    fun onStarted(key: String, nowNs: Long) {
        val request = active?.takeIf { it.key == key } ?: return
        spoken[request.signature.obstacleId] = Spoken(request, nowNs)
    }

    fun onFinished(key: String, nowNs: Long, completed: Boolean) {
        val request = active?.takeIf { it.key == key } ?: return
        if (!completed) {
            spoken.entries.removeAll { it.value.request.key == key }
            onRejected(request, nowNs)
        }
        active = null
        nextAllowedNs = nowNs + config.gapMs * 1_000_000L
    }

    private fun candidates(frame: ObjectSpeechFrame?, nowNs: Long): List<Candidate> {
        if (!config.enabled || frame == null) return emptyList()
        val snapshot = frame.snapshot ?: return emptyList()
        val output = frame.output
        if (output.state !in setOf(GuidanceState.NORMAL, GuidanceState.DEGRADED) ||
            snapshot.mapHealth != MapHealth.OK ||
            !fresh(output.tBlockNs, nowNs) || !fresh(snapshot.tCaptureNs, nowNs) ||
            snapshot.tCaptureNs > output.tBlockNs) return emptyList()
        val byId = snapshot.obstacles.associateBy { it.id }
        return output.commands.mapNotNull { command ->
            val obstacle = byId[command.obstacleId] ?: return@mapNotNull null
            if (!command.inCorridor || command.band == Band.SILENT ||
                !command.distanceM.isFinite() || command.distanceM <= 0f ||
                !command.azimuthDeg.isFinite() || command.azimuthDeg !in -180f..180f ||
                !command.infoAgeMs.isFinite() || command.infoAgeMs < 0f ||
                command.infoAgeMs * 1_000_000.0 > maxInfoAgeNs ||
                obstacle.lastSeenNs > snapshot.tCaptureNs || !fresh(obstacle.lastSeenNs, nowNs)) return@mapNotNull null
            candidate(command, obstacle, snapshot, nowNs)
        }
    }

    private fun candidate(command: AudioCmd, obstacle: Obstacle, snapshot: ObstacleSnapshot, nowNs: Long): Candidate {
        val classification = obstacle.classification?.takeIf {
            config.useSemanticLabels && it.observedNs <= snapshot.tCaptureNs && fresh(it.observedNs, nowNs) &&
                it.supportFraction.isFinite() && it.supportFraction >= config.minLabelFraction && it.supportFraction <= 1f
        }
        val labelName = when (classification?.label) {
            SemanticLabel.PERSON -> "사람"
            SemanticLabel.VEHICLE -> "차량"
            SemanticLabel.TREE -> "나무"
            SemanticLabel.STRUCTURE -> "구조물"
            else -> null
        }
        val name = labelName ?: if (obstacle.heightClass == HeightClass.HEAD) "머리 높이 장애물" else "장애물"
        val angle = command.azimuthDeg
        val direction = when {
            abs(angle) <= config.frontHalfAngleDeg -> "정면"
            abs(angle) >= 180f - config.frontHalfAngleDeg -> "뒤쪽"
            angle < -90f -> "왼쪽 뒤"
            angle > 90f -> "오른쪽 뒤"
            angle < 0f -> "왼쪽"
            else -> "오른쪽"
        }
        val urgent = command.band == Band.STOP
        val distance = if (command.distanceM < 0.1f) "0.1미터 이내" else
            String.format(Locale.ROOT, "%.1f", command.distanceM).removeSuffix(".0") + "미터"
        val text = "$direction, $name, $distance." + if (urgent) " 잠시 멈춰 주세요." else ""
        val evidenceNs = minOf(snapshot.tCaptureNs, obstacle.lastSeenNs,
            classification?.observedNs ?: snapshot.tCaptureNs)
        return Candidate(Signature(obstacle.id, name, direction, urgent), text, evidenceNs, command.distanceM)
    }

    private fun fresh(observedNs: Long, nowNs: Long) = nowNs - observedNs in 0L..maxInfoAgeNs
}
