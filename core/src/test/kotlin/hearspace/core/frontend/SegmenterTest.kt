package hearspace.core.frontend

import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.synth.Box
import hearspace.core.synth.HorizontalPlane
import hearspace.core.synth.Scene
import hearspace.core.synth.SceneItem
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.synth.Walk
import hearspace.core.types.ConfigLoader
import hearspace.core.types.HeightClass
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 깊이 영상 영역 분할(IMPROVE_SPEC §6 C3a): 합성 장면의 깊이 한 장(바닥 y = 0, 걷는 방향 월드 −Z). */
class SegmenterTest {
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())
    private val sub = config.depth.subsample

    /** [spec]의 t초 근처 깊이 한 장의 점(월드)과 점마다 영역 번호. 바닥 점(y < 0.05 m)은 영역에서 뺀다. */
    private fun segment(spec: SceneSpec, t: Float, zeroBoundary: Boolean = false): Pair<List<Vec3>, IntArray> {
        val d = spec.generate().frames.first { it.tS >= t && it.depth != null }.depth!!
        var mm = d.depthMm
        if (zeroBoundary) {
            val mask = EdgeDetector.boundaryMask(d, config.frontend)
            mm = mm.copyOf().also { a -> for (i in a.indices) if (mask[i]) a[i] = 0 }
        }
        val r = Segmenter.segment(mm, d.K, d.worldFromCam, sub, config.frontend.edgeMinStepRatio, config.frontend.edgeFitTolRatio, config.cluster.minSamples) { _, p -> p.y < 0.05f }
        val pts = Projection.backprojectToWorld(mm, d.K, d.worldFromCam, sub).let { a -> List(a.size / 3) { Vec3(a[3 * it], a[3 * it + 1], a[3 * it + 2]) } }
        val labels = r.pointLabels(mm, d.K)
        assertEquals(pts.size, labels.size)
        return pts to labels
    }

    /** [sel] 점들의 영역 번호 중 가장 많은 것과 그 비율. */
    private fun dominant(pts: List<Vec3>, labels: IntArray, sel: (Vec3) -> Boolean): Pair<Int, Float> {
        val ls = pts.indices.filter { sel(pts[it]) }.map { labels[it] }
        val (id, c) = ls.groupingBy { it }.eachCount().maxByOrNull { it.value }!!.toPair()
        return id to c.toFloat() / ls.size
    }

    private fun box(name: String, min: Vec3, max: Vec3) = SceneItem(name, Box(min, max), obstacle = true, expectedClass = HeightClass.FLOOR)
    private val floor = SceneItem("floor", HorizontalPlane(0f), obstacle = false)

    @Test
    fun `box and back wall are different regions once the edge detector removed the membrane`() {
        // SC-21: 상자 z −2.7~−2.4(높이 0.6), 벽 앞면 z −4.4. 평활된 막은 경사가 완만해 그대로 두면 상자와 벽이 한 영역이 된다(한계):
        // 영역 분할은 경계 판정으로 막을 지운 깊이에서 쓴다(지도 갱신의 앞단 순서와 같음)
        val (raw, rawLabels) = segment(Scenes.SC21, 1f, zeroBoundary = false)
        val rawBox = dominant(raw, rawLabels) { it.z in -2.7f..-2.4f && it.y in 0.1f..0.55f && kotlin.math.abs(it.x) < 0.2f }.first
        val rawWall = dominant(raw, rawLabels) { it.z < -4.35f && it.y in 0.8f..2.0f }.first
        println("SC-21 without the edge detector: box region $rawBox, wall region $rawWall")
        val (pts, labels) = segment(Scenes.SC21, 1f, zeroBoundary = true)
        val (boxId, boxShare) = dominant(pts, labels) { it.z in -2.7f..-2.4f && it.y in 0.1f..0.55f && kotlin.math.abs(it.x) < 0.2f }
        val (wallId, _) = dominant(pts, labels) { it.z < -4.35f && it.y in 0.8f..2.0f }
        assertTrue(boxId != 0 && boxShare > 0.9f, "box region $boxId share $boxShare")
        assertTrue(wallId != 0 && wallId != boxId, "wall $wallId vs box $boxId")
    }

    @Test
    fun `a box 0_5 m behind another is a separate region`() {
        // 앞 상자(높이 0.4) 뒤 0.5 m에 더 높은 상자(0.9): 위쪽이 보이고 앞 상자 윗면과 깊이가 0.5 m 넘게 다르다
        val scene = Scene(listOf(floor, box("front", Vec3(-0.2f, 0f, -2.3f), Vec3(0.2f, 0.4f, -2f)), box("back", Vec3(-0.3f, 0f, -3.1f), Vec3(0.3f, 0.9f, -2.8f))))
        val (pts, labels) = segment(SceneSpec("two", scene, Walk(durationS = 2f)), 1f)
        val (a, _) = dominant(pts, labels) { it.z in -2.3f..-2f && it.y in 0.05f..0.4f }
        val (b, _) = dominant(pts, labels) { it.z in -3.1f..-2.8f && it.y in 0.45f..0.9f }
        assertTrue(a != 0 && b != 0 && a != b, "front $a back $b")
    }

    @Test
    fun `two boxes touching side by side at the same depth are one region - known limit`() {
        // 깊이만 보므로 맞닿은 같은 거리의 두 물체는 가르지 못한다(색·법선 단서 필요, C3a 한계)
        val scene = Scene(listOf(floor, box("left", Vec3(-0.4f, 0f, -2.3f), Vec3(0f, 0.5f, -2f)), box("right", Vec3(0f, 0f, -2.3f), Vec3(0.4f, 0.5f, -2f))))
        val (pts, labels) = segment(SceneSpec("side", scene, Walk(durationS = 2f)), 1f)
        val (l, _) = dominant(pts, labels) { it.x in -0.35f..-0.05f && it.z in -2.3f..-2f && it.y in 0.05f..0.5f }
        val (r, _) = dominant(pts, labels) { it.x in 0.05f..0.35f && it.z in -2.3f..-2f && it.y in 0.05f..0.5f }
        assertTrue(l != 0 && l == r, "left $l right $r")
    }

    @Test
    fun `an oblique side wall is not shattered into many regions`() {
        // SC-14: 카메라 x 0.2에서 오른쪽 벽(x 0.55)을 비스듬히 본다
        val (pts, labels) = segment(Scenes.SC14, 1f)
        val wall = pts.indices.filter { kotlin.math.abs(pts[it].x - 0.55f) < 0.03f && pts[it].y in 0.1f..2.0f }.map { labels[it] }
        val counts = wall.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
        val top3 = counts.take(3).sumOf { it.value }
        assertTrue(wall.isNotEmpty() && top3 >= 0.9f * wall.size && counts.first().key != 0, "wall regions $counts")
    }

    @Test
    fun `the slow path reports regions only when segmentation is on`() {
        val base = File(System.getProperty("hearspace.defaultConfig")).readText()
        fun regions(overrides: String): List<Int?> {
            val slow = hearspace.core.pipeline.SlowPath(ConfigLoader.load(base, overrides))
            return Scenes.SC21.generate().frames.mapNotNull { f -> f.depth?.let { slow.process(it, f.truthHead.headingW); slow.lastMapUpdate!!.segments?.count } }
        }
        assertTrue(regions("""{ "frontend": { "segment": "NONE" }, "map": { "instances": false } }""").all { it == null })
        val on = regions("""{ "frontend": { "enabled": true, "segment": "REGION" } }""")
        // 상자(바닥 제외). 벽은 평면 추출을 켠 설정에서는 벽 평면으로 빠진다
        assertTrue(on.drop(on.size / 2).all { it != null && it >= 1 }, "regions per frame $on")
    }

    @Test
    fun `same depth gives the same regions`() {
        val (_, a) = segment(Scenes.SC21, 1f)
        val (_, b) = segment(Scenes.SC21, 1f)
        assertArrayEquals(a, b)
    }
}
