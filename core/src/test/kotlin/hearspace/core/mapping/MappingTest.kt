package hearspace.core.mapping

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.synth.Box
import hearspace.core.synth.HorizontalPlane
import hearspace.core.synth.Noise
import hearspace.core.synth.Scene
import hearspace.core.synth.SceneItem
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.synth.SyntheticRecording
import hearspace.core.synth.Walk
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader
import hearspace.core.types.Intrinsics
import hearspace.core.types.MapHealth
import java.io.File

class MappingTest {

    private val config: Config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())
    private val voxel = config.map.voxelSizeM

    /** 합성 녹화를 처음부터 끝까지 맵에 넣는다. 진행 방향은 참값(진행 방향 추정은 M5). [onFrame]은 매 깊이 뒤에 불린다. */
    private fun run(rec: SyntheticRecording, cfg: Config = config, onFrame: (Float, LocalMap, MapUpdate) -> Unit = { _, _, _ -> }): LocalMap {
        val map = LocalMap(cfg)
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
        // 기준선(히스토그램 바닥)을 명시한다. 평면 바닥은 이 장면에서 가까운 바닥 띠(0.5 m)가 너무 좁아(평면 퍼짐 기준 미달) 먼 낮은
        // 바닥을 바닥으로 잡아 내려가는 단차를 놓친다: 단차·계단은 바닥 높이 지도(M13.5)의 일이다(M13_REPORT §5)
        val histogram = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(), """{ "floor": { "source": "HISTOGRAM" } }""")
        val map = run(SceneSpec("step", scene, Walk(durationS = 2f)).generate(), histogram) { _, _, u -> below += u.nBelowFloorPoints }
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

    private val config: Config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())
    private val k = Intrinsics(100f, 100f, 50f, 50f, 101, 101)

    /** 기준선 규칙 테스트는 HITS를 명시한다(`-PtestOverrides`로 기본 모드를 바꾼 실행에서도 기준선을 보게). */
    private val hitsCfg = config.map.copy(mode = hearspace.core.types.MapMode.HITS, hitWeighting = hearspace.core.types.HitWeighting.NONE)

    /** 카메라 원점, C_cv 정면(+Z)이 월드 −Z가 되도록: C_cv (x, y, z) → 월드 (x, −y, −z). */
    private val worldFromCam = Mat4(floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f))
    private val camFromWorld = worldFromCam.rigidInverse()

    private fun depthImage(mm: Int) = ShortArray(k.width * k.height) { mm.toShort() }

    private fun mapWithVoxelAt(p: Vec3, hits: Int = 3): VoxelMap {
        val m = VoxelMap(hitsCfg)
        repeat(hits) { i ->
            m.beginFrame()
            m.insert(p, i.toLong())
        }
        return m
    }

    @Test
    fun `distance weighting needs more far frames and keeps near votes`() {
        val cfg = hitsCfg.copy(hitWeighting = hearspace.core.types.HitWeighting.DISTANCE)
        fun occupiedAfter(frames: Int, distM: Float): Boolean {
            val m = VoxelMap(cfg)
            repeat(frames) { m.beginFrame(); m.insert(Vec3(0.01f, 0.01f, -distM), it.toLong(), m.weight(distM)) }
            return m.occupied().isNotEmpty()
        }
        val near = cfg.weightRefM * 0.75f
        val far = cfg.weightRefM * 2f // 가중치 1/4
        assertTrue(occupiedAfter(cfg.minHits, near), "가까운 관측은 기준선과 같은 장 수")
        assertFalse(occupiedAfter(cfg.minHits, far), "먼 관측은 같은 장 수로는 점유가 아님")
        assertTrue(occupiedAfter(cfg.minHits * 4, far), "가중합이 문턱에 닿으면 점유")
        // 기준선은 거리와 무관
        val base = VoxelMap(hitsCfg)
        repeat(cfg.minHits) { base.beginFrame(); base.insert(Vec3(0.01f, 0.01f, -far), it.toLong(), base.weight(far)) }
        assertEquals(1, base.occupied().size)
    }

    @Test
    fun `tsdf averages a noisy surface into a thinner layer than hits`() {
        // 카메라 원점, 정면 2 m(월드 z −2)의 평면을 깊이 잡음 ±5 cm(1.95·2.00·2.05 m)로 18장 본다. HITS는 맞은 칸을 모두 남겨 세 층,
        // TSDF는 평균 표면(2.0 m) 양옆 반 칸 안의 두 층만 점유다
        fun layers(cfg: hearspace.core.types.MapConfig): Int {
            val m = VoxelMap(cfg)
            repeat(18) { i -> // 층마다 6장 = minHits
                val d = listOf(1.95f, 2.0f, 2.05f)[i % 3]
                m.beginFrame()
                for (a in -3..3) for (b in -3..3) {
                    m.insert(Vec3(0.01f + a * 0.02f, 0.01f + b * 0.02f, -d), i.toLong(), m.weight(d), cameraW = Vec3.ZERO)
                }
                m.decayFree(depthImage((d * 1000).toInt()), k, camFromWorld)
            }
            return m.occupied().filter { kotlin.math.abs(it.centerW.x) < 0.05f && kotlin.math.abs(it.centerW.y) < 0.05f }.map { it.iz }.distinct().size
        }
        val hits = layers(hitsCfg)
        val tsdf = layers(hitsCfg.copy(mode = hearspace.core.types.MapMode.TSDF))
        assertEquals(3, hits)
        assertTrue(tsdf in 1..2, "tsdf layers $tsdf")
    }

    @Test
    fun `wall votes count once per frame next to the hits`() {
        val m = VoxelMap(hitsCfg)
        val p = Vec3(0.01f, 0.01f, 0.01f)
        m.beginFrame(); m.insert(p, 0); m.insert(p, 0, wall = true); m.insert(p, 0, wall = true)
        m.beginFrame(); m.insert(p, 1)
        m.beginFrame(); m.insert(p, 2, wall = true)
        val v = m.views().single()
        assertEquals(3, v.hits)
        assertEquals(2, v.wallHits)
    }

    @Test
    fun `hits count once per frame and score saturates`() {
        val m = VoxelMap(hitsCfg)
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
        val m = VoxelMap(hitsCfg)
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
    fun `cell view uses the same rule as free-space decay`() {
        val p = Vec3(0f, 0f, -2f) // 광축 위 2 m
        val m = mapWithVoxelAt(p)
        fun view(q: Vec3, mm: Int, hit: Boolean = false) = m.cellView(q, hit, depthImage(mm), k, camFromWorld)
        assertEquals(CellView.HIT, view(p, 3000, hit = true))
        assertEquals(CellView.FREE, view(p, 3000))
        assertEquals(CellView.OCCLUDED, view(p, 1000))
        assertEquals(CellView.OCCLUDED, view(p, 2050)) // 칸 자리
        assertEquals(CellView.NO_DEPTH, view(p, 0))
        assertEquals(CellView.OUT_OF_VIEW, view(Vec3(0f, 0f, 2f), 3000)) // 카메라 뒤
        assertEquals(CellView.OUT_OF_VIEW, view(Vec3(5f, 0f, -1f), 3000)) // 영상 밖
        // 감쇠는 FREE일 때만
        for ((mm, decayed) in listOf(3000 to 1, 1000 to 0, 0 to 0)) {
            val d = mapWithVoxelAt(p)
            d.beginFrame()
            assertEquals(decayed, d.decayFree(depthImage(mm), k, camFromWorld), "depth $mm mm")
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
        val m = VoxelMap(hitsCfg)
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

    // ---- M13.2 로그 오즈: 규칙 자체를 보므로 값은 명시(OctoMap 적중 0.7, 빈 칸 0.4, 0.12~0.97, 점유 0.5, 기준 거리 2 m, 여유 3%) ----
    private val logCfg = config.map.copy(
        mode = hearspace.core.types.MapMode.LOG_ODDS, logHit = 0.847f, logMiss = -0.405f, logMin = -1.992f, logMax = 3.476f,
        logOccupied = 0f, weightRefM = 2f, freeMarginRatio = 0.03f,
    )

    private fun logMapWithVoxelAt(p: Vec3, hits: Int, weight: Float = 1f) = VoxelMap(logCfg).also { m ->
        repeat(hits) { i ->
            m.beginFrame()
            m.insert(p, i.toLong(), weight)
        }
    }

    @Test
    fun `log odds - hits make a voxel occupied, the upper clamp keeps it responsive, misses remove it`() {
        val p = Vec3(0f, 0f, -2f)
        val m = logMapWithVoxelAt(p, hits = 1)
        assertEquals(1, m.occupied().size, "one hit = p 0.7 > 0.5")
        repeat(20) { m.beginFrame(); m.insert(p, 0) }
        assertEquals(logCfg.logMax, m.views().single().logOdds, 1e-6f) // 상한에서 멈춤
        // 상한(3.476)에서 빈 칸 관측(−0.405) 9번이면 점유가 풀리고 하한까지 가면 지워진다
        var n = 0
        while (m.occupied().isNotEmpty()) { m.beginFrame(); m.decayFree(depthImage(3000), k, camFromWorld); n++ }
        assertEquals(9, n)
        while (m.size > 0 && n < 100) { m.beginFrame(); m.decayFree(depthImage(3000), k, camFromWorld); n++ }
        assertEquals(0, m.size)
    }

    @Test
    fun `log odds - out of view, occluded and invalid depth leave the voxel unchanged`() {
        for ((q, mm) in listOf(Vec3(0f, 0f, 2f) to 3000, Vec3(5f, 0f, -1f) to 3000, Vec3(0f, 0f, -2f) to 1000, Vec3(0f, 0f, -2f) to 0)) {
            val m = logMapWithVoxelAt(q, hits = 2)
            val before = m.views().single().logOdds
            m.beginFrame()
            m.decayFree(depthImage(mm), k, camFromWorld)
            assertEquals(before, m.views().single().logOdds, 1e-6f, "voxel $q depth $mm")
        }
    }

    @Test
    fun `log odds - free margin grows with depth`() {
        // 칸 중심 깊이 1.975 m: 여유 max(0.05, 0.03 × 1.975) ≈ 0.059 m → 2.03 m 관측은 칸 자리, 2.10 m는 지나침
        // (HITS의 고정 여유 0.15 m면 2.10 m도 칸 자리로 봐서 지우지 않는다)
        for ((mm, decays) in listOf(2030 to 0, 2100 to 1)) {
            val m = logMapWithVoxelAt(Vec3(0f, 0f, -2f), hits = 2)
            m.beginFrame()
            assertEquals(decays, m.decayFree(depthImage(mm), k, camFromWorld), "depth $mm mm")
        }
    }

    @Test
    fun `log odds - far observations weigh less, so near views overwrite a far phantom`() {
        val m = VoxelMap(logCfg)
        assertEquals(1f, m.weight(1.5f), 1e-6f)
        assertEquals(0.25f, m.weight(4f), 1e-6f) // (2 / 4)²
        // 4 m 밖에서 20번 본 가짜 칸(가중치 0.25 → 로그 오즈 3.476 상한)을 2 m 안 가까운 빈 칸 관측이 지운다: HITS보다 빨리
        val phantom = Vec3(0f, 0f, -2f)
        val far = logMapWithVoxelAt(phantom, hits = 20, weight = 0.25f)
        val hits = mapWithVoxelAt(phantom, hits = 20)
        var nLog = 0
        while (far.occupied().isNotEmpty()) { far.beginFrame(); far.decayFree(depthImage(3000), k, camFromWorld); nLog++ }
        var nHits = 0
        while (hits.occupied().isNotEmpty()) { hits.beginFrame(); hits.decayFree(depthImage(3000), k, camFromWorld); nHits++ }
        assertTrue(nLog <= nHits * 3, "log odds $nLog vs hits $nHits frames")
    }
}

class FloorTest {
    private val config: Config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())
    /** M18 전 바닥 규칙(잊고 다시 찾음): 이 시험들은 그 규칙을 본다(유지는 기본 켬, M18). */
    private val baseFloor = config.floor.copy(holdWhenLost = false)

    private fun points(vararg ys: Pair<Float, Int>): FloatArray =
        ys.flatMap { (y, n) -> List(n) { listOf(0f, y, 0f) }.flatten() }.toFloatArray()

    @Test
    fun `a nearby box top with more points is not the floor when candidates must be well below the camera`() {
        // M13 실측: 캐리어에 다가가면 보이는 바닥보다 캐리어 윗면(카메라 아래 0.5 m) 점이 많아 바닥이 그리로 올라갔다
        val cam = Vec3(0f, 1.1f, 0f)
        val pts = points(0f to 300, 0.6f to 800)
        assertEquals(0.6f, Floor(baseFloor.copy(minBelowCameraM = 0f), config.map.radiusM).update(pts, cam).floorY!!, 1e-4f)
        val f = Floor(baseFloor.copy(minBelowCameraM = 0.7f), config.map.radiusM)
        assertEquals(0f, f.update(pts, cam).floorY!!, 1e-4f)
        // 바닥이 안 보이고 윗면만 보여도 바닥을 옮기지 않는다(직전 값 유지)
        assertEquals(0f, f.update(points(0.6f to 800), cam).floorY!!, 1e-4f)
    }

    @Test
    fun `facing an end wall with the floor out of view, the floor does not climb the wall when candidates must be well below the camera`() {
        // M12.0 실측: 끝 벽 1 m 앞에서 바닥이 시야에서 빠지면 후보가 끝 벽 면뿐이라, 최빈값이 탐색 폭 안에서 벽을 타고 약 1 m 올라갔다.
        // 합성: 폭 1.04 m 복도에서 끝 벽 0.8 m 앞까지 걸어 선다(카메라 높이 1 m, 10° 숙임이면 바닥은 카메라에서 1.07 m부터 보인다).
        val rec = SceneSpec(
            "M12.1", Scenes.corridorE(), Walk(legM = 3.2f, legCount = 1, durationS = 2f + 3.2f + 3f), Noise(seed = 3, depthMulStd = 0.01f),
        ).generate()
        fun trace(minBelowM: Float): List<Float?> {
            val f = Floor(baseFloor.copy(minBelowCameraM = minBelowM), config.map.radiusM)
            return rec.frames.mapNotNull { fr ->
                fr.depth?.let { d -> f.update(Projection.backprojectToWorld(d.depthMm, d.K, d.worldFromCam, config.depth.subsample), d.worldFromCam.translation()).floorY }
            }
        }
        val base = trace(0f).filterNotNull()
        assertTrue(base.max() > 0.3f, "baseline reproduces the climb: max ${base.max()}")
        val fixed = trace(0.8f)
        assertEquals(0f, fixed.first()!!, 0.05f)
        assertTrue(fixed.filterNotNull().all { it < 0.3f }, "max ${fixed.filterNotNull().max()}")
    }

    @Test
    fun `first estimate uses points below the camera, then the search band`() {
        val f = Floor(baseFloor.copy(minBelowCameraM = 0f), config.map.radiusM) // 기준선(하한 없음)을 명시: -PtestOverrides로 하한을 켠 실행에서도
        // 탁자면(0.7)보다 바닥(−0.3)이 많다
        val u = f.update(points(-0.3f to 500, 0.7f to 300, 1.5f to 1000), Vec3(0f, 1.0f, 0f))
        assertEquals(-0.3f, u.floorY!!, 1e-4f)
        assertEquals(800, u.nCandidates) // 카메라(1.0) 위의 점 제외
        // 이후: 직전 바닥 ± 0.5 밖(0.7의 많은 점)은 무시하고 평활
        val u2 = f.update(points(-0.2f to 300, 0.7f to 5000), Vec3(0f, 1.0f, 0f))
        assertEquals(-0.3f + 0.2f * 0.1f, u2.floorY!!, 1e-4f)
    }

    @Test
    fun `floor tolerance grows with horizontal distance`() {
        val f = Floor(baseFloor, config.map.radiusM) // toleranceM 0.05, tolerancePerM 0.08
        f.update(points(0f to 500), Vec3(0f, 1f, 0f))
        assertEquals(true, f.isFloor(0.12f, 1f)) // 허용 0.13
        assertEquals(false, f.isFloor(0.14f, 1f))
        assertEquals(true, f.isFloor(0.25f, 3f)) // 허용 0.29: 멀리서 올라가 보이는 바닥
        assertEquals(false, f.isFloor(0.35f, 3f))
        assertEquals(true, f.isBelowFloor(-0.2f, 1f)) // 기준 0.18
        assertEquals(false, f.isBelowFloor(-0.3f, 3f)) // 기준 0.34
    }

    @Test
    fun `too few points keeps previous estimate`() {
        val f = Floor(baseFloor, config.map.radiusM)
        assertNull(f.update(points(-1f to 10), Vec3.ZERO).floorY)
        f.update(points(-1f to 300), Vec3.ZERO)
        val u = f.update(points(-0.9f to 10), Vec3.ZERO)
        assertEquals(false, u.estimated)
        assertEquals(-1f, u.floorY!!, 1e-4f)
    }

    @Test
    fun `far points are not floor candidates`() {
        val f = Floor(baseFloor, config.map.radiusM) // radiusM 5
        val far = FloatArray(3 * 1000) { i -> if (i % 3 == 0) 20f else if (i % 3 == 1) -30f else 0f } // 수평 20 m, 아주 낮음
        val u = f.update(far + points(-1f to 300), Vec3.ZERO)
        assertEquals(-1f, u.floorY!!, 1e-4f)
        assertEquals(300, u.nCandidates)
    }

    @Test
    fun `floor is dropped and re-acquired after lostFrames without candidates`() {
        val f = Floor(baseFloor, config.map.radiusM) // lostFrames 10
        f.update(points(-30f to 500), Vec3.ZERO) // 잘못 잡은 바닥
        repeat(config.floor.lostFrames - 1) { assertEquals(-30f, f.update(points(-1f to 500), Vec3.ZERO).floorY!!, 1e-4f) }
        assertNull(f.update(points(-1f to 500), Vec3.ZERO).floorY) // 잊음
        assertEquals(-1f, f.update(points(-1f to 500), Vec3.ZERO).floorY!!, 1e-4f) // 다시 찾음
    }

    @Test
    fun `with holdWhenLost the floor is kept and re-searched without the band (M18 S3)`() {
        val f = Floor(baseFloor.copy(holdWhenLost = true), config.map.radiusM)
        f.update(points(-30f to 500), Vec3.ZERO) // 잘못 잡은 바닥
        repeat(config.floor.lostFrames) { assertEquals(-30f, f.update(points(-1f to 500), Vec3.ZERO).floorY!!, 1e-4f) } // 잊지 않음
        assertEquals(-1f, f.update(points(-1f to 500), Vec3.ZERO).floorY!!, 1e-4f) // 탐색 폭 없이 다시 찾아 평활 없이 받음
        assertEquals(-1f + 0.2f * 0.1f, f.update(points(-0.9f to 500), Vec3.ZERO).floorY!!, 1e-4f) // 그 뒤는 다시 폭 안에서 평활
    }
}
