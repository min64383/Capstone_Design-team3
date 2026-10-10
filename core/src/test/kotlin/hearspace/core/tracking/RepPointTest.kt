package hearspace.core.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import hearspace.core.geometry.Vec3
import hearspace.core.guidance.Corridor
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader
import hearspace.core.types.RepStrategy
import java.io.File
import kotlin.math.abs

/** M21 CORRIDOR_BAND: 거리는 CORRIDOR_NEAREST와 같고, 좌우는 띠 안에서 보행선에 가장 가까운 칸. 칸 0.05 m 격자의 합성 군집. */
class RepPointTest {

    private val config: Config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())

    // 원점에서 −z로 걷는다: 진행 방향 거리 a → z = −a, 오른쪽 = +x
    private val corridor = Corridor(Vec3.ZERO, Vec3(0f, 0f, -1f), 0f, config.corridor)
    private fun p(alongM: Float, lateralM: Float, yM: Float = 0.3f) = Vec3(lateralM, yM, -alongM)

    private fun reps(pts: List<Vec3>) = RepPoint.candidates(pts, corridor, config.map.voxelSizeM, config.repPoint.bandM)

    @Test
    fun `tail cell in front of a face across the walk line - band points at the face, same distance`() {
        // 앞면 두 줄(1.00·1.05 m) × 높이 세 층, 좌우 −0.075~+0.275 + 0.10 m 앞 오른쪽 0.15 m의 꼬리 칸
        val lats = listOf(-0.075f, -0.025f, 0.025f, 0.075f, 0.125f, 0.175f, 0.225f, 0.275f)
        val pts = listOf(1.00f, 1.05f).flatMap { a -> lats.flatMap { l -> listOf(0.15f, 0.3f, 0.45f).map { y -> p(a, l, y) } } } + p(0.90f, 0.15f)
        val r = reps(pts)
        val nearest = r.getValue(RepStrategy.CORRIDOR_NEAREST)
        val band = r.getValue(RepStrategy.CORRIDOR_BAND)
        assertEquals(0.15f, corridor.lateralM(nearest), 1e-4f) // 지금 규칙: 앞줄의 꼬리 칸
        assertTrue(abs(corridor.lateralM(band)) <= 0.025f + 1e-4f, "band $band")
        assertEquals(corridor.alongM(nearest), corridor.alongM(band), 1e-4f) // 거리는 그대로
    }

    @Test
    fun `only the two side walls in the band - band takes a wall cell, not the empty middle`() {
        // 통로 양옆 벽(좌우 ±0.375)이 0.5~2.0 m에 이어지고 끝벽은 2.0 m: 띠(0.5~0.7 m)에는 옆벽 칸만 있다
        val walls = (0..30).flatMap { k -> listOf(-0.375f, 0.375f).map { l -> p(0.5f + 0.05f * k, l) } }
        val end = (-7..7).map { k -> p(2.0f, 0.05f * k) }
        val band = reps(walls + end).getValue(RepStrategy.CORRIDOR_BAND)
        assertEquals(0.375f, abs(corridor.lateralM(band)), 1e-4f)
        assertEquals(0.5f, corridor.alongM(band), 1e-4f)
    }

    @Test
    fun `no cell inside the corridor - band equals nearest`() {
        val r = reps(listOf(p(1.0f, 0.6f), p(1.2f, 0.7f)))
        assertEquals(r.getValue(RepStrategy.NEAREST), r.getValue(RepStrategy.CORRIDOR_BAND))
    }
}
