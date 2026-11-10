package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.types.FloorConfig
import kotlin.math.abs
import kotlin.math.floor

/** 바닥 추정 한 번의 결과. */
data class FloorUpdate(
    /** 이번 깊이로 새 추정을 했는지(후보 점이 `floor.minPoints` 이상). */
    val estimated: Boolean,
    /** 평활 후 바닥 높이(월드 y). 아직 모르면 null. */
    val floorY: Float?,
    /** 이번 추정에 쓴 후보 점 수. */
    val nCandidates: Int,
)

/**
 * 바닥 높이 추정 (§7.2). 월드 +Y가 위이므로 높이 히스토그램의 최빈값을 쓴다.
 * - 후보는 카메라보다 `floor.minBelowCameraM` 이상 낮고(M12.1: 물체 윗면·벽 면이 바닥으로 잡히지 않게) 카메라에서 수평거리
 *   [maxDistM](`map.radiusM`) 안의 점만(v0.2.7: 먼 쓰레기 깊이 제외).
 * - 첫 추정: 위 후보 전체. 이후: 직전 바닥 ± `floor.searchBandM` 안의 점(사용자 결정 2026-09-28).
 * - 직전 바닥 근처 후보가 모자란 깊이가 `floor.lostFrames`장 이어지면 바닥을 잊고 첫 추정부터 다시(v0.2.7).
 *   잘못 잡은 바닥에 갇히지 않기 위함(M7 실측: 재생 시작 약 3 s 동안 17~28 m 깊이 → 바닥 −33.7 m 고정).
 *   `floor.holdWhenLost`면 잊지 않고 마지막 값을 쓰면서 탐색 폭 없이 다시 찾는다(M18).
 * - 최빈 칸 주변 ± `toleranceM` 점의 평균으로 칸보다 세밀하게 잡고, `emaAlpha`로 지수 평활한다(과거 값만 사용).
 */
class Floor(private val cfg: FloorConfig, private val maxDistM: Float) {

    /** 바닥 히스토그램 후보의 카메라 수평 거리 상한: `floor.histogramMaxDistM`(0이면 [maxDistM] = `map.radiusM`, 기준선). */
    private val histMax = if (cfg.histogramMaxDistM > 0f) cfg.histogramMaxDistM else maxDistM

    private var lostFrames = 0

    /** 바닥을 유지한 채 탐색 폭 없이 다시 찾는 중(`floor.holdWhenLost`). */
    private var searching = false

    /** 현재 바닥 높이. 아직 모르면 null. */
    var floorY: Float? = null
        private set

    /**
     * 월드 점 배열(x, y, z 교차)과 카메라 위치 [cameraW]로 바닥을 갱신한다. [weights]가 있으면(M54 `floor.weighting` CONFIDENCE) 최빈 칸과 그 둘레
     * 평균을 점 무게로 센다. 최소 점 수 검사(`floor.minPoints`)는 무게와 상관없이 점 수로 한다(바닥을 잃는 장이 늘지 않게).
     */
    fun update(pointsW: FloatArray, cameraW: Vec3, weights: FloatArray? = null): FloorUpdate {
        val prev = floorY
        val minD = cfg.histogramMinDistM
        val n = pointsW.size / 3
        // 후보 높이 모으기
        val ys = FloatArray(n)
        val ws = if (weights == null) null else FloatArray(n)
        var m = 0
        for (i in 0 until n) {
            val y = pointsW[3 * i + 1]
            if (y >= cameraW.y - cfg.minBelowCameraM) continue
            val dx = pointsW[3 * i] - cameraW.x
            val dz = pointsW[3 * i + 2] - cameraW.z
            val d2 = dx * dx + dz * dz
            if (d2 > histMax * histMax || d2 < minD * minD) continue
            if (prev != null && !searching && abs(y - prev) > cfg.searchBandM) continue
            if (ws != null) ws[m] = weights!![i]
            ys[m++] = y
        }
        if (m < cfg.minPoints) {
            if (prev != null && ++lostFrames >= cfg.lostFrames) {
                if (cfg.holdWhenLost) searching = true else reset()
            }
            return FloorUpdate(false, floorY, m)
        }
        lostFrames = 0
        val wasSearching = searching
        searching = false

        if (ws != null) return weightedEstimate(ys, ws, m, prev, wasSearching)
        // 최빈 칸
        val counts = HashMap<Int, Int>()
        var bestBin = 0
        var bestCount = -1
        for (i in 0 until m) {
            val b = floor(ys[i] / cfg.binM).toInt()
            val c = (counts[b] ?: 0) + 1
            counts[b] = c
            // 같은 개수면 더 낮은 칸(바닥이 가장 아래 넓은 면이라는 가정)
            if (c > bestCount || (c == bestCount && b < bestBin)) {
                bestCount = c
                bestBin = b
            }
        }
        val center = (bestBin + 0.5f) * cfg.binM
        var sum = 0.0
        var k = 0
        for (i in 0 until m) {
            if (abs(ys[i] - center) < cfg.toleranceM) {
                sum += ys[i]
                k++
            }
        }
        val estimate = (sum / k).toFloat()
        // 재탐색으로 찾은 값은 다른 높이일 수 있으므로 평활하지 않고 받는다(잊고 다시 찾는 기준선과 같은 결과)
        floorY = if (prev == null || wasSearching) estimate else prev + cfg.emaAlpha * (estimate - prev)
        return FloorUpdate(true, floorY, m)
    }

    /** [update]의 무게 판(M54): 칸마다 무게 합의 최빈 칸(같으면 낮은 칸), 그 칸 중심 ± `toleranceM` 점의 가중 평균. 평활은 같다. */
    private fun weightedEstimate(ys: FloatArray, ws: FloatArray, m: Int, prev: Float?, wasSearching: Boolean): FloorUpdate {
        val sums = HashMap<Int, Double>()
        var bestBin = 0
        var best = -1.0
        for (i in 0 until m) {
            val b = floor(ys[i] / cfg.binM).toInt()
            val c = (sums[b] ?: 0.0) + ws[i]
            sums[b] = c
            if (c > best || (c == best && b < bestBin)) {
                best = c
                bestBin = b
            }
        }
        val center = (bestBin + 0.5f) * cfg.binM
        var sum = 0.0
        var wsum = 0.0
        for (i in 0 until m) {
            if (abs(ys[i] - center) < cfg.toleranceM) {
                sum += ws[i] * ys[i]
                wsum += ws[i]
            }
        }
        val estimate = (sum / wsum).toFloat()
        floorY = if (prev == null || wasSearching) estimate else prev + cfg.emaAlpha * (estimate - prev)
        return FloorUpdate(true, floorY, m)
    }

    private var outOfBandFrames = 0

    /**
     * 평면 추출의 바닥 높이 [planeHeightM]로 갱신한다(`floor.source` PLANE, M13.1c). 바닥이 안 보이는 프레임(null)은 갱신하지 않고
     * 이전 값을 유지한다(벽을 따라 올라가지 않는다, M12.0). 바닥을 한 번도 못 잡은 동안의 시작은 [LocalMap]이 히스토그램으로 한다. 이전 바닥에서 `searchBandM`을 넘게 벗어난 평면이 `lostFrames`장
     * 이어지면 높이가 실제로 달라진 것으로 보고 새 값을 받는다(계단·경사 대비).
     */
    fun updateFromPlane(planeHeightM: Float?, nCandidates: Int): FloorUpdate {
        val prev = floorY
        if (planeHeightM == null) return FloorUpdate(false, prev, nCandidates)
        if (prev != null && abs(planeHeightM - prev) > cfg.searchBandM) {
            if (++outOfBandFrames < cfg.lostFrames) return FloorUpdate(false, prev, nCandidates)
            floorY = planeHeightM
        } else {
            floorY = if (prev == null) planeHeightM else prev + cfg.emaAlpha * (planeHeightM - prev)
        }
        outOfBandFrames = 0
        return FloorUpdate(true, floorY, nCandidates)
    }

    /** M55 바닥 평면(`floor.model` PLANE_RAW): y = a·x + b·z + c(월드). null이면 수평 바닥([floorY]). */
    var plane: FloatArray? = null
        private set
    private val rng = java.util.Random(1)

    /** 자리 (x, z)의 바닥 높이: 평면이 있으면 평면, 아니면 [floorY]. */
    fun heightAt(x: Float, z: Float): Float? = plane?.let { it[0] * x + it[1] * z + it[2] } ?: floorY

    /**
     * 원시 깊이 바닥 후보 점 [pts](x, y, z 교차)로 바닥 평면을 맞춘다(M55 설계 보완: 150159에서 원시·평활 바닥이 함께 약 10° 기울어 수평 바닥 하나로는
     * 가까운 바닥 점이 띠 위로 올라옴). 기울기 ≤ `planeMaxTiltDeg`인 RANSAC(`planeIterations`번, 안쪽 = 평면에서 수직으로 `toleranceM` 안, 안쪽이 같으면
     * 카메라 자리에서 낮은 평면), 안쪽 점 최소제곱, 직전 평면과 `emaAlpha`로 평활. 후보가 `minPoints` 미만이면 그대로 둔다. 돌려주는 값은 맞췄는지.
     */
    fun updatePlane(pts: FloatArray, cameraW: Vec3): Boolean {
        val n = pts.size / 3
        if (n < cfg.minPoints) return false
        val maxSlope = kotlin.math.tan(Math.toRadians(cfg.planeMaxTiltDeg.toDouble())).toFloat()
        var best: FloatArray? = null
        var bestIn = -1
        var bestH = Float.POSITIVE_INFINITY
        repeat(cfg.planeIterations) {
            val i = rng.nextInt(n); val j = rng.nextInt(n); val k = rng.nextInt(n)
            if (i == j || j == k || i == k) return@repeat
            val c = solvePlane(pts, intArrayOf(i, j, k)) ?: return@repeat
            if (kotlin.math.hypot(c[0], c[1]) > maxSlope) return@repeat
            var inl = 0
            for (q in 0 until n) if (abs(pts[3 * q + 1] - (c[0] * pts[3 * q] + c[1] * pts[3 * q + 2] + c[2])) < cfg.toleranceM) inl++
            val h = c[0] * cameraW.x + c[1] * cameraW.z + c[2]
            if (inl > bestIn || (inl == bestIn && h < bestH)) { best = c; bestIn = inl; bestH = h }
        }
        val r = best ?: return false
        val idx = (0 until n).filter { q -> abs(pts[3 * q + 1] - (r[0] * pts[3 * q] + r[1] * pts[3 * q + 2] + r[2])) < cfg.toleranceM }.toIntArray()
        val fit = solvePlane(pts, idx)?.takeIf { kotlin.math.hypot(it[0], it[1]) <= maxSlope } ?: r
        val prev = plane
        plane = if (prev == null) fit else FloatArray(3) { q -> prev[q] + cfg.emaAlpha * (fit[q] - prev[q]) }
        return true
    }

    /** [heightAt]으로 [floorY]를 카메라 자리 평면 높이로 맞춘다(하류가 쓰는 바닥 높이 하나). 평면이 없으면 그대로. */
    fun alignToCamera(cameraW: Vec3) {
        plane?.let { floorY = it[0] * cameraW.x + it[1] * cameraW.z + it[2] }
    }

    /** 점들 [idx]의 y = a·x + b·z + c 최소제곱(3점이면 정확히 지나는 면). 퇴화하면 null. */
    private fun solvePlane(pts: FloatArray, idx: IntArray): FloatArray? {
        var mx = 0.0; var mz = 0.0; var my = 0.0
        for (q in idx) { mx += pts[3 * q]; my += pts[3 * q + 1]; mz += pts[3 * q + 2] }
        mx /= idx.size; my /= idx.size; mz /= idx.size
        var sxx = 0.0; var sxz = 0.0; var szz = 0.0; var sxy = 0.0; var szy = 0.0
        for (q in idx) {
            val x = pts[3 * q] - mx; val z = pts[3 * q + 2] - mz; val y = pts[3 * q + 1] - my
            sxx += x * x; sxz += x * z; szz += z * z; sxy += x * y; szy += z * y
        }
        val det = sxx * szz - sxz * sxz
        if (abs(det) < 1e-9) return null
        val a = (sxy * szz - szy * sxz) / det
        val b = (szy * sxx - sxy * sxz) / det
        return floatArrayOf(a.toFloat(), b.toFloat(), (my - a * mx - b * mz).toFloat())
    }

    /** 점 [p]가 바닥인지(자리의 바닥 높이 [heightAt] 기준, 평면이 없으면 [isFloor]와 같음). */
    fun isFloor(p: Vec3, horizontalDistM: Float): Boolean =
        heightAt(p.x, p.z)?.let { abs(p.y - it) < cfg.toleranceM + cfg.tolerancePerM * horizontalDistM } ?: false

    /** 점 [p]가 바닥보다 확실히 낮은지([heightAt] 기준). */
    fun isBelowFloor(p: Vec3, horizontalDistM: Float): Boolean =
        heightAt(p.x, p.z)?.let { p.y < it - (cfg.belowMarginM + cfg.tolerancePerM * horizontalDistM) } ?: false

    /** 점 [p]가 그 자리 바닥보다 낮은지([heightAt] 기준). */
    fun isUnder(p: Vec3): Boolean = heightAt(p.x, p.z)?.let { p.y < it } ?: false

    /** 바닥 점인지: |y − floorY| < toleranceM + tolerancePerM × [horizontalDistM](카메라에서 수평거리). 바닥을 모르면 false. */
    fun isFloor(y: Float, horizontalDistM: Float): Boolean =
        floorY?.let { abs(y - it) < cfg.toleranceM + cfg.tolerancePerM * horizontalDistM } ?: false

    /** 바닥보다 확실히 낮은 점(내려가는 단차 후보)인지: y < floorY − (belowMarginM + tolerancePerM × 수평거리). */
    fun isBelowFloor(y: Float, horizontalDistM: Float): Boolean =
        floorY?.let { y < it - (cfg.belowMarginM + cfg.tolerancePerM * horizontalDistM) } ?: false

    /** 바닥 추정을 잊는다(자세 불연속 등으로 월드 좌표가 바뀌었을 때). */
    fun reset() {
        floorY = null
        plane = null
        lostFrames = 0
        searching = false
        outOfBandFrames = 0
    }
}
