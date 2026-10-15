package hearspace.core.guidance

import hearspace.core.geometry.Vec3
import hearspace.core.synth.Box
import hearspace.core.synth.Noise
import hearspace.core.synth.Scene
import hearspace.core.synth.SceneItem
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.synth.Walk
import hearspace.core.types.Band
import hearspace.core.types.ConfigLoader
import hearspace.core.types.HeightClass
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 작은 군집 확인 강화(M25 S, `track.smallClusterVoxels`): 작은 실제 물체의 첫 경고·첫 STOP이 0.10 m 넘게 늦어지지 않는다.
 * 테이블 모서리(SC-05), 머리 높이 판(SC-06), 낮은 상자(SC-07), 가는 기둥(폭 0.05 m, 깊이 잡음 1%·가장자리 평활).
 */
class SmallClusterTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    private val pole = SceneSpec(
        "pole",
        Scene(listOf(SceneItem("floor", hearspace.core.synth.HorizontalPlane(0f), obstacle = false),
            SceneItem("pole", Box(Vec3(-0.025f, 0f, -2.55f), Vec3(0.025f, 2.0f, -2.5f)), obstacle = true, expectedClass = HeightClass.FLOOR))),
        Walk(durationS = 2f + 2.0f),
        Noise(depthMulStd = 0.01f, edgeSmoothPx = Scenes.EDGE_SMOOTH_PX),
    )

    private fun run(spec: SceneSpec, small: Boolean): List<Step> {
        val g = spec.walk.gripOffsetM
        val track = if (small) """, "track": { "smallClusterVoxels": 10, "suspiciousConfirmObservations": 4 }""" else ""
        return runGuidance(spec, ConfigLoader.load(base, """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }$track }"""))
    }

    /** [band] 이상(STOP은 WARN보다 위)이 처음 나온 단계의 머리 위치 z(−Z로 걸음: 작을수록 늦음). */
    private fun firstZ(steps: List<Step>, band: Band) =
        steps.firstOrNull { s -> s.out.commands.any { it.band.ordinal <= band.ordinal } }?.f?.truthHead?.positionW?.z

    @Test
    fun `small real objects keep first WARN and STOP with small cluster confirmation`() {
        for (spec in listOf(Scenes.SC05, Scenes.SC06, Scenes.SC07, pole)) {
            val off = run(spec, false)
            val on = run(spec, true)
            for (band in listOf(Band.WARN, Band.STOP)) {
                val a = firstZ(off, band) ?: continue
                val b = firstZ(on, band)
                println("${spec.id} $band off $a on $b")
                assertTrue(b != null && b >= a - 0.10f, "${spec.id} first $band later: $a -> $b")
            }
        }
    }
}
