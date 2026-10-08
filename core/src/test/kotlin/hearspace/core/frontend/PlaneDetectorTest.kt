package hearspace.core.frontend

import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.mapping.Floor
import hearspace.core.synth.HorizontalPlane
import hearspace.core.synth.Noise
import hearspace.core.synth.Scene
import hearspace.core.synth.SceneItem
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.synth.SyntheticGenerator
import hearspace.core.synth.Walk
import hearspace.core.types.Config
import hearspace.core.types.ConfigException
import hearspace.core.types.ConfigLoader
import hearspace.core.types.FloorSource
import hearspace.core.types.PlaneMode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import kotlin.math.abs
import kotlin.math.hypot

/** 평면 추출(IMPROVE_SPEC §6.1.1 M13.1c): 합성 장면의 깊이 한 장에서 바닥·벽. */
class PlaneDetectorTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val config: Config = ConfigLoader.load(base, """{ "frontend": { "planes": "RANSAC" } }""")
    private val cfg = config.frontend

    /** [spec]의 t초 근처 깊이 한 장의 월드 점(참 자세)과 카메라 위치. */
    private fun frame(spec: SceneSpec, t: Float): Pair<FloatArray, Vec3> {
        val f = spec.generate().frames.first { it.tS >= t && it.depth != null }
        val d = f.depth!!
        return Projection.backprojectToWorld(d.depthMm, d.K, f.truthWorldFromCam, config.depth.subsample) to f.truthWorldFromCam.translation()
    }

    private fun <T : Any> need(v: T?, what: String = "value"): T = v ?: throw AssertionError("expected $what, got null")

    private fun detect(spec: SceneSpec, t: Float): PlaneResult = frame(spec, t).let { (p, cam) -> PlaneDetector.detect(p, cam, cfg, config.map.radiusM) }

    @Test
    fun `floor height is recovered on a floor-only scene`() {
        val r = detect(Scenes.SC01, 1f)
        val fl = need(r.floor)
        assertEquals(0f, fl.heightM, 0.03f)
        assertTrue(fl.nInliers >= cfg.floorMinPoints && fl.spreadM >= cfg.floorMinSpreadM, "inliers ${fl.nInliers} spread ${fl.spreadM}")
        assertTrue(r.walls.isEmpty())
    }

    @Test
    fun `floor survives depth noise and a hand-held bob`() {
        val spec = SceneSpec("noisy", Scenes.corridorE(), Walk(durationS = 3f, bobAmpM = 0.02f, wristYawAmpDeg = 10f), Noise(seed = 5, depthMulStd = 0.02f, invalidRatio = 0.1f))
        for (t in listOf(0.5f, 2.5f)) assertEquals(0f, need(detect(spec, t).floor).heightM, 0.05f, "t=$t")
    }

    @Test
    fun `corridor walls are found as vertical planes at their positions`() {
        val r = detect(SceneSpec("corridor", Scenes.corridorE(), Walk(durationS = 3f)), 0.5f)
        assertEquals(0f, need(r.floor).heightM, 0.03f)
        assertTrue(r.walls.size >= 2, "walls ${r.walls}")
        // 옆 벽 x = ±0.52(월드, 걷는 방향 −Z), 끝 벽 z = −4
        val side = r.walls.filter { abs(it.x1 - it.x0) < 0.2f }.map { (it.x0 + it.x1) / 2 }.sorted()
        assertTrue(side.size >= 2 && abs(side.first() + 0.52f) < 0.06f && abs(side.last() - 0.52f) < 0.06f, "side walls x $side")
        assertTrue(r.walls.all { it.rmsM < 0.06f }, "rms ${r.walls.map { it.rmsM }}")
        // 평면에 든 점에 라벨이 붙는다
        assertTrue(r.labels.count { it == PlaneResult.FLOOR } == r.floor!!.nInliers)
        assertTrue(r.labels.count { it == PlaneResult.WALL } == r.walls.sumOf { it.nInliers })
    }

    @Test
    fun `facing an end wall with the floor out of view gives no floor`() {
        // 끝 벽(월드 z −4) 1 m 앞, 수평으로 바라봄: 바닥은 카메라에서 1.5 m 밖부터 보이므로 시야에 없다(M12.0에서 히스토그램 바닥이 벽을 타고 올라간 상황)
        val spec = SceneSpec("end", Scenes.corridorE(), Walk(startCameraW = Vec3(0f, 1f, -3f), pitchDownDeg = 0f, standS = 10f, durationS = 1f))
        val (p, cam) = frame(spec, 0.3f)
        val r = PlaneDetector.detect(p, cam, cfg, config.map.radiusM)
        assertNull(r.floor, "wall points must not become a floor")
        // 이 거리에서는 가로 시야(약 ±20°)로 끝 벽이 0.7 m만, 옆 벽은 전혀 안 보여 벽 평면도 없다(`wallMinLengthM` 0.8)
        assertTrue(r.walls.isEmpty(), "walls ${r.walls}")
        // 기준선(히스토그램)은 같은 점에서 바닥을 벽 위 어딘가에 잡는다
        val hist = Floor(config.floor, config.map.radiusM).update(p, cam)
        assertTrue(hist.floorY != null && hist.floorY!! > 0.2f, "histogram floor on the wall: ${hist.floorY}")
        // 평면 바닥은 이전 값을 유지한다
        val f = Floor(config.floor, config.map.radiusM)
        f.updateFromPlane(0.01f, 400)
        assertEquals(0.01f, f.updateFromPlane(r.floor?.heightM, 0).floorY!!, 1e-6f)
    }

    @Test
    fun `a low box top with many points is not the floor`() {
        // 카메라(1 m)보다 0.6 m 아래에 윗면이 크게 보이는 낮은 상자(높이 0.4, 깊이 0.8)를 2.2 m 앞에: 바닥은 0
        val spec = SceneSpec("lowbox", Scene(listOf(SceneItem("floor", HorizontalPlane(0f), false), Scenes.boxOnLine(2.2f, heightM = 0.4f, depthM = 0.8f))), Walk(durationS = 3f))
        val r = detect(spec, 0.5f)
        val fl = need(r.floor, "floor plane")
        assertEquals(0f, fl.heightM, 0.05f)
        assertTrue(fl.liftPerM < 0.05f, "lift ${fl.liftPerM}")
    }

    @Test
    fun `a floor that lifts with distance is fitted by its line, not rejected as a narrow plane`() {
        // M13.1c S02 실측: 평활 깊이의 바닥은 멀수록 위로 들린다(수평 평면으로는 가까운 띠만 맞아 폭 기준에 걸림). 합성: 점을 직접 만든다
        val pts = ArrayList<Float>()
        val rnd = java.util.Random(3)
        for (i in 0 until 1500) {
            val d = 1.6f + rnd.nextFloat() * 2.4f // 수평거리 1.6~4.0 m
            val x = (rnd.nextFloat() - 0.5f) * 1.2f
            pts += x; pts += -1.1f + 0.09f * d + (rnd.nextFloat() - 0.5f) * 0.03f; pts += -hypot(d * d - x * x, 0f).let { kotlin.math.sqrt(it) }
        }
        val r = PlaneDetector.detect(pts.toFloatArray(), Vec3(0f, 0f, 0f), cfg, config.map.radiusM)
        val fl = need(r.floor, "floor plane")
        // 보고 높이는 점들의 중앙 거리(d 약 2.8 m)에서의 값: −1.1 + 0.09 × 2.8
        assertEquals(-1.1f + 0.09f * 2.8f, fl.heightM, 0.05f)
        assertEquals(0.09f, fl.liftPerM, 0.03f)
    }

    @Test
    fun `same points give the same planes`() {
        val (p, cam) = frame(Scenes.SC14, 1f)
        val a = PlaneDetector.detect(p, cam, cfg, config.map.radiusM)
        val b = PlaneDetector.detect(p, cam, cfg, config.map.radiusM)
        assertEquals(a.floor, b.floor)
        assertEquals(a.walls, b.walls)
        assertTrue(a.labels.contentEquals(b.labels))
    }

    @Test
    fun `floor source plane without planes is rejected`() {
        assertThrows<ConfigException> { ConfigLoader.load(base, """{ "floor": { "source": "PLANE" }, "frontend": { "planes": "NONE" } }""") }
        ConfigLoader.load(base, """{ "floor": { "source": "PLANE" }, "frontend": { "planes": "RANSAC" } }""")
        // 기본값(`-PtestOverrides`가 없을 때)은 꺼짐
        if (System.getProperty("hearspace.defaultConfig").endsWith("app/src/main/assets/config/default.json") || System.getProperty("hearspace.defaultConfig").contains("assets")) {
            assertEquals(PlaneMode.NONE, ConfigLoader.load(base).frontend.planes)
            assertEquals(FloorSource.HISTOGRAM, ConfigLoader.load(base).floor.source)
        }
    }

    @Test
    fun `plane update holds without a plane, smooths inside the band and accepts a lasting step`() {
        val f = Floor(config.floor, config.map.radiusM)
        assertNull(f.updateFromPlane(null, 0).floorY)
        assertEquals(-1f, f.updateFromPlane(-1f, 300).floorY!!, 1e-6f)
        assertEquals(-1f, f.updateFromPlane(null, 0).floorY!!, 1e-6f) // 안 보이면 유지
        assertEquals(-0.98f, f.updateFromPlane(-0.9f, 300).floorY!!, 1e-4f) // 지수 평활(0.2)
        // 밴드(0.5 m)를 벗어난 평면은 lostFrames(10)장 이어져야 받는다
        repeat(config.floor.lostFrames - 1) { assertEquals(-0.98f, f.updateFromPlane(-0.3f, 300).floorY!!, 1e-4f) }
        assertEquals(-0.3f, f.updateFromPlane(-0.3f, 300).floorY!!, 1e-6f)
    }
}
