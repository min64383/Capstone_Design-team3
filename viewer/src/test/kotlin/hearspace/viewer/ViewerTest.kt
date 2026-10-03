package hearspace.viewer

import hearspace.core.replay.OfflineReplay
import hearspace.core.session.RunLog
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.awt.Dimension
import java.awt.image.BufferedImage
import java.io.File
import kotlin.math.abs

/**
 * 평가 GUI의 화면 아닌 부분(IMPROVE_SPEC §10, M11). 실제 세션은 git에 있는 정답 세션(testdata)이라 기기 없이 돈다.
 * 알고리즘 합격 기준은 core의 합성 장면 테스트이고, 여기서는 GUI 배관(재생·소리·찾기·평가 저장)만 확인한다.
 */
class ViewerTest {
    private val s02 = File(Repo.sessions, "20261003_130815_S01") // M10 S02 2회, 앞면 정답 z 2.39

    @Test
    fun `time lookups never return a later value`() {
        val t = longArrayOf(10, 20, 20, 30)
        assertEquals(-1, lastAtOrBefore(t, 9))
        assertEquals(0, lastAtOrBefore(t, 10))
        assertEquals(0, lastAtOrBefore(t, 19))
        assertEquals(2, lastAtOrBefore(t, 20))
        assertEquals(3, lastAtOrBefore(t, 1_000))
        assertEquals(-1, lastAtOrBefore(LongArray(0), 5))
    }

    @Test
    fun `feedback round trips and the config hash ignores formatting`() {
        val fb = Feedback("sid", "S02", "v1", """{ "map": { "voxelSizeM": 0.075 } }""",
            RatingItem.entries.associateWith { 4 }, listOf(Note(1.5, "왼쪽 상자가 오른쪽에서 들림")), "2026-10-03T12:00:00")
        val back = Feedback.parse(fb.toJson())
        assertEquals(fb.copy(overridesJson = back.overridesJson), back)
        assertEquals(Feedback.configHash(fb.overridesJson), Feedback.configHash(back.overridesJson))
        assertEquals(Feedback.configHash("""{"map":{"voxelSizeM":0.075}}"""), Feedback.configHash("{ \"map\" :\n { \"voxelSizeM\" : 0.075 } }"))
        assertTrue(Feedback.configHash("{}") != Feedback.configHash("""{"map":{"voxelSizeM":0.1}}"""))
        assertThrows<IllegalArgumentException> { fb.copy(ratings = mapOf(RatingItem.DIRECTION to 6)) }
    }

    @Test
    fun `feedback is saved under the session id`(@TempDir dir: File) {
        val fb = Feedback("20261003_130815_S01", "S02", "", "{}", mapOf(RatingItem.DIRECTION to 3), emptyList(), "2026-10-03T12:34:56")
        val f = fb.save(dir)
        assertEquals(File(dir, "20261003_130815_S01/20261003T123456.json"), f)
        assertEquals(fb, Feedback.parse(f.readText()))
    }

    @Test
    fun `all views paint at any time without errors`() {
        val r = ReplayRunner.run(File(Repo.sessions, "20261003_131039_S01"), "{}", ReplayRunner.loadHrtf()) // S07: 물체가 시야 밖으로
        val m = ViewerModel().apply { setResult(r) }
        val views = listOf(CameraView(m) to Dimension(360, 480), TopView(m) to Dimension(420, 480), TimelineView(m) {} to Dimension(900, 230))
        for (k in 0..10) {
            m.setTime(r.t0Ns + (k / 10.0 * r.durationS * 1e9).toLong())
            for ((v, d) in views) {
                v.setSize(d)
                val img = BufferedImage(d.width, d.height, BufferedImage.TYPE_INT_RGB)
                img.createGraphics().also { v.paint(it) }.dispose()
                val bg = img.getRGB(0, d.height - 1)
                assertTrue((0 until d.width step 7).any { x -> (0 until d.height step 7).any { y -> img.getRGB(x, y) != bg } }, "${v.javaClass.simpleName} drew nothing at $k")
            }
        }
    }

    @Test
    fun `replay of a git session gives aligned audio, blocks, truth and lookups`(@TempDir dir: File) {
        val r = ReplayRunner.run(s02, "{}", ReplayRunner.loadHrtf())
        println("viewer rerun ${r.elapsedMs} ms, ${r.blocks.size} blocks, ${r.slow.size} slow steps, ${"%.2f".format(r.durationS)} s")
        // 목표는 15초 세션 ≤ 5 s(IMPROVE_SPEC §10.4, M11 보고). PC마다 속도가 달라 여기서는 상식 범위만 본다
        assertTrue(r.elapsedMs < 30_000, "rerun took ${r.elapsedMs} ms")

        // 소리: 블록마다 blockSize 프레임, 무음이 아님(경고 구간을 지나는 세션)
        assertEquals(r.blocks.size * 2 * r.blockSize, r.audio.size)
        assertTrue(r.audio.maxOf { abs(it) } > 0.01f)

        // 실행 로그 파일과 같은 블록
        val config = ConfigLoader.load(Repo.defaultConfig.readText(), "{}")
        OfflineReplay(config, OfflineReplay.fixed(ReplayRunner.SLOW_PATH_MS)).run(s02, dir)
        val logBlocks = File(dir, RunLog.GUIDANCE_FILE).readLines().drop(1).map { it.substringBefore(',').toLong() }.distinct()
        assertEquals(logBlocks, r.blocks.map { it.tNs })

        // 정답: 장면 S02, 출발 때 정답 앞면까지 2.39 m(머리 원점), 걸어가며 줄어든다
        assertEquals("S02", r.scene)
        val truthD = r.truthDistM.filter { !it.isNaN() }
        assertTrue(abs(truthD.first() - 2.39f) < 0.05f, "first truth distance ${truthD.first()}")
        assertTrue(truthD.last() < 1.2f, "last truth distance ${truthD.last()}")

        // 찾기는 과거만
        for (k in 0..20) {
            val t = r.t0Ns + (k / 20.0 * r.durationS * 1e9).toLong()
            r.rgbRowAt(t)?.let { assertTrue(it.sysElapsedNs <= t) }
            r.depthRowAt(t)?.let { assertTrue(it.sysElapsedNs <= t) }
            r.slowAt(t)?.let { assertTrue(it.doneNs <= t && it.applied) }
            val i = r.blockIndexAt(t)
            if (i >= 0) assertTrue(r.blocks[i].tNs <= t)
        }
        // 소리 위치 ↔ 시각 왕복
        val t = r.t0Ns + 3_000_000_000L
        assertTrue(abs(r.timeOfFrame(r.frameOfTime(t)) - t) < 1_000_000_000L / r.sampleRate + 1)
    }
}
