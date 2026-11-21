package hearspace.core.situation

import kotlin.math.ceil

/**
 * 노면 모델의 앞처리·뒤처리(M65 설계 3.3절). PC `tools/surface/infer_sessions.py`와 같은 단계를 Pillow와 같은 계산으로 한다.
 * - 앞: 센서 방향 RGB([srcW]×[srcH]) → 시계 방향 90°(세로로 세움) → 위 [cropTop] 몫 버림 → Pillow 쌍선형으로 [inW]×[inH]
 *   (줄이는 비율만큼 넓힌 삼각 필터, 22비트 정수 계수, 가로 다음 세로, 중간 8비트) → ImageNet 정규화 + 0인 넷째 채널(NHWC).
 * - 뒤: 화소마다 가장 큰 로짓의 클래스 → 세로 영상(폭 [outH], 높이 [outW])의 위 [cropTop] 아래에 Pillow 최근접으로 넣음 →
 *   반시계 90°(센서 방향 [outW]×[outH], 계산 안 한 곳 [SurfaceClass.UNKNOWN_PIXEL]).
 * 작업 버퍼는 만들 때 한 번 잡는다(한 스레드 전용).
 */
class SurfaceIO(
    val srcW: Int, val srcH: Int, val cropTop: Float,
    val inW: Int, val inH: Int, val outW: Int, val outH: Int,
    val classes: Int = SurfaceClass.COUNT,
) {
    private val portraitW = srcH
    private val top = (srcW * cropTop.toDouble()).toInt()
    private val cropH = srcW - top
    private val hx = Coeffs(portraitW, inW)
    private val vy = Coeffs(cropH, inH)
    private val tmp = ByteArray(cropH * inW * 3)
    /** 앞처리한 8비트 RGB(세로, [inW]×[inH]). 시험·확인용. */
    val resized = ByteArray(inH * inW * 3)
    private val portraitLabels = ByteArray(inW * inH)

    /** 바닥에 놓을 세로 영상 아래 몫의 시작 행(Python `round(ph * CROP_TOP)`, 짝수 반올림). */
    private val outTop = Math.rint(outW * cropTop.toDouble()).toInt()
    private val placed = ByteArray(outH * (outW - outTop))

    /** 센서 방향 [rgb](행 우선 RGB)를 모델 입력 [input](1×[inH]×[inW]×4, NHWC)에 쓴다. */
    fun preprocess(rgb: ByteArray, input: FloatArray) {
        require(rgb.size >= srcW * srcH * 3 && input.size >= inH * inW * 4)
        // 가로: 자른 세로 영상의 행 cy(세로 y = top + cy = 센서 x), 열 px(센서 y = srcH − 1 − px)
        for (cy in 0 until cropH) {
            val sx = top + cy
            for (x in 0 until inW) {
                var s0 = HALF; var s1 = HALF; var s2 = HALF
                val k0 = x * hx.ksize
                for (j in 0 until hx.count[x]) {
                    val px = hx.start[x] + j
                    val i = ((srcH - 1 - px) * srcW + sx) * 3
                    val k = hx.kk[k0 + j]
                    s0 += (rgb[i].toInt() and 0xFF) * k
                    s1 += (rgb[i + 1].toInt() and 0xFF) * k
                    s2 += (rgb[i + 2].toInt() and 0xFF) * k
                }
                val o = (cy * inW + x) * 3
                tmp[o] = clip8(s0); tmp[o + 1] = clip8(s1); tmp[o + 2] = clip8(s2)
            }
        }
        // 세로
        for (y in 0 until inH) {
            val k0 = y * vy.ksize
            for (x in 0 until inW) {
                var s0 = HALF; var s1 = HALF; var s2 = HALF
                for (j in 0 until vy.count[y]) {
                    val i = ((vy.start[y] + j) * inW + x) * 3
                    val k = vy.kk[k0 + j]
                    s0 += (tmp[i].toInt() and 0xFF) * k
                    s1 += (tmp[i + 1].toInt() and 0xFF) * k
                    s2 += (tmp[i + 2].toInt() and 0xFF) * k
                }
                val o = (y * inW + x) * 3
                resized[o] = clip8(s0); resized[o + 1] = clip8(s1); resized[o + 2] = clip8(s2)
            }
        }
        for (p in 0 until inW * inH) {
            for (c in 0 until 3) input[p * 4 + c] = ((resized[p * 3 + c].toInt() and 0xFF) / 255f - MEAN[c]) / STD[c]
            input[p * 4 + 3] = 0f
        }
    }

    /** 모델 로짓 [logits](1×[inH]×[inW]×[classes], NHWC)를 센서 방향 노면 영상 [out]([outW]×[outH], 행 우선)으로. */
    fun postprocess(logits: FloatArray, out: ByteArray) {
        require(logits.size >= inW * inH * classes && out.size >= outW * outH)
        for (p in 0 until inW * inH) {
            var best = 0
            var bestV = logits[p * classes]
            for (c in 1 until classes) {
                val v = logits[p * classes + c]
                if (v > bestV) { bestV = v; best = c }
            }
            portraitLabels[p] = best.toByte()
        }
        placeAndRotate(portraitLabels, out)
    }

    /** 세로 라벨([inW]×[inH])을 센서 방향 노면 영상으로(뒤처리의 둘째 단계, 시험용으로 따로 연다). */
    fun placeAndRotate(labels: ByteArray, out: ByteArray) {
        // Pillow 최근접 크기 바꾸기 = 아핀 변환: 출력 화소 중심을 입력으로 옮겨 내림, 좌표는 더해 가며 센다(같은 부동소수 누적)
        val tw = outH
        val th = outW - outTop
        val a0 = inW.toDouble() / tw
        val a4 = inH.toDouble() / th
        var yy = a4 * 0.5
        for (y in 0 until th) {
            var xx = a0 * 0.5
            val yin = yy.toInt()
            for (x in 0 until tw) {
                placed[y * tw + x] = labels[yin * inW + xx.toInt()]
                xx += a0
            }
            yy += a4
        }
        // 반시계 90°: 센서 (x', y') = 세로 (x = outH − 1 − y', y = x')
        for (yp in 0 until outH) for (xp in 0 until outW) {
            out[yp * outW + xp] = if (xp < outTop) UNKNOWN else placed[(xp - outTop) * tw + (outH - 1 - yp)]
        }
    }

    /** Pillow `precompute_coeffs` + `normalize_coeffs_8bpc`(쌍선형, support 1). */
    private class Coeffs(inSize: Int, outSize: Int) {
        val ksize: Int
        val start = IntArray(outSize)
        val count = IntArray(outSize)
        val kk: IntArray

        init {
            val scale = inSize.toDouble() / outSize
            val filterscale = maxOf(scale, 1.0)
            val support = 1.0 * filterscale
            ksize = ceil(support).toInt() * 2 + 1
            kk = IntArray(outSize * ksize)
            val k = DoubleArray(ksize)
            for (xx in 0 until outSize) {
                val center = (xx + 0.5) * scale
                val ss = 1.0 / filterscale
                val xmin = maxOf((center - support + 0.5).toInt(), 0)
                val xmax = minOf((center + support + 0.5).toInt(), inSize) - xmin
                var ww = 0.0
                for (x in 0 until xmax) {
                    val t = kotlin.math.abs((x + xmin - center + 0.5) * ss)
                    k[x] = if (t < 1.0) 1.0 - t else 0.0
                    ww += k[x]
                }
                for (x in 0 until xmax) {
                    if (ww != 0.0) k[x] /= ww
                    val v = k[x] * (1 shl PRECISION_BITS)
                    kk[xx * ksize + x] = if (k[x] < 0) (-0.5 + v).toInt() else (0.5 + v).toInt()
                }
                start[xx] = xmin
                count[xx] = xmax
            }
        }
    }

    companion object {
        /** ImageNet 평균·표준편차(학습한 가중치의 정의, 설정 아님: tools/surface/train.py와 같은 float32 값). */
        val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val PRECISION_BITS = 32 - 8 - 2
        private const val HALF = 1 shl (PRECISION_BITS - 1)
        private val UNKNOWN = SurfaceClass.UNKNOWN_PIXEL.toByte()

        private fun clip8(v: Int): Byte = (v shr PRECISION_BITS).coerceIn(0, 255).toByte()

        /**
         * NV21(Y 평면 + V·U 교차 반 해상도)을 센서 방향 RGB로(JFIF BT.601 전범위식, 녹화 JPEG와 같은 식, 색차는 2×2 같은 값).
         * 앱 CPU 영상은 압축 전 원본이라 PC의 JPEG 영상과 화소가 같지는 않다(차이는 M65 E2에서 잰다).
         */
        fun nv21ToRgb(nv21: ByteArray, w: Int, h: Int, rgb: ByteArray) {
            require(nv21.size >= w * h * 3 / 2 && rgb.size >= w * h * 3)
            val uv0 = w * h
            for (y in 0 until h) {
                val uvRow = uv0 + (y shr 1) * w
                for (x in 0 until w) {
                    val yv = (nv21[y * w + x].toInt() and 0xFF).toFloat()
                    val i = uvRow + (x and 1.inv())
                    val cr = (nv21[i].toInt() and 0xFF) - 128f
                    val cb = (nv21[i + 1].toInt() and 0xFF) - 128f
                    val o = (y * w + x) * 3
                    rgb[o] = clamp8(yv + 1.402f * cr)
                    rgb[o + 1] = clamp8(yv - 0.344136f * cb - 0.714136f * cr)
                    rgb[o + 2] = clamp8(yv + 1.772f * cb)
                }
            }
        }

        private fun clamp8(v: Float): Byte = Math.round(v).coerceIn(0, 255).toByte()
    }
}
