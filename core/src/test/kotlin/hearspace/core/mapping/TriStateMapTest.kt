package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.types.ConfigLoader
import hearspace.core.types.MapMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * 3상태 지도(M32 `map.mode` TRISTATE, 설계 4절 단위 시험). 합성 장면만 쓴다. 다른 설정을 켠 실행(`-PtestOverrides`)에서도 이 규칙만 보게
 * 지도 방식은 덮어써 고정한다.
 */
class TriStateMapTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val config = ConfigLoader.load(base, """{ "map": { "mode": "TRISTATE" }, "frontend": { "enabled": false, "rgbGuide": "NONE" } }""")
    private val voxel = config.map.voxelSizeM

    private fun headed(spec: SceneSpec) = ConfigLoader.load(
        base,
        listOf(
            """{ "map": { "mode": "TRISTATE" }, "frontend": { "enabled": false, "rgbGuide": "NONE" } }""",
            spec.walk.gripOffsetM.let { g -> """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] } }""" },
        ),
    )

    private fun inside(p: Vec3, min: Vec3, max: Vec3, margin: Float) =
        p.x in (min.x - margin)..(max.x + margin) && p.y in (min.y - margin)..(max.y + margin) && p.z in (min.z - margin)..(max.z + margin)

    @Test
    fun `floor only - floor cells are occupied in the map but nothing reaches guidance`() {
        val spec = Scenes.SC01
        val slow = SlowPath(headed(spec))
        var last = emptyList<hearspace.core.types.Obstacle>()
        for (f in spec.generate().frames) {
            val d = f.depth ?: continue
            last = slow.process(d, f.truthHead.headingW).obstacles
            assertTrue(last.isEmpty(), "t=${f.tS}: $last")
        }
        val occ = slow.map.voxels.occupied()
        assertTrue(occ.size > 100, "floor cells ${occ.size}")
        assertTrue(occ.all { abs(it.centerW.y) < voxel }, "non-floor cell ${occ.firstOrNull { abs(it.centerW.y) >= voxel }}")
    }

    @Test
    fun `a ray marks free cells up to the margin, hits the end cell and leaves the cells behind unknown`() {
        val map = VoxelMap(config.map)
        map.beginFrame()
        val cam = Vec3(0.01f, 1.01f, 0.01f)
        map.insertRay(Vec3(0.01f, 1.01f, -2.01f), cam, 1L, 1f, 0.1f)
        val free = map.freeCenters()
        val occ = map.occupied()
        assertEquals(1, occ.size)
        assertEquals(-2.025f, occ.single().centerW.z, 1e-4f)
        // 카메라 칸부터 측정점 0.1 m 앞까지(z −1.91)의 칸: −0.0 ~ −1.95 칸 중심, 측정점 칸 바로 앞 칸(−1.975)은 여유 안
        assertTrue(free.any { abs(it.z + 1.025f) < 1e-4f })
        assertTrue(free.none { it.z < -1.95f }, "free inside margin: ${free.filter { it.z < -1.95f }}")
        assertTrue(free.none { it.z < -2.05f }, "behind the hit is not free")
        assertTrue(free.all { abs(it.x - 0.025f) < 1e-4f && abs(it.y - 1.025f) < 1e-4f })
    }

    @Test
    fun `a point seen once and then looked through becomes free`() {
        val map = VoxelMap(config.map)
        val cam = Vec3(0.01f, 1.01f, 0.01f)
        map.beginFrame()
        map.insertRay(Vec3(0.01f, 1.01f, -1.01f), cam, 1L, 1f, 0.05f) // 떠 있는 점 한 번
        assertEquals(1, map.occupied().size)
        for (t in 2L..3L) {
            map.beginFrame()
            map.insertRay(Vec3(0.01f, 1.01f, -3.01f), cam, t, 1f, 0.05f) // 그 뒤의 면이 보인다
        }
        assertTrue(map.occupied().none { abs(it.centerW.z + 1.025f) < 1e-4f }, "floating point still occupied")
        assertTrue(map.freeCenters().any { abs(it.z + 1.025f) < 1e-4f })
    }

    @Test
    fun `SC-07 low box keeps its cells after leaving the view`() {
        val spec = Scenes.SC07
        val box = spec.scene.items.first { it.obstacle }.shape
        val map = LocalMap(config)
        var inView = 0
        for (f in spec.generate().frames) {
            val d = f.depth ?: continue
            map.update(d, d.worldFromCam.translation(), f.truthHead.headingW)
            inView = maxOf(inView, map.voxels.occupied().count { inside(it.centerW, box.aabbMin, box.aabbMax, voxel) })
        }
        val kept = map.voxels.occupied().count { inside(it.centerW, box.aabbMin, box.aabbMax, voxel) }
        assertTrue(inView > 20, "box cells while in view $inView")
        assertTrue(kept >= inView / 2, "box cells kept $kept of $inView")
    }

    @Test
    fun `a depth frame with no valid pixel changes nothing`() {
        val spec = Scenes.SC02
        val map = LocalMap(config)
        val frames = spec.generate().frames.mapNotNull { it.depth?.let { d -> d to it.truthHead.headingW } }
        for ((d, h) in frames.take(frames.size / 2)) map.update(d, d.worldFromCam.translation(), h)
        val before = map.voxels.views().map { Triple(it.centerW, it.evidence, it.free) }.toSet()
        val (d, h) = frames[frames.size / 2]
        map.update(d.copy(depthMm = ShortArray(d.depthMm.size)), d.worldFromCam.translation(), h)
        val after = map.voxels.views().map { Triple(it.centerW, it.evidence, it.free) }.toSet()
        assertEquals(before, after)
    }

    @Test
    fun `occupancy needs the same number of observations at 30 Hz and 10 Hz`() {
        // H ≥ 1은 관측 장 수의 문턱이라 깊이 주기와 무관하다(시각은 주기만큼 다르다)
        val spec = Scenes.SC02
        val box = spec.scene.items.first { it.obstacle }.shape
        fun observationsUntilOccupied(every: Int): Int? {
            val map = LocalMap(config)
            var n = 0
            for ((i, f) in spec.generate().frames.filter { it.depth != null }.withIndex()) {
                if (i % every != 0) continue
                val d = f.depth!!
                map.update(d, d.worldFromCam.translation(), f.truthHead.headingW)
                n++
                if (map.voxels.occupied().any { inside(it.centerW, box.aabbMin, box.aabbMax, 0f) }) return n
            }
            return null
        }
        val n30 = observationsUntilOccupied(1)
        val n10 = observationsUntilOccupied(3)
        assertTrue(n30 != null && n10 != null, "30 Hz $n30, 10 Hz $n10")
        assertTrue(abs(n30!! - n10!!) <= 1, "30 Hz $n30 observations, 10 Hz $n10")
    }

    @Test
    fun `other modes keep no free cells`() {
        val hits = ConfigLoader.load(base, """{ "map": { "mode": "HITS" } }""")
        assertEquals(MapMode.HITS, hits.map.mode)
        val map = VoxelMap(hits.map)
        map.beginFrame()
        map.insert(Vec3(0f, 1f, -2f), 1L)
        assertTrue(map.freeCenters().isEmpty())
    }
}
