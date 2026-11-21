package hearspace.core.synth

import hearspace.core.audio.BinauralRenderer
import hearspace.core.audio.Hrtf
import hearspace.core.types.AlertKind
import hearspace.core.types.AudioCmd
import hearspace.core.types.Band
import hearspace.core.types.ConfigLoader
import hearspace.core.types.GuidanceOutput
import hearspace.core.types.GuidanceState
import hearspace.core.types.SoundKind
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SpeechGainTest {
    private val config = ConfigLoader.load(
        File(System.getProperty("hearspace.defaultConfig")).readText(),
        """{"sonify":{"mode":"RISK_CONTINUOUS","mapping":"DISTANCE_HEIGHT"},"audio":{"masterGainDb":-30,"ttsObstacleGain":0.35}}""",
    )
    private val hrtf = syntheticHrtf()
    private val n = config.audio.blockSize
    private val blockNs = n * 1_000_000_000L / config.audio.sampleRate

    @Test
    fun spatialToneFadesOnBothChannelsAndRecovers() {
        val reference = BinauralRenderer(config, hrtf)
        val actual = BinauralRenderer(config, hrtf)
        val command = AudioCmd(1, 30f, config.policy.stopM, Band.STOP, SoundKind.FLOOR_PULSE, 0f)
        val baseline = FloatArray(2 * n)
        val heard = FloatArray(2 * n)
        val gain = config.audio.ttsObstacleGain
        repeat(14) { block ->
            val frame = GuidanceOutput(block * blockNs, GuidanceState.NORMAL, listOf(command), 0f, null)
            reference.render(frame, baseline)
            actual.renderWithSpeech(frame, heard, speechActive = block in 5..9)
            if (block >= 5) assertTrue(baseline.any { it != 0f })
            for (i in baseline.indices) {
                val progress = (i / 2 + 1f) / n
                val expected = when (block) {
                    5 -> 1f + (gain - 1f) * progress
                    in 6..9 -> gain
                    10 -> gain + (1f - gain) * progress
                    else -> 1f
                }
                assertEquals(baseline[i] * expected, heard[i], 1e-6f)
            }
        }
    }

    @Test
    fun statusAlertKeepsItsLevelDuringSpeech() {
        val reference = BinauralRenderer(config, hrtf)
        val actual = BinauralRenderer(config, hrtf)
        val baseline = FloatArray(2 * n)
        val heard = FloatArray(2 * n)
        var audible = false
        repeat(24) { block ->
            val alert = if (block == 0) AlertKind.START else null
            val frame = GuidanceOutput(block * blockNs, GuidanceState.NORMAL, emptyList(), 0f, alert)
            reference.render(frame, baseline)
            actual.renderWithSpeech(frame, heard, speechActive = true)
            assertArrayEquals(baseline, heard, 0f)
            audible = audible || baseline.any { it != 0f }
        }
        assertTrue(audible)
    }

    private fun syntheticHrtf(): Hrtf {
        val bytes = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        bytes.putInt(0x52484157).putInt(1).putInt(config.audio.sampleRate).putInt(1).putInt(2)
        bytes.putFloat(-90f).putFloat(0.7f).putFloat(0.3f)
        bytes.putFloat(90f).putFloat(0.3f).putFloat(0.7f)
        return Hrtf.parse(bytes.array())
    }
}
