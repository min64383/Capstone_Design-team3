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
 * - CORRIDOR_BAND(M21): 거리와 방향을 다른 칸에서 낸다. 진행 방향 거리는 CORRIDOR_NEAREST와 같고(STOP 판정 그대로),
 *   좌우·높이는 최소에서 [bandM] 안 통로 안 점 중 보행선에 가장 가까운 점의 것. 앞줄 폭만 한 칸에서 [bandM]으로 넓힌 셈이다.
 *   깊이 꼬리 칸이 앞줄을 차지해 방위가 그 칸의 좌우로 끌리던 것을 막는다(M20: 6° 초과 블록의 대표점이 앞면보다
 *   8~9 cm 앞의 꼬리 칸). 좌우는 실제 점에서 가져온다: 띠 좌우 범위에 0을 가두면(M21 1차) 옆벽 둘만 띠에 든 통로에서
 *   아무것도 없는 가운데를 가리킨다. 분위수 범위는 비켜 선 상자의 진짜 모서리를 잘라 방향이 나빠져 쓰지 않는다(M21 결과).
 *   칸 중심이 아니라 만든 점이다. 통로 안 점이 없으면 NEAREST와 같다.
 * - [stableTies] 실험을 켜면 같은 거리의 후보는 입력 순서 대신 진행 방향 거리 → 높이 → 부호 있는 좌우 순으로 고른다.
 *   복셀 HashMap 순서가 바뀌어도 같은 면의 다른 높이·좌우를 번갈아 가리키지 않는다.
 */
object RepPoint {

    /** [pointsW]의 대표점들. [pointsW]는 비어 있으면 안 된다. */
    fun candidates(pointsW: List<Vec3>, corridor: Corridor, frontTolM: Float, bandM: Float, stableTies: Boolean = false): Map<RepStrategy, Vec3> {
        require(pointsW.isNotEmpty()) { "empty cluster" }
        var sx = 0f
        var sy = 0f
        var sz = 0f
        for (p in pointsW) {
            sx += p.x; sy += p.y; sz += p.z
        }
        val n = pointsW.size.toFloat()
        val centroid = Vec3(sx / n, sy / n, sz / n)
        // 동률 규칙도 사용자 통로 좌표를 쓴다. 월드 x/z로 가르면 같은 면을 회전했을 때 선택이 바뀐다.
        // 새 거리 허용치는 두지 않는다: 기존 1차 기준이 정확히 같은 경우에만 적용한다.
        val surfaceOrder = compareBy<Vec3> { corridor.alongM(it) }
            .thenBy { it.y }
            .thenBy { corridor.lateralM(it) }
        val nearestOrder = compareBy<Vec3> { (it - corridor.originW).horizontal().norm() }
        val nearest = pointsW.minWith(if (stableTies) nearestOrder.then(surfaceOrder) else nearestOrder)
        val lateralOrder = compareBy<Vec3> { kotlin.math.abs(corridor.lateralM(it)) }.then(surfaceOrder)
        val chosenLateralOrder = if (stableTies) lateralOrder else compareBy<Vec3> { kotlin.math.abs(corridor.lateralM(it)) }
        val inside = pointsW.filter { corridor.contains(it) }
        val minAlong = inside.minOfOrNull { corridor.alongM(it) }
        val corridorNearest = minAlong?.let { m ->
            inside.filter { corridor.alongM(it) <= m + frontTolM }.minWith(chosenLateralOrder)
        } ?: nearest
        val corridorBand = minAlong?.let { m ->
            val c = inside.filter { corridor.alongM(it) <= m + bandM }.minWith(chosenLateralOrder)
            corridor.pointW(corridor.alongM(corridorNearest), corridor.lateralM(c), c.y)
        } ?: nearest
        return mapOf(
            RepStrategy.CENTROID to centroid,
            RepStrategy.NEAREST to nearest,
            RepStrategy.CORRIDOR_NEAREST to corridorNearest,
            RepStrategy.CORRIDOR_BAND to corridorBand,
        )
    }
}
