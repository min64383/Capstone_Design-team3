package hearspace.core.frontend

import hearspace.core.types.FrontendConfig
import hearspace.core.types.GuideImage
import hearspace.core.types.Intrinsics
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * RGB 안내 깊이 보정(IMPROVE_SPEC §6.1.1 M13.7). ARCore 일반 깊이는 물체 가장자리를 평활해 앞 물체와 뒤 배경 사이를 중간 깊이로
 * 잇는다(막). 경계 판정([EdgeDetector])은 이 막 픽셀(두 면 사이의 짧고 가파른 경사)을 찾아 지우는데, RGB는 같은 카메라의 선명한 영상이라
 * 막 픽셀이 앞·뒤 중 어느 면에 속하는지 밝기로 정해 **그 면의 깊이로 옮길** 수 있다(물체 윤곽을 지우지 않고 살린다).
 *
 * 막 후보 = 경계 마스크 픽셀. 후보마다 반경 `rgbGuideRadiusPx` 창에서 **후보가 아닌**(면 위의) 유효 픽셀 깊이의 결합 가중 중앙값
 * (가중치 = exp(−밝기 차² / (2 `rgbGuideLumaSigma`²)))으로 바꾼다. 중앙값이라 면 위 깊이 중 하나가 되고 새 중간 깊이를 만들지 않는다
 * (가중 중앙값 Ma 2013, 결합 양방향 필터 Petschnigg 2004·Kopf 2007, 선행 조사 [R5]). 후보가 아닌 픽셀은 그대로다: 처음에 창 안 깊이
 * 사이에 끼인 픽셀을 모두 후보로 했더니 비스듬한 벽에서 이웃 줄 깊이로 바뀌어 점이 면 밖으로 밀렸다(SC-03). 창에 면 위 이웃이
 * 없으면 옮기지 않는다. 무효 깊이는 채우지 않는다. 스레드를 모르고 같은 입력이면 같은 출력이다.
 */
object RgbGuide {

    /** 보정한 깊이와, 후보 중 옮긴 픽셀. */
    class Result(val depthMm: ShortArray, val snapped: BooleanArray)

    /** [depthMm](깊이 K [k])의 막 후보 [candidates](경계 마스크)를 [guide]로 앞·뒤 면 깊이에 붙인다. */
    fun snap(depthMm: ShortArray, k: Intrinsics, guide: GuideImage, cfg: FrontendConfig, candidates: BooleanArray): Result {
        val w = k.width
        val h = k.height
        val lum = sampleLuma(k, guide)
        val r = cfg.rgbGuideRadiusPx
        val inv = 1f / (2f * cfg.rgbGuideLumaSigma * cfg.rgbGuideLumaSigma)
        val out = depthMm.copyOf()
        val snapped = BooleanArray(w * h)
        val n = (2 * r + 1) * (2 * r + 1)
        val ds = IntArray(n)
        val ws = FloatArray(n)
        for (v in 0 until h) for (u in 0 until w) {
            val i = v * w + u
            if (!candidates[i] || depthMm[i].toInt() == 0 || lum[i] < 0) continue
            var m = 0
            var total = 0f
            for (dv in -r..r) for (du in -r..r) {
                val uu = u + du
                val vv = v + dv
                if (uu < 0 || uu >= w || vv < 0 || vv >= h) continue
                val j = vv * w + uu
                if (candidates[j]) continue
                val dd = depthMm[j].toInt() and 0xFFFF
                if (dd == 0 || lum[j] < 0) continue
                val dl = (lum[j] - lum[i]).toFloat()
                ds[m] = dd
                ws[m] = exp(-dl * dl * inv)
                total += ws[m]
                m++
            }
            if (m == 0) continue
            out[i] = weightedMedian(ds, ws, m, total).toShort()
            snapped[i] = true
        }
        return Result(out, snapped)
    }

    /** 깊이 픽셀마다 RGB 밝기(같은 카메라: 깊이 K의 시선 → RGB K의 가장 가까운 픽셀), RGB 밖이면 −1. */
    private fun sampleLuma(k: Intrinsics, guide: GuideImage): IntArray {
        val g = guide.K
        val out = IntArray(k.width * k.height) { -1 }
        for (v in 0 until k.height) for (u in 0 until k.width) {
            val gu = (g.fx * (u - k.cx) / k.fx + g.cx).roundToInt()
            val gv = (g.fy * (v - k.cy) / k.fy + g.cy).roundToInt()
            if (gu in 0 until g.width && gv in 0 until g.height) out[v * k.width + u] = guide.luma[gv * g.width + gu].toInt() and 0xFF
        }
        return out
    }

    /** [ds]·[ws]의 앞 [m]개에서 가중 중앙값(누적 가중치가 [total]의 절반에 처음 닿는 값). 삽입 정렬(창이 작다). */
    private fun weightedMedian(ds: IntArray, ws: FloatArray, m: Int, total: Float): Int {
        for (a in 1 until m) {
            val dk = ds[a]
            val wk = ws[a]
            var b = a - 1
            while (b >= 0 && ds[b] > dk) {
                ds[b + 1] = ds[b]
                ws[b + 1] = ws[b]
                b--
            }
            ds[b + 1] = dk
            ws[b + 1] = wk
        }
        var acc = 0f
        for (a in 0 until m) {
            acc += ws[a]
            if (acc >= total / 2f) return ds[a]
        }
        return ds[m - 1]
    }
}
