package hearspace.core.mapping

import hearspace.core.frontend.EdgeDetector
import hearspace.core.frontend.PlaneDetector
import hearspace.core.frontend.PlaneResult
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.FloorSource
import hearspace.core.types.MapHealth
import hearspace.core.types.PlaneMode

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
)

/**
 * 느린 경로의 맵 부분: 역투영 → 월드 → 바닥 → 복셀 갱신(관측·감쇠·정리) (§7.2, §7.3).
 * 스레드를 모르고, 입력 깊이의 `tCaptureNs`만 시각으로 쓴다(같은 입력이면 같은 결과).
 * 군집·추적은 M4에서 `SlowPath`가 이 위에 조립한다.
 */
class LocalMap(private val config: Config) {

    /** 바닥 추정기. */
    val floor = Floor(config.floor, config.map.radiusM, config.map.weightRefM)

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
        voxels.beginFrame()
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
                    voxels.insert(p, depth.tCaptureNs, voxels.weight((p - camW).norm()))
                    nInserted++
                }
            }
        }
        val nDecayed = voxels.decayFree(depthMm, depth.K, depth.worldFromCam.rigidInverse())
        val pruned = voxels.prune(userPosW, headingW, depth.tCaptureNs)
        return MapUpdate(
            depth.tCaptureNs, n, nFloor, nBelow, nInserted, nDecayed, pruned, voxels.size, floor.floorY, MapHealth.OK, planes,
        )
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
        if (config.frontend.enabled) {
            val mask = EdgeDetector.boundaryMask(depth, config.frontend, out)
            if (out === depth.depthMm) out = out.copyOf()
            for (i in out.indices) if (mask[i]) out[i] = 0
        }
        return out
    }
}
