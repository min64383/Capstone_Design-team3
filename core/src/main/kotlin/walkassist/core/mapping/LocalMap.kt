package walkassist.core.mapping

import walkassist.core.geometry.Projection
import walkassist.core.geometry.Vec3
import walkassist.core.types.Config
import walkassist.core.types.DepthFrame
import walkassist.core.types.MapHealth

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
        val f = floor.update(pts, depth.worldFromCam.translation())
        if (f.floorY == null) {
            return MapUpdate(depth.tCaptureNs, n, 0, 0, 0, 0, PruneCounts(0, 0, 0), voxels.size, null, MapHealth.DEGRADED)
        }

        val floorY = f.floorY
        voxels.beginFrame()
        var nFloor = 0
        var nBelow = 0
        var nInserted = 0
        val radius = config.map.radiusM
        val camW = depth.worldFromCam.translation()
        for (i in 0 until n) {
            val p = Vec3(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2])
            val camDist = (p - camW).horizontal().norm()
            when {
                floor.isFloor(p.y, camDist) -> nFloor++
                floor.isBelowFloor(p.y, camDist) -> nBelow++
                p.y < floorY -> Unit // 바닥보다 낮은데 단차로도 확실하지 않은 점: 장애물일 수 없으므로 버린다
                (p - userPosW).horizontal().norm() > radius -> Unit
                else -> {
                    voxels.insert(p, depth.tCaptureNs)
                    nInserted++
                }
            }
        }
        val nDecayed = voxels.decayFree(depthMm, depth.K, depth.worldFromCam.rigidInverse())
        val pruned = voxels.prune(userPosW, headingW, depth.tCaptureNs)
        return MapUpdate(
            depth.tCaptureNs, n, nFloor, nBelow, nInserted, nDecayed, pruned, voxels.size, floor.floorY, MapHealth.OK,
        )
    }

    /** 맵·바닥을 모두 잊는다(자세 불연속, §7.5). */
    fun reset() {
        voxels.clear()
        floor.reset()
    }

    /** 신뢰도가 있는 깊이(원시 깊이)면 `depth.minConfidence` 미만 픽셀을 무효(0)로 만든 복사본. 그 외에는 원본. */
    private fun effectiveDepth(depth: DepthFrame): ShortArray {
        val conf = depth.confidence
        val min = config.depth.minConfidence
        if (conf == null || min <= 0) return depth.depthMm
        val out = depth.depthMm.copyOf()
        for (i in out.indices) if ((conf[i].toInt() and 0xFF) < min) out[i] = 0
        return out
    }
}
