package hearspace.core.replay

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import hearspace.core.geometry.Vec3
import hearspace.core.session.RunLog
import hearspace.core.synth.Noise
import hearspace.core.synth.Scene
import hearspace.core.synth.SceneSpec
import hearspace.core.synth.Scenes
import hearspace.core.synth.SyntheticSessionWriter
import hearspace.core.synth.Walk
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.ObstacleSnapshot
import hearspace.core.types.PoseFrame
import java.io.File
import kotlin.math.abs

/**
 * 오프라인 재생 (§7.9, M8). 합성 세션을 디스크에 써서 실제 세션과 같은 경로로 재생한다.
 * 결과는 `build/test-output/replay/<변형>/{session,run}`에 남겨 Python 분석 도구 점검(`tools/analysis/test_analysis.py`)에 쓴다.
 */
class OfflineReplayTest {
    private val base = File(System.getProperty("hearspace.defaultConfig")).readText()
    private val root = File(System.getProperty("hearspace.testOutput"), "replay")

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

    private fun replay(name: String, spec: SceneSpec, slowMs: Float = 10f, runName: String = "run", extra: String = ""): File {
        val dir = File(root, name)
        val session = File(dir, "session")
        session.deleteRecursively()
        SyntheticSessionWriter.write(spec.generate(), session, "SYN")
        val overrides = overridesFor(spec).let { o -> if (extra.isBlank()) o else o.removeSuffix(" }") + ", $extra }" }
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
    fun `stage timing has one row per slow path result`() {
        val run = replay("clean", variants.getValue("clean"), runName = "run_stage")
        val slow = File(run, RunLog.SLOW_PATH_FILE).readLines().drop(1)
        val stage = File(run, RunLog.STAGE_TIMING_FILE).readLines()
        assertEquals(RunLog.header(RunLog.STAGE_TIMING_HEADER), stage.first())
        assertEquals(slow.map { it.substringBefore(',') }, stage.drop(1).map { it.substringBefore(',') })
        assertTrue(stage.drop(1).all { r -> r.split(',').drop(1).all { it.toLong() >= 0 } })
    }

    @Test
    fun `memory listener sees the same blocks and slow steps as the log files`() {
        val spec = variants.getValue("clean")
        val run = replay("clean", spec, runName = "run_listener")
        val blocks = ArrayList<Long>()
        val steps = ArrayList<SlowStep>()
        val listener = object : ReplayListener {
            override val captureVoxels = true
            override fun onSlowStep(step: SlowStep) { steps += step }
            override fun onBlock(tNs: Long, pose: PoseFrame, snapshot: ObstacleSnapshot?, g: GuidanceOutput) { blocks += tNs }
        }
        val overrides = overridesFor(spec)
        OfflineReplay(ConfigLoader.load(base, overrides), OfflineReplay.fixed(10f)).run(File(root, "clean/session"), listener)
        val logBlocks = guidance(run).map { it[0].toLong() }.distinct()
        assertEquals(logBlocks, blocks)
        assertEquals(File(run, RunLog.SLOW_PATH_FILE).readLines().size - 1, steps.size)
        assertTrue(steps.zipWithNext().all { (a, b) -> a.doneNs <= b.doneNs }, "slow steps in time order")
        assertTrue(steps.all { it.occupiedVoxels != null && it.doneNs > it.startNs })
        assertTrue(steps.any { it.occupiedVoxels!!.isNotEmpty() })
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
    fun `slow path rate cap limits the slow path frequency and still warns`() {
        // M13.4: 깊이 30 Hz, 처리 10 ms면 느린 경로가 약 30 Hz로 돈다. 상한 10 Hz면 시작 간격이 0.1 s 이상이고 안내는 그대로 난다.
        // 상한은 1/상한 + 파이프라인 지연(이 합성 세션의 정보 나이 중앙값 약 160 ms) ≤ `policy.maxInfoAgeMs`(300 ms)여야 한다:
        // 5 Hz(200 ms)면 정보 나이가 허용치를 넘어 안내 상태가 내내 UNKNOWN이었다(무음 = 원칙대로)
        val run = replay("clean", variants.getValue("clean"), 10f, "run_cap10", """ "slowPath": { "maxRateHz": 10 } """)
        val starts = File(run, RunLog.SLOW_PATH_FILE).readLines().drop(1).map { it.split(',')[1].toLong() }
        val gaps = starts.zipWithNext { a, b -> (b - a) / 1e9 }
        assertTrue(gaps.isNotEmpty() && gaps.min() >= 0.1 - 1e-6, "min start gap ${gaps.minOrNull()} s")
        assertTrue(guidance(run).any { it[7] == "WARN" }, "still warns")
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
