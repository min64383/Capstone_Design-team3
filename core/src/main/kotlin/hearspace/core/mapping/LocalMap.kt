package hearspace.core.mapping

import hearspace.core.frontend.EdgeDetector
import hearspace.core.frontend.PlaneDetector
import hearspace.core.frontend.PlaneResult
import hearspace.core.frontend.RgbGuide
import hearspace.core.frontend.Segmenter
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.FloorSource
import hearspace.core.types.MapHealth
import hearspace.core.types.IndoorGeometryResult
import hearspace.core.types.PlaneMode
import hearspace.core.types.RgbGuideMode
import hearspace.core.types.SegmentMode

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
    val voxels = VoxelMap(config.map)

    /**
     * 깊이 한 장을 반영한다. [userPosW]·[headingW]는 삭제 조건(지나감·반경)의 기준(진행 방향 추정은 M5).
     * 바닥을 아직 모르면 맵을 갱신하지 않고 `DEGRADED`를 돌려준다(사용자 결정 2026-09-28).
     */
    fun update(depth: DepthFrame, userPosW: Vec3, headingW: Vec3): MapUpdate {
        val depthMm = effectiveDepth(depth)
        val pts = Projection.backprojectToWorld(depthMm, depth.K, depth.worldFromCam, config.depth.subsample)
        val n = pts.size / 3
        val camW = depth.worldFromCam.translation()
        val planes = if (config.frontend.planes == PlaneMode.RANSAC) PlaneDetector.detect(pts, camW, config.frontend, config.map.radiusM) else null
        val f = if (config.floor.source == FloorSource.PLANE) {
            val pf = planes?.floor
            // 바닥을 한 번도 못 잡았으면(시작·자세 불연속 뒤) 평면 바닥이 보일 때까지 히스토그램으로 시작한다. 안 그러면 맵이 안 갱신된다
            // (M13.1c 실측: 시작 때 바닥 없음 프레임 S01 32%·S02 41%·E02-3 42%, S01 첫 STOP 1.26 → 0.78 m로 늦어짐)
            if (pf == null && floor.floorY == null) floor.update(pts, camW) else floor.updateFromPlane(pf?.heightM, pf?.nInliers ?: 0)
        } else {
            floor.update(pts, camW)
        }
        if (f.floorY == null) {
            return MapUpdate(depth.tCaptureNs, n, 0, 0, 0, 0, PruneCounts(0, 0, 0), voxels.size, null, MapHealth.DEGRADED, planes)
        }

        val floorY = f.floorY
        val wallLabels = if (config.cluster.separateWalls) planes?.labels else null
        val segments = if (config.frontend.segment == SegmentMode.REGION) {
            Segmenter.segment(depthMm, depth.K, depth.worldFromCam, config.depth.subsample, config.frontend.edgeMinStepRatio, config.frontend.edgeFitTolRatio, config.cluster.minSamples,
            ) { i, p ->
                val camDist = (p - camW).horizontal().norm()
                p.y < floorY || floor.isFloor(p.y, camDist) || planes?.labels?.get(i) == PlaneResult.WALL
            }
        } else {
            null
        }
        voxels.beginFrame()
        val instOf = if (config.map.instances && segments != null) associate(pts, n, camW, userPosW, floorY, segments, depthMm, depth) else null
        var nFloor = 0
        var nBelow = 0
        var nInserted = 0
        val radius = config.map.radiusM
        for (i in 0 until n) {
            val p = Vec3(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2])
            val camDist = (p - camW).horizontal().norm()
            when {
                floor.isFloor(p.y, camDist) -> nFloor++
                floor.isBelowFloor(p.y, camDist) -> nBelow++
                p.y < floorY -> Unit // 바닥보다 낮은데 단차로도 확실하지 않은 점: 장애물일 수 없으므로 버린다
                (p - userPosW).horizontal().norm() > radius -> Unit
                else -> {
                    voxels.insert(
                        p, depth.tCaptureNs, voxels.weight((p - camW).norm()), wallLabels?.get(i) == PlaneResult.WALL,
                        camW, floorY + config.floor.toleranceM,
                    )
                    nInserted++
                    if (instOf != null) instOf.first[i].let { s -> if (s > 0) voxels.voteInstance(p, instOf.second.getValue(s)) }
                }
            }
        }
        val nDecayed = voxels.decayFree(depthMm, depth.K, depth.worldFromCam.rigidInverse())
        val pruned = voxels.prune(userPosW, headingW, depth.tCaptureNs)
        val geometry = if (config.geometry.enabled) {
            IndoorGeometryAnalyzer.analyze(
                pointsW = pts,
                occupied = voxels.occupied(),
                userPosW = userPosW,
                headingW = headingW,
                floorY = floorY,
                corridorWidthM = config.corridor.widthM,
                maxAlongM = config.policy.silentMaxM,
                floorToleranceM = config.floor.toleranceM,
                cluster = config.cluster,
                cfg = config.geometry,
            )
        } else null
        return MapUpdate(
            depth.tCaptureNs, n, nFloor, nBelow, nInserted, nDecayed, pruned, voxels.size, floor.floorY, MapHealth.OK, planes, segments, geometry,
        )
    }

    private var nextInstance = 1

    /** 지도에 넣을 점인지(삽입 반복문과 같은 조건). */
    private fun insertable(p: Vec3, camW: Vec3, userPosW: Vec3, floorY: Float): Boolean {
        val camDist = (p - camW).horizontal().norm()
        return !floor.isFloor(p.y, camDist) && !floor.isBelowFloor(p.y, camDist) && p.y >= floorY &&
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

    /** 맵·바닥을 모두 잊는다(자세 불연속, §7.5). */
    fun reset() {
        voxels.clear()
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
        // 못한 후보만 지운다(둘 다 켜도 경계 판정 단독보다 막이 늘지 않음)
        val guide = depth.guide
        val rgb = config.frontend.rgbGuide == RgbGuideMode.WEIGHTED_MEDIAN && guide != null
        val mask = if (config.frontend.enabled || rgb) EdgeDetector.boundaryMask(depth, config.frontend, out) else null
        var snapped: BooleanArray? = null
        if (rgb && mask != null) {
            val r = RgbGuide.snap(out, depth.K, guide!!, config.frontend, mask)
            out = r.depthMm
            snapped = r.snapped
        }
        if (config.frontend.enabled && mask != null) {
            if (out === depth.depthMm) out = out.copyOf()
            for (i in out.indices) if (mask[i] && snapped?.get(i) != true) out[i] = 0
        }
        return out
    }
}
