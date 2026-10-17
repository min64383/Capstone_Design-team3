package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.exp

/**
 * TRISTATE M33 한 장 갱신(`map.triSameFrame`, 설계 3.6절)이 점유 격자 모형의 예측대로 움직이는지. 장면 단위 예측(정답 깊이에서 표면을
 * 지우지 않음)은 [hearspace.core.synth.TriFrameSceneTest].
 */
class TriFrameTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private fun map(extra: String = "") = VoxelMap(
        ConfigLoader.load(base, """{ "map": { "mode": "TRISTATE", "triSameFrame": true $extra } }""").map,
    )

    private val cam = Vec3(0.01f, 1.01f, 0.01f)
    private fun pts(vararg p: Vec3) = FloatArray(p.size * 3) { i -> p[i / 3].let { v -> if (i % 3 == 0) v.x else if (i % 3 == 1) v.y else v.z } }
    private fun VoxelMap.at(p: Vec3) = views().single { abs(it.centerW.x - (index(p.x) + 0.5f) * 0.05f) < 1e-4f && abs(it.centerW.y - (index(p.y) + 0.5f) * 0.05f) < 1e-4f && abs(it.centerW.z - (index(p.z) + 0.5f) * 0.05f) < 1e-4f }

    @Test
    fun `a cell hit in the frame gets no free evidence from another ray through it`() {
        val m = map()
        val a = Vec3(0.01f, 1.01f, -2.01f)
        val b = Vec3(0.01f, 1.01f, -4.01f) // 같은 시선 위 더 먼 점: 광선이 a 칸을 지난다
        m.beginFrame()
        m.insertTriFrame(pts(a, b), floatArrayOf(1f, 1f), floatArrayOf(0.05f, 0.05f), cam, 1L, null)
        assertEquals(0f, m.at(a).free)
        assertEquals(1f, m.at(a).evidence)
    }

    @Test
    fun `the map does not depend on the pixel order`() {
        val p = listOf(Vec3(0.01f, 1.01f, -2.01f), Vec3(0.03f, 1.02f, -4.01f), Vec3(0.2f, 0.9f, -3.0f), Vec3(-0.1f, 1.2f, -2.6f))
        val w = floatArrayOf(1f, 0.3f, 0.6f, 0.8f)
        fun run(order: List<Int>): Set<Triple<Vec3, Float, Float>> {
            val m = map()
            m.beginFrame()
            m.insertTriFrame(pts(*order.map { p[it] }.toTypedArray()), FloatArray(order.size) { w[order[it]] }, FloatArray(order.size) { 0.05f }, cam, 1L, null)
            return m.views().map { Triple(it.centerW, it.evidence, it.free) }.toSet()
        }
        assertEquals(run(listOf(0, 1, 2, 3)), run(listOf(3, 2, 1, 0)))
    }

    @Test
    fun `evidence is forgotten with T only when the cell receives new evidence`() {
        val m = map(""", "triDecayS": 2.0""")
        val a = Vec3(0.01f, 1.01f, -2.01f)
        m.beginFrame()
        m.insertTriFrame(pts(a), floatArrayOf(1f), floatArrayOf(0.05f), cam, 0L, null)
        // 4 s 동안 다른 쪽만 본다(a는 광선이 닿지 않는다): 증거 그대로
        m.beginFrame()
        m.insertTriFrame(pts(Vec3(2.01f, 1.01f, 0.01f)), floatArrayOf(1f), floatArrayOf(0.05f), cam, 4_000_000_000L, null)
        assertEquals(1f, m.at(a).evidence)
        // 다시 맞으면 그 전에 4 s만큼 잊는다: 1·e^(−2) + 1
        m.beginFrame()
        m.insertTriFrame(pts(a), floatArrayOf(1f), floatArrayOf(0.05f), cam, 4_000_000_000L + 1, null)
        assertEquals(1f + exp(-2.0).toFloat(), m.at(a).evidence, 1e-4f)
    }

    @Test
    fun `the clamp comparison caps hit and free evidence`() {
        val m = map(""", "triClamp": 2.5""")
        val a = Vec3(0.01f, 1.01f, -2.01f)
        for (t in 1L..5L) {
            m.beginFrame()
            m.insertTriFrame(pts(a), floatArrayOf(1f), floatArrayOf(0.05f), cam, t, null)
        }
        assertEquals(2.5f, m.at(a).evidence)
    }

    @Test
    fun `cells behind the user stay when passed pruning is off`() {
        val a = Vec3(0.01f, 1.01f, 2.01f) // 사용자(원점) 뒤 2 m, 진행 방향 −z
        for ((prune, kept) in listOf(true to 0, false to 1)) {
            val m = map(""", "prunePassed": $prune""")
            m.beginFrame()
            m.insertTriFrame(pts(a), floatArrayOf(1f), floatArrayOf(0.05f), cam, 1L, null)
            m.prune(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f), 2L)
            assertEquals(kept, m.occupied().size, "prunePassed $prune")
        }
    }
}
