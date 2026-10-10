package hearspace.core.truth

import hearspace.core.geometry.HeadPose
import hearspace.core.geometry.Vec3
import hearspace.core.pipeline.FastPath
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.Box
import hearspace.core.synth.HorizontalPlane
import hearspace.core.synth.Scene
import hearspace.core.synth.SceneItem
import hearspace.core.synth.Scenes
import hearspace.core.synth.SyntheticGenerator
import hearspace.core.synth.Walk
import hearspace.core.types.ConfigLoader
import hearspace.core.types.HeightClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs
import kotlin.math.sqrt

/** 정답 대입 재생(M12.3 원인 분해): 정답 깊이 S1·S2, 정답 지도 S3, 진행 방향 대입 S4. */
class SubstitutionTest {
    // 합성 장면은 월드 −Z로 걷고 바닥 y = 0이다: 정답 좌표 = (x, y, −z)가 되는 정렬
    private val align = Alignment(0.0, 0.0, 0.0, -1.0, 0.0, 2.0, false, false, 0, 0.0, 0.0)

    private fun truthOf(scene: Scene) = scene.items.mapNotNull { it ->
        val b = it.shape as? Box ?: return@mapNotNull null
        TruthObstacle(it.name, HeightClass.FLOOR, TruthKind.OBJECT, Vec3(b.min.x, b.min.y, -b.max.z), Vec3(b.max.x, b.max.y, -b.min.z), null)
    }

    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(), "{}")

    @Test
    fun `S1 truth depth matches the synthetic renderer`() {
        val scene = Scenes.corridorE(suitcaseM = 2.0f)
        val rec = SyntheticGenerator.generate(scene, Walk(durationS = 4f))
        val truth = truthOf(scene)
        var compared = 0
        for (f in rec.frames.filterIndexed { i, _ -> i % 20 == 0 }) {
            val d = f.depth ?: continue
            val t = TruthDepth.render(d, truth, align)
            for (i in d.depthMm.indices) {
                val a = d.depthMm[i].toInt() and 0xFFFF
                val b = t.depthMm[i].toInt() and 0xFFFF
                assertTrue(abs(a - b) <= 1, "pixel $i: synthetic $a mm vs truth $b mm")
                if (a > 0) compared++
            }
        }
        assertTrue(compared > 10_000, "compared $compared valid pixels")
    }

    @Test
    fun `S2 truth depth noise has the requested relative sigma and is repeatable`() {
        val scene = Scene(listOf(SceneItem("floor", HorizontalPlane(0f), false), SceneItem("wall", Box(Vec3(-3f, 0f, -2.1f), Vec3(3f, 3f, -2f)), true)))
        val d = SyntheticGenerator.generate(scene, Walk(durationS = 0.5f, pitchDownDeg = 0f)).frames.first { it.depth != null }.depth!!
        val truth = truthOf(scene)
        val clean = TruthDepth.render(d, truth, align)
        val noisy = TruthDepth.render(d, truth, align, noisePerM = 0.03f)
        val rel = clean.depthMm.indices.filter { clean.depthMm[it] > 0 && noisy.depthMm[it] > 0 }
            .map { ((noisy.depthMm[it].toInt() and 0xFFFF) - (clean.depthMm[it].toInt() and 0xFFFF)).toDouble() / (clean.depthMm[it].toInt() and 0xFFFF) }
        val mean = rel.average()
        val std = sqrt(rel.sumOf { (it - mean) * (it - mean) } / rel.size)
        assertEquals(0.03, std, 0.003, "relative sigma")
        assertTrue(noisy.depthMm.contentEquals(TruthDepth.render(d, truth, align, noisePerM = 0.03f).depthMm), "same seed, same image")
    }

    @Test
    fun `S3 truth map gives one obstacle at the corridor-nearest point of the box in the corridor`() {
        val inside = TruthObstacle("box", HeightClass.FLOOR, TruthKind.OBJECT, Vec3(-0.2f, 0f, 2.0f), Vec3(0.2f, 0.6f, 2.3f), null)
        val outside = TruthObstacle("far_right", HeightClass.FLOOR, TruthKind.OBJECT, Vec3(1.5f, 0f, 1.0f), Vec3(1.9f, 0.6f, 1.4f), null)
        val sub = Substitution(GroundTruth(2, "S", false, "", listOf(inside, outside)), align, map = true)
        val slow = SlowPath(config, sub.fixedMap(config.map.voxelSizeM))
        val frames = SyntheticGenerator.generate(Scene(listOf(SceneItem("floor", HorizontalPlane(0f), false))), Walk(durationS = 0.5f)).frames
        val heading = Vec3(0f, 0f, -1f)
        val snaps = frames.mapNotNull { it.depth }.take(3).map { slow.process(it, heading) }
        val obs = snaps.last().obstacles
        assertEquals(1, obs.size, "obstacles $obs")
        val head = HeadPose.fromCamera(frames.first().depth!!.worldFromCam.translation(), heading, config.head.offsetFromCameraM)
        val rep = align.toTruth(obs.single().repPointW)
        val headT = align.toTruth(head.positionW)
        assertEquals(2.0f, rep.z, 0.05f, "rep along the walk line")
        assertEquals(headT.x, rep.x, 0.05f, "rep on the head's line")
    }

    @Test
    fun `S4 heading substitution snaps a tilted heading to the walk line`() {
        val sub = Substitution(GroundTruth(2, "S", false, "", emptyList()), align, heading = true)
        val fast = FastPath(config, sub.headingSnap())
        val plain = FastPath(config)
        val rec = SyntheticGenerator.generate(Scene(listOf(SceneItem("floor", HorizontalPlane(0f), false))), Walk(durationS = 4f, headingDeg = 5f))
        for (f in rec.frames) {
            fast.compute(f.pose, null, f.pose.tCaptureNs)
            plain.compute(f.pose, null, f.pose.tCaptureNs)
        }
        val h = fast.headingW!!
        assertEquals(0f, h.x, 1e-6f)
        assertEquals(-1f, h.z, 1e-6f) // 정답 +z = 월드 −Z
        assertTrue(abs(plain.headingW!!.x) > 0.05f, "without substitution the 5° tilt stays: ${plain.headingW}")
        val back = sub.headingSnap()!!(Vec3(0.087f, 0f, 0.996f))
        assertEquals(0f, back.x, 1e-6f)
        assertEquals(1f, back.z, 1e-6f, "walking back snaps to the opposite direction")
    }
}
