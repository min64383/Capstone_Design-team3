package hearspace.core.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import hearspace.core.geometry.Vec3
import hearspace.core.guidance.Corridor
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader
import hearspace.core.types.RepStrategy
import java.io.File

/** M21 CORRIDOR_BAND: 거리는 통로 안 가장 앞 칸, 좌우는 띠 칸 좌우 분위수 범위에 0을 가둠. 칸 0.05 m 격자의 합성 군집. */
class RepPointTest {

    private val config: Config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())

    // 원점에서 −z로 걷는다: 진행 방향 거리 a → z = −a, 오른쪽 = +x
    private val corridor = Corridor(Vec3.ZERO, Vec3(0f, 0f, -1f), 0f, config.corridor)
    private fun p(alongM: Float, lateralM: Float, yM: Float = 0.3f) = Vec3(lateralM, yM, -alongM)

    /** 앞면 두 줄(진행 방향 1.00·1.05 m) × 높이 세 층, 좌우 [lats]. */
    private fun face(lats: List<Float>) = listOf(1.00f, 1.05f).flatMap { a -> lats.flatMap { l -> listOf(0.15f, 0.3f, 0.45f).map { y -> p(a, l, y) } } }

    private fun reps(pts: List<Vec3>, bandQ: Float) =
        RepPoint.candidates(pts, corridor, config.map.voxelSizeM, config.repPoint.bandM, bandQ)

    @Test
    fun `tail cell in front of a face across the walk line - band points straight ahead at the same distance`() {
        val pts = face(listOf(-0.075f, -0.025f, 0.025f, 0.075f, 0.125f, 0.175f, 0.225f, 0.275f)) + p(0.90f, 0.15f)
        val r = reps(pts, config.repPoint.bandQ)
        val nearest = r.getValue(RepStrategy.CORRIDOR_NEAREST)
        val band = r.getValue(RepStrategy.CORRIDOR_BAND)
        assertEquals(0.15f, corridor.lateralM(nearest), 1e-4f) // 지금 규칙: 앞줄의 꼬리 칸
        assertEquals(0f, corridor.lateralM(band), 1e-4f)
        assertEquals(corridor.alongM(nearest), corridor.alongM(band), 1e-4f) // 거리는 그대로
    }

    @Test
    fun `box off the walk line with one inward spill cell - quantile ignores it, q 0 does not`() {
        val pts = face(listOf(0.225f, 0.275f, 0.325f, 0.375f)) + p(1.00f, 0.05f)
        assertEquals(0.05f, corridor.lateralM(reps(pts, 0f).getValue(RepStrategy.CORRIDOR_BAND)), 1e-4f)
        assertEquals(0.225f, corridor.lateralM(reps(pts, config.repPoint.bandQ).getValue(RepStrategy.CORRIDOR_BAND)), 1e-4f)
    }

    @Test
    fun `no cell inside the corridor - band equals nearest`() {
        val pts = listOf(p(1.0f, 0.6f), p(1.2f, 0.7f))
        val r = reps(pts, config.repPoint.bandQ)
        assertEquals(r.getValue(RepStrategy.NEAREST), r.getValue(RepStrategy.CORRIDOR_BAND))
    }
}
