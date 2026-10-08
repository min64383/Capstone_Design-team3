package hearspace.core.frontend

import hearspace.core.geometry.Projection
import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Vec3
import hearspace.core.types.FrontendConfig
import hearspace.core.types.Intrinsics
import java.util.Random
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 바닥: 바닥 점의 중앙 거리에서의 월드 높이 [heightM]와 거리에 따른 들림 기울기 [liftPerM](m/m, 평활 깊이의 바닥은 멀수록 위로
 * 들려 보인다), 평면에 든 점 수, 수평 퍼짐(작은 주축 표준편차, m), 거리 방향 폭(5~95%, m).
 */
data class FloorPlane(val heightM: Float, val liftPerM: Float, val nInliers: Int, val spreadM: Float, val spanM: Float)

/** 수직 벽 평면: 위에서 본 직선 구간(월드 x, z)과 평면에 든 점 수, 직선에서 벗어난 정도(RMS, m). */
data class WallPlane(val x0: Float, val z0: Float, val x1: Float, val z1: Float, val nInliers: Int, val rmsM: Float) {
    val lengthM get() = hypot(x1 - x0, z1 - z0)
}

/** 평면 추출 결과. [labels]는 입력 점마다 0 = 없음, [FLOOR], [WALL]. */
class PlaneResult(val floor: FloorPlane?, val walls: List<WallPlane>, val labels: ByteArray) {
    companion object {
        const val FLOOR: Byte = 1
        const val WALL: Byte = 2
    }
}

/**
 * 깊이 영상 앞단 ③ 평면 표시 (IMPROVE_SPEC §6.1.1 M13.1c). 깊이 한 장의 월드 점에서 바닥과 큰 수직 벽을 찾는다.
 *
 * **바닥**: 카메라 아래 점에 (수평거리, 높이) 직선을 RANSAC으로 맞춘다(기울기 허용: 멀수록 들리는 평활 깊이). 벽 아래 둥글게 올라간
 * 띠·작은 물체 윗면·한 줄로 모인 벽 점은 폭·퍼짐·기울기 조건에서 걸러진다. 바닥이 시야에 없으면 `null`이다(M12.0: 끝 벽 앞에서
 * 히스토그램 최빈값이 벽을 따라 올라간 문제, M13.1c: 수평 평면으로 하면 벽 아래 띠가 선택되고 진짜 바닥은 탈락).
 * **벽**: 카메라 높이 둘레 띠의 점(바닥·천장 제외)을 위에서 본 직선으로 순차 RANSAC(결정적 시드)하고, 점 수·길이·높이 범위가
 * 충분한 직선을 수직 평면으로 낸다(최대 `wallMaxPlanes`).
 * 허용 오차는 거리에 따라 늘어난다(`planeTolM + planeTolPerM × 수평거리`, 깊이 오차가 거리에 비례).
 */
object PlaneDetector {
    private const val SEED = 7L

    /** 깊이 한 장(행 우선 [depthMm], 0 = 무효)의 평면과 픽셀 라벨(`k.width × k.height`, 격자 [subsample] 간격 블록으로 채움). */
    class DepthPlanes(val planes: PlaneResult, val pixelLabels: ByteArray)

    /** [Projection.backprojectToWorld]와 같은 격자로 역투영해 평면을 찾고 라벨을 픽셀로 되돌린다(뷰어 표시용). */
    fun detectDepth(
        depthMm: ShortArray, k: Intrinsics, worldFromCam: Mat4, subsample: Int, cfg: FrontendConfig, maxDistM: Float,
    ): DepthPlanes {
        val pix = IntArray(depthMm.size)
        val pts = FloatArray(3 * depthMm.size)
        var n = 0
        var v = 0
        while (v < k.height) {
            var u = 0
            while (u < k.width) {
                val mm = depthMm[v * k.width + u].toInt() and 0xFFFF
                if (mm != 0) {
                    val p = worldFromCam.transformPoint(Projection.backproject(u.toFloat(), v.toFloat(), mm / 1000f, k))
                    pts[3 * n] = p.x; pts[3 * n + 1] = p.y; pts[3 * n + 2] = p.z
                    pix[n++] = v * k.width + u
                }
                u += subsample
            }
            v += subsample
        }
        val r = detect(pts.copyOf(3 * n), worldFromCam.translation(), cfg, maxDistM)
        val out = ByteArray(depthMm.size)
        for (i in 0 until n) {
            val l = r.labels[i]
            if (l == 0.toByte()) continue
            val u0 = pix[i] % k.width
            val v0 = pix[i] / k.width
            for (dv in 0 until subsample) for (du in 0 until subsample) {
                val u1 = u0 + du
                val v1 = v0 + dv
                if (u1 < k.width && v1 < k.height) out[v1 * k.width + u1] = l
            }
        }
        return DepthPlanes(r, out)
    }

    /** 월드 점 배열(x, y, z 교차)과 카메라 위치로 바닥·벽 평면. [maxDistM]은 카메라에서 수평 이 거리 안의 점만 쓴다(`map.radiusM`). */
    fun detect(pointsW: FloatArray, cameraW: Vec3, cfg: FrontendConfig, maxDistM: Float): PlaneResult {
        val n = pointsW.size / 3
        val labels = ByteArray(n)
        val dist = FloatArray(n) { hypot(pointsW[3 * it] - cameraW.x, pointsW[3 * it + 2] - cameraW.z) }
        fun tol(d: Float) = cfg.planeTolM + cfg.planeTolPerM * d
        // 벽을 먼저 찾아 바닥 후보에서 뺀다: 복도의 양쪽 벽 점은 두 평행선이라 수평 퍼짐·폭 검사를 통과하고 높이 띠가 직선에 맞는다(S02 실측)
        val walls = findWalls(pointsW, dist, cameraW, cfg, maxDistM, ::tol, labels)
        val floor = findFloor(pointsW, dist, cameraW, cfg, maxDistM, ::tol, labels)
        return PlaneResult(floor, walls, labels)
    }

    /**
     * 바닥: 카메라 아래 후보 점의 (수평거리 d, 높이 y)에 직선 y = a + b·d를 결정적 RANSAC으로 맞춘다. 평활 깊이의 바닥은 멀수록
     * 위로 들려 보이므로(M3 실측 약 0.08 m/m, M13.1c에서 S02 장면 확인) 수평 평면이 아니라 기울기 b ∈ [`floorMinLiftPerM`, `floorMaxLiftPerM`]를 둔다.
     * 채택 조건: 점 수 ≥ `floorMinPoints`, 거리 방향 폭(5~95%) ≥ `floorMinSpanM`(작은 물체 윗면 제외), 수평 퍼짐(작은 주축
     * 표준편차) ≥ `floorMinSpreadM`(벽을 따라 한 줄로 모인 점 제외), 카메라보다 [`floorMinDropM`, `floorMaxDropM`] 아래, 바닥 선
     * 아래에 큰 구조 없음. 수평거리 `floorMaxDistM`(3 m)까지의 점만 쓴다: 평활 깊이의 먼 바닥은 비선형으로 위로 휜다(E02: 1.9 m −1.28,
     * 2.6 m −1.14, 3.5 m −0.86, 4.5 m −0.48 m, 카메라 기준). 보고 높이는 바닥 점의 중앙 거리에서의 직선 값이다.
     */
    private fun findFloor(
        p: FloatArray, dist: FloatArray, cam: Vec3, cfg: FrontendConfig, maxDistM: Float, tol: (Float) -> Float, labels: ByteArray,
    ): FloorPlane? {
        val cand = (0 until dist.size).filter {
            labels[it] == 0.toByte() && p[3 * it + 1] <= cam.y - cfg.planeFloorMinBelowM && dist[it] <= min(maxDistM, cfg.floorMaxDistM)
        }
        if (cand.size < cfg.floorMinPoints) return null
        // 바닥 허용 오차: 거리 비례분이 벽보다 크다(평활 깊이의 바닥은 곡선이라 직선 띠가 거리에 따라 넓어져야 한다, M3 실측 0.08 m/m)
        fun ftol(d: Float) = cfg.planeTolM + cfg.floorTolPerM * d
        val rnd = Random(SEED + 1)
        var best: List<Int> = emptyList()
        repeat(cfg.planeIterations) {
            val i = cand[rnd.nextInt(cand.size)]
            val j = cand[rnd.nextInt(cand.size)]
            val dd = dist[j] - dist[i]
            if (abs(dd) < cfg.floorMinSpanM / 2) return@repeat
            val b = (p[3 * j + 1] - p[3 * i + 1]) / dd
            if (b < cfg.floorMinLiftPerM || b > cfg.floorMaxLiftPerM) return@repeat
            val a = p[3 * i + 1] - b * dist[i]
            if (cam.y - a !in cfg.floorMinDropM..cfg.floorMaxDropM) return@repeat
            val inl = cand.filter { abs(p[3 * it + 1] - (a + b * dist[it])) < ftol(dist[it]) }
            if (inl.size > best.size) best = inl
        }
        if (best.size < cfg.floorMinPoints) return null
        // 최소제곱으로 다듬고 다시 센다
        var fit = fitFloor(p, dist, best)
        val refined = cand.filter { abs(p[3 * it + 1] - (fit.first + fit.second * dist[it])) < ftol(dist[it]) }
        if (refined.size >= cfg.floorMinPoints) {
            best = refined
            fit = fitFloor(p, dist, best)
        }
        val (a, b) = fit
        if (b < cfg.floorMinLiftPerM || b > cfg.floorMaxLiftPerM || cam.y - a !in cfg.floorMinDropM..cfg.floorMaxDropM) return null
        val ds = best.map { dist[it] }.sorted()
        val span = ds[((ds.size - 1) * 0.95).toInt()] - ds[((ds.size - 1) * 0.05).toInt()]
        val spread = minorSpread(p, best)
        if (span < cfg.floorMinSpanM || spread < cfg.floorMinSpreadM) return null
        // 바닥 아래에 큰 구조가 없어야 한다: 선보다 `floorUnderM` 넘게 아래인 후보 점이 바닥 점의 `floorUnderFraction`을 넘으면 바닥 위의 면(벽 띠·물체 면)이다
        val inSet = best.toHashSet()
        val under = cand.count { it !in inSet && p[3 * it + 1] < a + b * dist[it] - cfg.floorUnderM }
        if (under > cfg.floorUnderFraction * best.size) return null
        for (i in best) labels[i] = PlaneResult.FLOOR
        // 보고 높이는 점들의 중앙 거리(50%)에서의 직선 값 = 바닥 점 높이의 평균: 기존 "바닥 점" 판정(바닥 높이 ± 오차 + 거리 비례)이
        // 히스토그램 최빈값(= 바닥 점이 가장 많은 높이)을 기대하므로 같은 의미로 맞춘다(가장 가까운 거리 값은 S01에서 0.2 m 낮아
        // 바닥 점이 장애물로 넘어갔다)
        return FloorPlane(a + b * ds[(ds.size - 1) / 2], b, best.size, spread, span)
    }

    /** y = a + b·d 최소제곱. */
    private fun fitFloor(p: FloatArray, dist: FloatArray, idx: List<Int>): Pair<Float, Float> {
        val m = idx.size
        val dm = idx.sumOf { dist[it].toDouble() } / m
        val ym = idx.sumOf { p[3 * it + 1].toDouble() } / m
        var sdd = 0.0
        var sdy = 0.0
        for (i in idx) {
            sdd += (dist[i] - dm) * (dist[i] - dm)
            sdy += (dist[i] - dm) * (p[3 * i + 1] - ym)
        }
        val b = if (sdd > 1e-9) sdy / sdd else 0.0
        return (ym - b * dm).toFloat() to b.toFloat()
    }

    /** 점들의 수평(x, z) 분포에서 작은 주축의 표준편차. */
    private fun minorSpread(p: FloatArray, idx: List<Int>): Float {
        val m = idx.size
        val mx = idx.sumOf { p[3 * it].toDouble() } / m
        val mz = idx.sumOf { p[3 * it + 2].toDouble() } / m
        var a = 0.0
        var d = 0.0
        var b = 0.0
        for (i in idx) {
            val x = p[3 * i] - mx
            val z = p[3 * i + 2] - mz
            a += x * x; d += z * z; b += x * z
        }
        a /= m; d /= m; b /= m
        return sqrt(max(0.0, (a + d) / 2 - sqrt(((a - d) / 2) * ((a - d) / 2) + b * b))).toFloat()
    }

    private fun findWalls(
        p: FloatArray, dist: FloatArray, cam: Vec3, cfg: FrontendConfig, maxDistM: Float, tol: (Float) -> Float, labels: ByteArray,
    ): List<WallPlane> {
        val pool = (0 until dist.size).filterTo(ArrayList()) {
            labels[it] == 0.toByte() && dist[it] <= maxDistM &&
                p[3 * it + 1] >= cam.y - cfg.wallBandBelowM && p[3 * it + 1] <= cam.y + cfg.wallBandAboveM
        }
        val band = pool.toList() // 높이 띠 안 점 전부(다른 벽에 쓰인 점 포함, 길이 잴 때)
        val rnd = Random(SEED)
        val walls = ArrayList<WallPlane>()
        var attempts = 0
        while (walls.size < cfg.wallMaxPlanes && attempts < 2 * cfg.wallMaxPlanes && pool.size >= cfg.wallMinPoints) {
            attempts++
            // 순차 RANSAC: 두 점으로 직선, 허용 오차 안의 점 수 최대
            var bestIn: List<Int> = emptyList()
            repeat(cfg.planeIterations) {
                val a = pool[rnd.nextInt(pool.size)]
                val b = pool[rnd.nextInt(pool.size)]
                val dx = p[3 * b] - p[3 * a]
                val dz = p[3 * b + 2] - p[3 * a + 2]
                val len = hypot(dx, dz)
                if (len < cfg.wallMinLengthM / 3) return@repeat
                val nx = -dz / len
                val nz = dx / len
                val inl = pool.filter { abs(nx * (p[3 * it] - p[3 * a]) + nz * (p[3 * it + 2] - p[3 * a + 2])) < tol(dist[it]) }
                if (inl.size > bestIn.size) bestIn = inl
            }
            if (bestIn.size < cfg.wallMinPoints) break
            // 최소제곱(주성분)으로 다듬고 다시 센다
            var line = fitLine(p, bestIn)
            val refined = pool.filter { line.offset(p[3 * it], p[3 * it + 2]) < tol(dist[it]) }
            if (refined.size >= cfg.wallMinPoints) {
                bestIn = refined
                line = fitLine(p, bestIn)
            }
            // 연속성: 직선 위 간격이 wallMaxGapM보다 벌어지면 끊고 가장 긴 구간만(떨어진 면들을 한 직선으로 잇지 않게)
            val inliers = bestIn
            bestIn = longestRun(p, inliers, line, cfg.wallMaxGapM)
            if (bestIn.size >= cfg.wallMinPoints && bestIn.size < inliers.size) line = fitLine(p, bestIn)
            if (bestIn.isEmpty()) break
            // 길이는 이미 다른 벽에 쓰인 점까지 포함해 잰다: 모서리 점은 두 벽 모두에 속한다. 거리에 비례한 허용 오차 때문에 먼 끝 벽의
            // 양 끝이 먼저 찾은 옆 벽에 들어가 짧아져(4 m에서 1.04 → 0.74 m) 벽으로 안 잡혔다(합성 C2 장면)
            val span = longestRun(p, band.filter { line.offset(p[3 * it], p[3 * it + 2]) < tol(dist[it]) }, line, cfg.wallMaxGapM)
            val proj = (if (span.size > bestIn.size) span else bestIn).map { line.along(p[3 * it], p[3 * it + 2]) }
            val lengthM = proj.max() - proj.min()
            val heightRange = bestIn.maxOf { p[3 * it + 1] } - bestIn.minOf { p[3 * it + 1] }
            val ok = bestIn.size >= cfg.wallMinPoints && lengthM >= cfg.wallMinLengthM && heightRange >= cfg.wallMinHeightM
            if (ok) {
                val rms = sqrt(bestIn.sumOf { line.offset(p[3 * it], p[3 * it + 2]).toDouble().let { d -> d * d } } / bestIn.size).toFloat()
                walls += WallPlane(
                    line.cx + line.dx * proj.min(), line.cz + line.dz * proj.min(),
                    line.cx + line.dx * proj.max(), line.cz + line.dz * proj.max(), bestIn.size, rms,
                )
                for (i in bestIn) labels[i] = PlaneResult.WALL
                // 벽은 수직이므로 찾은 직선(길이 범위) 위의 점은 높이 띠 밖(벽 밑동·윗부분)도 벽이다. 군집이 벽과 물체를 가를 때(C2)
                // 벽 밑동 칸이 라벨 없이 남아 다리가 되지 않게 한다. [WallPlane.nInliers]는 맞춤에 쓴 띠 안 점 수 그대로다.
                val lo = proj.min()
                val hi = proj.max()
                for (i in 0 until dist.size) {
                    if (labels[i] != 0.toByte() || dist[i] > maxDistM) continue
                    val a = line.along(p[3 * i], p[3 * i + 2])
                    if (a in lo..hi && line.offset(p[3 * i], p[3 * i + 2]) < tol(dist[i])) labels[i] = PlaneResult.WALL
                }
            }
            val used = (if (ok) bestIn else inliers).toHashSet()
            pool.removeAll { it in used }
        }
        return walls
    }

    /** [idx]를 직선 위 위치로 늘어놓아 간격이 [maxGapM]보다 큰 곳에서 끊었을 때 가장 긴(길이 기준) 연속 구간. */
    private fun longestRun(p: FloatArray, idx: List<Int>, line: Line, maxGapM: Float): List<Int> {
        if (idx.isEmpty()) return idx
        val sorted = idx.sortedBy { line.along(p[3 * it], p[3 * it + 2]) }
        val a = sorted.map { line.along(p[3 * it], p[3 * it + 2]) }
        var bestFrom = 0
        var bestTo = 0
        var from = 0
        for (k in 1..sorted.size) {
            if (k < sorted.size && a[k] - a[k - 1] <= maxGapM) continue
            if (a[k - 1] - a[from] > a[bestTo] - a[bestFrom]) {
                bestFrom = from
                bestTo = k - 1
            }
            from = k
        }
        return sorted.subList(bestFrom, bestTo + 1)
    }

    /** 위에서 본 직선: 중심 ([cx], [cz]), 방향 ([dx], [dz]) 단위 벡터. */
    private class Line(val cx: Float, val cz: Float, val dx: Float, val dz: Float) {
        fun offset(x: Float, z: Float) = abs(-dz * (x - cx) + dx * (z - cz))
        fun along(x: Float, z: Float) = dx * (x - cx) + dz * (z - cz)
    }

    private fun fitLine(p: FloatArray, idx: List<Int>): Line {
        val m = idx.size
        val cx = idx.sumOf { p[3 * it].toDouble() } / m
        val cz = idx.sumOf { p[3 * it + 2].toDouble() } / m
        var a = 0.0
        var d = 0.0
        var b = 0.0
        for (i in idx) {
            val x = p[3 * i] - cx
            val z = p[3 * i + 2] - cz
            a += x * x; d += z * z; b += x * z
        }
        val th = 0.5 * atan2(2 * b, a - d)
        return Line(cx.toFloat(), cz.toFloat(), cos(th).toFloat(), sin(th).toFloat())
    }
}
