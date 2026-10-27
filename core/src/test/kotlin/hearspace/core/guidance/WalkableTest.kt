package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.VoxelView
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M44 걸을 수 있는 영역 판정(`corridor.judge` WALKABLE): 벽만 있는 복도는 진행 방향이 틀려도 넘기지 않고, 길을 막거나 몸 폭보다 좁히는 물체는 넘긴다.
 * 월드: 사용자 머리 (0, 1.5, 0), 복도 축 −Z, 바닥 y = 0, 칸 5 cm.
 */
class WalkableTest {
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(), """{ "corridor": { "judge": "WALKABLE" } }""")
    private val v = 0.05f

    private fun cell(x: Float, y: Float, z: Float) = VoxelView(
        kotlin.math.floor(x / v).toInt(), kotlin.math.floor(y / v).toInt(), kotlin.math.floor(z / v).toInt(), Vec3(x, y, z), 3, 1f, 0L, 0f,
    )

    /** x = [xs] 위치의 벽(z 0 ~ −4 m, 높이 0.1 ~ 1.9 m). */
    private fun wall(x: Float) = (0 until 80).flatMap { k -> (2..38).map { j -> cell(x, j * v, -(0.025f + k * v)) } }

    /** 상자: x [x0, x1], z [z0, z1](음수), 높이 0.6 m. */
    private fun box(x0: Float, x1: Float, z0: Float, z1: Float): List<VoxelView> {
        val out = ArrayList<VoxelView>()
        var x = x0 + v / 2
        while (x < x1) {
            var z = z1 + v / 2
            while (z < z0) {
                for (j in 1..12) out += cell(x, j * v, z)
                z += v
            }
            x += v
        }
        return out
    }

    private fun corridor(headingDeg: Double): Corridor {
        val a = Math.toRadians(headingDeg)
        return Corridor(Vec3(0f, 1.5f, 0f), Vec3(kotlin.math.sin(a).toFloat(), 0f, -kotlin.math.cos(a).toFloat()), 0f, config.corridor)
    }

    private val walls = wall(-0.545f) + wall(0.545f) // 폭 1.04 m 복도(벽 면 ±0.52)

    @Test
    fun `walls only - nothing in path within warn range for heading errors up to 7 deg`() {
        for (deg in listOf(0.0, 3.0, -5.0, 7.0)) {
            val c = corridor(deg)
            val inPath = walkableInPath(walls, c, config).filter { c.alongM(it.centerW) < config.policy.warnMaxM }
            assertTrue(inPath.isEmpty()) { "heading $deg°: ${inPath.size} wall cells in path" }
        }
    }

    @Test
    fun `box on the walking line is in path`() {
        val b = box(-0.15f, 0.15f, -2.0f, -2.3f)
        val inPath = walkableInPath(walls + b, corridor(5.0), config)
        assertTrue(b.any { it in inPath })
    }

    @Test
    fun `box narrowing the corridor below body width is in path`() {
        val b = box(0.15f, 0.52f, -2.0f, -2.3f) // 남는 폭 0.67 m < 0.8 m
        val inPath = walkableInPath(walls + b, corridor(0.0), config)
        assertTrue(b.any { it in inPath })
    }

    @Test
    fun `side box leaving body width in a wide corridor is not in path (meaning changes, M36)`() {
        val wide = wall(-1.025f) + wall(1.025f) // 폭 2 m
        val b = box(0.30f, 0.60f, -2.0f, -2.3f) // 왼쪽에 1.3 m 남음
        val inPath = walkableInPath(wide + b, corridor(0.0), config)
        assertTrue(b.none { it in inPath })
    }
}
