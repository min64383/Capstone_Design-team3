package hearspace.core.segment

import hearspace.core.geometry.Vec3
import hearspace.core.mapping.VoxelView
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.types.ConfigLoader
import hearspace.core.types.Obstacle
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.RepStrategy
import hearspace.core.types.VoxelLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.floor

/** 분할 C2: 구조물(큰 수직 평면) 꼬리표와 꼬리표별 군집 (IMPROVE_SPEC §6, §12 SC-18·SC-21, M13). */
class SegmentTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val planes = """{ "segment": { "method": "PLANES" } }"""
    private val config = ConfigLoader.load(base, planes)
    private val v = config.map.voxelSizeM

    private fun box(x0: Float, x1: Float, y0: Float, y1: Float, z0: Float, z1: Float): List<VoxelView> {
        fun idx(a: Float) = floor(a / v + 1e-4f).toInt()
        val out = ArrayList<VoxelView>()
        for (ix in idx(x0) until idx(x1)) for (iy in idx(y0) until idx(y1)) for (iz in idx(z0) until idx(z1)) {
            out += VoxelView(ix, iy, iz, Vec3((ix + 0.5f) * v, (iy + 0.5f) * v, (iz + 0.5f) * v), 10, 1f, 0L)
        }
        return out
    }

    @Test
    fun `tall long planes are structure, objects next to them are not`() {
        val wall = box(0.6f, 0.65f, 0f, 2f, -4f, 0f)              // 옆벽 4 m
        val endWall = box(-0.6f, 0.6f, 0f, 2f, -4.5f, -4.45f)     // 끝 벽 1.2 m
        val freeBox = box(-0.2f, 0.2f, 0f, 0.6f, -2.3f, -2f)      // 떨어진 상자
        val attached = box(0.2f, 0.6f, 0f, 0.6f, -1.3f, -1f)      // 벽에 붙은 상자(벽에서 0.4 m 나옴)
        val pole = box(-0.5f, -0.35f, 0f, 2f, -3f, -2.85f)        // 높고 좁은 기둥(사람·기둥)
        val bench = box(-0.55f, -0.35f, 0f, 0.4f, -1.5f, 0f)      // 길고 낮은 의자
        val all = wall + endWall + freeBox + attached + pole + bench
        val s = StructurePlanes.find(all, config.segment, v)
        fun frac(part: List<VoxelView>) = part.count { s[all.indexOf(it)] }.toFloat() / part.size
        assertEquals(1f, frac(wall), "side wall")
        assertEquals(1f, frac(endWall), "end wall")
        assertEquals(0f, frac(freeBox), "free-standing box")
        assertEquals(0f, frac(pole), "narrow pole")
        assertEquals(0f, frac(bench), "long low bench")
        // 벽에 붙은 상자: 벽 가까운 부분은 벽과 함께 구조물이 될 수 있지만(직선 띠 ±planeInlierM, 직선이 조금 기울면 더),
        // 벽에서 띠 두 배보다 멀리 나온 부분은 물체로 남는다
        val inner = attached.filter { it.centerW.x < 0.6f - 2 * config.segment.planeInlierM }
        val labelled = attached.filter { s[all.indexOf(it)] }.map { it.centerW.x }.distinct().sorted()
        assertTrue(inner.isNotEmpty() && inner.none { s[all.indexOf(it)] }, "protruding part of the attached box stays an object; structure x = $labelled")
    }

    @Test
    fun `a low box with a rising membrane behind it is not a structure`() {
        // M13 S02 실측: 캐리어(높이 0.6) 뒤로 평활 깊이의 가짜 면이 비스듬히 1.2 m 높이까지 올라가며 1.2 m 이어진다.
        // 조각 전체의 높이 폭(0~1.2)으로 보면 "길고 높은 면"이지만, 위치마다 보면 어디도 키가 크지 않다.
        val suitcase = box(-0.2f, 0.2f, 0f, 0.6f, -2.7f, -2.4f)
        val membrane = (0 until 24).flatMap { i ->
            val z = -2.7f - i * v
            val y = 0.6f + i * 0.025f
            box(-0.2f, 0.2f, y, y + v, z - v, z)
        }
        val all = suitcase + membrane
        assertTrue(StructurePlanes.find(all, config.segment, v).none { it })
        // 같은 직선 끝에 벽(높이 2 m, 1 m)이 이어져도 벽만 구조물, 캐리어와 가짜 면은 물체(S02: 왼쪽 벽 – 가짜 면 – 캐리어)
        val wall = box(-0.2f, 0.2f, 0f, 2f, -4.9f, -3.9f)
        val withWall = all + wall
        val s = StructurePlanes.find(withWall, config.segment, v)
        assertTrue(wall.all { s[withWall.indexOf(it)] }, "wall")
        assertTrue((suitcase + membrane).none { s[withWall.indexOf(it)] }, "suitcase and membrane stay objects")
    }

    private fun run(spec: SceneSpec, overrides: String): List<Pair<Float, ObstacleSnapshot>> {
        val slow = SlowPath(ConfigLoader.load(base, overrides))
        return spec.generate().frames.mapNotNull { f -> f.depth?.let { f.tS to slow.process(it, f.truthHead.headingW) } }
    }

    private fun Obstacle.overlapsZ(z0: Float, z1: Float) = aabbMinW.z <= z1 && aabbMaxW.z >= z0

    @Test
    fun `SC-18 box attached to a wall stays an object after structure separation`() {
        val front = -2f
        for (ov in listOf("{}", planes)) {
            val last = run(Scenes.SC18, ov).last().second
            val box = last.obstacles.filter { it.label == VoxelLabel.OBJECT && it.overlapsZ(-2.3f, -2f) }
            assertTrue(box.isNotEmpty(), "$ov: box obstacle")
            val nearest = box.maxOf { it.repCandidatesW.getValue(RepStrategy.CORRIDOR_NEAREST).z }
            assertEquals(front, nearest, 0.1f, "$ov: nearest point on the box front")
        }
    }

    @Test
    fun `SC-21 box and the wall behind it are separate obstacles with planes`() {
        val wallZ = -4.2f
        // 합성 평활(4 px)의 가짜 면은 성겨서(점 간격 약 0.19 m > cluster.epsM) 기준선도 상자와 벽을 잇지 않는다.
        // 실제 녹화의 합쳐짐(S02 캐리어-문)은 재생 지표로 본다. 여기서는 PLANES가 둘을 따로 두는지만 확인한다.
        val withPlanes = run(Scenes.SC21, planes)
        val boxObs = withPlanes.flatMap { (_, s) -> s.obstacles.filter { it.label == VoxelLabel.OBJECT && it.overlapsZ(-2.7f, -2.4f) } }
        assertTrue(boxObs.isNotEmpty(), "box seen")
        assertTrue(boxObs.all { it.aabbMinW.z > wallZ + 0.05f }, "box obstacle reaches the wall: ${boxObs.minOf { it.aabbMinW.z }}")
        assertTrue(withPlanes.any { (_, s) -> s.obstacles.any { it.label == VoxelLabel.STRUCTURE && it.overlapsZ(wallZ - 0.1f, wallZ) } }, "wall is a structure")
    }
}
