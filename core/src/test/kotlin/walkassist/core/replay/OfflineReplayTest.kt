package walkassist.core.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import walkassist.core.geometry.Vec3
import walkassist.core.session.RunLog
import walkassist.core.synth.Noise
import walkassist.core.synth.Scene
import walkassist.core.synth.SceneSpec
import walkassist.core.synth.Scenes
import walkassist.core.synth.SyntheticSessionWriter
import walkassist.core.synth.Walk
import walkassist.core.types.ConfigLoader
import java.io.File
import kotlin.math.abs

/**
 * 오프라인 재생 (§7.9, M8). 합성 세션을 디스크에 써서 실제 세션과 같은 경로로 재생한다.
 * 결과는 `build/test-output/replay/<변형>/{session,run}`에 남겨 Python 분석 도구 점검(`tools/analysis/test_analysis.py`)에 쓴다.
 */
class OfflineReplayTest {
    private val base = File(System.getProperty("walkassist.defaultConfig")).readText()
    private val root = File(System.getProperty("walkassist.testOutput"), "replay")

    /** 보행선 4 m 앞 상자, 2 s 정지 후 3.5 m 걷기(경고 구간 1.0~2.5 m를 모두 지남). */
    private fun box4(walk: Walk.() -> Walk = { this }, noise: Noise = Noise()) = SceneSpec(
        "M8-BOX4",
        Scene(Scenes.SC01.scene.items + Scenes.boxOnLine(4f)),
        Walk(durationS = 5.5f).walk(),
        noise,
    )

    /** 합성 변형: 이름 → 장면. 파지 오프셋은 설정에도 그대로 넣는다(분석 도구가 replay_info로 읽음). */
    private val variants = mapOf(
        "clean" to box4(),
        "scale_plus10" to box4(noise = Noise(depthScaleBias = 0.1f)),
        "pose_noise" to box4(noise = Noise(posePosStdM = 0.01f, poseRotStdDeg = 1.5f, seed = 7L)),
        "grip_right" to box4(walk = { copy(gripOffsetM = Vec3(-0.2f, 0.5f, -0.3f)) }),
    )

    private fun overridesFor(spec: SceneSpec): String {
        val g = spec.walk.gripOffsetM
        return """{ "head": { "offsetFromCameraM": [${g.x}, ${g.y}, ${g.z}] } }"""
    }

    private fun replay(name: String, spec: SceneSpec, slowMs: Float = 10f, runName: String = "run"): File {
        val dir = File(root, name)
        val session = File(dir, "session")
        session.deleteRecursively()
        SyntheticSessionWriter.write(spec.generate(), session, "SYN")
        val overrides = overridesFor(spec)
        val out = File(dir, runName)
        OfflineReplay(ConfigLoader.load(base, overrides), OfflineReplay.fixed(slowMs)).run(session, out, overrides, "fixed:${slowMs}ms")
        return out
    }

    private fun guidance(run: File) = File(run, RunLog.GUIDANCE_FILE).readLines().drop(1).map { it.split(',') }

    @Test
    fun `same input gives identical logs`() {
        val a = replay("clean", variants.getValue("clean"), runName = "run_a")
        val b = replay("clean", variants.getValue("clean"), runName = "run_b")
        for (f in listOf(RunLog.SLOW_PATH_FILE, RunLog.GUIDANCE_FILE, RunLog.OBSTACLES_FILE)) {
            assertEquals(File(a, f).readText(), File(b, f).readText(), f)
        }
    }

    @Test
    fun `clean synthetic box warns straight ahead`() {
        val rows = guidance(replay("clean", variants.getValue("clean")))
        val warns = rows.filter { it[7] == "WARN" }
        assertTrue(warns.isNotEmpty())
        val maxAz = warns.maxOf { abs(it[5].toFloat()) }
        assertTrue(maxAz < 3f, "max |az| $maxAz")
    }

    @Test
    fun `slow path time adds to information age`() {
        fun medianAge(run: File) = guidance(run).mapNotNull { it[9].toFloatOrNull() }.sorted().let { it[it.size / 2] }
        val fast = medianAge(replay("clean", variants.getValue("clean"), 10f, "run_slow10"))
        val slow = medianAge(replay("clean", variants.getValue("clean"), 60f, "run_slow60"))
        // 처리 시간 증가분(50 ms) + 워커가 바빠 깊이가 기다리는 시간(최대 처리 시간 60 ms)
        assertTrue(slow - fast in 50f..110f, "median info age $fast → $slow ms")
    }

    @Test
    fun `write all variants for analysis tools`() {
        for ((name, spec) in variants) {
            val run = replay(name, spec)
            assertTrue(File(run, RunLog.GUIDANCE_FILE).length() > 0, name)
            assertTrue(File(run.parentFile, "session/annotations/obstacles.json").isFile, name)
        }
    }
}
