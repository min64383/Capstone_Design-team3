package hearspace.viewer

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import javax.sound.sampled.AudioSystem
import kotlin.math.abs

class ObjectCueReplayTest {
    @Test fun `recorded replay mixes events on the same timeline and exports the same PCM`(@TempDir temp: File) {
        val session = File(Repo.sessions, "20261003_130815_S01")
        val hrtf = ReplayRunner.loadHrtf()
        val baseline = ReplayRunner.run(session, "{}", hrtf)
        val dir = File(temp, "cues")
        val result = ReplayRunner.run(session, "{}", hrtf, dir, objectCueSettings = ObjectCueOptions.load())
        assertEquals(baseline.blocks.size, result.blocks.size)
        assertEquals(baseline.audio.size, result.audio.size)
        assertEquals(baseline.blocks.map { it.g }, result.blocks.map { it.g })
        assertTrue(result.audio.all { it.isFinite() && abs(it) <= 1f })
        assertTrue(result.objectCues.isNotEmpty(), "Recorded session should produce events")
        assertTrue(result.objectCues.any { it.disposition == "PLAYED" })
        assertFalse(result.audio.contentEquals(baseline.audio))
        assertTrue(File(dir, "object-cues.csv").readText().contains("APPEAR"))
        AudioSystem.getAudioInputStream(File(dir, "audio.wav")).use { wav ->
            val bytes = wav.readBytes()
            assertEquals(result.audio.size * 2, bytes.size)
            for (i in result.audio.indices) {
                val expected = (result.audio[i].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt().toShort().toInt()
                val actual = ((bytes[2 * i].toInt() and 255) or (bytes[2 * i + 1].toInt() shl 8)).toShort().toInt()
                assertEquals(expected, actual, "sample $i")
            }
        }
        println("Recorded cue test: ${result.objectCues.size} events, ${result.objectCues.count { it.disposition == "PLAYED" }} played")
    }
}
