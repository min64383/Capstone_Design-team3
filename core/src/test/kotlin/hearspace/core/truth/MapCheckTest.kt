package hearspace.core.truth

import hearspace.core.geometry.Mat4
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.synth.SyntheticGenerator
import hearspace.core.types.ConfigLoader
import hearspace.core.types.DepthFrame
import hearspace.core.types.HeightClass
import hearspace.core.types.JsonNull
import hearspace.core.types.JsonNumber
import hearspace.core.types.JsonObject
import hearspace.core.types.MiniJson
import hearspace.core.types.PoseFrame
import hearspace.core.types.TrackingState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.floor

/**
 * 정답 없는 복셀 지도 검사(M33 [MapCheck], A1 MA2): 알려진 답이 있는 작은 합성 장면. 장면은 [SceneEvalTest]와 같은 복도(폭 1.8 m, 끝 벽 5 m,
 * 2 m 앞 상자)이고, 카메라는 높이 1 m에서 앞을 보며 3 s 동안 +z로 1.5 m 걷는다(머리 = 카메라). 나중 장은 시작 자리의 정답 깊이 한 장.
 * - 걸어갈 자리(머리 높이) 허공의 칸 하나: 발자국 헛 칸 1, 나중 장 모순 1/1(그 화소는 끝 벽까지 본다).
 * - 정답 깊이를 그대로 넣은 칸(표면): 나중 장 모순 거의 없음.
 * - 나중 자세·나중 장이 없으면 비율은 null, 한 방향 보행이면 왕복 일관성은 null.
 */
class MapCheckTest {
    private val v = 0.05f
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(), "{}")
    private fun box(name: String, kind: TruthKind, min: Vec3, max: Vec3) = TruthObstacle(name, HeightClass.FLOOR, kind, min, max, null)
    private val obstacles = listOf(
        box("wall_left", TruthKind.STRUCTURE, Vec3(-1.0f, 0f, 0f), Vec3(-0.9f, 2.4f, 5f)),
        box("wall_right", TruthKind.STRUCTURE, Vec3(0.9f, 0f, 0f), Vec3(1.0f, 2.4f, 5f)),
        box("wall_end", TruthKind.STRUCTURE, Vec3(-1.0f, 0f, 5f), Vec3(1.0f, 2.4f, 5.1f)),
        box("box", TruthKind.OBJECT, Vec3(-0.2f, 0f, 2f), Vec3(0.2f, 0.6f, 2.3f)),
    )
    private val scene = SceneEval.scene(GroundTruth(2, "test", false, "", obstacles), 2.0f)!!
    private val al = Alignment(0.0, 0.0, 0.0, 1.0, 0.0, 0.0, false, false, 0, 0.0, 0.0)
    private val k = SyntheticGenerator.DEPTH_K
    private fun cam(z: Float) = Mat4.fromAxes(Vec3(1f, 0f, 0f), Vec3(0f, -1f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 1f, z))
    private val depth = TruthDepth.render(DepthFrame(1L, ShortArray(k.width * k.height) { 1 }, null, k, cam(0.05f), "test"), obstacles, al)

    /** 0.1 s마다 0.5 m/s로 +z(0 ~ 3 s). */
    private val walk = (0..30).map { i -> PoseFrame(i * 100_000_000L, TrackingState.TRACKING, cam(0.05f + 0.05f * i)) }
    private val later = mapOf("smoothed" to (false to listOf(depth.copy(tCaptureNs = 1_000_000_000L))))

    private fun center(a: Float) = (floor(a / v) + 0.5f) * v

    private fun check(occ: List<Vec3>, poses: List<PoseFrame> = walk, tests: Map<String, Pair<Boolean, List<DepthFrame>>> = later): JsonObject {
        val snap = MapCheck.Snapshot(0L, occ, LongArray(occ.size))
        return MiniJson.parse(
            MapCheck.run(listOf(snap), poses, tests, config.depth, al, scene, v, 8f, Vec3(0f, 0f, 0f), config.map.tsdfMinCosIncidence),
        ) as JsonObject
    }

    private fun JsonObject.count(vararg path: String): JsonObject =
        path.fold(this) { o, k -> o.fields.getValue(k) as JsonObject }

    private fun JsonObject.num(k: String) = (fields.getValue(k) as JsonNumber).value

    @Test
    fun `a floating cell on the walked path is a footprint cell and a later-frame contradiction`() {
        val ghost = Vec3(center(0.01f), center(1.01f), center(1.01f)) // 머리 높이(카메라 1 m 위 0.1 m 안), 걸어갈 자리
        val j = check(listOf(ghost))
        val foot = j.count("footprint")
        assertEquals(1.0, foot.num("num"))
        assertTrue(foot.num("den") > 1.0, "footprint space ${foot.num("den")}")
        val c = j.count("contradiction", "smoothed")
        assertEquals(1.0, c.num("num"))
        assertEquals(1.0, c.num("den"))
        assertEquals(1.0, c.num("ratio"), 1e-9)
        assertEquals(JsonNull, j.fields["roundTripM"]) // 한 방향 보행
    }

    @Test
    fun `exact surface cells are rarely contradicted and are off the walked path`() {
        val xyz = Projection.backprojectToWorld(depth.depthMm, k, cam(0.05f), 1)
        val exact = (0 until xyz.size / 3).map { i -> Vec3(center(xyz[3 * i]), center(xyz[3 * i + 1]), center(xyz[3 * i + 2])) }.distinct()
        val j = check(exact)
        val c = j.count("contradiction", "smoothed")
        assertTrue(c.num("den") > 500, "tested ${c.num("den")}")
        assertTrue(c.num("ratio") <= 0.01, "contradiction ${c.num("ratio")}")
        // 표면 칸(벽·바닥·2 m 앞 상자)은 1.5 m만 걷는 몸 공간(바닥 띠 위, 머리 둘레 0.2 m) 밖이다
        assertEquals(0.0, j.count("footprint").num("num"))
    }

    @Test
    fun `without later poses or later frames the ratios are null`() {
        val ghost = Vec3(center(0.01f), center(1.01f), center(1.01f))
        val j = check(listOf(ghost), poses = walk.take(1), tests = emptyMap())
        assertEquals(JsonNull, j.count("footprint").fields["ratio"])
        assertEquals(0.0, j.count("footprint").num("den"))
        assertTrue(j.count("contradiction").fields.isEmpty())
        assertEquals(JsonNull, j.fields["roundTripM"])
    }
}
