package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** 시점 묶음 규칙 RV(M39 설계 3.2절, `map.triViewRho`)가 등상관 관측 결합의 예측대로 움직이는지. */
class TriViewTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private fun map(rho: Float) = VoxelMap(
        ConfigLoader.load(base, """{ "map": { "mode": "TRISTATE", "triSameFrame": true, "triViewRho": $rho } }""").map,
    )

    /** 칸 중심(칸 0.05 m). */
    private val a = Vec3(0.025f, 1.025f, -1.975f)
    private val front = Vec3(0f, 0f, 1f)
    private fun pts(vararg p: Vec3) = FloatArray(p.size * 3) { i -> p[i / 3].let { v -> if (i % 3 == 0) v.x else if (i % 3 == 1) v.y else v.z } }
    private fun VoxelMap.at(p: Vec3) = views().single { abs(it.centerW.x - p.x) < 1e-4f && abs(it.centerW.y - p.y) < 1e-4f && abs(it.centerW.z - p.z) < 1e-4f }

    /** 칸 [a]를 거리 [r]·방향 [dir](칸 → 카메라)에서 한 장 맞힌다. */
    private fun VoxelMap.hitA(r: Float, dir: Vec3 = front, t: Long = 0L) {
        beginFrame()
        insertTriFrame(pts(a), floatArrayOf(1f), floatArrayOf(0.05f), a + dir * r, t, null)
    }

    /** 방향 [dir]에서 칸 [a]를 지나 1 m 뒤를 맞힌다: [a]는 빈 증거 1을 받는다. */
    private fun VoxelMap.seeThroughA(dir: Vec3) {
        beginFrame()
        insertTriFrame(pts(a - dir), floatArrayOf(1f), floatArrayOf(0.05f), a + dir * 2f, 0L, null)
    }

    @Test
    fun `n frames from one view count as n over 1 + (n - 1) rho`() {
        val m = map(0.5f)
        repeat(5) { m.hitA(2f) }
        assertEquals(5f / (1f + 4 * 0.5f), m.at(a).evidence, 1e-5f)
    }

    @Test
    fun `groups from views far enough apart in range add independently`() {
        val m = map(0.5f)
        repeat(3) { m.hitA(2f) }
        repeat(3) { m.hitA(1f) } // |ln(1/2)| = 0.69 ≥ β 0.25: 새 묶음
        assertEquals(2 * 3f / (1f + 2 * 0.5f), m.at(a).evidence, 1e-5f)
    }

    @Test
    fun `rho 0 counts every frame as RM does`() {
        val m = map(0f)
        repeat(5) { m.hitA(2f) }
        assertEquals(5f, m.at(a).evidence)
    }

    @Test
    fun `a ghost cell hit long from one view is cleared by independent views in fewer frames than RM`() {
        fun framesToClear(rho: Float): Int {
            val m = map(rho)
            repeat(20) { m.hitA(2f) }
            var k = 0
            while (m.at(a).let { it.free <= it.evidence }) {
                val th = Math.toRadians(50.0 * (1 + k / 2)) // 두 장마다 시선을 50°(≥ α 40°) 돌린다
                m.seeThroughA(Vec3(sin(th).toFloat(), 0f, cos(th).toFloat()))
                k++
            }
            return k
        }
        val rm = framesToClear(0f)
        val rv = framesToClear(0.5f)
        assertEquals(21, rm)
        assertTrue(rv < rm, "RV $rv, RM $rm")
    }

    @Test
    fun `a cell out of view keeps its evidence and its group`() {
        val m = map(0.5f)
        m.hitA(2f)
        repeat(10) {
            m.beginFrame()
            m.insertTriFrame(pts(Vec3(2.025f, 1.025f, 0.025f)), floatArrayOf(1f), floatArrayOf(0.05f), a + front * 2f, 0L, null)
        }
        assertEquals(1f, m.at(a).evidence)
        assertEquals(0f, m.at(a).free)
        m.hitA(2f) // 같은 묶음의 두 번째 장: 2 / 1.5
        assertEquals(2f / 1.5f, m.at(a).evidence, 1e-5f)
    }
}
