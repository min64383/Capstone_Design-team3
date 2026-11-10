package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.tracking.Cluster
import kotlin.math.abs
import kotlin.math.sqrt

/** 프레임 바닥면(M54 설계 3.7절, 기록만): 월드 평면 y = a·x + b·z + c, 맞춘 점 수, 이번 장에 새로 맞췄는지(false면 앞 장 면 유지). */
data class FramePlane(val a: Float, val b: Float, val c: Float, val n: Int, val fresh: Boolean)

/** 프레임 군집 하나의 법선 기록(M54 설계 3.8절): 종류, 칸 수, 중심, 카메라 쪽 법선, 두께 √λ3, 길이 √λ1. */
data class FrameCluster(val kind: VoxelRole, val n: Int, val centerW: Vec3, val normalW: Vec3, val thicknessM: Float, val lengthM: Float)

/** 기록용 계산(안내에는 쓰지 않는다). */
object FrameRecords {
    /**
     * 가중 최소제곱 평면 y = a·x + b·z + c. 2 × 가중 잔차 RMS 밖 점을 한 번 빼고 다시 맞춘다. 점 무게 합이 0이거나 점이 [minPoints]보다 적으면 null.
     * [pts]는 (x, y, z) 교차, [w]는 점 무게.
     */
    fun fitPlane(pts: FloatArray, w: FloatArray, minPoints: Int): Triple<Float, Float, Float>? {
        var keep = BooleanArray(w.size) { true }
        var res: DoubleArray? = null
        repeat(2) { pass ->
            val s = DoubleArray(9) // Σw·[xx, xz, x, zz, z, 1, xy, zy, y]
            var n = 0
            for (i in w.indices) {
                if (!keep[i]) continue
                val x = pts[3 * i].toDouble(); val y = pts[3 * i + 1].toDouble(); val z = pts[3 * i + 2].toDouble(); val wi = w[i].toDouble()
                s[0] += wi * x * x; s[1] += wi * x * z; s[2] += wi * x; s[3] += wi * z * z; s[4] += wi * z; s[5] += wi
                s[6] += wi * x * y; s[7] += wi * z * y; s[8] += wi * y
                n++
            }
            if (n < minPoints || s[5] <= 0.0) return null
            val m = arrayOf(doubleArrayOf(s[0], s[1], s[2]), doubleArrayOf(s[1], s[3], s[4]), doubleArrayOf(s[2], s[4], s[5]))
            val sol = solve3(m, doubleArrayOf(s[6], s[7], s[8])) ?: return null
            res = sol
            if (pass == 0) {
                var e2 = 0.0
                var ws = 0.0
                for (i in w.indices) {
                    val r = pts[3 * i + 1] - (sol[0] * pts[3 * i] + sol[1] * pts[3 * i + 2] + sol[2])
                    e2 += w[i] * r * r; ws += w[i]
                }
                val lim = 2 * sqrt(e2 / ws)
                keep = BooleanArray(w.size) { i -> abs(pts[3 * i + 1] - (sol[0] * pts[3 * i] + sol[1] * pts[3 * i + 2] + sol[2])) <= lim }
            }
        }
        return res?.let { Triple(it[0].toFloat(), it[1].toFloat(), it[2].toFloat()) }
    }

    private fun solve3(m: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val det = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) - m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
        if (abs(det) < 1e-12) return null
        fun d(c: Int) = Array(3) { r -> DoubleArray(3) { k -> if (k == c) b[r] else m[r][k] } }.let { a ->
            a[0][0] * (a[1][1] * a[2][2] - a[1][2] * a[2][1]) - a[0][1] * (a[1][0] * a[2][2] - a[1][2] * a[2][0]) + a[0][2] * (a[1][0] * a[2][1] - a[1][1] * a[2][0])
        }
        return doubleArrayOf(d(0) / det, d(1) / det, d(2) / det)
    }

    /**
     * 프레임 역할 칸([VoxelRole.BUILDING]·[VoxelRole.FLOORISH])을 종류마다 위에서 본 DBSCAN([epsM]·[minSamples], 안내 군집과 같은 규칙)으로 묶고, 군집마다
     * 칸 중심 공분산의 가장 작은 고윳값 방향을 법선으로(Hoppe 1992), 부호는 [cameraW] 쪽. 사용자에서 수평 [radiusM] 안 칸만.
     */
    fun clusters(occupied: List<VoxelView>, cameraW: Vec3, radiusM: Float, epsM: Float, minSamples: Int): List<FrameCluster> {
        val out = ArrayList<FrameCluster>()
        for (kind in listOf(VoxelRole.BUILDING, VoxelRole.FLOORISH)) {
            val cells = occupied.filter { it.role == kind && (it.centerW - cameraW).horizontal().norm() <= radiusM }.map { it.centerW }
            if (cells.size < minSamples) continue
            for (idx in Cluster.dbscanXZ(cells, epsM, minSamples)) {
                val c = idx.map { cells[it] }
                out += pca(kind, c, cameraW) ?: continue
            }
        }
        return out
    }

    private fun pca(kind: VoxelRole, c: List<Vec3>, cameraW: Vec3): FrameCluster? {
        if (c.size < 3) return null
        val mx = c.sumOf { it.x.toDouble() } / c.size
        val my = c.sumOf { it.y.toDouble() } / c.size
        val mz = c.sumOf { it.z.toDouble() } / c.size
        val a = Array(3) { DoubleArray(3) }
        for (p in c) {
            val d = doubleArrayOf(p.x - mx, p.y - my, p.z - mz)
            for (r in 0..2) for (k in 0..2) a[r][k] += d[r] * d[k] / c.size
        }
        val (vals, vecs) = jacobi(a)
        val order = (0..2).sortedBy { vals[it] }
        val nIdx = order[0]
        var n = Vec3(vecs[0][nIdx].toFloat(), vecs[1][nIdx].toFloat(), vecs[2][nIdx].toFloat())
        val center = Vec3(mx.toFloat(), my.toFloat(), mz.toFloat())
        if ((cameraW - center) dot n < 0f) n = n * -1f
        return FrameCluster(kind, c.size, center, n, sqrt(maxOf(vals[order[0]], 0.0)).toFloat(), sqrt(maxOf(vals[order[2]], 0.0)).toFloat())
    }

    /** 대칭 3×3 행렬의 고윳값·고유벡터(열) — 야코비 회전. */
    private fun jacobi(m: Array<DoubleArray>): Pair<DoubleArray, Array<DoubleArray>> {
        val a = Array(3) { m[it].copyOf() }
        val v = Array(3) { r -> DoubleArray(3) { k -> if (r == k) 1.0 else 0.0 } }
        repeat(50) {
            var p = 0; var q = 1
            for ((i, j) in listOf(0 to 1, 0 to 2, 1 to 2)) if (abs(a[i][j]) > abs(a[p][q])) { p = i; q = j }
            if (abs(a[p][q]) < 1e-15) return DoubleArray(3) { a[it][it] } to v
            val theta = (a[q][q] - a[p][p]) / (2 * a[p][q])
            val t = (if (theta >= 0) 1.0 else -1.0) / (abs(theta) + sqrt(theta * theta + 1))
            val cs = 1 / sqrt(t * t + 1); val sn = t * cs
            for (k in 0..2) {
                val akp = a[k][p]; val akq = a[k][q]
                a[k][p] = cs * akp - sn * akq; a[k][q] = sn * akp + cs * akq
            }
            for (k in 0..2) {
                val apk = a[p][k]; val aqk = a[q][k]
                a[p][k] = cs * apk - sn * aqk; a[q][k] = sn * apk + cs * aqk
            }
            for (k in 0..2) {
                val vkp = v[k][p]; val vkq = v[k][q]
                v[k][p] = cs * vkp - sn * vkq; v[k][q] = sn * vkp + cs * vkq
            }
        }
        return DoubleArray(3) { a[it][it] } to v
    }
}
