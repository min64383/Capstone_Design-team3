package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.Scenes
import hearspace.core.types.ConfigLoader
import hearspace.core.types.Obstacle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * 앞면 뒤 감쇠(M24, `map.decayBehind`) 합성 장면 SC-27: 낮은 상자 뒤에 가려지는 키 큰 상자가 지워지지 않는지 본다.
 * 끈 재생(CUR·G)과 켠 재생(O·GO)을 단계마다 비교한다. 합격: 키 큰 상자가 끈 재생에서 잡힌 단계마다 켠 재생에서도 한 물체로 잡히고,
 * 가장 가까운 칸 거리 차 ≤ 0.10 m, 앞 상자의 가장 가까운 칸 거리는 같다(첫 STOP이 같음).
 */
class BehindDecaySceneTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    private class Step(val camZ: Float, val occupied: List<Vec3>, val obstacles: List<Obstacle>)

    private fun run(rgbGuide: String, decayBehind: Boolean): List<Step> {
        val spec = Scenes.SC27
        val g = spec.walk.gripOffsetM
        val slow = SlowPath(
            ConfigLoader.load(
                base,
                """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] },
                     "frontend": { "enabled": false, "rgbGuide": "$rgbGuide", "segment": "NONE" },
                     "map": { "instances": false, "decayBehind": $decayBehind } }""",
            ),
        )
        return spec.generate().frames.mapNotNull { f ->
            val d = f.depth ?: return@mapNotNull null
            val snap = slow.process(d, f.truthHead.headingW)
            Step(d.worldFromCam.translation().z, slow.map.voxels.occupied().map { it.centerW }, snap.obstacles)
        }
    }

    // 월드(보행 −Z): 앞 상자 x ±0.225, z −2.7~−2.4, 높이 0.3. 키 큰 상자 z −3.7~−3.4, 높이 1.0. 칸 중심(0.05 m 칸)으로 센다

    /** 카메라에서 [z0]~[z1] 상자 안 점유 칸까지 가장 가까운 앞 거리, 칸이 없으면 null. */
    private fun nearest(s: Step, z0: Float, z1: Float): Float? =
        s.occupied.filter { abs(it.x) <= 0.25f && it.y in 0.05f..1.05f && it.z in z0..z1 }.minOfOrNull { s.camZ - it.z }

    private fun tall(s: Step) = nearest(s, -3.75f, -3.35f)
    private fun low(s: Step) = nearest(s, -2.75f, -2.35f)

    /** 키 큰 상자에 걸친 물체 수(상자 범위와 겹치는 물체 상자). */
    private fun tallObjects(s: Step) =
        s.obstacles.count { it.aabbMinW.z <= -3.35f && it.aabbMaxW.z >= -3.75f && it.aabbMinW.x <= 0.25f && it.aabbMaxW.x >= -0.25f }

    private fun check(name: String, off: List<Step>, on: List<Step>) {
        var compared = 0
        for ((a, b) in off.zip(on)) {
            val ta = tall(a) ?: continue
            if (tallObjects(a) == 0) continue
            compared++
            val tb = tall(b)
            assertTrue(tb != null && abs(tb - ta) <= 0.10f, "$name tall box nearest cell off $ta on $tb (camera z ${a.camZ})")
            assertTrue(tallObjects(b) in 1..maxOf(1, tallObjects(a)), "$name tall box objects off ${tallObjects(a)} on ${tallObjects(b)} (camera z ${a.camZ})")
        }
        val lowOff = off.map { low(it) }
        val lowOn = on.map { low(it) }
        assertTrue(lowOff.zip(lowOn).all { (x, y) -> x == y || (x != null && y != null && abs(x - y) <= 0.05f) }, "$name low box nearest cell")
        println("SC-27 $name: tall box compared at $compared steps, tall cells max off ${off.maxOf { s -> s.occupied.count { it.z in -3.75f..-3.35f } }} on ${on.maxOf { s -> s.occupied.count { it.z in -3.75f..-3.35f } }}")
        assertTrue(compared > 0, "$name tall box never detected")
    }

    @Test
    fun `SC-27 behind decay keeps a partly occluded box behind a low box`() {
        check("O", run("NONE", false), run("NONE", true))
        check("GO", run("WEIGHTED_MEDIAN", false), run("WEIGHTED_MEDIAN", true))
    }
}
