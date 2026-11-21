package hearspace.core.situation

import hearspace.core.audio.ObjectSpeechPolicy
import hearspace.core.types.SituationConfig

/** Lower ordinal speaks first. An active line keeps its turn against another of equal priority. */
enum class SpeechPriority { UNAVAILABLE, IMMEDIATE, OBSTACLE, ROUTE, STATUS, INFORMATION }

fun <T> chooseSpeech(
    active: SpeechPriority?,
    candidates: List<T>,
    priority: (T) -> SpeechPriority,
): T? = candidates.minByOrNull(priority)?.takeIf { active == null || priority(it) < active }

data class SituationSpeechFrame(val epochNs: Long, val requests: List<SpeechRequest>)

data class ObstacleSpeechLine(val text: String, val evidenceNs: Long)

fun obstacleSpeechLine(
    obstacle: ObjectSpeechPolicy.Request,
    walking: List<SpeechRequest>,
): ObstacleSpeechLine {
    val onBlock = walking.filter { it.onBlockObstacleId == obstacle.signature.obstacleId }
        .maxByOrNull { it.evidenceNs }
    val name = obstacle.signature.name
    val description = if (onBlock == null) name else "점자블록 위 $name"
    val text = "${obstacle.signature.direction}, $description, ${obstacle.distanceText}."
    return ObstacleSpeechLine(
        if (obstacle.urgent) "멈추세요. $text" else text,
        minOf(obstacle.evidenceNs, onBlock?.evidenceNs ?: obstacle.evidenceNs),
    )
}

/** A candidate can wait only while it is present in the latest valid observations. */
class LiveSituationSpeech(config: SituationConfig, private val maxInfoAgeNs: Long) {
    private data class Spoken(val atNs: Long, val level: Int)

    private val infoSpeech = config.infoSpeech
    private val repeatNs = (config.repeatS * 1e9).toLong()
    private val gapNs = (config.gapS * 1e9).toLong()
    private val spoken = HashMap<String, Spoken>()
    private val repeatDue = HashSet<String>()
    private var present = emptySet<String>()
    private var retryAfterNs = Long.MIN_VALUE
    private var nextRegularNs = Long.MIN_VALUE
    private var epochNs: Long? = null

    fun reset() {
        spoken.clear()
        repeatDue.clear()
        present = emptySet()
        retryAfterNs = Long.MIN_VALUE
        nextRegularNs = Long.MIN_VALUE
        epochNs = null
    }

    fun observe(frame: SituationSpeechFrame?, epoch: Long?, nowNs: Long): List<SpeechRequest> {
        if (epoch != null && epoch != epochNs) {
            reset()
            epochNs = epoch
        }
        val current = frame?.takeIf { epoch != null && it.epochNs == epoch }?.requests.orEmpty()
            .filter { nowNs - it.evidenceNs in 0L..maxInfoAgeNs && (infoSpeech || it.priority != 3) }
        val keys = current.mapTo(HashSet()) { it.key }
        repeatDue.retainAll(keys)
        spoken.entries.removeAll { it.key !in keys && nowNs - it.value.atNs >= repeatNs }
        for (key in keys) {
            val last = spoken[key] ?: continue
            if (key !in present && nowNs - last.atNs >= repeatNs) repeatDue += key
        }
        present = keys
        return current
    }

    fun next(current: List<SpeechRequest>, objectSpeechEnabled: Boolean, nowNs: Long): SpeechRequest? {
        if (nowNs < retryAfterNs) return null
        return current.asSequence()
            .filter { !objectSpeechEnabled || it.onBlockObstacleId == null }
            .filter { it.priority == 1 || nowNs >= nextRegularNs }
            .filter { request ->
                val last = spoken[request.key]
                last == null || request.level > last.level || request.key in repeatDue
            }
            .minWithOrNull(compareBy<SpeechRequest> { it.priority }.thenByDescending { it.evidenceNs })
    }

    fun finished(request: SpeechRequest, nowNs: Long, completed: Boolean, failed: Boolean) {
        if (completed) {
            spoken[request.key] = Spoken(nowNs, request.level)
            repeatDue.remove(request.key)
            nextRegularNs = nowNs + gapNs
        } else if (failed) {
            retryAfterNs = nowNs + gapNs
        }
    }
}
