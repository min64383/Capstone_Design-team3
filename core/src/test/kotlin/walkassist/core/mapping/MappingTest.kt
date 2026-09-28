package walkassist.core.mapping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import walkassist.core.geometry.Mat4
import walkassist.core.geometry.Projection
import walkassist.core.geometry.Vec3
import walkassist.core.synth.Box
import walkassist.core.synth.HorizontalPlane
import walkassist.core.synth.Scene
import walkassist.core.synth.SceneItem
import walkassist.core.synth.SceneSpec
import walkassist.core.synth.Scenes
import walkassist.core.synth.SyntheticRecording
import walkassist.core.synth.Walk
import walkassist.core.types.Config
import walkassist.core.types.ConfigLoader
import walkassist.core.types.Intrinsics
import walkassist.core.types.MapHealth
import java.io.File

class MappingTest {

    private val config: Config = ConfigLoader.load(File(System.getProperty("walkassist.defaultConfig")).readText())
    private val voxel = config.map.voxelSizeM

    /** 합성 녹화를 처음부터 끝까지 맵에 넣는다. 진행 방향은 참값(진행 방향 추정은 M5). [onFrame]은 매 깊이 뒤에 불린다. */
    private fun run(rec: SyntheticRecording, onFrame: (Float, LocalMap, MapUpdate) -> Unit = { _, _, _ -> }): LocalMap {
        val map = LocalMap(config)
        for (f in rec.frames) {
            val d = f.depth ?: continue
            val u = map.update(d, d.worldFromCam.translation(), f.truthHead.headingW)
            onFrame(f.tS, map, u)
        }
        return map
    }

    private fun inside(p: Vec3, min: Vec3, max: Vec3, margin: Float) =
        p.x in (min.x - margin)..(max.x + margin) && p.y in (min.y - margin)..(max.y + margin) && p.z in (min.z - margin)..(max.z + margin)

    @Test
    fun `SC-01 floor only - floor found within 2 cm, no occupied voxels`() {
        run(Scenes.SC01.generate()) { tS, map, u ->
            assertEquals(MapHealth.OK, u.mapHealth, "t=$tS")
            assertEquals(0f, u.floorY!!, 0.02f, "t=$tS")
            assertTrue(map.voxels.occupied().isEmpty(), "t=$tS: ${map.voxels.occupied().take(3)}")
        }
    }

    @Test
    fun `SC-02 box - occupied voxels only on the box, none on the floor`() {
        val spec = Scenes.SC02
        val box = spec.scene.items.first { it.obstacle }.shape
        val map = run(spec.generate())
        val occ = map.voxels.occupied()
        assertTrue(occ.size > 30, "box voxels ${occ.size}")
        for (v in occ) {
            assertTrue(inside(v.centerW, box.aabbMin, box.aabbMax, voxel), "voxel ${v.centerW} outside box")
            assertTrue(v.centerW.y > config.floor.toleranceM, "floor voxel ${v.centerW}")
        }
    }

    @Test
    fun `SC-03 walls - voxels only at the walls, corridor middle empty`() {
        val map = run(Scenes.SC03.generate())
        val occ = map.voxels.occupied()
        assertTrue(occ.size > 100, "wall voxels ${occ.size}")
        for (v in occ) {
            val ax = kotlin.math.abs(v.centerW.x)
            assertTrue(ax in (0.8f - voxel)..(0.9f + voxel), "voxel ${v.centerW} not on a wall")
        }
    }

    @Test
    fun `SC-04 removed box - its voxels decay away while in view`() {
        val spec = Scenes.SC04
        val box = spec.scene.items.first { it.obstacle }.shape
        var beforeRemoval = 0
        var firstEmptyAfter: Float? = null
        run(spec.generate()) { tS, map, _ ->
            val onBox = map.voxels.views().count { inside(it.centerW, box.aabbMin, box.aabbMax, voxel) }
            if (tS < 3f) beforeRemoval = map.voxels.occupied().count { inside(it.centerW, box.aabbMin, box.aabbMax, voxel) }
            if (tS >= 3f && onBox == 0 && firstEmptyAfter == null) firstEmptyAfter = tS
        }
        assertTrue(beforeRemoval > 30, "box voxels before removal $beforeRemoval")
        // score 1 → 0: decayPerObservation 0.3이면 4장(약 0.13 s)
        assertTrue(firstEmptyAfter != null && firstEmptyAfter < 3.5f, "box voxels still present at ${firstEmptyAfter ?: "end"}")
    }

    @Test
    fun `SC-07 low box stays in the map after leaving the view`() {
        val spec = Scenes.SC07
        val box = spec.scene.items.first { it.obstacle }.shape
        val rec = spec.generate()
        val map = run(rec)
        val lastDepth = rec.frames.last { it.depth != null }.depth!!
        val camFromWorld = lastDepth.worldFromCam.rigidInverse()
        val k = lastDepth.K
        val boxVoxels = map.voxels.occupied().filter { inside(it.centerW, box.aabbMin, box.aabbMax, voxel) }
        assertTrue(boxVoxels.size > 20, "box voxels kept: ${boxVoxels.size}")
        // 실제로 시야 밖인지: 마지막 깊이 이미지에 하나도 투영되지 않는다
        val visible = boxVoxels.count { v ->
            val p = camFromWorld.transformPoint(v.centerW)
            val uv = Projection.project(p, k)
            uv != null && uv.first in 0f..(k.width - 1f) && uv.second in 0f..(k.height - 1f)
        }
        assertEquals(0, visible, "box should be out of view at the end")
        // 사용자와 상자가 1 m 이내(STOP 구간 거리)
        val camPos = lastDepth.worldFromCam.translation()
        assertTrue(boxVoxels.minOf { (it.centerW - camPos).horizontal().norm() } < 1f)
    }

    @Test
    fun `floor unknown - map is not updated and health is degraded`() {
        // 바닥이 없고 카메라(1 m)보다 높은 곳에만 물체가 있다 → 바닥 후보 점 없음
        val scene = Scene(listOf(SceneItem("sign", Box(Vec3(-3f, 1.2f, -3f), Vec3(3f, 3f, -2.9f)), true)))
        val spec = SceneSpec("no-floor", scene, Walk(durationS = 1f, pitchDownDeg = -30f))
        run(spec.generate()) { _, map, u ->
            assertNull(u.floorY)
            assertEquals(MapHealth.DEGRADED, u.mapHealth)
            assertEquals(0, map.voxels.size)
        }
    }

    @Test
    fun `points below the floor are counted, not mapped`() {
        // 2 m 앞에서 바닥이 0.4 m 내려간다(내려가는 단차)
        val scene = Scene(
            listOf(
                SceneItem("floor_near", Box(Vec3(-5f, -0.1f, -2f), Vec3(5f, 0f, 5f)), false),
                SceneItem("floor_low", Box(Vec3(-5f, -0.5f, -10f), Vec3(5f, -0.4f, -2f)), false),
            ),
        )
        var below = 0
        val map = run(SceneSpec("step", scene, Walk(durationS = 2f)).generate()) { _, _, u -> below += u.nBelowFloorPoints }
        assertTrue(below > 1000, "below-floor points $below")
        assertTrue(map.voxels.views().none { it.centerW.y < -0.2f }, "below-floor points must not be mapped")
        assertEquals(0f, map.floor.floorY!!, 0.02f)
    }

    @Test
    fun `same input gives the same map`() {
        val rec = Scenes.SC08.generate()
        val a = run(rec).voxels.views().sortedBy { Triple(it.ix, it.iy, it.iz).toString() }
        val b = run(rec).voxels.views().sortedBy { Triple(it.ix, it.iy, it.iz).toString() }
        assertEquals(a, b)
    }
}

class VoxelMapTest {

    private val config: Config = ConfigLoader.load(File(System.getProperty("walkassist.defaultConfig")).readText())
    private val k = Intrinsics(100f, 100f, 50f, 50f, 101, 101)

    /** 카메라 원점, C_cv 정면(+Z)이 월드 −Z가 되도록: C_cv (x, y, z) → 월드 (x, −y, −z). */
    private val worldFromCam = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
    private val camFromWorld = worldFromCam.rigidInverse()

    private fun depthImage(mm: Int) = ShortArray(k.width * k.height) { mm.toShort() }

    private fun mapWithVoxelAt(p: Vec3, hits: Int = 3): VoxelMap {
        val m = VoxelMap(config.map)
        repeat(hits) { i ->
            m.beginFrame()
            m.insert(p, i.toLong())
        }
        return m
    }

    @Test
    fun `hits count once per frame and score saturates`() {
        val m = VoxelMap(config.map)
        m.beginFrame()
        repeat(10) { m.insert(Vec3(0.01f, 0.01f, 0.01f), 0) }
        assertEquals(1, m.views().single().hits)
        repeat(20) {
            m.beginFrame()
            m.insert(Vec3(0.01f, 0.01f, 0.01f), 0)
        }
        assertEquals(21, m.views().single().hits)
        assertEquals(1f, m.views().single().score, 1e-6f)
    }

    @Test
    fun `negative coordinates floor to the correct voxel`() {
        val m = VoxelMap(config.map)
        assertEquals(-1, m.index(-0.001f))
        assertEquals(0, m.index(0f))
        assertEquals(-2, m.index(-0.05f - 1e-4f))
    }

    @Test
    fun `free space decays only when depth is seen beyond the voxel`() {
        val p = Vec3(0f, 0f, -2f) // 광축 위 2 m
        // 더 멀리 3 m가 보이면 감쇠
        mapWithVoxelAt(p).also { m ->
            m.beginFrame()
            assertEquals(1, m.decayFree(depthImage(3000), k, camFromWorld))
        }
        // 가려짐(1 m 물체), 복셀 자리(2.05 m), 무효(0) → 감쇠 없음
        for (mm in listOf(1000, 2050, 0)) {
            val m = mapWithVoxelAt(p)
            m.beginFrame()
            assertEquals(0, m.decayFree(depthImage(mm), k, camFromWorld), "depth $mm mm")
        }
        // 시야 밖(카메라 뒤, 옆) → 감쇠 없음
        for (q in listOf(Vec3(0f, 0f, 2f), Vec3(5f, 0f, -1f))) {
            val m = mapWithVoxelAt(q)
            m.beginFrame()
            assertEquals(0, m.decayFree(depthImage(3000), k, camFromWorld), "voxel $q")
        }
    }

    @Test
    fun `repeated free observations remove the voxel`() {
        val m = mapWithVoxelAt(Vec3(0f, 0f, -2f), hits = 5) // score 1.0
        var n = 0
        while (m.size > 0 && n < 10) {
            m.beginFrame()
            m.decayFree(depthImage(3000), k, camFromWorld)
            n++
        }
        assertEquals(0, m.size)
        assertEquals(4, n) // 1.0 − 0.3×4 ≤ 0
    }

    @Test
    fun `prune removes passed, unseen and far voxels only`() {
        val m = VoxelMap(config.map)
        val heading = Vec3(0f, 0f, -1f)
        m.beginFrame()
        m.insert(Vec3(0f, 0.5f, -1f), 0) // 앞: 유지
        m.insert(Vec3(0f, 0.5f, 0.5f), 0) // 뒤 0.5 m: 유지(passedMargin 1.0)
        m.insert(Vec3(0f, 0.5f, 1.5f), 0) // 뒤 1.5 m: 지나감
        m.insert(Vec3(0f, 0.5f, -6f), 0) // 앞 6 m: 반경 밖
        val c = m.prune(Vec3.ZERO, heading, 1_000_000_000L)
        assertEquals(1, c.passed)
        assertEquals(1, c.outOfRadius)
        assertEquals(2, m.size)
        // 10 s 넘게 관측 없음 → 삭제
        val c2 = m.prune(Vec3.ZERO, heading, 10_000_000_001L)
        assertEquals(2, c2.unseen)
        assertEquals(0, m.size)
    }

    @Test
    fun `scale scores and clear`() {
        val m = mapWithVoxelAt(Vec3(0f, 0f, -2f), hits = 5)
        m.scaleScores(0.5f)
        assertEquals(0.5f, m.views().single().score, 1e-6f)
        m.clear()
        assertEquals(0, m.size)
    }
}

class FloorTest {
    private val config: Config = ConfigLoader.load(File(System.getProperty("walkassist.defaultConfig")).readText())

    private fun points(vararg ys: Pair<Float, Int>): FloatArray =
        ys.flatMap { (y, n) -> List(n) { listOf(0f, y, 0f) }.flatten() }.toFloatArray()

    @Test
    fun `first estimate uses points below the camera, then the search band`() {
        val f = Floor(config.floor)
        // 탁자면(0.7)보다 바닥(−0.3)이 많다
        val u = f.update(points(-0.3f to 500, 0.7f to 300, 1.5f to 1000), cameraY = 1.0f)
        assertEquals(-0.3f, u.floorY!!, 1e-4f)
        assertEquals(800, u.nCandidates) // 카메라(1.0) 위의 점 제외
        // 이후: 직전 바닥 ± 0.5 밖(0.7의 많은 점)은 무시하고 평활
        val u2 = f.update(points(-0.2f to 300, 0.7f to 5000), cameraY = 1.0f)
        assertEquals(-0.3f + 0.2f * 0.1f, u2.floorY!!, 1e-4f)
    }

    @Test
    fun `too few points keeps previous estimate`() {
        val f = Floor(config.floor)
        assertNull(f.update(points(-1f to 10), 0f).floorY)
        f.update(points(-1f to 300), 0f)
        val u = f.update(points(-0.9f to 10), 0f)
        assertEquals(false, u.estimated)
        assertEquals(-1f, u.floorY!!, 1e-4f)
    }
}
