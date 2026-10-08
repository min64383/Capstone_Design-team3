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
    fun `round trip walk turns in place around the head and comes back`() {
        val w = Walk(standS = 1f, legM = 2f, turnS = 2f, durationS = 9f)
        fun at(t: Float) = SyntheticGenerator.cameraPose(w, t)
        val h0 = at(0f).second
        // 갈 때 끝(3 s): 머리는 보행선 2 m 앞
        assertEquals(2f, (at(3f).second.positionW - h0.positionW).norm(), 1e-4f)
        // 회전 중(3~5 s) 머리는 제자리, 카메라는 머리 둘레로 돌아 앞뒤 오프셋(0.3 m)의 두 배만큼 옮겨진다
        assertEquals(0f, (at(5f).second.positionW - at(3f).second.positionW).norm(), 1e-4f)
        assertEquals(0.6f, (at(5f).first.translation() - at(3f).first.translation()).norm(), 1e-3f)
        // 되돌아와(7 s) 머리는 시작 자리, 방향은 반대
        assertEquals(0f, (at(7f).second.positionW - h0.positionW).norm(), 1e-4f)
        assertEquals(-1f, at(7f).second.headingW dot h0.headingW, 1e-4f)
        // 걸은 거리는 회전 중에 늘지 않는다
        assertEquals(2f, SyntheticGenerator.legState(w, 4f).walkedM, 1e-5f)
        assertEquals(4f, SyntheticGenerator.legState(w, 7f).walkedM, 1e-4f)
        assertTrue(!SyntheticGenerator.legState(w, 4f).moving && SyntheticGenerator.legState(w, 6f).moving)
        // 구간 수를 다 걸으면 마지막 자리·방향으로 선다
        val two = w.copy(legCount = 2)
        assertEquals(0f, (SyntheticGenerator.cameraPose(two, 8.5f).second.positionW - h0.positionW).norm(), 1e-4f)
        assertEquals(-1f, SyntheticGenerator.cameraPose(two, 8.5f).second.headingW dot h0.headingW, 1e-4f)
        assertTrue(!SyntheticGenerator.legState(two, 8.5f).moving)
        // legM이 없으면 기존 한 방향 보행과 같은 자세
        val straight = Walk(standS = 1f, durationS = 4f)
        assertEquals(SyntheticGenerator.cameraPose(straight, 2.5f).first, SyntheticGenerator.cameraPose(straight.copy(legM = null), 2.5f).first)
    }

    @Test
    fun `cumulative drift grows with walked distance and not while standing`() {
        val noise = Noise(yawDriftDegPerM = 2f, posDriftPerM = Vec3(0.01f, 0f, 0f))
        val rec = SyntheticGenerator.generate(Scenes.SC01.scene, Walk(durationS = 5f), noise, depthEveryNFrames = 1000)
        fun drift(t: Float): Pair<Float, Vec3> {
            val f = rec.frames.minBy { abs(it.tS - t) }
            val d = f.pose.worldFromCam * f.truthWorldFromCam.rigidInverse() // 보고 = d × 참
            val v = d.transformDir(Vec3(0f, 0f, -1f))
            return Math.toDegrees(kotlin.math.atan2(-v.x, -v.z).toDouble()).toFloat() to d.translation()
        }
        val (yaw0, p0) = drift(1f) // 정지 중
        assertEquals(0f, yaw0, 1e-3f)
        assertEquals(0f, p0.norm(), 1e-5f)
        val (yaw, p) = drift(4.5f) // 2.5 m 걸음
        assertEquals(5f, yaw, 0.1f)
        assertEquals(0.025f, p.x, 1e-3f)
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
    fun `edge smoothing turns a depth step into a short ramp and barely changes surfaces without steps`() {
        // SC-21 첫 프레임, 가운데 행: 세로 파지라 u가 커지면 월드 아래(F2). 위에서부터 벽(약 4.3 m) → 상자 앞면(약 2.4 m) → 바닥
        fun between(d: ShortArray) = (0 until 160).count { u -> (d[45 * 160 + u].toInt() and 0xFFFF) in 2800..4000 }
        val clean = Scenes.SC21.copy(noise = Noise()).generate().frames[0].depth!!.depthMm
        val smooth = Scenes.SC21.generate().frames[0].depth!!.depthMm
        assertEquals(0, between(clean), "no depth between the box and the wall without smoothing")
        val ramp = between(smooth)
        assertTrue(ramp in 2..2 * Scenes.EDGE_SMOOTH_PX, "ramp $ramp px")
        // 불연속이 없는 바닥(5 m 안): 바닥은 행마다 깊이가 빠르게 늘어 창 안 차이가 10%를 넘는 곳도 평균되지만, 깊이가 픽셀에
        // 대해 거의 선형이라 바뀌는 양은 0.5% 이내다
        val floorClean = Scenes.SC01.generate().frames[0].depth!!.depthMm
        val floorSmooth = Scenes.SC01.copy(noise = Noise(edgeSmoothPx = Scenes.EDGE_SMOOTH_PX)).generate().frames[0].depth!!.depthMm
        val near = floorClean.indices.filter { (floorClean[it].toInt() and 0xFFFF) in 1 until 5000 }
        val maxRel = near.maxOf { kotlin.math.abs((floorSmooth[it].toInt() and 0xFFFF) - (floorClean[it].toInt() and 0xFFFF)).toFloat() / (floorClean[it].toInt() and 0xFFFF) }
        assertTrue(near.size > 5000 && maxRel < 0.005f, "near floor max relative change $maxRel over ${near.size} px")
    }

    @Test
    fun `all appendix B scenes generate`() {
        val ids = Scenes.ALL.map { it.id }
        assertEquals((1..14).map { "SC-%02d".format(it) } + listOf("SC-21", "SC-22", "SC-23"), ids)
        for (s in Scenes.ALL) assertTrue(s.generate().frames.any { it.depth != null }, s.id)
    }
}
