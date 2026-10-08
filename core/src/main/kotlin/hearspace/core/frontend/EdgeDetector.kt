package hearspace.core.frontend

import hearspace.core.geometry.Vec3
import hearspace.core.types.DepthFrame
import hearspace.core.types.FrontendConfig
import hearspace.core.types.Intrinsics
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 깊이 영상 앞단 ① 경계 판정 (IMPROVE_SPEC §6.1.1 M13.1, 주사선 선분 방식: Sabov 2008).
 *
 * 가로·세로·두 대각선 주사선마다 깊이를 선형 조각으로 나눈다(앞에서부터, 직선 맞춤 오차가 `edgeFitTolRatio` × 깊이 안인 동안
 * 늘린다). 대각선은 물체 모서리용이다: 모서리 픽셀은 평활이 가로·세로로 함께 섞여 두 방향 모두 한쪽에 긴 평탄 조각이 없다.
 * 픽셀당 깊이 변화가 깊이의 `edgeSteepRatio`를 넘는 조각과 한 픽셀 조각을 경사 조각, 그 밖의 두 픽셀 이상 조각을 표면으로
 * 본다(길이가 아니라 기울기로 가른다: 막 앞의 뒤 배경 끌림처럼 완만하게 휜 표면은 짧은 조각으로 잘려도 표면이다. M13.1a 실측
 * E01h 막 경사 9~25%/픽셀, 끌림 1~3%/픽셀). 이웃한 두 표면 사이의 경사 조각들(합 ≤ `edgeMaxRampPx`)에서 두 표면의 맞닿는 끝 깊이
 * 차가 가까운 쪽 깊이의 `edgeMinStepRatio`를 넘으면(불연속) 경사 픽셀 중 두 표면 직선 어느 쪽에서도 벗어난 픽셀을 경계로 표시한다. 단 경사 픽셀들의 높이 변화가 깊이 변화의 `levelMaxSlope` 배 이하(+ `levelTolM`)이고
 * 카메라보다 `levelMinBelowCameraM` 이상 낮으면 다가가며 비스듬히 보이는 상자 윗면 같은 수평면으로 보고 남긴다. 막은 내려다보는
 * 광선을 따라 늘어선 점이라 높이 변화가 깊이 변화 × tan(내려다보는 각)이다. 높이 차의 절대값만 보면 얕은 각에서 짧은 막
 * (2픽셀, 깊이 차 0.35 m)이 5 cm 안에 들어와 윗면으로 오인된다(M13.1a 실측 E01h).
 * - 막(평활이 앞 물체와 뒤 배경을 잇는 경사): 평활 폭만큼의 짧은 경사 → 경계.
 * - 비스듬히 보이는 실제 벽: 영상에서 긴 조각 하나 → 경계 아님(1차 시도의 그림자 필터가 지운 것).
 * - 평활 폭보다 좁은 기둥: 짧은 조각 양쪽이 같은 배경이라 불연속이 아님 → 남김(번지지만 지우지 않는다).
 */
object EdgeDetector {

    /** 깊이 한 장의 경계 마스크(행 우선, true = 경계). 높이는 깊이 촬영 자세의 중력 방향으로 잰다. */
    fun boundaryMask(depth: DepthFrame, cfg: FrontendConfig, depthMm: ShortArray = depth.depthMm): BooleanArray =
        boundaryMask(depthMm, depth.K, depth.worldFromCam.rigidInverse().transformDir(Vec3.UP), cfg)

    /** [upCam]: 월드 위쪽 단위 벡터를 C_cv(+X 오른쪽, +Y 아래, +Z 앞)로 나타낸 것. */
    fun boundaryMask(depthMm: ShortArray, k: Intrinsics, upCam: Vec3, cfg: FrontendConfig): BooleanArray {
        require(depthMm.size == k.width * k.height) { "depth size ${depthMm.size} != ${k.width}x${k.height}" }
        val w = k.width
        val h = k.height
        val mask = BooleanArray(w * h)
        val d = FloatArray(w + h)
        val ht = FloatArray(w + h)
        val idx = IntArray(w + h)
        /** (u0, v0)에서 (du, dv)로 영상 끝까지 가는 주사선 하나. */
        fun line(u0: Int, v0: Int, du: Int, dv: Int) {
            var n = 0
            var u = u0
            var v = v0
            while (u in 0 until w && v in 0 until h) {
                val z = (depthMm[v * w + u].toInt() and 0xFFFF) / 1000f
                d[n] = z
                // 카메라 기준 높이 = C_cv 점 · 위쪽
                ht[n] = ((u - k.cx) * z / k.fx) * upCam.x + ((v - k.cy) * z / k.fy) * upCam.y + z * upCam.z
                idx[n] = v * w + u
                n++
                u += du
                v += dv
            }
            scan(d, ht, n, cfg) { mask[idx[it]] = true }
        }
        for (v in 0 until h) line(0, v, 1, 0)
        for (u in 0 until w) line(u, 0, 0, 1)
        for (v in 0 until h) { line(0, v, 1, 1); line(0, v, 1, -1) }
        for (u in 1 until w) { line(u, 0, 1, 1); line(u, h - 1, 1, -1) }
        return mask
    }

    /** 선형 조각 [start, end]와 직선(깊이 = a + b·픽셀 위치). */
    private class Piece(val start: Int, val end: Int, val a: Float, val b: Float) {
        val length get() = end - start + 1
        fun at(i: Float) = a + b * i
    }

    /** 주사선 하나: 깊이 [d](m, 0 = 무효)와 카메라 기준 높이 [ht](m)의 앞 [n]개. 경계 픽셀 위치를 [mark]로 알린다. */
    internal fun scan(d: FloatArray, ht: FloatArray, n: Int, cfg: FrontendConfig, mark: (Int) -> Unit) {
        val pieces = segment(d, n, cfg.edgeFitTolRatio)
        fun surface(p: Piece) = p.length >= 2 && abs(p.b) <= cfg.edgeSteepRatio * p.at((p.start + p.end) / 2f)
        var p = 0
        while (p < pieces.size) {
            val left = pieces[p]
            if (!surface(left)) { p++; continue }
            // left 다음의 경사 조각들을 모은 뒤 바로 이어지는 표면
            var q = p + 1
            var expect = left.end + 1
            var rampLength = 0
            while (q < pieces.size && pieces[q].start == expect && !surface(pieces[q])) {
                rampLength += pieces[q].length
                expect = pieces[q].end + 1
                q++
            }
            if (q >= pieces.size || pieces[q].start != expect || rampLength == 0 || rampLength > cfg.edgeMaxRampPx) { p = q; continue }
            val right = pieces[q]
            val dl = left.at(left.end.toFloat())
            val dr = right.at(right.start.toFloat())
            if (abs(dl - dr) > cfg.edgeMinStepRatio * min(dl, dr) && !isLevel(d, ht, left.end + 1, right.start - 1, cfg)) {
                for (i in left.end + 1 until right.start) {
                    val tol = cfg.edgeFitTolRatio * d[i]
                    if (abs(d[i] - left.at(i.toFloat())) > tol && abs(d[i] - right.at(i.toFloat())) > tol) mark(i)
                }
            }
            p = q
        }
    }

    /** 경사 픽셀 [from, to]가 수평면(높이 변화 ≤ 깊이 변화 × `levelMaxSlope` + `levelTolM`)이고 카메라보다 충분히 낮은지. */
    private fun isLevel(d: FloatArray, ht: FloatArray, from: Int, to: Int, cfg: FrontendConfig): Boolean {
        var hLo = Float.MAX_VALUE
        var hHi = -Float.MAX_VALUE
        var dLo = Float.MAX_VALUE
        var dHi = -Float.MAX_VALUE
        for (i in from..to) {
            if (d[i] <= 0f) continue
            hLo = min(hLo, ht[i])
            hHi = max(hHi, ht[i])
            dLo = min(dLo, d[i])
            dHi = max(dHi, d[i])
        }
        if (hHi < hLo) return false
        return hHi - hLo <= cfg.levelMaxSlope * (dHi - dLo) + cfg.levelTolM && hHi <= -cfg.levelMinBelowCameraM
    }

    /** 앞에서부터 직선 맞춤 오차(최대 |깊이 − 직선|)가 [tolRatio] × 깊이 안인 동안 늘린 선형 조각들. 무효 픽셀에서 끊는다. */
    private fun segment(d: FloatArray, n: Int, tolRatio: Float): List<Piece> {
        val out = ArrayList<Piece>()
        var i = 0
        while (i < n) {
            if (d[i] <= 0f) { i++; continue }
            var j = i
            var a = d[i]
            var b = 0f
            while (j + 1 < n && d[j + 1] > 0f) {
                val (na, nb) = fit(d, i, j + 1)
                var ok = true
                for (t in i..j + 1) if (abs(d[t] - (na + nb * t)) > tolRatio * d[t]) { ok = false; break }
                if (!ok) break
                j++
                a = na
                b = nb
            }
            out += Piece(i, j, a, b)
            i = j + 1
        }
        return out
    }

    /** [from, to] 최소제곱 직선(깊이 = a + b·위치). */
    private fun fit(d: FloatArray, from: Int, to: Int): Pair<Float, Float> {
        val m = to - from + 1
        var st = 0.0
        var sd = 0.0
        for (t in from..to) { st += t; sd += d[t] }
        val tm = st / m
        val dm = sd / m
        var stt = 0.0
        var std = 0.0
        for (t in from..to) { stt += (t - tm) * (t - tm); std += (t - tm) * (d[t] - dm) }
        val b = if (stt > 0) std / stt else 0.0
        return (dm - b * tm).toFloat() to b.toFloat()
    }
}
