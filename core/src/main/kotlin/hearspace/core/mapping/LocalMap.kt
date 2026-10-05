package hearspace.core.mapping

import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.Config
import hearspace.core.types.DepthFrame
import hearspace.core.types.MapHealth

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
    /** 그림자 거르기(`depth.shadowRadiusPx`)로 넣지 않은 비바닥 점 수(M12). */
    val nShadowedPoints: Int = 0,
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
        // 비바닥 점(맵에 넣을 후보) 고르기. 그림자 거르기를 쓰면 픽셀 위치·깊이도 함께 기억한다.
        val shadowOn = config.depth.shadowRadiusPx > 0
        val candidates = if (shadowOn) ArrayList<ShadowCandidate>() else null
        forEachSample(depthMm, depth.K, depth.worldFromCam, config.depth.subsample) { u, v, zM, p ->
            val camDist = (p - camW).horizontal().norm()
            when {
                floor.isFloor(p.y, camDist) -> nFloor++
                floor.isBelowFloor(p.y, camDist) -> nBelow++
                p.y < floorY -> Unit // 바닥보다 낮은데 단차로도 확실하지 않은 점: 장애물일 수 없으므로 버린다
                (p - userPosW).horizontal().norm() > radius -> Unit
                candidates != null -> candidates += ShadowCandidate(u, v, zM, p)
                else -> {
                    voxels.insert(p, depth.tCaptureNs)
                    nInserted++
                }
            }
        }
        var nShadowed = 0
        if (candidates != null) {
            val keep = notInShadow(candidates, depth.K.width, depth.K.height)
            for (i in candidates.indices) {
                if (keep[i]) {
                    voxels.insert(candidates[i].pW, depth.tCaptureNs)
                    nInserted++
                } else {
                    nShadowed++
                }
            }
        }
        val nDecayed = voxels.decayFree(depthMm, depth.K, depth.worldFromCam.rigidInverse())
        val pruned = voxels.prune(userPosW, headingW, depth.tCaptureNs)
        return MapUpdate(
            depth.tCaptureNs, n, nFloor, nBelow, nInserted, nDecayed, pruned, voxels.size, floor.floorY, MapHealth.OK, nShadowed,
        )
    }

    /** 맵에 넣을 후보 점: 깊이 픽셀 (u, v), 카메라 깊이 Z(m), 월드 점. */
    private class ShadowCandidate(val u: Int, val v: Int, val zM: Float, val pW: Vec3)

    /**
     * 그림자 거르기(`depth.shadowRadiusPx`, `depth.shadowRatio`, M12): 후보 점마다 픽셀 반경 안에 깊이가 (1 − ratio)배보다
     * 가까운 다른 후보 점이 있으면 앞 물체의 그림자로 보고 뺀다. 바닥 점은 비교에 넣지 않아 바닥에 닿은 낮은 물체는 남는다.
     * 평활 깊이는 앞 물체 윤곽과 뒤 배경 사이를 메워 가짜 면을 만든다(M12 검토: 캐리어 윗모서리 뒤 약 1.5 m 면).
     */
    private fun notInShadow(c: List<ShadowCandidate>, width: Int, height: Int): BooleanArray {
        val r = config.depth.shadowRadiusPx
        val keepRatio = 1f - config.depth.shadowRatio
        val grid = FloatArray(width * height) // 픽셀별 후보 깊이(0 = 후보 없음)
        for (x in c) grid[x.v * width + x.u] = x.zM
        val keep = BooleanArray(c.size) { true }
        for ((i, x) in c.withIndex()) {
            val limit = x.zM * keepRatio
            var shadowed = false
            var dv = -r
            while (dv <= r && !shadowed) {
                val v = x.v + dv
                if (v in 0 until height) {
                    var du = -r
                    while (du <= r) {
                        val u = x.u + du
                        if (u in 0 until width && du * du + dv * dv <= r * r) {
                            val z = grid[v * width + u]
                            if (z > 0f && z < limit) { shadowed = true; break }
                        }
                        du++
                    }
                }
                dv++
            }
            keep[i] = !shadowed
        }
        return keep
    }

    /** 맵·바닥을 모두 잊는다(자세 불연속, §7.5). */
    fun reset() {
        voxels.clear()
        floor.reset()
    }

    /**
     * [Projection.backprojectToWorld]와 같은 순서·규약(픽셀 중심이 정수)으로 표본 픽셀마다 (u, v, 카메라 깊이, 월드 점)을 넘긴다.
     * 0(무효) 깊이는 건너뛴다.
     */
    private inline fun forEachSample(depthMm: ShortArray, k: hearspace.core.types.Intrinsics, worldFromCam: hearspace.core.geometry.Mat4, subsample: Int, f: (Int, Int, Float, Vec3) -> Unit) {
        var v = 0
        while (v < k.height) {
            var u = 0
            while (u < k.width) {
                val mm = depthMm[v * k.width + u].toInt() and 0xFFFF
                if (mm != 0) {
                    val z = mm / 1000f
                    f(u, v, z, worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), z, k)))
                }
                u += subsample
            }
            v += subsample
        }
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
