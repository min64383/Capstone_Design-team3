package hearspace.core.pipeline

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
import hearspace.core.types.Obstacle
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 인스턴스 지도(IMPROVE_SPEC §6 C3b, `map.instances`). 기준선은 같은 앞단(경계 판정)에서 인스턴스·영역 분할만 끈다.
 * 지도는 HITS(거리 가중 없음), 벽 분리·RGB 보정은 끈다(합성은 밝기 대비가 없어 RGB 보정이 경계 픽셀을 임의 쪽에 붙인다)(`-PtestOverrides`로 다른 설정을 켠 실행에서도 C3b만 보게).
 */
class InstanceMapTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val common = """ "map": { "mode": "HITS", "hitWeighting": "NONE", "instances": %s }, "cluster": { "separateWalls": false } """
    private val off = """ "frontend": { "enabled": true, "rgbGuide": "NONE", "segment": "NONE" }, ${common.format("false")} """
    private val on = """ "frontend": { "enabled": true, "rgbGuide": "NONE", "segment": "REGION" }, ${common.format("true")} """

    private fun slow(spec: SceneSpec, overrides: String): SlowPath {
        val g = spec.walk.gripOffsetM
        return SlowPath(ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }, $overrides }"""))
    }

    private fun run(spec: SceneSpec, overrides: String): List<List<Obstacle>> {
        val s = slow(spec, overrides)
        return spec.generate().frames.mapNotNull { f -> f.depth?.let { s.process(it, f.truthHead.headingW).obstacles } }
    }

    private fun box(name: String, min: Vec3, max: Vec3) = SceneItem(name, Box(min, max), obstacle = true, expectedClass = HeightClass.FLOOR)

    @Test
    fun `two staggered boxes 7 cm apart from above are two obstacles with instances, one without`() {
        // 왼쪽 상자 앞면 z −2.0, 오른쪽 상자는 0.35 m 뒤(앞면 z −2.35)에 엇갈려 있다. 위에서 보면 모서리 사이가 약 7 cm라 거리로만 묶으면
        // 한 덩어리지만, 깊이 영상에서는 왼쪽 상자 오른쪽 끝과 오른쪽 상자 앞면 사이 깊이가 0.35 m(17%) 끊긴다
        val scene = Scene(
            listOf(
                SceneItem("floor", HorizontalPlane(0f), obstacle = false),
                box("left", Vec3(-0.4f, 0f, -2.3f), Vec3(-0.05f, 0.5f, -2.0f)),
                box("right", Vec3(0.0f, 0f, -2.65f), Vec3(0.35f, 0.5f, -2.35f)),
            ),
        )
        val spec = SceneSpec("staggered", scene, Walk(durationS = 2f + 1.0f))
        fun spansBoth(o: Obstacle) = o.aabbMinW.x < -0.2f && o.aabbMaxW.x > 0.15f
        fun separate(o: List<Obstacle>) = o.any { it.aabbMaxW.x < 0f && it.aabbMinW.z > -2.4f } && o.any { it.aabbMinW.x > -0.05f && it.aabbMinW.z < -2.5f }
        val baseline = run(spec, off).takeLast(10)
        val instances = run(spec, on).takeLast(10)
        println("staggered: baseline merged ${baseline.count { s -> s.any(::spansBoth) }}/10, instances merged ${instances.count { s -> s.any(::spansBoth) }}/10 separate ${instances.count(::separate)}/10")
        assertTrue(baseline.count { s -> s.any(::spansBoth) } >= 8, "baseline should merge the boxes")
        assertTrue(instances.none { s -> s.any(::spansBoth) } && instances.count(::separate) >= 8, "instances should keep two boxes")
    }

    @Test
    fun `a box keeps its instance number after leaving the view`() {
        // SC-07: 낮은 상자가 다가가는 동안 시야 아래로 빠진다. 시야 밖 칸은 지우지 않으므로 번호도 남아야 한다
        val spec = Scenes.SC07
        val shape = spec.scene.items.first { it.obstacle }.shape
        val s = slow(spec, on)
        val ids = ArrayList<Set<Int>>()
        for (f in spec.generate().frames) {
            val d = f.depth ?: continue
            s.process(d, f.truthHead.headingW)
            ids += s.map.voxels.occupied().filter { v ->
                v.centerW.x in shape.aabbMin.x - 0.05f..shape.aabbMax.x + 0.05f && v.centerW.z in shape.aabbMin.z - 0.05f..shape.aabbMax.z + 0.05f
            }.map { it.instId }.toSet()
        }
        val seen = ids.filter { it.isNotEmpty() }
        val main = seen[seen.size / 2].maxOrNull()!!
        assertTrue(main != 0 && seen.last().contains(main), "instance ids over time: first ${seen.first()} mid ${seen[seen.size / 2]} last ${seen.last()}")
    }
}
