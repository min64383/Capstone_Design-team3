package hearspace.core.truth

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.CellView
import hearspace.core.types.CorridorConfig
import hearspace.core.types.HeightClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 군집 칸 단위 평가(M23): 알려진 답. */
class ClusterEvalTest {
    private val v = 0.05f
    private val corridor = CorridorConfig(0.8f, 2.0f, 3.5f, 0.2f, 0.25f, 0.8f, 0f, false)
    private val truth = GroundTruth(
        2, "S02", false, "",
        listOf(TruthObstacle("suitcase", HeightClass.FLOOR, TruthKind.OBJECT, Vec3(-0.15f, 0f, 2.0f), Vec3(0.15f, 0.6f, 2.3f), null)),
    )

    /** x·y·z 칸 범위(칸 번호, 끝 제외)를 채운 군집. 상태는 [state]. */
    private fun box(x: IntRange, y: IntRange, z: IntRange, state: CellView = CellView.HIT): ClusterEval.Cells {
        val c = ArrayList<Vec3>()
        for (i in x) for (j in y) for (k in z) c += Vec3(i * v + v / 2, j * v + v / 2, k * v + v / 2)
        return ClusterEval.Cells(c, ByteArray(c.size) { state.ordinal.toByte() })
    }

    private fun eval(vararg c: ClusterEval.Cells) = ClusterEval.evaluate(Vec3(0f, 1.5f, 0f), c.toList(), truth, corridor, v).single()

    @Test
    fun `exact cluster fits and has zero face offsets`() {
        val r = eval(box(-3 until 3, 0 until 12, 40 until 46))
        assertEquals(1, r.nAttached)
        assertTrue(r.fits)
        listOf(r.frontM, r.backM, r.leftM, r.rightM, r.topM).forEach { assertEquals(0f, it, 1e-5f) }
        assertEquals(1f, r.purity, 1e-6f)
        assertEquals(2.0f, r.alongM, 1e-5f)
    }

    @Test
    fun `back stretch is measured and the cells beyond tolerance are counted by state`() {
        val body = box(-3 until 3, 0 until 12, 40 until 46)
        val tail = box(-3 until 3, 0 until 12, 46 until 50, CellView.OUT_OF_VIEW) // 0.2 m 뒤로 늘어남: 뒤 0.10 m 넘는 두 줄이 밖
        val r = eval(ClusterEval.Cells(body.centers + tail.centers, body.states + tail.states))
        assertEquals(0.2f, r.backM, 1e-5f)
        assertFalse(r.fits)
        assertEquals(6 * 12 * 2, r.outside[CellView.OUT_OF_VIEW.ordinal])
        assertEquals(0, r.outside[CellView.HIT.ordinal])
    }

    @Test
    fun `two pieces are a split, a far cluster is not attached, nothing is a miss`() {
        val front = box(-3 until 3, 0 until 12, 40 until 42)
        val back = box(-3 until 3, 0 until 12, 44 until 46)
        val wall = box(-8 until 8, 0 until 30, 70 until 72)
        val r = eval(front, back, wall)
        assertEquals(2, r.nAttached)
        assertEquals(0, eval(wall).nAttached)
        assertFalse(eval(wall).fits)
    }

    @Test
    fun `truth box is clipped to the corridor at the head`() {
        val wide = GroundTruth(2, "S", false, "", listOf(TruthObstacle("bench", HeightClass.FLOOR, TruthKind.OBJECT, Vec3(-1f, 0f, 2f), Vec3(1f, 0.4f, 2.3f), null)))
        val r = ClusterEval.evaluate(Vec3(0f, 1.5f, 0f), listOf(box(-8 until 8, 0 until 8, 40 until 46)), wide, corridor, v).single()
        assertTrue(r.fits) // 통로 ±0.4 m 안 부분만 정답
    }
}
