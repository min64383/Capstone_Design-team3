package hearspace.core.guidance

import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.types.HeadingConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * M42 진행 방향 가중 결합(`heading.cameraSigmaDeg` > 0): 창 이동 방향(오차 √2·swayM/d)과 카메라 정면(오차 cameraSigmaDeg)의 최소 분산 결합.
 * 값은 설계에서 잰 σ_lat 0.016 m, σ_cam 2.3°.
 */
class HeadingFusionTest {
    private val fused = HeadingConfig(windowS = 2f, minTravelM = 0.15f, swayM = 0.016f, cameraSigmaDeg = 2.3f)

    private fun wrap(a: Float): Float = ((a + 540f) % 360f) - 180f

    /** 프레임마다 (시각, 추정 진행 방향°, 정답 진행 방향°, 카메라 정면°). */
    private fun headings(spec: SceneSpec, cfg: HeadingConfig): List<FloatArray> {
        val h = Heading(cfg)
        return spec.generate().frames.mapNotNull { f ->
            val est = h.update(f.pose) ?: return@mapNotNull null
            val cam = Heading.toDeg(f.truthWorldFromCam.axis(2).horizontal().normalized())
            floatArrayOf(f.tS, Heading.toDeg(est), Heading.toDeg(f.truthHead.headingW), cam)
        }
    }

    @Test
    fun `SC-08 wrist yaw stays within 2 deg once the 2 s window is full`() {
        // SC-08은 1.5 s만 걸어 창이 차지 않는다: 같은 손목 요 ±20°로 4 s 걷는다. 창이 찬 뒤(d = 2 m) 손목 요 기여는 ±1.5°(설계 3.5절)
        val spec = Scenes.SC08.let { it.copy(walk = it.walk.copy(durationS = 6f)) }
        val rows = headings(spec, fused)
        var maxCamDev = 0f
        for (r in rows.filter { it[0] > spec.walk.standS + 2f }) {
            assertEquals(0f, wrap(r[1] - r[2]), 2f) { "t=${r[0]}" }
            maxCamDev = maxOf(maxCamDev, abs(wrap(r[3] - r[2])))
        }
        assertTrue(maxCamDev > 10f) { "wrist yaw should move the camera by >10° (was $maxCamDev)" }
    }

    @Test
    fun `standing heading follows the camera forward`() {
        val spec = Scenes.SC08
        val rows = headings(spec, fused).filter { it[0] < spec.walk.standS - 0.1f }
        assertTrue(rows.isNotEmpty())
        for (r in rows) assertEquals(0f, wrap(r[1] - r[3]), 0.5f) { "t=${r[0]}" }
    }

    @Test
    fun `after an in-place 180 deg turn the fused heading is closer than the window direction`() {
        // 3 m 걷고 머리 자리에서 2 s 동안 돌아 다시 걷는다. 돌기를 마친 뒤 1 s 동안 결합이 지금 규칙(창 이동 방향)보다 정답에 가깝다.
        // 창 안에 남은 회전 호의 편향은 모형 밖이라 0이 되지는 않는다(설계 3.4절)
        val walk = Scenes.SC01.walk.copy(legM = 3f, turnS = 2f, legCount = 2, durationS = 2f + 3f + 2f + 2f)
        val spec = Scenes.SC01.copy(walk = walk)
        val tEnd = walk.standS + 3f / walk.speedMps + walk.turnS
        val now = headings(spec, HeadingConfig(windowS = 2f, minTravelM = 0.15f)).filter { it[0] in tEnd..tEnd + 1f }
        val fu = headings(spec, fused).filter { it[0] in tEnd..tEnd + 1f }
        val errNow = now.map { abs(wrap(it[1] - it[2])) }.average()
        val errFused = fu.map { abs(wrap(it[1] - it[2])) }.average()
        assertTrue(errFused < errNow) { "mean |error| fused $errFused vs window $errNow" }
    }
}
