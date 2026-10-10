package hearspace.core.mapping

import hearspace.core.synth.Box
import hearspace.core.geometry.Vec3
import hearspace.core.pipeline.SlowPath
import hearspace.core.synth.Noise
import hearspace.core.synth.SceneItem
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.types.ConfigLoader
import hearspace.core.types.HeightClass
import hearspace.core.types.Obstacle
import hearspace.core.types.ObstacleSnapshot
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 옆 번짐과 RGB 번짐 띠 보정(M19 설계 표 7의 S1~S4). 합성 깊이에 옆 번짐(`Noise.edgeSpillPx`, 앞 물체 깊이가 옆·위 배경 화소로 퍼짐)과
 * 가장자리 평활을 넣고, 느린 경로 지도에서 상자 폭과 상자 칸 수를 본다. 장면은 SC-21(2.4 m 상자 폭 0.45 m + 뒤 벽)이다.
 */
class SpillTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val off = """ "frontend": { "enabled": false, "rgbGuide": "NONE", "rgbGuideBandPx": 0, "segment": "NONE" }, "map": { "instances": false } """
    private val band = """ "frontend": { "enabled": false, "rgbGuide": "WEIGHTED_MEDIAN", "rgbGuideBandPx": $BAND_PX, "segment": "NONE" }, "map": { "instances": false } """

    private class Step(val snapshot: ObstacleSnapshot, val occupied: List<Vec3>)

    private fun run(spec: SceneSpec, overrides: String): List<Step> {
        val g = spec.walk.gripOffsetM
        val slow = SlowPath(ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }, $overrides }"""))
        return spec.generate().frames.mapNotNull { f ->
            val d = f.depth ?: return@mapNotNull null
            Step(slow.process(d, f.truthHead.headingW), slow.map.voxels.occupied().map { it.centerW })
        }
    }

    /** 밝기: 상자 220, 벽 60, 바닥 120(M13.7 SC-21 시험과 같음). [contrast]가 false면 모두 같은 밝기(대비 없음). */
    private fun scene(spill: Int, contrast: Boolean = true, extra: List<SceneItem> = emptyList()) = Scenes.SC21.let { s ->
        val items = s.scene.items.map { if (contrast) it.copy(luma = when (it.name) { "box" -> 220; "back_wall" -> 60; else -> 120 }) else it }
        s.copy(scene = s.scene.copy(items = items + extra), noise = Noise(edgeSmoothPx = Scenes.EDGE_SMOOTH_PX, edgeSpillPx = spill))
    }

    // SC-21 상자(월드): x ±0.225, 높이 0~0.6, z −2.7~−2.4
    private fun overBox(o: Obstacle) = o.aabbMaxW.x > -0.225f && o.aabbMinW.x < 0.225f && o.aabbMaxW.z > -2.7f && o.aabbMinW.z < -2.4f

    /** 상자 앞뒤 범위·높이 띠의 점유 칸 좌우 폭(m)의 중앙값. 상자 칸이 없는 프레임은 뺀다. */
    private fun width(steps: List<Step>): Float {
        val w = steps.mapNotNull { s ->
            val xs = s.occupied.filter { it.z in -2.75f..-2.35f && it.y in 0.1f..0.55f }.map { it.x }
            if (xs.isEmpty()) null else xs.max() - xs.min()
        }.sorted()
        return w[w.size / 2]
    }

    /** 정답 상자 안(한 칸 여유) 점유 칸 수의 최대. */
    private fun boxVoxels(steps: List<Step>) = steps.maxOf { s -> s.occupied.count { it.x in -0.275f..0.275f && it.y in 0f..0.65f && it.z in -2.75f..-2.35f } }

    /** 상자가 장애물로 잡힌 깊이 프레임 수. */
    private fun seen(steps: List<Step>) = steps.count { s -> s.snapshot.obstacles.any(::overBox) }

    @Test
    fun `S1 side spill widens the box without correction`() {
        val clean = width(run(scene(spill = 0), off))
        val spilled = width(run(scene(spill = SPILL_PX), off))
        println("S1 box width: clean $clean m, spilled $spilled m")
        assertTrue(spilled > clean + VOXEL_M, "clean $clean spilled $spilled")
    }

    @Test
    fun `S2 the spill band snaps spilled pixels back to the background`() {
        val cleanSteps = run(scene(spill = 0), off)
        val fixed = run(scene(spill = SPILL_PX), band)
        val (wc, wf) = width(cleanSteps) to width(fixed)
        val (vc, vf) = boxVoxels(cleanSteps) to boxVoxels(fixed)
        println("S2 box width clean $wc -> band $wf m; box voxels clean $vc -> band $vf")
        assertTrue(wf <= wc + VOXEL_M, "width clean $wc band $wf")
        assertTrue(vf >= 0.9f * vc, "box voxels clean $vc band $vf")
    }

    @Test
    fun `S3 without colour contrast the band does not erase the box`() {
        val plain = run(scene(spill = SPILL_PX, contrast = false), off)
        val fixed = run(scene(spill = SPILL_PX, contrast = false), band)
        println("S3 box seen frames: no correction ${seen(plain)}, band ${seen(fixed)} of ${fixed.size}; box voxels ${boxVoxels(plain)} -> ${boxVoxels(fixed)}")
        assertTrue(seen(fixed) >= seen(plain), "seen ${seen(plain)} -> ${seen(fixed)}")
    }

    @Test
    fun `S4 a pole thinner than the band stays an obstacle`() {
        // 통로 안(x 0.24~0.30) 보행선 1.6 m 앞에 폭 0.06 m 기둥(밝기 200, 상자보다 0.8 m 앞): 출발 때 화면 폭 약 4.6화소로 띠 두 겹보다 얇다
        val pole = SceneItem("pole", Box(Vec3(0.24f, 0f, -1.66f), Vec3(0.30f, 1.5f, -1.6f)), obstacle = true, expectedClass = HeightClass.FLOOR, luma = 200)
        fun overPole(o: Obstacle) = o.aabbMaxW.x > 0.24f && o.aabbMinW.x < 0.30f && o.aabbMaxW.z > -1.66f && o.aabbMinW.z < -1.6f
        val plain = run(scene(spill = SPILL_PX, extra = listOf(pole)), off)
        val fixed = run(scene(spill = SPILL_PX, extra = listOf(pole)), band)
        val (p, f) = plain.count { s -> s.snapshot.obstacles.any(::overPole) } to fixed.count { s -> s.snapshot.obstacles.any(::overPole) }
        println("S4 pole seen frames: no correction $p, band $f of ${fixed.size}")
        assertTrue(p > 0 && f >= p, "pole seen $p -> $f")
    }

    companion object {
        /** 합성 번짐 4화소, 띠 4화소(합성은 번짐 폭을 알고 있다. 실제 값은 실측 p90). */
        const val SPILL_PX = 4
        const val BAND_PX = 4
        const val VOXEL_M = 0.05f
    }
}
