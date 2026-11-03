package hearspace.core.tracking

import hearspace.core.geometry.Vec3
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.random.Random

/** M48 등속 칼만: 합성 측정열(30 Hz, 위치 잡음 σ = 3 cm)로 속도 복원과 움직임 검정을 본다. */
class MotionFilterTest {
    private fun run(vMps: Vec3, seed: Int, n: Int = 45): MotionFilter {
        val f = MotionFilter(accelSigmaMps2 = 1f)
        val rnd = Random(seed)
        fun g() = (rnd.nextDouble() * 2 - 1).toFloat() * 0.03f * 1.732f // 균등 잡음, σ = 3 cm
        for (k in 0 until n) {
            val t = k / 30f
            f.update(1_000_000_000L + k * 33_333_333L, Vec3(1f, 0f, -3f) + vMps * t + Vec3(g(), 0f, g()), 0.03f)
        }
        return f
    }

    @Test
    fun `constant velocity is recovered and flagged as moving`() {
        val f = run(Vec3(0f, 0f, 0.8f), seed = 1)
        val v = f.velocityW!!
        assertEquals(0.8f, v.z, 0.15f)
        assertEquals(0f, v.x, 0.15f)
        assertTrue(f.velocityChi2 > 9.21f, "chi2 ${f.velocityChi2}")
    }

    @Test
    fun `static target rarely passes the 99 percent test`() {
        val passed = (1..200).count { run(Vec3.ZERO, seed = it).velocityChi2 > 9.21f }
        assertTrue(passed <= 10, "static passed $passed / 200") // 기대 약 1%, 상관된 마지막 값이라 넉넉히
    }

    @Test
    fun `two point start gives the difference velocity`() {
        val f = MotionFilter(1f)
        f.update(0L, Vec3(0f, 0f, 0f), 0.01f)
        f.update(100_000_000L, Vec3(0.1f, 0f, -0.05f), 0.01f)
        assertEquals(1.0f, f.velocityW!!.x, 1e-4f)
        assertEquals(-0.5f, f.velocityW!!.z, 1e-4f)
    }
}
