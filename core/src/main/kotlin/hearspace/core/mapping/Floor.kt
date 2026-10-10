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

    private var lostFrames = 0

    /** 바닥을 유지한 채 탐색 폭 없이 다시 찾는 중(`floor.holdWhenLost`). */
    private var searching = false

    /** 현재 바닥 높이. 아직 모르면 null. */
    var floorY: Float? = null
        private set

    /** 월드 점 배열(x, y, z 교차)과 카메라 위치 [cameraW]로 바닥을 갱신한다. */
    fun update(pointsW: FloatArray, cameraW: Vec3): FloorUpdate {
        val prev = floorY
        val n = pointsW.size / 3
        // 후보 높이 모으기
        val ys = FloatArray(n)
        var m = 0
        for (i in 0 until n) {
            val y = pointsW[3 * i + 1]
            if (y >= cameraW.y - cfg.minBelowCameraM) continue
            val dx = pointsW[3 * i] - cameraW.x
            val dz = pointsW[3 * i + 2] - cameraW.z
            if (dx * dx + dz * dz > maxDistM * maxDistM) continue
            if (prev != null && !searching && abs(y - prev) > cfg.searchBandM) continue
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

    /** 바닥 점인지: |y − floorY| < toleranceM + tolerancePerM × [horizontalDistM](카메라에서 수평거리). 바닥을 모르면 false. */
    fun isFloor(y: Float, horizontalDistM: Float): Boolean =
        floorY?.let { abs(y - it) < cfg.toleranceM + cfg.tolerancePerM * horizontalDistM } ?: false

    /** 바닥보다 확실히 낮은 점(내려가는 단차 후보)인지: y < floorY − (belowMarginM + tolerancePerM × 수평거리). */
    fun isBelowFloor(y: Float, horizontalDistM: Float): Boolean =
        floorY?.let { y < it - (cfg.belowMarginM + cfg.tolerancePerM * horizontalDistM) } ?: false

    /** 바닥 추정을 잊는다(자세 불연속 등으로 월드 좌표가 바뀌었을 때). */
    fun reset() {
        floorY = null
        lostFrames = 0
        searching = false
        outOfBandFrames = 0
    }
}
