package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.Noise
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 옆 번짐 재현(M19 설계 표 7의 S1). 합성 깊이에 옆 번짐(`Noise.edgeSpillPx`, 앞 물체 깊이가 옆·위 배경 화소로 퍼짐)과 가장자리 평활을
 * 넣고, 느린 경로 지도에서 상자 폭을 본다. 장면은 SC-21(2.4 m 상자 폭 0.45 m + 뒤 벽)이다. RGB 번짐 띠 보정(S2~S4)은 실제 세션에서
 * 오경보·STOP 누락을 늘려 코드와 함께 지웠다(M19 결과, 커밋 8b02594). 번짐을 고치는 다음 방법은 이 재현 시험부터 다시 쓴다.
 */
class SpillTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val off = """ "frontend": { "enabled": false, "rgbGuide": "NONE", "segment": "NONE" }, "map": { "instances": false } """

    /** 깊이 한 장마다 그때의 점유 복셀 중심(월드). */
    private fun run(spec: SceneSpec, overrides: String): List<List<Vec3>> {
        val g = spec.walk.gripOffsetM
        val slow = SlowPath(ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }, $overrides }"""))
        return spec.generate().frames.mapNotNull { f ->
            val d = f.depth ?: return@mapNotNull null
            slow.process(d, f.truthHead.headingW)
            slow.map.voxels.occupied().map { it.centerW }
        }
    }

    private fun scene(spill: Int) = Scenes.SC21.copy(noise = Noise(edgeSmoothPx = Scenes.EDGE_SMOOTH_PX, edgeSpillPx = spill))

    /** SC-21 상자(z −2.7~−2.4, 높이 0~0.6) 앞뒤 범위·높이 띠의 점유 칸 좌우 폭(m)의 중앙값. 상자 칸이 없는 프레임은 뺀다. */
    private fun width(steps: List<List<Vec3>>): Float {
        val w = steps.mapNotNull { occupied ->
            val xs = occupied.filter { it.z in -2.75f..-2.35f && it.y in 0.1f..0.55f }.map { it.x }
            if (xs.isEmpty()) null else xs.max() - xs.min()
        }.sorted()
        return w[w.size / 2]
    }

    @Test
    fun `S1 side spill widens the box without correction`() {
        val clean = width(run(scene(spill = 0), off))
        val spilled = width(run(scene(spill = SPILL_PX), off))
        println("S1 box width: clean $clean m, spilled $spilled m")
        assertTrue(spilled > clean + VOXEL_M, "clean $clean spilled $spilled")
    }

    companion object {
        /** 합성 번짐 4화소(실측 번짐 거리 p50 2·p90 13화소, tools/analysis/spill.py). */
        const val SPILL_PX = 4
        const val VOXEL_M = 0.05f
    }
}
