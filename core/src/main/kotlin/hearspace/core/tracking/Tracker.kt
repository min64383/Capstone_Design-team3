package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import hearspace.core.types.HeightClass
import hearspace.core.types.MapConfig
import hearspace.core.types.MeasureWeight
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
 *
 * [TrackConfig.reidLost]면(M22 R) 출력된 적 있는 track이 지워질 때 id·마지막 상자·시각을 [reidMemoryNs] 동안 기억하고, 새 track의 상자가
 * 그 상자와 과반 겹치면 그 id를 쓴다(가장 최근에 놓친 것 먼저). 잇는 것은 id뿐이고 위치·평활·확인 횟수는 새로 시작한다.
 * [reidMemoryNs]는 `map.maxUnseenS`(지도가 그 물체의 칸을 잊는 시간)다.
 */
class Tracker(
    private val cfg: TrackConfig,
    private val strategy: RepStrategy,
    private val reidMemoryNs: Long = 0L,
    /** 물체 좌표 HITS 칸의 산술(M48, `track.motion` KALMAN일 때만 씀). */
    private val mapCfg: MapConfig? = null,
    /** 칼만 측정·후보 묶음의 최소 칸 수(`cluster.minSamples`). */
    private val minSamples: Int = 5,
) {

    private class Track(
        /** 후보 track(M48)은 음수 번호를 쓰고, 움직이는 track이 되어 출력될 때 정식 번호를 받는다(움직이는 물체가 없으면 번호가 기준선과 같다). */
        var id: Int,
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
        /** 움직임 상태(M48). 끈 설정에서는 null. */
        var motion: MotionState? = null,
    )

    /** 마지막 [update]의 움직임 기록(M48, `motion.csv`). */
    var lastMotionDebug: List<MotionDebug> = emptyList()
        private set

    /** 시각 [tNs]의 움직이는 track 추적 창(정지 지도에 넣지 않을 점의 상자, M48). 지난 장의 상태로 정한다. */
    fun movingGates(tNs: Long): List<Pair<Vec3, Vec3>> =
        tracks.mapNotNull { t -> t.motion?.takeIf { it.moving }?.gate(tNs, cfg.matchRadiusM) }

    /** 지워진 track(M22 R): id, 마지막 상자, 지워진 시각. */
    private class Lost(val id: Int, val aabbMin: Vec3, val aabbMax: Vec3, val tNs: Long)

    private var tracks = listOf<Track>()
    private val lost = ArrayList<Lost>()
    private var nextId = 1
    private var nextCandidateId = -1
    /** 물체 번호(C3b) → 마지막으로 그 번호였던 track id. 놓쳤다가 같은 번호로 다시 나타나면 그 id를 다시 쓴다(재식별). */
    private val idOfInstance = HashMap<Int, Int>()

    /**
     * 이번 군집들로 물체 목록을 갱신한다. [tNs]는 이번 깊이의 시각(놓친 track 기억, M22 R).
     * [frame]이 있으면(M48 `track.motion` KALMAN) 정지·움직임 분해를 함께 한다: 움직이는 track은 지도 군집과 짝짓지 않고 이번 장 칸으로
     * 갱신하며, 지도가 점유로 만들지 못한 칸 묶음에서 후보 track을 만든다. null이면 기준선과 같다.
     */
    fun update(detections: List<Detection>, tNs: Long = detections.maxOfOrNull { it.lastSeenNs } ?: 0L, frame: MotionFrame? = null): List<Obstacle> {
        if (cfg.reidLost) lost.removeAll { tNs - it.tNs > reidMemoryNs }
        // 움직이는 track(M48)은 지도 군집과 짝짓지 않는다: 그 점은 지도에 들어가지 않는다. 그 창 안의 군집(판정 전에 생긴 칸)은 흡수한다
        // 후보 track도 짝짓기에서 뺀다: 따로 세운 가설이라 정지 track이 받을 군집을 가져가면 움직이는 물체가 없어도 정지 추적이 바뀐다
        val movingTracks = tracks.filter { it.motion?.let { m -> m.moving || m.candidate } == true }
        if (movingTracks.isNotEmpty()) tracks = tracks.filter { it.motion?.let { m -> m.moving || m.candidate } != true }
        @Suppress("NAME_SHADOWING")
        val detections = if (movingTracks.none { it.motion!!.moving }) detections else detections.filter { d ->
            val c = d.repCandidatesW.getValue(RepStrategy.CENTROID)
            movingTracks.none { t -> t.motion!!.moving && t.motion!!.gate(tNs, cfg.matchRadiusM).let { (lo, hi) -> inBox(c, lo, hi) } }
        }
        // 지도가 군집으로 잡은 자리의 후보는 지운다(정지 지도가 맡음). 움직이는 track 창 안 군집은 위에서 흡수했다
        val candidatesTaken = movingTracks.filter { t ->
            t.motion!!.candidate && detections.any { d -> t.motion!!.gate(tNs, cfg.matchRadiusM).let { (lo, hi) -> inBox(d.repCandidatesW.getValue(RepStrategy.CENTROID), lo, hi) } }
        }.toSet()
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
                fun free(id: Int) = tracks.none { it.id == id } && next.none { it.id == id }
                val reuse = d.instanceId.takeIf { it != 0 }?.let { idOfInstance[it] }?.takeIf(::free)
                    ?: if (!cfg.reidLost) null else lost.filter { free(it.id) && overlapsMajority(it.aabbMin, it.aabbMax, d.aabbMinW, d.aabbMaxW) }
                        .maxByOrNull { it.tNs }?.also { l -> lost.remove(l) }?.id
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
            else if (cfg.reidLost && old.confirmed) lost += Lost(old.id, old.aabbMin, old.aabbMax, tNs)
        }

        for (t in next) if (t.instanceId != 0) idOfInstance[t.instanceId] = t.id
        tracks = if (movingTracks.isEmpty()) next else next + movingTracks.filter { it !in candidatesTaken } // 바닥을 잃은 장(frame 없음)에도 둔다
        if (frame == null) return out
        return motionStep(frame, out)
    }

    /**
     * 정지·움직임 분해(M48 설계 3.1·3.5절). 짝짓기 뒤에 부른다. 모든 track의 칼만을 이번 장 칸으로 갱신하고, 움직이는 track·후보의
     * 물체 좌표 HITS 칸을 쌓고, 움직임 판정·넘겨주기·후보 생성을 한다. [out]은 짝짓기가 낸 정지 track 출력.
     */
    private fun motionStep(f: MotionFrame, out: List<Obstacle>): List<Obstacle> {
        val mc = mapCfg ?: error("track.motion KALMAN needs MapConfig")
        val tNs = f.tNs
        // 필터 시각(M52): 평활 깊이 뒤처짐만큼 앞당긴 측정 시각. 필터의 갱신·예측·창은 이 시각으로, 지도·출력·기록은 촬영 시각으로 한다
        val tf = tNs - f.measLagNs
        // 측정점 가중(M52 POINTS): 칸마다 이번 장 점 수
        val wt = if (cfg.measureWeight == MeasureWeight.POINTS) f.pointCount else null
        fun newMotion(birthNs: Long, candidate: Boolean) =
            MotionState(cfg.accelSigmaMps2, birthNs, candidate, cfg.initMaxSpeedMps, cfg.initGateSigmas, mc.minHits)
        for (t in tracks) if (t.motion == null) {
            t.motion = newMotion(tNs, candidate = false).also { it.boxMin = t.aabbMin; it.boxMax = t.aabbMax }
        }
        val removed = HashSet<Track>()
        // 움직이는 track·후보의 측정 = 창에 걸린 군집 전체(창은 군집을 고르는 데만 쓴다). 창 안 칸만 쓰면 측정점이 창 쪽(예측 쪽)으로 끌려
        // 속도가 작게 나오고 창이 물체보다 점점 뒤처졌다(합성 SM-3: 1.0 m/s를 0.55~0.7 m/s로)
        val clustersAll by lazy { f.clusterXZ(f.allCells) }
        for (t in tracks) {
            val m = t.motion!!
            // 추적 창: 움직이는 track·후보는 측정 상자를 예측만큼 옮긴 것, 지도 track은 이번 지도 군집 상자(지도 앞면이 뒤처지므로 여유로 덮음)
            if (!m.moving && !m.candidate) { m.boxMin = t.aabbMin; m.boxMax = t.aabbMax }
            val (lo, hi) = m.gate(tf, cfg.matchRadiusM)
            // 측정은 통로 밖 칸도 쓴다: 통로만 보면 옆으로 비켜 간 물체를 놓친 것으로 보고 마지막 자리를 정지 지도에 넘겨 유령 칸이 생겼다(SM-6)
            val meas = if (m.moving || m.candidate) clustersAll.filter { cl -> cl.count { inBox(it, lo, hi) } >= minSamples }.flatten()
            else f.allCells.filter { inBox(it, lo, hi) }
            m.nMeasCells = meas.size
            // 칼만 측정 = 이번 장 창 안 칸의 중심점(가장 앞 칸 기준 대표점은 장마다 칸이 바뀌어 크게 뛴다, M48 값 재기). 예측에서 벗어난 측정은 버린다
            val rep = if (meas.size >= minSamples) nearSurfacePoint(meas, f.cameraW, wt) else null
            val restarts = m.filter.restarts
            if (rep != null && !m.filter.update(tf, rep, f.sigmaAt(rep), cfg.validationChi2)) {
                m.missed++
                m.nMeasCells = -meas.size // 버린 측정(기록용 음수)
            } else if (rep != null) {
                m.lastMeasW = rep
                m.lastMeasNs = tNs
                // 움직이는 track·후보의 창 = 측정한 군집의 상자(+ 여유는 gate가 붙인다). 창 안 칸 전체의 상자로 두면 장마다 여유만큼
                // 넓어졌다(T 150338 칼만 속도 3 m/s). 군집 상자는 장마다 다시 재므로 쌓여 커지지 않는다
                m.missed = 0
                if (m.moving || m.candidate) {
                    m.boxMin = Vec3(meas.minOf { it.x }, meas.minOf { it.y }, meas.minOf { it.z })
                    m.boxMax = Vec3(meas.maxOf { it.x }, meas.maxOf { it.y }, meas.maxOf { it.z })
                }
                // 시작 게이트가 필터를 다시 시작했으면(다른 대상과 짝지은 측정) 표·점수도 처음부터 모은다(M52): 남은 표로 바로 출력되면
                // 막 시작한 필터의 큰 속도 불확실성으로 TTC STOP이 났다(M48 SM-3과 같은 까닭)
                if (m.filter.restarts != restarts) { m.votes = 0f; m.score = 0f }
                // 물체 단위 HITS: 측정된 장마다 표·점수(정지 지도와 같은 거리 가중)
                val w = f.weightAt(rep)
                m.votes += w
                m.score = kotlin.math.min(1f, m.score + mc.hitGain * w)
                m.lastCells = meas
                m.lastCellsNs = tNs
            } else {
                m.filter.predictTo(tf)
                m.missed++
                // 예측 자리가 빈 곳으로 보이면 점수를 깎는다(시야 밖·깊이 무효면 그대로: 시야 밖 보존)
                val p = m.filter.predictedAt(tNs)?.first ?: m.lastMeasW
                if ((m.moving || m.candidate) && p != null) {
                    val probe = Vec3(p.x, (m.boxMin.y + m.boxMax.y) / 2, p.z)
                    if (f.seenFree(probe)) m.score -= mc.decayPerObservation
                }
            }
            val v = m.filter.velocityW
            m.lastChi2 = m.filter.velocityChi2
            val isMoving = m.filter.ready && v != null && if (f.movingOracle != null) {
                (if (meas.isEmpty()) null else f.movingOracle.invoke(tNs, meas)) ?: m.moving // 정답(라벨처럼): 모르면 지금 상태 유지
            } else v.norm() > cfg.minMovingMps && m.lastChi2 > cfg.movingChi2
            val origin = m.origin()
            val occupied = m.occupied(mc.minHits, mc.minScore)
            when {
                !m.moving && isMoving && m.candidate -> if (occupied) {
                    // 후보 → 움직이는 track: 판정 전에 같은 점이 정지 지도에도 들어갔으므로 이 후보가 생긴 뒤 생긴 창 안 칸을 지도에서 뺀다
                    f.take(lo, hi, m.birthNs)
                    m.moving = true
                    m.candidate = false
                    t.confirmed = true
                    t.id = nextId++
                }
                !m.moving && isMoving && origin != null -> {
                    // 지도 track → 움직이는 track: track이 생긴 뒤 생긴 창 안 칸(뒤처진 흔적)을 지도에서 뺀다(그 앞의 칸은 정지 물체일 수 있어 둔다).
                    // 표는 넘겨받지 않는다: 이번 장 측정으로 6표를 모아야 출력한다(그 사이 칼만 속도가 수렴한다. 넘겨받으면 막 시작한 필터의
                    // 큰 속도 불확실성 때문에 TTC 하한이 작아져 가로지르는 사람에게 STOP을 냈다, 합성 SM-3)
                    f.take(lo, hi, m.birthNs)
                    m.moving = true
                }
                m.moving && !isMoving && m.filter.ready && origin != null -> {
                    // 섰다: 물체 모양(마지막 측정 칸)을 점유로 정지 지도에 넣는다. 다음 장부터 창 안 점도 지도로 간다
                    if (m.occupied(mc.minHits, mc.minScore)) m.cellsAt(tNs).forEach { f.seed(hearspace.core.mapping.VoxelMap.CellEvidence(it, m.votes, m.score)) }
                    t.motion = newMotion(m.birthNs, candidate = false).also { n ->
                        n.boxMin = m.boxMin
                        n.boxMax = m.boxMax
                        m.lastMeasW?.let { n.filter.update(m.lastMeasNs - f.measLagNs, it, f.sigmaAt(it)); n.lastMeasW = it; n.lastMeasNs = m.lastMeasNs }
                    }
                    t.aabbMin = m.boxMin
                    t.aabbMax = m.boxMax
                    t.missedUpdates = 0
                }
            }
            val mm = t.motion!!
            when {
                mm.moving && mm.lastMeasNs != Long.MIN_VALUE && tNs - mm.lastMeasNs > (mc.maxUnseenS * 1e9).toLong() -> removed += t
                // 시야 밖으로 나감: 물체는 시야 밖이라 점수를 깎지 않고(시야 밖 보존) 움직임 가설대로 예측해 옮긴다. 지도의 시야 밖 칸처럼
                // `map.maxUnseenS` 동안 둔다. 처음 설계(마지막 자리를 정지 지도로 넘김)는 옆으로 비켜 시야를 벗어난 사람을 통로 가장자리의
                // 유령 칸으로 남겼다(합성 SM-6)
                mm.moving && mm.score <= 0f -> removed += t // 시야 안에서 빈 곳으로 보여 지워짐
                mm.candidate && mm.missed > cfg.maxMissedUpdates -> removed += t
                mm.candidate && !isMoving && mm.occupied(mc.minHits, mc.minScore) -> removed += t // 6표가 모였는데 정지: 정지 지도가 맡음
            }
        }
        // 같은 물체를 따르는 움직이는 track·후보가 둘이면(한 사람이 두 묶음으로 갈려 후보 둘이 생김) 짝짓기 반경 안에서 움직이는 track을 먼저,
        // 그다음 먼저 생긴 쪽을 남긴다. 번호로 나이를 재면 안 된다: 후보는 음수 번호라 오래된 움직이는 track을 지웠다(T 150159 track이 0.3~1 s마다 끊김)
        val live = tracks.filter { it !in removed && (it.motion!!.moving || it.motion!!.candidate) }
            .sortedWith(compareBy({ it.motion!!.candidate }, { it.motion!!.birthNs }))
        for ((i, a) in live.withIndex()) {
            if (a in removed) continue
            val pa = a.motion!!.origin() ?: continue
            for (b in live.subList(i + 1, live.size)) {
                if (b in removed) continue
                val pb = b.motion!!.origin() ?: continue
                if ((pa - pb).horizontal().norm() <= cfg.matchRadiusM) removed += b
            }
        }
        if (removed.isNotEmpty()) tracks = tracks.filter { it !in removed }

        // 후보 track: 정지 지도가 아직 점유로 만들지 못했고 어느 창에도 들지 않는 이번 장 칸의 묶음
        val gates = tracks.map { it.motion!!.gate(tNs, cfg.matchRadiusM) }
        val free = f.corridorCells.filter { (c, occ) -> !occ && gates.none { (lo, hi) -> inBox(c, lo, hi) } }.map { it.first }
        if (free.size >= minSamples) for (cl in f.clusterXZ(free)) {
            val reps = f.reps(cl) ?: continue
            val m = newMotion(tNs, candidate = true)
            m.boxMin = Vec3(cl.minOf { it.x }, cl.minOf { it.y }, cl.minOf { it.z })
            m.boxMax = Vec3(cl.maxOf { it.x }, cl.maxOf { it.y }, cl.maxOf { it.z })
            // 첫 측정도 다음 장들과 같은 정의(창에 걸린 군집 전체)로 한다. 묶음(11칸)으로 시작하고 다음 장에 군집 전체(250칸)로 바뀌면
            // 두 점 차분 속도가 −28 m/s로 시작해 출력 때까지 2~3 m/s가 남았다(T 150338 TTC STOP이 실제 2.16 m, TTC 4.5 s에서 남)
            val (glo, ghi) = m.gate(tf, cfg.matchRadiusM)
            val meas = clustersAll.filter { c -> c.count { inBox(it, glo, ghi) } >= minSamples }.flatten().ifEmpty { cl }
            val rep = nearSurfacePoint(meas, f.cameraW, wt) ?: continue
            m.boxMin = Vec3(meas.minOf { it.x }, meas.minOf { it.y }, meas.minOf { it.z })
            m.boxMax = Vec3(meas.maxOf { it.x }, meas.maxOf { it.y }, meas.maxOf { it.z })
            m.filter.update(tf, rep, f.sigmaAt(rep))
            m.lastMeasW = rep
            m.lastMeasNs = tNs
            m.nMeasCells = meas.size
            val w = f.weightAt(rep)
            m.votes = w
            m.score = kotlin.math.min(1f, mc.hitGain * w)
            m.lastCells = meas
            m.lastCellsNs = tNs
            tracks = tracks + Track(nextCandidateId--, reps, 0, 0, 0, false, motion = m).also { it.aabbMin = m.boxMin; it.aabbMax = m.boxMax }
        }

        // 출력: 정지 track은 짝짓기 출력에 움직임 값을 붙이고, 움직이는 track은 물체 좌표 점유 칸(통로 안)으로 낸다
        val byId = tracks.associateBy { it.id }
        val result = ArrayList<Obstacle>()
        for (o in out) {
            val m = byId[o.id]?.motion
            if (m != null && m.moving) continue // 이번 장에 움직이는 track이 됨: 아래에서 물체 좌표 칸으로 낸다
            val pr = m?.filter?.predictedAt(tNs) // 출력은 촬영 시각(필터가 뒤처짐만큼 앞선 시각이면 예측, M52)
            result += if (m == null || pr == null) o else o.copy(
                velocityW = m.filter.velocityW, motionRepW = pr.first,
                positionSigmaM = pr.second, velocitySigmaMps = m.filter.velocitySigmaMps,
            )
        }
        for (t in tracks) {
            val m = t.motion!!
            if (!m.moving) continue
            if (!m.occupied(mc.minHits, mc.minScore)) continue
            val occ = m.cellsAt(tNs).filter(f.inCorridor)
            if (occ.isEmpty()) continue
            // 안내 대표점 = 칼만 위치(이번 장 가까운 면 점을 시간에 걸쳐 거른 값), 높이는 칸 중앙값. 한 장 칸의 가장 앞 칸은 잡음 한 칸에 당겨져
            // 가로지르는 사람에게 STOP을 냈다(합성 SM-3). 이번 장 칸은 통로 안인지·높이 분류·상자에만 쓴다
            val pr = m.filter.predictedAt(tNs)
            val kp = pr?.first ?: m.lastMeasW ?: continue
            val ys = occ.map { it.y }.sorted()
            val repW = Vec3(kp.x, ys[ys.size / 2], kp.z)
            val reps = (f.reps(occ) ?: continue) + (strategy to repW)
            result += Obstacle(
                id = t.id,
                repPointW = repW,
                repCandidatesW = reps,
                aabbMinW = Vec3(occ.minOf { it.x }, occ.minOf { it.y }, occ.minOf { it.z }),
                aabbMaxW = Vec3(occ.maxOf { it.x }, occ.maxOf { it.y }, occ.maxOf { it.z }),
                heightClass = f.heightClass(occ) ?: HeightClass.BODY,
                inCorridor = true,
                confidence = 1f,
                lastSeenNs = m.lastMeasNs,
                nObservations = m.filter.nUpdates,
                velocityW = m.filter.velocityW,
                motionRepW = pr?.first,
                moving = true,
                positionSigmaM = pr?.second ?: Float.NaN,
                velocitySigmaMps = m.filter.velocitySigmaMps,
            )
        }
        lastMotionDebug = tracks.map { t ->
            val m = t.motion!!
            MotionDebug(
                tNs, t.id, m.candidate, m.moving, m.lastMeasW.takeIf { m.lastMeasNs == tNs }, m.filter.predictedAt(tNs)?.first, m.filter.velocityW,
                m.filter.positionSigmaM, m.filter.velocitySigmaMps, m.lastChi2, m.nMeasCells, m.votes, m.score,
            )
        }
        return result
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
        lastMotionDebug = emptyList()
        idOfInstance.clear()
        lost.clear()
    }
}
