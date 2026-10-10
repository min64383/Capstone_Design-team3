package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import kotlin.math.floor

/**
 * 수평(XZ) 거리 기반 DBSCAN (§7.4). 외부 라이브러리 없이 eps 크기 격자로 이웃을 찾는다.
 * 점은 보통 점유 복셀 중심이다. 돌려주는 군집은 입력 인덱스 목록이며, 잡음 점은 어느 군집에도 없다.
 * 결과는 입력 순서에만 의존한다(같은 입력이면 같은 결과).
 */
object Cluster {

    /** [pointsW]를 군집으로 나눈다. 이웃 수(자기 포함)가 [minSamples] 이상인 점이 핵심점.
     * [canExpand]가 있으면 false인 점은 경계점으로 군집에 붙을 수는 있지만 다른 점으로 군집을 확장하지 못한다.
     * 낮은 신뢰도의 얇은 bridge가 두 강한 물체를 한 덩어리로 잇는 것을 막는 실험 옵션이다.
     */
    fun dbscanXZ(pointsW: List<Vec3>, epsM: Float, minSamples: Int, canExpand: BooleanArray? = null): List<List<Int>> {
        require(canExpand == null || canExpand.size == pointsW.size)
        val eps2 = epsM * epsM
        val grid = HashMap<Long, MutableList<Int>>()
        fun cell(v: Float) = floor(v / epsM).toInt()
        fun key(cx: Int, cz: Int) = (cx.toLong() shl 32) or (cz.toLong() and 0xFFFFFFFFL)
        for ((i, p) in pointsW.withIndex()) grid.getOrPut(key(cell(p.x), cell(p.z))) { ArrayList() } += i

        fun neighbors(i: Int): List<Int> {
            val p = pointsW[i]
            val cx = cell(p.x)
            val cz = cell(p.z)
            val out = ArrayList<Int>()
            for (dx in -1..1) for (dz in -1..1) {
                for (j in grid[key(cx + dx, cz + dz)] ?: continue) {
                    val q = pointsW[j]
                    val ddx = p.x - q.x
                    val ddz = p.z - q.z
                    if (ddx * ddx + ddz * ddz <= eps2) out += j
                }
            }
            return out
        }

        val label = IntArray(pointsW.size) { UNVISITED }
        val clusters = ArrayList<List<Int>>()
        for (i in pointsW.indices) {
            if (label[i] != UNVISITED) continue
            val nb = neighbors(i)
            if (nb.size < minSamples || canExpand?.get(i) == false) {
                label[i] = NOISE
                continue
            }
            val id = clusters.size
            val members = ArrayList<Int>()
            val queue = ArrayDeque(nb)
            label[i] = id
            members += i
            while (queue.isNotEmpty()) {
                val j = queue.removeFirst()
                if (label[j] == NOISE) {
                    label[j] = id // 경계점
                    members += j
                }
                if (label[j] != UNVISITED) continue
                label[j] = id
                members += j
                val nb2 = neighbors(j)
                if (nb2.size >= minSamples && canExpand?.get(j) != false) queue.addAll(nb2)
            }
            clusters += members
        }
        return clusters
    }

    private const val UNVISITED = -2
    private const val NOISE = -1
}
