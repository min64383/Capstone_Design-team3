package hearspace.core.frontend

import hearspace.core.geometry.Vec3
import hearspace.core.types.FrontendConfig
import hearspace.core.types.GuideImage
import hearspace.core.types.Intrinsics
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.min

/**
 * RGB 경계로 막 옮기기(IMPROVE_SPEC §6.1.1 M13.7 확장, M23). 경계 판정([EdgeDetector])이 찾은 막 경사마다 같은 장의 전체 해상도
 * 밝기로 경계 위치를 깊이 화소의 1/4 정밀도로 찾고, 경계 앞쪽 막 화소는 앞 면(가까운 쪽 표면 직선)의 깊이로, 뒤쪽은 뒤 면의 깊이로
 * 옮긴다(Ra). [margin]이면 뒤 면 쪽으로 이어지는 배경 당김(평활이 배경을 물체 쪽으로 끌어온 완만한 구간, M13 실측 약 25화소 안
 * 0.15~0.5 m)도 당김이 끝난 바깥 조각의 직선 깊이로 옮긴다(Rb). 지우지 않고 옮기므로 그 시선의 쌓인 막 칸은 빈 공간 관측을 받는다.
 *
 * - RGB 경계 없음: 탐색 구간의 최대 밝기 차가 그 장 깊이 시야 안 밝기 기울기의 p90보다 작으면 옮기지 않는다(호출하는 쪽이 [RgbGuide]로 처리).
 *   강한 RGB 경계의 58~66%가 무늬라 깊이 경사가 있는 곳에서만 찾는다(M23 설계 2.4절).
 * - 바닥 경계: 양쪽 표면 끝 중 하나라도 [isFloor]면 건너뛴다. 걸으며 보면 캐리어 앞 바닥이 아랫단보다 가까워 물체가 먼 쪽이 되고,
 *   먼 쪽 마진을 옮기면 물체 아랫단이 깎인다(M13.1a 끌림 억제의 실패 원인으로 추정).
 * - 여러 주사선이 한 화소를 다르게 옮기면 가까운 쪽을 쓴다(확실하지 않을 때 장애물 면을 지우지 않는 쪽).
 * 스레드를 모르고 같은 입력이면 같은 출력이다. 현재 장의 깊이와 RGB만 쓴다.
 */
object RgbEdge {

    /** 옮긴 깊이, 옮긴 화소, 경계 마스크([EdgeDetector.boundaryMask]와 같음), 경계 수(바닥 건너뜀·RGB 경계 있음 포함). */
    class Result(val depthMm: ShortArray, val moved: BooleanArray, val mask: BooleanArray, val nEdges: Int, val nFloor: Int, val nRgb: Int)

    /**
     * [depthMm](깊이 K [k])의 막을 [guide] 밝기 경계로 옮긴다. [upCam]은 C_cv로 나타낸 월드 위쪽, [isFloor](화소 번호, 깊이 m)는
     * 지난 단계 바닥 추정으로 본 바닥 여부.
     */
    fun refine(
        depthMm: ShortArray, k: Intrinsics, guide: GuideImage, upCam: Vec3, cfg: FrontendConfig, margin: Boolean, isFloor: (Int, Float) -> Boolean,
    ): Result {
        val w = k.width
        val g = guide.K
        val threshold = gradientP90(k, guide)
        val best = FloatArray(w * k.height) { Float.MAX_VALUE }
        val mask = BooleanArray(w * k.height)
        var nEdges = 0
        var nFloor = 0
        var nRgb = 0
        // 깊이 화소 좌표 → 밝기(쌍선형), RGB 밖이면 −1
        fun luma(u: Float, v: Float): Float {
            val x = g.fx * (u - k.cx) / k.fx + g.cx
            val y = g.fy * (v - k.cy) / k.fy + g.cy
            if (x < 0f || y < 0f || x > g.width - 1 || y > g.height - 1) return -1f
            val x0 = min(floor(x).toInt(), g.width - 2)
            val y0 = min(floor(y).toInt(), g.height - 2)
            val fx = x - x0
            val fy = y - y0
            fun at(xx: Int, yy: Int) = (guide.luma[yy * g.width + xx].toInt() and 0xFF).toFloat()
            return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy
        }
        fun propose(p: Int, z: Float) { if (z > 0f && z < best[p]) best[p] = z }

        EdgeDetector.edges(depthMm, k, upCam, cfg) { e ->
            e.forEachMembrane(cfg) { mask[e.idx[it]] = true }
            nEdges++
            val l = e.leftPiece
            val r = e.rightPiece
            val dl = l.at(l.end.toFloat())
            val dr = r.at(r.start.toFloat())
            if (isFloor(e.idx[l.end], dl) || isFloor(e.idx[r.start], dr)) { nFloor++; return@edges }
            // 탐색 구간 = 막 경사 양쪽으로 한 화소씩(두 표면 끝 화소 포함), 깊이 화소의 1/4 간격
            val u0 = (e.idx[l.end] % w).toFloat()
            val v0 = (e.idx[l.end] / w).toFloat()
            val nS = (r.start - l.end) * 4 + 1
            var prev = luma(u0, v0)
            if (prev < 0f) return@edges
            var maxDiff = -1f
            var at = 0
            for (j in 1 until nS) {
                val t = j / 4f
                val cur = luma(u0 + t * e.du, v0 + t * e.dv)
                if (cur < 0f) return@edges
                val diff = abs(cur - prev)
                if (diff > maxDiff) { maxDiff = diff; at = j }
                prev = cur
            }
            // 표본 간격이 RGB 한 화소보다 좁으면(합성: RGB가 깊이와 같은 해상도) 화소당 기울기로 맞춘다
            val stepPx = hypot(e.du * g.fx / k.fx, e.dv * g.fy / k.fy) / 4f
            if (maxDiff <= 0f || maxDiff / min(stepPx, 1f) < threshold) return@edges
            nRgb++
            val b = l.end + (at - 0.5f) / 4f // 경계 위치(주사선 위치)
            val nearLeft = dl < dr
            val near = if (nearLeft) l else r
            val farFirst = if (nearLeft) e.right else e.left
            val bg = if (margin) background(e, farFirst, if (nearLeft) 1 else -1, b, cfg) else e.pieces[farFirst]
            fun depthAt(i: Int): Float = if ((i < b) == nearLeft) near.at(i.toFloat()) else bg.at(i.toFloat())
            e.forEachMembrane(cfg) { i -> propose(e.idx[i], depthAt(i)) }
            if (!margin) return@edges
            // 배경 당김: 뒤 면 쪽으로 평활 깊이가 배경 직선에서 edgeFitTolRatio 안으로 돌아오거나, 물체 밝기가 다시 나오거나,
            // rgbMarginMaxPx를 넘을 때까지 배경 직선 깊이로 옮긴다. 당김은 물체와 배경 사이에만 있으므로 그 밖이면 멈춘다
            val step = if (nearLeft) 1 else -1
            val objLuma = luma(u0 + (if (nearLeft) 0f else (r.start - l.end).toFloat()) * e.du, v0 + (if (nearLeft) 0f else (r.start - l.end).toFloat()) * e.dv)
            val nearB = near.at(b)
            var i = if (nearLeft) r.start else l.end
            while (i in 0 until e.n && abs(i - b) <= cfg.rgbMarginMaxPx) {
                val z = e.d[i]
                val zb = bg.at(i.toFloat())
                if (z <= 0f || z <= nearB || abs(z - zb) <= cfg.edgeFitTolRatio * z || zb <= z) break
                val li = luma(u0 + (i - l.end).toFloat() * e.du, v0 + (i - l.end).toFloat() * e.dv)
                if (li < 0f || abs(li - objLuma) <= cfg.rgbGuideLumaSigma) break
                propose(e.idx[i], zb)
                i += step
            }
        }

        val out = depthMm.copyOf()
        val moved = BooleanArray(out.size)
        for (p in out.indices) if (best[p] != Float.MAX_VALUE) {
            out[p] = min((best[p] * 1000f).toInt(), 65535).toShort()
            moved[p] = true
        }
        return Result(out, moved, mask, nEdges, nFloor, nRgb)
    }

    /**
     * 뒤 면의 배경 직선(Rb): 뒤 면 첫 조각 [first]에서 [step] 방향으로 이어진 표면 조각을 따라가 경계 [b]에서 `rgbMarginMaxPx` 떨어진
     * 위치를 덮는 조각(그 전에 경사·무효로 끊기면 마지막 표면 조각). 당김 구간은 화소당 1~3%로 휘어 짧은 표면 조각 여럿으로 잘린다.
     */
    private fun background(e: EdgeDetector.Edge, first: Int, step: Int, b: Float, cfg: FrontendConfig): EdgeDetector.Piece {
        var j = first
        while (true) {
            val cur = e.pieces[j]
            if (abs((if (step > 0) cur.end else cur.start) - b) >= cfg.rgbMarginMaxPx) return cur
            val nx = j + step
            if (nx !in e.pieces.indices) return cur
            val next = e.pieces[nx]
            val touches = if (step > 0) next.start == cur.end + 1 else next.end == cur.start - 1
            if (!touches || !EdgeDetector.isSurface(next, cfg)) return cur
            j = nx
        }
    }

    /** 깊이 시야 안 RGB 화소(2화소 간격)의 밝기 기울기(가로·세로 이웃 차의 큰 쪽) p90. */
    private fun gradientP90(k: Intrinsics, guide: GuideImage): Float {
        val g = guide.K
        fun gx(u: Float) = (g.fx * (u - k.cx) / k.fx + g.cx).toInt().coerceIn(0, g.width - 2)
        fun gy(v: Float) = (g.fy * (v - k.cy) / k.fy + g.cy).toInt().coerceIn(0, g.height - 2)
        val hist = IntArray(256)
        var n = 0
        for (y in gy(-0.5f)..gy(k.height - 0.5f) step 2) for (x in gx(-0.5f)..gx(k.width - 0.5f) step 2) {
            val c = guide.luma[y * g.width + x].toInt() and 0xFF
            val dx = abs((guide.luma[y * g.width + x + 1].toInt() and 0xFF) - c)
            val dy = abs((guide.luma[(y + 1) * g.width + x].toInt() and 0xFF) - c)
            hist[maxOf(dx, dy)]++
            n++
        }
        var acc = 0
        for (v in 0 until 256) {
            acc += hist[v]
            if (acc >= 0.9 * n) return v.toFloat()
        }
        return 255f
    }
}
