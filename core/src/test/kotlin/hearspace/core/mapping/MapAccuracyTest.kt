package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.Noise
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.truth.Alignment
import hearspace.core.truth.GroundTruth
import hearspace.core.truth.MapEval
import hearspace.core.truth.MapEvalStep
import hearspace.core.truth.MapEvalSummary
import hearspace.core.truth.TruthFreeBox
import hearspace.core.truth.TruthKind
import hearspace.core.truth.TruthObstacle
import hearspace.core.types.ConfigLoader
import hearspace.core.types.HeightClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 맵(복셀) 정확도 (IMPROVE_SPEC §6.1, M12). SC-21: 보행선 위 상자(높이 0.6) 뒤 1.8 m에 벽, 평활 깊이 흉내.
 * 평활 깊이가 상자 윗모서리와 벽 사이를 메운 가짜 면(헛 복셀)을 만들고, 거르기가 그것을 지우면서 상자는 남기는지 본다.
 */
class MapAccuracyTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    // 합성 보행: 카메라 (0, 1, 0)에서 월드 −Z로, 머리 = 카메라 + (0, 0.5, −0.3)(진행 방향 기준) → 머리 시작 z = +0.3
    private val al = Alignment(0.0, 0.3, 0.0, -1.0, 0.0, 2.0, false, 0, 0.0, 0.0)
    private val truth = GroundTruth(
        2, "SC-21", false, "",
        listOf(TruthObstacle("box", HeightClass.FLOOR, TruthKind.OBJECT, Vec3(-0.225f, 0f, 2.7f), Vec3(0.225f, 0.6f, 3.0f), null)),
        // 상자 뒤 0.1 m부터 벽 앞 0.2 m까지 통로 가운데(벽은 정답 z 4.5)
        listOf(TruthFreeBox("behind_box", Vec3(-0.3f, 0.1f, 3.1f), Vec3(0.3f, 2.0f, 4.3f))),
    )

    private fun run(spec: SceneSpec, overrides: String): List<MapEvalStep> {
        val config = ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [0, 0.5, -0.3] }, $overrides }""")
        val slow = SlowPath(config)
        return spec.generate().frames.mapNotNull { f ->
            val d = f.depth ?: return@mapNotNull null
            slow.process(d, f.truthHead.headingW)
            val occ = slow.map.voxels.occupied()
            val c = FloatArray(occ.size * 3)
            occ.forEachIndexed { i, v -> c[3 * i] = v.centerW.x; c[3 * i + 1] = v.centerW.y; c[3 * i + 2] = v.centerW.z }
            val head = al.toTruth(f.truthHead.positionW)
            MapEval.step(d.tCaptureNs, c, truth, al, head, config.corridor, config.map.voxelSizeM)
        }
    }

    private fun summary(spec: SceneSpec, overrides: String): MapEvalSummary = MapEval.summary(run(spec, overrides))

    private val off = """ "depth": { "shadowRadiusPx": 0 } """

    @Test
    fun `perfect depth leaves the space behind the box empty`() {
        val s = summary(Scenes.SC21.copy(noise = Noise()), off)
        assertEquals(0, s.phantomMax, "no phantom voxels without smoothing")
        assertTrue(s.objectSeenFraction!! > 0.95, "box seen: ${s.objectSeenFraction}")
    }

    @Test
    fun `smoothed depth builds a phantom surface behind the box`() {
        val s = summary(Scenes.SC21, off)
        assertTrue(s.phantomMean > 20, "phantom mean ${s.phantomMean}") // 실측 약 33(보정 출력)
    }

    @Test
    fun `shadow filter removes most phantom voxels and keeps the box`() {
        val before = summary(Scenes.SC21, off)
        val after = summary(Scenes.SC21, """ "depth": { "shadowRadiusPx": 8, "shadowRatio": 0.2 } """)
        assertTrue(after.phantomMean < 0.4 * before.phantomMean, "phantom ${before.phantomMean} -> ${after.phantomMean}")
        assertEquals(before.objectSeenFraction, after.objectSeenFraction, "box must stay visible")
        assertTrue(after.objectVoxelsMean!! > 0.9 * before.objectVoxelsMean!!, "box voxels ${before.objectVoxelsMean} -> ${after.objectVoxelsMean}")
    }

    @Test
    fun `map eval counts phantom and object voxels against the truth`() {
        // 정답 좌표 → 월드(합성 정렬)로 중심을 만든다: 상자 안 2개, 빈 공간 안 1개, 둘 다 아닌 곳 1개
        val pts = listOf(Vec3(0f, 0.3f, 2.8f), Vec3(0.1f, 0.5f, 2.9f), Vec3(0f, 0.5f, 3.5f), Vec3(1f, 0.5f, 3.5f)).map { al.toWorld(it) }
        val c = FloatArray(pts.size * 3).also { a -> pts.forEachIndexed { i, p -> a[3 * i] = p.x; a[3 * i + 1] = p.y; a[3 * i + 2] = p.z } }
        val cfg = ConfigLoader.load(base).corridor
        val near = MapEval.step(1, c, truth, al, Vec3(0f, 1.5f, 0.5f), cfg, 0.05f)   // 상자까지 2.2 m: 통로 안
        assertEquals(MapEvalStep(1, 4, 1, 2, 1, 1), near)
        val far = MapEval.step(2, FloatArray(0), truth, al, Vec3(0f, 1.5f, -2f), cfg, 0.05f) // 상자까지 4.7 m: 통로 밖
        assertEquals(MapEvalStep(2, 0, 0, 0, 0, 0), far)
        val s = MapEval.summary(listOf(near, far, near.copy(tCaptureNs = 3, objectsSeen = 0, nObject = 0)))
        assertEquals(3, s.nSteps)
        assertEquals(2.0 / 3, s.phantomMean, 1e-9)
        assertEquals(0.5, s.objectSeenFraction!!, 1e-9) // 통로 안 2번 중 1번 보임
        assertEquals(1.0, s.objectVoxelsMean!!, 1e-9)
    }
}
