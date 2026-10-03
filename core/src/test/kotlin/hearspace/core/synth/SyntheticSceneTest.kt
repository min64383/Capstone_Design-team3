package hearspace.core.synth

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import hearspace.core.geometry.Conventions
import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.types.TrackingState
import kotlin.math.abs

class SyntheticSceneTest {

    /** 역투영 점이 도형 표면 위에 있는지 (M2 완료 기준: 오차 < 1 cm). 참 자세로 역투영한다. */
    private fun maxSurfaceErrorM(spec: SceneSpec, everyNthFrame: Int = 10): Pair<Float, Int> {
        val rec = spec.generate()
        var worst = 0f
        var n = 0
        for (f in rec.frames.filterIndexed { i, _ -> i % everyNthFrame == 0 }) {
            val d = f.depth ?: continue
            val pts = Projection.backprojectToWorld(d.depthMm, d.K, f.truthWorldFromCam, 1)
            for (i in 0 until pts.size / 3) {
                val e = rec.scene.surfaceDistance(Vec3(pts[3 * i], pts[3 * i + 1], pts[3 * i + 2]), f.tS)
                if (e > worst) worst = e
                n++
            }
        }
        return worst to n
    }

    @Test
    fun `backprojected points lie on shape surfaces within 1 cm`() {
        val pillar = SceneSpec(
            "pillar",
            Scene(
                listOf(
                    SceneItem("floor", HorizontalPlane(0f), false),
                    SceneItem("pillar", VerticalCylinder(0.1f, -2.5f, 0.025f, 0f, 2.2f), true),
                    Scenes.boxOnLine(3.5f),
                ),
            ),
            Walk(durationS = 3f, bobAmpM = 0.02f, wristYawAmpDeg = 10f),
        )
        for (spec in listOf(Scenes.SC02, Scenes.SC03, Scenes.SC05, Scenes.SC06, Scenes.SC08, pillar)) {
            val (err, n) = maxSurfaceErrorM(spec)
            assertTrue(n > 10_000, "${spec.id}: too few points ($n)")
            // 1 mm 양자화 + 픽셀 격자 → 수 mm 이내가 정상. 기준은 1 cm.
            assertTrue(err < 0.01f, "${spec.id}: max surface error ${err * 1000} mm over $n points")
        }
    }

    @Test
    fun `thin pillar is actually hit`() {
        val scene = Scene(listOf(SceneItem("floor", HorizontalPlane(0f), false), SceneItem("pillar", VerticalCylinder(0f, -2f, 0.025f, 0f, 2.2f), true)))
        val rec = SyntheticGenerator.generate(scene, Walk(durationS = 0.1f))
        val d = rec.frames[0].depth!!
        val pts = Projection.backprojectToWorld(d.depthMm, d.K, rec.frames[0].truthWorldFromCam, 1)
        val onPillar = (0 until pts.size / 3).count { i -> abs(pts[3 * i + 2] + 2f) < 0.03f && pts[3 * i + 1] > 0.05f }
        assertTrue(onPillar >= 20, "pillar pixels: $onPillar")
    }

    @Test
    fun `portrait hold matches F2 measurement`() {
        // F2: 세로 파지에서 센서 +X(GL)는 월드 아래, +Y(GL)는 진행 방향 오른쪽, 시선(−Z)은 진행 방향
        val (cv, head) = SyntheticGenerator.cameraPose(Walk(pitchDownDeg = 0f), 0f)
        val gl = Conventions.cvToGl(cv)
        val right = head.rightW
        assertEquals(-1f, gl.axis(0).y, 1e-5f)
        assertEquals(1f, gl.axis(1) dot right, 1e-5f)
        assertEquals(1f, -gl.axis(2) dot head.headingW, 1e-5f)
        // C_cv: +Z 앞, +X(이미지 오른쪽) = 월드 아래
        assertEquals(1f, cv.axis(2) dot head.headingW, 1e-5f)
        assertEquals(-1f, cv.axis(0).y, 1e-5f)
    }

    @Test
    fun `walk has stand phase then 1 m per second`() {
        val rec = SyntheticGenerator.generate(Scene(listOf(SceneItem("floor", HorizontalPlane(0f), false))), Walk(durationS = 4f), depthEveryNFrames = 1000)
        val p = rec.frames.associate { it.tS to it.truthWorldFromCam.translation() }
        val at = { t: Float -> p.entries.minBy { abs(it.key - t) }.value }
        assertEquals(0f, (at(1.9f) - at(0f)).norm(), 1e-5f)
        assertEquals(1f, (at(3.5f) - at(2.5f)).norm(), 0.01f)
        assertEquals(1f, at(3f).y, 1e-5f) // 카메라 높이 1 m
        assertEquals(1.5f, rec.frames[0].truthHead.positionW.y, 1e-5f) // 기본 파지: 머리는 0.5 m 위
    }

    @Test
    fun `same seed gives identical output`() {
        val noisy = Scenes.SC02.copy(noise = Noise(seed = 7, depthMulStd = 0.02f, invalidRatio = 0.1f, posePosStdM = 0.01f))
        val a = noisy.generate()
        val b = noisy.generate()
        assertEquals(a.frames.size, b.frames.size)
        for (i in a.frames.indices) {
            assertEquals(a.frames[i].pose, b.frames[i].pose)
            assertEquals(a.frames[i].depth, b.frames[i].depth)
        }
        assertNotEquals(a.frames[70].depth, noisy.copy(noise = noisy.noise.copy(seed = 8)).generate().frames[70].depth)
    }

    @Test
    fun `noise options have their intended effect`() {
        val clean = Scenes.SC02.generate()
        val center = { r: SyntheticRecording, i: Int -> r.frames[i].depth!!.let { it.depthMm[45 * 160 + 80].toInt() } }

        val biased = Scenes.SC11.generate()
        assertEquals(center(clean, 0) * 1.1f, center(biased, 0).toFloat(), 2f)

        val invalid = Scenes.SC02.copy(noise = Noise(invalidRatio = 0.3f)).generate()
        // 무효화는 원래 값이 있던 픽셀에만 적용된다(광선이 아무것도 맞히지 않은 픽셀은 원래 0)
        val cleanValid = clean.frames[0].depth!!.depthMm.count { it.toInt() != 0 }
        val zeros = invalid.frames[0].depth!!.depthMm.count { it.toInt() == 0 } - clean.frames[0].depth!!.depthMm.count { it.toInt() == 0 }
        assertEquals(0.3f, zeros.toFloat() / cleanValid, 0.03f)

        val dropped = Scenes.SC02.copy(noise = Noise(frameDropRatio = 0.2f)).generate()
        assertEquals(clean.frames.size * 0.8f, dropped.frames.size.toFloat(), clean.frames.size * 0.08f)

        val lost = Scenes.SC10.generate()
        val lostFrames = lost.frames.filter { it.pose.tracking == TrackingState.PAUSED }
        assertTrue(lostFrames.size in 29..31, "lost frames ${lostFrames.size}")
        assertTrue(lostFrames.all { it.depth == null })

        val jump = Scenes.SC12.generate()
        val i = jump.frames.indexOfFirst { it.tS >= 2.5f }
        val step = (jump.frames[i].pose.worldFromCam.translation() - jump.frames[i - 1].pose.worldFromCam.translation()).norm()
        assertTrue(step > 2f, "jump step $step m")
        assertTrue(jump.frames.all { it.pose.tracking == TrackingState.TRACKING }, "jump keeps TRACKING")
        // 참 자세는 연속
        val trueStep = (jump.frames[i].truthWorldFromCam.translation() - jump.frames[i - 1].truthWorldFromCam.translation()).norm()
        assertTrue(trueStep < 0.05f)

        val frozen = Scenes.SC13.generate()
        val inFreeze = frozen.frames.filter { it.tS in 1.0f..2.0f }.mapNotNull { it.depth?.tCaptureNs }.toSet()
        assertEquals(1, inFreeze.size, "depth timestamp must repeat during freeze")
        assertTrue(inFreeze.first() < frozen.frames.first { it.tS >= 1.0f }.pose.tCaptureNs)

        val poseNoise = Scenes.SC02.copy(noise = Noise(posePosStdM = 0.01f)).generate()
        val dev = poseNoise.frames.map { (it.pose.worldFromCam.translation() - it.truthWorldFromCam.translation()).norm() }
        assertTrue(dev.average() in 0.008..0.025, "pose noise mean ${dev.average()}")
    }

    @Test
    fun `removed box disappears from depth`() {
        val rec = Scenes.SC04.generate()
        val before = rec.frames.first { it.tS >= 2.9f }.depth!!.depthMm[45 * 160 + 80]
        val after = rec.frames.first { it.tS >= 3.1f }.depth!!.depthMm[45 * 160 + 80]
        assertTrue(after > before + 500, "before $before after $after")
    }

    @Test
    fun `all appendix B scenes generate`() {
        val ids = Scenes.ALL.map { it.id }
        assertEquals((1..14).map { "SC-%02d".format(it) }, ids)
        for (s in Scenes.ALL) assertTrue(s.generate().frames.any { it.depth != null }, s.id)
    }
}
