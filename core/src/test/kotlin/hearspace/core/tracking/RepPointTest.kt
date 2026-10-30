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
import kotlin.random.Random

/** M21 CORRIDOR_BAND: 거리는 CORRIDOR_NEAREST와 같고, 좌우는 띠 안에서 보행선에 가장 가까운 칸. 칸 0.05 m 격자의 합성 군집. */
class RepPointTest {

    private val config: Config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())

    // 원점에서 −z로 걷는다: 진행 방향 거리 a → z = −a, 오른쪽 = +x
    private val corridor = Corridor(Vec3.ZERO, Vec3(0f, 0f, -1f), 0f, config.corridor)
    private fun p(alongM: Float, lateralM: Float, yM: Float = 0.3f) = Vec3(lateralM, yM, -alongM)

    private fun reps(pts: List<Vec3>) = RepPoint.candidates(pts, corridor, config.map.voxelSizeM, config.repPoint.bandM, stableTies = true)

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

    @Test
    fun `same face in a different voxel order keeps surface representatives`() {
        // 같은 |좌우|에 앞뒤 두 줄·높이 두 층: HashMap 순서를 흉내 내어 순서만 바꾼다.
        val points = listOf(1f, 1.03125f).flatMap { a ->
            listOf(-0.125f, 0.125f).flatMap { l -> listOf(0.25f, 0.75f).map { y -> p(a, l, y) } }
        }
        val expected = reps(points)
        repeat(30) { seed ->
            val actual = reps(points.shuffled(Random(seed)))
            for (strategy in RepStrategy.entries.filter { it != RepStrategy.CENTROID }) {
                assertEquals(expected.getValue(strategy), actual.getValue(strategy), "$strategy seed=$seed")
            }
        }
    }

    @Test
    fun `equal lateral candidates keep the front row even if the rear row arrives first`() {
        val points = listOf(p(1.03125f, 0.125f, 0.75f), p(1f, 0.125f, 0.25f))
        val actual = reps(points)
        for (strategy in listOf(RepStrategy.CORRIDOR_NEAREST, RepStrategy.CORRIDOR_BAND)) {
            assertEquals(p(1f, 0.125f, 0.25f), actual.getValue(strategy), strategy.name)
        }
    }

    @Test
    fun `outside corridor nearest ties do not depend on point order`() {
        val points = listOf(p(1f, -0.625f), p(1f, 0.625f))
        for (strategy in listOf(RepStrategy.NEAREST, RepStrategy.CORRIDOR_NEAREST, RepStrategy.CORRIDOR_BAND)) {
            assertEquals(reps(points).getValue(strategy), reps(points.reversed()).getValue(strategy), strategy.name)
        }
    }

    @Test
    fun `tie resolution uses the walking frame after a right angle turn`() {
        // 월드 원점·축에 가까운 점을 우선하지 않는다: 같은 합성 면을 90도 회전하고 평행 이동한다.
        val points = listOf(1f, 1.03125f).flatMap { a ->
            listOf(-0.125f, 0.125f).map { l -> p(a, l, 0.25f) }
        }
        fun turn(v: Vec3) = Vec3(-v.z, v.y, v.x)
        val offset = Vec3(2f, 0f, 3f)
        val turned = Corridor(offset, turn(corridor.headingW), 0f, config.corridor)
        val expected = reps(points)
        val actual = RepPoint.candidates(points.reversed().map { turn(it) + offset }, turned, config.map.voxelSizeM, config.repPoint.bandM, stableTies = true)
        for (strategy in RepStrategy.entries.filter { it != RepStrategy.CENTROID }) {
            assertEquals(turn(expected.getValue(strategy)) + offset, actual.getValue(strategy), strategy.name)
        }
    }

    @Test
    fun `disabled option retains the legacy first equal candidate`() {
        val first = p(1.03125f, 0.125f, 0.75f)
        val r = RepPoint.candidates(listOf(first, p(1f, 0.125f, 0.25f)), corridor, config.map.voxelSizeM, config.repPoint.bandM)
        assertEquals(first, r.getValue(RepStrategy.CORRIDOR_NEAREST))
        assertEquals(first, r.getValue(RepStrategy.CORRIDOR_BAND))
    }
}
