package walkassist.core.tracking

import walkassist.core.geometry.Vec3
import walkassist.core.guidance.Corridor
import walkassist.core.types.RepStrategy

/**
 * 대표점 3방식 (§7.4). 비교 실험을 위해 항상 셋 다 계산한다.
 * - CENTROID: 점 중심
 * - NEAREST: 사용자(통로 원점)에 수평으로 가장 가까운 점
 * - CORRIDOR_NEAREST: 통로 안 점 중 진행 방향 거리 최소. 최소에서 [frontTolM](복셀 한 칸) 안의 앞줄 점이 여럿이면 보행선에
 *   가장 가까운 점(|좌우| 최소) — 부딪힐 곳을 가리키도록(v0.2.8: 동점을 점 순서로 고르면 정면의 평평한 면도 한쪽 끝을 가리켰다, M8).
 *   통로 안 점이 없으면 NEAREST와 같다(통로 밖 물체는 안내 대상이 아님).
 */
object RepPoint {

    /** [pointsW]의 세 대표점. [pointsW]는 비어 있으면 안 된다. */
    fun candidates(pointsW: List<Vec3>, corridor: Corridor, frontTolM: Float): Map<RepStrategy, Vec3> {
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
        return mapOf(
            RepStrategy.CENTROID to centroid,
            RepStrategy.NEAREST to nearest,
            RepStrategy.CORRIDOR_NEAREST to corridorNearest,
        )
    }
}
