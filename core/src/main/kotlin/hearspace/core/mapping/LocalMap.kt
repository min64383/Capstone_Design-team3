package hearspace.core.mapping

import hearspace.core.frontend.EdgeDetector
import hearspace.core.frontend.PlaneDetector
import hearspace.core.frontend.PlaneResult
import hearspace.core.frontend.RgbEdge
import hearspace.core.frontend.RgbGuide
import hearspace.core.frontend.Segmenter
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.DepthSource
import hearspace.core.types.FloorSource
import hearspace.core.types.MapMode
import hearspace.core.types.MapHealth
import hearspace.core.types.IndoorGeometryResult
import hearspace.core.types.PlaneMode
import hearspace.core.types.RgbGuideMode
import hearspace.core.types.SegmentMode
import hearspace.core.types.TriFree
import hearspace.core.types.TriWeight

/** 깊이 한 장을 맵에 반영한 결과(§10.1 `slow_path.csv`의 일부). */
data class MapUpdate(
    val tCaptureNs: Long,
    val nPoints: Int,
    val nFloorPoints: Int,
    /** 바닥보다 확실히 낮은 점(내려가는 단차 후보). 맵에는 넣지 않고 개수만 기록(§7.2). */
    val nBelowFloorPoints: Int,
    val nInsertedPoints: Int,
    val nDecayed: Int,
    val pruned: PruneCounts,
    val nVoxels: Int,
    val floorY: Float?,
    val mapHealth: MapHealth,
    /** `frontend.planes`가 RANSAC일 때 이 깊이의 평면(M13.1c), 아니면 null. */
    val planes: PlaneResult? = null,
    /** `frontend.segment`가 REGION일 때 이 깊이의 영역 분할(C3a), 아니면 null. 바닥·바닥 아래·벽 평면 점은 영역에 넣지 않는다. */
    val segments: Segmenter.Result? = null,
    /** Depth/복셀 기하만으로 얻은 실내 공간 형태(실험용, guidance에는 아직 미연결). */
    val geometry: IndoorGeometryResult? = null,
    /** RGB 경계 옮기기(M23)의 경계 수(전체, 바닥 건너뜀, RGB 경계 있음). 실행 로그에는 쓰지 않는다(평가 기록용). */
    val rgbEdgeCounts: IntArray? = null,
)

/**
 * 느린 경로의 맵 부분: 역투영 → 월드 → 바닥 → 복셀 갱신(관측·감쇠·정리) (§7.2, §7.3).
 * 스레드를 모르고, 입력 깊이의 `tCaptureNs`만 시각으로 쓴다(같은 입력이면 같은 결과).
 * 군집·추적은 M4에서 `SlowPath`가 이 위에 조립한다.
 */
class LocalMap(private val config: Config) {

    /** 바닥 추정기. */
    val floor = Floor(config.floor, config.map.radiusM)

    /** 복셀 맵. */
    val voxels = VoxelMap(config.map, config.vision.floorishLabels.map { it.pixel }.toIntArray())

    /** 마지막 [update]가 지도에 쓴 깊이(신뢰도·앞단 필터 뒤). 대표점 칸 상태 진단(M20)이 같은 장으로 판정한다. */
    var lastDepthMm: ShortArray? = null
        private set

    /**
     * 정지·움직임 분해(M48): 이 상자들(월드 AABB, 움직이는 track의 추적 창) 안의 장애물 점은 지도에 넣지 않는다. 빈 목록 = 기준선.
     * [update] 전에 느린 경로가 정한다(지난 장의 track 상태로, 인과적).
     */
    var excludeBoxes: List<Pair<Vec3, Vec3>> = emptyList()

    /** true면 [update]가 이번 장의 장애물 점(바닥 위·반경 안, 지도에 넣었든 뺐든)과 관측 가중치를 [frameObstaclePoints]에 모은다(M48). */
    var collectFramePoints = false

    /** 마지막 [update]의 장애물 점과 가중치([collectFramePoints]일 때만). */
    val frameObstaclePoints = ArrayList<Pair<Vec3, Float>>()

    /**
     * 원시 깊이 장의 장애물 점(M49 `track.measureFrom` RAW_MASK, 지도에는 넣지 않음): 신뢰도 [minConfidence] 미만 화소를 빼고
     * [update]와 같은 바닥·반경 규칙으로 고른다. 바닥을 아직 모르면 빈 목록.
     */
    fun obstaclePointsOf(raw: DepthFrame, minConfidence: Int, userPosW: Vec3): List<Vec3> {
        if (floor.floorY == null) return emptyList()
        val mm = raw.depthMm.copyOf()
        raw.confidence?.let { c -> for (i in mm.indices) if ((c[i].toInt() and 0xFF) < minConfidence) mm[i] = 0 }
        val pts = Projection.backprojectToWorld(mm, raw.K, raw.worldFromCam, config.depth.subsample)
        val camW = raw.worldFromCam.translation()
        val out = ArrayList<Vec3>(pts.size / 3)
        for (i in 0 until pts.size / 3) {
            val p = Vec3(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2])
            val camDist = (p - camW).horizontal().norm()
            if (floor.isFloor(p, camDist) || floor.isBelowFloor(p, camDist) || floor.isUnder(p)) continue
            if ((p - userPosW).horizontal().norm() > config.map.radiusM) continue
            out += p
        }
        return out
    }

    /**
     * 깊이 한 장을 반영한다. [userPosW]·[headingW]는 삭제 조건(지나감·반경)의 기준(진행 방향 추정은 M5).
     * 바닥을 아직 모르면 맵을 갱신하지 않고 `DEGRADED`를 돌려준다(사용자 결정 2026-09-28). TRISTATE는 예외([updateTriState]).
     */
    fun update(depth: DepthFrame, userPosW: Vec3, headingW: Vec3): MapUpdate {
        val update = updateMap(depth, userPosW, headingW)
        if (config.floor.mode != hearspace.core.types.FloorMode.ELEVATION) return update
        val fy = update.floorY
        val geometry = if (fy == null) IndoorGeometryResult(hearspace.core.types.IndoorGeometryType.UNKNOWN, emptyList(), emptyList()) else
            IndoorGeometryAnalyzer.analyze(
                Projection.backprojectToWorld(lastDepthMm!!, depth.K, depth.worldFromCam, config.depth.subsample),
                voxels.occupied(), userPosW, headingW, fy, config.corridor.widthM,
                config.geometry.maxDistanceM, config.floor.toleranceM, config.cluster, config.geometry,
            )
        return update.copy(geometry = geometry)
    }

    private fun updateMap(depth: DepthFrame, userPosW: Vec3, headingW: Vec3): MapUpdate {
        val depthMm = effectiveDepth(depth)
        lastDepthMm = depthMm
        val pts = Projection.backprojectToWorld(depthMm, depth.K, depth.worldFromCam, config.depth.subsample)
        val n = pts.size / 3
        val camW = depth.worldFromCam.translation()
        if (config.map.mode == MapMode.TRISTATE) {
            return if (config.map.triSameFrame) updateTriFrame(depth, depthMm, camW, userPosW, headingW) else updateTriState(depth, pts, camW, userPosW, headingW)
        }
        val planes = if (config.frontend.planes == PlaneMode.RANSAC) PlaneDetector.detect(pts, camW, config.frontend, config.map.radiusM) else null
        // M54: 라벨·원시 장(과거 값, 나이 한도 안)과 점마다 원시 신뢰도(지도·바닥 무게), 움직이는 라벨 여부(사람 원천)
        val labels54 = if (m54MovableRaw || obstacleRaw) freshLabels(depth) else null
        val raw54 = if (m54ConfWeights || m54FloorWeights || config.vision.voxelLabels || collectInserted || cellTrace != null || floorPlane || obstacleRaw) freshRaw(depth) else null
        val rawCamFromWorld = raw54?.worldFromCam?.rigidInverse()
        val confOf = if (raw54 == null) null else IntArray(n) { i -> rawConfidenceAt(raw54, rawCamFromWorld, Vec3(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2])) }
        val floorWeights = when {
            m54FloorWeights -> FloatArray(n) { i -> config.depth.floorConfidenceWeights[config.depth.confidenceClass(confOf?.get(i) ?: 0)] }
            config.floor.weighting == hearspace.core.types.FloorWeighting.DISTANCE ->
                FloatArray(n) { i -> voxels.weight(Vec3(pts[3 * i] - camW.x, pts[3 * i + 1] - camW.y, pts[3 * i + 2] - camW.z).norm()) }
            else -> null
        }
        val f = if (config.floor.source == FloorSource.PLANE) {
            val pf = planes?.floor
            // 바닥을 한 번도 못 잡았으면(시작·자세 불연속 뒤) 평면 바닥이 보일 때까지 히스토그램으로 시작한다. 안 그러면 맵이 안 갱신된다
            // (M13.1c 실측: 시작 때 바닥 없음 프레임 S01 32%·S02 41%·E02-3 42%, S01 첫 STOP 1.26 → 0.78 m로 늦어짐)
            if (pf == null && floor.floorY == null) floor.update(pts, camW) else floor.updateFromPlane(pf?.heightM, pf?.nInliers ?: 0)
        } else {
            floor.update(pts, camW, floorWeights)
        }
        // M55 바닥 평면: 새 원시 장마다 원시 바닥 점으로 평면을 맞추고, 하류가 쓰는 바닥 높이 하나는 카메라 자리 평면 높이로
        if (floorPlane && f.floorY != null) {
            if (raw54 != null && raw54.tCaptureNs != lastPlaneRawTNs) {
                lastPlaneRawTNs = raw54.tCaptureNs
                // 최근 `floor.planeWindowS` 동안의 원시 바닥 후보를 모아 맞춘다(장 하나는 바닥 점이 수십~수백 개라 평평한 세션에서도 2~11° 기울었음)
                planeWindow.addLast(raw54.tCaptureNs to rawFloorCandidates(raw54))
                val keepNs = (config.floor.planeWindowS * 1e9).toLong()
                while (planeWindow.isNotEmpty() && raw54.tCaptureNs - planeWindow.first().first > keepNs) planeWindow.removeFirst()
                val cand = FloatArray(planeWindow.sumOf { it.second.size }).also { a ->
                    var o = 0
                    for ((_, c) in planeWindow) { c.copyInto(a, o); o += c.size }
                }
                val fitted = floor.updatePlane(cand, camW)
                floor.plane?.let { lastFramePlane = FramePlane(it[0], it[1], it[2], cand.size / 3, fitted) }
            }
            floor.alignToCamera(camW)
        }
        if (f.floorY == null) {
            return MapUpdate(depth.tCaptureNs, n, 0, 0, 0, 0, PruneCounts(0, 0, 0), voxels.size, null, MapHealth.DEGRADED, planes)
        }

        val floorY = floor.floorY!!
        val wallLabels = if (config.cluster.separateWalls) planes?.labels else null
        val segments = if (config.frontend.segment == SegmentMode.REGION) {
            Segmenter.segment(depthMm, depth.K, depth.worldFromCam, config.depth.subsample, config.frontend.edgeMinStepRatio, config.frontend.edgeFitTolRatio, config.cluster.minSamples,
            ) { i, p ->
                val camDist = (p - camW).horizontal().norm()
                floor.isUnder(p) || floor.isFloor(p, camDist) || planes?.labels?.get(i) == PlaneResult.WALL
            }
        } else {
            null
        }
        voxels.beginFrame()
        cellTrace?.beginFrame()
        frameObstaclePoints.clear()
        insertedVoxels.clear()
        // 칸 라벨 표: 나이 한도 안의 새 라벨 영상일 때만 이번 장 칸들이 한 표씩(16 Hz 라벨, 30 Hz 깊이)
        labelsForVotes = if (config.vision.voxelLabels) freshLabels(depth)?.takeIf { it.tCaptureNs != lastVotedLabelTNs } else null
        val instOf = if (config.map.instances && segments != null) associate(pts, n, camW, userPosW, floorY, segments, depthMm, depth) else null
        var nFloor = 0
        var nBelow = 0
        var nInserted = 0
        var nMovable = 0
        val radius = config.map.radiusM
        for (i in 0 until n) {
            val p = Vec3(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2])
            val camDist = (p - camW).horizontal().norm()
            when {
                floor.isFloor(p, camDist) -> nFloor++
                floor.isBelowFloor(p, camDist) -> nBelow++
                floor.isUnder(p) -> Unit // 바닥보다 낮은데 단차로도 확실하지 않은 점: 장애물일 수 없으므로 버린다
                (p - userPosW).horizontal().norm() > radius -> Unit
                else -> {
                    val wDist = voxels.weight((p - camW).norm())
                    // 후보 track 세기용 이번 장 점은 M49 그대로 거리 무게(설계 3.2절). 신뢰도 무게는 지도에만: 다가오는 사람 화소는 원시와 시각이 어긋나
                    // 낮은 구간으로 읽혀, 후보에도 주면 표가 모이지 않아 사람이 움직이는 track이 되지 못했다(T 150159 STOP 사라짐, 구현 결함)
                    val w = if (m54ConfWeights) config.depth.mapConfidenceWeights[config.depth.confidenceClass(confOf?.get(i) ?: 0)] else wDist
                    // 사람 원천(M54 P): 움직일 수 있는 라벨 평활 점은 후보 세기용으로만 남기고 지도에는 원시 점으로 넣는다.
                    // 장애물 원천(M55 O): 장애물 묶음 평활 점 중 그 자리 원시(신뢰도 ≥ movableRawMinConfidence)가 있는 것만 원시가 대신하고(지도·track 모두),
                    // 원시가 없는 것은 평활 점으로 채운다
                    val rawCovered = labels54 != null && labels54.pixelAt(p) in rawPixels &&
                        (!fillSmoothed || (confOf?.get(i) ?: 0) >= config.vision.movableRawMinConfidence)
                    if (collectFramePoints && !(obstacleRaw && rawCovered)) frameObstaclePoints += p to wDist
                    if (excludeBoxes.isNotEmpty() && excludeBoxes.any { (lo, hi) -> p.x in lo.x..hi.x && p.y in lo.y..hi.y && p.z in lo.z..hi.z }) continue
                    if (rawCovered) {
                        nMovable++
                        continue
                    }
                    voxels.insert(
                        p, depth.tCaptureNs, w, wallLabels?.get(i) == PlaneResult.WALL,
                        camW, floorY + config.floor.toleranceM,
                    )
                    cellTrace?.insert(p, depth.tCaptureNs, w, config.depth.confidenceClass(confOf?.get(i) ?: 0))
                    noteInserted(p)
                    nInserted++
                    if (instOf != null) instOf.first[i].let { s -> if (s > 0) voxels.voteInstance(p, instOf.second.getValue(s)) }
                }
            }
        }
        lastMovableExcluded = nMovable
        val rawMov = if (labels54 != null) freshRaw(depth) else null
        lastMovableRawInserted = if (rawMov != null) insertMovableRaw(rawMov, labels54!!, camW, userPosW, floorY) else 0
        // O: track에 넘기는 이번 장 점에도 같은 깊이 — 원시가 대신한 장애물 점은 가장 최근 원시 장(나이 한도 안)의 원시 점으로(원시 약 10 Hz, 평활 30 Hz)
        if (obstacleRaw && collectFramePoints && rawMov != null) frameObstaclePoints += lastRawFramePts
        if (config.vision.voxelLabels) freshLabels(depth)?.let { fitFramePlane(pts, n, confOf, it, camW) }
        if (labelsForVotes != null) {
            commitLabels()
            lastVotedLabelTNs = labelsForVotes!!.tCaptureNs
        }
        // 감쇠 무게(M54 결과 검토): 그 칸을 비었다고 본 화소의 원시 신뢰도 구간 = 표와 같은 규칙(원시 없음·나이 초과면 원시 없음 구간)
        val pixCls = if (m54DecayWeights || collectInserted || cellTrace != null) IntArray(depthMm.size) { -1 } else null
        fun clsAt(px: Int): Int {
            val c = pixCls!![px]
            if (c >= 0) return c
            val z = (depthMm[px].toInt() and 0xFFFF) / 1000f
            val k = depth.K
            val pW = depth.worldFromCam.transformPoint(Vec3((px % k.width - k.cx) / k.fx * z, (px / k.width - k.cy) / k.fy * z, z))
            return config.depth.confidenceClass(rawConfidenceAt(raw54, rawCamFromWorld, pW)).also { pixCls[px] = it }
        }
        voxels.collectDecays = collectInserted
        val decayDepth = if (labels54 != null) withoutMovable(depthMm, depth, labels54, raw54, rawCamFromWorld) else depthMm
        val camFromWorld = depth.worldFromCam.rigidInverse()
        var nDecayed = voxels.decayFree(
            decayDepth, depth.K, camFromWorld,
            if (m54DecayWeights) { px -> config.depth.mapConfidenceWeights[clsAt(px)] } else null,
        )
        decayTrace.clear()
        if (collectInserted) {
            val d = voxels.decayedVoxels
            for (j in 0 until d.size / 4) {
                decayTrace += d[4 * j]; decayTrace += d[4 * j + 1]; decayTrace += d[4 * j + 2]; decayTrace += clsAt(d[4 * j + 3])
            }
        }
        // O: 원시가 대신한 장애물 화소의 빈 곳 감쇠는 새 원시 장마다 그 원시 깊이로(평활 감쇠에서는 뺐다). 빼기만 하면 물체 앞 헛 복셀이 지워지지 않는다.
        // ponytail: 감쇠 무게(`map.decayWeighting`)는 이 원시 감쇠에 주지 않음 — 그 옵션과 O를 함께 켤 때 다시 본다
        val rawDecay = if (obstacleRaw && rawMov != null && rawMov.tCaptureNs != lastRawDecayTNs) rawMov else null
        if (rawDecay != null) {
            lastRawDecayTNs = rawDecay.tCaptureNs
            nDecayed += voxels.decayFree(rawObstacleDepth(rawDecay, labels54!!), rawDecay.K, rawDecay.worldFromCam.rigidInverse(), null)
        }
        val pruned = voxels.prune(userPosW, headingW, depth.tCaptureNs)
        cellTrace?.endFrame(depth.tCaptureNs, camW, decayDepth, depth.K, camFromWorld, ::clsAt, userPosW, headingW, voxels)
        return MapUpdate(
            depth.tCaptureNs, n, nFloor, nBelow, nInserted, nDecayed, pruned, voxels.size, floor.floorY, MapHealth.OK, planes, segments, null,
            lastRgbEdgeCounts,
        )
    }

    /**
     * TRISTATE(M32): 지도 앞에서 바닥을 판단하지 않는다(프로포절 "같은 처리"). 바닥 추정 없이 모든 점(반경 안)을 같은 규칙으로 넣는다.
     * 관측 가중 = min(1, (반 칸 / σ(Z))²), 빈 곳 여유 = max(τ, `map.sigmaMultiple` × σ(Z)). 돌려주는 바닥은 갱신한 지도에서 구한다
     * ([floorFromMap], 안내의 통로·바닥 띠용 임시 규칙, 요소 분해 M35에서 바꾼다). 지도에서 바닥을 못 찾으면 null(`DEGRADED`).
     */
    private fun updateTriState(depth: DepthFrame, pts: FloatArray, camW: Vec3, userPosW: Vec3, headingW: Vec3): MapUpdate {
        require(depth.pixelSource == null) { "화소 출처(학습형 장, M37)는 map.triSameFrame 갱신만 다룬다" }
        val n = pts.size / 3
        val raw = config.depth.source == DepthSource.RAW
        val camFromWorld = depth.worldFromCam.rigidInverse()
        val halfVoxel = config.map.voxelSizeM / 2f
        val tau = config.map.voxelSizeM * kotlin.math.sqrt(3f) / 2f
        voxels.beginFrame()
        var nInserted = 0
        for (i in 0 until n) {
            val p = Vec3(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2])
            if ((p - userPosW).horizontal().norm() > config.map.radiusM) continue
            val sigma = config.depth.sigmaM(raw, camFromWorld.transformPoint(p).z)
            val w = if (sigma <= halfVoxel) 1f else (halfVoxel / sigma).let { it * it }
            voxels.insertRay(p, camW, depth.tCaptureNs, w, kotlin.math.max(tau, config.map.sigmaMultiple * sigma))
            nInserted++
        }
        val pruned = voxels.prune(userPosW, headingW, depth.tCaptureNs)
        val floorY = floorFromMap(userPosW, camW)
        return MapUpdate(
            depth.tCaptureNs, n, 0, 0, nInserted, 0, pruned, voxels.size, floorY, if (floorY == null) MapHealth.DEGRADED else MapHealth.OK,
        )
    }

    /**
     * TRISTATE M33 갱신(`map.triSameFrame`): 점유 격자 모형의 성질 ①~⑤(M33 설계 3.6절)를 갖춘 한 장 갱신. 화소마다 관측 가중과 빈 곳 여유를
     * 깊이 오차 모형에서 준다. 가중 w = min(1, (반 칸 / σ)²)(②, `map.triWeight` SIGMA_INCIDENCE면 σ(Z, θ), DISTANCE는 비교 줄), 여유 =
     * max(τ / cos θ, |b(Z)| + 배수 × σ)(③, `map.triMargin`이 아니면 M32 max(τ, 배수 × σ)). 점과 화소는 [Projection.backprojectToWorld]와 같은 순서로 고른다.
     */
    private fun updateTriFrame(depth: DepthFrame, depthMm: ShortArray, camW: Vec3, userPosW: Vec3, headingW: Vec3): MapUpdate {
        val m = config.map
        val raw = config.depth.source == DepthSource.RAW
        val k = depth.K
        val halfVoxel = m.voxelSizeM / 2f
        val tau = m.voxelSizeM * kotlin.math.sqrt(3f) / 2f
        fun cos(u: Int, w: Int) = Projection.cosIncidence(depthMm, k, u, w, m.tsdfMinCosIncidence)
        // 화소 출처(M37 학습형 장): 없으면 모두 측정값이고 아래 계산은 지금과 같다
        val src = depth.pixelSource
        if (src != null && !config.depth.learnedSigmaSet && src.any { it.toInt() == DepthFrame.PIXEL_LEARNED }) {
            error("학습형 화소가 있는데 depth.learnedSigma0M·learnedSigmaPerM2를 재서 주지 않았다(M37)")
        }
        fun srcOf(u: Int, w: Int): Int = src?.get(w * k.width + u)?.toInt() ?: DepthFrame.PIXEL_MEASURED
        fun sigma(u: Int, w: Int, z: Float): Float = if (m.triWeight == TriWeight.SIGMA_INCIDENCE) {
            val c = cos(u, w)
            config.depth.sigmaM(raw, z, srcOf(u, w), kotlin.math.sqrt(1f - c * c) / c, k.fx)
        } else {
            config.depth.sigmaM(raw, z, srcOf(u, w), null, k.fx)
        }
        fun weight(u: Int, w: Int, z: Float, distM: Float): Float =
            if (m.triWeight == TriWeight.DISTANCE) voxels.weight(distM) else sigma(u, w, z).let { s -> if (s <= halfVoxel) 1f else (halfVoxel / s).let { it * it } }
        fun margin(u: Int, w: Int, z: Float): Float = if (m.triMargin) {
            kotlin.math.max(tau / cos(u, w), kotlin.math.abs(config.depth.biasM(raw, z, srcOf(u, w))) + m.sigmaMultiple * sigma(u, w, z))
        } else {
            kotlin.math.max(tau, m.sigmaMultiple * sigma(u, w, z))
        }
        val pts = ArrayList<Float>()
        val ws = ArrayList<Float>()
        val ms = ArrayList<Float>()
        val s = config.depth.subsample
        var nAll = 0
        for (v in 0 until k.height step s) for (u in 0 until k.width step s) {
            val mm = depthMm[v * k.width + u].toInt() and 0xFFFF
            if (mm == 0) continue
            nAll++
            val z = mm / 1000f
            val p = depth.worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), z, k))
            if ((p - userPosW).horizontal().norm() > m.radiusM) continue
            pts += p.x; pts += p.y; pts += p.z
            ws += weight(u, v, z, (p - camW).norm())
            ms += margin(u, v, z)
        }
        val proj = if (m.triFree == TriFree.PROJECTION) {
            VoxelMap.TriProjection(depthMm, k, depth.worldFromCam.rigidInverse(), { u, w, z ->
                val p = depth.worldFromCam.transformPoint(Projection.backproject(u.toFloat(), w.toFloat(), z, k))
                weight(u, w, z, (p - camW).norm())
            }, ::margin)
        } else {
            null
        }
        voxels.beginFrame()
        voxels.insertTriFrame(pts.toFloatArray(), ws.toFloatArray(), ms.toFloatArray(), camW, depth.tCaptureNs, proj)
        val pruned = voxels.prune(userPosW, headingW, depth.tCaptureNs)
        val floorY = floorFromMap(userPosW, camW)
        return MapUpdate(
            depth.tCaptureNs, nAll, 0, 0, ws.size, 0, pruned, voxels.size, floorY, if (floorY == null) MapHealth.DEGRADED else MapHealth.OK,
        )
    }

    /**
     * 지도에서 구한 바닥 높이(M32 임시 규칙, M35 요소 분해 전까지): 사용자에서 수평 `corridor.lengthM` 안, 카메라보다 `floor.minBelowCameraM`
     * 이상 낮은 점유 칸을 높이 칸(복셀)별로 세어 가장 많은 층을 바닥 층으로 본다(바닥은 넓게 퍼진 수평 층이고, 벽은 층마다 몇 칸씩만 있다).
     * 그 층과 위아래 한 층 칸 중심의 평균 높이를 쓴다(바닥면이 칸 경계에 놓이면 두 층에 나뉜다). 가장 많은 층이 `cluster.minSamples`칸보다
     * 적으면 null. 같은 규칙이 지도 전체에 쌓인 관측을 쓰므로, 끝 벽 앞에서 바닥이 안 보여도 앞서 본 바닥 칸으로 유지된다.
     */
    fun floorFromMap(userPosW: Vec3, cameraW: Vec3): Float? {
        val maxD = config.corridor.lengthM
        val top = cameraW.y - config.floor.minBelowCameraM
        val layers = HashMap<Int, Int>()
        for (v in voxels.occupied()) {
            if (v.centerW.y >= top || (v.centerW - userPosW).horizontal().norm() > maxD) continue
            layers.merge(v.iy, 1, Int::plus)
        }
        val (mode, count) = layers.maxWithOrNull(compareBy({ it.value }, { -it.key }))?.toPair() ?: return null
        if (count < config.cluster.minSamples) return null
        var sum = 0.0
        var k = 0
        for (iy in mode - 1..mode + 1) {
            val c = layers[iy] ?: continue
            sum += (iy + 0.5) * config.map.voxelSizeM * c
            k += c
        }
        return (sum / k).toFloat()
    }

    private var nextInstance = 1

    /** 지도에 넣을 점인지(삽입 반복문과 같은 조건). */
    private fun insertable(p: Vec3, camW: Vec3, userPosW: Vec3, floorY: Float): Boolean {
        val camDist = (p - camW).horizontal().norm()
        return !floor.isFloor(p, camDist) && !floor.isBelowFloor(p, camDist) && !floor.isUnder(p) &&
            (p - userPosW).horizontal().norm() <= config.map.radiusM
    }

    /**
     * 인스턴스 지도(C3b): 영역마다 그 점이 든 칸의 기존 물체 번호를 세어, 번호가 붙은 점의 과반이 한 번호이고 그 수가
     * `cluster.minSamples` 이상이면 그 번호를 이어받고, 아니면 새 번호를 낸다(Voxblox++의 겹침 연결). 한 영역이 다른 번호에도
     * `cluster.minSamples` 이상 걸치면 같은 물체를 둘로 나눠 갖던 것으로 보고 그 번호를 이어받은 번호로 합친다(인스턴스 병합: 손목을
     * 흔들며 본 상자의 앞면·윗면이 두 번호로 갈라졌다, SC-23). 영역은 깊이 불연속에서 끊기므로 다른 물체를 잇는 경우는 드물다.
     * 돌려주는 값은 점마다 영역 번호와 영역 → 물체 번호.
     */
    private fun associate(
        pts: FloatArray, n: Int, camW: Vec3, userPosW: Vec3, floorY: Float, segments: Segmenter.Result, depthMm: ShortArray, depth: DepthFrame,
    ): Pair<IntArray, Map<Int, Int>> {
        val seg = segments.pointLabels(depthMm, depth.K)
        val votes = HashMap<Int, HashMap<Int, Int>>() // 영역 → (기존 번호 → 점 수)
        val labeled = IntArray(segments.count + 1)
        for (i in 0 until n) {
            val s = seg[i]
            if (s == 0) continue
            val p = Vec3(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2])
            if (!insertable(p, camW, userPosW, floorY)) continue
            val id = voxels.instanceAt(p)
            if (id == 0) continue
            labeled[s]++
            votes.getOrPut(s) { HashMap() }.merge(id, 1, Int::plus)
        }
        val out = HashMap<Int, Int>()
        val merged = HashMap<Int, Int>() // 합쳐진 번호 → 남는 번호
        fun root(id: Int): Int = merged[id]?.let { root(it) } ?: id
        for (s in 1..segments.count) {
            val vs = votes[s]
            val best = vs?.maxWithOrNull(compareBy({ it.value }, { -it.key }))
            if (best == null || best.value * 2 <= labeled[s] || best.value < config.cluster.minSamples) {
                out[s] = nextInstance++
                continue
            }
            val keep = root(best.key)
            out[s] = keep
            for ((id, c) in vs) {
                val r = root(id)
                if (r != keep && c >= config.cluster.minSamples) {
                    merged[r] = keep
                    voxels.relabelInstance(r, keep)
                }
            }
        }
        for (s in out.keys) out[s] = root(out.getValue(s))
        return seg to out
    }

    /** 마지막 RGB 경계 옮기기(M23)의 경계 수(전체, 바닥 건너뜀, RGB 경계 있음). 평가 기록용, EDGE·EDGE_MARGIN이 아니면 null. */
    var lastRgbEdgeCounts: IntArray? = null
        private set

    // ---- M54 지도 앞단(설계 3.2~3.4절). 모두 끄면 기준선과 같다 ----

    private val m54MovableRaw = config.vision.movableDepth == hearspace.core.types.MovableDepth.RAW
    private val m54ConfWeights = config.map.hitWeighting == hearspace.core.types.HitWeighting.CONFIDENCE
    private val m54DecayWeights = config.map.decayWeighting == hearspace.core.types.DecayWeighting.CONFIDENCE
    private val m54FloorWeights = config.floor.weighting == hearspace.core.types.FloorWeighting.CONFIDENCE
    private val movingPixels = config.vision.movingLabels.map { it.pixel }.toSet()

    // ---- M55 ②-A 장애물 원천 O(`vision.obstacleDepth` RAW): M54 P를 장애물 묶음 전체로 넓히고, 원시가 없는 화소는 평활로 채운다 ----
    private val obstacleRaw = config.vision.obstacleDepth == hearspace.core.types.MovableDepth.RAW

    /** 장애물 라벨 화소를 원시가 대신하는지: P는 늘, O는 그 자리 원시(신뢰도 ≥ 하한)가 있을 때, O + `vision.obstacleFill` NONE은 늘(M56 P처럼 판). */
    private val fillSmoothed = obstacleRaw && config.vision.obstacleFill == hearspace.core.types.ObstacleFill.SMOOTHED

    /** 원시 점으로 지도에 넣는 라벨: O면 `vision.obstacleLabels`, 아니면 P의 움직일 수 있는 라벨. */
    private val rawPixels: Set<Int> = if (obstacleRaw) config.vision.obstacleLabels.map { it.pixel }.toSet() else movingPixels

    /** 경계 띠의 장애물 역할 표(`frontend.bandLabels`, 기본은 M54 묶음). */
    private val bandObstacle = BooleanArray(hearspace.core.frontend.LabelBand.OBSTACLE.size) { v -> config.frontend.bandLabels.any { it.pixel == v } }

    /** O: 가장 최근 원시 장의 장애물 원시 점(track 이번 장 점용, 무게 = 원시 카메라 거리 무게). */
    private var lastRawFramePts: List<Pair<Vec3, Float>> = emptyList()
    private var lastRawDecayTNs = Long.MIN_VALUE

    /** O 감쇠용: 원시 장에서 장애물 묶음이고 신뢰도 ≥ `vision.movableRawMinConfidence`인 화소만 남긴 깊이. */
    private fun rawObstacleDepth(raw: DepthFrame, labels: hearspace.core.types.LabelImage): ShortArray {
        val k = raw.K
        val src = bandedRaw(raw, labels)
        val out = ShortArray(raw.depthMm.size)
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val i = v * k.width + u
            val mm = src[i].toInt() and 0xFFFF
            val conf = raw.confidence?.get(i)?.toInt()?.and(0xFF) ?: 255
            if (mm == 0 || conf < config.vision.movableRawMinConfidence) continue
            if (labels.pixelAt(raw.worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), mm / 1000f, k))) in rawPixels) out[i] = src[i]
        }
        return out
    }

    /** O: 원시 장에 평활과 같은 경계 띠(`frontend.labelBandPx`, 같은 라벨 영상의 거리 마당)를 깎은 깊이. 띠를 끄면 그대로. */
    private fun bandedRaw(raw: DepthFrame, labels: hearspace.core.types.LabelImage): ShortArray {
        val b = config.frontend.labelBandPx
        if (b <= 0) return raw.depthMm
        val field = bandField?.takeIf { it.image === labels } ?: hearspace.core.frontend.LabelBand.field(labels, raw.K.fx, b, bandObstacle).also { bandField = it }
        return hearspace.core.frontend.LabelBand.apply(raw.depthMm, raw, field).first
    }
    private val maxAgeNs = config.vision.maxLabelAgeMs * 1_000_000L

    /** 마지막 [update]에서 경계 띠가 뺀 화소 수, 지도에서 뺀 움직이는 라벨 점 수, 넣은 움직이는 라벨 원시 점 수(평가 기록용). */
    var lastBandRemoved = 0
        private set
    var lastMovableExcluded = 0
        private set
    var lastMovableRawInserted = 0
        private set

    // ---- M55 바닥 평면(`floor.model` PLANE_RAW) ----
    private val floorPlane = config.floor.model == hearspace.core.types.FloorModel.PLANE_RAW
    private var lastPlaneRawTNs = Long.MIN_VALUE
    private val planeWindow = ArrayDeque<Pair<Long, FloatArray>>()

    /** 바닥 평면 후보: 원시 점 중 신뢰도 ≥ `floor.planeMinConfidence`, 원시 카메라보다 `floor.minBelowCameraM` 넘게 낮고 수평 `map.radiusM` 안, 지금 바닥 ± `floor.searchBandM` 안. */
    private fun rawFloorCandidates(raw: DepthFrame): FloatArray {
        val k = raw.K
        val s = config.depth.subsample
        val cam = raw.worldFromCam.translation()
        val out = ArrayList<Float>()
        var v = 0
        while (v < k.height) {
            var u = 0
            while (u < k.width) {
                val i = v * k.width + u
                val mm = raw.depthMm[i].toInt() and 0xFFFF
                val conf = raw.confidence?.get(i)?.toInt()?.and(0xFF) ?: 255
                if (mm != 0 && conf >= config.floor.planeMinConfidence) {
                    val p = raw.worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), mm / 1000f, k))
                    val h = floor.heightAt(p.x, p.z)
                    if (p.y < cam.y - config.floor.minBelowCameraM && (p - cam).horizontal().norm() <= config.map.radiusM &&
                        (h == null || kotlin.math.abs(p.y - h) <= config.floor.searchBandM)
                    ) { out += p.x; out += p.y; out += p.z }
                }
                u += s
            }
            v += s
        }
        return out.toFloatArray()
    }

    /** 칸 관측 기록(M55, 평가 전용 `-PcellTrace=true`). null이면 끔. */
    var cellTrace: CellTrace? = null

    /** true면 [update]가 이번 장에 지도에 넣은 칸 번호(ix, iy, iz 교차)를 [insertedVoxels]에 모은다(M54 평가 `-PmapInserts=true`). */
    var collectInserted = false
    val insertedVoxels = ArrayList<Int>()

    /** [collectInserted]일 때 이번 장에 감쇠한 칸과 그 칸을 비었다고 본 화소의 신뢰도 구간(ix, iy, iz, 구간 교차; 0 = 원시 없음). */
    val decayTrace = ArrayList<Int>()

    /** 칸 라벨 표(M54 3.5절): 이번 장에 지도에 넣은 점의 칸마다 라벨 값 수. 새 라벨 영상일 때만 장 끝에 칸마다 가장 많은 라벨로 한 표. */
    private val frameLabels = HashMap<Long, IntArray>()
    private var labelsForVotes: hearspace.core.types.LabelImage? = null
    private var lastVotedLabelTNs = Long.MIN_VALUE

    private fun noteLabel(p: Vec3) {
        val img = labelsForVotes ?: return
        val l = img.pixelAt(p)
        if (l < 0) return
        val vs = config.map.voxelSizeM
        val k = (kotlin.math.floor(p.x / vs).toLong() and 0x1FFFFF) or ((kotlin.math.floor(p.y / vs).toLong() and 0x1FFFFF) shl 21) or ((kotlin.math.floor(p.z / vs).toLong() and 0x1FFFFF) shl 42)
        // 칸 대표점(그 칸에 처음 든 점)을 기억: 표는 대표점의 칸에 준다
        val c = frameLabels.getOrPut(k) { frameRep[k] = p; IntArray(12) }
        if (l < 12) c[l]++
    }

    private val frameRep = HashMap<Long, Vec3>()

    /** 프레임 바닥면(M54 3.7절, 기록만, `vision.voxelLabels`일 때): 바닥류 라벨 평활 점을 바닥 무게로 맞춘 면. 못 맞춘 장은 앞 면 유지(fresh = false). */
    var lastFramePlane: FramePlane? = null
        private set

    private fun fitFramePlane(pts: FloatArray, n: Int, confOf: IntArray?, labels: hearspace.core.types.LabelImage, camW: Vec3) {
        val sel = ArrayList<Int>()
        for (i in 0 until n) {
            val p = Vec3(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2])
            if ((p - camW).horizontal().norm() > config.map.radiusM) continue
            if (labels.pixelAt(p) in FLOORISH_PIXELS) sel += i
        }
        val sp = FloatArray(3 * sel.size)
        val w = FloatArray(sel.size)
        for ((j, i) in sel.withIndex()) {
            sp[3 * j] = pts[3 * i]; sp[3 * j + 1] = pts[3 * i + 1]; sp[3 * j + 2] = pts[3 * i + 2]
            w[j] = config.depth.floorConfidenceWeights[config.depth.confidenceClass(confOf?.get(i) ?: 0)]
        }
        val fit = FrameRecords.fitPlane(sp, w, config.floor.minPoints)
        lastFramePlane = if (fit != null) FramePlane(fit.first, fit.second, fit.third, sel.size, true) else lastFramePlane?.copy(n = sel.size, fresh = false)
    }

    private fun commitLabels() {
        for ((k, c) in frameLabels) {
            var best = -1
            var n = 0
            for (l in 0 until 12) if (c[l] > n) { n = c[l]; best = l }
            if (best >= 0) voxels.voteLabel(frameRep.getValue(k), best)
        }
        frameLabels.clear()
        frameRep.clear()
    }

    private fun noteInserted(p: Vec3) {
        noteLabel(p)
        if (!collectInserted) return
        val vs = config.map.voxelSizeM
        insertedVoxels += kotlin.math.floor(p.x / vs).toInt()
        insertedVoxels += kotlin.math.floor(p.y / vs).toInt()
        insertedVoxels += kotlin.math.floor(p.z / vs).toInt()
    }

    /** M54 옵션 중 하나라도 켰는지(기록 파일 `m54.csv`). */
    val m54On = m54MovableRaw || m54ConfWeights || m54FloorWeights || config.frontend.labelBandPx > 0 || config.vision.voxelLabels || m54DecayWeights || obstacleRaw

    private var bandField: hearspace.core.frontend.LabelBand.Field? = null
    private var lastMovableRawTNs = Long.MIN_VALUE

    /** 깊이 장 시각에서 `vision.maxLabelAgeMs` 안의 의미 영상(과거 값). */
    private fun freshLabels(depth: DepthFrame) = depth.labels?.takeIf { kotlin.math.abs(depth.tCaptureNs - it.tCaptureNs) <= maxAgeNs }

    /** 깊이 장 시각에서 `vision.maxLabelAgeMs` 안의 원시 장(과거 값). */
    private fun freshRaw(depth: DepthFrame) = depth.raw?.takeIf { kotlin.math.abs(depth.tCaptureNs - it.tCaptureNs) <= maxAgeNs }

    /** 월드 점 [p]를 원시 장 [raw]에 투영한 화소의 신뢰도(0~255). 원시 없음(장 없음·영상 밖·깊이 0)이면 0. */
    private fun rawConfidenceAt(raw: DepthFrame?, camFromWorld: hearspace.core.geometry.Mat4?, p: Vec3): Int {
        if (raw == null || camFromWorld == null) return 0
        val c = camFromWorld.transformPoint(p)
        if (c.z <= 0.1f) return 0
        val k = raw.K
        val u = (k.fx * c.x / c.z + k.cx).toInt()
        val v = (k.fy * c.y / c.z + k.cy).toInt()
        if (u < 0 || v < 0 || u >= k.width || v >= k.height) return 0
        val i = v * k.width + u
        if ((raw.depthMm[i].toInt() and 0xFFFF) == 0) return 0
        return raw.confidence?.get(i)?.toInt()?.and(0xFF) ?: 255
    }

    /** 움직일 수 있는 라벨 화소를 0으로 만든 깊이(빈 곳 감쇠용, M54 사람 원천): 평활의 사람 깊이는 뒤처져 원시로 넣은 칸을 지운다. */
    /** 원시가 대신하는 화소를 뺀 감쇠 깊이: P면 움직일 수 있는 라벨 화소 모두, O면 장애물 묶음 화소 중 그 자리 원시가 있는 것. */
    private fun withoutMovable(
        depthMm: ShortArray, depth: DepthFrame, labels: hearspace.core.types.LabelImage, raw: DepthFrame?, rawCamFromWorld: hearspace.core.geometry.Mat4?,
    ): ShortArray {
        val k = depth.K
        val out = depthMm.copyOf()
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val i = v * k.width + u
            val mm = depthMm[i].toInt() and 0xFFFF
            if (mm == 0) continue
            val p = depth.worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), mm / 1000f, k))
            if (labels.pixelAt(p) in rawPixels && (!fillSmoothed || rawConfidenceAt(raw, rawCamFromWorld, p) >= config.vision.movableRawMinConfidence)) out[i] = 0
        }
        return out
    }

    /**
     * 사람 원천(M54 3.2절): 새 원시 장의 신뢰도 ≥ `vision.movableRawMinConfidence`이고 라벨이 움직일 수 있는 라벨인 원시 점을 지도에 한 번 넣는다
     * (같은 바닥·반경·움직이는 track 창 규칙). 무게는 CONFIDENCE면 1(아래 주석), 아니면 거리 무게.
     */
    private fun insertMovableRaw(raw: DepthFrame, labels: hearspace.core.types.LabelImage, camW: Vec3, userPosW: Vec3, floorY: Float): Int {
        if (raw.tCaptureNs <= lastMovableRawTNs) return 0
        lastMovableRawTNs = raw.tCaptureNs
        val k = raw.K
        val s = config.depth.subsample
        val rawCam = raw.worldFromCam.translation()
        val framePts = if (obstacleRaw) ArrayList<Pair<Vec3, Float>>() else null
        // O: 원시도 같은 경계 띠를 깎고 띠 안쪽만 쓴다(설계 3.6절 "띠를 깎고 안쪽은 원시"). 띠 안 원시는 평활과 30~50% 어긋났다(T 150439·150457)
        val rawMm = if (obstacleRaw) bandedRaw(raw, labels) else raw.depthMm
        var n = 0
        var v = 0
        while (v < k.height) {
            var u = 0
            while (u < k.width) {
                val i = v * k.width + u
                val mm = rawMm[i].toInt() and 0xFFFF
                val conf = raw.confidence?.get(i)?.toInt()?.and(0xFF) ?: 255
                if (mm != 0 && conf >= config.vision.movableRawMinConfidence) {
                    val p = raw.worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), mm / 1000f, k))
                    val take = labels.pixelAt(p) in rawPixels && insertable(p, camW, userPosW, floorY)
                    if (take && framePts != null) framePts += p to voxels.weight((p - rawCam).norm())
                    if (take && (excludeBoxes.isEmpty() || excludeBoxes.none { (lo, hi) -> p.x in lo.x..hi.x && p.y in lo.y..hi.y && p.z in lo.z..hi.z })) {
                        // CONFIDENCE: 움직이는 라벨 원시 점은 물체·벽 표가 아니라 따로 잰 표(설계 3.1절) — 쓰는 구간이 신뢰도 ≥ movableRawMinConfidence 하나뿐이라
                        // 그 구간이 기준(무게 1). 물체·벽 표(30~127 → 0.57)를 주면 10 Hz 원시만으로 사람 칸이 6표를 못 채워 사람이 지도에서 사라졌다(T 150159, 구현 결함).
                        // O는 `vision.obstacleRawWeight`(낮은 물체 STOP 요구에서 유도)
                        val w = if (m54ConfWeights) (if (obstacleRaw) config.vision.obstacleRawWeight else 1f) else voxels.weight((p - rawCam).norm())
                        voxels.insert(p, raw.tCaptureNs, w, false, rawCam, floorY + config.floor.toleranceM)
                        cellTrace?.insert(p, raw.tCaptureNs, w, config.depth.confidenceClass(conf))
                        noteInserted(p)
                        n++
                    }
                }
                u += s
            }
            v += s
        }
        if (framePts != null) lastRawFramePts = framePts
        return n
    }

    /** RGB 경계로 막·배경 당김 옮기기(M23, [RgbEdge]). 바닥 경계 판정은 지난 단계 바닥 추정으로 한다. */
    private fun rgbEdge(depth: DepthFrame, depthMm: ShortArray, margin: Boolean): RgbEdge.Result {
        val k = depth.K
        val camW = depth.worldFromCam.translation()
        return RgbEdge.refine(
            depthMm, k, depth.guide!!, depth.worldFromCam.rigidInverse().transformDir(Vec3.UP), config.frontend, margin,
        ) { p, z ->
            val pw = depth.worldFromCam.transformPoint(Vec3(((p % k.width) - k.cx) * z / k.fx, ((p / k.width) - k.cy) * z / k.fy, z))
            floor.isFloor(pw.y, (pw - camW).horizontal().norm())
        }
    }

    /** 맵·바닥을 모두 잊는다(자세 불연속, §7.5). */
    fun reset() {
        voxels.clear()
        planeWindow.clear()
        lastRawFramePts = emptyList()
        cellTrace?.noteReset()
        floor.reset()
    }

    /**
     * 지도·바닥에 넣을 깊이. 신뢰도가 있는 깊이(원시 깊이)면 `depth.minConfidence` 미만 픽셀을, 깊이 영상 앞단을 켜면
     * 경계 픽셀(막, M13.1)을 무효(0)로 만든 복사본. 무효는 관측 없음이라 빈 공간 감쇠도 하지 않는다. 그 외에는 원본.
     */
    private fun effectiveDepth(depth: DepthFrame): ShortArray {
        val conf = depth.confidence
        val min = config.depth.minConfidence
        var out = depth.depthMm
        if (conf != null && min > 0) {
            out = out.copyOf()
            for (i in out.indices) if ((conf[i].toInt() and 0xFF) < min) out[i] = 0
        }
        // 막 후보 = 경계 마스크. RGB 보정(M13.7)을 켜면 후보를 색이 같은 쪽 면의 깊이로 옮기고(윤곽을 살림), 경계 판정을 켜면 옮기지
        // 못한 후보만 지운다(둘 다 켜도 경계 판정 단독보다 막이 늘지 않음). EDGE·EDGE_MARGIN(M23)은 RGB 경계 위치로 먼저 옮기고
        // 남은 후보(RGB 경계 없음·바닥 경계)를 가중 중앙값으로. 지난 단계 바닥을 모르면(시작) 가중 중앙값만
        val guide = depth.guide
        val mode = config.frontend.rgbGuide
        val rgb = mode != RgbGuideMode.NONE && guide != null
        val edge = rgb && (mode == RgbGuideMode.EDGE || mode == RgbGuideMode.EDGE_MARGIN) && floor.floorY != null
        val refined = if (edge) rgbEdge(depth, out, mode == RgbGuideMode.EDGE_MARGIN) else null
        lastRgbEdgeCounts = refined?.let { intArrayOf(it.nEdges, it.nFloor, it.nRgb) }
        val mask = refined?.mask ?: if (config.frontend.enabled || rgb) EdgeDetector.boundaryMask(depth, config.frontend, out) else null
        var snapped: BooleanArray? = null
        if (rgb && mask != null) {
            val r = RgbGuide.snap(out, depth.K, guide!!, config.frontend, mask)
            out = r.depthMm
            snapped = r.snapped
            if (refined != null) {
                for (i in out.indices) if (refined.moved[i]) { out[i] = refined.depthMm[i]; snapped[i] = true }
            }
        }
        if (config.frontend.enabled && mask != null) {
            if (out === depth.depthMm) out = out.copyOf()
            for (i in out.indices) if (mask[i] && snapped?.get(i) != true) out[i] = 0
        }
        // M54 라벨 경계 띠: 원시 유무와 상관없이 장애물 역할 라벨 경계 b화소 안을 무효로(의미 영상이 나이 한도 안일 때만)
        lastBandRemoved = 0
        val b = config.frontend.labelBandPx
        val labels = if (b > 0) freshLabels(depth) else null
        if (labels != null) {
            val field = bandField?.takeIf { it.image === labels } ?: hearspace.core.frontend.LabelBand.field(labels, depth.K.fx, b, bandObstacle).also { bandField = it }
            // M56 깊이 경계 띠: 이 평활 장의 깊이 경계 근처에서만 뺀다(0 = 끔)
            val ns = config.frontend.bandDepthEdgeSigmas
            val near = if (ns > 0f) hearspace.core.frontend.LabelBand.depthEdgeNear(
                depth.depthMm, depth.K.width, depth.K.height, ns, config.depth.smoothedSigma0M, config.depth.smoothedSigmaPerM2, b,
            ) else null
            // M56 거리 비례 띠: 화소마다 폭 = 실제 폭을 경계 옆 가장 가까운 깊이에서 화소로(0 = 끔)
            val bw = config.frontend.bandWidthM
            val pxLim = when {
                config.frontend.bandNearM > 0f -> hearspace.core.frontend.LabelBand.stepByDepth(
                    depth.depthMm, depth.K.width, depth.K.height, config.frontend.bandNearM, config.frontend.bandFarPx, b,
                )
                bw > 0f -> hearspace.core.frontend.LabelBand.widthByDepth(
                    depth.depthMm, depth.K.width, depth.K.height, depth.K.fx, bw, config.frontend.bandMinPx, b,
                )
                else -> null
            }
            val (banded, removed) = hearspace.core.frontend.LabelBand.apply(out, depth, field, near, pxLimit = pxLim)
            out = banded
            lastBandRemoved = removed
        }
        return out
    }

    private companion object {
        /** 바닥류 라벨 화소 값(도로·보도·지면, ARCore SemanticLabel). */
        val FLOORISH_PIXELS = setOf(4, 5, 6)
    }
}
