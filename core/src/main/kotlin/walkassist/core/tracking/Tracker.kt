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
 * 물체 추적 (§7.4, v0.2.10 추적 v2 — 팀원 실험안 fpfilter-v2 기반).
 *
 * 기존 방식의 두 문제를 보완한다.
 * 1) 한 번 군집이 빠지면 id가 즉시 사라지는 문제 -> [TrackConfig.maxMissedUpdates] 동안 내부 track 유지.
 * 2) 한 번 생긴 군집이 바로 장애물로 출력되는 문제 -> [TrackConfig.minConfirmObservations]회 연속 관측 후 출력.
 *    한 번 확인된 track은 잠깐 놓쳤다 다시 잡히면 바로 출력한다(다시 확인하느라 음원이 끊기지 않게).
 *
 * 매칭 자체는 기존 결정대로 CENTROID 거리 최근접 + [TrackConfig.matchRadiusM]을 유지한다.
 * CORRIDOR_NEAREST는 물체가 통로에 들어오는 순간 튈 수 있어 id 매칭에는 쓰지 않는다.
 * 대표점 3개는 모두 EMA([TrackConfig.emaAlpha])로 평활한다.
 *
 * missed 상태의 track은 id 복구를 위해 내부에만 유지하고 Obstacle로 출력하지 않는다.
 * 따라서 오래된 위치가 안내에 재사용되지는 않는다.
 */
class Tracker(private val cfg: TrackConfig, private val strategy: RepStrategy) {

    private class Track(
        val id: Int,
        var reps: Map<RepStrategy, Vec3>,
        var nObservations: Int,
        var consecutiveObservations: Int,
        var missedUpdates: Int,
        /** 한 번이라도 연속 확인을 통과했는지. */
        var confirmed: Boolean = false,
    )

    private var tracks = listOf<Track>()
    private var nextId = 1

    /** 이번 군집들로 물체 목록을 갱신한다. */
    fun update(detections: List<Detection>): List<Obstacle> {
        // 이전 track과 현재 detection 사이에서 matchRadius 안의 후보를 만든다.
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for ((ti, t) in tracks.withIndex()) for ((di, d) in detections.withIndex()) {
            val oldCenter = t.reps.getValue(RepStrategy.CENTROID)
            val newCenter = d.repCandidatesW.getValue(RepStrategy.CENTROID)
            val dist = (oldCenter - newCenter).norm()
            if (dist <= cfg.matchRadiusM) pairs += Triple(dist, ti, di)
        }

        // 가까운 후보부터 탐욕적으로 1:1 매칭한다.
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

        // 현재 detection과 매칭된 track은 갱신하고, 새 detection은 tentative track으로 만든다.
        for ((di, d) in detections.withIndex()) {
            val track = if (trackOf[di] >= 0) {
                tracks[trackOf[di]].also { t ->
                    t.reps = t.reps.mapValues { (s, old) ->
                        old + (d.repCandidatesW.getValue(s) - old) * cfg.emaAlpha
                    }
                    t.nObservations++
                    t.consecutiveObservations++
                    t.missedUpdates = 0
                }
            } else {
                Track(
                    id = nextId++,
                    reps = d.repCandidatesW,
                    nObservations = 1,
                    consecutiveObservations = 1,
                    missedUpdates = 0,
                )
            }

            next += track

            // 연속 관측 횟수가 기준을 넘은(또는 이미 확인된) track만 실제 장애물로 출력한다.
            if (track.consecutiveObservations >= cfg.minConfirmObservations) track.confirmed = true
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

        // 이번 갱신에서 안 잡힌 이전 track도 잠깐 내부 유지한다.
        // 출력은 하지 않으므로 놓친 프레임의 오래된 좌표가 안내에 쓰이지 않는다.
        for ((ti, old) in tracks.withIndex()) {
            if (usedTrack[ti]) continue
            old.missedUpdates++
            old.consecutiveObservations = 0
            if (old.missedUpdates <= cfg.maxMissedUpdates) next += old
        }

        tracks = next
        return out
    }

    /** 모든 물체를 잊는다(자세 불연속, §7.5). id는 계속 증가한다. */
    fun reset() {
        tracks = emptyList()
    }
}
