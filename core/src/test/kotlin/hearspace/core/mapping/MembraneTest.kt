package hearspace.core.mapping

import hearspace.core.geometry.Vec3
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.Noise
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.types.ConfigLoader
import hearspace.core.types.ObstacleSnapshot
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 막 장면 SC-21~SC-23 (IMPROVE_SPEC §6.1.1 M13.0, §12). 합성 깊이에 가장자리 평활(실측 막 흉내)을 넣고 느린 경로 지도를 본다.
 * M13.0 완료 기준: 기준선(지금 코드)에서 SC-21은 실패하고(막 복셀, 상자–벽 합쳐짐) SC-22·SC-23은 통과한다.
 * M13.1(깊이 영상 앞단)은 SC-21을 통과로 뒤집으면서 SC-22·SC-23을 계속 통과해야 한다.
 */
class MembraneTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    /** 깊이 한 장마다 느린 경로 결과와 그때의 점유 복셀 중심(월드). 진행 방향은 참 머리 방향. */
    private class MapStep(val tS: Float, val snapshot: ObstacleSnapshot, val occupied: List<Vec3>)

    private fun run(spec: SceneSpec, overrides: String = ""): List<MapStep> {
        val g = spec.walk.gripOffsetM
        val sep = if (overrides.isBlank()) "" else ", $overrides"
        val slow = SlowPath(ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }$sep }"""))
        return spec.generate().frames.mapNotNull { f ->
            val d = f.depth ?: return@mapNotNull null
            val snap = slow.process(d, f.truthHead.headingW)
            MapStep(f.tS, snap, slow.map.voxels.occupied().map { it.centerW })
        }
    }

    private fun Vec3.inBox(min: Vec3, max: Vec3) = x in min.x..max.x && y in min.y..max.y && z in min.z..max.z

    // SC-21(월드, 보행 −Z): 상자 x ±0.225, 높이 0~0.6, z −2.7~−2.4. 벽 앞면 z −4.4
    private val gapMin = Vec3(-0.6f, 0.05f, -4.25f) // 상자 뒤 0.15 m부터 벽 앞 0.15 m까지(복셀 0.05 m의 세 칸 여유)
    private val gapMax = Vec3(0.6f, 1.5f, -2.85f)

    /** SC-21 지표: 상자와 벽 사이 빈 공간의 점유 복셀 수(최대)와, 한 물체가 상자부터 벽까지 이어진 깊이 프레임 비율. */
    private fun sc21(steps: List<MapStep>): Pair<Int, Float> {
        val gap = steps.maxOf { s -> s.occupied.count { it.inBox(gapMin, gapMax) } }
        val merged = steps.count { s -> s.snapshot.obstacles.any { it.aabbMaxW.z > -2.7f && it.aabbMinW.z < -4.3f } }
        return gap to merged.toFloat() / steps.size
    }

    /** M13.1의 SC-21 합격 조건: 빈 공간에 점유 복셀이 없고 상자와 벽이 따로 잡힌다. */
    private fun sc21Passes(steps: List<MapStep>) = sc21(steps).let { (gap, merged) -> gap == 0 && merged == 0f }

    @Test
    fun `SC-21 without smoothing keeps the space between box and wall empty`() {
        val clean = run(Scenes.SC21.copy(noise = Noise()))
        assertTrue(sc21Passes(clean), "control: ${sc21(clean)}")
    }

    @Test
    fun `SC-21 baseline reproduces the membrane - fails until the depth front end (M13_1)`() {
        val (gap, merged) = sc21(run(Scenes.SC21))
        println("SC-21 baseline: gap voxels max $gap, box-wall merged fraction $merged")
        assertTrue(gap > 0, "membrane voxels between box and wall: $gap")
    }

    /** SC-22(SC-14 + 평활) 오른쪽 벽(x 0.55~0.65)의 마지막 지도 칸 수. M13.1은 기준선 값의 80% 이상을 남겨야 한다. */
    private fun rightWall(steps: List<MapStep>) = steps.last().occupied.count { it.x in 0.5f..0.7f && it.y in 0.1f..2.0f }

    @Test
    fun `SC-22 baseline keeps the oblique side wall in the map`() {
        // 생성기의 평활이 비스듬한 벽의 먼 끝(깊이가 빠르게 늘어나는 곳)도 조금 바꾸므로 평활 없을 때보다 적다(실측 약 76%)
        val clean = rightWall(run(Scenes.SC14))
        val smoothed = rightWall(run(Scenes.SC22))
        println("SC-22 right-wall voxels: clean $clean, smoothed baseline $smoothed")
        assertTrue(clean > 50 && smoothed >= 0.6f * clean, "clean $clean smoothed $smoothed")
    }

    // SC-23(SC-08 + 평활) 상자: x ±0.225, 높이 0~0.8, z −2.45~−2.0
    private val boxMin = Vec3(-0.325f, 0f, -2.55f)
    private val boxMax = Vec3(0.325f, 0.9f, -1.9f)

    /** 상자 윗면 근처(높이 0.7~0.9) 점유 칸 수의 최대. M13.1은 기준선 값의 80% 이상을 남겨야 한다. */
    private fun boxTop(steps: List<MapStep>) = steps.maxOf { s -> s.occupied.count { it.inBox(Vec3(-0.3f, 0.7f, -2.5f), Vec3(0.3f, 0.9f, -1.95f)) } }

    /** 상자 자리에 물체가 둘 이상 잡힌 깊이 프레임 비율(상자 자리에 물체가 있는 프레임 중). */
    private fun boxSplit(steps: List<MapStep>): Float {
        fun over(o: hearspace.core.types.Obstacle) = o.aabbMaxW.x > boxMin.x && o.aabbMinW.x < boxMax.x && o.aabbMaxW.z > boxMin.z && o.aabbMinW.z < boxMax.z
        val seen = steps.filter { s -> s.snapshot.obstacles.any(::over) }
        return seen.count { s -> s.snapshot.obstacles.count(::over) > 1 }.toFloat() / seen.size
    }

    @Test
    fun `SC-23 baseline keeps the box top and the box is one object`() {
        // 평활 없는 SC-08 기준선은 상자가 앞면과 비스듬히 보이는 윗면 뒤 모서리, 두 물체로 갈라진다(약 88% 프레임).
        // 평활이 둘 사이를 이어 SC-23 기준선은 한 물체다. 윗면 칸은 평활 때 줄어든다(실측 약 68%)
        val clean = run(Scenes.SC08)
        val smoothed = run(Scenes.SC23)
        println("SC-23 box-top voxels: clean ${boxTop(clean)}, smoothed baseline ${boxTop(smoothed)}; split fraction clean ${boxSplit(clean)}, smoothed ${boxSplit(smoothed)}")
        assertTrue(boxTop(smoothed) >= 0.5f * boxTop(clean), "box top kept")
        assertEquals(0f, boxSplit(smoothed), 0.05f, "box is one object")
    }
}
