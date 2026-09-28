package walkassist.core.mapping

import walkassist.core.types.FloorConfig
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
 * - 첫 추정: 카메라보다 낮은 점 전체. 이후: 직전 바닥 ± `floor.searchBandM` 안의 점(사용자 결정 2026-09-28).
 * - 최빈 칸 주변 ± `toleranceM` 점의 평균으로 칸보다 세밀하게 잡고, `emaAlpha`로 지수 평활한다(과거 값만 사용).
 */
class Floor(private val cfg: FloorConfig) {

    /** 현재 바닥 높이. 아직 모르면 null. */
    var floorY: Float? = null
        private set

    /** 월드 점 배열(x, y, z 교차)과 카메라 높이 [cameraY]로 바닥을 갱신한다. */
    fun update(pointsW: FloatArray, cameraY: Float): FloorUpdate {
        val prev = floorY
        val n = pointsW.size / 3
        // 후보 높이 모으기
        val ys = FloatArray(n)
        var m = 0
        for (i in 0 until n) {
            val y = pointsW[3 * i + 1]
            if (y >= cameraY) continue
            if (prev != null && abs(y - prev) > cfg.searchBandM) continue
            ys[m++] = y
        }
        if (m < cfg.minPoints) return FloorUpdate(false, prev, m)

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
        floorY = if (prev == null) estimate else prev + cfg.emaAlpha * (estimate - prev)
        return FloorUpdate(true, floorY, m)
    }

    /** 바닥 점인지: |y − floorY| < toleranceM + tolerancePerM × [horizontalDistM](카메라에서 수평거리). 바닥을 모르면 false. */
    fun isFloor(y: Float, horizontalDistM: Float): Boolean =
        floorY?.let { abs(y - it) < cfg.toleranceM + cfg.tolerancePerM * horizontalDistM } ?: false

    /** 바닥보다 확실히 낮은 점(내려가는 단차 후보)인지: y < floorY − (belowMarginM + tolerancePerM × 수평거리). */
    fun isBelowFloor(y: Float, horizontalDistM: Float): Boolean =
        floorY?.let { y < it - (cfg.belowMarginM + cfg.tolerancePerM * horizontalDistM) } ?: false

    /** 바닥 추정을 잊는다(자세 불연속 등으로 월드 좌표가 바뀌었을 때). */
    fun reset() {
        floorY = null
    }
}
