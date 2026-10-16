package hearspace.core.truth

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.synth.SyntheticGenerator
import hearspace.core.types.DepthFrame
import hearspace.core.types.HeightClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.floor

/**
 * 장면 지표(M30 완료 기준 ①): 알고 넣은 오차를 지표가 맞히는지. 장면은 폭 1.8 m 복도(양옆 벽 두께 0.1 m, 끝 벽 5 m),
 * 보행선 2 m 앞 상자(폭 0.4, 높이 0.6, 깊이 0.3). 카메라는 시작점 높이 1 m에서 앞을 본다. "정확한 지도"는 정답 깊이를 그대로
 * 칸에 넣은 지도(잡음 없는 깊이 → 지도 경로)다.
 */
class SceneEvalTest {
    private val v = 0.05f
    private val tau = SceneEval.tau(v)
    private fun box(name: String, kind: TruthKind, min: Vec3, max: Vec3) = TruthObstacle(name, HeightClass.FLOOR, kind, min, max, null)
    private val obstacles = listOf(
        box("wall_left", TruthKind.STRUCTURE, Vec3(-1.0f, 0f, 0f), Vec3(-0.9f, 2.4f, 5f)),
        box("wall_right", TruthKind.STRUCTURE, Vec3(0.9f, 0f, 0f), Vec3(1.0f, 2.4f, 5f)),
        box("wall_end", TruthKind.STRUCTURE, Vec3(-1.0f, 0f, 5f), Vec3(1.0f, 2.4f, 5.1f)),
        box("box", TruthKind.OBJECT, Vec3(-0.2f, 0f, 2f), Vec3(0.2f, 0.6f, 2.3f)),
    )
    private val truth = GroundTruth(2, "test", false, "", obstacles)
    private val scene = SceneEval.scene(truth, 2.0f)!!

    // 월드 = 정답 좌표를 x만 뒤집은 것(정렬: 원점 0, 보행선 월드 +z, 바닥 y 0)
    private val al = Alignment(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, false, false, 0, 0.0, 0.0)
    private val k = SyntheticGenerator.DEPTH_K
    private val camW = Mat4.fromAxes(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, 0.05f))
    private val blank = DepthFrame(1L, ShortArray(k.width * k.height) { 1 }, null, k, camW, "test")
    private val depth = TruthDepth.render(blank, obstacles, al)

    /** 정답 깊이의 모든 화소를 칸 중심으로(지도와 같은 격자: floor(c / v)). */
    private val exact: List<Vec3> = run {
        val xyz = Projection.backprojectToWorld(depth.depthMm, k, camW, 1)
        (0 until xyz.size / 3).map { i ->
            fun c(a: Float) = (floor(a / v) + 0.5f) * v
            Vec3(c(xyz[3 * i]), c(xyz[3 * i + 1]), c(xyz[3 * i + 2]))
        }.distinct()
    }

    private fun sample(occ: List<Vec3>, labels: List<Element?> = occ.map { Element.OBJECT }) =
        SceneEval.sample(scene, al, v, 8f, occ, labels, SceneEval.occupancyOnly(occ, v), depth, depth.depthMm, 4)

    private fun pool(occ: List<Vec3>, labels: List<Element?> = occ.map { Element.OBJECT }) = SceneEval.Pool(listOf(sample(occ, labels)), v)

    private fun truthOf(w: Vec3) = al.toTruth(w)

    @Test
    fun `exact map has no false cells, small surface error and full completeness`() {
        val p = pool(exact)
        val inRegion = exact.count { scene.inRegion(truthOf(it)) }
        assertTrue(p.occupied > 500 && p.occupied <= inRegion, "occupied ${p.occupied}")
        assertEquals(0, p.false_, "false")
        assertTrue(p.distQuantile(0.9)!! <= tau, "p90 ${p.distQuantile(0.9)}")
        for (el in listOf(Element.FLOOR, Element.WALL, Element.OBJECT)) {
            assertTrue(p.visible(el) > 0, "$el visible")
            assertEquals(1.0, p.completeness(el)!!, 1e-9, "$el completeness")
        }
        assertEquals(0.0, p.erasedRatio(), 1e-9)
        // 점유만 저장하는 지도: 관측한 표면은 모두 점유, 관측한 빈 칸은 모두 모름
        val s = sample(exact)
        assertEquals(s.observedCells - s.visible.sum(), s.unknownCells)
    }

    @Test
    fun `shifting end wall cells toward the camera by k cells gives surface error k cells`() {
        // 끝 벽 칸만(옆 벽·바닥에서 τ 넘게 떨어지고 평가 높이 2 m 안): 밀어도 옆 벽·바닥에 붙지 않는다
        val end = exact.filter { truthOf(it).let { t -> t.z > 4.9f && kotlin.math.abs(t.x) < 0.85f && t.y in 0.1f..1.95f } }
        for (n in 1..3) {
            val shifted = end.map { it - Vec3(0f, 0f, n * v) }
            val p50 = pool(shifted).distQuantile(0.5)!!
            assertEquals(n * v.toDouble(), p50, v.toDouble(), "k=$n p50 $p50")
        }
        // 2칸 넘게 밀면 모두 τ 밖 빈 공간: 전부 헛 칸
        assertEquals(end.size, pool(end.map { it - Vec3(0f, 0f, 2 * v) }).false_)
    }

    @Test
    fun `cells added in free space are counted exactly as false`() {
        val n = 37
        val floating = (0 until n).map { i -> al.toWorld(Vec3(0f, 1.0f + (i / 10) * v, 1.0f + (i % 10) * v)).let { w -> Vec3((floor(w.x / v) + 0.5f) * v, (floor(w.y / v) + 0.5f) * v, (floor(w.z / v) + 0.5f) * v) } }
        val p = pool(exact + floating)
        assertEquals(n, p.false_)
        assertEquals(n.toDouble() / p.occupied, p.falseRatio(), 1e-9)
    }

    @Test
    fun `deleting half of the cells halves completeness`() {
        // 장면과 카메라가 좌우 대칭이라 관측한 표면의 절반이 x < 0 쪽이다. 경계 칸 한 줄이 반대쪽 칸에 덮일 수 있어 ±2%p.
        val half = exact.filter { truthOf(it).x >= 0f }
        val c = pool(half).completeness(*Element.entries.toTypedArray())!!
        assertEquals(0.5, c, 0.02, "completeness $c")
    }

    @Test
    fun `cells inside a solid are inside, not false`() {
        val inWall = al.toWorld(Vec3(-0.95f, 1.0f, 2.5f)) // 벽 두께 가운데: 표면에서 0.05 m > τ
        assertEquals(VoxelClass.INSIDE, SceneEval.classify(scene, truthOf(inWall), tau))
        assertEquals(VoxelClass.FALSE, SceneEval.classify(scene, Vec3(0f, -0.3f, 2.5f), tau)) // 바닥 고체 아래
        assertEquals(VoxelClass.HIT, SceneEval.classify(scene, Vec3(0f, 0.02f, 1.0f), tau))
    }

    @Test
    fun `label confusion counts swapped labels`() {
        // 물체에 맞은 칸에만 벽 라벨, 나머지는 정답 요소 라벨
        val labels = exact.map { w -> scene.nearest(truthOf(w)).second.let { if (it == Element.OBJECT) Element.WALL else it } }
        val p = pool(exact, labels)
        val c = p.confusion()
        val objHits = c[Element.OBJECT.ordinal].sum()
        assertTrue(objHits > 0)
        assertEquals(objHits, c[Element.OBJECT.ordinal][Element.WALL.ordinal])
        val all = c.sumOf { it.sum() }
        assertEquals(1.0 - objHits.toDouble() / all, p.labelAgreement()!!, 1e-9)
    }

    @Test
    fun `structure element types and region`() {
        assertEquals(Element.CEILING, SceneEval.element(box("c", TruthKind.STRUCTURE, Vec3(-1f, 2.5f, 0f), Vec3(1f, 2.6f, 5f))))
        assertEquals(Element.WALL, SceneEval.element(obstacles[0]))
        assertEquals(Element.OBJECT, SceneEval.element(obstacles[3]))
        assertEquals(null, SceneEval.scene(GroundTruth(1, null, false, "", listOf(obstacles[3])), 2f)) // 구조물 없음 = 평가 안 함
        assertTrue(scene.inRegion(Vec3(0f, -0.3f, 1f)) && !scene.inRegion(Vec3(0f, 2.2f, 1f)) && !scene.inRegion(Vec3(1.2f, 1f, 1f)))
    }
}
