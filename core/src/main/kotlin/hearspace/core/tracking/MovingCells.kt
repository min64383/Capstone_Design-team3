package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.VoxelMap
import hearspace.core.types.MapConfig
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * 움직이는 track 하나의 물체 좌표 HITS 칸(M48 설계 3.1절). 정지 지도(`map.mode` HITS)와 같은 산술을 쓰되, 칸 좌표를 track 원점
 * (칼만 위치) 기준으로 둔다. 정지 지도가 "같은 칸"에 증거를 모으듯 이 칸은 "그 움직임을 따라 옮긴 칸"에 증거를 모은다(정지 = 속도 0).
 * - 맞음: 장마다 한 번, 가중 표 += 가중치, 점수 += `hitGain` × 가중치(최대 1)
 * - 빈 곳: 이번 장에 맞지 않은 칸이 빈 곳으로 보이면 점수 −= `decayPerObservation`, 0 이하면 지움. 시야 밖·깊이 무효·칸 자리 관측은 그대로
 * - 점유: 가중 표 ≥ `minHits`이고 점수 ≥ `minScore`
 */
class MovingCells(private val cfg: MapConfig) {

    private class Cell(var hits: Float, var score: Float, var lastHitFrame: Long)

    private val cells = HashMap<Long, Cell>()

    val size: Int get() = cells.size

    private fun idx(c: Float) = floor(c / cfg.voxelSizeM).toInt()
    private fun key(ix: Int, iy: Int, iz: Int): Long = (ix.toLong() + OFF shl 42) or (iy.toLong() + OFF shl 21) or (iz.toLong() + OFF)
    private fun center(key: Long, originW: Vec3) = Vec3(
        ((key shr 42 and MASK) - OFF + 0.5f) * cfg.voxelSizeM + originW.x,
        ((key shr 21 and MASK) - OFF + 0.5f) * cfg.voxelSizeM,
        ((key and MASK) - OFF + 0.5f) * cfg.voxelSizeM + originW.z,
    )

    /** 높이(y)는 월드 그대로, 수평만 원점 기준. */
    private fun keyOf(pW: Vec3, originW: Vec3) = key(idx(pW.x - originW.x), idx(pW.y), idx(pW.z - originW.z))

    /** 이번 장([frame])의 점 [pW](가중치 [weight])를 넣는다. */
    fun hit(pW: Vec3, originW: Vec3, weight: Float, frame: Long) {
        val k = keyOf(pW, originW)
        val c = cells[k]
        if (c == null) {
            cells[k] = Cell(weight, min(1f, cfg.hitGain * weight), frame)
            return
        }
        if (c.lastHitFrame == frame) return
        c.hits += weight
        c.score = min(1f, c.score + cfg.hitGain * weight)
        c.lastHitFrame = frame
    }

    /** 정지 지도에서 옮겨 온 칸(판정 순간, 설계 3.1절 5). 표·점수를 그대로 받는다. */
    fun add(e: VoxelMap.CellEvidence, originW: Vec3, frame: Long) {
        val k = keyOf(e.centerW, originW)
        val c = cells[k]
        if (c == null) cells[k] = Cell(e.weightedHits, e.score, frame - 1)
        else { c.hits = max(c.hits, e.weightedHits); c.score = max(c.score, e.score) }
    }

    /** 이번 장에 맞지 않은 칸 중 빈 곳으로 보인 칸([seenFree])의 점수를 깎고 0 이하면 지운다. 지운 칸 수를 돌려준다. */
    fun decay(originW: Vec3, frame: Long, seenFree: (Vec3) -> Boolean): Int {
        var removed = 0
        val it = cells.entries.iterator()
        while (it.hasNext()) {
            val (k, c) = it.next()
            if (c.lastHitFrame == frame || !seenFree(center(k, originW))) continue
            c.score -= cfg.decayPerObservation
            if (c.score <= 0f) { it.remove(); removed++ }
        }
        return removed
    }

    private fun occupied(c: Cell) = c.hits >= cfg.minHits && c.score >= cfg.minScore

    /** 점유 칸의 중심(월드, 원점 [originW] 기준). */
    fun occupiedCenters(originW: Vec3): List<Vec3> = cells.filter { (_, c) -> occupied(c) }.map { (k, _) -> center(k, originW) }

    /** 점유 칸(넘겨주기용: 중심·표·점수). */
    fun occupiedEvidence(originW: Vec3): List<VoxelMap.CellEvidence> =
        cells.filter { (_, c) -> occupied(c) }.map { (k, c) -> VoxelMap.CellEvidence(center(k, originW), c.hits, c.score) }

    private companion object {
        const val OFF = 1L shl 20
        const val MASK = (1L shl 21) - 1
    }
}
