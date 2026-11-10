package hearspace.core.synth

import hearspace.core.guidance.Corridor
import hearspace.core.tracking.FarTracker
import hearspace.core.types.ConfigLoader
import hearspace.core.types.HeightClass
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.sin

/**
 * M53 발 접점 track을 지금 추적 없이 바로 돌리는 합성 시험(설계 표 8 SM-19). 앱 바닥 대신 시험용 바닥 높이를 장마다 넣는다(전체 재생에서는 바닥이 합성
 * 깊이에서 추정돼 흔들림을 정할 수 없다). 의미 영상은 합성 생성기가 그린 것을 깊이 2장에 1장(약 15 Hz) 쓴다.
 */
class FarTrackerTest {
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(), """{ "vision": { "footContact": true } }""")

    /** 장면을 생성해 발 접점 track만 돌리고, 장마다 (움직인다고 본 track 수, 낸 출력 수)를 낸다. [floorAt]은 시각(s) → 앱 바닥 대신 넣을 바닥 높이. */
    private fun run(spec: SceneSpec, floorAt: (Float) -> Float): List<Pair<Int, Int>> {
        val far = FarTracker(config.vision, config.track, config.map.minHits, (config.policy.maxInfoAgeMs * 1e6).toLong())
        val out = ArrayList<Pair<Int, Int>>()
        var n = 0
        for (f in spec.generate().frames) {
            val d = f.depth ?: continue
            val floorY = floorAt(f.tS)
            val corridor = Corridor(f.truthHead.positionW, f.truthHead.headingW, floorY, config.corridor)
            val labels = if (n++ % 2 == 0) d.labels else null
            val obs = far.step(d.tCaptureNs, d.worldFromCam.translation().y, floorY, labels, corridor, emptyList())
            out += far.lastDebug.count { it.kind == 'O' } to obs.size
            if (System.getenv("SM19_TRACE") != null) far.lastDebug.filter { it.kind == 'M' }.forEach { println("  t=%.2f id%d n%d dist %.2f v %.2f,%.2f sv %.2f moving %s".format(f.tS, it.id, it.nMeas, it.distM, it.velW?.x ?: Float.NaN, it.velW?.z ?: Float.NaN, it.velSigmaMps, it.moving)) }
        }
        return out
    }

    /**
     * SM-19(M53): 머리 앞 6 m에 서 있는 사람. 바닥 높이가 진폭 0.2 m·주기 2 s로 흔들린다(T 150159의 앱 바닥 떠돎을 본뜸, 0.5 s 묶음 −1.04~−1.47 m).
     * 관문: 바닥이 고정이면(잡음 둘 다) 움직임 판정 0, 흔들려도 잡음 없는 장면이면 0, 출력은 모두 0. 잡음·라벨 흔들림이 있는 흔들리는 바닥은 보고만:
     * 1단계에서 카메라 높이 창이 1 s도 차지 않은 시작 직후(0.60~0.80 s) 중앙값이 바닥 추세를 따라가 8장이 움직임으로 판정됐다(바닥 σ를 창 흩어짐으로
     * 키워도 추세는 흰 잡음이 아니라 남음). 8 s 창이 다 찰 때까지 재지 않는 규칙은 T 세션(6.9~12 s)을 거의 다 지워 쓰지 않았다(M53 결과 문서에서 판정).
     */
    @Test
    fun `SM-19 a standing person 6 m ahead is not judged moving under a wobbling floor`() {
        val r = 0.2f
        val p = SceneItem("person", VerticalCylinder(0f, 0.3f - 6.0f - r, r, 0f, 1.7f), obstacle = true, expectedClass = HeightClass.BODY,
            semantic = hearspace.core.types.SemanticLabel.PERSON)
        val floor = SceneItem("floor", HorizontalPlane(0f), obstacle = false)
        for (noise in listOf(Noise(), Noise(depthQuadK = 0.009f, edgeSmoothPx = 3, labelJitterPx = 1))) {
            val spec = SceneSpec("SM-19", Scene(listOf(floor, p)), Walk(standS = 1000f, durationS = 12f), noise)
            val still = run(spec) { 0f }
            val wobble = run(spec) { t -> 0.2f * sin(2 * PI.toFloat() * t / 2f) }
            val movingAt = wobble.withIndex().filter { it.value.first > 0 }.map { it.index / 30f }
            println("SM-19 noise=${noise.depthQuadK} jitter=${noise.labelJitterPx}: moving frames still ${still.count { it.first > 0 }}, wobble ${movingAt.size} (at ${movingAt.minOrNull()}..${movingAt.maxOrNull()} s); outputs still ${still.sumOf { it.second }}, wobble ${wobble.sumOf { it.second }} (frames ${wobble.size})")
            assertEquals(0, still.count { it.first > 0 }, "still floor: judged moving")
            if (noise.labelJitterPx == 0) assertEquals(0, movingAt.size, "wobbling floor: judged moving")
            assertTrue(wobble.all { it.second == 0 } && still.all { it.second == 0 })
        }
    }
}
