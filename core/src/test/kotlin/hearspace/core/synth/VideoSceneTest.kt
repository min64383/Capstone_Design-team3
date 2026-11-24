package hearspace.core.synth

import hearspace.core.audio.Hrtf
import hearspace.core.replay.BoxKey
import hearspace.core.replay.SceneObject
import hearspace.core.replay.SignalKey
import hearspace.core.replay.VideoDemoSettings
import hearspace.core.replay.VideoScene
import hearspace.core.replay.VideoSceneResult
import hearspace.core.replay.VideoSceneRunner
import hearspace.core.situation.Signals
import hearspace.core.types.Band
import hearspace.core.types.ConfigLoader
import hearspace.core.types.SemanticLabel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * M73 영상 데모 재현 실행기 합성 시험 VS-1~5(설계 5절). 장면은 월드 경로에서 상자를 거꾸로 그려 만든다(핀홀, 가로 1920 × 1080, 긴 변 화각 69.4°).
 * 설정은 앱과 같은 층(기본값 → 소리 방식 → 장착 → 상황 → 의미 조합)이다.
 */
class VideoSceneTest {
    private val dir = File(System.getProperty("hearspace.defaultConfig")).parentFile
    private val config = ConfigLoader.load(File(dir, "default.json").readText(),
        listOf("risk-continuous.override.json", "harness.override.json", "situation.override.json", "semantic-combo.override.json").map { File(dir, it).readText() })
    private val hrtf = Hrtf.parse(File(System.getProperty("hearspace.hrtfAsset")).readBytes())
    private val settings = VideoDemoSettings(preRollS = 1.0, poseHz = 30.0, snapshotIntervalMs = 80, snapshotLatencyMs = 80, velocityWindowS = 0.3,
        signalElevationDeg = 10f, labelWidth = 64, labelHeight = 48, screenRefreshMs = 200)
    private val w = 1920
    private val h = 1080
    private val fov = 69.4f
    private val f = (w / 2f) / kotlin.math.tan(Math.toRadians(fov / 2.0)).toFloat()
    /** 말 소리 대역: 0.6 s 무음(길이만 씀). */
    private val clip: (String) -> FloatArray? = { FloatArray(config.audio.sampleRate * 6 / 10) }

    /** 월드 경로(시각 s → 진행 방향 거리, 가운데 좌우)를 0.1 s마다 상자로 그린다. */
    private fun obj(id: Int, label: SemanticLabel, widthM: Float, heightM: Float, t0: Double, t1: Double, path: (Double) -> Pair<Float, Float>): SceneObject {
        val keys = ArrayList<BoxKey>()
        var t = t0
        while (t <= t1 + 1e-9) {
            val (d, x) = path(t)
            val hp = f * heightM / d
            keys += BoxKey(t, w / 2f + (x - widthM / 2f) / d * f, h / 2f - hp / 2f, w / 2f + (x + widthM / 2f) / d * f, h / 2f + hp / 2f)
            t += 0.1
        }
        return SceneObject(id, label, widthM, heightM, 0.5f, keys)
    }

    private fun scene(endS: Double, objects: List<SceneObject> = emptyList(), signal: List<SignalKey> = emptyList()) =
        VideoScene("t", "t", 0.0, endS, w, h, fov, 1.3f, objects, signal)

    private fun run(s: VideoScene): VideoSceneResult = VideoSceneRunner(s, config, settings, hrtf, clip).run()

    private fun texts(r: VideoSceneResult) = r.speech.map { it.text }

    @Test
    fun `VS-1 an approaching person is unseen beyond 5 m, spoken once, and stopped before 1 m`() {
        val person = obj(1, SemanticLabel.PERSON, 0.5f, 1.7f, 0.0, 6.2) { t -> (8.0 - 1.2 * t).toFloat() to 0f }
        val r = run(scene(6.2, listOf(person)))
        assertTrue(r.truth.filter { it.alongM > 5f }.all { it.band == null }, "no command beyond map.radiusM")
        assertEquals(1, texts(r).count { it == "사람 다가옴" }, r.speech.toString())
        assertTrue(texts(r).none { it == "차 다가옴" })
        val firstStop = r.truth.first { it.band == Band.STOP }
        assertTrue(firstStop.alongM >= 1f, "first STOP at ${firstStop.alongM} m")
    }

    @Test
    fun `VS-2 an approaching car is spoken once`() {
        val car = obj(1, SemanticLabel.VEHICLE, 1.8f, 1.6f, 0.0, 3.8) { t -> (12.0 - 3.0 * t).toFloat() to 0f }
        val r = run(scene(3.8, listOf(car)))
        assertEquals(1, texts(r).count { it == "차 다가옴" }, r.speech.toString())
        assertTrue(texts(r).none { it == "사람 다가옴" })
    }

    @Test
    fun `VS-3 a person crossing 2 m ahead is not spoken`() {
        val person = obj(1, SemanticLabel.PERSON, 0.5f, 1.7f, 0.0, 6.0) { t -> 2f to (-3.6 + 1.2 * t).toFloat() }
        val r = run(scene(6.0, listOf(person)))
        assertTrue(texts(r).none { it.endsWith("다가옴") }, r.speech.toString())
    }

    @Test
    fun `VS-4 red then green speaks red once and the change once within speakWithinS`() {
        val r = run(scene(7.4, signal = listOf(SignalKey(0.0, "RED"), SignalKey(3.0, "OFF"), SignalKey(3.4, "GREEN"))))
        assertEquals(1, texts(r).count { it == Signals.RED_TEXT }, r.speech.toString())
        val green = r.speech.filter { it.text == Signals.GREEN_TEXT }
        assertEquals(1, green.size, r.speech.toString())
        val tS = green.single().startNs / 1e9
        assertTrue(tS >= 3.4 && tS <= 3.4 + config.signal.speakWithinS, "green said at $tS s")
    }

    @Test
    fun `VS-5 the runner never asks the truth after now`() {
        val person = obj(1, SemanticLabel.PERSON, 0.5f, 1.7f, 0.0, 6.2) { t -> (8.0 - 1.2 * t).toFloat() to 0f }
        var asked = 0
        VideoSceneRunner(scene(6.2, listOf(person)), config, settings, hrtf, clip) { askedNs, nowNs ->
            asked++
            assertTrue(askedNs <= nowNs, "asked $askedNs > now $nowNs")
        }.run()
        assertTrue(asked > 0)
    }
}
