package hearspace.core.guidance

import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.types.Band
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 통로 끝에 잘린 구조물 조각(M25 E, `corridor.cutEnd`): SC-30. 끝 벽이 통로 끝 바로 너머에 있으면 벽 칸 일부만 끝 안에 들어와 작은 군집이
 * 생겼다 없어진다. 끝 너머와 이어진 조각은 무음 거리에서만 빼므로 다가갔을 때의 경고·STOP은 그대로여야 한다.
 */
class EndFragmentTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val cfg = ConfigLoader.load(base)

    /** 시험 설정(기본값 또는 -PtestOverrides)에 장면의 파지 오프셋과 [cutEnd]만 덮는다. */
    private fun run(spec: SceneSpec, cutEnd: Boolean): List<Step> {
        val g = spec.walk.gripOffsetM
        return runGuidance(spec, ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }, "corridor": { "cutEnd": $cutEnd } }"""))
    }

    /** 머리에서 끝 벽 앞면까지 진행 방향 거리(−Z로 걸음). */
    private fun wallAlong(spec: SceneSpec, s: Step) = s.f.truthHead.positionW.z - spec.scene.items.first { it.name == "end_wall" }.shape.aabbMax.z

    /** 벽 앞면이 통로 끝 너머에 있는 동안 나온 출력 track 수(단계 합). 3.0 m 너머는 안내 명령이 없어 track 출력으로 센다. */
    private fun beyondEndOutputs(spec: SceneSpec, steps: List<Step>) =
        steps.filter { wallAlong(spec, it) > cfg.corridor.lengthM }.sumOf { it.snapshot?.obstacles?.size ?: 0 }

    private fun firstAlong(spec: SceneSpec, steps: List<Step>, band: Band) =
        steps.firstOrNull { s -> s.out.commands.any { it.band == band } }?.let { wallAlong(spec, it) }

    @Test
    fun `SC-30 baseline outputs a fragment of the end wall beyond the corridor end`() {
        // 재현 확인: 이 시험이 실패하면 장면이 더는 끝 조각을 만들지 않는 것이다
        assertTrue(beyondEndOutputs(Scenes.SC30, run(Scenes.SC30, false)) > 0, "baseline no longer reproduces the end fragment")
    }

    @Test
    fun `SC-30 cutting end fragments drops them and keeps WARN and STOP`() {
        val off = run(Scenes.SC30, false)
        val on = run(Scenes.SC30, true)
        assertEquals(0, beyondEndOutputs(Scenes.SC30, on), "end fragment output")
        for (band in listOf(Band.WARN, Band.STOP)) {
            val a = firstAlong(Scenes.SC30, off, band)
            val b = firstAlong(Scenes.SC30, on, band)
            assertTrue(a != null && b != null, "$band off=$a on=$b")
            assertTrue(b!! >= a!! - 0.10f, "first $band later: $a -> $b")
        }
    }
}
