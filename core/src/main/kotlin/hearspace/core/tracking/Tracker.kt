package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import hearspace.core.types.HeightClass
import hearspace.core.types.Obstacle
import hearspace.core.types.RepStrategy
import hearspace.core.types.TrackConfig

/** 이번 깊이에서 찾은 군집 하나(추적 전). */
data class Detection(
    val repCandidatesW: Map<RepStrategy, Vec3>,
    val aabbMinW: Vec3,
    val aabbMaxW: Vec3,
    val heightClass: HeightClass,
    val inCorridor: Boolean,
    val confidence: Float,
    val lastSeenNs: Long,
    /** depth sheet 모양이라 오인식이 의심되는 군집([hearspace.core.pipeline.FalsePositiveFilter.matches]). */
    val suspicious: Boolean = false,
    /** 인스턴스 지도(C3b)의 물체 번호, 0 = 없음. 매칭 반경 안에서 같은 번호의 track과 먼저 짝짓는다. */
    val instanceId: Int = 0,
)

/**
 * 물체 추적 (§7.4, v0.2.10 추적 v2 — 팀원 실험안 fpfilter-v2 기반).
 *
 * 기존 방식의 두 문제를 보완한다.
 * 1) 한 번 군집이 빠지면 id가 즉시 사라지는 문제 -> [TrackConfig.maxMissedUpdates] 동안 내부 track 유지.
 * 2) 한 번 생긴 군집이 바로 장애물로 출력되는 문제 -> [TrackConfig.minConfirmObservations]회 연속 관측 후 출력.
 *    한 번 확인된 track은 잠깐 놓쳤다 다시 잡히면 바로 출력한다(다시 확인하느라 음원이 끊기지 않게).
 *    확인 전에는 confidence가 [TrackConfig.minConfirmConfidence] 미만인 관측을 세지 않고(0부터),
 *    이전 중심점에서 [TrackConfig.maxConfirmCentroidJumpM]보다 튄 관측은 새 연속의 첫 관측(1)으로 센다.
 *    한 번이라도 의심 군집([Detection.suspicious])이었던 track은 [TrackConfig.suspiciousConfirmObservations]회가 필요하다.
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
        /** 확인 전에 의심 군집으로 관측된 적이 있는지. */
        var suspicious: Boolean,
        /** 한 번이라도 연속 확인을 통과했는지. */
        var confirmed: Boolean = false,
        /** 마지막으로 짝지은 군집의 물체 번호(C3b). */
        var instanceId: Int = 0,
        /** 마지막으로 짝지은 군집의 상자(겹침 연결, T1). */
        var aabbMin: Vec3 = Vec3.ZERO,
        var aabbMax: Vec3 = Vec3.ZERO,
    )

    private var tracks = listOf<Track>()
    private var nextId = 1
    /** 물체 번호(C3b) → 마지막으로 그 번호였던 track id. 놓쳤다가 같은 번호로 다시 나타나면 그 id를 다시 쓴다(재식별). */
    private val idOfInstance = HashMap<Int, Int>()

    /** 이번 군집들로 물체 목록을 갱신한다. */
    fun update(detections: List<Detection>): List<Obstacle> {
        // 이전 track과 현재 detection 사이에서 matchRadius 안의 후보를 만든다.
        val pairs = ArrayList<Triple<Float, Int, Int>>()
        for ((ti, t) in tracks.withIndex()) for ((di, d) in detections.withIndex()) {
            val oldCenter = t.reps.getValue(RepStrategy.CENTROID)
            val newCenter = d.repCandidatesW.getValue(RepStrategy.CENTROID)
            val dist = (oldCenter - newCenter).norm()
            // 같은 물체 번호(C3b)는 매칭 반경 안에서 먼저 짝짓는다. 반경 밖까지 허용했더니 한 번호가 DBSCAN으로 상자와 1.6 m 떨어진
            // 벽 조각으로 나뉜 장에서 상자 track이 벽 조각과 짝지어져 안내가 엉뚱한 곳을 가리켰다(SC-22)
            if (dist <= cfg.matchRadiusM) pairs += Triple(if (d.instanceId != 0 && d.instanceId == t.instanceId) -1f else dist, ti, di)
            else if (cfg.matchByOverlap && overlapsMajority(t.aabbMin, t.aabbMax, d.aabbMinW, d.aabbMaxW)) pairs += Triple(dist, ti, di)
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
            val confident = d.confidence >= cfg.minConfirmConfidence
            val track = if (trackOf[di] >= 0) {
                tracks[trackOf[di]].also { t ->
                    val jump = (d.repCandidatesW.getValue(RepStrategy.CENTROID) - t.reps.getValue(RepStrategy.CENTROID)).norm()
                    t.consecutiveObservations = when {
                        !confident -> 0
                        jump > cfg.maxConfirmCentroidJumpM -> 1
                        else -> t.consecutiveObservations + 1
                    }
                    t.reps = t.reps.mapValues { (s, old) ->
                        old + (d.repCandidatesW.getValue(s) - old) * cfg.emaAlpha
                    }
                    t.nObservations++
                    t.missedUpdates = 0
                    t.suspicious = t.suspicious || d.suspicious
                    t.instanceId = d.instanceId
                    t.aabbMin = d.aabbMinW
                    t.aabbMax = d.aabbMaxW
                }
            } else {
                // 재식별(C3b): 같은 물체 번호의 예전 id가 살아 있는 track에 없으면 다시 쓴다. 물체가 하나뿐인 세션에서도 잠깐 놓쳤다가
                // 다시 잡을 때마다 새 id가 붙어 id 전환이 생겼다(S02 회차 합 7회)
                val reuse = d.instanceId.takeIf { it != 0 }?.let { idOfInstance[it] }?.takeIf { id -> tracks.none { it.id == id } && next.none { it.id == id } }
                Track(
                    id = reuse ?: nextId++,
                    reps = d.repCandidatesW,
                    nObservations = 1,
                    consecutiveObservations = if (confident) 1 else 0,
                    missedUpdates = 0,
                    suspicious = d.suspicious,
                    instanceId = d.instanceId,
                    aabbMin = d.aabbMinW,
                    aabbMax = d.aabbMaxW,
                )
            }

            next += track

            // 연속 관측 횟수가 기준을 넘은(또는 이미 확인된) track만 실제 장애물로 출력한다.
            val required = if (track.suspicious) cfg.suspiciousConfirmObservations else cfg.minConfirmObservations
            if (track.consecutiveObservations >= required) track.confirmed = true
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
        for (t in next) if (t.instanceId != 0) idOfInstance[t.instanceId] = t.id
        return out
    }

    /** 위에서 본 두 상자(x, z)가 작은 쪽 면적의 과반 넘게 겹치는지. */
    private fun overlapsMajority(aMin: Vec3, aMax: Vec3, bMin: Vec3, bMax: Vec3): Boolean {
        val ox = minOf(aMax.x, bMax.x) - maxOf(aMin.x, bMin.x)
        val oz = minOf(aMax.z, bMax.z) - maxOf(aMin.z, bMin.z)
        if (ox <= 0f || oz <= 0f) return false
        val areaA = (aMax.x - aMin.x) * (aMax.z - aMin.z)
        val areaB = (bMax.x - bMin.x) * (bMax.z - bMin.z)
        return ox * oz * 2 > minOf(areaA, areaB)
    }

    /** 모든 물체를 잊는다(자세 불연속, §7.5). id는 계속 증가한다. */
    fun reset() {
        tracks = emptyList()
        idOfInstance.clear()
    }
}
