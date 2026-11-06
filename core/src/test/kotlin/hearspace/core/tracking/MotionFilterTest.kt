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

    /** M52 시작 게이트: T 150338처럼 첫 측정이 뒤쪽 물체와 합친 군집(3.40 m)이고 33 ms 뒤 사람(2.35 m)이면 다시 시작해 사람 속도를 읽는다. */
    @Test
    fun `start gate restarts on a 1 m jump and then reads the walking speed`() {
        fun feed(f: MotionFilter): List<Float> {
            val rnd = Random(3)
            fun g() = (rnd.nextDouble() * 2 - 1).toFloat() * 0.03f * 1.732f
            f.update(1_000_000_000L, Vec3(0f, 0f, -3.40f), 0.046f)
            return (1..12).map { k ->
                f.update(1_000_000_000L + k * 33_333_333L, Vec3(g(), 0f, -2.35f + 0.68f * (k - 1) / 30f + g()), 0.046f)
                f.velocityW?.norm() ?: Float.NaN
            }
        }
        val on = MotionFilter(1.13f, initMaxSpeedMps = 2.5f, initGateSigmas = 3f, initUpdates = 6)
        val vOn = feed(on)
        val vOff = feed(MotionFilter(1.13f))
        assertEquals(1, on.restarts)
        // 출력 조건(측정 6개)에 닿는 때: 게이트를 켜면 사람 속도 0.68 m/s 둘레, 끄면 첫 뜀 때문에 훨씬 큼(M48 150338: 17.8 → 출력 때 3.8 m/s)
        assertEquals(0.68f, vOn[6], 0.5f)
        assertTrue(vOff[6] > 2f, "off ${vOff[6]}")
    }

    @Test
    fun `start gate does not restart a person walking at 2 m per s`() {
        val rnd = Random(4)
        fun g() = (rnd.nextDouble() * 2 - 1).toFloat() * 0.03f * 1.732f
        val f = MotionFilter(1.13f, initMaxSpeedMps = 2.5f, initGateSigmas = 3f, initUpdates = 6)
        for (k in 0 until 30) f.update(1_000_000_000L + k * 33_333_333L, Vec3(g(), 0f, -4f + 2f * k / 30f + g()), 0.03f)
        assertEquals(0, f.restarts)
        assertEquals(2f, f.velocityW!!.z, 0.3f)
    }

    @Test
    fun `prediction without update keeps the state and equals the state at its own time`() {
        val f = MotionFilter(1f)
        f.update(0L, Vec3(0f, 0f, 0f), 0.01f)
        f.update(100_000_000L, Vec3(0.1f, 0f, 0f), 0.01f)
        assertEquals(f.positionW, f.predictedAt(100_000_000L)!!.first)
        assertEquals(f.positionSigmaM, f.predictedAt(100_000_000L)!!.second)
        val (p, s) = f.predictedAt(445_000_000L)!!
        assertEquals(0.1f + 1.0f * 0.345f, p.x, 1e-4f)
        assertTrue(s > f.positionSigmaM)
        assertEquals(100_000_000L, f.tNs)
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
