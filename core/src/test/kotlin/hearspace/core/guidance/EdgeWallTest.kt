package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.geometry.headRelative
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.types.Band
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.abs

/**
 * 통로 경계에 걸린 벽(M22 W, `corridor.edgeOutsideM`): SC-28·SC-29. 오른쪽 벽면이 통로 경계(머리에서 0.40 m)에 놓여 벽 칸 일부만 경계 안에
 * 들어오면, 통로 안 칸만으로 길이를 재는 가장자리 구조물 규칙이 그 조각을 벽으로 보지 못해 안내한다(실측 S02 130834·S07 131003).
 */
class EdgeWallTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    /** 시험 설정(기본값 또는 -PtestOverrides)에 장면의 파지 오프셋과 [edgeOutsideM]만 덮는다. */
    private fun run(spec: SceneSpec, edgeOutsideM: Float): List<Step> {
        val g = spec.walk.gripOffsetM
        val cfg = ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }, "corridor": { "edgeOutsideM": $edgeOutsideM } }""")
        return runGuidance(spec, cfg)
    }

    /** 안내 명령을 상자 / 그 밖으로 나눈다: 상자 앞면 가운데까지 거리와 0.35 m 안이면 상자(SC-14와 같은 여유). */
    private fun split(spec: SceneSpec, steps: List<Step>): Pair<Int, Int> {
        val box = spec.scene.items.first { it.name == "box" }.shape
        var onBox = 0
        var other = 0
        for (s in steps) for (c in s.out.commands.filter { it.band != Band.SILENT }) {
            val rel = headRelative(Vec3((box.aabbMin.x + box.aabbMax.x) / 2, 0.2f, box.aabbMax.z), s.f.truthHead)
            if (abs(rel.horizontalDistM - c.distanceM) <= 0.35f) onBox++ else other++
        }
        return onBox to other
    }

    /** 첫 STOP 때 머리에서 상자 앞면까지 진행 방향 거리(−Z로 걸음). */
    private fun firstStopAlong(spec: SceneSpec, steps: List<Step>): Float? {
        val front = spec.scene.items.first { it.name == "box" }.shape.aabbMax.z
        return steps.firstOrNull { s -> s.out.commands.any { it.band == Band.STOP } }?.let { it.f.truthHead.positionW.z - front }
    }

    @Test
    fun `SC-28 baseline announces the wall slice at the corridor edge`() {
        // 재현 확인: 통로 안 칸만으로 재면 경계에 걸린 벽 조각이 안내된다. 이 장면이 통과하게 되면 재현이 깨진 것이다
        val (onBox, other) = split(Scenes.SC28, run(Scenes.SC28, 0f))
        assertTrue(onBox > 10, "the box must be announced: $onBox")
        assertTrue(other > 0, "baseline no longer reproduces the edge wall slice")
    }

    @Test
    fun `SC-28 measuring the edge structure outside the corridor drops the wall slice and keeps the box`() {
        val (offBox, _) = split(Scenes.SC28, run(Scenes.SC28, 0f))
        val (onBox, other) = split(Scenes.SC28, run(Scenes.SC28, edgeOutsideM))
        assertEquals(0, other, "wall slice announced")
        assertEquals(offBox, onBox, "box announcements changed")
    }

    @Test
    fun `SC-29 a low box 0_10 m from the outside wall still gets STOP no later`() {
        val off = firstStopAlong(Scenes.SC29, run(Scenes.SC29, 0f))
        val steps = run(Scenes.SC29, edgeOutsideM)
        val on = firstStopAlong(Scenes.SC29, steps)
        assertTrue(off != null && on != null, "STOP off=$off on=$on")
        assertTrue(on!! >= off!! - 0.10f, "first STOP later: $off -> $on")
        assertEquals(0, split(Scenes.SC29, steps).second, "wall slice announced")
    }

    /** 통로 밖으로 더 보는 폭 = `cluster.epsM`(군집이 이을 거리, M22 설계 3.1절). */
    private val edgeOutsideM = ConfigLoader.load(base).cluster.epsM
}
