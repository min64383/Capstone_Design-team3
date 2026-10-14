package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * RGB 경계 옮기기(M23) 합성 장면 SC-25·SC-26. 평활(막) + 배경 당김 + 밝기 대비 깊이로 느린 경로를 돌려 상자와 벽 사이 빈 공간의
 * 칸과 바닥 위 낮은 상자 아랫단을 본다. 기준선(앞단 끔), G(가중 중앙값), Ra(EDGE), Rb(EDGE_MARGIN)를 비교한다.
 */
class RgbEdgeSceneTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val off = """ "frontend": { "enabled": false, "rgbGuide": "NONE", "segment": "NONE" }, "map": { "instances": false } """
    private fun mode(m: String) = """ "frontend": { "enabled": false, "rgbGuide": "$m", "segment": "NONE" }, "map": { "instances": false } """

    private class Step(val occupied: List<Vec3>)

    private fun run(spec: SceneSpec, overrides: String): List<Step> {
        val g = spec.walk.gripOffsetM
        val slow = SlowPath(ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }, $overrides }"""))
        return spec.generate().frames.mapNotNull { f ->
            val d = f.depth ?: return@mapNotNull null
            slow.process(d, f.truthHead.headingW)
            Step(slow.map.voxels.occupied().map { it.centerW })
        }
    }

    // 상자(월드, 보행 −Z): x ±0.225, z −2.7~−2.4. 벽 앞면 z −4.4. 칸 중심으로 센다(0.05 m 칸)

    /** 상자 뒤 0.15 m부터 벽 앞 0.15 m까지 빈 공간의 점유 칸 수(최대). 합성 당김은 상자 뒤가 아니라 벽 앞에 칸을 만든다(둘 사이 1.7 m). */
    private fun gap(steps: List<Step>) =
        steps.maxOf { s -> s.occupied.count { it.x in -0.6f..0.6f && it.y in 0.05f..1.5f && it.z in -4.25f..-2.85f } }

    /** 상자 안 높이 [y0]~[y1]의 점유 칸 수(최대). */
    private fun box(steps: List<Step>, y0: Float, y1: Float) =
        steps.maxOf { s -> s.occupied.count { it.x in -0.2f..0.2f && it.y in y0..y1 && it.z in -2.7f..-2.4f } }

    @Test
    fun `SC-25 margin variant removes the pulled background between box and wall and keeps the box`() {
        val r = listOf("CUR" to off, "G" to mode("WEIGHTED_MEDIAN"), "Ra" to mode("EDGE"), "Rb" to mode("EDGE_MARGIN"))
            .associate { (n, o) -> n to run(Scenes.SC25, o) }
        r.forEach { (n, s) -> println("SC-25 $n: gap cells max ${gap(s)}, box cells max ${box(s, 0f, 0.7f)}") }
        val cur = r.getValue("CUR")
        val rb = r.getValue("Rb")
        assertTrue(gap(cur) > 0 && gap(rb) <= 0.1f * gap(cur), "gap CUR ${gap(cur)} Rb ${gap(rb)}")
        assertTrue(box(rb, 0f, 0.7f) >= 0.8f * box(cur, 0f, 0.7f), "box CUR ${box(cur, 0f, 0.7f)} Rb ${box(rb, 0f, 0.7f)}")
    }

    @Test
    fun `SC-26 margin variant keeps the bottom of a low box on the floor`() {
        // 2.4 m에서 바닥 허용 오차(0.05 + 0.08 × 거리)가 약 0.24 m라 지도에 들어가는 가장 낮은 줄은 0.15~0.2 m다.
        // 윗줄(0.2~0.25)은 G·Ra·Rb 모두 같게 줄어든다(윗모서리 막 화소가 윗면으로 옮겨짐): Rb 고유의 손실이 아니다
        val cur = run(Scenes.SC26, off)
        val rb = run(Scenes.SC26, mode("EDGE_MARGIN"))
        println("SC-26 bottom (0.1-0.2 m) cells: CUR ${box(cur, 0.1f, 0.2f)} Rb ${box(rb, 0.1f, 0.2f)}; all: CUR ${box(cur, 0f, 0.35f)} Rb ${box(rb, 0f, 0.35f)}")
        assertTrue(box(cur, 0.1f, 0.2f) > 0 && box(rb, 0.1f, 0.2f) >= 0.8f * box(cur, 0.1f, 0.2f), "bottom")
        assertTrue(box(rb, 0f, 0.35f) >= 0.8f * box(cur, 0f, 0.35f), "all")
    }
}
