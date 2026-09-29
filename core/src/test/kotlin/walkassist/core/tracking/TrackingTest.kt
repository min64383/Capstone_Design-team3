package walkassist.core.tracking

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import walkassist.core.geometry.Vec3
import walkassist.core.guidance.Corridor
import walkassist.core.pipeline.SlowPath
import walkassist.core.synth.SceneSpec
import walkassist.core.synth.Scenes
import walkassist.core.types.Config
import walkassist.core.types.ConfigLoader
import walkassist.core.types.HeightClass
import walkassist.core.types.Obstacle
import walkassist.core.types.ObstacleSnapshot
import walkassist.core.types.RepStrategy
import java.io.File
import kotlin.math.abs

class TrackingTest {

    private val config: Config = ConfigLoader.load(File(System.getProperty("walkassist.defaultConfig")).readText())

    /** 합성 장면을 느린 경로에 넣고 매 스냅샷을 돌려준다. 사용자 위치 = 카메라, 진행 방향 = 참값(M5 전). */
    private fun run(spec: SceneSpec): List<Pair<Float, ObstacleSnapshot>> {
        val slow = SlowPath(config)
        return spec.generate().frames.mapNotNull { f ->
            val d = f.depth ?: return@mapNotNull null
            f.tS to slow.process(d, d.worldFromCam.translation(), f.truthHead.headingW)
        }
    }

    /**
     * [min]..[max]와 겹치는 물체 중 사용자에게 가장 가까운 조각(경고 대상).
     * 멀리서 스치듯 보이는 윗면은 깊이 표본 간격(약 0.3 m)이 cluster.epsM보다 커서 여러 조각으로 나뉠 수 있다(알려진 제약).
     */
    private fun front(s: ObstacleSnapshot, min: Vec3, max: Vec3, userZ: Float = 0f) =
        s.obstacles.filter { overlaps(it, min, max) }.minByOrNull { abs(it.repCandidatesW.getValue(RepStrategy.NEAREST).z - userZ) }

    private fun overlaps(o: Obstacle, min: Vec3, max: Vec3) =
        o.aabbMinW.x <= max.x && o.aabbMaxW.x >= min.x && o.aabbMinW.y <= max.y && o.aabbMaxW.y >= min.y &&
            o.aabbMinW.z <= max.z && o.aabbMaxW.z >= min.z

    @Test
    fun `SC-05 table corner - three rep points differ, corridor-nearest is on the protruding edge`() {
        val spec = Scenes.SC05
        val top = spec.scene.items.first { it.name == "table_top" }.shape
        val snaps = run(spec)
        // 테이블이 앞에 보이는 동안(걷기 중반)의 스냅샷
        val (_, snap) = snaps.first { (t, s) -> t >= 2.6f && s.obstacles.any { overlaps(it, top.aabbMin, top.aabbMax) } }
        val table = front(snap, top.aabbMin, top.aabbMax)!!
        assertTrue(table.inCorridor)
        assertEquals(HeightClass.BODY, table.heightClass)
        val c = table.repCandidatesW
        val corridorNearest = c.getValue(RepStrategy.CORRIDOR_NEAREST)
        val centroid = c.getValue(RepStrategy.CENTROID)
        // 보행선은 x = 0, 통로 반폭 0.4: 돌출 모서리(x 0.2~0.4)만 통로 안
        assertTrue(corridorNearest.x in 0.15f..0.45f, "corridor-nearest $corridorNearest")
        assertEquals(-2.0f, corridorNearest.z, 0.1f) // 모서리 앞면
        // 중심점은 통로 밖(테이블 몸체 쪽), 통로 안 최근접은 모서리
        assertTrue(centroid.x > corridorNearest.x + 0.1f, "centroid $centroid vs corridor-nearest $corridorNearest")
        assertTrue((centroid - corridorNearest).horizontal().norm() > 0.1f)
        assertEquals(corridorNearest, table.repPointW) // 기본 설정 CORRIDOR_NEAREST
    }

    @Test
    fun `SC-06 head-height plate attached to a wall is HEAD by its in-corridor part`() {
        val spec = Scenes.SC06
        val plate = spec.scene.items.first { it.name == "head_plate" }.shape
        val snaps = run(spec)
        val last = snaps.last().second
        val obs = last.obstacles.filter { overlaps(it, plate.aabbMin, plate.aabbMax) }
        assertEquals(1, obs.size, "plate and wall should be one cluster: ${last.obstacles}")
        val o = obs.single()
        // 벽(바닥까지 이어짐)과 한 군집이지만 통로 안 부분은 판뿐 → HEAD
        // 벽 아랫부분 ~0.2 m는 거리 비례 바닥 허용 오차로 바닥 처리된다(M3). 판(1.55 m)보다 훨씬 아래까지 벽이 한 군집
        assertTrue(o.aabbMinW.y < 0.5f, "cluster includes the wall well below the plate: ${o.aabbMinW}")
        assertTrue(o.inCorridor)
        assertEquals(HeightClass.HEAD, o.heightClass)
        // 머리 높이 판이 보이기 시작한 뒤로 줄곧 HEAD
        val classes = snaps.mapNotNull { (_, s) -> s.obstacles.firstOrNull { overlaps(it, plate.aabbMin, plate.aabbMax) && it.inCorridor }?.heightClass }
        assertTrue(classes.isNotEmpty() && classes.all { it == HeightClass.HEAD }, "classes $classes")
    }

    @Test
    fun `box keeps the same id while walking, also with wrist sway`() {
        for (spec in listOf(Scenes.SC02, Scenes.SC08)) {
            val box = spec.scene.items.first { it.obstacle }.shape
            val snaps = run(spec)
            val ids = snaps.mapNotNull { (_, s) -> front(s, box.aabbMin, box.aabbMax)?.id }
            assertTrue(ids.size > 30, "${spec.id}: box seen in ${ids.size} snapshots")
            assertEquals(1, ids.toSet().size, "${spec.id}: ids ${ids.toSet()}")
            val last = front(snaps.last().second, box.aabbMin, box.aabbMax)!!
            // 가까워지면 조각이 하나로 합쳐진다
            assertEquals(1, snaps.last().second.obstacles.count { overlaps(it, box.aabbMin, box.aabbMax) })
            assertTrue(last.nObservations > 30)
            assertEquals(HeightClass.FLOOR, last.heightClass) // 바닥에 놓인 상자: 통로 안 최저점이 바닥(§7.4)
        }
    }

    @Test
    fun `SC-03 side walls are outside the corridor`() {
        val snap = run(Scenes.SC03).last().second
        assertTrue(snap.obstacles.isNotEmpty())
        assertTrue(snap.obstacles.none { it.inCorridor }, "${snap.obstacles.filter { it.inCorridor }}")
    }

    @Test
    fun `SC-07 low box stays tracked with the same id after leaving the view`() {
        val spec = Scenes.SC07
        val box = spec.scene.items.first { it.obstacle }.shape
        val ids = run(spec).mapNotNull { (_, s) -> front(s, box.aabbMin, box.aabbMax) }
        assertEquals(1, ids.map { it.id }.toSet().size)
        val last = ids.last()
        assertEquals(HeightClass.FLOOR, last.heightClass)
        assertTrue(last.inCorridor)
    }
}

class ClusterTest {
    private fun grid(x0: Float, z0: Float, n: Int, step: Float) =
        (0 until n).flatMap { i -> (0 until n).map { j -> Vec3(x0 + i * step, 0.5f, z0 + j * step) } }

    @Test
    fun `separated groups become separate clusters and noise is dropped`() {
        val a = grid(0f, 0f, 4, 0.05f)
        val b = grid(1f, 0f, 4, 0.05f)
        val noise = listOf(Vec3(5f, 0f, 5f))
        val pts = a + b + noise
        val clusters = Cluster.dbscanXZ(pts, 0.15f, 5)
        assertEquals(2, clusters.size)
        assertEquals(setOf(a.indices.toSet(), b.indices.map { it + a.size }.toSet()), clusters.map { it.toSet() }.toSet())
    }

    @Test
    fun `height is ignored - stacked points form one cluster`() {
        val pts = (0 until 10).map { Vec3(0f, it * 0.3f, 0f) }
        assertEquals(1, Cluster.dbscanXZ(pts, 0.15f, 5).size)
        assertEquals(0, Cluster.dbscanXZ(pts.take(4), 0.15f, 5).size) // minSamples 미만 → 전부 잡음
    }
}

class TrackerUnitTest {
    private val cfg = ConfigLoader.load(File(System.getProperty("walkassist.defaultConfig")).readText())

    private fun det(p: Vec3) = Detection(
        RepStrategy.entries.associateWith { p }, p, p, HeightClass.BODY, true, 1f, 0L,
    )

    @Test
    fun `matching keeps ids, new detections get new ids, missing tracks are dropped`() {
        val t = Tracker(cfg.track, RepStrategy.CORRIDOR_NEAREST)
        val first = t.update(listOf(det(Vec3(0f, 0f, -2f)), det(Vec3(1f, 0f, -2f))))
        val second = t.update(listOf(det(Vec3(1.1f, 0f, -2f)), det(Vec3(0.05f, 0f, -2f)), det(Vec3(3f, 0f, 0f))))
        assertEquals(first[0].id, second[1].id)
        assertEquals(first[1].id, second[0].id)
        assertTrue(second[2].id !in first.map { it.id })
        val third = t.update(listOf(det(Vec3(3f, 0f, 0f))))
        assertEquals(listOf(second[2].id), third.map { it.id })
    }

    @Test
    fun `rep points are smoothed with emaAlpha`() {
        val t = Tracker(cfg.track, RepStrategy.CENTROID)
        t.update(listOf(det(Vec3(0f, 0f, 0f))))
        val o = t.update(listOf(det(Vec3(0.2f, 0f, 0f)))).single()
        assertEquals(0.2f * cfg.track.emaAlpha, o.repPointW.x, 1e-6f)
        assertEquals(2, o.nObservations)
    }
}

class CorridorTest {
    private val cfg = ConfigLoader.load(File(System.getProperty("walkassist.defaultConfig")).readText())

    @Test
    fun `corridor box follows heading, width, length, behind and height`() {
        val c = Corridor(Vec3(0f, 1f, 0f), Vec3(0f, -0.3f, -1f), 0f, cfg.corridor) // 기울어진 진행 방향도 수평으로
        assertTrue(c.contains(Vec3(0f, 0.5f, -2f)))
        assertTrue(c.contains(Vec3(0.39f, 0.5f, -3.4f)))
        assertTrue(!c.contains(Vec3(0.41f, 0.5f, -2f))) // 폭 0.8
        assertTrue(!c.contains(Vec3(0f, 0.5f, -3.6f))) // 길이 3.5
        assertTrue(c.contains(Vec3(0f, 0.5f, 0.19f)) && !c.contains(Vec3(0f, 0.5f, 0.21f))) // 뒤 0.2
        assertTrue(!c.contains(Vec3(0f, 2.1f, -1f)) && !c.contains(Vec3(0f, -0.1f, -1f))) // 높이 0~2
        assertEquals(1f, c.lateralM(Vec3(1f, 0f, 0f)), 1e-6f) // −Z로 걸을 때 오른쪽 = +X
        assertEquals(2f, c.alongM(Vec3(0f, 0f, -2f)), 1e-6f)
    }

    @Test
    fun `height class uses the lowest in-corridor point`() {
        val cc = cfg.cluster
        assertEquals(HeightClass.HEAD, HeightClassifier.classify(listOf(1.6f, 1.3f), cc))
        assertEquals(HeightClass.BODY, HeightClassifier.classify(listOf(1.6f, 0.6f), cc))
        assertEquals(HeightClass.FLOOR, HeightClassifier.classify(listOf(0.1f), cc))
        assertNull(HeightClassifier.classify(emptyList(), cc))
        assertTrue(abs(cc.headMinM - 1.2f) < 1e-6f)
    }
}
