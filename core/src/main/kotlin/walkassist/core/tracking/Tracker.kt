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
)

/**
 * 물체 추적 (§7.4). 이전 물체와 이번 군집을 **중심점(CENTROID)** 거리 최근접으로 짝짓는다(`track.matchRadiusM`, 탐욕적).
 * 중심점을 쓰는 이유: CORRIDOR_NEAREST는 물체가 통로에 들어오는 순간 튈 수 있어 id가 끊긴다(DECISIONS 2026-09-29).
 * 대표점 3개는 모두 지수 이동 평균(`track.emaAlpha` = 새 값 비중)으로 평활한다(과거 값만 사용).
 * 짝이 없는 이전 물체는 지운다: 군집이 사라졌다는 것은 복셀이 §7.3 규칙으로 지워졌다는 뜻이다.
 */
class Tracker(private val cfg: TrackConfig, private val strategy: RepStrategy) {

    private class Track(val id: Int, var reps: Map<RepStrategy, Vec3>, var nObservations: Int)

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
            val track = if (trackOf[di] >= 0) {
                tracks[trackOf[di]].also { t ->
                    t.reps = t.reps.mapValues { (s, old) -> old + (d.repCandidatesW.getValue(s) - old) * cfg.emaAlpha }
                    t.nObservations++
                }
            } else {
                Track(nextId++, d.repCandidatesW, 1)
            }
            next += track
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
        tracks = next
        return out
    }

    /** 모든 물체를 잊는다(자세 불연속, §7.5). id는 계속 증가한다. */
    fun reset() {
        tracks = emptyList()
    }
}
