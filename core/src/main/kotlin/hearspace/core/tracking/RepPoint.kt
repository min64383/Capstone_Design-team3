package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import hearspace.core.guidance.Corridor
import hearspace.core.types.RepStrategy

/**
 * 대표점 4방식 (§7.4, M21). 비교 실험을 위해 항상 전부 계산한다.
 * - CENTROID: 점 중심
 * - NEAREST: 사용자(통로 원점)에 수평으로 가장 가까운 점
 * - CORRIDOR_NEAREST: 통로 안 점 중 진행 방향 거리 최소. 최소에서 [frontTolM](복셀 한 칸) 안의 앞줄 점이 여럿이면 보행선에
 *   가장 가까운 점(|좌우| 최소) — 부딪힐 곳을 가리키도록(v0.2.8: 동점을 점 순서로 고르면 정면의 평평한 면도 한쪽 끝을 가리켰다, M8).
 *   통로 안 점이 없으면 NEAREST와 같다(통로 밖 물체는 안내 대상이 아님).
 * - CORRIDOR_BAND(M21): 거리와 방향을 다른 칸에서 낸다. 진행 방향 거리는 통로 안 최소(STOP 안전, 앞줄보다 멀지 않음),
 *   좌우는 최소에서 [bandM] 안 통로 안 점들의 좌우 [bandQ]·(1 − [bandQ]) 분위수 범위에 보행선(0)을 가둔 값.
 *   깊이 꼬리 칸 하나가 앞줄을 차지해 방위가 그 칸의 좌우로 끌리던 것을 막는다(M20: 6° 초과 블록의 대표점이 앞면보다
 *   8~9 cm 앞의 꼬리 칸). 정답 최근접점(상자 좌우 범위에 0을 가둠)과 같은 꼴. 높이는 띠 점 중 그 좌우에 가장 가까운 점.
 *   칸 중심이 아니라 만든 점이다. 통로 안 점이 없으면 NEAREST와 같다.
 */
object RepPoint {

    /** [pointsW]의 대표점들. [pointsW]는 비어 있으면 안 된다. */
    fun candidates(pointsW: List<Vec3>, corridor: Corridor, frontTolM: Float, bandM: Float, bandQ: Float): Map<RepStrategy, Vec3> {
        require(pointsW.isNotEmpty()) { "empty cluster" }
        var sx = 0f
        var sy = 0f
        var sz = 0f
        for (p in pointsW) {
            sx += p.x; sy += p.y; sz += p.z
        }
        val n = pointsW.size.toFloat()
        val centroid = Vec3(sx / n, sy / n, sz / n)
        val nearest = pointsW.minBy { (it - corridor.originW).horizontal().norm() }
        val inside = pointsW.filter { corridor.contains(it) }
        val minAlong = inside.minOfOrNull { corridor.alongM(it) }
        val corridorNearest = minAlong?.let { m ->
            inside.filter { corridor.alongM(it) <= m + frontTolM }.minBy { kotlin.math.abs(corridor.lateralM(it)) }
        } ?: nearest
        val corridorBand = minAlong?.let { m ->
            val band = inside.filter { corridor.alongM(it) <= m + bandM }
            val lats = band.map { corridor.lateralM(it) }.sorted()
            val lat = 0f.coerceIn(quantile(lats, bandQ), quantile(lats, 1f - bandQ))
            val y = band.minBy { kotlin.math.abs(corridor.lateralM(it) - lat) }.y
            corridor.pointW(m, lat, y)
        } ?: nearest
        return mapOf(
            RepStrategy.CENTROID to centroid,
            RepStrategy.NEAREST to nearest,
            RepStrategy.CORRIDOR_NEAREST to corridorNearest,
            RepStrategy.CORRIDOR_BAND to corridorBand,
        )
    }

    /** 정렬된 [sorted]의 [q] 분위수(선형 보간, numpy 기본과 같음). */
    private fun quantile(sorted: List<Float>, q: Float): Float {
        val pos = q * (sorted.size - 1)
        val i = pos.toInt().coerceAtMost(sorted.size - 1)
        val j = (i + 1).coerceAtMost(sorted.size - 1)
        return sorted[i] + (sorted[j] - sorted[i]) * (pos - i)
    }
}
