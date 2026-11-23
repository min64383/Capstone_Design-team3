package hearspace.core.audio

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * 표본율 바꾸기(M67 설계 3.5절): S10 TTS가 쓴 말 소리(엔진이 정한 표본율)를 중계 서버 표본율로 바꾼다.
 * Hann 창을 씌운 sinc 보간, 원 표본 기준 양쪽 [HALF]표본. 내릴 때는 차단 주파수를 새 나이퀴스트로 낮추고 창을 그만큼 넓힌다.
 * 서버가 시작할 때 한 번만 부르므로 빠르기는 따지지 않는다.
 */
object Resample {
    const val HALF = 16

    fun to(x: FloatArray, fromHz: Int, toHz: Int): FloatArray {
        require(fromHz > 0 && toHz > 0)
        if (fromHz == toHz) return x.copyOf()
        val ratio = toHz.toDouble() / fromHz
        val cut = minOf(1.0, ratio) // 원 표본율의 나이퀴스트를 1로 본 차단
        val half = HALF / cut
        val reach = ceil(half).toInt()
        return FloatArray(floor(x.size * ratio).toInt()) { i ->
            val t = i / ratio
            val c = floor(t).toInt()
            var acc = 0.0
            for (k in maxOf(0, c - reach + 1)..minOf(x.size - 1, c + reach)) {
                val d = t - k
                if (d <= -half || d >= half) continue
                val z = cut * d
                val sinc = if (z == 0.0) 1.0 else sin(PI * z) / (PI * z)
                acc += x[k] * cut * sinc * 0.5 * (1 + cos(PI * d / half))
            }
            acc.toFloat()
        }
    }
}
