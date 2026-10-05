package walkassist.core.tracking

import walkassist.core.geometry.Vec3
import walkassist.core.types.HeightClass
import walkassist.core.types.Obstacle
import walkassist.core.types.RepStrategy
import walkassist.core.types.TrackConfig

/** 이번 깊이에서 찾은 군집 하나(추적 전). */
data class Detection(
    val repCandidatesW: Map<RepStrategy, Vec3>,
    val aabbMinW: Vec3,
    val aabbMaxW: Vec3,
    val heightClass: HeightClass,
    val inCorridor: Boolean,
    val confidence: Float,
    val lastSeenNs: Long,
    /** 큰 depth-sheet 형태 등 오인식 가능성이 높은 군집. 삭제하지 않고 더 오래 확인한다. */
    val suspicious: Boolean = false,
)

/**
 * 물체 추적. 중심점(CENTROID) 거리 최근접 매칭과 EMA는 기존 동작을 유지한다.
 * 다만 새 군집을 즉시 출력하지 않고 시간적으로 안정된 관측이 반복된 뒤에만 confirmed 상태로 만든다.
 * confirmed 물체를 잠깐 놓치면 ID만 보존하고(최대 maxMissedUpdates), 누락 중에는 오래된 위치를 출력하지 않는다.
 */
class Tracker(private val cfg: TrackConfig, private val strategy: RepStrategy) {

    private class Track(
        val id: Int,
        var reps: Map<RepStrategy, Vec3>,
        var nObservations: Int,
        var consecutiveObservations: Int,
        var missedUpdates: Int,
        var confirmed: Boolean,
    )

    private var tracks = listOf<Track>()
    private var nextId = 1

    /** 이번 군집들로 물체 목록을 갱신한다. */
    fun update(detections: List<Detection>): List<Obstacle> {
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for ((ti, t) in tracks.withIndex()) for ((di, d) in detections.withIndex()) {
            val dist = (t.reps.getValue(RepStrategy.CENTROID) - d.repCandidatesW.getValue(RepStrategy.CENTROID)).norm()
            if (dist <= cfg.matchRadiusM) pairs += Triple(dist, ti, di)
        }
        pairs.sortWith(compareBy({ it.first }, { it.second }, { it.third }))
        val trackOf = IntArray(detections.size) { -1 }
        val usedTrack = BooleanArray(tracks.size)
        for ((_, ti, di) in pairs) {
            if (usedTrack[ti] || trackOf[di] >= 0) continue
            usedTrack[ti] = true
            trackOf[di] = ti
        }

        val next = ArrayList<Track>()
        val out = ArrayList<Obstacle>()
        for ((di, d) in detections.withIndex()) {
            val oldIndex = trackOf[di]
            val track = if (oldIndex >= 0) {
                tracks[oldIndex].also { t ->
                    val previousCenter = t.reps.getValue(RepStrategy.CENTROID)
                    val newCenter = d.repCandidatesW.getValue(RepStrategy.CENTROID)
                    val stable = d.confidence >= cfg.minConfirmConfidence &&
                        (newCenter - previousCenter).norm() <= cfg.maxConfirmCentroidJumpM

                    t.reps = t.reps.mapValues { (s, old) ->
                        old + (d.repCandidatesW.getValue(s) - old) * cfg.emaAlpha
                    }
                    t.nObservations++
                    t.missedUpdates = 0
                    if (!t.confirmed) {
                        t.consecutiveObservations = if (stable) t.consecutiveObservations + 1 else 1
                    }
                }
            } else {
                val stableFirst = d.confidence >= cfg.minConfirmConfidence
                Track(
                    id = nextId++,
                    reps = d.repCandidatesW,
                    nObservations = 1,
                    consecutiveObservations = if (stableFirst) 1 else 0,
                    missedUpdates = 0,
                    confirmed = false,
                )
            }

            val required = if (d.suspicious) cfg.suspiciousConfirmObservations else cfg.minConfirmObservations
            if (!track.confirmed && track.consecutiveObservations >= required) track.confirmed = true
            next += track

            if (track.confirmed) {
                out += Obstacle(
                    id = track.id,
                    repPointW = track.reps.getValue(strategy),
                    repCandidatesW = track.reps,
                    aabbMinW = d.aabbMinW,
                    aabbMaxW = d.aabbMaxW,
                    heightClass = d.heightClass,
                    inCorridor = d.inCorridor,
                    confidence = d.confidence,
                    lastSeenNs = d.lastSeenNs,
                    nObservations = track.nObservations,
                )
            }
        }

        // 이번 깊이에서 놓친 기존 물체는 ID만 잠시 보존한다. 오래된 좌표는 출력하지 않는다.
        for ((ti, old) in tracks.withIndex()) {
            if (usedTrack[ti]) continue
            old.missedUpdates++
            old.consecutiveObservations = 0
            if (old.missedUpdates <= cfg.maxMissedUpdates) next += old
        }

        tracks = next
        return out
    }

    /** 모든 물체를 잊는다(자세 불연속). id는 계속 증가한다. */
    fun reset() {
        tracks = emptyList()
    }
}
