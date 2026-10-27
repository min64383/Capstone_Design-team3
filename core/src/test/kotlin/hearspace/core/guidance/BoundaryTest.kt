package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.VoxelView
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M44 재설계 나란한 경계 판정(`corridor.judge` BOUNDARY): 벽만 있는 복도는 진행 방향 오차 ±10°·사용자 치우침 ±0.15 m에서도 통로 안 0칸,
 * 짧은 물체·몸 폭보다 좁아지는 곳·보행선 위 긴 물체는 통로 안. 월드: 바닥 y = 0, 복도 축 −Z, 칸 5 cm, 머리 높이 1.5 m.
 */
class BoundaryTest {
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(), """{ "corridor": { "judge": "BOUNDARY" } }""")
    private val v = 0.05f

    private fun cell(x: Float, y: Float, z: Float) = VoxelView(
        kotlin.math.floor(x / v).toInt(), kotlin.math.floor(y / v).toInt(), kotlin.math.floor(z / v).toInt(), Vec3(x, y, z), 3, 1f, 0L, 0f,
    )

    /** x 위치의 벽(z [z0, z1], 높이 0.1 ~ 1.9 m). */
    private fun wall(x: Float, z0: Float = 0f, z1: Float = -4f): List<VoxelView> {
        val n = ((z0 - z1) / v).toInt()
        return (0 until n).flatMap { k -> (2..38).map { j -> cell(x, j * v, z0 - (0.025f + k * v)) } }
    }

    /** 상자: x [x0, x1], z [z1, z0](z0 > z1), 높이 0.6 m. */
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

    private fun corridor(headingDeg: Double, offsetX: Float = 0f): Corridor {
        val a = Math.toRadians(headingDeg)
        return Corridor(Vec3(offsetX, 1.5f, 0f), Vec3(kotlin.math.sin(a).toFloat(), 0f, -kotlin.math.cos(a).toFloat()), 0f, config.corridor)
    }

    /** SlowPath와 같은 순서: 상자 → 가장자리 규칙 → 나란한 경계. */
    private fun inPath(occupied: List<VoxelView>, c: Corridor): List<VoxelView> =
        withoutParallelBoundaries(occupied, withoutEdgeStructures(occupied, occupied.filter { c.contains(it.centerW) }, c, config), c, config)

    private val walls = wall(-0.545f) + wall(0.545f) // 폭 1.04 m 복도(벽 면 ±0.52)

    @Test
    fun `walls only - nothing in path within warn range for heading error up to 10 deg and offset 0_15 m`() {
        for (deg in listOf(0.0, 5.0, -5.0, 10.0, -10.0)) for (off in listOf(0f, 0.15f, -0.15f)) {
            val c = corridor(deg, off)
            val n = inPath(walls, c).count { c.alongM(it.centerW) < config.policy.warnMaxM }
            assertTrue(n == 0) { "heading $deg°, offset $off m: $n wall cells in path" }
        }
        // 재현: 상자 + 가장자리 규칙만이면 10°·치우침 0.15 m에서 벽 칸이 경고 거리 안에 남는다
        val c = corridor(10.0, 0.15f)
        val box = withoutEdgeStructures(walls, walls.filter { c.contains(it.centerW) }, c, config).count { c.alongM(it.centerW) < config.policy.warnMaxM }
        assertTrue(box > 0) { "box rule should keep wall cells at 10°, kept $box" }
    }

    @Test
    fun `box on the walking line is in path, also within stop range at 10 deg`() {
        val far = box(-0.15f, 0.15f, -2.0f, -2.3f)
        assertTrue(far.any { it in inPath(walls + far, corridor(10.0)) })
        val near = box(-0.15f, 0.15f, -0.6f, -0.9f)
        val c = corridor(10.0)
        assertTrue(inPath(walls + near, c).any { it in near && c.alongM(it.centerW) < config.policy.stopM })
    }

    @Test
    fun `box against the wall protruding into the path is in path`() {
        val b = box(0.17f, 0.52f, -2.0f, -2.3f)
        val got = inPath(walls + b, corridor(5.0))
        assertTrue(b.any { it in got && it.centerW.x < 0.35f })
    }

    @Test
    fun `corridor narrowing below body width is in path`() {
        // z −2 ~ −3 m 구간만 벽이 ±0.345로 들어옴(남는 폭 0.64 m < 0.8 m)
        val narrowed = wall(-0.545f, 0f, -2f) + wall(-0.545f, -3f, -4f) + wall(0.545f, 0f, -2f) + wall(0.545f, -3f, -4f) +
            wall(-0.345f, -2f, -3f) + wall(0.345f, -2f, -3f)
        val got = inPath(narrowed, corridor(5.0))
        assertTrue(got.any { it.centerW.z < -2f && it.centerW.z > -3f && kotlin.math.abs(it.centerW.x) < 0.4f })
    }

    @Test
    fun `long object on the walking line is in path`() {
        val rod = box(-0.05f, 0.05f, -1.5f, -2.7f) // 길이 1.2 m, 폭 0.1 m
        val got = inPath(walls + rod, corridor(5.0))
        assertTrue(rod.any { it in got })
    }
}
