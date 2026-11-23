package hearspace.core.audio

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 표본율 바꾸기(M67): 합성 사인파로 주파수·크기가 그대로이고 거울 성분이 작은지 본다. */
class ResampleTest {
    private fun sine(hz: Double, rate: Int, n: Int, amp: Double = 0.5) = FloatArray(n) { (amp * sin(2 * PI * hz * it / rate)).toFloat() }

    /** [x]의 [from, until) 구간에서 [hz] 성분의 크기(사인·코사인에 투영). */
    private fun amplitude(x: FloatArray, hz: Double, rate: Int, from: Int, until: Int): Double {
        var s = 0.0
        var c = 0.0
        for (i in from until until) {
            s += x[i] * sin(2 * PI * hz * i / rate)
            c += x[i] * cos(2 * PI * hz * i / rate)
        }
        return 2 * hypot(s, c) / (until - from)
    }

    @Test
    fun `24 kHz 1 kHz 사인파를 48 kHz로 바꾸면 같은 사인파가 된다`() {
        val out = Resample.to(sine(1000.0, 24_000, 12_000), 24_000, 48_000)
        assertEquals(24_000, out.size)
        val want = sine(1000.0, 48_000, out.size)
        val edge = 4 * Resample.HALF
        val err = (edge until out.size - edge).maxOf { abs(out[it] - want[it]) }
        assertTrue(err < 0.005, "max error $err")
    }

    @Test
    fun `올릴 때 원 나이퀴스트 위의 거울 성분이 1 % 아래다`() {
        // 22.05 kHz의 3 kHz는 48 kHz로 올릴 때 22.05 − 3 = 19.05 kHz에 거울이 생길 수 있다
        val out = Resample.to(sine(3000.0, 22_050, 22_050), 22_050, 48_000)
        val from = 4 * Resample.HALF
        val until = out.size - from
        val main = amplitude(out, 3000.0, 48_000, from, until)
        val image = amplitude(out, 19_050.0, 48_000, from, until)
        assertEquals(0.5, main, 0.005)
        assertTrue(image / main < 0.01, "image ratio ${image / main}")
    }

    @Test
    fun `내릴 때 새 나이퀴스트 위 성분을 줄이고 같은 표본율이면 그대로다`() {
        val x = sine(20_000.0, 48_000, 4800) // 24 kHz로 내리면 나이퀴스트 12 kHz 위
        val out = Resample.to(x, 48_000, 24_000)
        assertEquals(2400, out.size)
        val from = 4 * Resample.HALF
        assertTrue(out.drop(from).dropLast(from).maxOf { abs(it) } < 0.02)
        val same = Resample.to(x, 48_000, 48_000)
        assertTrue(same.contentEquals(x) && same !== x)
    }
}
