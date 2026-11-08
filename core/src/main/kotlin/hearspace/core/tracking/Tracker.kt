package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import hearspace.core.types.HeightClass
import hearspace.core.types.MapConfig
import hearspace.core.types.MeasureFrom
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
    /** 정보 나이 허용치(ns, `policy.maxInfoAgeMs`): M58 `track.existFrom` RAW면 마지막 측정이 이보다 오래된 움직이는 track은 내지 않는다. */
    private val maxInfoAgeNs: Long = Long.MAX_VALUE,
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

    /** 마지막 [update]의 빈 곳 위반 기록(M57 `track.freeSpaceRecord`, `motion_evidence.csv`). 새 원시 장이 없던 장은 빈 목록. */
    var lastEvidenceDebug: List<EvidenceDebug> = emptyList()
        private set

    /** 시각 [tNs]의 움직이는 track 추적 창(정지 지도에 넣지 않을 점의 상자, M48). 지난 장의 상태로 정한다. */
    fun movingGates(tNs: Long): List<Pair<Vec3, Vec3>> =
        tracks.mapNotNull { t -> t.motion?.takeIf { it.moving }?.let { lagGate(it, tNs) } }

    /**
     * 시각 [tNs]의 소유 창(M57 `track.motionMap` OWNED): 모든 track. 움직이는 track·후보는 추적 창(뒤쪽 [TrackConfig.gateLagS]·|v| 늘림), 지도 track은
     * 짝지은 군집 상자 ± `track.matchRadiusM`. 지난 장의 상태로 정한다.
     */
    fun ownerGates(tNs: Long): List<hearspace.core.mapping.OwnerGate> = tracks.map { t ->
        val m = t.motion
        if (m != null && (m.moving || m.candidate)) {
            val (lo, hi) = lagGate(m, tNs)
            hearspace.core.mapping.OwnerGate(lo, hi, t.id, m.moving, m.origin() ?: ((lo + hi) * 0.5f))
        } else {
            val r = Vec3(cfg.matchRadiusM, cfg.matchRadiusM, cfg.matchRadiusM)
            hearspace.core.mapping.OwnerGate(t.aabbMin - r, t.aabbMax + r, t.id, false, (t.aabbMin + t.aabbMax) * 0.5f)
        }
    }

    /** 추적 창을 속도 반대쪽으로 [TrackConfig.gateLagS]·|v|만큼 늘린 상자(M57 규칙 2, 평활 깊이 뒤처짐). 0이면 [gateOf]와 같다. */
    private fun lagGate(m: MotionState, tNs: Long): Pair<Vec3, Vec3> {
        val (lo, hi) = gateOf(m, tNs)
        val v = m.filter.velocityW
        if (cfg.gateLagS <= 0f || v == null) return lo to hi
        val d = v * (-cfg.gateLagS)
        return Vec3(minOf(lo.x, lo.x + d.x), minOf(lo.y, lo.y + d.y), minOf(lo.z, lo.z + d.z)) to
            Vec3(maxOf(hi.x, hi.x + d.x), maxOf(hi.y, hi.y + d.y), maxOf(hi.z, hi.z + d.z))
    }

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
            movingTracks.none { t -> t.motion!!.moving && gateOf(t.motion!!, tNs).let { (lo, hi) -> inBox(c, lo, hi) } }
        }
        // 지도가 군집으로 잡은 자리의 후보는 지운다(정지 지도가 맡음). 움직이는 track 창 안 군집은 위에서 흡수했다
        val candidatesTaken = movingTracks.filter { t ->
            t.motion!!.candidate && detections.any { d -> gateOf(t.motion!!, tNs).let { (lo, hi) -> inBox(d.repCandidatesW.getValue(RepStrategy.CENTROID), lo, hi) } }
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
        val idOfDetection = IntArray(detections.size)

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
            idOfDetection[di] = track.id

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
            else {
                if (cfg.reidLost && old.confirmed) lost += Lost(old.id, old.aabbMin, old.aabbMax, tNs)
                frame?.owned?.releaseOwner(old.id, tNs, null, null) // M57: 지워진 지도 track의 소유를 푼다
            }
        }
        // M57: 지도가 넘겨받은 후보의 소유는 창 안 군집과 짝지은 track으로
        frame?.owned?.let { owned ->
            for (c in candidatesTaken) {
                val (lo, hi) = gateOf(c.motion!!, tNs)
                val di = detections.indices.firstOrNull { d -> inBox(detections[d].repCandidatesW.getValue(RepStrategy.CENTROID), lo, hi) }
                if (di != null) owned.transferOwner(c.id, idOfDetection[di]) else owned.releaseOwner(c.id, tNs, null, null)
            }
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
        val owned = f.owned
        val fsJudge = cfg.motionEvidence == hearspace.core.types.MotionEvidence.FREE_SPACE
        // 물체 단위 점유 문턱(M57: 지도의 신뢰도 표 문턱과 같게)
        val objMinHits = if (cfg.objectMinHits > 0f) cfg.objectMinHits else mc.minHits.toFloat()
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
        val evidence = ArrayList<EvidenceDebug>()
        for (t in tracks) {
            val m = t.motion!!
            // 추적 창: 움직이는 track·후보는 측정 상자를 예측만큼 옮긴 것, 지도 track은 이번 지도 군집 상자(지도 앞면이 뒤처지므로 여유로 덮음)
            if (!m.moving && !m.candidate) { m.boxMin = t.aabbMin; m.boxMax = t.aabbMax }
            val rawMode = cfg.measureFrom == MeasureFrom.RAW_MASK && (m.moving || m.candidate)
            // M58 `track.existFrom` RAW: 칼만이 원시 장으로 갱신되는 움직이는 track은 존재 증거(표·점수·마지막 측정 시각)를 새 원시 장에서만 센다.
            // 평활 깊이는 움직이는 사람을 늦게 담고 일찍 지워(검출 확률이 낮음) 그 놓침은 없다는 증거가 아니다(설계 3.1절, IPDA)
            val rawExist = cfg.existFrom == hearspace.core.types.ExistFrom.RAW && rawMode && m.moving && m.filterRaw
            val (lo, hi) = gateOf(m, tf)
            // 측정은 통로 밖 칸도 쓴다: 통로만 보면 옆으로 비켜 간 물체를 놓친 것으로 보고 마지막 자리를 정지 지도에 넘겨 유령 칸이 생겼다(SM-6)
            val meas = if (m.moving || m.candidate) clustersAll.filter { cl -> cl.count { inBox(it, lo, hi) } >= minSamples }.flatten()
            else f.allCells.filter { inBox(it, lo, hi) }
            m.nMeasCells = meas.size
            // M57 2단계: 창 안 원시 점의 빈 곳 위반을 센다(기록만, 판정은 그대로)
            m.fsDecision = null
            f.freeSpace?.let { fs ->
                val pts = fs.points.filter { inBox(it, lo, hi) }
                val c = fs.count(pts)
                val lab = IntArray(12)
                for (p in pts) fs.labelAt(p).let { if (it in 0..11) lab[it]++ }
                if (fsJudge) m.fsDecision = freeSpaceStep(m, c, lab, fs.rotDegS, (tNs - m.birthNs) / 1e9f, f.movingPixels)
                val own = IntArray(12)
                if (m.moving) f.owned?.movingCells(t.id)?.forEach { p -> fs.labelAt(p).let { if (it in 0..11) own[it]++ } }
                val zs = pts.map { (it - f.cameraW).norm() }.sorted()
                evidence += EvidenceDebug(
                    fs.tRawNs, tNs, t.id, m.candidate, m.moving, (tNs - m.birthNs) / 1e9f, pts.size, c[0], c[1],
                    if (zs.isEmpty()) Float.NaN else zs[zs.size / 2], fs.rotDegS, lo, hi, lab, own,
                )
            }
            // 라벨 표(M49): 칼만 갱신 전 속도로 창 안 칸을 라벨 영상 시각까지 되돌려 센다
            if (f.labelVotes) labelEvidence(m, f, lo, hi, tNs, mc)
            // 칼만 측정 = 이번 장 창 안 칸의 중심점(가장 앞 칸 기준 대표점은 장마다 칸이 바뀌어 크게 뛴다, M48 값 재기). 예측에서 벗어난 측정은 버린다
            val rep = if (meas.size >= minSamples) nearSurfacePoint(meas, f.cameraW, wt) else null
            val restarts = m.filter.restarts
            // RAW_MASK(M49): 움직이는 track·후보의 칼만은 새 원시 장의 마스크 점으로만, 원시 장 촬영 시각으로 갱신한다. 원시 장이 없는 장에는
            // 필터를 건드리지 않는다(평활 시각으로 예측해 두면 더 이른 원시 시각의 측정이 버려진다). 표·점수·창은 아래처럼 평활 칸으로 센다
            if (rawMode) f.rawPoints?.let { rawMeasure(m, f, it, lo, hi, t.id) }
            if (rep != null && !rawMode && !m.filter.update(tf, rep, f.sigmaAt(rep), cfg.validationChi2)) {
                m.missed++
                m.nMeasCells = -meas.size // 버린 측정(기록용 음수)
            } else if (rep != null) {
                if (!rawMode) m.lastMeasW = rep
                if (!rawExist) m.lastMeasNs = tNs
                // 움직이는 track·후보의 창 = 측정한 군집의 상자(+ 여유는 gate가 붙인다). 창 안 칸 전체의 상자로 두면 장마다 여유만큼
                // 넓어졌다(T 150338 칼만 속도 3 m/s). 군집 상자는 장마다 다시 재므로 쌓여 커지지 않는다
                m.missed = 0
                if ((m.moving || m.candidate) && !(rawMode && m.rawMeasTNs != Long.MIN_VALUE)) { // RAW_MASK는 원시 측정이 생긴 뒤로는 마스크 점 상자
                    m.boxMin = Vec3(meas.minOf { it.x }, meas.minOf { it.y }, meas.minOf { it.z })
                    m.boxMax = Vec3(meas.maxOf { it.x }, meas.maxOf { it.y }, meas.maxOf { it.z })
                    m.boxNs = tNs
                }
                // 시작 게이트가 필터를 다시 시작했으면(다른 대상과 짝지은 측정) 표·점수도 처음부터 모은다(M52): 남은 표로 바로 출력되면
                // 막 시작한 필터의 큰 속도 불확실성으로 TTC STOP이 났다(M48 SM-3과 같은 까닭)
                if (m.filter.restarts != restarts) { m.votes = 0f; m.score = 0f }
                // 물체 단위 HITS: 측정된 장마다 표·점수(정지 지도와 같은 거리 가중). RAW 존재면 평활 칸은 모양만 갱신한다
                if (!rawExist) {
                    val w = f.weightAt(rep)
                    m.votes += w
                    m.score = kotlin.math.min(1f, m.score + mc.hitGain * w)
                }
                m.lastCells = meas
                m.lastCellsNs = tNs
            } else {
                if (!rawMode) m.filter.predictTo(tf)
                m.missed++
                // 예측 자리가 빈 곳으로 보이면 점수를 깎는다(시야 밖·깊이 무효면 그대로: 시야 밖 보존)
                val p = m.filter.predictedAt(tNs)?.first ?: m.lastMeasW
                if ((m.moving || m.candidate) && !rawExist && p != null) {
                    val probe = Vec3(p.x, (m.boxMin.y + m.boxMax.y) / 2, p.z)
                    if (f.seenFree(probe)) m.score -= mc.decayPerObservation
                }
            }
            // M58 RAW 존재(설계 표 2 규칙 1~3): 받아들인 원시 측정 = 표·점수 +(원시 장마다 한 관측). 측정이 없으면 예측 자리 화소를 원시가 알면 원시로
            // (새 원시 장마다, 빈 곳이면 감쇠), 원시가 모르면(깊이 없음·신뢰도 미달·영상 밖) 평활로(평활 장마다, M57과 같은 검사). 각 센서를 자기 관측
            // 빈도로 센다(B-2: 원시 구멍·가까운 곳에서 지울 증거가 없어 떠난 사람의 경고가 남음, 사용자 승인)
            if (rawExist) {
                val r = m.lastMeasW
                val y = (m.boxMin.y + m.boxMax.y) / 2
                if (f.rawPoints != null && m.rawMeasTNs == f.rawTNs && r != null) {
                    if (m.filter.restarts != restarts) { m.votes = 0f; m.score = 0f } // 시작 게이트가 다시 시작함: 표·점수도 처음부터(M52 규칙)
                    val w = f.weightAt(r)
                    m.votes += w
                    m.score = kotlin.math.min(1f, m.score + mc.hitGain * w)
                    m.lastMeasNs = tNs
                } else if (m.rawMeasTNs != f.rawLatestTNs) { // 가장 최근 원시 장에서 측정된 track은 다음 원시 장까지 있음으로 둔다
                    val pr = m.filter.predictedAt(f.rawLatestTNs)?.first ?: r
                    when (pr?.let { f.rawFreeAt?.invoke(Vec3(it.x, y, it.z)) }) {
                        null -> (m.filter.predictedAt(tNs)?.first ?: r)?.let { if (f.seenFree(Vec3(it.x, y, it.z))) m.score -= mc.decayPerObservation }
                        true -> if (f.rawPoints != null) m.score -= mc.decayPerObservation
                        false -> Unit
                    }
                }
            }
            val v = m.filter.velocityW
            m.lastChi2 = m.filter.velocityChi2
            val isMoving = m.filter.ready && v != null && when {
                f.movingOracle != null -> (if (meas.isEmpty()) null else f.movingOracle.invoke(tNs, meas)) ?: m.moving // 정답(라벨처럼): 모르면 지금 상태 유지
                // M57 빈 곳 위반(라벨 길·기하 길): 새 원시 장이 없거나 보류면 지금 상태
                fsJudge -> m.fsDecision ?: m.moving
                // 라벨 표(M49): 지도의 점유 조건과 같이 표 ≥ minHits·점수 ≥ minScore면 참, 표가 있는데 점수가 0이면 거짓, 그 사이는 지금 상태
                f.labelVotes -> when {
                    m.labelVotes >= mc.minHits && m.labelScore >= mc.minScore -> true
                    m.labelVotes > 0f && m.labelScore <= 0f -> false
                    else -> m.moving
                }
                else -> v.norm() > cfg.minMovingMps && m.lastChi2 > cfg.movingChi2
            }
            val origin = m.origin()
            val occupied = m.occupied(objMinHits, mc.minScore)
            when {
                !m.moving && isMoving && m.candidate -> if (occupied) {
                    // 후보 → 움직이는 track: 판정 전에 같은 점이 정지 지도에도 들어갔으므로 이 후보가 생긴 뒤 생긴 창 안 칸을 지도에서 뺀다
                    // (M57 OWNED: 칸을 빼지 않고 후보 번호의 소유를 새 번호로 넘겨 움직이는 칸으로 바꾼다)
                    if (owned == null) f.take(lo, hi, m.birthNs)
                    m.moving = true
                    m.candidate = false
                    t.confirmed = true
                    val old = t.id
                    t.id = nextId++
                    owned?.transferOwner(old, t.id)
                    owned?.claimOwned(t.id)
                }
                !m.moving && isMoving && origin != null -> {
                    // 지도 track → 움직이는 track: track이 생긴 뒤 생긴 창 안 칸(뒤처진 흔적)을 지도에서 뺀다(그 앞의 칸은 정지 물체일 수 있어 둔다).
                    // 표는 넘겨받지 않는다: 이번 장 측정으로 6표를 모아야 출력한다(그 사이 칼만 속도가 수렴한다. 넘겨받으면 막 시작한 필터의
                    // 큰 속도 불확실성 때문에 TTC 하한이 작아져 가로지르는 사람에게 STOP을 냈다, 합성 SM-3)
                    if (owned == null) f.take(lo, hi, m.birthNs) else owned.claimOwned(t.id)
                    m.moving = true
                }
                m.moving && !isMoving && m.filter.ready && origin != null -> {
                    // 섰다: 물체 모양(마지막 측정 칸)을 점유로 정지 지도에 넣는다. 다음 장부터 창 안 점도 지도로 간다
                    val occ = m.occupied(objMinHits, mc.minScore)
                    if (owned != null) owned.releaseOwner(t.id, tNs, m.votes.takeIf { occ }, m.score.takeIf { occ })
                    else if (occ) m.cellsAt(tNs).forEach { f.seed(hearspace.core.mapping.VoxelMap.CellEvidence(it, m.votes, m.score)) }
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
            // M57 OWNED: 이번 장에 측정한 움직이는 track은 시선 그늘의 같은 라벨 칸을 가져오고(규칙 7), 맞지 않은 움직이는 칸을 지운다(규칙 3)
            if (owned != null && mm.moving && mm.lastCellsNs == tNs) {
                if (cfg.shadowDepthM > 0f) owned.claimShadow(t.id, f.cameraW, cfg.shadowDepthM)
                owned.dropUnhitMoving(t.id)
            }
            when {
                mm.moving && mm.lastMeasNs != Long.MIN_VALUE && tNs - mm.lastMeasNs > (mc.maxUnseenS * 1e9).toLong() -> removed += t
                // 시야 밖으로 나감: 물체는 시야 밖이라 점수를 깎지 않고(시야 밖 보존) 움직임 가설대로 예측해 옮긴다. 지도의 시야 밖 칸처럼
                // `map.maxUnseenS` 동안 둔다. 처음 설계(마지막 자리를 정지 지도로 넘김)는 옆으로 비켜 시야를 벗어난 사람을 통로 가장자리의
                // 유령 칸으로 남겼다(합성 SM-6)
                mm.moving && mm.score <= 0f -> removed += t // 시야 안에서 빈 곳으로 보여 지워짐
                mm.candidate && mm.missed > cfg.maxMissedUpdates -> removed += t
                // 6표가 모였는데 정지: 정지 지도가 맡음. RAW_MASK는 칼만이 원시 장(10 Hz)으로만 차므로 준비 전에는 판정하지 않는다
                // M57 FREE_SPACE: 판정 전에 넘기지 않는다(새 판정은 창이 차야 함, M55 판정 전 넘김 빈틈). 정지 후보는 지도 군집이 창을 차지하면 넘겨받는다
                !fsJudge && mm.candidate && !isMoving && mm.occupied(objMinHits, mc.minScore) &&
                    (cfg.measureFrom != MeasureFrom.RAW_MASK || mm.filter.ready) &&
                    // M55 ②: 라벨 판정 중(움직일 수 있는 라벨 증거가 있음)이면 넘기지 않고 기다린다(`track.holdLabelPending`)
                    !(cfg.holdLabelPending && f.labelVotes && (mm.labelScore > 0f || mm.nRawPoints >= minSamples)) -> removed += t
            }
        }
        // 같은 물체를 따르는 움직이는 track·후보가 둘이면(한 사람이 두 묶음으로 갈려 후보 둘이 생김) 짝짓기 반경 안에서 움직이는 track을 먼저,
        // 그다음 먼저 생긴 쪽을 남긴다. 번호로 나이를 재면 안 된다: 후보는 음수 번호라 오래된 움직이는 track을 지웠다(T 150159 track이 0.3~1 s마다 끊김)
        val live = tracks.filter { it !in removed && (it.motion!!.moving || it.motion!!.candidate) }
            .sortedWith(compareBy({ it.motion!!.candidate }, { it.motion!!.birthNs }))
        val mergedInto = HashMap<Track, Track>()
        for ((i, a) in live.withIndex()) {
            if (a in removed) continue
            val pa = a.motion!!.origin() ?: continue
            for (b in live.subList(i + 1, live.size)) {
                if (b in removed) continue
                val pb = b.motion!!.origin() ?: continue
                if ((pa - pb).horizontal().norm() <= cfg.matchRadiusM) {
                    removed += b
                    mergedInto[b] = a
                }
            }
        }
        // M57 OWNED: 합쳐진 track의 소유는 남는 쪽으로, 지워진 움직이는 track의 움직이는 칸은 지우고 후보의 소유는 푼다(규칙 6)
        if (owned != null) for (r in removed) {
            val into = mergedInto[r]
            when {
                into != null -> owned.transferOwner(r.id, into.id)
                r.motion!!.moving -> owned.dropOwner(r.id)
                else -> owned.releaseOwner(r.id, tNs, null, null)
            }
        }
        if (removed.isNotEmpty()) tracks = tracks.filter { it !in removed }

        // 후보 track: 정지 지도가 아직 점유로 만들지 못했고 어느 창에도 들지 않는 이번 장 칸의 묶음
        val gates = tracks.map { gateOf(it.motion!!, tNs) }
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
            m.boxNs = tNs
            // RAW_MASK: 후보의 칼만은 원시 점으로만 시작한다(평활 칸 대표점을 넣지 않음)
            if (cfg.measureFrom == MeasureFrom.RAW_MASK) m.filterRaw = true else m.filter.update(tf, rep, f.sigmaAt(rep))
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
            if (!m.occupied(objMinHits, mc.minScore)) continue
            // M58: 정보 나이 원칙(§2.2-4)을 움직이는 출력에도. 마지막으로 받아들인 측정의 촬영 시각부터 허용치가 지나면 예측만으로 내지 않는다
            // (B-2: 150338에서 8.03 s 측정으로 STOP이 8.63 s까지 남음. M57은 모양 칸이 지도 감쇠로 우연히 지워져 가려졌을 뿐 track은 살아 있었다)
            if (cfg.existFrom == hearspace.core.types.ExistFrom.RAW) {
                val measNs = if (m.filterRaw && m.rawMeasTNs != Long.MIN_VALUE) m.rawMeasTNs else m.lastMeasNs
                if (measNs == Long.MIN_VALUE || tNs - measNs > maxInfoAgeNs) continue
            }
            // M57 OWNED: 모양 =복셀 지도의 그 track 움직이는 칸(마지막 측정)을 속도 × 경과만큼 옮긴 것(cellsAt과 같은 계산).
            // M58 규칙 5: 받아들인 원시 측정이 마지막 평활 칸 측정보다 새것이면 그 원시 점 칸을 옮긴다(평활 감쇠가 움직이는 칸을 지운 동안)
            val v58 = m.filter.velocityW
            val shape = if (cfg.existFrom == hearspace.core.types.ExistFrom.RAW && m.filterRaw && m.rawCells.isNotEmpty() && m.rawMeasTNs > m.lastCellsNs) {
                val dt = ((tNs - m.rawMeasTNs) / 1e9f).coerceAtLeast(0f)
                if (v58 == null || dt == 0f) m.rawCells else m.rawCells.map { it + v58 * dt }
            } else if (owned == null) m.cellsAt(tNs) else {
                val v = m.filter.velocityW
                val dt = ((tNs - m.lastCellsNs) / 1e9f).coerceAtLeast(0f)
                owned.movingCells(t.id).let { c -> if (v == null || dt == 0f) c else c.map { it + v * dt } }
            }
            val occ = shape.filter(f.inCorridor)
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
        // M57 판정 전 출력: 점유한 후보를 이번 측정 칸(통로 안)으로 속도 없이 낸다(거리 STOP만)
        if (cfg.candidateOutput) for (t in tracks) {
            val m = t.motion!!
            if (!m.candidate || !m.occupied(objMinHits, mc.minScore) || m.lastCellsNs != tNs) continue
            val occ = m.lastCells.filter(f.inCorridor)
            if (occ.isEmpty()) continue
            val reps = f.reps(occ) ?: continue
            result += Obstacle(
                id = t.id,
                repPointW = reps.getValue(strategy),
                repCandidatesW = reps,
                aabbMinW = Vec3(occ.minOf { it.x }, occ.minOf { it.y }, occ.minOf { it.z }),
                aabbMaxW = Vec3(occ.maxOf { it.x }, occ.maxOf { it.y }, occ.maxOf { it.z }),
                heightClass = f.heightClass(occ) ?: HeightClass.BODY,
                inCorridor = true,
                confidence = 1f,
                lastSeenNs = m.lastMeasNs,
                nObservations = m.filter.nUpdates,
            )
        }
        lastEvidenceDebug = evidence
        lastMotionDebug = tracks.map { t ->
            val m = t.motion!!
            // RAW_MASK의 움직이는 track·후보는 이번 장에 원시 측정을 넣었을 때만 측정점을 적고, 그 시각은 원시 장 촬영 시각이다
            val rawMode = cfg.measureFrom == MeasureFrom.RAW_MASK && (m.moving || m.candidate)
            val rawNow = rawMode && f.rawPoints != null && m.rawMeasTNs == f.rawTNs
            MotionDebug(
                tNs, t.id, m.candidate, m.moving,
                if (rawMode) m.lastMeasW.takeIf { rawNow } else m.lastMeasW.takeIf { m.lastMeasNs == tNs },
                m.filter.predictedAt(tNs)?.first, m.filter.velocityW,
                m.filter.positionSigmaM, m.filter.velocitySigmaMps, m.lastChi2, m.nMeasCells, m.votes, m.score,
                measTNs = if (rawNow) f.rawTNs else tNs, labelVotes = m.labelVotes, labelScore = m.labelScore,
                nRawPoints = if (rawMode && f.rawPoints != null) m.nRawPoints else -1,
            )
        }
        return result
    }

    /** 추적 창(M48). RAW_MASK의 움직이는 track·후보는 상자를 잰 시각부터 옮긴다([MotionState.gate]). */
    private fun gateOf(m: MotionState, tNs: Long) =
        m.gate(tNs, cfg.matchRadiusM, fromBox = cfg.measureFrom == MeasureFrom.RAW_MASK && (m.moving || m.candidate))

    /**
     * 라벨 표(M49 설계 3.3절): 새 의미 영상마다 한 번, 창 안 이번 장 칸을 그 영상에 투영한다. 속도가 있는 track(움직이는 track·후보)은 칸을
     * 칼만 속도로 영상 시각까지 되돌린다(과거 상태만). 움직일 수 있는 라벨 칸이 `cluster.minSamples` 이상이면 표 +1·점수 + hitGain(최대 1),
     * 영상 안 칸은 그만큼인데 그 라벨 칸이 적으면 점수 − decayPerObservation(0 아래로는 내리지 않음), 그 밖은 모름. 지도 HITS와 같은 상수.
     */
    private fun labelEvidence(m: MotionState, f: MotionFrame, lo: Vec3, hi: Vec3, tNs: Long, mc: hearspace.core.types.MapConfig) {
        val label = f.labels ?: return
        if (label.tCaptureNs == m.lastLabelTNs) return
        m.lastLabelTNs = label.tCaptureNs
        val v = if (m.moving || m.candidate) m.filter.velocityW else null
        val shift = (v ?: Vec3.ZERO) * ((label.tCaptureNs - tNs) / 1e9f)
        val shiftRaw = (v ?: Vec3.ZERO) * ((f.rawLatestTNs - tNs) / 1e9f)
        var nIn = 0
        var nMoving = 0
        for (c in f.allCells) {
            if (!inBox(c, lo, hi)) continue
            // 가시성 검사: 원시 깊이에서 보이는 표면인 칸만 센다(평활 막 칸·가려진 칸은 그 화소의 라벨을 받지 않는다). 원시 깊이가 없으면 세지 않는다
            if (f.rawVisible != null && f.rawVisible.invoke(c + shiftRaw) != true) continue
            val px = label.pixelAt(c + shift)
            if (px < 0) continue
            nIn++
            if (px in f.movingPixels) nMoving++
        }
        if (nMoving >= minSamples) {
            m.labelVotes += 1f
            m.labelScore = kotlin.math.min(1f, m.labelScore + mc.hitGain)
        } else if (nIn >= minSamples) {
            m.labelScore = kotlin.math.max(0f, m.labelScore - mc.decayPerObservation)
        }
    }

    /**
     * RAW_MASK 측정(M49 설계 3.4절): 새 원시 장의 장애물 점 중 창 안이고 라벨이 움직일 수 있는 라벨인 점(원시 장 시각에서 라벨 영상 시각까지
     * 칼만 속도로 되돌려 투영)의 가까운 면 점으로 칼만을 원시 장 촬영 시각에 갱신한다. 점 하나 = 관측 하나. 라벨이 없거나 점이 적으면 측정 없음.
     */
    private fun rawMeasure(m: MotionState, f: MotionFrame, points: List<Vec3>, lo: Vec3, hi: Vec3, id: Int) {
        val label = f.labels
        if (label == null && cfg.measureLabels) {
            m.nRawPoints = 0
            return
        }
        val shift = (m.filter.velocityW ?: Vec3.ZERO) * (((label?.tCaptureNs ?: f.rawTNs) - f.rawTNs) / 1e9f)
        // M57 `track.measureLabels` false: 라벨로 거르지 않고, 그 track 소유가 아닌 정지 점유 칸(track보다 먼저 있던 배경)의 점만 뺀다
        val labelled = if (cfg.measureLabels) {
            points.mapNotNull { p -> if (inBox(p, lo, hi)) label!!.pixelAt(p + shift).takeIf { it in f.movingPixels }?.let { p to it } else null }
        } else {
            points.filter { p -> inBox(p, lo, hi) && f.owned?.staticOther(p, id) != true }.map { p -> p to (label?.pixelAt(p + shift) ?: -1) }
        }
        val pts = labelled.map { it.first }
        m.nRawPoints = pts.size
        if (pts.size < minSamples) return
        val r = nearSurfacePoint(pts, f.rawCameraW) ?: return
        // 원천을 섞지 않는다: 평활 칸으로 쌓인 칼만이면 첫 원시 측정에서 다시 시작한다(지도 track → 움직이는 track 전환)
        if (!m.filterRaw) {
            m.filter.reset()
            m.filterRaw = true
        }
        // 시작 게이트의 최대 속도 = 마스크 점의 다수 라벨 종류의 최대 속도(사람 2.5·차량 6.9 m/s, M49 측정 단계 결과 뒤 사용자 승인)
        val major = labelled.groupingBy { it.second }.eachCount().maxByOrNull { it.value }!!.key // 라벨 없으면 −1 → initMaxSpeedMps
        val vmax = f.maxSpeedOfPixel[major] ?: cfg.initMaxSpeedMps
        if (m.filter.update(f.rawTNs, r, f.sigmaAt(r), cfg.validationChi2, maxSpeedMps = vmax)) {
            m.lastMeasW = r
            m.rawMeasTNs = f.rawTNs
            // 창 = 측정한 것의 상자(M48 규칙): RAW_MASK의 측정은 마스크 점이므로 그 점들의 범위로. 평활 군집 전체로 두면 큰 군집이 창을 키워
            // 엉뚱한 라벨 점이 섞였다(T 150338 창 안 평활 칸 283~661, 측정이 사람과 2.3 m 사이를 오감)
            m.boxMin = Vec3(pts.minOf { it.x }, pts.minOf { it.y }, pts.minOf { it.z })
            m.boxMax = Vec3(pts.maxOf { it.x }, pts.maxOf { it.y }, pts.maxOf { it.z })
            m.boxNs = f.rawTNs
            // M58 규칙 5: 출력 모양 재료(받아들인 원시 측정 점이 든 칸)
            if (cfg.existFrom == hearspace.core.types.ExistFrom.RAW) {
                val vs = f.voxelM
                fun c(x: Float) = (kotlin.math.floor(x / vs) + 0.5f) * vs
                m.rawCells = pts.map { Vec3(c(it.x), c(it.y), c(it.z)) }.distinct()
            }
        }
    }

    /**
     * 빈 곳 위반 판정 한 원시 장(M57 설계 3.4절, prototypes/python/motion_judge/judge2.py와 같은 규칙): 창(최근 [TrackConfig.freeSpaceWindow]장까지)에
     * 넣고, 보류면 null. 라벨 길 = 창 칸 다수 라벨이 움직일 수 있는 라벨이고 S > 문턱, 기하 길 = 나이 ≥ T_수렴에서 S > 문턱이 이어진 장 수 ≥
     * [TrackConfig.freeSpacePersistFrames], 정지 복귀 = S ≤ 문턱이 W장 이어짐. 그 밖은 지금 상태.
     */
    private fun freeSpaceStep(m: MotionState, c: IntArray, lab: IntArray, rotDegS: Float, ageS: Float, movingPixels: Set<Int>): Boolean? {
        m.fsWin.addLast(FsRow(c[0], c[1], if (rotDegS.isNaN()) 0f else kotlin.math.abs(rotDegS), lab))
        while (m.fsWin.size > cfg.freeSpaceWindow) m.fsWin.removeFirst()
        val n = m.fsWin.sumOf { it.nCmp }
        val held = n < cfg.freeSpaceMinCompared || m.fsWin.maxOf { it.rotDegS } > cfg.freeSpaceMaxRotDegS
        val exceed = !held && m.fsWin.sumOf { it.nViol }.toFloat() / n > cfg.freeSpaceThreshold
        m.fsRun = if (exceed && ageS >= cfg.freeSpaceConvergeS) m.fsRun + 1 else 0
        if (held) return null
        m.fsBelow = if (!exceed) m.fsBelow + 1 else 0
        val labs = IntArray(12)
        for (r in m.fsWin) for (i in 0 until 12) labs[i] += r.labels[i]
        val major = labs.indices.maxBy { labs[it] }.takeIf { labs[it] > 0 } ?: -1
        return when {
            !m.moving && exceed && major in movingPixels -> true
            !m.moving && m.fsRun >= cfg.freeSpacePersistFrames -> true
            m.moving && m.fsBelow >= cfg.freeSpaceWindow -> false
            else -> m.moving
        }
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
        lastEvidenceDebug = emptyList()
        idOfInstance.clear()
        lost.clear()
    }
}
