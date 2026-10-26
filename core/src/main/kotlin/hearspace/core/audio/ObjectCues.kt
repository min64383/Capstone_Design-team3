package hearspace.core.audio

import hearspace.core.types.Band
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.ObstacleSnapshot
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/** Offline audition settings, loaded by the viewer; no app defaults are changed. */
data class ObjectCueTone(val startHz: Double, val endHz: Double, val durationMs: Int,
                         val amplitude: Double, val fadeInMs: Int, val fadeOutMs: Int) {
    fun samples(sampleRateHz: Int): FloatArray {
        require(sampleRateHz > 0 && durationMs in 1..5000)
        require(startHz.isFinite() && endHz.isFinite() && startHz > 0 && endHz > 0)
        require(maxOf(startHz, endHz) < sampleRateHz / 2.0)
        require(amplitude.isFinite() && amplitude > 0 && amplitude <= 1)
        require(fadeInMs > 0 && fadeOutMs > 0 && fadeInMs + fadeOutMs <= durationMs)
        val n = (durationMs * sampleRateHz / 1000.0).roundToInt()
        val attack = (fadeInMs * sampleRateHz / 1000.0).roundToInt()
        val release = (fadeOutMs * sampleRateHz / 1000.0).roundToInt()
        require(n >= 2 && attack >= 2 && release >= 2)
        val slopeHzPerSec = (endHz - startHz) / ((n - 1).toDouble() / sampleRateHz)
        return FloatArray(n) { i ->
            val tSec = i.toDouble() / sampleRateHz
            val envelope = (i.toDouble() / (attack - 1)).coerceIn(0.0, 1.0) *
                ((n - 1 - i).toDouble() / (release - 1)).coerceIn(0.0, 1.0)
            (amplitude * envelope * sin(2 * PI * (startHz * tSec + 0.5 * slopeHzPerSec * tSec * tSec))).toFloat()
        }
    }
}

data class ObjectCueSettings(val appearHoldMs: Long, val lostHoldMs: Long, val cooldownMs: Long,
                             val alertGuardMs: Long, val gain: Float,
                             val appear: ObjectCueTone, val lost: ObjectCueTone) {
    init {
        require(appearHoldMs in 0..60000 && lostHoldMs in 0..60000)
        require(cooldownMs in 0..60000 && alertGuardMs in 0..60000)
        require(gain.isFinite() && gain in 0f..1f)
    }
}

enum class ObjectCueKind { APPEAR, LOST }
data class ObjectCueEvent(val tNs: Long, val obstacleId: Int, val kind: ObjectCueKind, val disposition: String)

/**
 * Uses fresh tracked snapshot IDs, not the maxSources-limited audio commands.
 * LOST means tracking loss, never clearance. Tones are non-spatial event notices;
 * no expired position is used. State and PCM belong to one replay run.
 */
class ObjectCueRenderer(private val settings: ObjectCueSettings, sampleRateHz: Int,
                        private val maxInfoAgeMs: Float) {
    private class Track(var sinceNs: Long, var announced: Boolean = false, var missingNs: Long? = null)
    private val tracks = linkedMapOf<Int, Track>()
    private val appear = settings.appear.samples(sampleRateHz)
    private val lost = settings.lost.samples(sampleRateHz)
    private var lastSnapshotNs: Long? = null
    private var lastBlockNs: Long? = null
    private var hadValid = false
    private var recovering = false
    private var lastPlayedNs: Long? = null
    private var alertUntilNs = Long.MIN_VALUE
    private var active: FloatArray? = null
    private var position = 0
    private var activeAppearanceId: Int? = null

    private fun fresh(nowNs: Long, observedNs: Long): Boolean =
        observedNs <= nowNs && (nowNs - observedNs) / 1e6 <= maxInfoAgeMs

    /** Mixes into the already rendered stereo block. No queued or delayed notices. */
    fun mix(tNs: Long, snapshot: ObstacleSnapshot?, g: GuidanceOutput, out: FloatArray): List<ObjectCueEvent> {
        require(out.size % 2 == 0)
        require(lastBlockNs == null || tNs >= lastBlockNs!!) { "Create a new renderer for each replay" }
        lastBlockNs = tNs
        val valid = (g.state == GuidanceState.NORMAL || g.state == GuidanceState.DEGRADED) &&
            snapshot != null && fresh(tNs, snapshot.tCaptureNs)
        if (!valid) {
            tracks.clear(); active = null; lastSnapshotNs = null
            recovering = hadValid
            return emptyList()
        }
        snapshot!!
        if (g.alert != null) alertUntilNs = tNs + settings.alertGuardMs * 1_000_000
        val stop = g.commands.any { it.inCorridor && it.band == Band.STOP }
        val blocked = stop || tNs < alertUntilNs
        if (blocked || activeAppearanceId?.let { id ->
                snapshot.obstacles.none { it.id == id && fresh(tNs, it.lastSeenNs) }
            } == true) active = null
        val candidates = ArrayList<Pair<Int, ObjectCueKind>>()
        if (lastSnapshotNs != snapshot.tCaptureNs) {
            val observedNs = snapshot.tCaptureNs
            val ids = snapshot.obstacles.filter { fresh(tNs, it.lastSeenNs) }.map { it.id }.toSet()
            if (recovering) {
                ids.sorted().forEach { tracks[it] = Track(observedNs, announced = true) }
                recovering = false
            } else {
                for (id in ids.sorted()) {
                    val track = tracks.getOrPut(id) { Track(observedNs) }
                    if (track.missingNs != null && !track.announced) track.sinceNs = observedNs
                    track.missingNs = null
                    if (!track.announced && observedNs - track.sinceNs >= settings.appearHoldMs * 1_000_000) {
                        track.announced = true
                        candidates += id to ObjectCueKind.APPEAR
                    }
                }
                val iterator = tracks.iterator()
                while (iterator.hasNext()) {
                    val (id, track) = iterator.next()
                    if (id in ids) continue
                    if (!track.announced) { iterator.remove(); continue }
                    val missingNs = track.missingNs ?: observedNs.also { track.missingNs = it }
                    if (observedNs - missingNs >= settings.lostHoldMs * 1_000_000) {
                        candidates += id to ObjectCueKind.LOST
                        iterator.remove()
                    }
                }
            }
            lastSnapshotNs = observedNs
            hadValid = true
        }
        // At most one notice, prefer a new object. Others are logged, not replayed late.
        val events = candidates.sortedWith(compareBy({ it.second.ordinal }, { it.first })).map { (id, kind) ->
            val reason = when {
                stop -> "SUPPRESSED_STOP"
                tNs < alertUntilNs -> "SUPPRESSED_ALERT"
                active != null -> "SUPPRESSED_BUSY"
                lastPlayedNs?.let { tNs - it < settings.cooldownMs * 1_000_000 } == true -> "SUPPRESSED_COOLDOWN"
                else -> "PLAYED"
            }
            if (reason == "PLAYED") {
                active = if (kind == ObjectCueKind.APPEAR) appear else lost
                position = 0
                activeAppearanceId = if (kind == ObjectCueKind.APPEAR) id else null
                lastPlayedNs = tNs
            }
            ObjectCueEvent(tNs, id, kind, reason)
        }
        val sound = active ?: return events
        for (frame in 0 until out.size / 2) {
            if (position >= sound.size) { active = null; break }
            val cue = sound[position++] * settings.gain
            for (ch in 0..1) {
                val i = frame * 2 + ch
                // Preserve base audio; fit the event into remaining sample headroom.
                out[i] += cue * (1f - abs(out[i])).coerceAtLeast(0f)
            }
        }
        if (position >= sound.size) active = null
        return events
    }
}
