package hearspace.core.audio

import hearspace.core.types.*
import java.io.File
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.sin

/** 합성 상대 거리 관측으로 접근/이벤트 경계와 수명 관리를 확인한다. */
class RiskSoundGeneratorTest {
    private val config = ConfigLoader.load(File(System.getProperty("hearspace.defaultConfig")).readText(),
        """{"sonify":{"mode":"RISK_CONTINUOUS"}}""")
    private val block = FloatArray(config.audio.blockSize)
    private val blockNs = config.audio.blockSize * 1_000_000_000L / config.audio.sampleRate
    private fun cmd(distanceM: Float, ageMs: Float = 0f, corridor: Boolean = true) =
        AudioCmd(1, 0f, distanceM, if (distanceM < config.policy.stopM) Band.STOP else Band.WARN,
            SoundKind.FLOOR_PULSE, ageMs, corridor)

    @Test fun staticAnnouncesOnceAndJitterDoesNotKeepItActive() {
        val generator = RiskSoundGenerator(config)
        generator.render(cmd(1.8f), 0L, 1f, block)
        assertTrue(generator.active)
        for (i in 1..750) generator.render(cmd(1.8f + 0.002f * sin(i.toFloat())), i * blockNs, 1f, block)
        assertFalse(generator.active)
        assertTrue(block.all { it == 0f })
    }
    @Test fun sameDistanceFastApproachHasGreaterRisk() {
        fun approach(speedMps: Float): Pair<Float, Float> {
            val generator = RiskSoundGenerator(config)
            for (i in 0..200) {
                val timeS = i * blockNs / 1e9f
                val endS = 200 * blockNs / 1e9f
                generator.render(cmd(2f + speedMps * (endS - timeS)), i * blockNs, 1f, block)
            }
            assertTrue(generator.active)
            assertEquals(speedMps, generator.closingMps, 0.002f)
            return generator.risk to generator.ttcS
        }
        val slow = approach(0.2f); val fast = approach(0.8f)
        assertTrue(fast.first > slow.first)
        assertEquals(2.5f, fast.second, 0.02f)
    }
    @Test fun stopRemainsActiveButOutsideCorridorCanBecomeQuiet() {
        for (corridor in listOf(true, false)) {
            val generator = RiskSoundGenerator(config)
            for (i in 0..750) generator.render(cmd(0.7f, corridor = corridor), i * blockNs, 1f, block)
            assertEquals(corridor, generator.active)
            if (!corridor) assertTrue(block.all { it == 0f })
        }
    }
    @Test fun expiredCommandIsSilentAndFreshReappearanceAnnounces() {
        val generator = RiskSoundGenerator(config)
        generator.render(cmd(1.8f), 0L, 1f, block)
        generator.render(cmd(1.8f, config.policy.maxInfoAgeMs + 1), blockNs, 1f, block)
        assertTrue(block.all { it == 0f })
        generator.render(cmd(1.8f), 2 * blockNs, 1f, block)
        assertTrue(generator.active)
    }
    @Test fun removalReleasesAndTimeRewindRestartsState() {
        val generator = RiskSoundGenerator(config)
        for (i in 0..100) generator.render(cmd(0.7f), i * blockNs, 1f, block)
        for (i in 101..900) generator.render(null, i * blockNs, 1f, block)
        assertTrue(block.all { it == 0f })
        generator.render(cmd(1.8f), 0L, 1f, block)
        assertTrue(generator.active)
    }
    @Test fun unknownAndExpirationMuteRendererWhileAlertsStillPlay() {
        val hrtf = Hrtf.parse(File(System.getProperty("hearspace.hrtfAsset")).readBytes())
        val r = BinauralRenderer(config, hrtf)
        val output = FloatArray(2 * block.size)
        fun render(timeNs: Long, state: GuidanceState, c: AudioCmd?, alert: AlertKind? = null) =
            r.render(GuidanceOutput(timeNs, state, listOfNotNull(c), 0f, alert), output)
        for (i in 0..40) render(i * blockNs, GuidanceState.NORMAL, cmd(0.7f))
        render(41 * blockNs, GuidanceState.NORMAL, cmd(0.7f, config.policy.maxInfoAgeMs + 1))
        assertTrue(output.all { it == 0f })
        render(42 * blockNs, GuidanceState.UNKNOWN, cmd(0.7f))
        assertTrue(output.all { it == 0f })
        render(43 * blockNs, GuidanceState.UNKNOWN, null, AlertKind.UNKNOWN)
        assertTrue(output.any { abs(it) > 0f })
        for (i in output.indices step 2) assertEquals(output[i], output[i + 1])
    }
    @Test fun stopDucksOtherSource() {
        val normal = RiskSoundGenerator(config)
        val ducked = RiskSoundGenerator(config)
        for (i in 0..40) {
            normal.render(cmd(1.8f), i * blockNs, 1f, block)
            val expected = block.copyOf()
            ducked.render(cmd(1.8f), i * blockNs, Sounds.db(config.sonify.duckDb), block)
            for (j in block.indices) assertEquals(expected[j] * Sounds.db(config.sonify.duckDb), block[j], 1e-5f)
        }
    }
}
