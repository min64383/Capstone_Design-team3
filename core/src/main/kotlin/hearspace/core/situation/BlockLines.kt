package hearspace.core.situation

import hearspace.core.geometry.Vec3
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 블록 선 하나(M50 설계 표 5): 수평면의 직선 [p0] + t·[dir](단위, y = 0), 안쪽 점이 든 구간 [tMin, tMax].
 */
class BlockLine(val p0: Vec3, val dir: Vec3, val tMin: Float, val tMax: Float, val nInliers: Int) {
    val lengthM: Float get() = tMax - tMin

    fun at(t: Float): Vec3 = p0 + dir * t

    fun t(p: Vec3): Float = (p - p0).horizontal() dot dir

    /** [p]에서 직선까지의 부호 있는 거리(+ = [dir]의 오른쪽). */
    fun lateral(p: Vec3): Float = (p - p0).horizontal() dot rightOf(dir)

    /** 방향을 뒤집은 같은 선(진행 방향과 맞출 때). */
    fun flipped(): BlockLine = BlockLine(p0, -dir, -tMax, -tMin, nInliers)

    companion object {
        /** 수평 방향 [f]의 오른쪽(월드 +Y 위, 오른손 좌표). */
        fun rightOf(f: Vec3): Vec3 = Vec3(-f.z, 0f, f.x)
    }
}

/**
 * 블록 칸 중심들에서 직선을 차례로 찾는다(RANSAC, 같은 입력이면 같은 출력: 고정 씨앗). 한 선을 찾으면 그 안쪽 점을 빼고 다음 선을 찾는다.
 * [inlierM] + 칸 절반([cellM] / 2, 블록 가장자리에 반쯤 걸친 칸의 중심이 블록 밖에 있을 수 있음) 안의 점을 안쪽으로 보고, 안쪽 점의 주축으로 방향을
 * 다듬은 뒤 그 선으로 안쪽 점을 다시 모아 두 번 더 다듬는다(우연히 뽑은 기운 선에 맞춰지지 않게). 길이가 [minLengthM]보다 짧은 선은 버리고 멈춘다.
 */
object BlockLines {
    fun fit(points: List<Vec3>, inlierM: Float, minLengthM: Float, cellM: Float, maxLines: Int = 3, iterations: Int = 200): List<BlockLine> {
        val tol = inlierM + cellM / 2f
        val rest = points.toMutableList()
        val out = ArrayList<BlockLine>()
        val rnd = java.util.Random(1L)
        while (out.size < maxLines && rest.size >= 2) {
            var best: List<Vec3> = emptyList()
            repeat(iterations) {
                val a = rest[rnd.nextInt(rest.size)]
                val b = rest[rnd.nextInt(rest.size)]
                val d = (b - a).horizontal()
                val n = d.norm()
                if (n < 1e-3f) return@repeat
                val dir = d * (1f / n)
                val right = BlockLine.rightOf(dir)
                val inl = rest.filter { abs((it - a).horizontal() dot right) <= tol }
                if (inl.size > best.size) best = inl
            }
            var line = refine(best) ?: break
            repeat(2) {
                val again = rest.filter { p -> abs(line.lateral(p)) <= tol }
                line = refine(again) ?: return@repeat
            }
            if (line.lengthM < minLengthM) break
            out.add(line)
            val l = line
            rest.removeAll { abs(l.lateral(it)) <= tol && l.t(it) in l.tMin..l.tMax }
        }
        return out
    }

    /** 안쪽 점의 무게중심과 주축(2×2 공분산의 큰 고유벡터)으로 선을 다시 맞춘다. */
    private fun refine(pts: List<Vec3>): BlockLine? {
        if (pts.size < 2) return null
        val cx = pts.sumOf { it.x.toDouble() } / pts.size
        val cz = pts.sumOf { it.z.toDouble() } / pts.size
        var sxx = 0.0
        var sxz = 0.0
        var szz = 0.0
        for (p in pts) {
            val dx = p.x - cx
            val dz = p.z - cz
            sxx += dx * dx; sxz += dx * dz; szz += dz * dz
        }
        val tr = sxx + szz
        val det = sxx * szz - sxz * sxz
        val l1 = tr / 2 + sqrt(maxOf(0.0, tr * tr / 4 - det))
        // 고유벡터는 (l1 − szz, sxz) 또는 (sxz, l1 − sxx). 주축 쪽 성분이 큰 식을 써야 거의 축과 나란한 줄에서 0/0이 되지 않는다
        val (ex, ez) = if (sxx >= szz) Pair(l1 - szz, sxz) else Pair(sxz, l1 - sxx)
        val n = sqrt(ex * ex + ez * ez)
        val dir = Vec3((ex / n).toFloat(), 0f, (ez / n).toFloat())
        val p0 = Vec3(cx.toFloat(), 0f, cz.toFloat())
        val ts = pts.map { (it - p0).horizontal() dot dir }
        return BlockLine(p0, dir, ts.min(), ts.max(), pts.size)
    }
}
