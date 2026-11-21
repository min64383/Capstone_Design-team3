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
     * 라벨 가중(M56 3.3절): [labels]가 있고 [sameLabelEpsM] > [epsM]이면, 두 점의 라벨이 같고 0보다 클 때(라벨 없음 0·과반 없음 -1 제외)
     * 수평 거리 [sameLabelEpsM]까지 이웃으로 본다. 라벨이 다른 쌍은 [epsM] 그대로.
     */
    fun dbscanXZ(
        pointsW: List<Vec3>, epsM: Float, minSamples: Int, canExpand: BooleanArray? = null, labels: IntArray? = null, sameLabelEpsM: Float = 0f,
    ): List<List<Int>> {
        require(canExpand == null || canExpand.size == pointsW.size)
        require(labels == null || labels.size == pointsW.size)
        val eps2 = epsM * epsM
        val same = labels != null && sameLabelEpsM > epsM
        val same2 = sameLabelEpsM * sameLabelEpsM
        val cellM = if (same) sameLabelEpsM else epsM
        val grid = HashMap<Long, MutableList<Int>>()
        fun cell(v: Float) = floor(v / cellM).toInt()
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
                    val d2 = ddx * ddx + ddz * ddz
                    if (d2 <= eps2 || (same && d2 <= same2 && labels!![i] > 0 && labels[i] == labels[j])) out += j
                }
            }
            return out
        }

        // M61(결과 같음, 시간만 줄임): 이웃 목록은 점의 (x, z)와 라벨 조건에만 달려 있다. 복셀 중심은 같은 기둥에 높이만 다른 점이
        // 많아(조합의 0.3 m 격자 칸에 수백~수천 점) 같은 열쇠는 한 번만 계산해 나눠 쓴다.
        val cache = HashMap<NbKey, Neighbors>()
        fun nbOf(i: Int): Neighbors {
            val p = pointsW[i]
            val lab = if (same && labels!![i] > 0) labels[i] else 0
            return cache.getOrPut(NbKey(p.x, p.z, lab)) { Neighbors(neighbors(i)) }
        }

        // 대기열에는 점을 한 번만 넣는다. 예전처럼 같은 점을 여러 번 넣어도 두 번째부터는 꺼낼 때 아무 일도 하지 않으므로(첫 꺼냄에서 라벨이
        // 정해진다) 처음 들어간 순서만 지키면 군집과 구성원 순서가 같다. 한 번 넣은 점은 군집이 바뀌어도 다시 넣을 일이 없다(라벨이 이미 정해짐).
        val queued = BooleanArray(pointsW.size)
        val queue = IntArray(pointsW.size)
        var tail = 0
        fun enqueue(nb: Neighbors) {
            if (nb.enqueued) return // 이 목록의 점은 모두 이미 넣었다
            for (j in nb.idx) if (!queued[j]) {
                queued[j] = true
                queue[tail++] = j
            }
            nb.enqueued = true
        }

        val label = IntArray(pointsW.size) { UNVISITED }
        val clusters = ArrayList<List<Int>>()
        var head = 0
        for (i in pointsW.indices) {
            if (label[i] != UNVISITED) continue
            val nb = nbOf(i)
            if (nb.idx.size < minSamples || canExpand?.get(i) == false) {
                label[i] = NOISE
                continue
            }
            val id = clusters.size
            val members = ArrayList<Int>()
            label[i] = id
            members += i
            queued[i] = true
            enqueue(nb)
            while (head < tail) {
                val j = queue[head++]
                if (label[j] == NOISE) {
                    label[j] = id // 경계점
                    members += j
                }
                if (label[j] != UNVISITED) continue
                label[j] = id
                members += j
                val nb2 = nbOf(j)
                if (nb2.idx.size >= minSamples && canExpand?.get(j) != false) enqueue(nb2)
            }
            clusters += members
        }
        return clusters
    }

    private data class NbKey(val x: Float, val z: Float, val label: Int)

    private class Neighbors(val idx: List<Int>) {
        var enqueued = false
    }

    private const val UNVISITED = -2
    private const val NOISE = -1
}
