package hearspace.core.pipeline

import hearspace.core.synth.Scenes
import hearspace.core.types.ConfigLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** 느린 경로 단계별 처리 시간(IMPROVE_SPEC §3-3, M11): 주입한 시계로 단계마다 정확히 나뉘는지. */
class StageTimingTest {
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText())

    @Test
    fun `each stage gets the time between its clock reads`() {
        var now = 0L
        val slow = SlowPath(config) { now.also { now += 1_000 } } // 읽을 때마다 1 µs씩 흐르는 시계
        var withFloor = 0
        for (f in Scenes.SC02.generate().frames) {
            val d = f.depth ?: continue
            val s = slow.process(d, f.truthHead.headingW)
            val t = slow.lastStageNs
            if (s.floorY == null) {
                // 바닥을 모르면 군집하지 않는다(시계 3번 읽음: 시작, 맵 뒤, 추적 뒤)
                assertEquals(StageTimes(1_000, 0, 1_000), t)
            } else {
                withFloor++
                assertEquals(StageTimes(1_000, 1_000, 1_000), t)
            }
        }
        assertTrue(withFloor > 0, "scene must reach a floor estimate")
    }

    @Test
    fun `stage times do not change the result`() {
        var now = 0L
        val fake = SlowPath(config) { now.also { now += 123_456 } }
        val real = SlowPath(config)
        for (f in Scenes.SC02.generate().frames) {
            val d = f.depth ?: continue
            assertEquals(real.process(d, f.truthHead.headingW), fake.process(d, f.truthHead.headingW))
        }
    }
}
