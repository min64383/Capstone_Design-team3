package walkassist.core.mapping

import walkassist.core.geometry.Mat4
import walkassist.core.geometry.Vec3
import walkassist.core.types.Intrinsics
import walkassist.core.types.MapConfig
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 복셀 하나의 읽기 전용 모습. */
data class VoxelView(
    val ix: Int,
    val iy: Int,
    val iz: Int,
    /** 복셀 중심(월드). */
    val centerW: Vec3,
    val hits: Int,
    val score: Float,
    val lastSeenNs: Long,
)

/** 삭제 사유별 개수(로그·테스트용). */
data class PruneCounts(val passed: Int, val unseen: Int, val outOfRadius: Int)

/**
 * 월드 좌표 희소 해시 복셀 점유 맵 (§7.3). 복셀 크기 `map.voxelSizeM`.
 * - 관측: 깊이 한 장에서 비바닥 점이 들어간 복셀은 hits += 1, score += hitGain(최대 1). 같은 장 안에서는 한 번만.
 * - 빈 공간 감쇠: **시야 안에서** 복셀 중심을 깊이 이미지에 투영했을 때 유효 깊이가 복셀보다 `freeMarginM` 이상 멀면
 *   score −= decayPerObservation. 시야 밖·깊이 무효·가려진(깊이가 더 가까운) 복셀은 감쇠하지 않는다(§2.2-6).
 * - 삭제: score ≤ 0, 또는 [prune]의 세 조건(지나감·오래 안 보임·반경 밖)뿐이다.
 */
class VoxelMap(private val cfg: MapConfig) {

    private class Voxel(var hits: Int, var score: Float, var lastSeenNs: Long, var lastHitFrame: Long)

    private val voxels = HashMap<Long, Voxel>()
    private var frame = 0L

    /** 복셀 수. */
    val size: Int get() = voxels.size

    /** 새 깊이 한 장을 시작한다. [insert]의 "한 장에 한 번" 판정에 쓴다. */
    fun beginFrame() {
        frame++
    }

    /** 비바닥 점 하나를 관측으로 넣는다. */
    fun insert(pW: Vec3, tNs: Long) {
        val ix = index(pW.x)
        val iy = index(pW.y)
        val iz = index(pW.z)
        val key = key(ix, iy, iz)
        val v = voxels[key]
        if (v == null) {
            voxels[key] = Voxel(1, min(1f, cfg.hitGain), tNs, frame)
        } else if (v.lastHitFrame != frame) {
            v.hits++
            v.score = min(1f, v.score + cfg.hitGain)
            v.lastSeenNs = tNs
            v.lastHitFrame = frame
        }
    }

    /**
     * 현재 깊이 이미지로 빈 공간을 관측한 복셀을 감쇠한다. 이번 장에서 관측된 복셀은 건너뛴다.
     * [depthMm]은 이미 신뢰도 필터를 거친 깊이(0 = 무효). 돌려주는 값은 감쇠한 복셀 수.
     */
    fun decayFree(depthMm: ShortArray, k: Intrinsics, camFromWorld: Mat4): Int {
        var decayed = 0
        val it = voxels.entries.iterator()
        while (it.hasNext()) {
            val (key, v) = it.next()
            if (v.lastHitFrame == frame) continue
            val pCv = camFromWorld.transformPoint(center(key))
            if (pCv.z <= 0f) continue // 카메라 뒤: 시야 밖
            val u = (k.fx * pCv.x / pCv.z + k.cx).roundToInt()
            val w = (k.fy * pCv.y / pCv.z + k.cy).roundToInt()
            if (u < 0 || u >= k.width || w < 0 || w >= k.height) continue // 시야 밖
            val mm = depthMm[w * k.width + u].toInt() and 0xFFFF
            if (mm == 0) continue // 관측 없음
            if (mm / 1000f < pCv.z + cfg.freeMarginM) continue // 복셀 자리 또는 그 앞이 관측됨(가려짐 포함)
            v.score -= cfg.decayPerObservation
            decayed++
            if (v.score <= 0f) it.remove()
        }
        return decayed
    }

    /**
     * 시야와 무관한 삭제 조건(§7.3): 사용자 뒤로 `passedMarginM` 이상 지나감(진행 방향 기준, 수평),
     * 마지막 관측 후 `maxUnseenS` 경과, 사용자에서 수평 `radiusM` 밖.
     */
    fun prune(userPosW: Vec3, headingW: Vec3, nowNs: Long): PruneCounts {
        val h = headingW.horizontal()
        val hn = h.norm()
        val dir = if (hn > 0f) h * (1f / hn) else null
        val maxUnseenNs = (cfg.maxUnseenS * 1e9).toLong()
        var passed = 0
        var unseen = 0
        var far = 0
        val it = voxels.entries.iterator()
        while (it.hasNext()) {
            val (key, v) = it.next()
            val d = (center(key) - userPosW).horizontal()
            when {
                dir != null && (d dot dir) < -cfg.passedMarginM -> passed++
                nowNs - v.lastSeenNs > maxUnseenNs -> unseen++
                d.norm() > cfg.radiusM -> far++
                else -> continue
            }
            it.remove()
        }
        return PruneCounts(passed, unseen, far)
    }

    /** 군집화 대상 복셀: hits ≥ `map.minHits`, score ≥ `map.minScore`. */
    fun occupied(): List<VoxelView> = views().filter { it.hits >= cfg.minHits && it.score >= cfg.minScore }

    /** 모든 복셀. */
    fun views(): List<VoxelView> = voxels.map { (key, v) ->
        VoxelView(ix(key), iy(key), iz(key), center(key), v.hits, v.score, v.lastSeenNs)
    }

    /** 모든 score에 [factor]를 곱한다(추적 복귀 시 `state.recoverScoreScale`, §7.5). */
    fun scaleScores(factor: Float) {
        for (v in voxels.values) v.score = max(0f, min(1f, v.score * factor))
        voxels.values.removeAll { it.score <= 0f }
    }

    /** 맵을 비운다(자세 불연속, §7.5). */
    fun clear() {
        voxels.clear()
    }

    /** 월드 좌표 → 복셀 정수 좌표(음수도 내림). */
    fun index(c: Float): Int = floor(c / cfg.voxelSizeM).toInt()

    private fun center(key: Long) = Vec3(
        (ix(key) + 0.5f) * cfg.voxelSizeM,
        (iy(key) + 0.5f) * cfg.voxelSizeM,
        (iz(key) + 0.5f) * cfg.voxelSizeM,
    )

    private companion object {
        const val BITS = 21
        const val OFFSET = 1 shl (BITS - 1) // ±2^20 칸 (0.05 m면 ±52 km)
        const val MASK = (1L shl BITS) - 1

        fun key(ix: Int, iy: Int, iz: Int): Long {
            require(ix in -OFFSET until OFFSET && iy in -OFFSET until OFFSET && iz in -OFFSET until OFFSET) { "voxel index out of range" }
            return ((ix + OFFSET).toLong() shl (2 * BITS)) or ((iy + OFFSET).toLong() shl BITS) or (iz + OFFSET).toLong()
        }

        fun ix(key: Long) = ((key shr (2 * BITS)) and MASK).toInt() - OFFSET
        fun iy(key: Long) = ((key shr BITS) and MASK).toInt() - OFFSET
        fun iz(key: Long) = (key and MASK).toInt() - OFFSET
    }
}
