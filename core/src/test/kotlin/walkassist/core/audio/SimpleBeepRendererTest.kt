package walkassist.core.audio

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import walkassist.core.types.AudioCmd
import walkassist.core.types.Band
import walkassist.core.types.Config
import walkassist.core.types.ConfigLoader
import walkassist.core.types.SoundKind
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

class SimpleBeepRendererTest {

    private val config: Config = ConfigLoader.load(File(System.getProperty("walkassist.defaultConfig")).readText())

    private fun renderer() = SimpleBeepRenderer(config.audio, config.policy)

    private fun renderBlock(azimuthDeg: Float): ShortArray {
        val out = ShortArray(config.audio.blockSize * 2)
        renderer().render(AudioCmd(1, azimuthDeg, 1.5f, Band.WARN, SoundKind.FLOOR_PULSE, 0f), out)
        return out
    }

    @Test
    fun `lateral is linear in azimuth and rear folds to front mirror`() {
        val r = renderer()
        val cases = listOf(
            0f to (0.0 to false),
            90f to (1.0 to false),
            -45f to (-0.5 to false),
            135f to (0.5 to true),
            -135f to (-0.5 to true),
            180f to (0.0 to true),
            -270f to (1.0 to false),
        )
        for ((az, expected) in cases) {
            val cue = r.stereoCue(az)
            assertEquals(expected.first, cue.lateral, 1e-9, "lateral at $az°")
            assertEquals(expected.second, cue.rear, "rear at $az°")
        }
    }

    @Test
    fun `front is identical in both ears`() {
        val out = renderBlock(0f)
        val left = ShortArray(config.audio.blockSize) { out[it * 2] }
        val right = ShortArray(config.audio.blockSize) { out[it * 2 + 1] }
        assertArrayEquals(left, right)
    }

    @Test
    fun `right source reaches left ear later by ITD`() {
        val out = renderBlock(45f)
        val maxItdFrames = (config.audio.maxItdMs * config.audio.sampleRate / 1000f).roundToInt()
        val expectedDelay = (0.5 * maxItdFrames).roundToInt()
        // 왼쪽 = 오른쪽을 expectedDelay만큼 늦춘 것 × (왼쪽 이득 / 오른쪽 이득). 반올림 오차 1 이내
        val panAngle = 1.5 * PI / 4.0
        val ratio = cos(panAngle) / sin(panAngle)
        for (i in expectedDelay until config.audio.blockSize) {
            assertEquals(out[(i - expectedDelay) * 2 + 1] * ratio, out[i * 2].toDouble(), 1.0, "frame $i")
        }
    }
}
