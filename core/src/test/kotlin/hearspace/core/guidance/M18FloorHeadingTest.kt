package hearspace.core.guidance

import hearspace.core.geometry.Projection
import hearspace.core.geometry.Vec3
import hearspace.core.mapping.Floor
import hearspace.core.synth.Noise
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.synth.Walk
import hearspace.core.types.Band
import hearspace.core.types.Config
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.sin

/** M18 바닥 유지와 진행 방향 창 (IMPROVE_SPEC v0.3.17, M18 설계 표 6 S1·S4·S5). 기둥 검사(S2)는 채택하지 않아 지웠다. */
class M18FloorHeadingTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()

    private fun config(spec: SceneSpec, hold: Boolean, windowS: Float = 1.0f): Config {
        val g = spec.walk.gripOffsetM
        return ConfigLoader.load(
            base,
            """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] }, "floor": { "holdWhenLost": $hold },
               "heading": { "windowS": $windowS } }""",
        )
    }

    /** 폭 1.04 m 복도에서 끝 벽(z = −4) 0.5 m 앞까지 0.5 m/s로 걷고 선다. 카메라 높이 1.2 m(M12.3 실측 1.07~1.27 m). */
    private val wallWalk = SceneSpec(
        "M18-wall", Scenes.corridorE(),
        Walk(startCameraW = Vec3(0f, 1.2f, 0f), speedMps = 0.5f, legM = 3.5f, legCount = 1, durationS = 2f + 7f + 2f),
        Noise(seed = 3, depthMulStd = 0.01f),
    )
    private val wallRec by lazy { wallWalk.generate() }

    private fun floorTrace(cfg: Config): List<Float?> {
        val f = Floor(cfg.floor, cfg.map.radiusM)
        return wallRec.frames.mapNotNull { fr ->
            fr.depth?.let { d -> f.update(Projection.backprojectToWorld(d.depthMm, d.K, d.worldFromCam, cfg.depth.subsample), d.worldFromCam.translation()).floorY }
        }
    }

    private fun lostAfterFirst(trace: List<Float?>) = trace.dropWhile { it == null }.count { it == null }

    @Test
    fun `S1 in front of an end wall the floor is held and the wall gives STOP`() {
        // 합성 깊이는 옆 벽 밑동까지 빈틈없이 보여 기준선이 바닥을 잃기보다 벽을 타고 올라간다(실측은 올라간 뒤 잃음, 잃는 경우는 FloorTest)
        val baseline = floorTrace(config(wallWalk, hold = false))
        val baseMax = baseline.filterNotNull().max()
        assertTrue(lostAfterFirst(baseline) > 0 || baseMax > 0.2f, "baseline reproduces the climb or the loss: lost ${lostAfterFirst(baseline)}, max $baseMax")
        val held = floorTrace(config(wallWalk, hold = true))
        assertTrue(held.first() != null && lostAfterFirst(held) == 0, "lost ${lostAfterFirst(held)} frames")

        // 머리 → 벽 0.9 m 안(STOP 1.0 m보다 여유)에서 모두 STOP. 올라간 바닥(카메라 − 0.8 m 이하)을 유지해도 벽은 그 위로 높다
        val near = runGuidance(wallWalk, config(wallWalk, hold = true)).filter { it.f.truthHead.positionW.z + 4f < 0.9f }
        assertTrue(near.isNotEmpty())
        val stop = near.count { s -> s.out.commands.any { it.band == Band.STOP } }.toFloat() / near.size
        assertTrue(stop == 1f, "STOP fraction $stop")
    }

    /** 곧게 걷는 동안 진행 방향 오차(도): 창이 찬 뒤의 프레임만. */
    private fun headingErrors(spec: SceneSpec, windowS: Float, fromS: Float): List<Float> {
        val h = Heading(config(spec, hold = false, windowS).heading)
        return spec.generate().frames.mapNotNull { fr ->
            val est = h.update(fr.pose) ?: return@mapNotNull null
            if (fr.tS < fromS) null else abs(Heading.toDeg(est) - Heading.toDeg(fr.truthHead.headingW))
        }
    }

    @Test
    fun `S4 a longer window averages out the lateral sway`() {
        val a = 0.03f
        val p = 1.4f
        val v = 0.5f
        val spec = SceneSpec(
            "M18-sway", Scenes.corridorE(),
            Walk(speedMps = v, stepHz = 2f / p, swayAmpM = a, durationS = 2f + 7f), depthEveryNFrames = 10_000,
        )
        // 창의 시작·끝 옆 변위 ≤ 2A·|sin(πT/P)|, 앞 변위 vT
        fun bound(t: Float) = Math.toDegrees(atan(2 * a * abs(sin(PI * t / p)) / (v * t))).toFloat() + 0.5f
        val e1 = headingErrors(spec, 1.0f, fromS = 2f + 2f).sorted()
        val e2 = headingErrors(spec, 2.0f, fromS = 2f + 2f).sorted()
        val p95 = { e: List<Float> -> e[(0.95 * (e.size - 1)).toInt()] }
        assertTrue(p95(e1) <= bound(1f), "1 s p95 ${p95(e1)} bound ${bound(1f)}")
        assertTrue(p95(e2) <= bound(2f), "2 s p95 ${p95(e2)} bound ${bound(2f)}")
        assertTrue(p95(e2) < p95(e1), "2 s ${p95(e2)} vs 1 s ${p95(e1)}")
    }

    @Test
    fun `S5 after a U-turn the heading follows within the window`() {
        // 2 m 걷고(4 s) 2 s 동안 180° 돌아 되돌아온다. 돌아섬 끝 = 2 + 4 + 2 = 8 s
        val spec = SceneSpec(
            "M18-turn", Scenes.corridorE(),
            Walk(speedMps = 0.5f, legM = 2f, legCount = 2, turnS = 2f, durationS = 2f + 4f + 2f + 4f), depthEveryNFrames = 10_000,
        )
        for (w in listOf(1.0f, 2.0f)) {
            val h = Heading(config(spec, hold = false, w).heading)
            var settledS: Float? = null
            for (fr in spec.generate().frames) {
                val est = h.update(fr.pose) ?: continue
                if (fr.tS < 8f) continue
                val back = Heading.toDeg(fr.truthHead.headingW)
                val err = abs(((Heading.toDeg(est) - back + 540f) % 360f) - 180f)
                if (err > 90f) settledS = null else if (settledS == null) settledS = fr.tS
            }
            val lag = settledS!! - 8f
            println("M18 S5: window $w s, heading within 90° of the new direction ${"%.2f".format(lag)} s after the turn")
            assertTrue(lag <= w, "window $w: lag $lag")
        }
    }
}
